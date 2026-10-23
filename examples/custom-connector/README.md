# A connector of its own

A source of a kind that FyrQL does not know joins it as a connector, written against the
SPI in `fyrql-connector-spi`. This one computes its table instead of reading it: the
source of the type `sequence` has one table, `numbers`, with the numbers `n` from 1 to the
option `max`, their squares and whether they are even. It takes over the filters that
narrow the range of `n`, so that `WHERE n BETWEEN 10 AND 14` computes five rows, not all.

| File | What it is |
| --- | --- |
| [`SequenceConnectorFactory.java`](src/main/java/net/fyrql/examples/customconnector/SequenceConnectorFactory.java) | creates the source from its options in a configuration file |
| [`META-INF/services/net.fyrql.connector.ConnectorFactory`](src/main/resources/META-INF/services/net.fyrql.connector.ConnectorFactory) | names the factory, so that the engine finds the type `sequence` |
| [`SequenceConnector.java`](src/main/java/net/fyrql/examples/customconnector/SequenceConnector.java) | the catalog, what a scan takes over, the parts of a scan and the reader of a part |
| [`fyrql.yaml`](fyrql.yaml) | the configuration file with a source `seq` of the type `sequence` |
| [`CustomConnector.java`](src/main/java/net/fyrql/examples/customconnector/CustomConnector.java) | runs three queries and shows what the source took over and computed |

## How a scan works

The engine calls a connector in four steps:

1. **`catalog()`** tells the schemas, tables and columns, and, if known, the number of
   rows of a table, which the optimizer uses.
2. **`plan(table, request)`** answers a `ScanRequest`: the columns the query needs, the
   filters, and possibly a limit, an order or an aggregation. The connector takes over
   what it can and says so in the `ScanPlan`; the engine does the rest. Here,
   `Capabilities.filtersOnly(...)` applies the rules of the request, and the predicate
   decides each filter:

   ```java
   static Range narrow(Expression filter, int n, Range range) {
       return switch (filter) {
           case Comparison(ComparisonOp op, Column column, Literal literal) when column.index() == n ->
                   range.compare(op, wholeNumber(literal));
           case Comparison(ComparisonOp op, Literal literal, Column column) when column.index() == n ->
                   range.compare(flip(op), wholeNumber(literal));
           case Between(Column column, Literal low, Literal high) when column.index() == n ->
                   range.between(wholeNumber(low), wholeNumber(high));
           // Every other filter, also of a kind that a later version of FyrQL adds to Expression, stays with
           // the engine. Without this branch, a connector might take over what it does not understand.
           default -> null;
       };
   }
   ```

   The range of `n` that the accepted filters leave goes into the plan as its state.
3. **`splits(plan, threads)`** divides the scan into parts that can be read
   independently, here one part of the range per thread.
4. **`open(split)`** returns a `BatchReader`, which delivers the rows of a part in
   batches of columns, only of the columns the plan names.

## The rules of the SPI

- **Exact or not at all.** A connector takes over a filter only when it gives exactly
  the rows the engine would give. `n > 2.5` compares with a `DECIMAL`, and `n <> 5` is no
  range, so this one declines both, and the engine evaluates them.
- **Decline what you do not know.** `Expression` and `PlanNode` may get new kinds of
  nodes in a minor version, and the engine offers a connector filters of any kind. The
  `default` branch above declines them; see
  [Compatibility](../../docs/manual.md#compatibility).
- **Read the options with `ConfigOptions`** and call `rejectUnknown()` after the last,
  so that a misspelled option fails with a message that names it and the known ones:
  `The sequence source seq has an unknown option maks; known are max, batchSize`.
- **Positions, not names.** The filters refer to the columns of the request by their
  position, not to the columns of the table.

## Running it

FyrQL is not yet published, so install it once from the root of the repository, then
run the example in this directory:

```
./mvnw -B -ntp install -DskipTests
cd examples/custom-connector
../../mvnw -q compile exec:java
```

Without installing, the build of FyrQL builds and tests it from the root:
`./mvnw -Pexamples -pl examples/custom-connector -am verify`.

## Output

```text
== SELECT n, square FROM seq.numbers WHERE n BETWEEN 10 AND 14 ORDER BY n
Sort #0
  Project #0 AS n, #1 AS square [parallel]
    Scan seq.main.numbers (n BIGINT, square BIGINT) [pushed: WHERE (#0 BETWEEN CAST(10 AS BIGINT) AND CAST(14 AS BIGINT))]
[10, 100]
[11, 121]
[12, 144]
[13, 169]
[14, 196]
Computed 5 rows
== SELECT count(*) AS evens, sum(square) AS total FROM seq.numbers WHERE even AND 1000 >= n
Project #0 AS evens, #1 AS total
  Aggregate count(*) AS "count_star()", sum(#0) AS "sum(square)"
    Project #1 AS "#argument0" [parallel]
      Filter #2
        Scan seq.main.numbers (n BIGINT, square BIGINT, even BOOLEAN) [pushed: WHERE (CAST(1000 AS INTEGER) >= #0)]
[500, 167167000]
Computed 1000 rows
== SELECT n, square FROM seq.numbers WHERE n = 7 OR n = 99999 ORDER BY n
Sort #0
  Project #0 AS n, #1 AS square [parallel]
    Filter ((#0 = CAST(7 AS INTEGER)) OR (#0 = CAST(99999 AS INTEGER)))
      Scan seq.main.numbers (n BIGINT, square BIGINT)
[7, 49]
[99999, 9999800001]
Computed 100000 rows
```

A test of the example checks that it prints exactly this.

## What to look at

- **`[pushed: ...]`** shows the filters the source took over; a filter it declined stays
  as a `Filter` node above the scan, such as `even` in the second query and the `OR` in
  the third. The number of rows computed shows the effect: 5, 1,000 and 100,000.
- **`1000 >= n`** arrives with the constant on the left; the connector turns it around.
- **The source needs no code in the application:** the configuration file names its
  type, and the engine finds the factory with the `ServiceLoader`. An application can
  also register it in Java, with `FyrQL.builder().register(new SequenceConnector("seq",
  100000, 1024))`.

More in the manual: [Connectors](../../docs/manual.md#connectors) and
[Compatibility](../../docs/manual.md#compatibility).
