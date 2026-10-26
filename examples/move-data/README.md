# Moving data

FyrQL moves data between databases and files with SQL alone. This example takes the
orders of a database, here H2 in memory, through four statements
([`MoveData.java`](src/main/java/net/fyrql/examples/movedata/MoveData.java)):

1. `COPY ... TO` writes them into a Parquet file, compressed with zstd:

   ```sql
   COPY (SELECT id, customer_id, amount, order_date FROM shop.orders ORDER BY id)
   TO 'target/out/orders.parquet' (compression zstd)
   ```

2. A query reads the file by its name, without registering it, with the types it was
   written with (`amount` stays a `DECIMAL(10,2)`):

   ```sql
   SELECT count(*) AS orders, sum(amount) AS total,
          min(order_date) AS first, max(order_date) AS last
   FROM 'target/out/orders.parquet'
   ```

3. `CREATE TABLE ... AS` writes the large orders of the file back into a new table of
   the database:

   ```sql
   CREATE TABLE shop.big_orders AS
   SELECT id, customer_id, amount FROM 'target/out/orders.parquet' WHERE amount >= 100
   ```

4. `COPY ... FROM` loads the orders of 2025 from a CSV file,
   [`data/orders-2025.csv`](data/orders-2025.csv), into a table that exists, with the
   types of its columns:

   ```sql
   COPY shop.archive FROM 'data/orders-2025.csv'
   ```

Each statement runs with `engine.update(sql)`, which returns the number of rows written.
The engine has one source, the database; the files need none, since an engine with
`fyrql-connector-parquet` and `fyrql-connector-csv` on its class path reads and writes
their formats:

```java
try (FyrQL engine = FyrQL.builder()
        .register(JdbcConnector.builder("shop").url(URL).user("sa").password("").build())
        .threads(4)
        .build()) {
    long exported = engine.update("COPY (SELECT ...) TO 'target/out/orders.parquet' (compression zstd)");
    ...
}
```

## Running it

FyrQL is not yet published, so install it once from the root of the repository, then
run the example in this directory:

```
./mvnw -B -ntp install -DskipTests
cd examples/move-data
../../mvnw -q compile exec:java
```

Without installing, the build of FyrQL builds and tests it from the root:
`./mvnw -Pexamples -pl examples/move-data -am verify`.

## Output

```text
Exported 10 orders to target/out/orders.parquet
== The Parquet file
[10, 1048.25, 2026-01-12, 2026-03-09]
== Plan
Create table shop.main.big_orders (id INTEGER, customer_id INTEGER, amount DECIMAL(10,2))
  Project #0 AS id, #1 AS customer_id, #2 AS amount [parallel]
    Filter (#2 >= CAST(100 AS INTEGER))
      Scan read_parquet('target/out/orders.parquet') (id INTEGER, customer_id INTEGER, amount DECIMAL(10,2))
Created shop.big_orders with 3 rows
== shop.big_orders
[2, 2, 149.00]
[5, 4, 178.00]
[6, 5, 298.00]
Loaded 6 orders of 2025 into shop.archive
== Orders by year in shop.orders and shop.archive
[2025, 6, 911.65]
[2026, 10, 1048.25]
```

A test of the example checks that it prints exactly this.

## What to look at

- **The plan of `CREATE TABLE ... AS`** is a query with a line on top that names the new
  table and its columns, which take the names and types of the query. The file is a
  table of the source `parquet`, which the engine has because its module is on the class
  path. The Parquet connector takes over no filter, but uses it to skip the row groups in
  which no row can pass.
- **Each statement is a transaction of its own** in the source it writes to. One that
  fails leaves no file and no rows behind: a file is written under a temporary name and
  renamed with the last row, and the rows of a table are committed at the end.
- **`COPY ... FROM`** reads each column as the type of its column in the table, from the
  first row to the last, where a query over the CSV file would infer the types from a
  sample (`amount` would be a `DOUBLE`).
- **The formats:** `COPY ... TO` writes CSV, JSON and Parquet, chosen by the end of the
  name or the option `format`, with options such as `compression`, `header` or
  `row_group_size`.

More in the manual: [Writing and loading](../../docs/manual.md#writing-and-loading), with
the options of each format and the types a database gets.
