<p align="center"><img src="../images/fyrql-banner.png" alt="FyrQL: embeddable federated SQL engine for the JVM" width="480"></p>

# FyrQL manual

This manual describes FyrQL 0.13.2 in full: its Java API and settings, the connectors and
what they push down, writing and loading, the configuration file, the JDBC driver, Arrow,
the Flight SQL server and the starter for Spring Boot, the SQL it takes and where it differs from DuckDB, how it
runs queries and how to observe them, with measurements. [Getting started](getting-started.md) is a shorter tour,
and the [README](../README.md) an overview; the [examples](../examples/README.md) are programs that run, each
with its output.

## Contents

- [Using the engine](#using-the-engine)
  - [Engine settings](#engine-settings)
  - [Sessions](#sessions)
- [Connectors](#connectors)
- [Writing and loading](#writing-and-loading)
  - [Files](#files)
  - [Formats](#formats)
  - [Loading](#loading)
  - [Tables](#tables)
- [File access](#file-access)
- [Configuration file](#configuration-file)
- [JDBC driver](#jdbc-driver)
  - [DBeaver](#dbeaver)
- [Arrow](#arrow)
- [Flight SQL server](#flight-sql-server)
  - [Python](#python)
  - [JDBC tools](#jdbc-tools)
- [Spring Boot](#spring-boot)
- [Supported SQL](#supported-sql)
  - [Time zone](#time-zone)
- [Differences from DuckDB](#differences-from-duckdb)
  - [Queries and functions](#queries-and-functions)
  - [Types and values](#types-and-values)
  - [Window functions](#window-functions)
  - [Sources and files](#sources-and-files)
  - [Execution](#execution)
- [Optimizer](#optimizer)
- [Code generation](#code-generation)
- [Parallel execution](#parallel-execution)
- [Time limits](#time-limits)
- [Memory budget and spilling](#memory-budget-and-spilling)
- [Observing queries](#observing-queries)
  - [EXPLAIN ANALYZE](#explain-analyze)
  - [JFR events](#jfr-events)
- [Performance](#performance)
  - [TPC-H at scale factor 1](#tpc-h-at-scale-factor-1)
  - [Compiled code and scans](#compiled-code-and-scans)
  - [Threads](#threads)
  - [Spilling](#spilling)
  - [Results through Arrow, Flight SQL and JDBC](#results-through-arrow-flight-sql-and-jdbc)
- [Compatibility](#compatibility)
- [Modules](#modules)
- [Building and testing](#building-and-testing)

## Using the engine

An application registers its sources and runs SQL over them. A table is named by
`source.schema.table`, or shorter: a shorter name is looked up in the default source and
schema first (`main` by default), and otherwise must be unique across the sources.

```java
record Order(long id, Integer customerId, BigDecimal amount) { }

try (FyrQL engine = FyrQL.builder()
        .register(CsvConnector.builder("csv").directory(Path.of("data")).build())
        .register(MemoryConnector.builder("mem")
                .table("orders", Order.class, orders, Map.of("amount", DataType.decimal(10, 2)))
                .build())
        .build()) {
    String sql = """
            SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
            FROM orders AS o JOIN customers AS c ON o.customerId = c.id
            GROUP BY ALL
            ORDER BY revenue DESC""";
    try (QueryResult result = engine.sql(sql)) {
        for (Object[] row : result.rows()) {
            System.out.println(Arrays.toString(row));
        }
    }
    System.out.print(engine.explain(sql));
}
```

With `data/customers.csv` holding the regions of three customers, and five orders, this
prints the rows and the plan:

```
[south, 1, 20.00]
[north, 3, 15.75]
Sort #2 DESC
  Project #0 AS region, #1 AS orders, #2 AS revenue
    Aggregate GROUP BY #0: count(*) AS "count_star()", sum(#1) AS "sum(o.amount)" [codegen]
      Project #3 AS "#group0", #0 AS "#argument0" [parallel]
        Join INNER ON l#1 = r#0 [codegen]
          Project #1 AS amount, CAST(#0 AS BIGINT) AS "#key0" [codegen]
            Scan mem.main.orders (customerId INTEGER, amount DECIMAL(10,2))
          Scan csv.main.customers (id BIGINT, region VARCHAR) [parallel]
```

The plan is the one the engine runs: the scan of the orders reads only the two columns
the query uses. With the module `fyrql-codegen` on the class path, as here, `[codegen]`
marks the nodes whose expressions or keys the engine compiles once enough rows pass; over
larger tables, `[codegen pipeline]` marks a chain of nodes that runs as one compiled loop;
see [Code generation](#code-generation). `[parallel]` marks the top of a chain that runs
on several threads when its source has several parts; see
[Parallel execution](#parallel-execution). `EXPLAIN ANALYZE`, or
`engine.explainAnalyze(sql)`, runs the query and shows what each node did; see
[Observing queries](#observing-queries). `engine.plan(sql)` returns the plan itself,
`engine.rows(sql)` all rows at once, with the dates and times as classes of `java.time`
(see [Time zone](#time-zone)). A query
that does not parse or bind throws an `SqlException` with the position in the text, for
example `Referenced column "x" not found in FROM clause! at line 1, column 33`.

A query with parameters `?` is prepared once and run with values: `engine.prepare(sql)`
parses and binds it, without running it, into a `PreparedQuery` with the columns of the
result and the types of the parameters, and `engine.execute(prepared, values)` or
`engine.rows(prepared, values)` runs it with a literal of each value in place of its
parameter. A parameter takes the type that a bare `NULL` takes in its place: that of the
other side of a comparison or of arithmetic, of the other values of `IN`, `BETWEEN`,
`CASE` and `COALESCE`, `BOOLEAN` as a condition, and that of `CAST(? AS type)`; as an
argument of a function, the first type the function takes of `BIGINT`, `DOUBLE`,
`INTEGER` and so on, `VARCHAR` last. A parameter without such a context, as in
`SELECT ?`, is an error that asks for `CAST(? AS type)`, as is one where a constant is
needed, such as in `LIMIT`, and one of a type with a `MAP`, which has no literal. A value
is of the class that `rows()` gives for the type of its parameter, such as a `LocalDate`
for a `DATE`, and `null` is `NULL`.

Plans can also be built in Java from the nodes of `PlanNode`, starting with
`engine.scan(TableRef.of("mem", "orders"))`, and run with `engine.execute(plan)`. The
builder takes further settings, which [Engine settings](#engine-settings) lists. Before a
plan runs, the [optimizer](#optimizer) rewrites it, every scan is narrowed to the columns
in use, and with pushdown (the default, or the system property `fyrql.pushdown` set to
`on` or `off`) the source of a scan takes over what it can of the filter, aggregation,
sort and limit above it; `explain` shows that as `[pushed: ...]`. A source may only take
over what it evaluates exactly as the engine would, and the tests run every query with and
without pushdown.

Every result is checked against DuckDB in the tests: the same SQL text, or the plan
rendered as SQL, runs in DuckDB over a copy of the same data, and the types and rows must
agree.

### Engine settings

The settings of an engine come from the methods of `FyrQL.Builder`, from the section
`engine` of the [configuration file](#configuration-file), with sizes such as `512MB`,
and from the URL or the properties of a connection of the [JDBC driver](#jdbc-driver);
the [Flight SQL server](#flight-sql-server) reads them from its configuration file. A few
take their default from a system property.

| Builder method | Key in a file or URL | Default | Meaning |
| --- | --- | --- | --- |
| `defaultSource(String)` | `defaultSource` | the only source, if exactly one is registered | The source in which names of fewer than three parts are looked up first, as every [session](#sessions) starts |
| `defaultSchema(String)` | `defaultSchema` | `main` | The schema of the default source in which one-part names are looked up first, as every session starts |
| `functions(FunctionRegistry)` | - | `FunctionRegistry.standard()` | The scalar functions of queries; an application adds its own to a registry of the built-in ones |
| `batchSize(int)` | `batchSize` | 2048 | The rows of a batch |
| `memoryBudget(long)` | `memoryBudget` | no limit | The bytes that the queries of the engine hold together; see [Memory budget and spilling](#memory-budget-and-spilling) |
| `spill(boolean)` | `spill` | `true` | Whether aggregations, sorts and windows spill to disk above the budget, instead of failing |
| `spillDirectory(Path)` | `spillDirectory` | the temporary directory of the JVM | The directory under which the engine creates its directory for spilling |
| `codegen(CodegenMode)` | `codegen`: `off`, `auto` or `always` | the system property `fyrql.codegen`, or `AUTO` | When expressions, keys and pipelines are compiled; see [Code generation](#code-generation) |
| `codegenThreshold(long)` | `codegenThreshold` | 10,000 | The rows after which an operator compiles in `AUTO`, and the estimated rows from which a chain runs as a compiled pipeline |
| `pushdown(boolean)` | `pushdown` | the system property `fyrql.pushdown` (`on` or `off`), or on | Whether sources take over filters, aggregations, sorts and limits, and a database the joins of its own tables |
| `threads(int)` | `threads` | the system property `fyrql.threads`, or the number of processors | The most threads on which one query runs at a time; see [Parallel execution](#parallel-execution) |
| `preserveOrder(boolean)` | `preserveOrder` | `true` | Whether rows that no `ORDER BY` orders come in the order of their sources, as on one thread |
| `implicitSources(boolean)` | `implicitSources`, in a file only | `true` | Whether the engine adds a source for each format of files on the class path; see [Formats](#formats) |
| `timeZone(ZoneId)` | `timeZone` | the time zone of the JVM | The time zone with which every [session](#sessions) starts; see [Time zone](#time-zone) |
| `fileAccess(FileAccess)` | `fileAccess`: `none`, `read` or `readWrite` | `READ_WRITE`; `none` in the Flight SQL server | What statements may do with the files and objects they name; see [File access](#file-access) |
| `allowedLocations(List<String>)` | `allowedLocations` | anywhere | The directories and prefixes, such as `s3://lake/`, under which they may do it |
| `queryTimeout(Duration)` | `queryTimeout`, such as `30s` | no limit | How long a query may run that asks for no timeout of its own, as every [session](#sessions) starts; see [Time limits](#time-limits) |
| `maxQueryTimeout(Duration)` | `maxQueryTimeout` | no limit | How long any query may run, also one that asks for a longer timeout |
| `classLoader(ClassLoader)` | - | the loader of the engine | The class loader in which the engine looks for the factories of the implicit sources |

### Sessions

The settings that `SET` changes belong to a session, `net.fyrql.Session`: the time zone,
the search path (the default source and schema) and the timeout of a statement. The
engine has a session of its own, `engine.session()`, in which its methods without a
session run, so that `engine.update("SET TimeZone = 'UTC'")` changes the time zone of the
next `engine.sql(...)`. `engine.newSession()` starts another one with the settings of the
builder, e.g. one per client. A session has the methods of the engine that parse, bind and
run SQL (`sql`, `rows`, `update`, `statement`, `prepare`, `execute`, `explain` and
`explainAnalyze`), needs no closing and may be used from several threads; `statement`
binds a statement without running it, as a `SqlStatement` with the columns of its result,
which `execute` runs in a `QueryContext` of the caller that can cancel it. Every
connection of the [JDBC driver](#jdbc-driver) is a session, and so is every client of the
[Flight SQL server](#flight-sql-server).

```java
Session berlin = engine.newSession();
berlin.update("SET TimeZone = 'Europe/Berlin'");
berlin.set("statement_timeout", "30s");
berlin.use(new SearchPath("shop", "main"));
List<Object[]> rows = berlin.rows("SELECT current_setting('TimeZone'), current_setting('search_path')");
// [Europe/Berlin, shop.main]; engine.timeZone() is still the zone of the builder
```

| Setting | Values | Starts with | Meaning |
| --- | --- | --- | --- |
| `TimeZone` | a zone such as `Europe/Berlin` | `timeZone` of the builder | The zone of `TIMESTAMPTZ`; see [Time zone](#time-zone) |
| `search_path` | `source`, `source.schema`, a schema of the default source, or empty | `defaultSource` and `defaultSchema` | Where names of fewer than three parts are looked up first |
| `statement_timeout` | milliseconds, or a number with `ms`, `s`, `min`, `h` or `d`; `0` for none | `queryTimeout` | The timeout of a statement that asks for none; see [Time limits](#time-limits) |

The statements of a session, with the names of the settings in any case:

- `SET name = value`, `SET name TO value` and `SET SESSION name = value`, with a string, a
  number or a name as the value; `SET TIME ZONE 'zone'`; `SET name TO DEFAULT` is
  `RESET name`.
- `RESET name` returns a setting to the value of the builder, `RESET ALL` all of them.
- `SHOW name` gives one row of one column named after the setting, `SHOW ALL` one row per
  setting with the columns `name` and `setting`; `current_setting('name')` gives the value
  in an expression.
- `USE source` and `USE source.schema` set the search path, as `SET search_path` does.

A `search_path` of one part is a source, with the schema `main`, or else a schema of the
default source, as `USE` in DuckDB; an empty one leaves no default source. `SHOW` writes
it as `source.schema`, a part with a dot in double quotes, and a timeout in the largest
unit that gives a whole number, such as `30s` or `1500ms`. `SET LOCAL` and more than one
value in one `SET` are not supported, and another name fails with `unrecognized
configuration parameter`. A prepared query keeps the search path of the session it was
prepared in. The memory budget, the threads, code generation, pushdown and the access to
files belong to the engine, not to a session.

## Connectors

Each kind of source is a connector in a module of its own, and every connector serves its
tables in the schema `main` unless it says otherwise. The connectors of CSV, JSON and
Parquet also write files and read them with the columns of a table, and those of memory
and JDBC write tables; see [Writing](#writing-and-loading).

- `fyrql-connector-memory`: Java collections of records, beans or rows; records and
  nested collections become `STRUCT`, `LIST` and `MAP` values. The tables that SQL creates
  in it hold their rows as batches, until the engine closes.
- `fyrql-connector-csv`: CSV and TSV files of a directory or one by one, with the types
  inferred from a sample as DuckDB's `read_csv` infers them, a time of day such as
  `12:30:00` as a `TIME` and a timestamp with an offset or a zone, such as
  `2024-07-01 14:00:00+02`, as a `TIMESTAMPTZ`. A scan reads integers,
  decimals of up to 18 digits, dates, booleans and ASCII strings straight from the
  characters of the file into the columns; a field of another type, or in a form such as
  `+1`, `1e3` or ` 1`, is read as a string is cast, with the same value and error. A file
  whose name ends in `.gz` is read through gzip, in one part, and so is a file in S3. The
  table functions take `header`, `delim` or `sep`, `quote`, `nullstr`, `dateformat` and
  `timestampformat`.
- `fyrql-connector-json`: JSON files (`.json`, `.jsonl`, `.ndjson`, also with `.gz`) that
  hold an array of records or a sequence of them. The columns are inferred from a sample
  as DuckDB's `read_json` infers them, a time of day as a `TIME`; objects and arrays become `STRUCT`, `MAP` and `LIST`
  values, and a field whose values do not unite is `VARCHAR` with the JSON text. A scan
  streams the file and skips the values of the columns, and of the fields of structs,
  that it does not need; a file in S3 is read in one part. The table
  functions take `format`, `sample_size`, `ignore_errors`, `map_inference_threshold`,
  `field_appearance_threshold`, `dateformat` and `timestampformat`.
- `fyrql-connector-parquet`: Parquet files, read in pure Java with parquet-java without
  Hadoop and with codecs in pure Java (`SNAPPY`, `GZIP`, `ZSTD`, `LZ4_RAW` and none); the
  JAR of the module carries parquet-java renamed to `net.fyrql.internal`, so that an
  application may use a parquet-java or Hadoop of its own. A table is a file, a directory
  of `.parquet` files or a pattern such as `data/orders-*.parquet`, whose files have the
  same columns. The columns have the types that DuckDB's `read_parquet` gives them,
  `STRUCT`, `LIST` and `MAP` to any depth, and the number of rows comes from the footers.
  A scan reads each row group on its own, and only the columns and the fields of structs
  in use; it takes over a limit without filters, reading only the row groups the limit
  needs, and `count(*)` without groups and filters, which the footers answer. It takes over
  no filter, but uses the filters to skip the row groups in which no row can pass, by their
  statistics, dictionaries and Bloom filters, and the pages of a row group, by its column
  index: comparisons, `BETWEEN`, `IN` and `IS NULL` on integers, decimals, dates,
  times of day, timestamps with and without a time zone, strings and booleans, also on fields of structs, under `AND`, `OR` and
  `NOT`. `explain` does not show these filters; `recordReads(true)` keeps the row groups
  and columns each scan read. parquet-java holds the column chunks of a row group in
  memory, compressed, outside the memory budget of the engine. A file in S3 is read in
  ranges, see `fyrql-connector-s3`; the option `readAhead` (`1MB`) is the size of the
  reads of the headers of pages, of dictionaries and of indexes there.
- `fyrql-connector-iceberg`: Apache Iceberg tables, read in pure Java without Hadoop
  and without a catalog: a table is its directory, the one with `metadata/`, or one of its
  metadata files, local or in S3. In a directory, `metadata/version-hint.text` names the
  version, or else the metadata file with the highest version is the current one. Format
  versions 1 to 3 are read: the manifest list and the manifests of a snapshot, which a
  reader of its own reads as Avro with the codecs `null`, `deflate`, `snappy` and
  `zstandard`, name the data files, which must be Parquet files and are read by the
  Parquet connector. The columns of a data file are found by their field ids, so that
  renamed, added, dropped and reordered columns read right; a column a file lacks is its
  identity partition value, its `initial-default` or `NULL`, and a promoted column (`int`
  to `long`, `float` to `double`, a `decimal` with more digits) is read in its new type.
  Deletion vectors, position deletes and equality deletes remove their rows. The types
  are those of the schema: `int` is `INTEGER`, `long` `BIGINT`, `string` `VARCHAR`,
  `fixed` and `binary` are `BLOB`, `timestamp` and `timestamptz` are `TIMESTAMP` and
  `TIMESTAMPTZ`, also in nanoseconds, which are read in microseconds, and `struct`,
  `list` and `map` are `STRUCT`, `LIST` and `MAP`; `variant`, `geometry`, `geography` and
  `unknown` are an error that names the column. A scan leaves out the manifests and data
  files in which no row can pass its filters, by the partition values (`identity`,
  `year`, `month`, `day`, `hour` and `truncate`, `bucket` for `=` and `IN`), the bounds,
  null and `NaN` counts of the columns and the partition summaries of the manifest list,
  and then the row groups and pages of the files as the Parquet connector does; it takes
  over a limit and `count(*)` only when the snapshot has no deletes, and `recordReads(true)`
  keeps the row groups each scan read. The table functions `iceberg_scan` and
  `read_iceberg` take the location and, to read another snapshot, `snapshot_from_id`,
  `snapshot_from_timestamp`, a `TIMESTAMP` in UTC, a `TIMESTAMPTZ` or text, or `ref`, the
  name of a branch or tag; `iceberg_snapshots` lists the snapshots of a table, as in
  DuckDB. A table that was moved or copied, also into S3, is read where it is now: a path
  of its metadata that starts with the location the metadata gives is taken relative to
  the directory of the table. The source of the type `iceberg` takes `tables.<name>` or
  `tables.<name>.path` with at most one of `.snapshot_from_id`,
  `.snapshot_from_timestamp` and `.ref`, `directory`, whose subdirectories with a
  directory `metadata` become tables, `batchSize` and `readAhead`; an engine with the
  module on its class path has a source `iceberg` without tables for the table functions,
  unless a registered source offers them. Catalogs such as REST, AWS Glue or Unity,
  writing, the `name-mapping` of files without field ids, field ids inside nested types,
  whose fields are found by their names, and ORC and Avro data files are not supported.

  ```sql
  SELECT count(*) FROM iceberg_scan('warehouse/db/orders');
  SELECT snapshot_id, "timestamp_ms" FROM iceberg_snapshots('warehouse/db/orders');
  SELECT * FROM iceberg_scan('s3://lake/warehouse/db/orders', snapshot_from_id = 3051729675574597004);
  ```
- `fyrql-connector-http`: the JSON an endpoint answers to a `GET`, read as a JSON file,
  or the array that a JSON pointer such as `/data` points to inside the answer. The
  connector sends its headers, such as `Authorization`, accepts gzip and infers the
  columns with a first request, which it keeps until `refresh()`. Every scan is a request
  of its own; nothing but the choice of columns and fields is pushed to the endpoint. A status other
  than 2xx is an `HttpSourceException` with the URL and the status.
- `fyrql-connector-odata`: the entity sets of OData services of versions 2 and 4, in
  JSON and read only. Each service is a schema and each entity set of it a table, with
  the columns of its `$metadata`, which the connector reads when the service is first
  used and keeps until `refresh()`. A scan is a query of its entity set that selects the
  columns it reads with `$select`, in version 4 also fields of complex properties, and
  reads the answer page after page over the links to the next page, only as far as the
  engine asks for rows; a service that tells how many records there are and sends fewer
  without a link to a next page is an error, not a part of the table. A service that
  fails on a `$select` of complex or collection properties, as some do, is asked once
  more without it. The connector logs in with HTTP Basic, a bearer token or OAuth 2.0
  with the client credentials grant; an error of a service is an `ODataSourceException`
  with the status and the message of the service, without credentials and the values of
  headers. An `ODataProfile` adds query options and headers to every request, such as
  the `sap-client` of SAP Gateway, and gives columns other types or leaves them out.
  `$expand` and navigation properties, `$apply`, functions and actions, entity sets with
  parameters, Atom, writing and client certificates are not supported.
- `fyrql-connector-jdbc`: the tables of PostgreSQL, SQLite, H2 and MySQL, and of any other
  database with a JDBC driver through the generic dialect, see below. The default
  schema of the database is `main`, the others keep their names. A scan becomes one
  statement in the dialect of the database, which selects the columns the query needs
  and takes over the filters, the order and the limit, or the grouping and the
  aggregates, that the database evaluates exactly as FyrQL; a join of tables of the same
  database runs in the database as one statement, also the semi or anti join of an
  `EXISTS`, `NOT EXISTS` or `IN` over its tables, as `WHERE [NOT] EXISTS (...)`. The
  result is read in parts of the fetch size. A `TIME` is a `TIME`, a timestamp with a
  time zone a `TIMESTAMPTZ` and PostgreSQL's `interval` an `INTERVAL`; a type FyrQL does
  not have, such as a time with a time zone, the intervals of other databases, JSON or an
  array, is read as `VARCHAR`, and PostgreSQL's `numeric` without a precision is
  `DOUBLE`, as in DuckDB. `recordStatements(true)` keeps every statement sent. With `splitRows(n)`, a
  scan of a table of more than `n` rows is read in parts on several threads, see
  [Parallel execution](#parallel-execution).
- `fyrql-connector-s3`: the objects of S3, and of stores with its API such as MinIO, as
  the files of locations `s3://bucket/key`, which the connectors of CSV, JSON and Parquet
  read and write in `FROM`, in their table functions, in `COPY` and as the tables of their
  sources, such as `tables.events: s3://lake/events/*.parquet`. The source of the type
  `s3` has no tables of its own, and an engine has at most one. It talks to S3 over HTTP
  with a client of its own, without the SDK of AWS, and signs its requests with the
  Signature Version 4 of AWS. A location names an object, the objects directly under a
  prefix (`s3://lake/events/` or `s3://lake/events`), or those whose names match a
  pattern in the last part (`s3://lake/events/2026-*.parquet`). A Parquet object is read
  in ranges: the footer, usually with one request for the tail of the object, and then
  only the column chunks of the row groups and columns a scan needs, so that a filter
  that excludes every row group by the statistics reads nothing more. Each read names
  the entity tag of the object, so that an object that changes during a query fails it
  rather than mixing versions. A CSV or JSON object is read as one stream, in one part.
  An object is written as a multipart upload in parts of `partSize` and appears only with
  the last row, as a local file does; a statement that fails abandons the upload. A
  request that fails on the network, with a status of 500 or more or with 429 is tried
  again `retries` times. The options: `endpoint` (such as `http://127.0.0.1:9000` for
  MinIO; by default that of AWS in the region), `region` (`us-east-1`), `accessKey`,
  `secretKey` and `sessionToken`, `profile`, `pathStyle` (`true` with an `endpoint`,
  otherwise `false`), `timeout` (`30s`), `partSize` (`8MB`, at least `5MB`) and `retries`
  (`3`). What is not given comes from the environment as for the tools of AWS:
  `AWS_ENDPOINT_URL_S3` or `AWS_ENDPOINT_URL`, `AWS_REGION` or `AWS_DEFAULT_REGION`,
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN`, or else the profile
  of `~/.aws/credentials` that `profile` or `AWS_PROFILE` names; without credentials the
  requests go unsigned, as for a public bucket. No message shows the value of an option.
  Without a source of S3, a location of S3 fails with `No source handles s3:// locations;
  register a source of the type s3`.

A query over orders in PostgreSQL, customers in a CSV file and products from an HTTP
endpoint:

```java
try (FyrQL engine = FyrQL.builder()
        .register(JdbcConnector.builder("pg").url("jdbc:postgresql://localhost/shop")
                .user("shop").password(password).build())
        .register(CsvConnector.builder("files").directory(Path.of("data")).build())
        .register(HttpConnector.builder("api").table("products", "https://example.com/products")
                .header("Authorization", "Bearer " + token).build())
        .build()) {
    System.out.print(engine.explain("""
            SELECT c.city, p.name, count(*) AS orders
            FROM (SELECT customer_id, sku FROM orders
                  WHERE customer_id > 10 AND sku <> 'p3') o
            JOIN customers c ON o.customer_id = c.id
            JOIN products p ON o.sku = p.sku
            GROUP BY c.city, p.name ORDER BY c.city, p.name"""));
}
```

PostgreSQL receives `SELECT t."customer_id", t."sku" FROM "public"."orders" t WHERE
((t."customer_id" > CAST(10 AS INTEGER)) AND (t."sku" <> 'p3'))`: two of the five
columns of the orders, and the filter. The joins, the grouping and the order run in the
engine; since the endpoint does not tell how many products it has, the optimizer counts
1,000 of them and builds the hash table of the upper join on the filtered orders with
their customers:

```
Sort #0, #1
  Project #0 AS city, #1 AS name, #2 AS orders
    Aggregate GROUP BY #0, #1: count(*) AS "count_star()" [codegen]
      Project #5 AS "#group0", #1 AS "#group1" [parallel]
        Join INNER ON l#0 = r#1 [codegen]
          Scan api.main.products (sku VARCHAR, name VARCHAR)
          Join INNER ON l#0 = r#0 [codegen] [parallel]
            Project #0 AS customer_id, #1 AS sku
              Scan pg.main.orders (customer_id BIGINT, sku VARCHAR) [pushed: WHERE ((#0 > CAST(10 AS INTEGER)) AND (#1 <> 'p3'))]
            Scan files.main.customers (id BIGINT, city VARCHAR) [parallel]
```

A filter is pushed when it stands on a table, as in the subquery above, or when the
optimizer can move it there: a condition in the `WHERE` of a join goes to the table whose
columns it reads, and an equality between two tables becomes the key of their join. What a
database takes over depends on how it compares and orders values:

| A database takes over | H2 | SQLite | PostgreSQL | MySQL |
| --- | --- | --- | --- | --- |
| comparisons, `BETWEEN`, `IN` on integers and `BOOLEAN` | yes | yes | yes | yes |
| the same on `DECIMAL`, `DATE`, `TIME` and `TIMESTAMP` | yes | no | yes | yes |
| the same on `TIMESTAMPTZ` | yes | no | yes | no |
| `=`, `<>`, `IN`, `IS DISTINCT FROM` on `VARCHAR` | yes | yes | yes | no |
| `<`, `>`, `BETWEEN` and `ORDER BY` on `VARCHAR` | no | yes | no | no |
| anything on `FLOAT`, `DOUBLE`, `HUGEINT`, `BLOB`, `UUID` or `INTERVAL` | no | no | no | no |
| `AND`, `OR`, `NOT`, `IS NULL` over the above | yes | yes | yes | yes |
| arithmetic, casts, `LIKE`, `CASE`, functions | no | no | no | no |
| a comparison of a `BOOLEAN` with a number | no | no | no | no |
| `ORDER BY` over the columns above, and `LIMIT` | yes | yes | yes | yes |
| `GROUP BY` over the columns compared with `=` above | yes | yes | yes | yes |
| `count(*)` and `count` of any column | yes | yes | yes | yes |
| `min`, `max` on the integers, decimals, dates and times compared above | yes | integers | yes | yes |
| `sum` on integers | yes | no | yes | yes |
| `avg`, `DISTINCT`, other aggregates, or over `FLOAT` and `DOUBLE` | no | no | no | no |

SQLite stores decimals, dates and timestamps as numbers or text; H2 orders strings by
their UTF-16 units and PostgreSQL by the collation of the database, where FyrQL orders
them by their bytes; MySQL compares strings without regard to case. An instant is sent
as a literal with its offset, which PostgreSQL and H2 compare as the instant it is. A filter is taken
over in parts, conjunct by conjunct; the order only as a whole, and the limit only with
all filters and the order. An aggregation is taken over as a whole, only with all
filters, and becomes `SELECT groups, aggregates FROM table WHERE ... GROUP BY groups`.
Window functions are never taken over; they run in the engine.
A sum is cast to `NUMERIC(39)`, in MySQL `DECIMAL(39,0)`, and read as `HUGEINT`, the type
FyrQL gives it, so that the database neither overflows nor answers in a type of its own;
SQLite has no such type and leaves sums to the engine. The connector of an application
may take over the same in its own way through `Capabilities` in `fyrql-connector-spi`.

Any other database, such as Oracle, SQL Server, Db2, Derby or HSQLDB, is read through the
generic dialect, which the connector chooses when the product name of the database is
none of the four above, or with `dialect(JdbcDialect.GENERIC)`. Databases differ in
quoting, in how they keep the first rows, in how they compare strings (with or without
regard to case and to spaces at their end; Oracle even takes `''` for `NULL`), in
arithmetic and in the place of `NULL`s in an order, so the generic dialect takes over
only what every one of them evaluates as FyrQL does, and binds every literal as a
parameter `?` of a prepared statement:

| The generic dialect takes over | |
| --- | --- |
| comparisons, `BETWEEN`, `IN`, `IS [NOT] NULL` of a column of integers or `DATE` | yes |
| the same on `DECIMAL`, `FLOAT` and `DOUBLE`, `TIMESTAMP` or `BOOLEAN` | with `trust` |
| `AND`, `OR`, `NOT` over the above | yes |
| `=` and `IN` against strings that are not empty, `LIKE` without `\` and `[`, `IS NULL` | as a hint |
| `<>`, `<`, `>` on strings, `IS DISTINCT FROM`, arithmetic, casts, functions | no |
| `LIMIT` without `ORDER BY`, with `Statement.setMaxRows` or a clause | yes |
| `ORDER BY`, `GROUP BY`, aggregates, joins | no |

A hint holds wherever the filter holds: the database returns at least the rows that
satisfy the filter, and the engine filters them once more, so a scan with a hint keeps
no limit in the database. The connector reads dates and timestamps with the getters of
JDBC 4.0 and a calendar in UTC, so that a time in a gap of daylight saving time and a
date before 1582 keep their values; a time of day is read in milliseconds, and a
timestamp with a time zone, such as Oracle's `TIMESTAMP WITH LOCAL TIME ZONE` or SQL
Server's `DATETIMEOFFSET`, is a `TIMESTAMPTZ`; the engine filters both. The quote of identifiers comes from the driver; the schemas
are those of the metadata, or its catalogs if it has no schemas, without the schemas of
the database itself (`INFORMATION_SCHEMA` and those starting with `SYS`), and
`schemas` and `defaultSchema` set them where the metadata does not tell. The builder and
the configuration set the rest:

- `pushdownFilters(false)`, `pushdown: none`: the database evaluates no filter at all;
- `quote(...)`, `quote`: `"`, `` ` ``, `[` for `[name]`, or `none`;
- `limitStyle(...)`, `limit`: `maxRows` (the default), `fetchFirst` for
  `FETCH FIRST n ROWS ONLY`, `limit` for `LIMIT n`, or `top` for `SELECT TOP n`;
- `trust(...)`, `trust`: `decimal`, `double`, `timestamp` and `boolean`, a promise that
  the database compares these types as FyrQL does, which a query with `pushdown(false)`
  checks.

A number without a precision, such as Oracle's `NUMBER`, is read as `DOUBLE`; an integer
key of Oracle, a `NUMBER(10)`, is a `DECIMAL`, which the database neither filters nor
splits by without `trust: decimal`. A part of a plan with joins is not sent to such a
database.

An OData service gets the filters of a scan as a `$filter` that holds wherever they hold,
and the engine evaluates them once more, since a service may compare strings without
regard to case or ignore spaces at their end, which gives more rows but never fewer. A
source with `trustFilters`, for all its services or for one, takes over the filters that
have an exact counterpart; this is a promise about the service, which the connector does
not check, and a query with `pushdown(false)` shows whether it holds:

| An OData service gets | as a hint | taken over with `trustFilters` |
| --- | --- | --- |
| comparisons, `BETWEEN`, `IN`, `IS DISTINCT FROM` on integers, `DECIMAL`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ` | yes | yes |
| `=`, `<>` on `BOOLEAN` and `UUID` | yes | yes |
| `=`, `IN` and `LIKE 'a%'`, `'%a'`, `'%a%'` on `VARCHAR` | yes | no |
| `IS NULL` on any column of a primitive type | yes | yes |
| `<>`, `<`, `>` on `VARCHAR`; anything else on `FLOAT`, `DOUBLE`, `BLOB`, `INTERVAL`, `STRUCT`, `LIST` | no | no |
| `AND`, `OR`, `NOT` over the above | yes | yes |
| arithmetic, functions, casts other than to a number type that holds every value | no | no |

A part without counterpart drops out of an `AND` of a hint and leaves an `OR` without
one. `NOT` moves inward and is never sent, since a comparison with `null` is false in
OData rather than unknown. A literal is written as the version wants it, such as
`datetime'2024-01-01T00:00:00'`, `time'PT13H20M'` and `12L` in version 2; one that the column cannot hold,
such as `1.5` for an integer, turns a range into one of the values the column holds. A
timestamp compares by ranges of the unit in which the service sends it, the millisecond
in version 2 and the microsecond in version 4, unless its facet `Precision` says that
the service holds no finer one. The order of a scan becomes `$orderby` when it is by
numbers, dates and timestamps, the latter only as the last key unless the service holds
no finer value, and its `NULL`s stand where OData puts them, first in an ascending and
last in a descending order, which holds for an ascending order only on a column without
`NULL`s or with `NULLS FIRST`; strings are never ordered by the service, whose collation
is its own. The limit becomes `$top` when the filters and the order are all taken over,
and `count(*)` without groups a request of `/$count`, with the filters as `$filter` when
they are all taken over; `OFFSET` stays in the engine. In version 2, a scan that reads
fields of a complex property reads the whole column, and nothing is filtered by its
fields. What the `$metadata` forbids, by the vocabulary `Capabilities` of version 4 or
the attributes `sap:filterable`, `sap:sortable`, `sap:pageable` and `sap:countable` of
version 2, is not asked for.

## Writing and loading

Five statements write, and each gives one row with the number of rows written in a
column `Count` of the type `BIGINT`, as in DuckDB:

- `COPY (query) TO 'file' [(option value, ...)]`, or `COPY table [(columns)] TO 'file'`,
  writes the rows of a query into a CSV, JSON or Parquet file;
- `COPY table [(columns)] FROM 'file' [(option value, ...)]` loads such a file into a
  table with the types of its columns;
- `CREATE TABLE t AS query` creates a table with the columns of the query and its rows,
  and `CREATE TABLE t (a INTEGER NOT NULL, b VARCHAR)` an empty one; `CREATE OR REPLACE
  TABLE` drops a table of the name first, and `CREATE TABLE IF NOT EXISTS` does nothing if
  there is one;
- `INSERT INTO t [(columns)] query` and `INSERT INTO t [(columns)] VALUES (...), ...` add
  rows;
- `DROP TABLE [IF EXISTS] t` drops a table.

`engine.update(sql)` runs such a statement and returns the number; `engine.sql`,
`engine.rows` and the [JDBC driver](#jdbc-driver) run it too and give the row. With the
sources of the [first example](#using-the-engine), this writes the orders with the
regions of their customers into a CSV file, and the revenue of each region, read back
from that file, into a new table in memory:

```java
long written = engine.update("""
        COPY (SELECT o.id, c.region, o.amount
              FROM orders AS o JOIN customers AS c ON o.customerId = c.id)
        TO 'data/orders-by-region.csv'""");
engine.update("CREATE TABLE mem.regions (region VARCHAR NOT NULL, revenue DECIMAL(12,2))");
engine.update("INSERT INTO mem.regions SELECT region, sum(amount) FROM 'data/orders-by-region.csv' GROUP BY ALL");
System.out.println(written + " " + engine.rows("SELECT * FROM regions ORDER BY region").stream()
        .map(Arrays::toString).toList());
```

```
4 [[north, 15.75], [south, 20.00]]
```

A statement runs while it is executed and returns when its rows are written. It reads
its rows as a query does, with pushdown, compiled code and several threads, and
`explain` shows its plan below a line such as `Insert into mem.main.regions` or
`Copy to 'data/orders-by-region.csv' [format: csv]`. The values of `INSERT INTO` go to
the columns in the list, by default all columns in their order; a column left out gets
`NULL`, and a value of another type is cast to the type of its column, where a cast
between the two types is allowed. A `NULL` in a column `NOT NULL` fails with
`NOT NULL constraint failed: regions.region`. `CREATE TABLE ... AS` gives the table the
names and types of the columns of the query, which must be unique, all of them without
`NOT NULL`, as DuckDB does. `engine.prepare` prepares a statement with parameters `?` as
it does a query, and `engine.update(prepared, values)` runs it; a parameter in `VALUES`
takes the type of its column. A cancelled statement, or one that fails, leaves no file
and no new rows behind, within the limits of the sources below.

A name of a new table of one part goes to the default source and schema, of two parts
to the schema `main` of the source of that name or else to the schema of that name in
the default source, and of three parts to `source.schema.table`. Each statement is a
transaction of its own in the source it writes to; there is no transaction over several
statements or sources, and `CREATE OR REPLACE` drops the old table and then creates the
new one in two steps. `UPDATE`, `DELETE`, `ALTER TABLE`, constraints and keys, and
scripts of several statements are not supported.

### Files

`COPY` takes the format from the option `FORMAT` (`csv`, `json` or
`parquet`), or else from the end of the name of the file: `.csv` and `.tsv`, `.json`,
`.jsonl` and `.ndjson`, or `.parquet`, CSV and JSON also with `.gz`, which compresses
them with gzip. A relative path is resolved against the working directory of the
process; a location `s3://bucket/key` writes an object of S3, see
[Connectors](#connectors). The rows go into a temporary file `<name>.<random>.tmp` next to the file, which
takes its name when the last row is written and replaces a file of that name, so that a
reader never sees half a file; the directory must exist. `COPY t (a, b) TO 'file'`
writes the columns of the list, as `COPY (SELECT a, b FROM t) TO 'file'` does. The
options, all optional, with their defaults:

| Format | Options |
| --- | --- |
| CSV | `header` (`true`), `delimiter`, `delim` or `sep` (`,`, or a tab for `.tsv`), `quote` (`"`), `escape` (the quote), `nullstr` or `null` (empty), `dateformat` and `timestampformat` (as in `strftime`), `compression` (`gzip` or `none`, by default from the name) |
| JSON | `array` (`false`: one record per line, `true`: one array of records), `dateformat`, `timestampformat`, `compression` |
| Parquet | `compression` or `codec` (`zstd`, `snappy`, `gzip`, `lz4_raw` or `uncompressed`; `zstd`), `row_group_size` (122,880 rows), `row_group_size_bytes` (128 MB), `parquet_version` (`v1` or `v2`; `v1`) |

An unknown option fails with a message that lists the known ones. A CSV file is written
as DuckDB writes it, byte for byte: in UTF-8 with line feeds, a field in quotes only
where it holds the delimiter, the quote or a line break or equals the null string, so
that an empty string is `""` and a `NULL` is nothing, and a nested value as its text,
such as `[1, NULL]`; a `TIMESTAMPTZ` is written in the time zone of the session, such
as `2024-07-01 14:00:00+02`, in both formats. A JSON file holds numbers as numbers, `NaN`
and `Infinity` as DuckDB writes them, dates, times, timestamps and intervals as strings, a `BLOB` as a string with `\x`
escapes, structs and maps as objects and lists as arrays. Neither keeps the types: read
back, the types are inferred anew, so that a `TINYINT` comes back as a `BIGINT` and a
`DECIMAL` from a CSV file as a `DOUBLE`, and DuckDB reads a number with a fraction from
JSON as a `DOUBLE`. A Parquet file holds the types that DuckDB's `read_parquet` reads
back as they were, nested ones to any depth, with a `TIMESTAMP` in microseconds not
adjusted to UTC, a `TIMESTAMPTZ` in microseconds adjusted to UTC, a `TIME` in
microseconds and a `UUID` of the logical type `UUID`, except for `HUGEINT`, which it
holds as a `DECIMAL(38,0)`: a value of 39 digits fails with the name of its column, where
DuckDB writes a `HUGEINT` as a `DOUBLE`. An `INTERVAL` is held as DuckDB holds it, in
twelve bytes of months, days and milliseconds, so that a part of a millisecond is lost
and an interval with a negative part fails. Its metadata has the key `writer.model.name`
with the value `fyrql`.

### Formats

The reader and the writer of a format come with the module of the format:
an engine with `fyrql-connector-parquet` on its class path has a source `parquet`
without tables, which reads and writes Parquet files for `COPY`, the table function
`read_parquet` and file names in `FROM`, unless registered sources read and write that
format or one has that name. The engine finds these sources with the `ServiceLoader` in the class loader of
FyrQL, or in the one given to `classLoader(...)` on the builder; `sources()` lists them
after the registered ones, and none of them is the default source. `implicitSources(false)`
on the builder, or `implicitSources: false` in the [configuration file](#configuration-file),
turns them off. Without a source of its format, a statement fails with
`No source writes files of the format parquet; put fyrql-connector-parquet on the class
path or register a source of the type parquet`.

### Loading

`COPY t [(columns)] FROM 'file' [(option value, ...)]` loads a file into the
table `t`, which must exist, with the types of its columns, as DuckDB does. Where
`INSERT INTO t SELECT * FROM 'file'` reads the file with the types that a sample of it
suggests and casts them, so that a `007` in a `VARCHAR` column becomes `7` and a column
that is empty in the sample gets a type that a later row may not fit, `COPY ... FROM`
reads each column as the type of its column in the table, from the first row to the last.
The file holds the columns of the list, by default all columns of the table; the other
columns get `NULL`, and one of them that is `NOT NULL` fails the statement before the
file is opened. A CSV file gives its fields in the order of the list, and its first line
is a header unless `HEADER false` is given, which is skipped without a look at its names;
a JSON file gives the values of its records by the names of their keys, where a missing
key is `NULL` and an unknown one is passed over; a Parquet file gives its columns in the
order of the list, which are cast to the types of the table where such a cast is
allowed, and otherwise the statement fails with the column and both types. The format
comes from the option `FORMAT` or the name of the file, as for `COPY ... TO`, and a name
that ends in `.gz` is read through gzip. The statement gives the number of rows loaded,
checks `NOT NULL` and leaves no rows behind when it fails, as `INSERT INTO` does, and
`explain` shows its plan below a line such as
`Copy from 'data/orders-by-region.csv' into mem.main.archive [format: csv]`. With the
sources of the example above, this loads the file that `COPY ... TO` wrote into a new
table with types of its own:

```java
engine.update("CREATE TABLE mem.archive (id BIGINT NOT NULL, region VARCHAR, amount DECIMAL(10,2))");
long loaded = engine.update("COPY mem.archive FROM 'data/orders-by-region.csv'");
```

The options of reading, all optional, with their defaults:

| Format | Options |
| --- | --- |
| CSV | `header` (`true`), `delimiter`, `delim` or `sep` (`,`, or a tab for `.tsv`), `quote` (`"`), `escape` (only the quote), `nullstr` or `null` (empty), `dateformat` and `timestampformat` (as in `strptime`; a value must have that form), `compression` (`gzip`, `none` or `auto`: by the name) |
| JSON | `format` (`auto`, `array` or `newline_delimited`; `auto`) or `array` (`true` or `false`), `dateformat`, `timestampformat`, `ignore_errors` (`false`; with `true`, a value that does not fit is `NULL`), `compression` |
| Parquet | none |

A CSV file holds no nested values: a column of the type `STRUCT`, `LIST` or `MAP` in the
list fails the statement. In a CSV file of one column whose null string is empty, an
empty line is a `NULL`, as DuckDB writes and reads it; otherwise it is skipped. An object
of S3 is loaded by its location, as in `COPY mem.archive FROM 's3://lake/archive.csv'`.
Files compressed with zstd, and several files at once, are not loaded.

### Tables

The memory connector and the JDBC connector create, fill and drop tables. In
memory, a table that SQL created holds the batches of its rows and shows the rows of a
statement when the statement ends; a query that runs at that time goes on with the rows
it started with. A table registered from Java objects can be read and dropped, but not
written. A source of the type `memory` in the configuration file starts without tables,
and the [JDBC driver](#jdbc-driver) has one without a configuration file.

A JDBC source creates a table with the types of its dialect, and sends the rows as
`INSERT INTO t ("a", "b") VALUES (?, ?)` in batches of `batchRows` rows (1,000 by
default), in a transaction that commits when the last batch is sent and rolls back when
a row or the query fails. Where the database commits a `CREATE TABLE` at once, as H2 and
MySQL do, the source drops the table it created after a rollback. The catalog of the
source learns of a new or dropped table at once.

| FyrQL | H2 | PostgreSQL | MySQL | SQLite | generic |
| --- | --- | --- | --- | --- | --- |
| `BOOLEAN` | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` |
| `TINYINT` | `TINYINT` | `SMALLINT` | `TINYINT` | `TINYINT` | `SMALLINT` |
| `SMALLINT`, `BIGINT` | the same | the same | the same | the same | the same |
| `INTEGER` | `INTEGER` | `INTEGER` | `INT` | `INT4` | `INTEGER` |
| `HUGEINT` | `NUMERIC(39)` | `NUMERIC(39)` | `DECIMAL(39,0)` | none | none |
| `FLOAT` | `REAL` | `REAL` | `FLOAT` | `FLOAT` | `REAL` |
| `DOUBLE` | `DOUBLE PRECISION` | `DOUBLE PRECISION` | `DOUBLE` | `DOUBLE` | `DOUBLE PRECISION` |
| `DECIMAL(p,s)` | `NUMERIC(p,s)` | `NUMERIC(p,s)` | `DECIMAL(p,s)` | `DECIMAL(p,s)` | `DECIMAL(p,s)` |
| `VARCHAR` | `VARCHAR` | `VARCHAR` | `TEXT` | `VARCHAR` | `VARCHAR(4000)` |
| `BLOB` | `VARBINARY` | `BYTEA` | `BLOB` | `BLOB` | `BLOB` |
| `DATE` | `DATE` | `DATE` | `DATE` | `DATE` | `DATE` |
| `TIMESTAMP` | `TIMESTAMP(6)` | `TIMESTAMP` | `DATETIME(6)` | `TIMESTAMP` | `TIMESTAMP` |
| `TIME` | `TIME(6)` | `TIME` | `TIME(6)` | `TIME` | `TIME` |
| `TIMESTAMPTZ` | `TIMESTAMP(6) WITH TIME ZONE` | `TIMESTAMPTZ` | `DATETIME(6)` in UTC | `TIMESTAMP` in UTC | `TIMESTAMP` in UTC |
| `INTERVAL` | `VARCHAR` | `INTERVAL` | `VARCHAR(64)` | `VARCHAR` | `VARCHAR(64)` |
| `UUID` | `UUID` | `UUID` | `CHAR(36)` | `UUID` | `CHAR(36)` |

A column of a type without a name in the dialect, such as a `HUGEINT` in SQLite or a
`STRUCT`, `LIST` or `MAP` in any database, fails the statement before it creates the
table, with the column and the dialect. Read back, a table may have other types than
those it was created with, as the database reports them: PostgreSQL has no `TINYINT`,
SQLite keeps the declared names only as its affinities, MySQL and the generic dialect
store a `UUID` as text, an instant stored in UTC comes back as a `TIMESTAMP`, and an
interval stored as text as a `VARCHAR`. A database through the generic dialect takes strings of up to
4,000 characters. With MySQL, `rewriteBatchedStatements=true` in the URL sends a batch
as one statement, which is much faster.

## File access

Statements name files and objects of their own: `FROM 'data/orders.csv'`, the table
functions such as `read_parquet` and `iceberg_scan`, `COPY ... TO` and `COPY ... FROM`.
The setting `fileAccess` says what they may do with them: `readWrite`, the default of the
engine and of the [JDBC driver](#jdbc-driver), reads and writes them, `read` only reads
them, and `none` neither, the default of the [Flight SQL server](#flight-sql-server).
The setting `allowedLocations` says where: directories and prefixes of objects, such as
`data` and `s3://lake/`, under which a location must lie; without it, every location
does, as far as `fileAccess` goes. The tables of the sources that an application or a
configuration file registers are not affected: an engine with `fileAccess` `none` still
reads the table `orders` of a source of the type `csv` over the directory `data`.

```java
FyrQL engine = FyrQL.builder()
        .register(CsvConnector.builder("csv").directory(Path.of("data")).build())
        .fileAccess(FileAccess.READ)
        .allowedLocations(List.of("data", "s3://lake/"))
        .build();
```

The engine checks a location before a source gets it, also in `EXPLAIN` and when a
statement is prepared. A local location is resolved as the readers resolve it, a relative
one against the working directory, and made absolute, without `.` and `..`, with the
symbolic links followed as far as the path exists; for a pattern such as
`data/*.parquet`, the directory before the pattern counts. A location lies under an
allowed directory only by whole names, so that `data` allows `data/2026/orders.csv` but
not `data2/orders.csv`, and a link in `data` that points elsewhere does not allow its
target. A location with a scheme, such as `s3://lake/sales/q1.parquet`, is compared as
text with the scheme in lower case and must not have the parts `.` or `..`; `s3://lake`
allows `s3://lake/x`, but not `s3://lakehouse/x`. The allowed locations are resolved in
the same way when the engine is built, in a [configuration file](#configuration-file)
against its directory, and `engine.allowedLocations()` returns them so. The files of a
table that `iceberg_scan` reads must all lie under the allowed locations: not only its
metadata, but also the manifest lists, manifests, data and delete files the metadata
names, and its scan fails at the first one that does not. The check comes before a file
is opened; whoever may change an allowed directory, for example put a link in place of a
directory, can still lead a statement elsewhere, so that the allowed directories belong to
the operator alone.

A statement that may not read or write a location fails with a message such as
`Reading data2/orders.csv is not allowed: it is not under the allowed locations
/srv/data, s3://lake/ at line 1, column 15`, with the SQLState `42501` in the JDBC driver
and the status `UNAUTHORIZED`, `PERMISSION_DENIED` in gRPC, in the Flight SQL server.
`allowedLocations` with `fileAccess` `none` is an error when the engine is built.

## Configuration file

With the module `fyrql-config`, a YAML file names the sources and settings of an engine,
and `FyrQLConfig.open(Path.of("fyrql.yaml"))` builds it:

```yaml
sources:
  - name: shop
    type: jdbc
    url: jdbc:postgresql://localhost/shop
    user: shop
    password: ${env:SHOP_PASSWORD}
  - name: files
    type: csv
    directory: data
  - name: lake
    type: parquet
    tables:
      events: lake/events/*.parquet
  - name: api
    type: http
    tables:
      products: { url: "https://example.com/v1/products", pointer: /data }
    headers:
      Authorization: Bearer ${env:API_TOKEN}
  - name: erp
    type: odata
    services:
      sales: https://example.com/odata/v2/SalesService/
      trips: { url: "https://example.com/odata/v4/TripService/", trustFilters: true }
    user: ${env:ERP_USER}
    password: ${env:ERP_PASSWORD}
engine:
  defaultSource: shop
  pushdown: true
```

The `type` of a source picks its connector, which must be on the class path: `csv`,
`json`, `parquet`, `iceberg`, `http`, `odata`, `jdbc`, `memory` or `s3`; a connector of an
application joins them through a `ConnectorFactory` listed in `META-INF/services`. The
other keys of a source are the options of its type, read as text with nested keys joined
by dots (`tables.products.url`); an unknown or misspelled option is an error that lists
the known ones. Relative paths are resolved against the directory of the file. A value may
name an environment variable as `${env:NAME}`, or `${env:NAME:-default}`, and a system
property as `${sys:name}`, so that secrets need not be in the file. The settings under
`engine` are those of the builder, listed under [Engine settings](#engine-settings);
`allowedLocations` is a list, or a text with commas between its entries, whose relative
directories are resolved against the directory of the file, and `queryTimeout` and
`maxQueryTimeout` are durations such as `30s` or `5m`. A
file whose name ends in `.json` is read as JSON. `FyrQLConfig.builder(path)` returns the
builder instead, so that an application can add sources of its own, such as tables in
memory. A third section, `server`, holds the settings of the [Flight SQL
server](#flight-sql-server), which the engine leaves aside.

A source of the type `odata` names one service with `url`, whose schema is `main`, or
several under `services`, each with its `url` and, if need be, `version` (`v2` or `v4`,
which otherwise comes from its `$metadata`) and `trustFilters`. It logs in with `user`
and `password`, with `token`, or with `oauth.tokenUrl`, `oauth.clientId`,
`oauth.clientSecret` and optionally `oauth.scope` and `oauth.clientAuthentication`
(`basic`, the default, or `post`). Its other options are `headers`, `timeout`, `pageSize`
(the size of the pages a service of version 4 is asked for), `batchSize`, `trustFilters`
and `rowCounts`, both `false` by default; with `rowCounts`, the catalog tells the
optimizer the number of rows of each entity set by a request of `/$count`, which a tool
that lists the tables, such as DBeaver through the JDBC driver, then sends for every
entity set.

A source of the type `jdbc` takes `url`, `user`, `password`, `dialect` (`h2`, `sqlite`,
`postgresql`, `mysql` or `generic`, by default from the URL or else the database, and
`generic` for any other), for the generic dialect `pushdown`, `quote`, `limit` and
`trust` (see [Connectors](#connectors)), `schemas` (a comma-separated list),
`defaultSchema`, `fetchSize`, `batchSize`, `batchRows` (the rows of a batch of
[`INSERT`](#writing-and-loading)), `recordStatements`, `splitRows` and `splitColumn.<table>` (see
[Parallel execution](#parallel-execution)), and `properties.<key>` for any property of
the driver.

A source of the type `memory` starts without tables, for those that SQL
[creates](#writing-and-loading), and takes `batchSize` and `splitRows`, the rows of the parts that
a scan of a table reads on several threads. A source of the type `csv`, `json` or
`parquet` without options has no tables; the engine has one of each anyway whose module
is on the class path, unless `implicitSources: false` stands under `engine` (see
[Writing](#writing-and-loading)).

A source of the type `s3` holds no tables; it opens the objects of `s3://` locations to
the sources of the formats, with the options of `fyrql-connector-s3` (see
[Connectors](#connectors)). A table of a source of the type `csv`, `json` or `parquet`
in S3 is looked up when it is first used:

```yaml
sources:
  - name: s3
    type: s3
    endpoint: http://127.0.0.1:9000
    accessKey: ${env:MINIO_ROOT_USER}
    secretKey: ${env:MINIO_ROOT_PASSWORD}
  - name: lake
    type: parquet
    tables:
      events: s3://lake/events/*.parquet
```

## JDBC driver

The module `fyrql-jdbc` is a JDBC driver that runs an engine in the process of the
application. Its class is `net.fyrql.jdbc.FyrQLDriver`, which registers itself with the
`DriverManager`, and its URL names a configuration file and, if need be, settings of the
engine:

```
jdbc:fyrql:?config=fyrql.yaml&codegen=off&memoryBudget=512MB
```

With the file `fyrql.yaml`

```yaml
sources:
  - name: files
    type: csv
    directory: data
```

and the customers of the [first example](#using-the-engine) together with five orders in
`data/orders.csv`, this lists the tables and runs a query with a parameter:

```java
try (Connection connection = DriverManager.getConnection("jdbc:fyrql:?config=fyrql.yaml")) {
    try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
        while (tables.next()) {
            System.out.println(tables.getString("TABLE_CAT") + "." + tables.getString("TABLE_SCHEM") + "."
                    + tables.getString("TABLE_NAME"));
        }
    }
    String sql = """
            SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
            FROM orders AS o JOIN customers AS c ON o.customer = c.id
            WHERE o.amount > ?
            GROUP BY ALL
            ORDER BY revenue DESC""";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setBigDecimal(1, new BigDecimal("6.00"));
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                System.out.println(rs.getString("region") + " " + rs.getLong("orders") + " "
                        + rs.getString("revenue"));
            }
        }
    }
}
```

```
files.main.customers
files.main.orders
south 1 20.0
north 1 10.5
```

The CSV file gives `amount` the type `DOUBLE`, as DuckDB would, and so the sums are doubles.

The keys of the URL are `config` and the [engine settings](#engine-settings) of the
[configuration file](#configuration-file) except `implicitSources`, which win over those
of the file. The same keys may come as properties of the connection, which win over the
URL; `getPropertyInfo` describes them, so that tools offer them. Keys are matched without
regard to case, values in the URL are decoded from `%xx`, and a blank value counts as not
set. The properties `user` and `password`, which tools always send, are ignored; another
unknown property gives a warning on the connection, and an unknown key in the URL fails
the connection with `08001`, as does a file with an error. A relative path of the file is
resolved against the working directory, and the paths in the file against its directory;
so is a relative entry of `allowedLocations` in the URL, or against the working directory
without a file. `jdbc:fyrql:?fileAccess=read&allowedLocations=data` lets statements read
only the files under `data` (see [File access](#file-access)).
Without `config`, the engine has the sources `csv`, `json`, `parquet` and `iceberg`
without tables, so that file names and table functions can be queried, as in `SELECT *
FROM 'data/orders.csv'`, `SELECT * FROM read_parquet('lake/events.parquet')` or `SELECT *
FROM iceberg_scan('warehouse/db/orders')`, and files written, and the source `memory`, in
which `CREATE TABLE memory.t AS ...` creates tables that live as long as the connection.

Every connection builds an engine of its own and closes it when the connection closes; an
application with an engine of its own opens a connection over it with
`FyrQLDriver.connect(engine, false)`, which leaves the engine open, and
`FyrQLDataSource` is a `DataSource` for either way. Either way, a connection is a
[session](#sessions) of its own: `SET`, `RESET` and `USE` change only it, also when
connections share an engine. The catalog of a connection is its default source and the
schema its default schema: `setCatalog` and `setSchema` change where names of fewer than
three parts are looked up, for this connection only. The
`DatabaseMetaData` shows the sources as catalogs, with the term `source`, and their
schemas, tables and columns, with the number of rows of a table, or an estimate, in
`REMARKS` if the source has one; it reads only the sources the catalog in the call matches.

A statement runs one query: a `SELECT`, `WITH`, `VALUES` or set operation; or one
statement that [writes](#writing-and-loading); or `EXPLAIN` or `EXPLAIN ANALYZE` followed by either,
which gives the plan as rows of one column `plan`. `EXPLAIN ANALYZE` runs the statement
first, see [EXPLAIN ANALYZE](#explain-analyze): of a statement that writes, it writes,
and a read-only connection refuses it with `25006`; `executeUpdate` refuses any
`EXPLAIN` with `42809`. The parameters
`?` of a `PreparedStatement` go into the text as literals of the values they are set to,
before the text is parsed; a parameter of a `MAP` type is not supported.
`getMetaData` gives the columns of the result before the parameters have values, as the
engine prepares the query, and once all have values those of the query with their
literals; it is `null` for a statement that writes and where the engine cannot prepare
the query, as for `SELECT ?`.
`getParameterMetaData` gives the types the engine infers for the parameters (see
[Using the engine](#using-the-engine)), and `OTHER` where it infers none, as in
`SELECT ?` or `LIMIT ?`, which the driver still runs with the literal of the value. A query runs
until its first rows while it is executed, as in the drivers of databases, and the rest
while its result set is read. `cancel()` and a timeout from `setQueryTimeout` stop a
running query or statement, also one that waits for a database, with the SQLState
`57014` or `HYT00`; the timeout counts until the result set is closed. `setQueryTimeout(0)`,
the default, takes the `queryTimeout` of the engine, and its `maxQueryTimeout` shortens any
timeout (see [Time limits](#time-limits)). The queries of one connection run one at a time. An
error has the message of FyrQL, with the position in the text or the node of the plan,
and the exception of FyrQL as its cause; its SQLState tells the kind, such as `42601` for
the syntax, `42P01` for an unknown table, `42703` for an unknown column, `42P07` for a
table that exists already, `23502` for a `NULL` in a column `NOT NULL`, `0A000` for what
FyrQL does not support, `22003`, `22012` and `22018` for values out of range, a division
by zero and a failed cast, `42501` for a file the engine does not allow (see
[File access](#file-access)), and `58030` for an error of a source.

The types map to those of JDBC as follows; `getColumnTypeName` gives the name of the type
in FyrQL, such as `DECIMAL(15,2)` or `INTEGER[]`, and `getString` the text of a value as
DuckDB writes it, such as `[1, NULL]` or `{'a': 1}`:

| FyrQL | `getColumnType` | `getObject` |
| --- | --- | --- |
| `BOOLEAN` | `BOOLEAN` | `Boolean` |
| `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT` | the same | `Byte`, `Short`, `Integer`, `Long` |
| `HUGEINT` | `NUMERIC` | `BigDecimal` |
| `FLOAT` | `REAL` | `Float` |
| `DOUBLE` | `DOUBLE` | `Double` |
| `DECIMAL(p,s)` | `DECIMAL` | `BigDecimal` |
| `VARCHAR` | `VARCHAR` | `String` |
| `BLOB` | `VARBINARY` | `byte[]` |
| `DATE` | `DATE` | `java.sql.Date` |
| `TIMESTAMP` | `TIMESTAMP` | `java.sql.Timestamp` |
| `TIME` | `TIME` | `java.sql.Time` |
| `TIMESTAMPTZ` | `TIMESTAMP_WITH_TIMEZONE` | `java.time.OffsetDateTime` |
| `INTERVAL` | `OTHER` | `net.fyrql.core.value.Interval` |
| `UUID` | `OTHER` | `java.util.UUID` |
| `T[]` | `ARRAY` | `java.sql.Array` |
| `STRUCT(...)` | `STRUCT` | `java.sql.Struct` |
| `MAP(K, V)` | `OTHER` | `java.util.Map` |

`getObject(i, LocalDate.class)`, `LocalDateTime`, `LocalTime`, `Instant`,
`OffsetDateTime`, `ZonedDateTime`, `Duration` (of an interval without months), `Period`
(of one without a time), `BigInteger` and `String` work as well, and the elements of
arrays, structs and maps are converted alike. A `java.sql.Time` holds milliseconds, a
`LocalTime` the microseconds; an instant is given, and its local values and its text
are read, in the [time zone](#time-zone) of the session. Parameters take the same
classes.

A statement that writes runs with `executeUpdate`, `executeLargeUpdate` or `execute`,
which returns `false` and leaves the number of rows written to `getUpdateCount`.
`executeQuery` runs only queries and `executeUpdate` only statements that write; each
fails with `42809` before it runs the other kind. `addBatch` and `executeBatch` run a
list of statements, or a `PreparedStatement` with several sets of values, one after the
other, and stop at the first that fails with a `BatchUpdateException`, which holds the
counts of those before it, whose rows stay written. A connection is always in
auto-commit: each statement is a transaction of its own in the source it writes to and
takes effect at once. The methods of transactions are accepted, since tools call them when
they connect, and do nothing: after `setAutoCommit(false)`, `getAutoCommit()` is still
`true`, `commit()` does nothing, and `rollback()` after a statement that wrote undoes
nothing. Savepoints fail with `0A000`. `setReadOnly(true)` makes a statement that writes
fail with `25006`. Results are read forward only, a call runs one statement and not a script, and
there are no stored procedures and no generated keys.

### DBeaver

The JAR of `fyrql-jdbc-all` holds the driver with the engine, code generation and all
connectors, with the libraries they use renamed under `net.fyrql.internal`, but not the
JDBC drivers of databases. This builds it as `fyrql-jdbc-all/target/fyrql-jdbc-all-<version>.jar`:

```
./mvnw -B -ntp clean package -pl fyrql-jdbc-all -am -DskipTests
```

In DBeaver, a new driver in the Driver Manager gets
- the class name `net.fyrql.jdbc.FyrQLDriver` and the URL template `jdbc:fyrql:?config={file}`,
  without user and password;
- as libraries the JAR, and the JDBC drivers of the databases that the configuration
  file names, such as the one of PostgreSQL; FyrQL finds them in the class loader of
  DBeaver, which does not register them with the `DriverManager`.

A connection with that driver then asks for the configuration file. DBeaver opens several
connections to a database, for the metadata and for each editor, and each of them builds
an engine; switching off the separate connections in the settings of the connection
keeps it at one. DBeaver runs a statement that writes with `execute` and shows the
number of rows written. Its navigator shows a new table after a refresh only with a
single connection: a table in memory exists only in the engine of the connection that
created it, and the engine of another connection learns of a table created in a
database only when that connection opens anew, as with Invalidate/Reconnect. DBeaver
should stay in auto-commit, its default: in its manual commit mode, Commit and Rollback do
nothing, and what a statement wrote stays.

## Arrow

The module `fyrql-arrow` gives the results of an engine as vectors of
[Apache Arrow](https://arrow.apache.org) 19, for libraries that take Arrow, and reads
Arrow vectors as batches of FyrQL. A query runs as an `ArrowReader` of Arrow, which
fills one `VectorSchemaRoot` with each batch of the result:

```java
try (BufferAllocator allocator = new RootAllocator();
        FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT * FROM orders", allocator)) {
    VectorSchemaRoot root = reader.getVectorSchemaRoot();
    while (reader.loadNextBatch()) {
        System.out.println(root.getRowCount() + " rows");
    }
}
```

`ArrowResults.reader` also takes a session, with its time zone and search path, a
prepared query with its values, or a
`QueryResult` that is already running; `cancel()` stops the query, and closing the reader
closes the result. `BatchToArrow` writes a single batch into a root, and `ArrowToBatch`
reads a root as a batch. The values are copied, since the vectors of FyrQL live on the
heap and those of Arrow outside of it, in memory of `arrow-memory-unsafe`, without Netty
or native code. Arrow needs the JVM option `--add-opens=java.base/java.nio=ALL-UNNAMED`,
or `--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED` on the module
path.

The types map as follows:

| FyrQL | Arrow |
| --- | --- |
| `BOOLEAN` | `Bool` |
| `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT` | `Int` of 8, 16, 32 and 64 bits, signed |
| `HUGEINT` | `Decimal128(38, 0)`, marked with the field metadata `fyrql.type=HUGEINT` |
| `FLOAT`, `DOUBLE` | `FloatingPoint(SINGLE)`, `FloatingPoint(DOUBLE)` |
| `DECIMAL(p,s)` | `Decimal128(p, s)` |
| `VARCHAR`, `BLOB` | `Utf8`, `Binary` |
| `DATE` | `Date(DAY)` |
| `TIMESTAMP` | `Timestamp(MICROSECOND)` without a time zone |
| `TIME` | `Time(MICROSECOND, 64)` |
| `TIMESTAMPTZ` | `Timestamp(MICROSECOND, "UTC")` |
| `INTERVAL` | `Interval(MONTH_DAY_NANO)` |
| `UUID` | the extension type `arrow.uuid` over `FixedSizeBinary(16)` |
| `T[]` | `List` with the element `item` |
| `STRUCT(...)` | `Struct` |
| `MAP(K, V)` | `Map` with the entries `key` and `value` |

A `HUGEINT` of 39 digits does not fit and fails with an error that names the column,
as does an `INTERVAL` whose microseconds do not fit into 64 bits of nanoseconds.
Read back from Arrow, the same types give the same values, and besides them: unsigned
integers as the next larger type with a sign, up to `HUGEINT` for a `UInt64`; `Null` as
`INTEGER`, `Float16` as `FLOAT`, `Decimal256` up to precision 38; `LargeUtf8`,
`LargeBinary`, `Utf8View` and `BinaryView`, and any other `FixedSizeBinary` as `BLOB`;
`Date(MILLISECOND)`; a `Timestamp` without a time zone in seconds, milliseconds or
nanoseconds, cut to microseconds, and one with a time zone in any unit as a
`TIMESTAMPTZ`; a `Time` in any unit as a `TIME`; an `Interval` in any unit and a
`Duration` as an `INTERVAL`; `FixedSizeList` as a list; an extension type FyrQL
does not know as its storage type; and a column encoded with a dictionary as its values.
A `Union`, `LargeList`, list views, `RunEndEncoded` and a dictionary inside a nested
type are an error that names the field.

## Flight SQL server

The module `fyrql-flight-sql` is an
[Arrow Flight SQL](https://arrow.apache.org/docs/format/FlightSql.html) server for an
engine, through which clients in other languages query it over gRPC and receive the
results as Arrow vectors, such as Python with the Flight SQL driver of
[ADBC](https://arrow.apache.org/adbc/), and JDBC tools with the Flight SQL JDBC driver of
Arrow. The JAR of `fyrql-flight-sql-all` holds the server with the engine, code
generation and all connectors, and starts it from a configuration file. This builds it as
`fyrql-flight-sql-all/target/fyrql-flight-sql-all-<version>.jar`:

```
./mvnw -B -ntp clean package -pl fyrql-flight-sql-all -am -DskipTests
```

and this starts it:

```
java -jar fyrql-flight-sql-all-<version>.jar fyrql.yaml [--host HOST] [--port PORT]
```

The sources and the section `engine` of the file build the engine, as for the
[configuration file](#configuration-file), and its section `server` holds the settings
of the server, all of them optional; `--host` and `--port` win over those of the file:

```yaml
server:
  host: 0.0.0.0
  port: 32010
  tls:
    certificate: server.pem
    key: server.key
  users:
    alice: ${env:ALICE_PASSWORD}
  token: ${env:FYRQL_TOKEN}
  handleTimeout: 10m
  sessionTimeout: 1h
  maxQueries: 16
```

- `host` and `port`: where the server listens, by default `127.0.0.1` and 32010; port 0
  takes a free one.
- `users`: names with their passwords. A client signs in with a name and a password and
  goes on with the bearer token the server gives it.
- `token`: a bearer token that every client may send in the header `Authorization`.
  Without `users` and `token`, the server takes every client, and must then listen on a
  loopback address: another host without them is an error at the start.
- `tls.certificate` and `tls.key`: the certificate chain and its private key as PEM, the
  key in PKCS #8. TLS comes from the JDK, without native code; client certificates are
  not supported.
- `handleTimeout`: how long a statement or a prepared statement stays without use until
  the server forgets it, by default 10 minutes.
- `sessionTimeout`: how long the [session](#sessions) of a client stays without use until
  the server forgets it, by default an hour; `0` keeps it until the server stops.
- `maxQueries`: the most queries that run at once, by default twice the threads of the
  engine; another one fails with `RESOURCE_EXHAUSTED`. A query leaves its place before
  the server sends the end of its stream or its error, so that a client that has read
  it to its end can run its next query at once.

Unless the section `engine` sets `fileAccess`, the server sets it to `none`: statements of
clients read and write no files of the machine of the server, while the tables of the
sources stay readable. `fileAccess: read` with `allowedLocations` opens directories and
buckets to them; see [File access](#file-access). `queryTimeout` and `maxQueryTimeout`
under `engine` limit how long the queries of clients run, see [Time limits](#time-limits);
a client of Flight SQL asks for no timeout of its own, and a deadline of its call that
passes ends the query as a cancel does.

```yaml
engine:
  fileAccess: read
  allowedLocations: [data, "s3://lake/"]
  queryTimeout: 30s
  maxQueryTimeout: 5m
```

The server prints the line `FyrQL Flight SQL server <version> listening on
grpc+tcp://127.0.0.1:32010`, or with `grpc+tls`, and a line such as `File access from SQL:
read under /srv/data, s3://lake/; query timeout: 30s, at most 5m; session timeout: 1h`,
and runs until the JVM
ends, as with Ctrl+C, and then closes the engine. It logs the warnings and errors of Arrow and gRPC to
the standard error stream. The JAR has the same libraries renamed as the one of the
[JDBC driver](#dbeaver), and Arrow, gRPC, Netty and protobuf in their own packages; its
manifest sets the option of Arrow. The JDBC drivers of databases are not in it: for
sources of the type `jdbc`, they go on the class path next to it, and the server then
starts with its class name and that option (with `;` between the entries of the class
path on Windows):

```
java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "fyrql-flight-sql-all-<version>.jar:drivers/*" net.fyrql.flight.FlightSqlServerMain fyrql.yaml
```

An application starts the server over an engine of its own with
`FlightSqlServer.builder(engine).config(ServerConfig.defaults()).build().start()`, and
`ServerConfig.of(config)` reads the section `server` of a configuration file; closing the
server leaves the engine open. That engine keeps the file access it was built with,
`readWrite` by default, so that a server for others builds it with
`fileAccess(FileAccess.NONE)`, or `READ` and allowed locations.

The server runs queries as the JDBC driver does: a `SELECT`, `WITH`, `VALUES` or set
operation, or `EXPLAIN` or `EXPLAIN ANALYZE` followed by a statement, which gives the
plan as rows of one column `plan`; `EXPLAIN ANALYZE` runs when the client reads its
stream, and an update with `EXPLAIN` fails with `INVALID_ARGUMENT`. A query is bound when the client asks for it, and its batches go out as the
engine gives them, as fast as the client takes them; a client that cancels the query, or
closes its stream before the end, stops it. A prepared statement tells the types of its
parameters as their schema and takes one row of values. The calls for metadata give the
sources as catalogs with their schemas, tables and columns, the table type `TABLE`, the
name `FyrQL` and the version of the server, and the types. A statement that
[writes](#writing-and-loading) runs as an update, whose answer is the number of rows written, or,
sent as a query, when the client asks for its flight info, which then gives the row of
`Count`. A prepared statement that writes has a result without columns, as the Flight SQL
JDBC driver of Arrow expects, and runs once for each row of values the client sends; at
a row that fails, the rows before it stay written. `COPY` reads and writes files on the
machine of the server, with paths relative to its working directory, as far as the
[file access](#file-access) of the engine allows. Each statement is a
transaction of its own, and there is no bulk ingestion. An error has the message of
FyrQL, with the position in the text, and the status `INVALID_ARGUMENT` for a statement that does not
parse or bind, values out of range, a failed cast and a write the target refuses,
`UNAUTHORIZED`, `PERMISSION_DENIED` in gRPC, for a file the engine does not allow,
`RESOURCE_EXHAUSTED` for the memory budget and `maxQueries`, which counts statements that
write too, `CANCELLED` for a cancelled query, `TIMED_OUT` for one past its timeout, and
`INTERNAL` for an error of a source. All clients share the engine, with its threads and its memory budget.

Every client is a [session](#sessions). The server sets the cookie
`arrow_flight_session_id`, the one the clients of Arrow keep, finds the session of a
client by it and forgets one that belongs to another user. The actions
`SetSessionOptions`, `GetSessionOptions` and `CloseSession` of Flight set, show and end
the session with the names of the settings and their values as strings; a number is taken
as milliseconds for `statement_timeout`, an empty value resets a setting, and another name
or a value that does not fit comes back as `INVALID_NAME` or `INVALID_VALUE`. The Flight
SQL JDBC driver of Arrow keeps cookies by default, ADBC with the option
`adbc.flight.sql.rpc.with_cookie_middleware` set to `"true"`, and pyarrow with
`pyarrow.flight.ClientMiddlewareFactory` of its own. A client that keeps no cookies gets
the settings of the engine in each call, so that its `SET` does not reach its next
statement. A statement keeps the session it was bound in; a prepared statement binds in
the session of the call that runs it.

### Python

With Python 3.10 or later, `pip install adbc-driver-flightsql pyarrow pandas` installs a
client. With the files of the [JDBC example](#jdbc-driver), and the server started on
`fyrql.yaml` without the section `server`, this runs the query of that example:

```python
import adbc_driver_flightsql.dbapi as flight_sql

with flight_sql.connect("grpc://127.0.0.1:32010", autocommit=True) as conn, conn.cursor() as cur:
    cur.execute("""
        SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
        FROM orders AS o JOIN customers AS c ON o.customer = c.id
        WHERE o.amount > ?
        GROUP BY ALL
        ORDER BY revenue DESC""", parameters=(6.0,))
    table = cur.fetch_arrow_table()
    print(table.to_pandas())
```

```
  region  orders  revenue
0  south       1     20.0
1  north       1     10.5
```

`autocommit=True` spares the warning of ADBC that it cannot turn auto-commit off, as the
server has no transactions. `fetch_arrow_table()` gives a `pyarrow.Table` in the types of
[Arrow](#arrow), with the
type of FyrQL of each column in the field metadata `ARROW:FLIGHT:SQL:TYPE_NAME`; a
`HUGEINT` comes as `decimal128(38, 0)`, a `TIMESTAMP` as `timestamp[us]`, a
`TIMESTAMPTZ` as `timestamp[us, tz=UTC]`, a `TIME` as `time64[us]`, an `INTERVAL` as
`month_day_nano_interval`, a `UUID` as
the extension type `arrow.uuid`, whose values pyarrow gives as `uuid.UUID`, and lists,
structs and maps as those of pyarrow. With `users`, the client
signs in with `db_kwargs={"username": "alice", "password": ...}`; with `token`, with
`db_kwargs={adbc_driver_flightsql.DatabaseOptions.AUTHORIZATION_HEADER.value: "Bearer " + token}`.
With TLS, the URI starts with `grpc+tls://`, and a certificate that no known authority
signed is trusted with `adbc_driver_flightsql.DatabaseOptions.TLS_ROOT_CERTS.value` and
its text as PEM. `adbc_get_objects()`, `adbc_get_table_schema()` and `adbc_get_info()`
give the metadata. A parameter alone in the select list needs a cast, as in
`SELECT CAST(? AS DATE)`, and `execute` runs a query with one row of values at a time.
`executemany` runs a statement that writes once for each row of values; with a source
`mem` of the type `memory` in the file, this prints 3 with the cursor of the example:

```python
cur.execute("CREATE TABLE mem.notes (id INTEGER NOT NULL, text VARCHAR)")
cur.executemany("INSERT INTO mem.notes VALUES (?, ?)", [(1, "a"), (2, "b"), (3, None)])
print(cur.rowcount)
```

`adbc_ingest` is not supported. This was tried with pyarrow 26.0.0 and
`adbc-driver-flightsql` 1.12.0.

### JDBC tools

The Flight SQL JDBC driver of Arrow (`org.apache.arrow:flight-sql-jdbc-driver`, one JAR)
connects with a URL such as

```
jdbc:arrow-flight-sql://localhost:32010/?useEncryption=false&user=alice&password=secret
```

and the tests run the 22 TPC-H queries and statements that write through it. Its
`executeBatch` of a `PreparedStatement` sends all sets of values at once and gives one
count, the sum. It names the type of a column after
its Arrow type, so that `DECIMAL(p,s)` and `HUGEINT` are both `DECIMAL`, returned as
`BigDecimal`, a `DATE` is a `java.sql.Date`, a `TIME` a `java.sql.Time` in
milliseconds and an `INTERVAL` a `PeriodDuration` of the driver; the types of FyrQL are
in the type
names of `getColumns` of its `DatabaseMetaData`.

## Spring Boot

With the module `fyrql-spring-boot-starter`, an application of Spring Boot 4 gets an
engine as a bean, configured in its `application.yml`. The application adds the starter
and the connectors of its sources; Spring Boot it brings itself, in its own version of
4.x (the starter is built and tested with 4.1.2):

```xml
<dependency>
    <groupId>io.github.stefan-zobel</groupId>
    <artifactId>fyrql-spring-boot-starter</artifactId>
    <version>0.13.2</version>
</dependency>
<dependency>
    <groupId>io.github.stefan-zobel</groupId>
    <artifactId>fyrql-connector-jdbc</artifactId>
    <version>0.13.2</version>
</dependency>
```

The keys under `fyrql` take the form of the [configuration file](#configuration-file),
with the settings under `engine` and the sources under `sources`:

```yaml
fyrql:
  engine:
    threads: 4
    memory-budget: 2GiB
    query-timeout: 30s
  sources:
    - name: sales
      type: jdbc
      url: jdbc:postgresql://db/sales
      user: sales
      password: ${DB_PASSWORD}
    - name: files
      type: csv
      directory: data
```

```java
@Service
class Revenue {

    private final FyrQL engine;

    Revenue(FyrQL engine) {
        this.engine = engine;
    }

    List<Object[]> byRegion() {
        return engine.rows("SELECT r.region, sum(o.amount) FROM sales.orders AS o"
                + " JOIN files.regions AS r ON o.region_id = r.id GROUP BY r.region");
    }
}
```

The settings under `fyrql.engine` are those of [Engine settings](#engine-settings), with
the relaxed names of Spring: `memory-budget` and `memoryBudget` are the same key, and an
IDE completes them from the metadata of the starter. `memory-budget` takes the units of
FyrQL, so that `512MB` are 512,000,000 bytes and `512MiB` 536,870,912, not those of
Spring; `query-timeout` and `max-query-timeout` are durations such as `30s` or `5m`.
The sources under `fyrql.sources` are those of the file: a `name`, a `type` and the
options of the type below them, with nested keys joined by dots and lists by commas.
Unlike the settings, the options keep the names of their connector exactly, such as
`recordStatements`, since Spring does not relax the keys of a map; for the same reason
an option cannot come from an environment variable named after its key, which Spring
reads in lower case, but from a placeholder in the YAML.

Placeholders are those of Spring, such as `${DB_PASSWORD}`, which Spring resolves from
environment variables, system properties and its other sources. The form
`${env:NAME}` of the configuration file does not work in `application.yml`: Spring reads
it as the property `env` with the default `NAME` and gives the text `NAME`, without an
error.

`fyrql.config` names a configuration file of FyrQL in addition. Its sources come first,
with `${env:NAME}` as in any file and relative paths against its directory, then those
under `fyrql.sources`; the settings under `fyrql.engine` replace those of the file.
Relative paths under `fyrql.sources` and `fyrql.engine` are resolved against the working
directory. Two sources with the same name, in any case, are an error.

The engine closes with the application context. A bean of the type `FyrQL` of the
application replaces it, and `fyrql.enabled: false` turns the starter off. Sources and
functions of the application, such as tables in memory, join through beans of the type
`FyrQLBuilderCustomizer`, which the starter applies in the order of their `@Order` before
it builds the engine:

```java
@Bean
FyrQLBuilderCustomizer products(ProductRepository repository) {
    return builder -> builder.register(MemoryConnector.builder("app")
            .table("products", Product.class, repository.findAll()).build());
}
```

If the engine cannot be built, the application does not start, and the message names the
setting or the source at fault; the connectors opened until then are closed.

With `fyrql.datasource.enabled: true`, there is a `DataSource` of the engine, the bean
`fyrqlDataSource`, for code that wants JDBC, such as a `JdbcTemplate`. Its connections
share the engine and do not close it, each a [session](#sessions) of its own, as with
`new FyrQLDataSource(engine)` of the [JDBC driver](#jdbc-driver). It is no default
candidate: the `DataSource` of the application, which Spring Boot still creates from
`spring.datasource`, stays the one that is injected by its type, and that of FyrQL is
injected by its name:

```java
@Bean
JdbcTemplate fyrqlTemplate(@Qualifier("fyrqlDataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
}
```

An application that wants FyrQL as its only `DataSource` defines the bean itself, as
`new FyrQLDataSource(engine)`.

With the health module of Spring Boot, which `spring-boot-starter-actuator` brings, the
health indicator `fyrql` checks each source of the configuration by listing its schemas,
which reaches the database or service behind it. It is up when all answer and down when
one fails; its details give the status of each source and the error of one that failed,
shown as Spring shows details (`management.endpoint.health.show-details`). Sources that
a customizer registers, and those of the file formats, are not checked; without sources
of the configuration, the indicator runs `SELECT 1`. `management.health.fyrql.enabled:
false` turns it off.

## Supported SQL

Queries, the statements of [Writing](#writing-and-loading), and `EXPLAIN` and `EXPLAIN ANALYZE`
of either, see [Observing queries](#observing-queries), and `SET`, `RESET`, `SHOW` and
`USE`, see [Sessions](#sessions). Names, types and the meaning of the forms follow DuckDB
1.5.6.

- `SELECT [DISTINCT]` with `*`, `t.*`, `* EXCLUDE (...)` and `* REPLACE (...)`; an alias
  of the select list can be used by later items, in `WHERE`, `GROUP BY`, `HAVING` and
  `ORDER BY`.
- `FROM` with tables, subqueries, `VALUES`, the table functions `read_csv`,
  `read_csv_auto`, `read_json`, `read_json_auto`, `read_ndjson`, `read_ndjson_auto`,
  `read_parquet`, `parquet_scan`, `iceberg_scan`, `read_iceberg` and
  `iceberg_snapshots`, and file names such as `FROM 'data/orders.csv'`,
  `FROM 'events.jsonl'` or `FROM 'lake/*.parquet'`, as far as the
  [file access](#file-access) of the engine allows; `INNER`, `LEFT`,
  `RIGHT`, `FULL` and `CROSS JOIN` with `ON` and any condition without a subquery,
  `USING` or `NATURAL`; `SELECT` without `FROM`.
- `WHERE`, `GROUP BY` with expressions, positions, aliases and `ALL`, `HAVING`, `ORDER BY`
  with expressions, positions, aliases and `ALL`, `ASC`/`DESC`, `NULLS FIRST`/`LAST`,
  `LIMIT` and `OFFSET`.
- Subqueries in `WHERE`, `HAVING` and the select list: `[NOT] EXISTS (...)`,
  `x [NOT] IN (SELECT ...)` with `NULL`s as in DuckDB, and scalar subqueries, which are
  `NULL` without a row and fail with `More than one row returned by a subquery used as
  an expression` with more than one. A subquery may reference the columns of the query
  around it in its `WHERE`, as TPC-H Q2, Q4, Q17, Q20, Q21 and Q22 do; one that
  aggregates, only through equalities, and its `count` is 0 for a row it finds nothing
  for.
- `UNION [ALL]`, `EXCEPT [ALL]`, `INTERSECT [ALL]`, `WITH`.
- Expressions: arithmetic with `+ - * / % //`, comparisons, `AND`, `OR`, `NOT`, `BETWEEN`,
  `IN (...)`, `LIKE`, `ILIKE`, `IS [NOT] NULL`, `IS [NOT] DISTINCT FROM`, `CASE`, `CAST`,
  `TRY_CAST`, `::`, `||`, `EXTRACT(... FROM ...)`, `AT TIME ZONE`.
- Parameters `?` in prepared queries, through `engine.prepare`, a `PreparedStatement` of
  the JDBC driver or a prepared statement of the Flight SQL server, typed by their
  context (see [Using the engine](#using-the-engine)); named parameters such as `:name`
  are not taken.
- Types: `BOOLEAN`, `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT`, `HUGEINT`, `FLOAT`,
  `DOUBLE`, `DECIMAL(p,s)`, `VARCHAR`, `DATE`, `TIME`, `TIMESTAMP`,
  `TIMESTAMP WITH TIME ZONE` (or `TIMESTAMPTZ`), `INTERVAL` (see
  [Time zone](#time-zone)), and nested to any depth
  `T[]`, `STRUCT(name T, ...)` and `MAP(K, V)`. Literals are typed as in DuckDB, and a
  bare `NULL` or a string literal takes the type of its context.
- Nested values: lists `[1, 2]` (or `ARRAY[1, 2]`), structs `{'a': 1, 'b': 'x'}` (or
  `struct_pack(a := 1, b := 'x')`) and maps `MAP {'k': 1}` (or `map(keys, values)`).
  A field of a struct is `s.a`, `t.s.a`, `s.a.b` or `s['a']`, in any case; a name is a
  table and a column first, as in DuckDB, and only then a column and its fields. An
  element of a list is `l[1]`, counting from 1, and from the end if negative; a position
  outside the list is `NULL`. The value of a key of a map is `m['k']`. Nested values
  compare, sort, group and cast element by element; two structs unite and cast by the
  names of their fields, so `CASE WHEN c THEN {'a': 1} ELSE {'b': 2} END` is a
  `STRUCT(b INTEGER, a INTEGER)`.
- `unnest`: in the select list, `SELECT id, unnest(tags) FROM t` repeats each row once
  per element of its list, also inside an expression or over an aggregate, as in
  `unnest(list(x)) + 1`; a row whose list is empty or `NULL` is left out. An item
  `unnest(s)` of a struct becomes one column per field. In `FROM`,
  `FROM t, unnest(t.tags) AS u(tag)` (also with `LATERAL`, `CROSS JOIN` or
  `JOIN ... ON`) reads the items before it, and `FROM unnest([1, 2]) AS u(x)` stands
  alone.
- Scalar functions: `coalesce`, `ifnull`, `nullif`, `if`, `greatest`, `least`, `typeof`;
  `abs`, `ceil`, `floor`, `trunc`, `round`, `sign`, `sqrt`, `ln`, `log10`, `log`, `log2`,
  `exp`, `power`, `pi`, `random`; `length`, `lower`, `upper`, `substring`, `trim`,
  `ltrim`, `rtrim`, `replace`, `contains`, `starts_with`, `ends_with`, `strpos`, `left`,
  `right`, `repeat`, `reverse`, `lpad`, `rpad`, `split_part`, `concat`, `concat_ws`;
  `current_date`, `now`, `current_time`, `localtimestamp`, `current_setting`, `year`
  to `microsecond` and the other parts of a date, a time or an interval, `date_part`,
  `date_trunc`, `date_diff`, `age`, `epoch`, `epoch_ms`, `epoch_us`, `epoch_ns`,
  `last_day`, `monthname`, `dayname`, `make_date`, `make_time`, `make_timestamp`,
  `make_timestamptz`, `to_timestamp`, `to_years` to `to_microseconds`, `timezone`,
  `strftime` (with `%z` and `%Z`), `strptime`, `try_strptime`; `list_value`,
  `list_extract`, `len`, `array_length`, `list_contains`, `list_sort`,
  `list_reverse_sort`, `map`, `map_extract`, `map_extract_value`, `struct_extract`,
  `struct_pack`; and DuckDB's aliases of these names.
- Aggregates: `count`, `sum`, `avg`, `min`, `max`, `string_agg`, `list`, `first`, `last`,
  `any_value`, `bool_and`, `bool_or`, with `DISTINCT` and `ORDER BY` in the call.
- Window functions: `row_number`, `rank`, `dense_rank`, `percent_rank`, `cume_dist`,
  `ntile`, `lag` and `lead` with an offset and a default, `first_value`, `last_value`,
  `nth_value`, and the aggregates above, also with `DISTINCT`, `ORDER BY` and
  `FILTER (WHERE ...)` in the call, over `OVER (PARTITION BY ... ORDER BY ...)` with a
  frame of `ROWS`, `RANGE` or `GROUPS`, `BETWEEN` any two of `UNBOUNDED PRECEDING`,
  `n PRECEDING`, `CURRENT ROW`, `n FOLLOWING` and `UNBOUNDED FOLLOWING`, and
  `EXCLUDE CURRENT ROW`, `GROUP`, `TIES` or `NO OTHERS`. The offset of a `RANGE` frame is
  a number for a numeric order, a number of days or an interval such as
  `INTERVAL 1 MONTH` for a `DATE` or `TIMESTAMP`, and an interval of hours, minutes or
  seconds for a `TIMESTAMPTZ`. `IGNORE NULLS` goes with `lag`, `lead`,
  `first_value`, `last_value` and `nth_value`. A window may be named in the `WINDOW`
  clause and used as `OVER w`; `QUALIFY` filters the rows by the values of window
  functions. Over an aggregation, a window function reads the groups and aggregates, as
  in `sum(count(*)) OVER ()`.

Over the data of the [first example](#using-the-engine), window functions rank the
orders of each region and add up their amounts:

```sql
SELECT c.region, o.id, o.amount,
       rank() OVER (PARTITION BY c.region ORDER BY o.amount DESC) AS place,
       sum(o.amount) OVER (PARTITION BY c.region) AS total
FROM orders AS o JOIN customers AS c ON o.customerId = c.id
ORDER BY c.region, place
```

```
[north, 1, 10.50, 1, 15.75]
[north, 3, 5.25, 2, 15.75]
[north, 4, null, 3, 15.75]
[south, 2, 20.00, 1, 20.00]
Sort #0, #3
  Project #4 AS region, #0 AS id, #1 AS amount, #5 AS place, #6 AS total
    Window PARTITION BY #4: sum(#1) AS "sum(o.amount) OVER (PARTITION BY c.region)"
      Window PARTITION BY #4 ORDER BY #1 DESC: rank() AS "rank() OVER (PARTITION BY c.region ORDER BY o.amount DESC NULLS LAST)"
        Join INNER ON l#2 = r#0 [codegen] [parallel]
          Project #0 AS id, #2 AS amount, CAST(#1 AS BIGINT) AS "#key0" [codegen]
            Scan mem.main.orders (id BIGINT, customerId INTEGER, amount DECIMAL(10,2))
          Scan csv.main.customers (id BIGINT, region VARCHAR) [parallel]
```

A `Window` node appends one column per call to the rows of its input, for all calls with
the same partition and order; `NULL`s come last in both directions, as in DuckDB. It reads
its whole input and sorts it, gives each row its frame, and computes an aggregate over a
moving frame from a segment tree, in logarithmic time per row; `string_agg`, `list`, and
aggregates with `DISTINCT` or `ORDER BY` in the call go over each frame anew. With
`QUALIFY`, the largest order of each region is

```sql
SELECT c.region, o.id
FROM orders AS o JOIN customers AS c ON o.customerId = c.id
WINDOW w AS (PARTITION BY c.region ORDER BY o.amount DESC)
QUALIFY row_number() OVER w = 1
ORDER BY c.region
```

with the rows `[north, 1]` and `[south, 2]`.

*Not yet supported*: the type `TIME WITH TIME ZONE` (`TIMETZ`), an offset of a `RANGE`
frame over a `TIME`, and one of days, months or years over a `TIMESTAMPTZ`, `PIVOT`, `SEMI JOIN` and `ANTI JOIN` in SQL,
lambdas, slices such as `l[1:2]`, structs without names
(`row(1, 'a')`), arrays of a fixed size (`INTEGER[3]`), and `WITH RECURSIVE`. Of the
subqueries: those in `ORDER BY`, `GROUP BY`, `ON` and the arguments of aggregates;
`ANY` and `ALL`; `IN` over more than one column. Of the correlated ones: a reference
to the outer query outside the `WHERE` of the subquery or more than one level out; a
condition that references the outer query and holds a subquery, or an `IN` whose value
does; a correlated condition of an aggregating subquery that is not an equality;
`EXISTS` and `IN` over a subquery that aggregates without `GROUP BY`; `LIMIT`, `OFFSET`,
and `HAVING` without `GROUP BY`; `DISTINCT` in a scalar subquery. Of the window
functions: `FILTER` without `OVER`, `WITHIN GROUP`, a window that adds to a named one,
as in `OVER (w ORDER BY a)`, offsets of a frame that are no constants or `NULL`, an order
in the arguments of a function that is no aggregate, as in `lag(a ORDER BY b)`, window
functions in a correlated subquery, and subqueries or `unnest` inside window functions.

### Time zone

A `TIMESTAMPTZ` (`TIMESTAMP WITH TIME ZONE`) is an instant, held as microseconds since
1970 in UTC, and it is shown, cast and taken apart in the time zone of the session, as
in DuckDB. That zone is the one of the JVM, unless the builder sets another with
`timeZone(ZoneId)`, the [configuration file](#configuration-file) with `timeZone` under
`engine`, or the URL of the [JDBC driver](#jdbc-driver) with `timeZone`; the
[Flight SQL server](#flight-sql-server) takes the one of its configuration file.
`SET TimeZone = 'Europe/Berlin'` changes it for the [session](#sessions), `RESET
TimeZone` restores the one of the builder, and `SHOW TimeZone` and
`current_setting('TimeZone')` give it. A zone is a name of the tz database, such as
`Europe/Berlin` or `UTC`, or an abbreviation such as `PST`; an offset such as `+02:00` is
none. Each connection of the JDBC driver and each client of the Flight SQL server is a
session with a zone of its own.

In the zone of the session, a literal or a string without an offset is read; `now()` and
`current_timestamp` give the instant of the query, and `current_date`, `current_time`
and `localtimestamp` its local values; the parts such as `year` and `hour`,
`date_trunc`, `date_diff`, `strftime`, `age` and the casts to and from `DATE`,
`TIMESTAMP` and `VARCHAR` compute. Adding an interval moves the months and days in the
calendar of the zone, also over a change of daylight saving time, and the hours,
minutes and seconds on the instant. A local time in a gap moves forward; one that
occurs twice takes the later offset, while a day added into it keeps the offset it
comes from, as in DuckDB. `x AT TIME ZONE 'zone'` and `timezone('zone', x)` turn an
instant into the `TIMESTAMP` it is in that zone, and a `TIMESTAMP` into the instant it
is there. The sources of CSV and JSON write an instant in the zone of the session and
read text without an offset in it. In Berlin, where the clocks went forward on
31 March 2024:

```sql
SET TimeZone = 'Europe/Berlin';
SELECT TIMESTAMPTZ '2024-03-30 12:00:00+01' + INTERVAL 1 DAY,                -- 2024-03-31 12:00:00+02
       TIMESTAMPTZ '2024-03-30 12:00:00+01' + INTERVAL 24 HOURS,             -- 2024-03-31 13:00:00+02
       date_trunc('day', TIMESTAMPTZ '2024-10-27 12:00:00+01'),              -- 2024-10-27 00:00:00+02
       TIMESTAMPTZ '2024-10-27 12:00:00+01' AT TIME ZONE 'America/New_York', -- 2024-10-27 07:00:00
       TIME '23:30:00' + INTERVAL '45 minutes',                              -- 00:15:00
       TIMESTAMP '2024-03-31 12:00:00' - TIMESTAMP '2024-01-01 00:00:00'     -- 90 days 12:00:00
```

A `TIME` is a time of day from `00:00:00` to `24:00:00` in microseconds, and arithmetic
with it wraps around midnight. An `INTERVAL` has months, days and microseconds, written
as `1 year 2 months -3 days 04:05:06.5`. Two intervals compare as in DuckDB, with 24
hours to a day and 30 days to a month, so that `INTERVAL '1 month' = INTERVAL '30 days'`,
while arithmetic keeps the three parts apart. A product with a number that has a fraction
moves the fraction of the months to days, at 30 days to a month, and what is left of a
day to the time, whole days of it to the days, as DuckDB and PostgreSQL do; a division
multiplies by the reciprocal of the divisor, and `avg` divides the sums of the three
parts and carries their remainders to the next smaller part.

In the rows of `rows()`, a `DATE` is a `LocalDate`, a `TIME` a `LocalTime` (`24:00:00` is
`LocalTime.MAX`), a `TIMESTAMP` a `LocalDateTime` and a `TIMESTAMPTZ` an `OffsetDateTime`
in the zone of the session, also inside a `LIST`, `STRUCT` or `MAP`; an `INTERVAL` is a
`net.fyrql.core.value.Interval`. The values of parameters take the same classes, and an
`Instant` or `ZonedDateTime` for a `TIMESTAMPTZ` and a `Duration` or `Period` for an
`INTERVAL` as well; digits below a microsecond are cut off. The batches of
`QueryResult.nextBatch()` and of connectors hold the values as the vectors do: a `DATE`
is an `Integer` of days since 1970-01-01, a `TIMESTAMP` a `Long` of microseconds since
1970-01-01 00:00:00, a `TIME` one since midnight, and a `TIMESTAMPTZ` one since 1970 in
UTC; `Values.toJava` and `Values.fromJava` in `net.fyrql.core.type` convert between the
two. The memory connector takes the classes of `java.time` as well. A plan built in Java from the nodes of `PlanNode` has no time zone: only the binder
gives the casts and functions of a query the zone of the session, and such a plan
computes in UTC.

## Differences from DuckDB

FyrQL follows DuckDB 1.5.6, and its tests compare every result with DuckDB over the same
data. Where the two differ on purpose, or by a limit of either, the difference is listed
here; the random tests explain such differences by a ledger of their own, see
[Building and testing](#building-and-testing). What FyrQL does not take at all is under
[Supported SQL](#supported-sql).

### Queries and functions

- `SELECT DISTINCT` cannot be ordered by a column that is not selected.
- A string literal is not converted to the type of a function argument, as in
  `sqrt('4')`.
- The name of an output column that is no plain column follows FyrQL's own text form of
  the expression, which is not always DuckDB's.
- `list(x ORDER BY x)` orders intervals as `ORDER BY` does, by the value they compare
  by; DuckDB 1.5.6 orders them there by their months, days and microseconds as written.
- Intervals that compare as equal but are written differently, such as `30 days`,
  `720:00:00` and `1 month`, keep the order of their input under `ORDER BY` and in
  `string_agg(x, ' | ' ORDER BY x)`; DuckDB may give them in another order.
- An expression that fails for some values, such as a cast of a string or a division of
  integers by zero, may fail in one of the two engines and not in the other: each moves
  conditions to other places, such as below a join or into a source, and evaluates
  them on other rows, or folds constants at another time. A Parquet source skips the row
  groups and pages in which no row passes the filters, so that their rows are not
  evaluated at all; without pushdown they are. Within FyrQL, a compiled
  pipeline runs all its nodes for one row before the next row, where the interpreter
  runs each node over a whole batch: when rows of one batch fail in different nodes, the
  two may report the failure of another node first, with another error; whether a query
  fails is the same.
- DuckDB 1.5.6 finds a row for a correlated `EXISTS` or `IN` inside an expression, such
  as `a OR EXISTS (SELECT * FROM u WHERE u.k = t.k AND u.d <> t.d)`, where `t.d` is
  `NULL`, unlike for the same `EXISTS` as a condition of `WHERE`; FyrQL finds none.
- A select list unnests one list, which may appear in several items; DuckDB unnests
  several lists side by side. `unnest` in a `LEFT JOIN`, with named arguments such as
  `recursive := true`, and of a struct that only an aggregate gives are not taken.
- A parameter `?` gets its type from its context when the query is prepared, as a bare
  `NULL` would; as the argument of a function, that is the first type the function takes
  of `BIGINT`, `DOUBLE`, `INTEGER` and so on, where DuckDB may choose another, and alone
  in the select list it has none and needs a cast.

### Types and values

- A constant `DATE` plus or minus an interval constant of days, months or years, such
  as `DATE '1998-12-01' - INTERVAL '90' DAY`, is computed when the query is bound and
  stays a `DATE`, so that a filter against it can go to a source; DuckDB gives a
  `TIMESTAMP`. With a column, or an interval with a time of day, the result is a
  `TIMESTAMP` as in DuckDB.
- `current_time` is a `TIME` in the time zone of the session; DuckDB gives a
  `TIME WITH TIME ZONE`.
- `avg` does not take a `DATE`. Over `BIGINT`, it divides the sum in a double, as DuckDB
  does on Windows; DuckDB on x86-64 Linux divides it in 80 bits, and its result may
  differ in the last binary digit.
- The empty list `[]`, a list of bare `NULL`s and `map()` have `INTEGER` elements; DuckDB
  gives them a type of its own for `NULL`, which it unites with any other type.
- A string does not convert to a nested type, as in `'[1, 2]'::INTEGER[]`.
- A cast of a map whose keys become equal, such as `'1'` and `'01'` to `INTEGER`, fails;
  DuckDB keeps both keys.
- A `MAP(K, V)` type cannot be written in a `CAST`, since the parser does not read it.

### Window functions

- A window whose first expression of `PARTITION BY` is in parentheses and followed by
  others, as in `PARTITION BY (a % 2), k`, does not parse; `PARTITION BY ((a % 2)), k`
  does.
- A window function whose value is not used is not computed, and so does not fail, as it
  does in DuckDB on a negative offset of a `RANGE` frame.
- `lag` and `lead` give their default where the offset reaches beyond the range of
  `BIGINT`; DuckDB fails with an overflow.
- An argument whose type does not cast to the type the function needs is an error when
  the query is bound: a `DATE` for the count of `ntile`, the position of `nth_value` or
  the offset of `lag` and `lead`, or a `BIGINT` for the default of `lag` of a `DATE`.
  DuckDB fails only at a row whose value is not `NULL`.
- A `-0.0` by which a window partitions or orders keeps its sign; DuckDB passes such
  columns on as `0.0`.
- DuckDB 1.5.6 takes rows into a frame whose start lies after its end when the frame has
  an `EXCLUDE`, as in `GROUPS BETWEEN 0 PRECEDING AND 3 PRECEDING EXCLUDE TIES`, where it
  takes earlier groups; FyrQL keeps such a frame empty.

### Sources and files

- An Iceberg table is read without the extension `iceberg`. Its directory needs no
  `version-hint.text`: without it, the metadata file with the highest version counts,
  as with `unsafe_enable_version_guessing`; a moved table needs no `allow_moved_paths`,
  and its deletion vectors are found as well; `ref` reads a branch or tag. DuckDB 1.5.6
  crashes on manifests compressed with `zstandard`, which FyrQL reads. The column
  `timestamp_ms` of `iceberg_snapshots` must be quoted, `"timestamp_ms"`, since the parser
  takes `TIMESTAMP_MS`, `TIMESTAMP_NS` and `TIMESTAMP_S` for types.
- Files in S3 need a registered source of the type `s3`, rather than the extension
  `httpfs` and `CREATE SECRET`. Locations of `gs://`, `az://`, `http://` and `https://`,
  `**` in a pattern and a pattern before the last part of a location are not supported.
- A JDBC source with `splitRows` reads the parts of a table in transactions of their
  own, each with its own state of the table; while others write to the table, a result
  may mix states, and a row whose key column changes may be missing or come twice.
- Of a Parquet file, FyrQL reads the types it does not have as others: an unsigned
  integer as the next wider type with a sign, up to `HUGEINT` for a `UBIGINT`; a `TIME`
  adjusted to UTC or in nanoseconds as a `VARCHAR` with the text DuckDB gives it; a
  timestamp in nanoseconds as a `TIMESTAMP` or a `TIMESTAMPTZ` in microseconds, cut
  toward zero as DuckDB casts it. A list in
  one of the older forms whose repeated group has one field and is named `array` or ends
  in `_tuple` is a list of that group, such as `STRUCT(x INTEGER)[]`, as the specification
  of Parquet has it; DuckDB takes the field as the element, `INTEGER[]`. A string is not
  checked for valid UTF-8, a `BSON` column is a `BLOB` that DuckDB does not read, and
  files compressed with `LZ4` (the older framing of Hadoop), `BROTLI` or `LZO` are an
  error that names the codec and the file.
- Of an OData service, FyrQL reads the types it does not have as others: an
  `Edm.Decimal` without a `Precision`, with one above 38 or with a `Scale` of `variable`
  or `floating` as a `DOUBLE`, and a member of an enumeration as a `VARCHAR` with its
  name. An `Edm.Time` or `Edm.TimeOfDay` is a `TIME`, an `Edm.Duration` an `INTERVAL`,
  which no filter goes to the service for, and an `Edm.DateTimeOffset` a `TIMESTAMPTZ`.
  `Edm.Stream`, the geographic types and navigation properties are no columns.
- A Parquet file that `COPY` writes holds a `HUGEINT` as a `DECIMAL(38,0)`, where DuckDB
  writes a `DOUBLE`; `CREATE OR REPLACE TABLE` drops and creates in two transactions.
- `COPY ... FROM` takes the first line of a CSV file as a header unless `HEADER false` is
  given, where DuckDB detects whether it is one; it reads a quote inside a quoted value
  only as two quotes, so that `ESCAPE` must be the quote.

### Execution

- On several threads, the last binary digits of `sum` and `avg` over `FLOAT` and
  `DOUBLE`, and which error is reported when rows of several parts of a source fail, may
  change from run to run, as in DuckDB; see [Parallel execution](#parallel-execution).
- DuckDB also spills the hash tables of joins, and a single group or partition that does
  not fit; FyrQL fails then, see [Memory budget and spilling](#memory-budget-and-spilling).

## Optimizer

Subqueries are joins by the time the optimizer sees the plan. The binder turns an
`EXISTS` or `IN` that is a conjunct of `WHERE` into a `SEMI` join and a `NOT EXISTS`
into an `ANTI` join; other `EXISTS` and `IN`, and `NOT IN` over an uncorrelated
subquery, become `MARK` joins, which add a column that is true, false or `NULL` as `IN`
is, and a scalar subquery a `SINGLE` join, a left join that fails when a row finds more
than one. A correlated subquery is joined on its equalities with the outer query, and
its other correlated conditions become the condition of the join; one that aggregates
also groups by the columns of its equalities, so that it runs once for all rows of the
outer query.

Before a plan runs, the module `fyrql-optimizer` rewrites it in these steps; each step
keeps the rows of the plan, and a second run changes nothing:

1. Constants are folded: `DATE '1998-12-01' - INTERVAL '90' DAY` becomes a date,
   `x > 1 OR TRUE` becomes `TRUE`, and a filter that is always true goes away. A
   condition that every branch of an `OR` contains is taken out in front of it, such as
   `p_partkey = l_partkey` in TPC-H Q19, which so becomes the key of a join.
2. Conditions move toward the tables: through projections, inner joins, the groups of
   an aggregation, the partitions of window functions, orders and unions, and through an outer join only to the side whose
   rows it keeps, as through a semi, anti, mark and single join to the left one. An
   equality between the two sides of a join, in a condition above it or in its own,
   becomes a key of the join, so that `FROM a, b WHERE a.id = b.id` is a hash join and
   not a cross product.
3. Inner joins are ordered by their estimated sizes. The row counts of the tables come
   from the catalogs of the sources: exact for Java collections and Parquet files, from
   one `count(*)` per table for a database, estimated from the sample and the size of the
   file for CSV and JSON, from one request of `/$count` per entity set for an OData
   service with `rowCounts`, and 1,000 where a source does not know. A condition keeps a fixed share of the
   rows, such as a tenth for `=` and 0.3 for `<`. Up to ten tables are ordered by
   dynamic programming over the connected subsets, more of them one join at a time; the
   smaller input of each join becomes its right one, on which the hash table is built.
   A semi or anti join above inner joins goes onto the table whose columns it reads,
   before that table is joined with the others.
4. Every scan reads only the columns in use, and of a struct only the fields in use
   where its source reads fields (Parquet and JSON files, HTTP endpoints, OData services
   of version 4 and Java collections);
   `explain` shows such a field as `s.a VARCHAR`. A window function whose value is not
   used is not computed.
5. The largest part of the plan that reads only tables of one database and holds a
   join is offered to that database, which takes it as one statement if it evaluates
   all of it exactly as FyrQL, and otherwise is offered the parts below. The join
   order of step 3 already counts such a part with only the rows that leave the
   database, and so puts tables of one database next to each other where that is not
   more expensive.
6. With pushdown, the source of every other scan takes over what it can of the filter,
   aggregation, order and limit above it.

Steps 4 for fields, 5 and 6 change what a source does and are left out with
`pushdown(false)`. At scale factor 1, the plan of TPC-H Q3 reads the orders and
customers of PostgreSQL in one statement, which filters and joins them; the filter on
the Parquet file of `lineitem` stays in the engine, which builds its hash table on the
smaller result of the database. The filter, the join and the two projections above it
run as one compiled [pipeline](#code-generation), on all threads over the row groups of
`lineitem`, which share the hash table:

```
Limit 10
  Sort #1 DESC, #2
    Project #0 AS l_orderkey, #3 AS revenue, #1 AS o_orderdate, #2 AS o_shippriority
      Aggregate GROUP BY #0, #1, #2: sum(#3) AS "sum((l_extendedprice * (1 - l_discount)))" [codegen]
        Project #2 AS "#group0", #0 AS "#group1", #1 AS "#group2", (#3 * (CAST(1 AS INTEGER) - #4)) AS "#argument0" [codegen pipeline] [parallel]
          Project #6 AS o_orderdate, #7 AS o_shippriority, #0 AS l_orderkey, #1 AS l_extendedprice, #2 AS l_discount [codegen]
            Join INNER ON l#0 = r#0 [codegen]
              Filter (#3 > DATE '1995-03-15') [codegen]
                Scan parquet.main.lineitem (l_orderkey INTEGER, l_extendedprice DECIMAL(15,2), l_discount DECIMAL(15,2), l_shipdate DATE)
              Remote db [SELECT t0.c0 AS c0, t0.c1 AS c1, t0.c2 AS c2, t0.c3 AS c3, t1.c0 AS c4, t1.c1 AS c5 FROM (SELECT * FROM (SELECT "o_orderkey" AS c0, "o_custkey" AS c1, "o_orderdate" AS c2, "o_shippriority" AS c3 FROM "public"."orders") t2 WHERE (t2.c2 < DATE '1995-03-15')) t0 INNER JOIN (SELECT * FROM (SELECT "c_custkey" AS c0, "c_mktsegment" AS c1 FROM "public"."customer") t3 WHERE (t3.c1 = 'BUILDING')) t1 ON t0.c1 = t1.c0]
                | Join INNER ON l#1 = r#0
                |   Filter (#2 < DATE '1995-03-15')
                |     Scan db.main.orders (o_orderkey INTEGER, o_custkey INTEGER, o_orderdate DATE, o_shippriority INTEGER)
                |   Filter (#1 = 'BUILDING')
                |     Scan db.main.customer (c_custkey INTEGER, c_mktsegment VARCHAR)
```

Below a `Remote` node, `explain` shows the part of the plan that the statement stands
for. A database takes over a join under the rules of the table above for its keys,
conditions and filters, as `INNER`, `LEFT`, `RIGHT` or `FULL JOIN` (H2 and MySQL without
`FULL`) and a semi or anti join as `WHERE [NOT] EXISTS (...)`, with projections of plain
columns, and with an order and a limit only on top. In Q22, the customers without orders
are an anti join of two tables of PostgreSQL, which the database takes over; the
average balance of the uncorrelated scalar subquery is a `SINGLE` join without keys
over one row, and the filter on `substring` stays in the engine:

```
Sort #0
  Project #0 AS cntrycode, #1 AS numcust, #2 AS totacctbal
    Aggregate GROUP BY #0: count(*) AS "count_star()", sum(#1) AS "sum(c_acctbal)" [codegen]
      Project #0 AS "#group0", #1 AS "#argument0" [codegen pipeline]
        Project substring(#1, CAST(1 AS INTEGER), CAST(2 AS INTEGER)) AS cntrycode, #2 AS c_acctbal [codegen]
          Filter (#2 > #3) [codegen]
            Join SINGLE [codegen]
              Filter (substring(#1, CAST(1 AS INTEGER), CAST(2 AS INTEGER)) IN ('13', '31', '23', '29', '30', '18', '17')) [codegen]
                Remote db [SELECT t0.c0 AS c0, t0.c1 AS c1, t0.c2 AS c2 FROM (SELECT "c_custkey" AS c0, "c_phone" AS c1, "c_acctbal" AS c2 FROM "public"."customer") t0 WHERE NOT EXISTS (SELECT 1 FROM (SELECT t2.c0 AS c0 FROM (SELECT "o_custkey" AS c0 FROM "public"."orders") t2) t1 WHERE t0.c0 = t1.c0)]
                  | Join ANTI ON l#0 = r#0
                  |   Scan db.main.customer (c_custkey INTEGER, c_phone VARCHAR, c_acctbal DECIMAL(15,2))
                  |   Project #0 AS "#corr0"
                  |     Scan db.main.orders (o_custkey INTEGER)
              Project #0 AS "avg(c_acctbal)"
                Aggregate avg(#0) AS "avg(c_acctbal)"
                  Project #1 AS "#argument0" [parallel]
                    Filter (substring(#0, CAST(1 AS INTEGER), CAST(2 AS INTEGER)) IN ('13', '31', '23', '29', '30', '18', '17')) [codegen]
                      Scan db.main.customer (c_phone VARCHAR, c_acctbal DECIMAL(15,2)) [pushed: WHERE (#1 > CAST('0.00' AS DECIMAL(3,2)))]
```

The correlated `EXISTS` and `NOT EXISTS` of Q21 become `Join SEMI ON l#0 = r#0 AND
(r#1 <> l#1)` and the same `ANTI` join: the order keys are equal, and the condition
asks for another supplier. Step 3 puts both on the scan of `lineitem`, before its join
with the suppliers of one nation, which would keep fewer rows; the optimizer does not
yet weigh such orders by their costs.

## Code generation

With the module `fyrql-codegen` on the class path, the engine compiles expressions,
filters, the keys of joins and aggregations, and whole pipelines into Java bytecode with
[Janino](https://github.com/janino-lts/janino) (janino-lts 3.1.20). The JAR of the module
carries Janino renamed to `net.fyrql.internal.janino`, so that an application may use a
Janino of its own. Without the module everything runs in the interpreter; the results are
the same either way, which the tests check for every random query, and so is whether a
query fails (see [Differences from DuckDB](#differences-from-duckdb)).

- A pipeline is a chain of filters, projections and joins above one source, such as a
  scan, an aggregation or a sort; the chain goes through the side of each join that
  probes its hash table. It becomes one class
  with one loop over the rows of a batch, which tests the filters, computes the
  projected values and looks up the keys of the joins row by row, and writes only the
  rows that leave the chain. The build sides of the joins are read into their hash
  tables before the source, as the interpreter reads them, and may be pipelines of their
  own. A chain is compiled when it holds a join or at least two nodes that compute
  something; a node that the compiler does not take ends it and runs as an operator.
  `explain` marks the top node of a pipeline with `[codegen pipeline]` and its other
  nodes with `[codegen]`.
- `FyrQL.builder().codegen(CodegenMode.AUTO)`, the default: a pipeline is compiled when
  the query starts if the estimated row count of its source reaches 10,000
  (`codegenThreshold`); a source that does not tell its size, such as an HTTP endpoint or
  a gzipped JSON file, counts 1,000 rows and stays in the interpreter. An operator
  outside a pipeline interprets its first rows and compiles its expressions or keys once
  it has processed 10,000 of them. `ALWAYS` compiles every pipeline and every operator
  from the first row on, `OFF` nothing. The system property `fyrql.codegen` (`off`,
  `auto` or `always`) sets the default of the builder.
- Compiled classes are cached by their source, which does not contain the values of
  literals: `quantity < 24` and `quantity < 30` share a class, as do the pipelines of
  queries of the same shape. `engine.codegenStatistics()` tells how many classes were
  compiled, how many requests the cache answered, and the time spent.
- Expressions over nested types and expressions of more than 400 nodes stay in the
  interpreter, as does an expression whose compilation fails. The code of a long
  expression becomes a method of its own, and a class with a method of more than 8,000
  bytes of bytecode, which the JIT compiler of HotSpot leaves alone, is not used.
- `-Dfyrql.codegen.dump=<directory>` writes every compiled source into the directory. The
  filter `quantity < 24 AND discount > 0.05` over an `INTEGER` and a `DECIMAL(4,2)` column
  becomes a loop over the rows of a batch, with a variable for `NULL` and one for the value
  of each step, and the second comparison only for the rows that pass the first:

```java
public int[] select(Batch in) {
    int n = in.rowCount();
    IntVector c0 = (IntVector) in.column(0);
    DecimalVector c1 = (DecimalVector) in.column(1);
    int[] rows = new int[n];
    int m = 0;
    for (int i = 0; i < n; i++) {
        boolean p0 = false;
        boolean n1 = c0.isNull(i);
        int v2 = c0.getInt(i);
        boolean n3 = false;
        boolean v4 = false;
        if (n1) {
            n3 = true;
        } else {
            v4 = v2 < f0;
        }
        boolean p5 = !n3 && v4;
        if (p5) {
            boolean n6 = c1.isNull(i);
            long v7 = c1.getUnscaledLong(i);
            long v8 = (long) f1;
            boolean n9 = false;
            boolean v10 = false;
            if (n6) {
                n9 = true;
            } else {
                v10 = v7 > v8;
            }
            boolean p11 = !n9 && v10;
            p0 = p11;
        }
        if (p0) {
            rows[m++] = i;
        }
    }
    return m == n ? rows : java.util.Arrays.copyOf(rows, m);
}
```

The filter and the projection of TPC-H Q6 over the four columns of `lineitem` it reads
form a pipeline, which is the method `consume` of its class. The loop calls the filter,
which is long enough for a method `s0` of its own and reads like the one above,
computes `l_extendedprice * l_discount` only for the rows that pass, and writes the
products into an array that becomes the column of the output batch. The fields `f0` to
`f6` hold the literals, a type and the schema; `stage` tells which node of the chain a
failure comes from, so that the error names that node as the interpreter would:

```java
public Batch consume(Batch in, JoinTable[] tables) {
    int n = in.rowCount();
    DecimalVector c0 = (DecimalVector) in.column(0);
    DecimalVector c1 = (DecimalVector) in.column(1);
    DecimalVector c2 = (DecimalVector) in.column(2);
    IntVector c3 = (IntVector) in.column(3);
    int cap = n;
    long[] out0 = new long[cap];
    boolean[] nulls0 = null;
    int m = 0;
    int stage = 0;
    try {
        for (int i = 0; i < n; i++) {
            stage = 0;
            if (!s0(i, c3, c2, c0)) {
                continue;
            }
            stage = 1;
            boolean n29 = c1.isNull(i);
            long v30 = c1.getUnscaledLong(i);
            boolean n31 = c2.isNull(i);
            long v32 = c2.getUnscaledLong(i);
            boolean n33 = false;
            long v34 = 0L;
            if (n29 || n31) {
                n33 = true;
            } else {
                v34 = ScalarOps.decimal(Expression.ArithmeticOp.MULTIPLY, v30, v32, f5);
            }
            if (n33) {
                if (nulls0 == null) {
                    nulls0 = new boolean[cap];
                }
                nulls0[m] = true;
            } else {
                out0[m] = v34;
            }
            m++;
        }
    } catch (RuntimeException e) {
        failed = stage;
        throw e;
    }
    if (m == 0) {
        return null;
    }
    Vector[] columns = new Vector[1];
    if (m < cap) {
        out0 = java.util.Arrays.copyOf(out0, m);
        if (nulls0 != null) {
            nulls0 = java.util.Arrays.copyOf(nulls0, m);
        }
    }
    columns[0] = DecimalVector.wrap(f5, out0, nulls0);
    return new Batch(f6, m, java.util.Arrays.asList(columns));
}
```

A join adds the hash of its keys to the loop, and a loop over the rows of its hash
table with these keys, inside which the nodes above the join run.

[Performance](#compiled-code-and-scans) compares compiled code with the interpreter.

## Parallel execution

A query runs on several threads, by default on as many as the machine has processors;
`FyrQL.builder().threads(n)` or the system property `fyrql.threads` sets another number,
and with `threads(1)` every query runs on the thread that reads its result. The work is
divided by the splits of the sources, the morsels: a row group of a Parquet file, a part
of a CSV file or of a file of JSON lines, a part of a list in memory. A chain of filters,
projections, `unnest` and the sides of joins that probe their hash tables, over a scan,
up to the first node of another kind, is a segment, and so is a `UNION ALL` of such
chains without joins. The workers of a segment take the splits one by one from a common
queue, so that a worker that is done early takes the next one, and each runs a copy of
the chain, also of a compiled pipeline. The hash tables of the joins of a chain are built
before, once, from segments of their own, and shared by the workers. `explain` marks the
top node of each segment with `[parallel]`, as in the plans of this manual; a segment
whose scan has one split runs on the thread of the query. The node above a segment takes
the rows of its workers:

- An aggregation whose aggregates are `count`, `sum`, `avg`, `min`, `max`, `bool_and`
  and `bool_or` without `DISTINCT` and without an order lets each worker group its own
  rows, then divides the groups into 16 partitions by their hashes and merges the
  partitions on several threads. A worker that holds more than 16,384 groups, of whose
  recent rows fewer than two fall on each new group, passes its further rows on as they
  are, scattered into the partitions at once, so that many groups do not cost the
  memory and the caches of every worker. The groups come out in the order of their first
  rows, as on one thread. Other aggregates, such as `string_agg`, `list` or
  `first`, read the rows in the order of the splits on the thread of the query.
- A sort and a window keep the rows of the workers in the order of the splits; the sort
  sorts runs on several threads and merges them, stably, and the window computes its
  partitions on several threads, in ranges of at least 4,096 rows.
- A limit, a set operation and the result read the rows in the order of the splits, as
  one thread gives them (`preserveOrder`, the default). With
  `FyrQL.builder().preserveOrder(false)`, a limit and the result take the rows as the
  workers deliver them, and an aggregation directly below gives its groups partition by
  partition; this changes only the order of a result without `ORDER BY`.

The results are those of one thread, with two exceptions, which DuckDB has as well: the
last binary digits of `sum` and `avg` over `FLOAT` and `DOUBLE`, which add the partial
sums of the workers; and, when rows of several splits fail, which of the errors is
reported (that of the earliest split among those that failed before the query stopped).
Of equal values, such as `-0.0` and `0.0`, a `min` or `max` keeps the first, as on one
thread. The tests run every random query
and every TPC-H query on several threads and on one and compare them.

The sources divide their tables into splits as follows:

- Parquet: one split per row group.
- Java collections: a `List` with random access in parts of `splitRows` rows (65,536 by
  default), a setting of the builder of the connector; another collection is one split.
- CSV: a file larger than `splitSize` (4 MiB by default, `0` turns it off), an option of
  the builder and of the configuration, in parts of about that size. The parts follow the
  index of FastCSV, which finds the start of each record, also with line breaks inside
  quotes; the connector builds it on one thread when a query first reads the file, and
  again when the size or the time of change of the file changes. A file in a charset whose
  line breaks the index cannot find byte by byte, such as UTF-16, is one split. An error
  names the line that reading the whole file names.
- JSON: a file of JSON lines larger than `splitSize` (4 MiB by default), but no array, no
  gzipped file and none in UTF-16 or UTF-32, in parts that start after a line that ends
  with `}` or `]` and before a line that starts with `{` or `[`, which in valid JSON lies
  between two records, also where records span several lines. An error names the line and
  the record that reading the whole file names.
- Databases: one split, unless `splitRows` is set for the source, an option of the builder
  and of the configuration (`0`, the default, turns it off). Then a scan of a table of
  more than `splitRows` rows, in a query on several threads, is read in parts of about
  that many rows, at most eight and no more than there are threads, each one statement
  over a range of a key column on a connection of its own. The key column is that of a
  primary key of one column, or the one that `splitColumn(table, column)` of the builder
  or `splitColumn.<table>` of the configuration names, of an integral type, a `DATE` or a
  `TIMESTAMP` without time zone. The ranges are of equal width between the least and the
  greatest key, which a statement asks for at every scan; the first and the last are
  open, so that rows added since are read all the same, and the rows with a `NULL` key
  are a part of their own. A scan stays one statement when the database aggregates,
  orders or limits it, over a connection the application shares, and for a join that the
  database runs. Without an index on the key column, the database reads the whole table
  for every part. Each part is a transaction of its own and sees the table as it is when
  the part starts, where one statement sees one state of the whole table: while others
  write to the table, the parts may see different states of it, so that a sum over rows
  of two parts may mix old and new values, and a row whose key column changes may be
  missing or come twice.
- HTTP endpoints and OData services: one split.

The workers come from one pool of daemon threads of the JVM (`fyrql-worker-N`), created
as needed; `threads` limits the workers of each query, and no task waits for another, so
that several engines, such as one per connection of the JDBC driver, share the pool. A
cancelled query stops all its workers. The rows that the workers have read ahead of a
limit, a set operation or the result, at most two splits per worker, are outside the
memory budget.

[Performance](#threads) shows how operators gain from more threads.

## Time limits

A query, or a statement that writes, may run for a limited time: `queryTimeout` is the
timeout of a query that asks for none of its own, and `maxQueryTimeout` the upper bound of
every timeout, also of one that a query asks for. Both have no limit by default, and a
`queryTimeout` above the `maxQueryTimeout` is an error when the engine is built.

```java
FyrQL engine = FyrQL.builder()
        .queryTimeout(Duration.ofSeconds(30))
        .maxQueryTimeout(Duration.ofMinutes(5))
        .build();
```

A query asks for a timeout of its own with `QueryContext.withTimeout(Duration)`, which
`engine.execute(plan, context)` takes, and so does the [JDBC driver](#jdbc-driver) for
`Statement.setQueryTimeout`. The timeout counts from the creation of the context, which
the methods without a context create before the query runs, until the result is closed,
so that the time the application takes to read the rows counts too. Then the query is
canceled, as `QueryResult.cancel()` cancels it, and fails with `The query timed out`,
with the SQLState `HYT00` in the JDBC driver and the status `TIMED_OUT` in the
[Flight SQL server](#flight-sql-server). One daemon thread of the engine, started with
the first timeout, cancels the queries, and ends when the engine closes; a query with the
context `QueryContext.NONE` has no timeout. In a configuration file and the URL of the
JDBC driver, a timeout is a duration such as `500ms`, `30s`, `5m` or `PT30S`.

A [session](#sessions) has a timeout of its own, `statement_timeout`, which starts as the
`queryTimeout` and takes its place for the statements of the session:
`SET statement_timeout = '30s'`, or `= 30000` in milliseconds, and `0` for none. A timeout
that a query asks for wins over it, and the `maxQueryTimeout` bounds it as every timeout.

## Memory budget and spilling

The builder setting `memoryBudget` limits the memory that the queries of an engine take
together for what they hold: the hash tables of joins, the groups of aggregations, the rows
of sorts, windows and set operations. Without it, the budget has no limit. When an
aggregation, a sort or a window does not fit, it spills to disk, unless the setting `spill`
is `false`; then the query fails with `Memory budget of ... bytes exceeded`, as do the
others when they do not fit, with the SQL state `53200` in the JDBC driver and
`RESOURCE_EXHAUSTED` in the Flight SQL server.

- A **sort** keeps its rows while they fit. Then the rows held, with the batch that did
  not fit, are sorted into a run that goes to disk with the place of each row among all
  rows; on several threads, each worker writes its own runs. At the end, the runs are
  merged as the rows are read, one batch of each in memory, in groups of up to 64 runs
  if they do not fit at once. Rows with equal keys keep their order, so that the result
  is the one in memory.
- An **aggregation** keeps the groups it has, up to half of the budget, so that the merge
  has room, and scatters its further rows as they are into 16 partitions by the hash of
  their keys, which go to disk once they do not fit. Then the partitions are merged one
  after the other: the groups of each, then its rows, read a batch at a time. A
  partition whose groups do not fit is cut into 16 smaller ones by more bits of the
  hash, down to 65,536 partitions. The groups come in the order of their first rows,
  with the keys of these rows, as in memory, for every aggregate function, also with
  `DISTINCT` and `ORDER BY`. An aggregation without keys does not spill; its one group
  must fit.
- A **window** sorts its rows as a sort does and computes whole partitions as they come;
  a partition must fit, so that a window without `PARTITION BY` does not spill.
- Joins, `EXCEPT` and `INTERSECT` do not spill.

The files go to a directory of the engine under the setting `spillDirectory`, by default
the temporary directory of the JVM, named `fyrql-spill-` and a random suffix; it is
created when an operator spills first and deleted when the engine closes, and the files
of an operator are deleted when the query ends, also after a failure or a cancel. A
directory left behind by a process that ended without closing its engine may be deleted
by hand. The files hold the vectors of the batches as they are, without compression.
`QueryResult.spill()` tells what a query spilled and read back, in parts and bytes,
[EXPLAIN ANALYZE](#explain-analyze) what each of its operators spilled, and
`FyrQL.spillStatistics()` the sums of all queries of the engine.

```java
FyrQL engine = FyrQL.builder()
        .register(...)
        .memoryBudget(256L << 20)          // 256 MB for all queries together
        .spillDirectory(Path.of("/var/tmp"))
        .build();
```

[Performance](#spilling) shows what spilling costs.

## Observing queries

Every query counts what its operators and scans do: the rows and batches each operator
returns, the time it works, the threads it works on and what it spills to disk, and what
each scan reads from its source. `EXPLAIN ANALYZE` shows these numbers with the plan,
`QueryResult.statistics()` gives them to the program, and while Java Flight Recorder
records, queries, requests to sources, compiled code and spilled parts are events.

### EXPLAIN ANALYZE

`EXPLAIN ANALYZE` followed by a statement runs the statement, reads and drops the rows of
a query, and gives the plan as rows of one column `plan`, as `EXPLAIN` does, with the
numbers of the run after `--` on the line of each node. `engine.explainAnalyze(sql)` gives
the same text. A run of the query of [Using the engine](#using-the-engine), over the five
orders and three customers, on two threads, gave:

```
Analyzed in 3.17 ms: 2 rows, 1 thread
Sort #2 DESC -- 2 rows, 1 batch, 56.5 us, 1 thread
  Project #0 AS region, #1 AS orders, #2 AS revenue -- 2 rows, 1 batch, 11.9 us, 1 thread
    Aggregate GROUP BY #0: count(*) AS "count_star()", sum(#1) AS "sum(o.amount)" [codegen] -- 2 rows, 1 batch, 127 us, 1 thread
      Project #3 AS "#group0", #0 AS "#argument0" [parallel] -- 4 rows, 1 batch, 7.70 us, 1 thread
        Join INNER ON l#1 = r#0 [codegen] -- 4 rows, 1 batch, 196 us, 1 thread
          Project #1 AS amount, CAST(#0 AS BIGINT) AS "#key0" [codegen] -- 5 rows, 1 batch, 40.1 us, 1 thread
            Scan mem.main.orders (customerId INTEGER, amount DECIMAL(10,2)) -- 5 rows, 1 batch, 176 us, 1 thread; 1 split
          Scan csv.main.customers (id BIGINT, region VARCHAR) [parallel] -- 3 rows, 1 batch, 976 us, 1 thread; 1 split, 34 bytes
```

- The first line has the time from the start of the query until its result was closed,
  the rows of the result, the threads its operators worked on, the compiled code it used,
  taken from the cache or compiled, with the time to get it, and what it spilled to disk.
- After `--` come the rows and batches the operator of the node returned, its time, and
  the threads it worked on. The time is the time the operator worked without the time of
  its inputs, summed over its threads: on several threads, the times of the operators
  may add up to more than the time of the query, and the time a thread waits for the
  workers of a segment counts for no operator.
- A scan, and a part of the plan that a source runs (`Remote`), adds what it read: its
  splits; the row groups of Parquet and Iceberg files it read of all, the others skipped
  by their statistics and Bloom filters; the data files of an Iceberg table it read of
  all; the requests to S3, an HTTP endpoint, an OData service or a database, with the time
  until their answers came; and the bytes it read, of the answers or of local files (of a
  CSV or JSON file, the bytes of the parts it opened). Each statement sent to a database
  follows on a line of its own after `>`.
- An operator that spilled adds the bytes and parts it wrote and the bytes it read back.
- The top of a compiled pipeline, `[codegen pipeline]`, has the numbers of the whole
  pipeline, and the nodes below it have none; the top of a segment, `[parallel]`, has
  those of its copies on all workers.

`EXPLAIN ANALYZE` of a statement that writes writes, as in DuckDB: the first line has the
rows written, followed by the line of the statement and the plan of its rows, indented.
The JDBC driver and the Flight SQL server run it as a query, see
[JDBC driver](#jdbc-driver).

`QueryResult.statistics()` gives the numbers of a query as a `QueryStatistics`, so far
or, once the result is closed, in all: the plan it ran, an `OperatorStatistics` and a
`SourceStatistics` for each label such as `Filter#2`, the spilling, the compiled code and
the threads; `engine.explain(statistics)` prints them as `EXPLAIN ANALYZE` does, without
its first line. `engine.explainAnalyze(plan)` and
`engine.explainAnalyze(statement, context)` run a plan, or a `SqlStatement` of
`engine.statement(sql)` in a context with a timeout, see [Time limits](#time-limits).

The counters cost each operator two readings of the clock per batch, about 60 ns on the
laptop of the measurements, and twice that while all twelve threads are busy. In the
queries of TPC-H Q1 and Q6 over four million rows in memory, the difference was within
the noise of the runs (3 %); only a scan that does nothing with its batches shows it.

A connector reports what it reads to `SourceCounters.current()`, on the thread the
engine calls it on, and a request through `SourceRequest`, which also makes the event
`net.fyrql.SourceRequest`; see the Javadoc of `Connector`.

### JFR events

While Java Flight Recorder records, FyrQL commits four events of the category `FyrQL`,
which JDK Mission Control shows:

| Event | Fields | Duration |
| --- | --- | --- |
| `net.fyrql.Query` | `sql` (the text of the statement, at most 1,000 characters, or else the first line of its plan), `rows` (read from the result, or written), `outcome` (`ok`, `failed`, `cancelled` or `timeout`), `error`, `spilledBytes` | from the start of the run until the result is closed, or until a statement that writes has written |
| `net.fyrql.SourceRequest` | `source`, `kind` (such as `GET`, `query` or `next_page`), `target` (a URL or a statement, at most 1,000 characters), `status`, `bytes` (as the answer announces them) | from sending a request to S3, an HTTP endpoint, an OData service or a database until its answer came |
| `net.fyrql.Compile` | `kind` (such as `CompiledFilter` or `CompiledPipeline`), `className`, `sourceLength`, `outcome` (`compiled`, `tooLarge` or `failed`) | the compilation of a class of generated code, not one taken from the cache |
| `net.fyrql.Spill` | `operator` (such as `Sort#1`), `what` (such as `run`), `rows`, `batches`, `bytes` | a part an operator spilled, from its first batch until it was finished |

```
java -XX:StartFlightRecording=filename=fyrql.jfr -jar application.jar
jfr print --events 'net.fyrql.*' fyrql.jfr
```

The events are on by default in a recording. Without a recording, an event costs the
creation of an object that the JIT compiler removes; in a runtime without the module
`jdk.jfr`, such as one built by `jlink` without it, FyrQL makes none.

## Performance

All figures were measured on a laptop, each with the version of FyrQL it names. A laptop
slows its processor down as it heats up, so that the same query may take up to half as
long again from one run to the next, DuckDB included; only figures of one run compare
with each other.

### TPC-H at scale factor 1

The 22 TPC-H queries, in the text of the specification with its parameters of validation,
run over the tables of TPC-H spread across the connectors: `lineitem` as a Parquet file,
`partsupp` as a CSV file, `orders` and `customer` in PostgreSQL with their primary keys,
read in parts of 250,000 rows (`splitRows`), `part` and `supplier` as JSON lines, `nation`
and `region` as Java collections. The data comes from `io.trino.tpch`, a Java port of the
TPC-H generator, and DuckDB reads the same files for the comparison; it also writes the
Parquet file from a CSV file of the generator, in row groups of 122,880 rows. The profile
`tpch` runs them at scale factor 1, see [Building and testing](#building-and-testing).

The files take about 0.6 GB in `fyrql-test/target/tpch` and are written once; loading
PostgreSQL takes about 30 s. On the laptop, all 22 queries give the rows of DuckDB in all
four ways, with a peak of the heap between 0.4 and 1.9 GB; in seconds, the fastest of
three runs, with version 0.5.5, which read the tables of PostgreSQL in one part:

| Query | Interpreted, 1 thread | Interpreted, 12 threads | Compiled, 1 thread | Compiled, 12 threads | DuckDB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Scan of `lineitem` | 0.17 | 0.04 | 0.17 | 0.04 | 0.01 |
| Q1 | 2.41 | 0.57 | 1.38 | 0.31 | 0.09 |
| Q2 | 0.56 | 0.19 | 0.55 | 0.20 | 0.25 |
| Q3 | 1.10 | 0.51 | 0.95 | 0.49 | 0.25 |
| Q4 | 0.67 | 0.34 | 0.59 | 0.32 | 0.19 |
| Q5 | 1.15 | 0.59 | 1.07 | 0.58 | 1.16 |
| Q6 | 0.77 | 0.17 | 0.59 | 0.14 | 0.03 |
| Q7 | 2.79 | 1.58 | 2.10 | 1.44 | 0.26 |
| Q8 | 1.58 | 0.82 | 1.49 | 0.80 | 0.28 |
| Q9 | 2.25 | 1.02 | 2.02 | 1.06 | 0.45 |
| Q10 | 1.42 | 0.61 | 1.16 | 0.53 | 0.31 |
| Q11 | 0.43 | 0.14 | 0.40 | 0.16 | 0.23 |
| Q12 | 1.94 | 1.04 | 1.78 | 0.95 | 0.25 |
| Q13 | 2.26 | 2.25 | 1.90 | 1.89 | 0.34 |
| Q14 | 0.89 | 0.40 | 0.80 | 0.33 | 0.15 |
| Q15 | 1.47 | 0.71 | 1.29 | 0.61 | 0.09 |
| Q16 | 0.48 | 0.28 | 0.42 | 0.26 | 0.30 |
| Q17 | 1.57 | 1.07 | 1.50 | 0.98 | 0.20 |
| Q18 | 2.33 | 2.04 | 2.24 | 2.00 | 0.61 |
| Q19 | 1.94 | 0.93 | 1.76 | 0.83 | 0.21 |
| Q20 | 1.42 | 0.82 | 1.12 | 0.68 | 0.29 |
| Q21 | 3.91 | 2.39 | 2.07 | 1.41 | 0.60 |
| Q22 | 1.03 | 0.93 | 0.93 | 0.92 | 0.37 |
| All 22 queries | 34.4 | 19.4 | 28.1 | 16.9 | 6.9 |

On twelve threads, Q1 and
Q6, which read `lineitem` and aggregate it into few groups, take less than a quarter of
the time of one thread, and most other queries a third to two thirds. Q13 and Q22 read
only the orders and customers of PostgreSQL and gained nothing in one part. Read in parts,
in runs without and with them in turn, compiled on twelve threads, Q13 takes 1.2 to 1.3 s
instead of 1.7 to 2.0 s, Q18 1.2 to 1.3 s instead of 1.7 s, and all 22 queries 16.0 s
instead of 17.2 s; Q22 still gains little, since PostgreSQL runs its `NOT EXISTS` over
the customers and orders on one core. Run once
and cold on one thread, before queries ran on several threads, the 22 queries took 47.7 s
in the interpreter and 41.4 s compiled; with `lineitem` as a CSV file, 79.4 s, 74.7 s and
45.4 s in DuckDB, and before the CSV files were read into typed columns, joins and
aggregations kept their values in primitive arrays and pipelines were compiled, the
interpreter took 287 s. Q15, Q17 and Q18 read `lineitem` twice, once for the subquery,
and Q21 three times.

### Compiled code and scans

The module `fyrql-bench` measures the operators with [JMH](https://github.com/openjdk/jmh)
over four million rows in the shape of TPC-H `lineitem`, held in memory as batches of
2048 rows (`java -jar fyrql-bench/target/benchmarks.jar`). On the laptop, in
milliseconds per run (two forks, five measurements each),
with compiled code from the first row on:

| Benchmark | Interpreted | Compiled |
| --- | ---: | ---: |
| Filter of TPC-H Q6 | 190 | 26 |
| Projection of four computed columns | 528 | 30 |
| A filter and the four columns, one pipeline | 595 | 41 |
| The same as two compiled operators | 483 | 100 |
| Hash join with 100,000 keys, one pipeline | 282 | 167 |
| Left join with 50,000 keys, one pipeline | 330 | 234 |
| Aggregation in 100,000 groups | 231 | 187 |
| Aggregation in the shape of TPC-H Q1 | 207 | 178 |
| Filter, projection, join and aggregation | 527 | 138 |
| The same with compiled operators, no pipeline | 569 | 198 |
| Scan only, the control | 0.4 | 0.3 |

Joins and aggregations keep their values in primitive arrays in both modes, and the
aggregation is not compiled: it takes the batches of the pipeline below it. A pipeline
gains over compiled operators since it writes only the rows that leave it, where each
operator copies the rows it passes on. Compiling the filter of Q6 takes about 1.8 ms, a
pipeline of a filter, a projection and a join about 3.3 ms; with the class in the cache,
generating the source and creating an instance takes about 12 and 24 microseconds.
A scan of one million rows in the shape of `lineitem` reads a file into batches, from a
CSV file and from a Parquet file that DuckDB writes with its defaults (Snappy, row groups
of 122,880 rows), in milliseconds per run:

| Scan | CSV | Parquet |
| --- | ---: | ---: |
| The four columns of TPC-H Q6 | 339 | 75 |
| All 16 columns | 598 | 570 |

parquet-java decodes a Parquet file value by value, with several calls for each value
of each column; over all columns of a file, that takes about as long as reading the CSV
file.

### Threads

`ParallelBenchmark` in `fyrql-bench` runs operators over the four million rows of the
benchmarks of code generation, in memory and in splits of about 65,000 rows,
interpreted. On the laptop, in milliseconds per run:

| Benchmark | 1 thread | 4 threads | 12 threads |
| --- | ---: | ---: | ---: |
| The filter of TPC-H Q6, its rows in the order of the splits | 218 | 111 | 109 |
| Aggregation in the shape of TPC-H Q1, in six groups | 207 | 93 | 103 |
| Aggregation in 100,000 groups | 209 | 171 | 228 |
| Hash join with 100,000 keys and an aggregation by its nations | 378 | 162 | 168 |
| Sort of all rows by a decimal | 4,805 | 2,543 | 1,848 |
| Window with `row_number` and a moving sum in 100,000 partitions | 6,987 | 3,371 | 2,699 |

Batches that are already in memory are read as fast as the memory delivers them, so
that the cores gain little beyond four threads. The figures are those of version 0.5.5,
in which each worker held a table of nearly all of 100,000 groups in random order, twelve
such tables no longer fitted into the caches, and the aggregation in 100,000 groups
gained nothing on twelve threads. Since then a worker passes such rows on into the
partitions, as described above: in runs of the old and the new version in turn, that
aggregation took 136 to 167 ms on twelve threads instead of about 200 ms. It still does
not take half the time of one thread, since the merge compares the keys of every group
once more.

### Spilling

On the laptop, with version 0.9.0, at
scale factor 1, compiled on twelve threads with a budget of 256 MB, the fastest of three
runs: Q17 spills 139 MB and takes 1.24 s instead of 0.83 s without a limit, Q18 spills
74 MB and takes 2.25 s instead of 1.60 s, and the other queries spill nothing and take
the same time, except Q21, whose joins need 315 MB for their hash tables and fail; with
512 MB, nothing spills. `SpillBenchmark` in `fyrql-bench` sorts, aggregates and windows
the four million rows of `ParallelBenchmark` without a limit and with a budget of a
quarter of their bytes, in milliseconds per run:

| Benchmark | 1 thread | 1 thread, budget | 12 threads | 12 threads, budget |
| --- | ---: | ---: | ---: | ---: |
| Sort of all rows by a decimal | 4,516 | 5,746 | 1,899 | 3,496 |
| Aggregation in about 980,000 groups | 1,175 | 1,070 | 496 | 1,262 |
| Window with `row_number` in 100,000 partitions | 6,097 | 7,443 | 2,114 | 3,407 |

On one thread, the sort and the window take a fifth to a quarter longer and the
aggregation about as long; on twelve, where the merge of the runs and partitions runs on
one thread, they take up to two and a half times as long. On
Windows, creating and deleting a file takes about a millisecond, since the virus scanner
looks at it, so that a query that spills into thousands of small files, with a budget of
a few kilobytes, spends most of its time on them.

### Results through Arrow, Flight SQL and JDBC

The profile `tpch` also measures how rows leave the engine: `ArrowTransferScale1Test`
reads all 16 columns of the 6,001,215 rows of `lineitem` at scale factor 1, 953 MB as
Arrow vectors, from one engine on all threads, in four ways in turn, and writes the
fastest run of each into `fyrql-test/target/arrow-report.md`:

```
./mvnw -B -ntp verify -P tpch -Dtest=ArrowTransferScale1Test -Dsurefire.failIfNoSpecifiedTests=false -Dfyrql.tpch.runs=5
```

On the laptop, with version 0.6.5, they took 0.99 s as the batches of the engine,
1.17 s through an `ArrowReader`, 2.69 s through the Flight SQL server on localhost, read
with the `FlightSqlClient` of Arrow Java, and 4.67 s through the JDBC driver with
`getObject` for every value. `ArrowBenchmark` in `fyrql-bench` converts four million rows
on one thread: into Arrow in 40 ms with four columns of fixed width, in 116 ms with nine
columns of which three are strings, and in 102 ms with a list and a struct; back from
Arrow in 80, 344 and 467 ms.

## Compatibility

From version 1.0.0 on, FyrQL follows [Semantic Versioning](https://semver.org): a minor
version adds to what is promised and a patch version fixes it, and only a major version
breaks it. Until then, in the versions 0.x, anything may still change. The promise
covers

- the API for applications and the SPI for connectors, the packages and classes the
  table below lists as such;
- the keys of the [configuration file](#configuration-file), of the URL of the
  [JDBC driver](#jdbc-driver) and of the starter for [Spring Boot](#spring-boot), and the
  command line of the [Flight SQL server](#flight-sql-server);
- the behavior of SQL: the statements, functions and types FyrQL takes, the results it
  gives, the types it maps to JDBC and Arrow, and the SQLSTATEs of its errors;
- the list of [Differences from DuckDB](#differences-from-duckdb): FyrQL behaves as it
  says, and an entry goes in a minor version only when FyrQL then behaves as DuckDB.

The SPI changes within a major version only so that a connector written for one minor
version keeps working with the next:

- interfaces of the SPI get new methods only with defaults;
- `PlanNode` and `Expression` may get new kinds of nodes. The engine offers a connector
  filters and parts of plans of any kind, whatever its `Capabilities` say, and a
  connector declines what it does not know, e.g. with a `default` branch that takes the
  filter or the part over not at all;
- the components of the records of the API and the SPI stay the same, since a new one
  would break their constructors and record patterns.

Not covered are the internal packages and classes, the output of `EXPLAIN` and
`EXPLAIN ANALYZE`, the format of the files spilled to disk, the texts of messages, the
names and fields of the JFR events, the code that code generation writes, and the Maven
coordinates.

Each package says its tier in its Javadoc, and a class that differs from its package says
its own; a test checks that the API and the SPI name no internal type and that this table
lists the same tiers. Besides the classes in the table, three methods of the API are
internal: `FyrQL.catalog()`, `JdbcConnector.sql()` and `JdbcDialect.sql()`.

| Package | Tier | Classes of another tier |
| --- | --- | --- |
| `net.fyrql` | API | - |
| `net.fyrql.catalog` | API | `EngineCatalog` (internal) |
| `net.fyrql.config` | API | `Connectors` (internal) |
| `net.fyrql.jdbc` | API | - |
| `net.fyrql.arrow` | API | `ArrowTypes` (internal) |
| `net.fyrql.flight` | API | - |
| `net.fyrql.spring` | API | `FyrQLAutoConfiguration` (internal) |
| `net.fyrql.sql` | API | - |
| `net.fyrql.connector.memory` | API | `MemoryConnectorFactory` (internal) |
| `net.fyrql.connector.csv` | API | `CsvConnectorFactory` (internal) |
| `net.fyrql.connector.json` | API | `JsonConnectorFactory`, `JsonLayout`, `JsonPointers`, `JsonRecordReader`, `JsonSchemaInference`, `JsonStringParser` (internal) |
| `net.fyrql.connector.parquet` | API | `ParquetConnectorFactory`, `AvroCodecs`, `ParquetFile` (internal) |
| `net.fyrql.connector.iceberg` | API | `IcebergConnectorFactory` (internal) |
| `net.fyrql.connector.http` | API | `HttpConnectorFactory`, `HttpTransport` (internal) |
| `net.fyrql.connector.odata` | API | `ODataConnectorFactory` (internal) |
| `net.fyrql.connector.jdbc` | API | `JdbcConnectorFactory` (internal) |
| `net.fyrql.connector.s3` | API | `S3ConnectorFactory` (internal) |
| `net.fyrql.connector` | SPI | - |
| `net.fyrql.core.type` | SPI | - |
| `net.fyrql.core.vector` | SPI | `SpillFormat` (internal) |
| `net.fyrql.core.plan` | SPI | `Plans` (internal) |
| `net.fyrql.core.expr` | SPI | `ScalarOps` (internal) |
| `net.fyrql.core.value` | Internal | `Interval` (SPI) |
| `net.fyrql.core.function` | Internal | `FunctionRegistry`, `ScalarFunction` (API) |
| `net.fyrql.exec` | Internal | `QueryContext`, `QueryException`, `QueryCancelledException`, `QueryStatistics`, `OperatorStatistics`, `SpillStatistics`, `CodegenStatistics`, `CodegenMode` (API) |
| `net.fyrql.exec.compiled` | Internal | - |
| `net.fyrql.codegen` | Internal | - |
| `net.fyrql.sql.ast` | Internal | - |
| `net.fyrql.sql.bind` | Internal | - |
| `net.fyrql.sql.parse` | Internal | - |
| `net.fyrql.sql.render` | Internal | - |
| `net.fyrql.optimizer` | Internal | - |
| `net.fyrql.bench` | Internal | - |

## Modules

| Module | Content |
| --- | --- |
| `fyrql-core` | data types, values, vectors and batches, expressions, functions, logical plan |
| `fyrql-connector-spi` | the interfaces that connectors implement |
| `fyrql-exec` | the vectorized interpreter, the choice of pipelines, the parallel execution of segments, and the interfaces of code generation |
| `fyrql-codegen` | code generation with Janino, which it carries renamed; optional |
| `fyrql-sql` | the SQL parser, the binder, which turns subqueries into joins, and the rendering of plans as SQL per dialect |
| `fyrql-optimizer` | the folding of constants, the moving of conditions toward the tables and into join keys, the order of inner joins by the row counts of the catalogs, the narrowing of scans to the columns and fields in use, joins in a database and the pushdown into sources |
| `fyrql-connector-memory` | Java collections, records and POJOs as tables, and the tables that SQL creates |
| `fyrql-connector-csv` | CSV and TSV files as tables, also compressed with gzip, and their writer |
| `fyrql-connector-json` | JSON files as tables, with the columns inferred as DuckDB infers them, and their writer |
| `fyrql-connector-parquet` | Parquet files as tables, read and written with parquet-java without Hadoop, which it carries renamed |
| `fyrql-connector-iceberg` | Apache Iceberg tables, local or in S3, with snapshots, deletes, schema evolution and the pruning of files by the manifests, read through the Parquet connector |
| `fyrql-connector-http` | JSON from HTTP endpoints as tables |
| `fyrql-connector-odata` | the entity sets of OData services of versions 2 and 4 as tables, with filters, orders, limits and `count(*)` pushed to the service |
| `fyrql-connector-jdbc` | tables of H2, SQLite, PostgreSQL and MySQL over JDBC, and of any other database through a generic dialect, read and written |
| `fyrql-connector-s3` | the objects of S3 and MinIO as the files of the connectors of CSV, JSON and Parquet, over a client of its own with the signatures of AWS |
| `fyrql-engine` | the public Java API and the catalog over the registered sources and those of the file formats on the class path |
| `fyrql-config` | the configuration file in YAML, and connectors found by their type; its JAR carries Jackson and SnakeYAML renamed under `net.fyrql.internal.config`, so that an application keeps its own versions |
| `fyrql-jdbc` | a JDBC driver that runs an engine in the process of the application |
| `fyrql-jdbc-all` | the JDBC driver with the engine, code generation and all connectors in one JAR, for tools such as DBeaver; the drivers of the databases are not in it |
| `fyrql-arrow` | results as Apache Arrow vectors through an `ArrowReader`, and Arrow vectors as batches |
| `fyrql-flight-sql` | an Arrow Flight SQL server for an engine |
| `fyrql-flight-sql-all` | the Flight SQL server with the engine, code generation and all connectors in one executable JAR; the drivers of the databases are not in it |
| `fyrql-spring-boot-starter` | an engine as a bean of Spring Boot 4, configured in `application.yml`, with a health indicator and, on request, a `DataSource` |
| `fyrql-test` | the differential test harness against DuckDB and the tests that use it |
| `fyrql-bench` | JMH benchmarks of the interpreter against compiled code, of the operators on one and on several threads, of the scans of CSV and Parquet files, and of the conversion to and from Arrow; not published |

## Building and testing

Java 21 or later is required; the Maven wrapper fetches Maven itself.

```
./mvnw -B -ntp verify
```

With `-Pexamples`, the build also builds the [examples](../examples/README.md) against its
modules, without installing them, and runs each, whose test checks that it prints what its
README shows.

The tests of `fyrql-test` run DuckDB through its JDBC driver, which is a test dependency only;
`fyrql-bench` uses it to write its Parquet file.

The Iceberg tables of the tests are written by Iceberg Java, a test dependency only, with
partitions of every transform, every kind of delete, schema evolution, format version 1,
manifests of every codec and branches and tags; the connector must read the rows Iceberg
Java reads, in every snapshot. DuckDB with its extension `iceberg` is a second reference:
these tests load it from the cache of DuckDB, never from the network, and are skipped
without it; it is there after `INSTALL iceberg` in any DuckDB 1.5.6. Tables of other
writers, such as PyIceberg or Spark, are compared with DuckDB with
`-Dfyrql.test.iceberg.dir=<directory>`, in which every directory with a directory
`metadata` is a table, and the tables in S3 against a running MinIO with
`-Dfyrql.test.s3.endpoint=http://127.0.0.1:9000`.

Besides a corpus of SQL texts, two generators of random queries compare FyrQL with DuckDB:
random plans, which FyrQL runs as they are and as it binds them from their SQL, and random
SQL text with names, aliases, joins, subqueries in `FROM`, `WHERE`, `HAVING` and the select
list, correlated or not, grouping, window functions with `WINDOW` and `QUALIFY`, and set
operations. Each runs 200
queries with a fixed seed in `verify`; more queries or another seed are a property away:

```
./mvnw -B -ntp verify -Dfyrql.random.queries=2000 -Dfyrql.random.seed=7
```

A difference fails the test unless the ledger of known differences explains it
(`fyrql-test`, `net.fyrql.test.random.Ledger`); the report shows each other difference
shrunk to a small query. Every random query also runs with compiled code from the first
row on, which must give exactly the results of the interpreter, and on one thread, which
must give the results of all threads over tables in splits of 16 rows, with the
exceptions of [Parallel execution](#parallel-execution); the other tests of `fyrql-test`
run a second time with `-Dfyrql.codegen=always`.

Metamorphic checks after SQLancer need no second engine: FyrQL is its own oracle there.
Ternary logic partitioning (TLP) splits a random query by a random condition `p` into the
rows where `p` is true, where it is false and where it is `NULL`, which together must be
the rows of the query: in `WHERE`, with `DISTINCT` and `UNION`, in `HAVING` over groups and
aggregates, and in `WHERE` under counts, sums, `min`, `max`, `bool_and` and `bool_or`,
which are aggregated again. NoREC counts the rows a condition selects twice: as a filter,
which the optimizer pushes into the source, splits and compiles, and as a `CASE` over every
row. The checks run over the memory source interpreted, with compiled code, on one thread
and with a budget that spills, and over Parquet files with small row groups, CSV and JSON
files in small splits, H2, SQLite and PostgreSQL in splits over ranges of a key, and the
Iceberg tables of the tests; the queries use the tables of each source, and their literals
come partly from its rows. A check where a query fails as a query may, such as a cast of a
string that a part meets and the whole query does not, is skipped and counted; any other
difference fails the test, shrunk to a small query in the report (`fyrql-test`,
`net.fyrql.test.random.Metamorphic`). `-Dfyrql.random.queries` and `-Dfyrql.random.seed`
apply to them as well.

The JDBC connector is tested against H2, SQLite and PostgreSQL in every build, and through
the generic dialect against H2 in its modes of compatibility with Oracle, SQL Server and
MySQL, Derby and HSQLDB, all in memory; PostgreSQL comes as binaries that the build unpacks and starts in a temporary directory, without
Docker (`-Dfyrql.test.postgres=off` leaves it out). MySQL runs in Docker through
Testcontainers, in the profile `containers`; without Docker its tests are skipped:

```
./mvnw -B -ntp verify -P containers
```

A server of one's own takes the place of either with
`-Dfyrql.test.postgres.url=jdbc:postgresql://...` or `-Dfyrql.test.mysql.url=jdbc:mysql://...`,
and `.user` and `.password` alike; the tests replace their tables in its database. The tests
of the connectors run once more with `-Dfyrql.pushdown=off`, where every source only reads
the columns a query needs, and must give the same results.

The OData connector is tested against a server of its own, which answers as services of
versions 2 and 4 do, also with the quirks of some of them: comparisons without regard to
case, an ignored `$filter` or `$top`, answers cut short without a link to the next page,
numbers as strings and a failing `$select` of complex properties. The build sends no
request to a public service.

The 22 TPC-H queries run over tables spread across the connectors, as
[Performance](#tpc-h-at-scale-factor-1) describes. In `verify` the queries run at scale
factor 0.01, on all threads and on one; the profile `tpch` runs them at scale factor 1
with a heap of 8 GB, in the interpreter (`OFF`) and with code generation (`AUTO`), each on
one thread and on all, three times in turn (`-Dfyrql.tpch.runs=3`), and writes the time of
the fastest run and the highest peak of the heap of every query, a scan of `lineitem` for
reference, and the plans with code generation into `fyrql-test/target/tpch-report.md`:

```
./mvnw -B -ntp verify -P tpch -Dtest=TpchScale1Test -Dsurefire.failIfNoSpecifiedTests=false
```

With `-Dfyrql.tpch.budget=256MB` and `-Dtest=TpchScale1Test#theQueriesRunWithinABudget`,
the profile runs the queries with code generation on all threads without a limit and
within the budget instead, and writes the times and what each query spilled into
`fyrql-test/target/tpch-spill-report.md`; see
[Performance](#spilling) for the figures.
