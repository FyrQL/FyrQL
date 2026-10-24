# Configuration file and JDBC driver

FyrQL through plain JDBC, with its sources in a configuration file. The application
knows nothing of FyrQL but the URL `jdbc:fyrql:?config=fyrql.yaml`: for each connection,
the driver builds an engine from the file, and the connection shows one database with
the tables of all sources
([`ConfigAndJdbc.java`](src/main/java/net/fyrql/examples/configandjdbc/ConfigAndJdbc.java)).

The file [`fyrql.yaml`](fyrql.yaml) names two sources and two settings:

```yaml
sources:
  - name: sales
    type: jdbc
    url: jdbc:h2:./target/db/sales;DATABASE_TO_LOWER=TRUE
    user: sales
    password: ${env:SALES_PASSWORD}
    schemas: public
  - name: files
    type: csv
    directory: ${env:DATA_DIR:-data}

engine:
  defaultSource: sales
  threads: 2
```

- `${env:SALES_PASSWORD}` takes the password from the environment, so that it is not in
  the file; a variable that is not set is an error that names it.
- `${env:DATA_DIR:-data}` takes the directory of the CSV files from `DATA_DIR`, or else
  `data`. A relative directory is resolved against the directory of the file; the path
  in the URL of H2 is not, it is H2's, relative to the working directory.
- `schemas: public` shows only the tables of that schema, without those of H2's
  `information_schema`.
- `defaultSource: sales` lets a query name the orders `orders` instead of
  `sales.orders`.

The example creates the database, an H2 file under `target/db`, then lists the tables
over `DatabaseMetaData` and runs a query with a parameter twice:

```java
try (Connection connection = DriverManager.getConnection("jdbc:fyrql:?config=fyrql.yaml")) {
    try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
        ...
    }
    try (PreparedStatement statement = connection.prepareStatement("""
            SELECT c.name, count(*) AS orders, sum(o.amount) AS revenue
            FROM orders AS o JOIN files.customers AS c ON o.customer_id = c.id
            WHERE c.region = ?
            GROUP BY c.name
            ORDER BY revenue DESC""")) {
        statement.setString(1, "north");
        ...
    }
}
```

Its dependencies are the driver `fyrql-jdbc`, the connectors of the sources and the
driver of H2.

## Running it

FyrQL is not yet published, so install it once from the root of the repository, then
run the example in this directory with the password in the environment:

```
./mvnw -B -ntp install -DskipTests
cd examples/config-and-jdbc
SALES_PASSWORD=example-only ../../mvnw -q compile exec:java
```

In PowerShell, set the variable with `$env:SALES_PASSWORD = "example-only"` first. The
database keeps the password of its first run; delete `target/db` to change it. Without
installing, the build of FyrQL builds and tests the example from the root, with the
password from the `pom.xml`: `./mvnw -Pexamples -pl examples/config-and-jdbc -am verify`.

## Output

```text
== Tables
sales.main.orders
files.main.customers
== Region north
name VARCHAR
orders BIGINT
revenue DECIMAL(38,2)
Cedar Inc 3 179.25
Alder & Co 2 81.50
== Region south
Elm Partners 1 298.00
Birch Ltd 2 238.00
```

A test of the example checks that it prints exactly this.

## What to look at

- **The sources are catalogs** in the `DatabaseMetaData`, and the default schema of a
  database is `main`: `sales.main.orders` is the table `public.orders` of H2.
- **The parameter** goes into the text as a literal of its value before the text is
  parsed, so that the database sees the filters that a parameter makes.
- **The columns** come from `statement.getMetaData()` before the parameter has a value,
  as the engine prepares the query without running it.
- **The types** are those of FyrQL: `getColumnTypeName` gives `DECIMAL(38,2)` for the
  sum of a `DECIMAL(10,2)`, and `getBigDecimal` reads it.
- **Each connection** builds an engine of its own and closes it with the connection. An
  application with an engine of its own uses `FyrQLDataSource` instead.

## DBeaver

The same file works in DBeaver, by hand:

1. Build the JAR with the driver, the engine and all connectors from the root of the
   repository: `./mvnw -B -ntp clean package -pl fyrql-jdbc-all -am -DskipTests`. It is
   `fyrql-jdbc-all/target/fyrql-jdbc-all-0.13.7.jar`.
2. Run the example once, so that the database exists.
3. In DBeaver, create a driver in the Driver Manager with the class name
   `net.fyrql.jdbc.FyrQLDriver`, the URL template `jdbc:fyrql:?config={file}`, no user and
   no password, and as libraries the JAR and the JAR of H2 (from the local Maven
   repository, `com/h2database/h2/2.5.252`).
4. DBeaver runs in a working directory of its own, so give the URL of H2 in `fyrql.yaml`
   an absolute path, such as
   `jdbc:h2:/home/me/fyrql/examples/config-and-jdbc/target/db/sales;DATABASE_TO_LOWER=TRUE`,
   and start DBeaver with `SALES_PASSWORD` in its environment.
5. Create a connection with that driver and the absolute path of `fyrql.yaml` as the
   file. The navigator shows the sources `sales` and `files`, and the query above runs
   in an SQL editor.

An H2 file is open in one process at a time: close DBeaver's connection before running
the example again. More in the manual: [DBeaver](../../docs/manual.md#dbeaver), the
[JDBC driver](../../docs/manual.md#jdbc-driver) and the
[configuration file](../../docs/manual.md#configuration-file).
