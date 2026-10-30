<p align="center"><img src="../images/fyrql-banner.png" alt="FyrQL: embeddable federated SQL engine for the JVM" width="480"></p>

# Getting started: FyrQL in 30 minutes

This tour takes you from an empty project to queries over files, Java objects and
databases, results written into files and tables, a configuration file, the JDBC driver,
Python and psql, and at the end Spring Boot. Each step builds on the one before and takes a
few minutes. The [manual](manual.md) has the details of every step; the
[README](../README.md) gives the overview.

## Contents

- [What you need](#what-you-need)
- [Your first query](#your-first-query)
- [Parameters](#parameters)
- [Querying across sources](#querying-across-sources)
- [Writing results](#writing-results)
- [Window functions](#window-functions)
- [A configuration file](#a-configuration-file)
- [Threads, memory and code generation](#threads-memory-and-code-generation)
- [The JDBC driver and DBeaver](#the-jdbc-driver-and-dbeaver)
- [Arrow, Flight SQL and Python](#arrow-flight-sql-and-python)
- [psql and the PostgreSQL protocol](#psql-and-the-postgresql-protocol)
- [Spring Boot](#spring-boot)
- [Where to go next](#where-to-go-next)

## What you need

*2 minutes.* FyrQL needs Java 21 or later. Until it is published, build it once and
install its modules into the local Maven repository; the Maven wrapper fetches Maven
itself:

```
./mvnw -B -ntp install -DskipTests
```

An application then depends on the engine, the connectors of its sources and, for
compiled code, `fyrql-codegen`:

```xml
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-engine</artifactId>
    <version>0.22.5</version>
</dependency>
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-connector-csv</artifactId>
    <version>0.22.5</version>
</dependency>
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-connector-memory</artifactId>
    <version>0.22.5</version>
</dependency>
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-codegen</artifactId>
    <version>0.22.5</version>
</dependency>
```

The other connectors are `fyrql-connector-json`, `-parquet`, `-xlsx`, `-iceberg`, `-s3`,
`-http`, `-odata` and `-jdbc`; [Modules](manual.md#modules) lists all modules.

## Your first query

*5 minutes.* The customers are in a CSV file, `data/customers.csv`:

```
id,region
1,north
2,south
3,north
```

and the orders are Java records in a list:

```java
record Order(long id, Integer customerId, BigDecimal amount) { }

List<Order> orders = List.of(
        new Order(1, 1, new BigDecimal("10.50")), new Order(2, 2, new BigDecimal("20.00")),
        new Order(3, 3, new BigDecimal("5.25")), new Order(4, 1, null),
        new Order(5, null, new BigDecimal("7.00")));
```

An engine registers both as sources, the directory as `csv` and the list as the table
`orders` of `mem`, and joins them in one query:

```java
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

This prints the rows and the plan:

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

A table is named `source.schema.table`, or shorter, as long as the name is unique; the
schema is `main` unless a source says otherwise. The CSV file needs no schema: its
columns and types are inferred from the data, as DuckDB infers them. The plan is the one
that runs: the scan of the orders reads only the two columns the query uses,
`[codegen]` marks the nodes that are compiled to bytecode once enough rows pass, and
`[parallel]` the chains that run on several threads. `engine.rows(sql)` returns all rows
at once, with a `DATE` as a `LocalDate` and the other dates and times as classes of
`java.time` as well. A query that does not parse or bind throws an `SqlException` with the position
in the text, such as `Referenced column "x" not found in FROM clause! at line 1, column 33`.

`engine.explainAnalyze(sql)`, or `EXPLAIN ANALYZE` in SQL, runs the query and adds to
each line of the plan, after `--`, what its node did: the rows and batches it returned,
the time it worked and its threads, and for a scan what it read from its source. In one
run, the customers gave
`Scan csv.main.customers (id BIGINT, region VARCHAR) [parallel] -- 3 rows, 1 batch, 976 us, 1 thread; 1 split, 34 bytes`.
While Java Flight Recorder records, each query is an event `net.fyrql.Query` as well.

More: [Using the engine](manual.md#using-the-engine), [Connectors](manual.md#connectors),
[Observing queries](manual.md#observing-queries).

## Parameters

*2 minutes.* A query with parameters `?` is prepared once, which parses and binds it, and
runs with values, here with the engine of the first query:

```java
PreparedQuery larger = engine.prepare("""
        SELECT o.id, c.region, o.amount
        FROM orders AS o JOIN customers AS c ON o.customerId = c.id
        WHERE o.amount > ?
        ORDER BY o.id""");
for (Object[] row : engine.rows(larger, List.of(new BigDecimal("6.00")))) {
    System.out.println(Arrays.toString(row));
}
```

```
[1, north, 10.50]
[2, south, 20.00]
```

A parameter takes its type from its context, here the `DECIMAL(10,2)` of `amount`; where
there is none, as in `SELECT ?`, it is a `VARCHAR`, and a cast gives it another type, as
`CAST(? AS DATE)`. Parameters may also be numbered, `$1`, `$2`, as in PostgreSQL.

More: [Using the engine](manual.md#using-the-engine).

## Querying across sources

*4 minutes.* The point of FyrQL is a query over sources of different kinds. Here the
orders are in PostgreSQL, the customers in a CSV file and the products come from an HTTP
endpoint as JSON (with `fyrql-connector-jdbc`, `fyrql-connector-http` and the driver of
PostgreSQL on the class path):

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

`[pushed: ...]` shows what a source took over: PostgreSQL receives
`SELECT t."customer_id", t."sku" FROM "public"."orders" t WHERE ...`, two of the five
columns and the filter, while the joins, the grouping and the order run in the engine.
A database also runs the joins of its own tables, and a Parquet file skips the row groups
in which no row passes.

Files can also be queried without registering them, by their names or with the table
functions of DuckDB, as long as the module of the format is on the class path:

```sql
SELECT region, count(*) FROM 'data/customers.csv' GROUP BY region;
SELECT * FROM read_parquet('lake/events.parquet') LIMIT 10;
SELECT region, sum(amount) FROM read_parquet('lake/sales') WHERE year = 2024 GROUP BY region;
SELECT count(*) FROM iceberg_scan('warehouse/db/orders');
SELECT snapshot_id, "timestamp_ms" FROM iceberg_snapshots('warehouse/db/orders');
```

A directory, such as `lake/sales`, is a table of all its files, and directories of the
form `year=2024` below it add columns, as DuckDB writes them with `PARTITION_BY`; the
filter on `year` skips the files of other years without reading them, see [Partitioned
files](manual.md#partitioned-files).

More: [Connectors](manual.md#connectors) with what each source takes over,
[Optimizer](manual.md#optimizer).

## Writing results

*3 minutes.* `COPY ... TO` writes the result of a query into a CSV, JSON or Parquet
file or an Excel workbook, `CREATE TABLE` and `INSERT INTO` write into tables in memory
and in databases, `UPDATE`, `DELETE` and `MERGE` change their rows, and `COPY ... FROM`
loads a file into a table. `engine.update(sql)` runs such a statement and returns the
number of rows. With the engine of the first query:

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

The file that `COPY ... TO` wrote loads back into a table with types of its own:

```java
engine.update("CREATE TABLE mem.archive (id BIGINT NOT NULL, region VARCHAR, amount DECIMAL(10,2))");
long loaded = engine.update("COPY mem.archive FROM 'data/orders-by-region.csv'");
```

With `fyrql-connector-xlsx`, the same goes for Excel: a workbook holds numbers, texts,
booleans, dates and times, and `read_xlsx` or the name of the file reads it as DuckDB
does, a number as a `DOUBLE`:

```java
engine.update("COPY (SELECT * FROM regions ORDER BY region) TO 'data/regions.xlsx' (HEADER true)");
System.out.println(engine.rows("SELECT * FROM 'data/regions.xlsx'").stream().map(Arrays::toString).toList());
```

```
[[north, 15.75], [south, 20.0]]
```

More: [Writing and loading](manual.md#writing-and-loading).

## Window functions

*3 minutes.* FyrQL takes the SQL of DuckDB, with joins, subqueries, set operations,
nested types and window functions. Over the data of the first query, window functions
rank the orders of each region and add up their amounts:

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
```

With `QUALIFY`, the largest order of each region is

```sql
SELECT c.region, o.id
FROM orders AS o JOIN customers AS c ON o.customerId = c.id
WINDOW w AS (PARTITION BY c.region ORDER BY o.amount DESC)
QUALIFY row_number() OVER w = 1
ORDER BY c.region
```

with the rows `[north, 1]` and `[south, 2]`. A lateral join reads the row before it and
runs once for each, so that `LIMIT 2` takes the two largest orders of each region: two
of north, whose third order has no amount, and the only order of south:

```sql
SELECT c.region, top.id, top.amount
FROM (SELECT DISTINCT region FROM customers) AS c,
     LATERAL (SELECT o.id, o.amount
              FROM orders AS o JOIN customers AS k ON o.customerId = k.id
              WHERE k.region = c.region
              ORDER BY o.amount DESC NULLS LAST LIMIT 2) AS top
ORDER BY c.region, top.amount DESC
```

```
[north, 1, 10.50]
[north, 3, 5.25]
[south, 2, 20.00]
```

`median`, `quantile_cont`, `corr`, `regr_slope`, `arg_max`, `mode` and the other
statistics of DuckDB work as aggregates and over windows, and `WITH RECURSIVE` walks
hierarchies.

More: [Supported SQL](manual.md#supported-sql),
[Differences from DuckDB](manual.md#differences-from-duckdb).

## A configuration file

*3 minutes.* With the module `fyrql-config`, a YAML file names the sources and settings
of an engine, and `FyrQLConfig.open(Path.of("fyrql.yaml"))` builds it:

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
      sales: { path: lake/sales, hiveTypes: "year INTEGER" }
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

The section `engine` holds the settings of the builder. An engine that runs the
statements of others, such as behind a server, limits which files they may read with
`FROM 'file'` or `COPY`, and how long a query may run:

```yaml
engine:
  fileAccess: read
  allowedLocations: [data]
  queryTimeout: 30s
```

The `type` of a source picks its connector: `csv`, `json`, `parquet`, `xlsx`, `iceberg`,
`http`, `odata`, `jdbc`, `memory` or `s3`. `${env:NAME}` and `${sys:name}` take a value from the
environment or a system property, so that secrets need not be in the file, and relative
paths are resolved against the directory of the file. Files in S3 or MinIO need a source
of the type `s3`:

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

The section `views` gives queries over the sources names that every client sees as
tables, the JDBC driver, Flight SQL and psql alike; `CREATE VIEW` does the same in SQL,
for as long as the engine runs:

```yaml
views:
  - name: shop.public.big_orders
    sql: SELECT * FROM orders WHERE amount > 1000
```

More: [Configuration file](manual.md#configuration-file) with the options of each type,
and [Views](manual.md#views).

## Threads, memory and code generation

*2 minutes.* The defaults suit most applications: a query runs on all processors, with
no limit of memory, and compiles its hot loops when `fyrql-codegen` is on the class path.
A budget limits the memory that the queries of an engine hold together; above it,
aggregations, sorts, windows and joins spill to disk:

```java
FyrQL engine = FyrQL.builder()
        .register(...)
        .threads(4)
        .memoryBudget(256L << 20)          // 256 MB for all queries together
        .spillDirectory(Path.of("/var/tmp"))
        .build();
```

More: [Engine settings](manual.md#engine-settings),
[Parallel execution](manual.md#parallel-execution),
[Memory budget and spilling](manual.md#memory-budget-and-spilling),
[Code generation](manual.md#code-generation).

## The JDBC driver and DBeaver

*3 minutes.* The module `fyrql-jdbc` is a JDBC driver that runs an engine in the process.
Its URL names a configuration file, here `fyrql.yaml` with one source:

```yaml
sources:
  - name: files
    type: csv
    directory: data
```

With `data/customers.csv` of the first query and `data/orders.csv`:

```
id,customer,amount
1,1,10.50
2,2,20.00
3,3,5.25
4,1,
5,,7.00
```

this lists the tables and runs a query with a parameter:

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

The CSV file gives `amount` the type `DOUBLE`, as DuckDB would, and so the sums are
doubles. Settings of the engine can follow in the URL, as in
`jdbc:fyrql:?config=fyrql.yaml&threads=4&memoryBudget=512MB`. Connections with the same
URL share one engine and its tables in memory; the last one to close closes it, unless
`closeDelay` keeps it open for a while, such as `closeDelay=5m`, or for good with `-1`.

For DBeaver and other tools, `fyrql-jdbc-all` holds the driver with the engine and all
connectors in one JAR, built as `fyrql-jdbc-all/target/fyrql-jdbc-all-<version>.jar`:

```
./mvnw -B -ntp clean package -pl fyrql-jdbc-all -am -DskipTests
```

In the Driver Manager of DBeaver, a new driver gets the class name
`net.fyrql.jdbc.FyrQLDriver`, the URL template `jdbc:fyrql:?config={file}` without user
and password, and as libraries the JAR and the JDBC drivers of the databases that the
configuration file names. A connection with that driver then asks for the file. A
connection is always in auto-commit: each statement takes effect at once, and `commit` and
`rollback` do nothing, so DBeaver stays in auto-commit, its default. The rows of a table
with a primary key can be edited in the grid of DBeaver.

More: [JDBC driver](manual.md#jdbc-driver), [DBeaver](manual.md#dbeaver).

## Arrow, Flight SQL and Python

*3 minutes.* With `fyrql-arrow`, a query runs as an `ArrowReader` of Apache Arrow, for
libraries that take Arrow vectors:

```java
try (BufferAllocator allocator = new RootAllocator();
        FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT * FROM orders", allocator)) {
    VectorSchemaRoot root = reader.getVectorSchemaRoot();
    while (reader.loadNextBatch()) {
        System.out.println(root.getRowCount() + " rows");
    }
}
```

Arrow needs the JVM option `--add-opens=java.base/java.nio=ALL-UNNAMED`.

Clients in other languages reach an engine through the Arrow Flight SQL server, one
executable JAR with the engine and all connectors, which this builds and starts on the
`fyrql.yaml` of the JDBC driver:

```
./mvnw -B -ntp clean package -pl fyrql-flight-sql-all -am -DskipTests
java -jar fyrql-flight-sql-all/target/fyrql-flight-sql-all-<version>.jar fyrql.yaml
```

The server listens on `127.0.0.1:32010` and takes every client there; a section `server`
in the file sets another address, users and passwords or a token, and TLS. Statements of
clients read no files of the machine of the server, as with `FROM 'data/orders.csv'`,
unless the section `engine` allows it with `fileAccess: read` and `allowedLocations`; the
tables of the sources stay readable (see [File access](manual.md#file-access)). No query
runs longer than an hour, and the queries hold at most half the heap, above which they
spill, unless the section `engine` sets `maxQueryTimeout` and `memoryBudget`, with `0`
for no limit. With Python 3.10 or later, `pip install adbc-driver-flightsql pyarrow
pandas` installs a client, which runs the query of the JDBC driver:

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

More: [Arrow](manual.md#arrow), [Flight SQL server](manual.md#flight-sql-server),
[Python](manual.md#python).

## psql and the PostgreSQL protocol

*3 minutes.* Tools of PostgreSQL reach an engine through a server that speaks the wire
protocol of PostgreSQL, another executable JAR with the engine and all connectors, which
this builds and starts on the same `fyrql.yaml`:

```
./mvnw -B -ntp clean package -pl fyrql-pgwire-all -am -DskipTests
java -jar fyrql-pgwire-all/target/fyrql-pgwire-all-<version>.jar fyrql.yaml
```

The server listens on `127.0.0.1:5433` and takes every client there; a section `pgwire`
in the file sets another address, users and passwords, and TLS, and the limits of the
Flight SQL server apply as well. Each source is a database, here `files`, and psql
connects to it as to PostgreSQL:

```
psql -h 127.0.0.1 -p 5433 -d files
files=> \dt
           List of tables
 Schema |   Name    | Type  | Owner
--------+-----------+-------+-------
 main   | customers | table | fyrql
 main   | orders    | table | fyrql
(2 rows)

files=> SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
files->   FROM orders AS o JOIN customers AS c ON o.customer = c.id
files->   WHERE o.amount > 6 GROUP BY ALL ORDER BY revenue DESC;
 region | orders | revenue
--------+--------+---------
 south  |      1 |      20
 north  |      1 |    10.5
(2 rows)
```

psycopg, the JDBC driver of PostgreSQL, DBeaver with a connection of the type PostgreSQL,
Metabase, Grafana and Superset connect the same way, with host, port and the source as the
database. Names resolve as in PostgreSQL, along the `search_path` in the database of the
connection; with more sources, a query reaches the tables of the others by three parts,
such as `shop.main.customers`. A column without an alias has the label PostgreSQL gives
it, such as `count` for `count(*)`.

From 1.0.0 on, the server also comes as a Docker image, `ghcr.io/fyrql/fyrql-pgwire`,
which takes the file from a volume; see [Docker](pgwire.md#docker).

More: [PostgreSQL wire protocol server](pgwire.md), [psql](pgwire.md#psql),
[psycopg](pgwire.md#psycopg).

## Spring Boot

*Optional, 3 minutes more.* In an application of Spring Boot 4, the starter makes the
engine a bean, configured in `application.yml`. Besides the connectors of the sources,
the application depends on

```xml
<dependency>
    <groupId>io.github.fyrql</groupId>
    <artifactId>fyrql-spring-boot-starter</artifactId>
    <version>0.22.5</version>
</dependency>
```

and names the sources and settings under `fyrql`, as in a configuration file:

```yaml
fyrql:
  engine:
    threads: 4
  sources:
    - name: files
      type: csv
      directory: data
```

The engine is then a bean like any other, and it closes with the application:

```java
@RestController
class Regions {

    private final FyrQL engine;

    Regions(FyrQL engine) {
        this.engine = engine;
    }

    @GetMapping("/regions")
    List<Object[]> regions() {
        return engine.rows("SELECT region, count(*) FROM files.customers GROUP BY region");
    }
}
```

Placeholders in `application.yml` are those of Spring, such as `${DB_PASSWORD}`, not
`${env:DB_PASSWORD}` as in a configuration file.

More: [Spring Boot](spring-boot.md), with sources of the application, a
`DataSource` and the health indicator.

## Where to go next

The [examples](../examples/README.md) are small programs that run, each with a README
and its output: a query over a database, a CSV and a JSON file with its plan, moving data
with `COPY` and `CREATE TABLE ... AS`, plain JDBC with a configuration file, a connector
of your own, and an application of Spring Boot.

The [manual](manual.md) describes everything in full:

- [Connectors](manual.md#connectors): every source, its options and what it takes over.
- [Supported SQL](manual.md#supported-sql) and
  [Differences from DuckDB](manual.md#differences-from-duckdb).
- [Optimizer](manual.md#optimizer), [Code generation](manual.md#code-generation),
  [Parallel execution](manual.md#parallel-execution) and
  [Memory budget and spilling](manual.md#memory-budget-and-spilling): how queries run.
- [Performance](manual.md#performance): TPC-H against DuckDB and the benchmarks.
- [Building and testing](manual.md#building-and-testing): the tests against DuckDB.
