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
   databases (via JDBC), local flat files (CSV, JSON/gzip), and live JSON HTTP endpoints into
   a single logical mesh. It infers column types from data samples like DuckDB and handles
   nested STRUCT, LIST, and MAP types natively, allowing cross-source subqueries and joins via
   the same SQL dialect that is used by [DuckDB](https://duckdb.org).
 - **Zero-ETL / In-Place Analytics**: FyrQL queries decentralized data directly at the source.
   The optimizer moves conditions toward the tables, orders joins by table size, and pushes
   down filters, orders, limits, and aggregations into the connectors - running joins of tables
   from the same database natively within that database.


FyrQL is in an *early stage*. There are data types with nested `STRUCT`, `LIST` and `MAP`,
columnar batches, a vectorized interpreter, code generation with Janino for expressions,
keys and pipelines of filters, projections and joins, connectors for Java collections, CSV
and JSON files, JSON from HTTP
endpoints and databases over JDBC, an optimizer that moves conditions toward the
tables, orders joins by the sizes of the tables and runs the joins of tables of one
database in that database, a pushdown of filters, orders, limits and aggregations into
the sources, an SQL front end with the names, types and forms of DuckDB, subqueries,
also correlated ones, that become joins, and a differential test harness that compares
the results with DuckDB. All 22 TPC-H queries run at scale factor 1 over tables spread
across CSV, JSON, PostgreSQL and Java collections.

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
      Project #3 AS "#group0", #0 AS "#argument0"
        Join INNER ON l#1 = r#0 [codegen]
          Project #1 AS amount, CAST(#0 AS BIGINT) AS "#key0" [codegen]
            Scan mem.main.orders (customerId INTEGER, amount DECIMAL(10,2))
          Scan csv.main.customers (id BIGINT, region VARCHAR)
```

The plan is the one the engine runs: the scan of the orders reads only the two columns
the query uses. With the module `fyrql-codegen` on the class path, as here, `[codegen]`
marks the nodes whose expressions or keys the engine compiles once enough rows pass; over
larger tables, `[codegen pipeline]` marks a chain of nodes that runs as one compiled loop;
see [Code generation](#code-generation). `engine.plan(sql)` returns the plan itself,
`engine.rows(sql)` all rows at once. A query
that does not parse or bind throws an `SqlException` with the position in the text, for
example `Referenced column "x" not found in FROM clause! at line 1, column 33`.

Plans can also be built in Java from the nodes of `PlanNode`, starting with
`engine.scan(TableRef.of("mem", "orders"))`, and run with `engine.execute(plan)`.
The builder takes further settings: `defaultSource` and `defaultSchema` for short names,
`functions` for a `FunctionRegistry` with functions of the application, `memoryBudget`,
`batchSize`, and `pushdown`. Before a plan runs, the [optimizer](#optimizer) rewrites
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
- `fyrql-connector-http`: the JSON an endpoint answers to a `GET`, read as a JSON file,
  or the array that a JSON pointer such as `/data` points to inside the answer. The
  connector sends its headers, such as `Authorization`, accepts gzip and infers the
  columns with a first request, which it keeps until `refresh()`. Every scan is a request
  of its own; nothing but the choice of columns and fields is pushed to the endpoint. A status other
  than 2xx is an `HttpSourceException` with the URL and the status.
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
      Project #5 AS "#group0", #1 AS "#group1"
        Join INNER ON l#0 = r#1 [codegen]
          Scan api.main.products (sku VARCHAR, name VARCHAR)
          Join INNER ON l#0 = r#0 [codegen]
            Project #0 AS customer_id, #1 AS sku
              Scan pg.main.orders (customer_id BIGINT, sku VARCHAR) [pushed: WHERE ((#0 > CAST(10 AS INTEGER)) AND (#1 <> 'p3'))]
            Scan files.main.customers (id BIGINT, city VARCHAR)
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
A sum is cast to `NUMERIC(39)`, in MySQL `DECIMAL(39,0)`, and read as `HUGEINT`, the type
FyrQL gives it, so that the database neither overflows nor answers in a type of its own;
SQLite has no such type and leaves sums to the engine. The connector of an application
may take over the same in its own way through `Capabilities` in `fyrql-connector-spi`.

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
   an aggregation, orders and unions, and through an outer join only to the side whose
   rows it keeps, as through a semi, anti, mark and single join to the left one. An
   equality between the two sides of a join, in a condition above it or in its own,
   becomes a key of the join, so that `FROM a, b WHERE a.id = b.id` is a hash join and
   not a cross product.
3. Inner joins are ordered by their estimated sizes. The row counts of the tables come
   from the catalogs of the sources: exact for Java collections, from one `count(*)` per
   table for a database, estimated from the sample and the size of the file for CSV and
   JSON, and 1,000 where a source does not know. A condition keeps a fixed share of the
   rows, such as a tenth for `=` and 0.3 for `<`. Up to ten tables are ordered by
   dynamic programming over the connected subsets, more of them one join at a time; the
   smaller input of each join becomes its right one, on which the hash table is built.
   A semi or anti join above inner joins goes onto the table whose columns it reads,
   before that table is joined with the others.
4. Every scan reads only the columns in use, and of a struct only the fields in use
   where its source reads fields (JSON files, HTTP endpoints and Java collections);
   `explain` shows such a field as `s.a VARCHAR`.
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
the CSV file of `lineitem` stays in the engine, which builds its hash table on the
smaller result of the database. The filter, the join and the two projections above it
run as one compiled [pipeline](#code-generation):

```
Limit 10
  Sort #1 DESC, #2
    Project #0 AS l_orderkey, #3 AS revenue, #1 AS o_orderdate, #2 AS o_shippriority
      Aggregate GROUP BY #0, #1, #2: sum(#3) AS "sum((l_extendedprice * (1 - l_discount)))" [codegen]
        Project #2 AS "#group0", #0 AS "#group1", #1 AS "#group2", (#3 * (CAST(1 AS INTEGER) - #4)) AS "#argument0" [codegen pipeline]
          Project #6 AS o_orderdate, #7 AS o_shippriority, #0 AS l_orderkey, #1 AS l_extendedprice, #2 AS l_discount [codegen]
            Join INNER ON l#0 = r#0 [codegen]
              Filter (#3 > DATE '1995-03-15') [codegen]
                Scan files.main.lineitem (l_orderkey INTEGER, l_extendedprice DECIMAL(15,2), l_discount DECIMAL(15,2), l_shipdate DATE)
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
                  Project #1 AS "#argument0"
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
  - name: api
    type: http
    tables:
      products: { url: "https://example.com/v1/products", pointer: /data }
    headers:
      Authorization: Bearer ${env:API_TOKEN}
engine:
  defaultSource: shop
  pushdown: true
```

The `type` of a source picks its connector, which must be on the class path: `csv`,
`json`, `http` or `jdbc`; a connector of an application joins them through a
`ConnectorFactory` listed in `META-INF/services`. The other keys of a source are the
options of its type, read as text with nested keys joined by dots (`tables.products.url`);
an unknown or misspelled option is an error that lists the known ones. Relative paths
are resolved against the directory of the file. A value may name an environment variable
as `${env:NAME}`, or `${env:NAME:-default}`, and a system property as `${sys:name}`, so
that secrets need not be in the file. The settings under `engine` are those of the
builder: `defaultSource`, `defaultSchema`, `batchSize`, `memoryBudget` (such as `512MB`),
`codegen` (`off`, `auto` or `always`), `codegenThreshold` and `pushdown`. A file whose
name ends in `.json` is read as JSON. `FyrQLConfig.builder(path)` returns the builder
instead, so that an application can add sources of its own, such as tables in memory.

## Code generation

With the module `fyrql-codegen` on the class path, the engine compiles expressions,
filters, the keys of joins and aggregations, and whole pipelines into Java bytecode with
Janino (janino-lts 3.1.20). The JAR of the module
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
Reading a CSV file of one million rows in the shape of `lineitem` takes 349 ms for four
of its columns and 643 ms for all 16.

## Supported SQL

Queries only; there is no DDL or DML. Names, types and the meaning of the forms follow
DuckDB 1.5.6.

- `SELECT [DISTINCT]` with `*`, `t.*`, `* EXCLUDE (...)` and `* REPLACE (...)`; an alias
  of the select list can be used by later items, in `WHERE`, `GROUP BY`, `HAVING` and
  `ORDER BY`.
- `FROM` with tables, subqueries, `VALUES`, the table functions `read_csv`,
  `read_csv_auto`, `read_json`, `read_json_auto`, `read_ndjson` and `read_ndjson_auto`,
  and file names such as `FROM 'data/orders.csv'` or `FROM 'events.jsonl'`; `INNER`, `LEFT`,
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

*Not yet supported*: window functions, the type `INTERVAL`, `QUALIFY`, `PIVOT`, `SEMI
JOIN` and `ANTI JOIN` in SQL, lambdas, slices such as `l[1:2]`, structs without names
(`row(1, 'a')`), arrays of a fixed size (`INTEGER[3]`), and `WITH RECURSIVE`. Of the
subqueries: those in `ORDER BY`, `GROUP BY`, `ON` and the arguments of aggregates;
`ANY` and `ALL`; `IN` over more than one column. Of the correlated ones: a reference
to the outer query outside the `WHERE` of the subquery or more than one level out; a
condition that references the outer query and holds a subquery, or an `IN` whose value
does; a correlated condition of an aggregating subquery that is not an equality;
`EXISTS` and `IN` over a subquery that aggregates without `GROUP BY`; `LIMIT`, `OFFSET`,
and `HAVING` without `GROUP BY`; `DISTINCT` in a scalar subquery.

Known differences from DuckDB:

- `now()` is a `TIMESTAMP` without time zone.
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
  them on other rows, or folds constants at another time. Within FyrQL, a compiled
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

## Modules

| Module | Content |
| --- | --- |
| `fyrql-core` | data types, values, vectors and batches, expressions, functions, logical plan |
| `fyrql-connector-spi` | the interfaces that connectors implement |
| `fyrql-exec` | the vectorized interpreter, the choice of pipelines, and the interfaces of code generation |
| `fyrql-codegen` | code generation with Janino, which it carries renamed; optional |
| `fyrql-sql` | the SQL parser, the binder, which turns subqueries into joins, and the rendering of plans as SQL per dialect |
| `fyrql-optimizer` | the folding of constants, the moving of conditions toward the tables and into join keys, the order of inner joins by the row counts of the catalogs, the narrowing of scans to the columns and fields in use, joins in a database and the pushdown into sources |
| `fyrql-connector-memory` | Java collections, records and POJOs as tables |
| `fyrql-connector-csv` | CSV and TSV files as tables |
| `fyrql-connector-json` | JSON files as tables, with the columns inferred as DuckDB infers them |
| `fyrql-connector-http` | JSON from HTTP endpoints as tables |
| `fyrql-connector-jdbc` | tables of H2, SQLite, PostgreSQL and MySQL over JDBC |
| `fyrql-engine` | the public Java API and the catalog over the registered sources |
| `fyrql-config` | the configuration file in YAML, and connectors found by their type |
| `fyrql-test` | the differential test harness against DuckDB and the tests that use it |
| `fyrql-bench` | JMH benchmarks of the interpreter against compiled code; not published |

## Building

Java 21 or later is required; the Maven wrapper fetches Maven itself.

```
./mvnw -B -ntp verify
```

The tests of `fyrql-test` run DuckDB through its JDBC driver, which is a test dependency only.

Besides a corpus of SQL texts, two generators of random queries compare FyrQL with DuckDB:
random plans, which FyrQL runs as they are and as it binds them from their SQL, and random
SQL text with names, aliases, joins, subqueries in `FROM`, `WHERE`, `HAVING` and the select
list, correlated or not, grouping and set operations. Each runs 200
queries with a fixed seed in `verify`; more queries or another seed are a property away:

```
./mvnw -B -ntp verify -Dfyrql.random.queries=2000 -Dfyrql.random.seed=7
```

A difference fails the test unless the ledger of known differences explains it
(`fyrql-test`, `net.fyrql.test.random.Ledger`); the report shows each other difference
shrunk to a small query. Every random query also runs with compiled code from the first
row on, which must give exactly the results of the interpreter, and the other tests of
`fyrql-test` run a second time with `-Dfyrql.codegen=always`.

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

The 22 TPC-H queries, in the text of the specification with its parameters of
validation, run over the tables of TPC-H spread across the connectors: `lineitem` and
`partsupp` as CSV files, `orders` and `customer` in PostgreSQL, `part` and `supplier` as
JSON lines, `nation` and `region` as Java collections. The data comes from
`io.trino.tpch`, a Java port of the TPC-H generator, and DuckDB reads the same files for
the comparison. In `verify` the queries run at scale factor 0.01; the profile `tpch` runs
them at scale factor 1 with a heap of 8 GB, once in the interpreter (`OFF`) and once with
code generation (`AUTO`), and writes the time and the peak of the heap of every query in
both modes, a scan of `lineitem` for reference, and the plans with code generation into
`fyrql-test/target/tpch-report.md`:

```
./mvnw -B -ntp verify -P tpch -Dtest=TpchScale1Test -Dsurefire.failIfNoSpecifiedTests=false
```

The files take about 1.1 GB in `fyrql-test/target/tpch` and are written once; loading
PostgreSQL takes about 25 s. On a laptop with an AMD Ryzen 5 5600H and JDK 21, all 22
queries give the rows of DuckDB in both modes, with a peak of the heap between 0.4 and
1.6 GB; in seconds:

| Query | Interpreted | Compiled | DuckDB |
| --- | ---: | ---: | ---: |
| Scan of `lineitem` | 2.5 | 2.4 | 1.6 |
| Q1 | 4.5 | 3.4 | 2.0 |
| Q2 | 1.1 | 1.0 | 0.5 |
| Q3 | 3.6 | 3.3 | 2.2 |
| Q4 | 2.9 | 2.8 | 2.0 |
| Q5 | 3.1 | 3.0 | 4.0 |
| Q6 | 2.5 | 2.4 | 1.7 |
| Q7 | 5.1 | 4.7 | 2.2 |
| Q8 | 3.5 | 3.8 | 2.3 |
| Q9 | 4.7 | 4.4 | 2.5 |
| Q10 | 3.6 | 3.4 | 2.3 |
| Q11 | 0.5 | 0.6 | 0.4 |
| Q12 | 4.0 | 4.1 | 2.1 |
| Q13 | 2.7 | 2.5 | 0.5 |
| Q14 | 3.0 | 2.7 | 1.7 |
| Q15 | 5.0 | 4.8 | 1.6 |
| Q16 | 0.8 | 0.7 | 0.4 |
| Q17 | 5.2 | 5.0 | 3.3 |
| Q18 | 6.2 | 6.1 | 3.7 |
| Q19 | 3.4 | 3.7 | 2.1 |
| Q20 | 3.6 | 3.0 | 2.0 |
| Q21 | 9.4 | 8.0 | 5.5 |
| Q22 | 1.1 | 1.3 | 0.4 |
| All 22 queries | 79.4 | 74.7 | 45.4 |

Before the CSV files were read into typed columns, joins and aggregations kept their
values in primitive arrays and pipelines were compiled, the interpreter took 287 s for
the 22 queries. Most of the time left goes into reading `lineitem`, which DuckDB reads
in parallel, and into building the hash tables of the joins; compiled code gains about
6 % over the interpreter. Q15, Q17 and Q18 read `lineitem` twice, once for the
subquery, and Q21 three times.

## License

Apache License 2.0, see `LICENSE`.
