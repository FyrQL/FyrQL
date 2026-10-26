# Federated query

One SQL query over three sources of different kinds:

- the orders in a database, here H2 in memory, which the example fills over JDBC;
- the customers in a CSV file, [`data/customers.csv`](data/customers.csv);
- the products in a JSON file, [`data/products.json`](data/products.json).

Each source is a connector registered under a name, and a table is named by that name:
`shop.orders`, `files.customers` and `catalog.products`. The example prints the plan, the
rows, and the statements the database received
([`FederatedQuery.java`](src/main/java/net/fyrql/examples/federatedquery/FederatedQuery.java)):

```java
JdbcConnector shop = JdbcConnector.builder("shop").url(URL).user("sa").password("")
        .recordStatements(true).build();
try (FyrQL engine = FyrQL.builder()
        .register(shop)
        .register(CsvConnector.builder("files").directory(Path.of("data")).build())
        .register(JsonConnector.builder("catalog").directory(Path.of("data")).build())
        .threads(4)
        .build()) {
    System.out.print(engine.explain(QUERY));
    for (Object[] row : engine.rows(QUERY)) {
        System.out.println(Arrays.toString(row));
    }
    shop.statements().forEach(System.out::println);
}
```

```sql
SELECT c.region, p.name AS product, sum(o.quantity) AS units,
       sum(o.quantity * p.price) AS revenue
FROM shop.orders AS o
JOIN files.customers AS c ON o.customer_id = c.id
JOIN catalog.products AS p ON o.sku = p.sku
WHERE o.status = 'shipped' AND o.order_date >= DATE '2026-02-01'
GROUP BY c.region, p.name
ORDER BY c.region, product
```

## Running it

FyrQL is not yet published, so install it once from the root of the repository, then
run the example in this directory:

```
./mvnw -B -ntp install -DskipTests
cd examples/federated-query
../../mvnw -q compile exec:java
```

Without installing, the build of FyrQL builds and tests it from the root:
`./mvnw -Pexamples -pl examples/federated-query -am verify`.

## Output

```text
== Plan
Sort #0, #1
  Project #0 AS region, #1 AS product, #2 AS units, #3 AS revenue
    Aggregate GROUP BY #0, #1: sum(#2) AS "sum(o.quantity)", sum(#3) AS "sum((o.quantity * p.price))" [codegen]
      Project #1 AS "#group0", #2 AS "#group1", #0 AS "#argument0", (#0 * #3) AS "#argument1" [codegen] [parallel]
        Project #6 AS quantity, #1 AS region, #3 AS name, #4 AS price
          Join INNER ON l#0 = r#5 [codegen]
            Scan files.main.customers (id BIGINT, region VARCHAR)
            Join INNER ON l#0 = r#0 [codegen] [parallel]
              Scan catalog.main.products (sku VARCHAR, name VARCHAR, price DOUBLE)
              Project #1 AS sku, #2 AS quantity, CAST(#0 AS BIGINT) AS "#key0" [codegen] [parallel]
                Scan shop.main.orders (customer_id INTEGER, sku VARCHAR, quantity INTEGER, status VARCHAR, order_date DATE) [pushed: SELECT #0, #1, #2 WHERE ((#3 = 'shipped') AND (#4 >= DATE '2026-02-01'))]
== Rows
[east, Desk lamp, 3, 73.5]
[east, Notebook, 20, 65.0]
[north, Desk lamp, 5, 122.5]
[north, Notebook, 10, 32.5]
[south, Monitor arm, 2, 178.0]
[south, Office chair, 2, 298.0]
== Statements the database received
SELECT t."customer_id", t."sku", t."quantity" FROM "public"."orders" t WHERE ((t."status" = 'shipped') AND (t."order_date" >= DATE '2026-02-01'))
```

A test of the example checks that it prints exactly this.

## What to look at

- **`[pushed: ...]`** on the scan of the orders: the database took over the filter, and
  the last line shows the statement it received. Only the matching orders leave the
  database, and only with the columns `#0` to `#2` that the query needs: `status` and
  `order_date`, which only the filter reads, stay there.
- **The joins and the grouping** run in the engine, since the three tables are in three
  sources: the orders are joined with the four products first, then with the customers.
  Two tables of the same database would be joined in the database.
- **`[codegen]`** marks the nodes whose expressions and keys the engine compiles once
  enough rows pass, since `fyrql-codegen` is on the class path; **`[parallel]`** marks the
  top of a chain that runs on several threads when its source has several parts.
- **The types** come from each source: the database tells its own, and the CSV and JSON
  connectors infer them from a sample, as DuckDB does (`price` is a `DOUBLE`).

More in the manual: [Connectors](../../docs/manual.md#connectors) with what each source
takes over, the [Optimizer](../../docs/manual.md#optimizer), and
[Code generation](../../docs/manual.md#code-generation).
