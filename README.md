<p align="center"><img src="images/fyrql-banner.png" alt="FyrQL: embeddable federated SQL engine for the JVM" width="800"></p>

FyrQL is an embeddable SQL engine for the JVM that queries data where it lives: the
tables of databases, files, HTTP endpoints, OData services and the Java objects of an
application, joined in one query, in the SQL dialect of [DuckDB](https://duckdb.org). It
is a library in pure Java: no server to run, no native code, and nothing to copy into a
warehouse first.

## Documentation

- [Getting started: FyrQL in 30 minutes](docs/getting-started.md): from an empty project
  to queries over files, Java objects and databases, writing results, a configuration
  file, the JDBC driver, Python, psql and Spring Boot.
- [Manual](docs/manual.md): everything in full, from the API and its settings, every
  connector and the SQL with its differences from DuckDB to the optimizer, code
  generation, parallel execution, spilling and performance.
- [Examples](examples/README.md): programs that run, each with a README and its output:
  a query over a database, a CSV and a JSON file, moving data between databases and
  files, plain JDBC with a configuration file, a connector of its own, and an
  application of Spring Boot.

## What it can do

- **Heterogeneous sources, one query.** PostgreSQL, MySQL, SQLite, H2 and any other
  database with a JDBC driver; CSV, JSON and Parquet files and Apache Iceberg tables,
  local or in S3 and MinIO; JSON from HTTP endpoints, OData services of versions 2 and
  4, and Java collections. The columns of files and endpoints are inferred as DuckDB
  infers them, nested `STRUCT`, `LIST` and `MAP` values included.
  See [Connectors](docs/manual.md#connectors).
- **Federation that lets each source do its part.** A source receives the columns,
  filters, sorts, limits and aggregations that it evaluates exactly as FyrQL would, a
  database also the joins of its own tables, and a Parquet file skips the row groups and
  pages no row of which can pass; what spans sources runs in the engine.
  See [Optimizer](docs/manual.md#optimizer).
- **Analytical SQL.** Joins, grouping, subqueries that are turned into joins, also
  correlated ones, window functions with all frames of DuckDB, set operations and nested
  types, executed over columnar batches by a vectorized interpreter, with whole pipelines
  compiled at run time by Janino, on all cores, and spilling to disk above a memory
  budget. See [Supported SQL](docs/manual.md#supported-sql),
  [Code generation](docs/manual.md#code-generation) and
  [Parallel execution](docs/manual.md#parallel-execution).
- **Moving data.** `COPY ... TO` writes the result of a query into a CSV, JSON or Parquet
  file, also in S3, `COPY ... FROM` loads such a file into a table, and
  `CREATE TABLE ... AS` and `INSERT INTO` write into tables in memory and in databases.
  See [Writing and loading](docs/manual.md#writing-and-loading).
- **Embedded, with clients.** The engine runs inside the application and is set up with
  a builder, a YAML file or, with a starter, the `application.yml` of Spring Boot. A JDBC
  driver, also as one JAR, opens it to tools such as DBeaver; results come as Apache
  Arrow vectors, in the process or from a Flight SQL server in one executable JAR, which
  Python reads through ADBC into pyarrow and pandas. A server that speaks the wire
  protocol of PostgreSQL, also in one executable JAR, lets psql, psycopg, DBeaver and
  Metabase query it as they query PostgreSQL, the other sources by `source.schema.table`.
  Rules for the files that statements may read and write, which the servers close by
  default, time limits for queries, and sessions with their own time zone, search path
  and timeout for each connection and client let others use an engine.
  See [JDBC driver](docs/manual.md#jdbc-driver),
  [Flight SQL server](docs/manual.md#flight-sql-server),
  [PostgreSQL wire protocol server](docs/pgwire.md),
  [File access](docs/manual.md#file-access), [Time limits](docs/manual.md#time-limits),
  [Sessions](docs/manual.md#sessions) and [Spring Boot](docs/spring-boot.md).
- **Observable.** `EXPLAIN ANALYZE` runs a query and shows for each node of its plan the
  rows, batches, time and threads, and for each scan the splits, row groups, requests,
  bytes and statements of its source; queries, requests to sources, compiled code and
  spilled parts are events of Java Flight Recorder.
  See [Observing queries](docs/manual.md#observing-queries).
- **Pure Java.** No JNI, no native libraries, no Hadoop: Parquet and Iceberg are read
  with codecs in pure Java, and Arrow allocates its memory without Netty or native code.
  The same JARs run wherever Java 21 runs, also where native code is not allowed.
- **Checked against DuckDB.** Every test compares the types and rows of a result with
  those of DuckDB over the same data; every build adds hundreds of random queries, each
  also run on one thread and with compiled code, which must give the same results, and
  metamorphic checks run random queries over Parquet, CSV, JSON, Iceberg, H2, SQLite,
  PostgreSQL, Derby and HSQLDB with FyrQL as its own oracle. See
  [Differences from DuckDB](docs/manual.md#differences-from-duckdb) and
  [Building and testing](docs/manual.md#building-and-testing).

## A first look

The customers are in a CSV file and the orders in a list of Java records; one query joins
them:

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
}
```

With `data/customers.csv` holding the regions of three customers, and five orders, this
prints

```
[south, 1, 20.00]
[north, 3, 15.75]
```

[Getting started](docs/getting-started.md#your-first-query) goes on from here, with the
data, the plan of the query and what comes next.

## Sources and clients

| What | Module | In the manual |
| --- | --- | --- |
| Java collections of records, beans or rows, and tables that SQL creates | `fyrql-connector-memory` | [Connectors](docs/manual.md#connectors) |
| CSV and TSV files, also compressed with gzip | `fyrql-connector-csv` | [Connectors](docs/manual.md#connectors) |
| JSON files and JSON lines | `fyrql-connector-json` | [Connectors](docs/manual.md#connectors) |
| Parquet files | `fyrql-connector-parquet` | [Connectors](docs/manual.md#connectors) |
| Apache Iceberg tables | `fyrql-connector-iceberg` | [Connectors](docs/manual.md#connectors) |
| Files in S3 and MinIO | `fyrql-connector-s3` | [Connectors](docs/manual.md#connectors) |
| JSON from HTTP endpoints | `fyrql-connector-http` | [Connectors](docs/manual.md#connectors) |
| OData services of versions 2 and 4 | `fyrql-connector-odata` | [Connectors](docs/manual.md#connectors) |
| Databases over JDBC | `fyrql-connector-jdbc` | [Connectors](docs/manual.md#connectors) |
| The engine and its Java API | `fyrql-engine` | [Using the engine](docs/manual.md#using-the-engine) |
| Compiled code, optional | `fyrql-codegen` | [Code generation](docs/manual.md#code-generation) |
| A configuration file in YAML | `fyrql-config` | [Configuration file](docs/manual.md#configuration-file) |
| A JDBC driver, also in one JAR for tools | `fyrql-jdbc`, `fyrql-jdbc-all` | [JDBC driver](docs/manual.md#jdbc-driver) |
| Results as Apache Arrow vectors | `fyrql-arrow` | [Arrow](docs/manual.md#arrow) |
| An Arrow Flight SQL server, also as one executable JAR | `fyrql-flight-sql`, `fyrql-flight-sql-all` | [Flight SQL server](docs/manual.md#flight-sql-server) |
| A server over the wire protocol of PostgreSQL, for psql, psycopg, DBeaver and Metabase, also as one executable JAR | `fyrql-pgwire`, `fyrql-pgwire-all` | [PostgreSQL wire protocol server](docs/pgwire.md) |
| An engine as a bean of Spring Boot 4, configured in `application.yml` | `fyrql-spring-boot-starter` | [Spring Boot](docs/spring-boot.md) |

## Performance

All 22 TPC-H queries run at scale factor 1 over tables spread across Parquet, CSV, JSON,
PostgreSQL and Java collections, on a laptop in about 21.5 s on one thread and 16 s on
twelve with version 0.15.0, where DuckDB takes about 14.5 s and 9 s over the same sources,
PostgreSQL included. The figures of each query, of one thread against twelve, of compiled
code against the interpreter and of spilling are under
[Performance](docs/manual.md#performance).

## Status

FyrQL is young: version 0.x, not yet used in production, and its API may still change
until 1.0.0. It writes results into files, tables in memory and databases, but does not
update or delete rows; the hash tables of the joins of a query must fit into the memory
budget, since only aggregations, sorts and windows spill to disk; and where native code
is fine and the sources are ones DuckDB reads, DuckDB is faster. FyrQL is meant for Java
applications and JDBC tools that query data across databases, files and services in
plain Java: any JDBC driver, connectors written in Java, and no native libraries or
extensions to load.

## Building

Java 21 or later is required; the Maven wrapper fetches Maven itself.

```
./mvnw -B -ntp verify
```

With `-Pexamples`, the build also builds and tests the [examples](examples/README.md).
[Building and testing](docs/manual.md#building-and-testing) describes the tests against
DuckDB, the random queries, the databases of the tests and the TPC-H runs.

## Contributing and security

Issues are welcome; [CONTRIBUTING.md](CONTRIBUTING.md) says how a pull request comes
about. A vulnerability goes not into a public issue but through private reporting, as
[SECURITY.md](SECURITY.md) describes with what FyrQL counts as one.

## Authors

This repo was produced almost entirely with AI based on a human blueprint.

**Maintainer ([@stefan-zobel](https://github.com/stefan-zobel))**: architecture, design, milestone
plans, and review of every plan and result.

**Claude Opus 5.5** and **Claude Fable 5.1**: implementation, tests, benchmarks, and documentation.

I did not expect AI to be able to implement a project like this until I saw what Opus 4.8 could
achieve in July 2026, so I gave it a try to see whether the Fable & Opus team was up to the task.
Whether it worked is for the code to show: every TPC-H result is checked against DuckDB, the
interpreter and the generated code check each other, and metamorphic and random tests probe
the optimizer and the executor (see [Building and testing](docs/manual.md#building-and-testing)).
Personally, I feel they passed the test.

## License

Mozilla Public License 2.0, see `LICENSE`.
