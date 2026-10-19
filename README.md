# FyrQL

A small, embeddable, pure-JVM federated SQL engine with [DuckDB](https://duckdb.org)-like semantics, designed
for Data Mesh deployments. Operating with no server footprint or native libraries, FyrQL
provides a lightweight execution layer that implements three core design principles:

 - **Serverless Data Product Consumption**: The engine embeds as a standard Java library directly
   inside the application process. It processes data using columnar batches and a vectorized
   interpreter, utilizing Janino for runtime code generation of expressions, keys, and whole
   pipelines of filters, projections, and joins.
   Its query execution is verified by running all 22 TPC-H queries across federated sources.
 - **Polyglot Storage Abstraction**: FyrQL unifies heterogeneous data sources - including relational
   databases (via JDBC), local files (CSV, JSON/gzip, Parquet), live JSON HTTP endpoints, and
   OData services (versions 2 and 4) into a single logical mesh. It infers column types from data samples like DuckDB and handles
   nested STRUCT, LIST, and MAP types natively, allowing cross-source subqueries and joins via
   the same SQL dialect that is used by [DuckDB](https://duckdb.org).
 - **Zero-ETL / In-Place Analytics**: FyrQL queries decentralized data directly at the source.
   The optimizer moves conditions toward the tables, orders joins by table size, and pushes
   down filters, orders, limits, and aggregations into the connectors - running joins of tables
   from the same database natively within that database.


FyrQL is in an *early stage*. There are data types with nested `STRUCT`, `LIST` and `MAP`,
columnar batches, a vectorized interpreter, code generation with Janino for expressions,
keys and pipelines of filters, projections and joins, connectors for Java collections, CSV,
JSON and Parquet files, JSON from HTTP
endpoints, OData services of versions 2 and 4 and databases over JDBC, an optimizer that moves conditions toward the
tables, orders joins by the sizes of the tables and runs the joins of tables of one
database in that database, parallel execution on all cores over the parts of the sources, a pushdown of filters, orders, limits and aggregations into
the sources, an SQL front end with the names, types and forms of DuckDB, subqueries,
also correlated ones, that become joins, window functions with all frames of DuckDB, a
JDBC driver, also as one JAR for tools such as
DBeaver, and a differential test harness that compares the results with DuckDB. All 22
TPC-H queries run at scale factor 1 over tables spread across Parquet, CSV, JSON,
PostgreSQL and Java collections.

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
[Parallel execution](#parallel-execution). `engine.plan(sql)` returns the plan itself,
`engine.rows(sql)` all rows at once. A query
that does not parse or bind throws an `SqlException` with the position in the text, for
example `Referenced column "x" not found in FROM clause! at line 1, column 33`.

Plans can also be built in Java from the nodes of `PlanNode`, starting with
`engine.scan(TableRef.of("mem", "orders"))`, and run with `engine.execute(plan)`.
The builder takes further settings: `defaultSource` and `defaultSchema` for short names,
`functions` for a `FunctionRegistry` with functions of the application, `memoryBudget`,
`batchSize`, `pushdown`, `threads` and `preserveOrder`. Before a plan runs, the [optimizer](#optimizer) rewrites
it, every scan is narrowed to the columns in use, and with pushdown (the default, or the system property `fyrql.pushdown` set to `on`
or `off`) the source of a scan takes over what it can of the filter, aggregation, sort and
limit above it; `explain` shows that as `[pushed: ...]`. A source may only take over what
it evaluates exactly as the engine would, and the tests run every query with and without
pushdown.

Every result is checked against DuckDB in the tests: the same SQL text, or the plan
rendered as SQL, runs in DuckDB over a copy of the same data, and the types and rows must
agree.

## Connectors

Each kind of source is a connector in a module of its own, and every connector serves its
tables in the schema `main` unless it says otherwise.

- `fyrql-connector-memory`: Java collections of records, beans or rows; records and
  nested collections become `STRUCT`, `LIST` and `MAP` values.
- `fyrql-connector-csv`: CSV and TSV files of a directory or one by one, with the types
  inferred from a sample as DuckDB's `read_csv` infers them. A scan reads integers,
  decimals of up to 18 digits, dates, booleans and ASCII strings straight from the
  characters of the file into the columns; a field of another type, or in a form such as
  `+1`, `1e3` or ` 1`, is read as a string is cast, with the same value and error.
- `fyrql-connector-json`: JSON files (`.json`, `.jsonl`, `.ndjson`, also with `.gz`) that
  hold an array of records or a sequence of them. The columns are inferred from a sample
  as DuckDB's `read_json` infers them; objects and arrays become `STRUCT`, `MAP` and `LIST`
  values, and a field whose values do not unite is `VARCHAR` with the JSON text. A scan
  streams the file and skips the values of the columns, and of the fields of structs,
  that it does not need. The table
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
  timestamps, strings and booleans, also on fields of structs, under `AND`, `OR` and
  `NOT`. `explain` does not show these filters; `recordReads(true)` keeps the row groups
  and columns each scan read. parquet-java holds the column chunks of a row group in
  memory, compressed, outside the memory budget of the engine.
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
- `fyrql-connector-jdbc`: the tables of PostgreSQL, SQLite, H2 and MySQL. The default
  schema of the database is `main`, the others keep their names. A scan becomes one
  statement in the dialect of the database, which selects the columns the query needs
  and takes over the filters, the order and the limit, or the grouping and the
  aggregates, that the database evaluates exactly as FyrQL; a join of tables of the same
  database runs in the database as one statement, also the semi or anti join of an
  `EXISTS`, `NOT EXISTS` or `IN` over its tables, as `WHERE [NOT] EXISTS (...)`. The
  result is read in parts of the fetch size. A type FyrQL does not have, such as `TIME`,
  `INTERVAL`, JSON or an array, is read as `VARCHAR`; a timestamp with a time zone is
  its instant in UTC, and PostgreSQL's `numeric` without a precision is `DOUBLE`, as in
  DuckDB. `recordStatements(true)` keeps every statement sent.

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
| the same on `DECIMAL`, `DATE` and `TIMESTAMP` | yes | no | yes | yes |
| `=`, `<>`, `IN`, `IS DISTINCT FROM` on `VARCHAR` | yes | yes | yes | no |
| `<`, `>`, `BETWEEN` and `ORDER BY` on `VARCHAR` | no | yes | no | no |
| anything on `FLOAT`, `DOUBLE`, `HUGEINT`, `BLOB` or `UUID` | no | no | no | no |
| `AND`, `OR`, `NOT`, `IS NULL` over the above | yes | yes | yes | yes |
| arithmetic, casts, `LIKE`, `CASE`, functions | no | no | no | no |
| `ORDER BY` over the columns above, and `LIMIT` | yes | yes | yes | yes |
| `GROUP BY` over the columns compared with `=` above | yes | yes | yes | yes |
| `count(*)` and `count` of any column | yes | yes | yes | yes |
| `min`, `max` on integers, `DECIMAL`, `DATE` and `TIMESTAMP` | yes | integers | yes | yes |
| `sum` on integers | yes | no | yes | yes |
| `avg`, `DISTINCT`, other aggregates, or over `FLOAT` and `DOUBLE` | no | no | no | no |

SQLite stores decimals, dates and timestamps as numbers or text; H2 orders strings by
their UTF-16 units and PostgreSQL by the collation of the database, where FyrQL orders
them by their bytes; MySQL compares strings without regard to case. A filter is taken
over in parts, conjunct by conjunct; the order only as a whole, and the limit only with
all filters and the order. An aggregation is taken over as a whole, only with all
filters, and becomes `SELECT groups, aggregates FROM table WHERE ... GROUP BY groups`.
Window functions are never taken over; they run in the engine.
A sum is cast to `NUMERIC(39)`, in MySQL `DECIMAL(39,0)`, and read as `HUGEINT`, the type
FyrQL gives it, so that the database neither overflows nor answers in a type of its own;
SQLite has no such type and leaves sums to the engine. The connector of an application
may take over the same in its own way through `Capabilities` in `fyrql-connector-spi`.

An OData service gets the filters of a scan as a `$filter` that holds wherever they hold,
and the engine evaluates them once more, since a service may compare strings without
regard to case or ignore spaces at their end, which gives more rows but never fewer. A
source with `trustFilters`, for all its services or for one, takes over the filters that
have an exact counterpart; this is a promise about the service, which the connector does
not check, and a query with `pushdown(false)` shows whether it holds:

| An OData service gets | as a hint | taken over with `trustFilters` |
| --- | --- | --- |
| comparisons, `BETWEEN`, `IN`, `IS DISTINCT FROM` on integers, `DECIMAL`, `DATE`, `TIMESTAMP` | yes | yes |
| `=`, `<>` on `BOOLEAN` and `UUID` | yes | yes |
| `=`, `IN` and `LIKE 'a%'`, `'%a'`, `'%a%'` on `VARCHAR` | yes | no |
| `IS NULL` on any column of a primitive type | yes | yes |
| `<>`, `<`, `>` on `VARCHAR`; anything else on `FLOAT`, `DOUBLE`, `BLOB`, `STRUCT`, `LIST` | no | no |
| `AND`, `OR`, `NOT` over the above | yes | yes |
| arithmetic, functions, casts other than to a number type that holds every value | no | no |

A part without counterpart drops out of an `AND` of a hint and leaves an `OR` without
one. `NOT` moves inward and is never sent, since a comparison with `null` is false in
OData rather than unknown. A literal is written as the version wants it, such as
`datetime'2024-01-01T00:00:00'` and `12L` in version 2; one that the column cannot hold,
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
`json`, `parquet`, `http`, `odata` or `jdbc`; a connector of an application joins them through a
`ConnectorFactory` listed in `META-INF/services`. The other keys of a source are the
options of its type, read as text with nested keys joined by dots (`tables.products.url`);
an unknown or misspelled option is an error that lists the known ones. Relative paths
are resolved against the directory of the file. A value may name an environment variable
as `${env:NAME}`, or `${env:NAME:-default}`, and a system property as `${sys:name}`, so
that secrets need not be in the file. The settings under `engine` are those of the
builder: `defaultSource`, `defaultSchema`, `batchSize`, `memoryBudget` (such as `512MB`),
`codegen` (`off`, `auto` or `always`), `codegenThreshold`, `pushdown`, `threads` and
`preserveOrder` (see [Parallel execution](#parallel-execution)). A file whose
name ends in `.json` is read as JSON. `FyrQLConfig.builder(path)` returns the builder
instead, so that an application can add sources of its own, such as tables in memory.

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

The keys of the URL are `config` and the settings of the engine of the
[configuration file](#configuration-file): `defaultSource`, `defaultSchema`, `batchSize`,
`memoryBudget`, `codegen`, `codegenThreshold`, `pushdown`, `threads` and `preserveOrder`,
which win over those of the file. The same keys may come as properties of the connection, which win over the URL;
`getPropertyInfo` describes them, so that tools offer them. Keys are matched without
regard to case, values in the URL are decoded from `%xx`, and a blank value counts as not
set. The properties `user` and `password`, which tools always send, are ignored; another
unknown property gives a warning on the connection, and an unknown key in the URL fails
the connection with `08001`, as does a file with an error. A relative path of the file is
resolved against the working directory, and the paths in the file against its directory.
Without `config`, the engine has the sources `csv`, `json` and `parquet` without tables,
so that file names and table functions can be queried, as in
`SELECT * FROM 'data/orders.csv'` or `SELECT * FROM read_parquet('lake/events.parquet')`.

Every connection builds an engine of its own and closes it when the connection closes; an
application with an engine of its own opens a connection over it with
`FyrQLDriver.connect(engine, false)`, which leaves the engine open, and
`FyrQLDataSource` is a `DataSource` for either way. The catalog of a connection is its
default source and the schema its default schema: `setCatalog` and `setSchema` change
where names of fewer than three parts are looked up, for this connection only. The
`DatabaseMetaData` shows the sources as catalogs, with the term `source`, and their
schemas, tables and columns, with the number of rows of a table, or an estimate, in
`REMARKS` if the source has one; it reads only the sources the catalog in the call matches.

A statement runs one query: a `SELECT`, `WITH`, `VALUES` or set operation, or `EXPLAIN`
followed by a query, which gives the plan as rows of one column `plan`. The parameters
`?` of a `PreparedStatement` go into the text as literals of the values they are set to,
before the text is parsed; a parameter of a `MAP` type is not supported. A query runs
until its first rows while it is executed, as in the drivers of databases, and the rest
while its result set is read. `cancel()` and a timeout from `setQueryTimeout` stop a
running query, also one that waits for a database, with the SQLState `57014` or `HYT00`. The queries of one connection run one at a time. An
error has the message of FyrQL, with the position in the text or the node of the plan,
and the exception of FyrQL as its cause; its SQLState tells the kind, such as `42601` for
the syntax, `42P01` for an unknown table, `42703` for an unknown column, `0A000` for what
FyrQL does not support, `22003`, `22012` and `22018` for values out of range, a division
by zero and a failed cast, and `58030` for an error of a source.

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
| `UUID` | `OTHER` | `java.util.UUID` |
| `T[]` | `ARRAY` | `java.sql.Array` |
| `STRUCT(...)` | `STRUCT` | `java.sql.Struct` |
| `MAP(K, V)` | `OTHER` | `java.util.Map` |

`getObject(i, LocalDate.class)`, `LocalDateTime`, `BigInteger` and `String` work as
well, and the elements of arrays, structs and maps are converted alike.

The driver only reads. `executeUpdate` and statements other than queries fail with
`0A000`; there are no transactions: a connection is read-only and in auto-commit, and
`commit` and `rollback` do nothing. Results are read forward only, a call runs one
statement and not a script, and there are no stored procedures.

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
keeps it at one.

## Code generation

With the module `fyrql-codegen` on the class path, the engine compiles expressions,
filters, the keys of joins and aggregations, and whole pipelines into Java bytecode with
[Janino](https://github.com/janino-lts/janino) (janino-lts 3.1.20). The JAR of the module
carries Janino renamed to `net.fyrql.internal.janino`, so that an application may use a
Janino of its own. Without the module everything runs in the interpreter; the results are
the same either way, which the tests check for every random query, and so is whether a
query fails (see the known differences under [Supported SQL](#supported-sql)).

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

The module `fyrql-bench` measures the operators with [JMH](https://github.com/openjdk/jmh)
over four million rows in the shape of TPC-H `lineitem`, held in memory as batches of
2048 rows (`java -jar fyrql-bench/target/benchmarks.jar`). On a laptop with an AMD
Ryzen 5 5600H and JDK 21, in milliseconds per run (two forks, five measurements each),
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
top node of each segment with `[parallel]`, as in the plans of this README; a segment
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
- Databases, HTTP endpoints and OData services: one split.

The workers come from one pool of daemon threads of the JVM (`fyrql-worker-N`), created
as needed; `threads` limits the workers of each query, and no task waits for another, so
that several engines, such as one per connection of the JDBC driver, share the pool. A
cancelled query stops all its workers. The rows that the workers have read ahead of a
limit, a set operation or the result, at most two splits per worker, are outside the
memory budget.

`ParallelBenchmark` in `fyrql-bench` runs operators over the four million rows of the
benchmarks of code generation, in memory and in splits of about 65,000 rows,
interpreted. On the laptop of the TPC-H figures under [Building](#building), with six
cores and twelve threads, after a while under load, in milliseconds per run:

| Benchmark | 1 thread | 4 threads | 12 threads |
| --- | ---: | ---: | ---: |
| The filter of TPC-H Q6, its rows in the order of the splits | 218 | 111 | 109 |
| Aggregation in the shape of TPC-H Q1, in six groups | 207 | 93 | 103 |
| Aggregation in 100,000 groups | 209 | 171 | 228 |
| Hash join with 100,000 keys and an aggregation by its nations | 378 | 162 | 168 |
| Sort of all rows by a decimal | 4,805 | 2,543 | 1,848 |
| Window with `row_number` and a moving sum in 100,000 partitions | 6,987 | 3,371 | 2,699 |

Batches that are already in memory are read as fast as the memory delivers them, so
that six cores gain little beyond four threads. With 100,000 groups in random order, each
worker holds a table of nearly all groups, twelve such tables no longer fit into the
caches, and the merge adds them up once more: such an aggregation gains little, and on
twelve threads nothing. Dividing the groups among the workers by their hashes from the
start, as DuckDB does, would avoid that; it is not done yet.

## Supported SQL

Queries only; there is no DDL or DML. Names, types and the meaning of the forms follow
DuckDB 1.5.6.

- `SELECT [DISTINCT]` with `*`, `t.*`, `* EXCLUDE (...)` and `* REPLACE (...)`; an alias
  of the select list can be used by later items, in `WHERE`, `GROUP BY`, `HAVING` and
  `ORDER BY`.
- `FROM` with tables, subqueries, `VALUES`, the table functions `read_csv`,
  `read_csv_auto`, `read_json`, `read_json_auto`, `read_ndjson`, `read_ndjson_auto`,
  `read_parquet` and `parquet_scan`, and file names such as `FROM 'data/orders.csv'`,
  `FROM 'events.jsonl'` or `FROM 'lake/*.parquet'`; `INNER`, `LEFT`,
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
  `TRY_CAST`, `::`, `||`, `EXTRACT(... FROM ...)`.
- Types: `BOOLEAN`, `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT`, `HUGEINT`, `FLOAT`,
  `DOUBLE`, `DECIMAL(p,s)`, `VARCHAR`, `DATE`, `TIMESTAMP`, and nested to any depth
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
  `current_date`, `now`, `year` to `second` and the other parts of a date, `date_part`,
  `date_trunc`, `date_diff`, `epoch`, `epoch_ms`, `last_day`, `monthname`, `dayname`,
  `make_date`, `make_timestamp`, `strftime`, `strptime`, `try_strptime`; `list_value`,
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
  a number for a numeric order, and a number of days or an interval such as
  `INTERVAL 1 MONTH` for a `DATE` or `TIMESTAMP`. `IGNORE NULLS` goes with `lag`, `lead`,
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

*Not yet supported*: the type `INTERVAL`, `PIVOT`, `SEMI JOIN` and `ANTI JOIN` in SQL,
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

Known differences from DuckDB:

- `now()` is a `TIMESTAMP` without time zone.
- On several threads, the last binary digits of `sum` and `avg` over `FLOAT` and
  `DOUBLE`, and which error is reported when rows of several parts of a source fail, may
  change from run to run, as in DuckDB; see [Parallel execution](#parallel-execution).
- `INTERVAL '90' DAY` (also `INTERVAL 1 year` and `INTERVAL '3 months'`) exists only
  as a constant added to or subtracted from a constant date or timestamp, such as
  `DATE '1998-12-01' - INTERVAL '90' DAY`, and is computed when the query is bound.
  A `DATE` shifted by days, months or years stays a `DATE`, where DuckDB gives a
  `TIMESTAMP`.
- `SELECT DISTINCT` cannot be ordered by a column that is not selected.
- A string literal is not converted to the type of a function argument, as in
  `sqrt('4')`.
- `avg` does not take a `DATE`. Over `BIGINT`, it divides the sum in a double, as DuckDB
  does on Windows; DuckDB on x86-64 Linux divides it in 80 bits, and its result may
  differ in the last binary digit.
- The name of an output column that is no plain column follows FyrQL's own text form of
  the expression, which is not always DuckDB's.
- The empty list `[]`, a list of bare `NULL`s and `map()` have `INTEGER` elements; DuckDB
  gives them a type of its own for `NULL`, which it unites with any other type.
- A string does not convert to a nested type, as in `'[1, 2]'::INTEGER[]`.
- A cast of a map whose keys become equal, such as `'1'` and `'01'` to `INTEGER`, fails;
  DuckDB keeps both keys.
- A `MAP(K, V)` type cannot be written in a `CAST`, since the parser does not read it.
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
- Of a Parquet file, FyrQL reads the types it does not have as others: an unsigned
  integer as the next wider type with a sign, up to `HUGEINT` for a `UBIGINT`; a `TIME`
  as a `VARCHAR` with the text DuckDB gives it; a timestamp in nanoseconds as a
  `TIMESTAMP` in microseconds, cut toward zero as DuckDB casts it, and a timestamp
  adjusted to UTC as its instant in UTC, where DuckDB gives a `TIMESTAMPTZ`. A list in
  one of the older forms whose repeated group has one field and is named `array` or ends
  in `_tuple` is a list of that group, such as `STRUCT(x INTEGER)[]`, as the specification
  of Parquet has it; DuckDB takes the field as the element, `INTEGER[]`. A string is not
  checked for valid UTF-8, a `BSON` column is a `BLOB` that DuckDB does not read, and
  files compressed with `LZ4` (the older framing of Hadoop), `BROTLI` or `LZO` are an
  error that names the codec and the file.
- Of an OData service, FyrQL reads the types it does not have as others: an
  `Edm.Decimal` without a `Precision`, with one above 38 or with a `Scale` of `variable`
  or `floating` as a `DOUBLE`, an `Edm.DateTimeOffset` as a `TIMESTAMP` with its instant
  in UTC, an `Edm.Time`, `Edm.TimeOfDay` or `Edm.Duration` as a `VARCHAR` with the text
  the service sends, and a member of an enumeration as a `VARCHAR` with its name.
  `Edm.Stream`, the geographic types and navigation properties are no columns.
- A select list unnests one list, which may appear in several items; DuckDB unnests
  several lists side by side. `unnest` in a `LEFT JOIN`, with named arguments such as
  `recursive := true`, and of a struct that only an aggregate gives are not taken.
- A window whose first expression of `PARTITION BY` is in parentheses and followed by
  others, as in `PARTITION BY (a % 2), k`, does not parse; `PARTITION BY ((a % 2)), k`
  does.
- A window function whose value is not used is not computed, and so does not fail, as it
  does in DuckDB on a negative offset of a `RANGE` frame.
- `lag` and `lead` give their default where the offset reaches beyond the range of
  `BIGINT`; DuckDB fails with an overflow. A default whose type does not cast to the
  type of the value, such as a `BIGINT` for a `DATE`, is an error when the query is
  bound; DuckDB fails only at a row that takes a default that is not `NULL`.
- A `-0.0` by which a window partitions or orders keeps its sign; DuckDB passes such
  columns on as `0.0`.
- DuckDB 1.5.6 takes rows into a frame whose start lies after its end when the frame has
  an `EXCLUDE`, as in `GROUPS BETWEEN 0 PRECEDING AND 3 PRECEDING EXCLUDE TIES`, where it
  takes earlier groups; FyrQL keeps such a frame empty.

## Modules

| Module | Content |
| --- | --- |
| `fyrql-core` | data types, values, vectors and batches, expressions, functions, logical plan |
| `fyrql-connector-spi` | the interfaces that connectors implement |
| `fyrql-exec` | the vectorized interpreter, the choice of pipelines, the parallel execution of segments, and the interfaces of code generation |
| `fyrql-codegen` | code generation with Janino, which it carries renamed; optional |
| `fyrql-sql` | the SQL parser, the binder, which turns subqueries into joins, and the rendering of plans as SQL per dialect |
| `fyrql-optimizer` | the folding of constants, the moving of conditions toward the tables and into join keys, the order of inner joins by the row counts of the catalogs, the narrowing of scans to the columns and fields in use, joins in a database and the pushdown into sources |
| `fyrql-connector-memory` | Java collections, records and POJOs as tables |
| `fyrql-connector-csv` | CSV and TSV files as tables |
| `fyrql-connector-json` | JSON files as tables, with the columns inferred as DuckDB infers them |
| `fyrql-connector-parquet` | Parquet files as tables, read with parquet-java without Hadoop, which it carries renamed |
| `fyrql-connector-http` | JSON from HTTP endpoints as tables |
| `fyrql-connector-odata` | the entity sets of OData services of versions 2 and 4 as tables, with filters, orders, limits and `count(*)` pushed to the service |
| `fyrql-connector-jdbc` | tables of H2, SQLite, PostgreSQL and MySQL over JDBC |
| `fyrql-engine` | the public Java API and the catalog over the registered sources |
| `fyrql-config` | the configuration file in YAML, and connectors found by their type |
| `fyrql-jdbc` | a JDBC driver that runs an engine in the process of the application; read only |
| `fyrql-jdbc-all` | the JDBC driver with the engine, code generation and all connectors in one JAR, for tools such as DBeaver; the drivers of the databases are not in it |
| `fyrql-test` | the differential test harness against DuckDB and the tests that use it |
| `fyrql-bench` | JMH benchmarks of the interpreter against compiled code, of the operators on one and on several threads, and of the scans of CSV and Parquet files; not published |

## Building

Java 21 or later is required; the Maven wrapper fetches Maven itself.

```
./mvnw -B -ntp verify
```

The tests of `fyrql-test` run DuckDB through its JDBC driver, which is a test dependency only;
`fyrql-bench` uses it to write its Parquet file.

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

The JDBC connector is tested against H2, SQLite and PostgreSQL in every build; PostgreSQL
comes as binaries that the build unpacks and starts in a temporary directory, without
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

The 22 TPC-H queries, in the text of the specification with its parameters of
validation, run over the tables of TPC-H spread across the connectors: `lineitem` as a
Parquet file, `partsupp` as a CSV file, `orders` and `customer` in PostgreSQL, `part` and
`supplier` as JSON lines, `nation` and `region` as Java collections. The data comes from
`io.trino.tpch`, a Java port of the TPC-H generator, and DuckDB reads the same files for
the comparison; it also writes the Parquet file from a CSV file of the generator, in row
groups of 122,880 rows. In `verify` the queries run at scale factor 0.01, on all threads
and on one; the profile `tpch` runs them at scale factor 1 with a heap of 8 GB, in the
interpreter (`OFF`) and with code generation (`AUTO`), each on one thread and on all,
three times in turn (`-Dfyrql.tpch.runs=3`), and writes the time of the fastest run and
the highest peak of the heap of every query, a scan of `lineitem` for reference, and the
plans with code generation into `fyrql-test/target/tpch-report.md`:

```
./mvnw -B -ntp verify -P tpch -Dtest=TpchScale1Test -Dsurefire.failIfNoSpecifiedTests=false
```

The files take about 0.6 GB in `fyrql-test/target/tpch` and are written once; loading
PostgreSQL takes about 30 s. On a laptop with an AMD Ryzen 5 5600H (six cores, twelve
threads) and JDK 21, all 22 queries give the rows of DuckDB in all four ways, with a peak
of the heap between 0.4 and 1.9 GB; in seconds, the fastest of three runs:

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

After a while under load, the same laptop takes up to half as long again for every
query, DuckDB included, so that only times of one run compare. On twelve threads, Q1 and
Q6, which read `lineitem` and aggregate it into few groups, take less than a quarter of
the time of one thread, and most other queries a third to two thirds. Q13 and Q22 read
only the orders and customers of PostgreSQL, which a database gives as one split, and
gain nothing; Q18 and Q21 read the orders in a chain with joins on one thread. Run once
and cold on one thread, before queries ran on several threads, the 22 queries took 47.7 s
in the interpreter and 41.4 s compiled; with `lineitem` as a CSV file, 79.4 s, 74.7 s and
45.4 s in DuckDB, and before the CSV files were read into typed columns, joins and
aggregations kept their values in primitive arrays and pipelines were compiled, the
interpreter took 287 s. Q15, Q17 and Q18 read `lineitem` twice, once for the subquery,
and Q21 three times.

## License

Mozilla Public License 2.0, see `LICENSE`.
