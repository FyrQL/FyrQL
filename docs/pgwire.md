# PostgreSQL wire protocol server

Part of the [FyrQL manual](manual.md).

## Contents

- [Docker](#docker)
- [psql](#psql)
- [psycopg](#psycopg)
- [pgjdbc and DBeaver](#pgjdbc-and-dbeaver)
- [Metabase](#metabase)
- [Grafana](#grafana)
- [Superset](#superset)

The module `fyrql-pgwire` is a server for an engine that speaks the wire protocol of
PostgreSQL, version 3.0, so that psql, psycopg, the JDBC driver of PostgreSQL and the
tools built on them, such as DBeaver, Metabase, Grafana and Superset, query the sources of
FyrQL without a driver of their own. It needs nothing but the JDK. The JAR of
`fyrql-pgwire-all` holds the server with the engine, code generation and all connectors,
without Arrow, gRPC and Netty, and starts it from a configuration file. This builds it as
`fyrql-pgwire-all/target/fyrql-pgwire-all-<version>.jar`:

```
./mvnw -B -ntp clean package -pl fyrql-pgwire-all -am -DskipTests
```

and this starts it:

```
java -jar fyrql-pgwire-all-<version>.jar fyrql.yaml [--host HOST] [--port PORT]
```

The sources and the section `engine` of the file build the engine, as for the
[configuration file](manual.md#configuration-file), and its section `pgwire` holds the
settings of the server, all of them optional; `--host` and `--port` win over those of the
file:

```yaml
pgwire:
  host: 0.0.0.0
  port: 5433
  tls:
    certificate: server.pem
    key: server.key
  users:
    alice: ${env:ALICE_PASSWORD}
  maxConnections: 100
  maxQueries: 16
  serverVersion: "18.0"
```

- `host` and `port`: where the server listens, by default `127.0.0.1` and 5433, so that
  it does not meet a PostgreSQL on 5432; port 0 takes a free one.
- `users`: names with their passwords. A client signs in with SCRAM-SHA-256, as
  PostgreSQL asks by default, without channel binding (`SCRAM-SHA-256-PLUS`). The server
  derives the keys from the passwords at its start and keeps no password; a user it does
  not know goes through the same steps as one with a wrong password, and both fail with
  `28P01`. Without `users`, the server takes every client under any name, and must then
  listen on a loopback address: another host without them is an error at the start.
  Users on another address without TLS keep their passwords off the network, as SCRAM
  sends none, but queries and their rows cross it in plain text; the server then warns
  on the standard error stream at the start, and runs, as TLS may come from a proxy in
  front of it.
- `tls.certificate` and `tls.key`: the certificate chain and its private key as PEM, the
  key in PKCS #8, as for the [Flight SQL server](manual.md#flight-sql-server). With TLS,
  the server takes only clients that ask for it, as with `sslmode=require`, and refuses
  the others with `28000`; client certificates are not supported.
- `maxConnections`: the most connections at once, by default 100; another one fails with
  `53300`.
- `maxQueries`: the most queries that run at once, by default twice the threads of the
  engine; another one fails with `53000`.
- `serverVersion`: the version of PostgreSQL that the server names, in the parameter
  `server_version` and in `version()`, by default `18.0`. Clients such as pgjdbc and
  DBeaver choose the forms of their queries of the catalogs by it.

Unless the section `engine` sets them, the server sets `fileAccess` to `none`,
`maxQueryTimeout` to an hour and `memoryBudget` to half the heap, as the [Flight SQL
server](manual.md#flight-sql-server) does, with the same meaning. It prints the line
`FyrQL PostgreSQL wire protocol server <version> listening on 127.0.0.1:5433`, with
` (TLS)` if it has a certificate, and a line such as `File access from SQL: none; query
timeout: none, at most 1h; memory budget: 2048MiB; databases: files, iceberg, json,
parquet`, and runs until the JVM ends, as with Ctrl+C, and then closes the engine. The JAR
has the same libraries renamed as the one of the [JDBC driver](manual.md#dbeaver). The
JDBC drivers of databases are not in it: for sources of the type `jdbc`, they go on the
class path next to it, and the server then starts with its class name (with `;` between
the entries of the class path on Windows):

```
java -cp "fyrql-pgwire-all-<version>.jar:drivers/*" net.fyrql.pgwire.PgWireServerMain fyrql.yaml
```

An application starts the server over an engine of its own with
`PgWireServer.builder(engine).config(PgWireConfig.defaults()).build().start()`, and
`PgWireConfig.of(config)` reads the section `pgwire` of a configuration file; closing the
server leaves the engine open, and one engine may serve a Flight SQL server as well. That
engine keeps the file access it was built with, as for the Flight SQL server.

**Databases and schemas.** The database a client names is a source of the engine, and
the schemas of that source are the schemas of PostgreSQL; `pg_database`, and so `\l` in
psql, lists all sources. Without a database, or with the name of the user, as psql sends
it by default, a client gets the default source of the engine, or else its first source;
a name that is no source fails with `3D000`. A connection starts with the search path on
the default schema of the engine in its database, else `main`, else `public`, else the
first schema.

**Names.** Tables resolve as in PostgreSQL, in the database of the connection, which has
the schemas `pg_catalog` and `information_schema` besides its own:

- A name of one part is looked up in `pg_catalog` first, unless the search path names
  it, then in the schemas of the search path in their order, and the first schema with
  the table wins, so that a name in two schemas is not ambiguous. A schema that the
  database does not have is passed over, and `$user` is a schema with the name of the
  user. Without the table in any of them, the name fails with `42P01`.
- `schema.table` is a table of that schema of the database.
- A new table of one part goes to the first schema of the search path that the database
  has, other than the two catalogs; without one, `CREATE TABLE` fails with `3F000`, as in
  PostgreSQL.

`SET search_path TO sales, public` sets the list. `SHOW search_path` gives it as it was
set, `current_schema` and `current_schema()` the first schema of it that exists, or
`NULL`, and `current_schemas(false)` all that exist, in their order;
`current_schemas(true)` has `pg_catalog` in front of them unless the list names it.

Two rules differ from PostgreSQL. A name of three parts, `source.schema.table`, reaches
every source of the engine, where PostgreSQL refuses a name in another database; so one
connection joins across sources, here a connection to the database `sales`:

```sql
SELECT r.region, sum(o.amount) AS revenue
FROM orders AS o JOIN files.main.regions AS r ON o.region_id = r.id
GROUP BY r.region;
```

And names do not tell case apart, as everywhere in FyrQL: `customers` also finds a table
`CUSTOMERS` of a source, which PostgreSQL finds only as `"CUSTOMERS"`. The engine, the
JDBC driver and the Flight SQL server resolve names by the rules of FyrQL, see
[Using the engine](manual.md#using-the-engine).

**Sessions and statements.** Each connection is a [session](manual.md#sessions) of the
engine, on a thread of its own; the parameters of the start, such as `TimeZone`,
`application_name` and those in `options` (`-c name=value`, as psql passes `PGOPTIONS`),
apply as `SET` would. The server answers these statements itself:

- `BEGIN`, `START TRANSACTION`, `COMMIT`, `END`, `ROLLBACK`, `ABORT`, `SAVEPOINT`,
  `RELEASE` and `ROLLBACK TO`, see below;
- `SET`, `RESET` and `SHOW`, also `SHOW ALL`, for the parameters of PostgreSQL:
  `TimeZone`, `statement_timeout` (in milliseconds or with a unit, such as `'5s'`) and
  `search_path` set the session; the server keeps those that only describe the client,
  such as `application_name`, `extra_float_digits` and `client_min_messages`, refuses a
  value it does not follow, such as a `DateStyle` other than ISO, takes any value for a
  name with a dot, as extensions use them, and fails with `42704` for a name it does not
  know;
- `DECLARE`, `FETCH`, `MOVE` and `CLOSE` of cursors, `DISCARD`, `DEALLOCATE`, and
  `UNLISTEN`, which does nothing.

It refuses with `0A000`: `COPY` from `STDIN` or to `STDOUT` and `COPY ... PROGRAM`,
`LISTEN` and `NOTIFY`, two-phase commit, `PREPARE` and `EXECUTE` in SQL (the extended
protocol prepares statements), `VACUUM`, `ANALYZE`, `GRANT`, `TRUNCATE` and the other
statements of maintenance and rights, cursors with `SCROLL` and fetching backward. Every
other statement goes to the engine: queries, `EXPLAIN`, the statements that
[write](manual.md#writing-and-loading), `CREATE VIEW` and `DROP VIEW` (see
[Views](manual.md#views)), and `COPY` to and from the files of the machine of the server,
as far as its [file access](manual.md#file-access) allows. A text of the simple protocol
may hold several statements between semicolons, which run in turn; an empty one is
answered as empty. The tags of the answers are those of PostgreSQL, such as `SELECT 3`,
`INSERT 0 3`, `CREATE TABLE`, `CREATE VIEW` and `COPY 3`.

**Transactions.** FyrQL has no transactions, and each statement writes for itself.
`BEGIN`, `COMMIT` and `ROLLBACK` only follow the state that PostgreSQL reports to its
clients, so that a client that turns auto-commit off works: after an error in a block,
every statement fails with `25P02` until `ROLLBACK`, as in PostgreSQL, and a `ROLLBACK`
after a statement that wrote warns `FyrQL has no transactions: what the statements of
the transaction wrote stays written`.

**Prepared statements and parameters.** The extended protocol, with which pgjdbc, psycopg
and most drivers send their statements, prepares named and unnamed statements, binds them
to portals and runs them, also a part of the rows at a time; a portal ends with the next
`Sync` outside a block. Its parameters are `$1`, `$2` and so on, which the engine takes as
it takes `?` (see [Using the engine](manual.md#using-the-engine)). A parameter of a type
that the client names, such as `int4` or `date`, is cast to that type, as
`CAST($1 AS INTEGER)`; one without a type, as psycopg sends a string, takes the type of
its context, `VARCHAR` without one, and a `numeric` without one `DECIMAL(38,10)`. Values
come in text or in binary.

**Cursors.** `DECLARE c CURSOR FOR query` opens a cursor in a block of `BEGIN`, and
`DECLARE c CURSOR WITH HOLD FOR query` one that stays open after it; `FETCH [NEXT |
FORWARD | n | FORWARD n | ALL | FORWARD ALL] [FROM | IN] c` reads its next rows, `MOVE`
passes over them, and `CLOSE c` or `CLOSE ALL` ends it. A cursor reads forward only, and
its query runs as the client fetches; the end of a block closes the cursors without
`WITH HOLD`. pgjdbc reads a result in parts this way with `setFetchSize` while
auto-commit is off, and psycopg with a named cursor.

**Cancel and time limits.** A cancel request, as psql sends it on Ctrl+C and pgjdbc on
`Statement.cancel()`, stops the query that runs on the connection, which fails with
`57014`; so does one past its `statement_timeout`, with the message `canceling statement
due to statement timeout`.

**Catalogs.** For each database, the server builds the tables of `pg_catalog` and
`information_schema` that clients read from the catalog of its source, with the columns
of PostgreSQL 18: `pg_namespace`, `pg_class`, `pg_attribute`, `pg_type`, `pg_database`,
`pg_proc`, `pg_settings`, `pg_roles`, `pg_tables`, `pg_views`, `pg_stat_user_tables` and
others, and `schemata`, `tables`, `columns`, `views`, `table_constraints`,
`key_column_usage` and `referential_constraints`. The [views](manual.md#views) of the
engine in the source of the database are there as PostgreSQL has views: in `pg_class`
with the kind `v`, with their columns in `pg_attribute` and `information_schema.columns`,
in `pg_views` and `information_schema.views` with the text of their queries as written,
and in `information_schema.tables` with the type `VIEW`, but not in `pg_tables`; a view
created over the server has columns named as PostgreSQL names them. The tables of things
that FyrQL has none of, such as indexes, constraints, triggers and partitions, are there
and empty, and so a table has no primary key there. The two catalogs are only in the sessions of the
server: the engine, the JDBC driver and the Flight SQL server do not see them, and they
hide a schema of the same name of a source, such as the `information_schema` of H2. A
snapshot is at most two seconds old, and new after a statement of the server that writes.
The OIDs are stable: the catalogs and types have those of PostgreSQL, and the schemas,
tables and columns of the sources numbers from a hash of their names, which stay the same
while the names do. The owner of every object is `fyrql`. The functions that clients call
over the catalogs are there as well: `format_type`, `pg_get_userbyid`, `to_regclass`,
`has_table_privilege` and its kin, which always grant, the visibility of objects, which
they always have, `obj_description` and `col_description`, which are `NULL`,
`pg_get_viewdef`, which gives the text of a view by its OID or name, the texts of other
definitions such as `pg_get_indexdef`, which are `NULL`, the sizes of relations, which
are 0, and `string_to_array` and `parse_ident`.

**Rewriting.** The engine follows DuckDB, and the server rewrites the forms of PostgreSQL
that it does not take before the engine sees a statement: casts to `regclass`, `regtype`,
`oid`, `name` and the other types of the catalogs; the qualifier `pg_catalog.` of
functions and types; `x = ANY(list)` and `x <> ALL(list)`; array literals such as
`'{a,b}'::text[]`; `COLLATE`; `current_user`, `session_user`, `user`, `current_role`,
`current_catalog`, `current_schema`, `current_schemas`, `current_setting` of the
parameters of the server, `version()` and `pg_backend_pid()`; strings in `$tag$`;
keywords as labels after `AS`; `generate_series`, `string_to_array` and `parse_ident` in
`FROM`; `array(SELECT ...)`; `to_date` and `to_timestamp` with a pattern, which become
`strptime`; `generate_subscripts` and a scalar subquery over `pg_sequence`, which read
catalogs without rows here; and the subqueries of the `\d` commands of psql. One rule
matters for queries of one's own: `~`, `~*`, `!~` and `!~*` match a part of the string, as
in PostgreSQL, where elsewhere in FyrQL `~` matches the whole string, as in DuckDB.

**Labels.** An output column without an alias gets the label PostgreSQL gives it, not the
one of FyrQL: a column its name, a call its function (`lower` for `lower(x)`, `count` for
`count(*)`, `btrim` for `trim(x)`), a cast the name of its operand or else of its type
(`int4` for `1::int`, `date` for `CAST(NULL AS date)`), `CASE`, `COALESCE`, `NULLIF`,
`GREATEST`, `LEAST`, `EXISTS` and `ARRAY` their keyword, a scalar subquery the name of its
column, and every other expression `?column?`, as an operator, a literal or `true`.
`GROUP BY` and `ORDER BY` take these labels as names, as in
`SELECT count(*) FROM t ORDER BY count`.
The columns of a subquery are named by the same rule, but each name once, as in DuckDB:
`SELECT * FROM (SELECT 1, 2) s` has the columns `?column?` and `?column?_1`, where
PostgreSQL repeats the label. A cast to `varchar` takes the name
`text`, the type of the server for strings, where PostgreSQL takes `varchar`; and two
forms keep the name of the function the parser makes of them: `position(a IN b)` is
`strpos` and `EXTRACT(year FROM d)` is `date_part`. A test compares the labels of some 90
items with those of PostgreSQL 18.

**Types.** The server gives the types of FyrQL these types of PostgreSQL:

| FyrQL | PostgreSQL |
| --- | --- |
| `BOOLEAN` | `bool` |
| `TINYINT`, `SMALLINT` | `int2` |
| `INTEGER` | `int4` |
| `BIGINT` | `int8` |
| `HUGEINT`, `DECIMAL(p,s)` | `numeric`, with precision and scale |
| `FLOAT` | `float4` |
| `DOUBLE` | `float8` |
| `VARCHAR` | `text` |
| `BLOB` | `bytea` |
| `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ`, `INTERVAL` | `date`, `time`, `timestamp`, `timestamptz`, `interval` |
| `UUID` | `uuid` |
| `T[]` of a type above | the array of its type, such as `int4[]` |
| a list of lists, `STRUCT`, `MAP` | `text`, in the text form of FyrQL |

Values go out in the text form of PostgreSQL, such as `t` and `f`, `\x0102` for bytes,
`1 year 2 mons 3 days 04:05:06.5` and a `TIMESTAMPTZ` with the offset of the time zone of
the session, or in binary, as the client asks for each column. Parameters come in either
form for all of these types, and a `json` or `jsonb`, a `varchar` or a `bpchar` as a
`VARCHAR`. FyrQL has no type `json`, so that the operators of `jsonb` such as `#>>` are
not taken.

The other errors have the SQLSTATEs of FyrQL, which are those of PostgreSQL, such as
`42601` for a syntax error, `42P01` for a table and `42703` for a column that does not
exist, with the position in the text where FyrQL has one, and `XX000` for an
error of a source.

## Docker

From 1.0.0 on, every release publishes an image of the server in the GitHub Container
Registry, for amd64 and arm64: `ghcr.io/fyrql/fyrql-pgwire:<version>`, also under the
tags of its minor and major version, such as `1.0` and `1`, and `latest`. It holds the
JAR on a JRE 21 of Eclipse Temurin on Ubuntu and starts the server as the user 10001 from
`/config/fyrql.yaml` on `0.0.0.0` and port 5433:

```
docker run -p 5433:5433 -v "$PWD/conf:/config:ro" -v "$PWD/data:/data:ro" \
  -e FYRQL_PASSWORD ghcr.io/fyrql/fyrql-pgwire:<version>
```

with a file `conf/fyrql.yaml` such as

```yaml
sources:
  - name: files
    type: csv
    directory: /data

pgwire:
  users:
    alice: ${env:FYRQL_PASSWORD}
```

after which psql, DBeaver or Metabase connect to `localhost:5433` as `alice`.

- As the server listens on `0.0.0.0`, the section `pgwire` must name `users`; without,
  it does not start. The passwords come from the environment or the secrets of the
  platform through `${env:NAME}`, not from the file or the image.
- `/config` holds the file and what it names, such as the certificate and the key of
  TLS; without TLS, the server warns that queries cross the network in plain text, and
  TLS may also come from a proxy in front of it. A relative path in the file is resolved
  against `/config`, so that `../data` is `/data` too.
- `/data` holds the files of the sources; statements read and write files only as far as
  the [file access](manual.md#file-access) allows, and writing needs a volume the user
  10001 may write to.
- `/drivers` holds the JDBC drivers of the databases of sources of the type `jdbc`, such
  as `-v "$PWD/drivers:/drivers:ro"` with the JAR of the driver of PostgreSQL; they are on
  the class path of the server.
- The heap may take three quarters of the memory of the container, and the memory budget
  is half the heap unless the file sets `memoryBudget`; spill files go to `/tmp` of the
  container. `JAVA_TOOL_OPTIONS`, such as `-e JAVA_TOOL_OPTIONS=-Xmx4g`, sets further
  options of the JVM. Arguments after the image replace the default ones, such as
  `/config/other.yaml --host 0.0.0.0 --port 6543`.
- `docker stop` ends the server as Ctrl+C does, and the server closes the engine.

In a Compose file, next to Metabase or Grafana, the service is

```yaml
services:
  fyrql:
    image: ghcr.io/fyrql/fyrql-pgwire:<version>
    ports: ["5433:5433"]
    volumes: ["./conf:/config:ro", "./data:/data:ro"]
    environment:
      FYRQL_PASSWORD: ${FYRQL_PASSWORD}
```

and the other services reach it as `fyrql:5433`. The images have attestations of where
they were built, signed by GitHub, which `gh attestation verify
oci://ghcr.io/fyrql/fyrql-pgwire:<version> --owner FyrQL` checks. The image of the
[Flight SQL server](manual.md#docker) works the same way.

## psql

With the files of the [JDBC example](manual.md#jdbc-driver), and the server started on
`fyrql.yaml` without the section `pgwire`, psql 18 lists the tables and runs the query
of that example:

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

`\l`, `\dn`, `\dt`, `\dv`, `\di`, `\d table`, `\d+ table`, `\df`, `\du`, `\dT` and
`\conninfo` work, and `\d` shows no indexes, keys or defaults, which FyrQL does not have;
`\copy` does not work, as it sends `COPY ... FROM STDIN`. With `users`, psql asks for the
password, or takes it from `PGPASSWORD`; with TLS, it connects with
`"host=... sslmode=require"`, or with `sslmode=verify-ca` and `sslrootcert=server.pem` for
a certificate that no known authority signed.

## psycopg

psycopg 3 connects as to PostgreSQL:

```python
import psycopg

with psycopg.connect("host=127.0.0.1 port=5433 dbname=files user=alice", autocommit=True) as conn:
    rows = conn.execute("""
        SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
        FROM orders AS o JOIN customers AS c ON o.customer = c.id
        WHERE o.amount > %s
        GROUP BY ALL
        ORDER BY revenue DESC""", (6.0,)).fetchall()
    print(rows)
```

```
[('south', 1, 20.0), ('north', 1, 10.5)]
```

Prepared statements (`prepare=True`), the binary format (`binary=True`), named cursors,
`executemany` into a source of the type `memory`, and the types `date`, `timestamp`,
`timestamptz`, `numeric`, `uuid`, `bytea` and lists both ways were tried with psycopg
3.3.6. Without `autocommit=True`, psycopg opens a block with `BEGIN`, which works as
described under Transactions.

## pgjdbc and DBeaver

The JDBC driver of PostgreSQL (`org.postgresql:postgresql`) connects with a URL such as

```
jdbc:postgresql://localhost:5433/files?user=alice&password=secret
```

and the tests run the 22 TPC-H queries through it, in text and in binary
(`binaryTransfer=true&prepareThreshold=1`), the methods of its `DatabaseMetaData`, cursors
with `setFetchSize`, cancel and statements that write; this was tried with pgjdbc 42.7.14.
Its `getCatalogs()` lists all sources, while the schemas, tables and columns of its
`DatabaseMetaData` are those of the database of the connection, as with PostgreSQL. In
DBeaver, a connection of the type PostgreSQL with host, port, database and user shows the
schemas, tables and columns with their types, the data of a table, sorted and filtered,
and runs queries; with "Show all databases" on the page PostgreSQL of the connection, it
shows every source as a database. Its SQL editor runs a query in the database it shows
as active, so that the tables of another database take three parts, or that database
becomes the active one. A connection of the type PostgreSQL needs no driver of FyrQL,
unlike the one of the [JDBC driver](manual.md#dbeaver), and the engine runs in the
server.

## Metabase

Metabase connects with a database of the type PostgreSQL, with host, port, the source as
the database name, and user and password; it synchronizes the schemas, tables and columns,
fingerprints the columns, and answers questions of the query builder and native queries.
This was tried with Metabase 0.64.3.2.

## Grafana

Grafana connects with a data source of the type PostgreSQL, with host and port, the
source as the database, user and password, and TLS mode `disable` unless the server has
TLS. `Save & test` passes. The query builder lists the tables of the database, those on
the search path without their schema, and their columns, as it reads them from
`information_schema` with `string_to_array` and `parse_ident`; tables, time series with
`$__timeFilter`, `$__timeGroup`, `$__timeGroupAlias`, `$__time` and `$__timeEpoch`, and
variables of a query, also with `__text` and `__value`, run as in PostgreSQL. This was tried
with Grafana 13.2.4, through its HTTP API.

## Superset

Superset connects with a database such as
`postgresql+psycopg2://alice:secret@localhost:5433/files`. The test of the connection
passes; it lists the schemas, the tables and their columns through the dialect of
SQLAlchemy for PostgreSQL, creates datasets, runs queries in SQL Lab, and draws charts with
aggregates, filters, the time grains of `DATE_TRUNC` and time ranges, whose bounds are
calls of `TO_DATE` and `TO_TIMESTAMP` with a pattern. A table has no primary key and
no identity column here, as FyrQL keeps none. This was tried with Superset 6.1.0 on Python
3.11 with SQLAlchemy 1.4.54 and psycopg2 2.9.13, through its REST API.
