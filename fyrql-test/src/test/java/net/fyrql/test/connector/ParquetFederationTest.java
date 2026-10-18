/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Orders in a Parquet file of several row groups, sorted by their day,
 * joined with customers from a CSV file, products in a second Parquet file
 * with a {@code STRUCT} and a {@code LIST} column, and warehouses in memory
 * with a {@code STRUCT} column. DuckDB writes both Parquet files and reads
 * them with its {@code read_parquet}. Every query runs in an engine with
 * the pushdown of the run and in one without, which must agree, and in
 * DuckDB. With pushdown, a filter on the day skips row groups, a limit
 * reads only the row groups it needs, and only the fields of a struct in
 * use are read.
 */
class ParquetFederationTest {

    /** The rows of the orders, 55 a day from 2024-01-01 on, in row groups of 2048 rows. */
    private static final int ORDERS = 20_000;

    private static final Schema WAREHOUSES = Schema.of(new Schema.Field("sku", DataType.VARCHAR),
            new Schema.Field("site", DataType.VARCHAR), new Schema.Field("bin", DataType.struct(
                    new DataType.Struct.Field("aisle", DataType.INTEGER),
                    new DataType.Struct.Field("shelf", DataType.INTEGER))));

    private static final List<Object[]> WAREHOUSE_ROWS = List.of(new Object[] {"p1", "North", List.of(1, 2)},
            new Object[] {"p1", "South", List.of(4, 1)}, new Object[] {"p2", "North", List.of(2, 3)},
            new Object[] {"p3", "East", null}, new Object[] {"p5", "West", Arrays.asList(7, null)},
            new Object[] {null, "Depot", List.of(0, 0)});

    private static final List<String> QUERIES = List.of(
            "SELECT count(*) FROM orders",
            "SELECT * FROM orders WHERE day BETWEEN DATE '2024-03-01' AND DATE '2024-03-07'",
            "SELECT order_id, day, amount FROM orders ORDER BY amount DESC, order_id LIMIT 5",
            "SELECT count(*), sum(qty), max(day) FROM (SELECT * FROM orders LIMIT 3000) o",
            "SELECT c.name, c.city, count(*), sum(o.qty * p.price) FROM orders o JOIN customers c"
                    + " ON o.customer_id = c.id JOIN products p ON o.sku = p.sku WHERE o.day >= DATE '2024-12-01'"
                    + " GROUP BY c.name, c.city",
            "SELECT sku, dims.w, tags FROM products WHERE dims.h > 3",
            "SELECT o.order_id, o.qty * p.dims.w AS width, w.site, w.bin.aisle FROM orders o JOIN products p"
                    + " ON o.sku = p.sku LEFT JOIN warehouses w ON p.sku = w.sku WHERE o.order_id < 30"
                    + " ORDER BY o.order_id, w.site",
            "SELECT sku, unnest(tags) AS tag FROM products ORDER BY sku, tag",
            "SELECT w.site, count(*), min(o.day), sum(o.amount) FROM warehouses w JOIN orders o ON o.sku = w.sku"
                    + " WHERE o.day < DATE '2024-02-01' AND w.bin.shelf > 1 GROUP BY w.site",
            "SELECT c.city, count(o.order_id) FROM customers c LEFT JOIN orders o ON o.customer_id = c.id"
                    + " AND o.day = DATE '2024-06-15' GROUP BY c.city",
            "SELECT sku, len(tags), dims FROM products WHERE sku IN ('p1', 'p3', 'p5')",
            "SELECT p.name, count(*) FROM orders o JOIN products p ON o.sku = p.sku WHERE o.order_id % 1000 = 7"
                    + " AND o.customer_id IS NOT NULL GROUP BY p.name");

    @TempDir
    static Path dir;

    private static Path orders;
    private static Path products;
    private static Path customers;

    @BeforeAll
    static void writeFiles() throws IOException {
        orders = dir.resolve("orders.parquet");
        products = dir.resolve("products.parquet");
        customers = dir.resolve("customers.csv");
        Files.writeString(customers, "id,name,city\n1,Ann,Rome\n2,Bob,Oslo\n3,Cid,Rome\n4,Dee,\n5,Eve,Oslo\n"
                + "6,Fay,Pisa\n", StandardCharsets.UTF_8);
        try (DuckDbReference duck = new DuckDbReference()) {
            duck.execute("COPY (SELECT CAST(i AS INTEGER) AS order_id,"
                    + " CASE WHEN i % 97 = 0 THEN NULL ELSE CAST(1 + i % 5 AS BIGINT) END AS customer_id,"
                    + " CASE WHEN i % 89 = 0 THEN NULL ELSE 'p' || (1 + i * 7 % 6) END AS sku,"
                    + " CAST(1 + i * 13 % 20 AS INTEGER) AS qty,"
                    + " CAST(DATE '2024-01-01' + CAST(i // 55 AS INTEGER) AS DATE) AS day,"
                    + " CAST(i * 37 % 100000 / 100 AS DECIMAL(9,2)) AS amount"
                    + " FROM range(" + ORDERS + ") t(i) ORDER BY i) TO '" + path(orders)
                    + "' (FORMAT parquet, ROW_GROUP_SIZE 2048)");
            duck.execute("COPY (SELECT sku, name, CAST(price AS DECIMAL(9,2)) AS price,"
                    + " CAST(dims AS STRUCT(w INTEGER, h INTEGER, d INTEGER)) AS dims, CAST(tags AS VARCHAR[]) AS tags"
                    + " FROM (VALUES ('p1', 'Pen', 1.5, {'w': 1, 'h': 14, 'd': 1}, ['office', 'blue']),"
                    + " ('p2', 'Ink', 4, {'w': 5, 'h': 5, 'd': 5}, []), ('p3', 'Pad', 2.25, NULL, ['paper']),"
                    + " ('p4', NULL, NULL, {'w': 21, 'h': NULL, 'd': 3}, NULL),"
                    + " ('p5', 'Box', 12.0, {'w': 30, 'h': 20, 'd': 10}, ['storage', NULL, 'brown']))"
                    + " t(sku, name, price, dims, tags)) TO '" + path(products) + "' (FORMAT parquet)");
        }
    }

    @Test
    void ordersAndProductsFromParquetJoinCsvAndMemoryAsInDuckDb() {
        try (FyrQL engine = engine(null); FyrQL off = engine(false); DuckDbReference duck = new DuckDbReference()) {
            duck.execute("CREATE VIEW orders AS SELECT * FROM read_parquet('" + path(orders) + "')");
            duck.execute("CREATE VIEW products AS SELECT * FROM read_parquet('" + path(products) + "')");
            duck.execute("CREATE VIEW customers AS SELECT * FROM read_csv('" + path(customers) + "')");
            duck.load("warehouses", WAREHOUSES, List.of(Batch.ofRows(WAREHOUSES, WAREHOUSE_ROWS)));
            List<String> failures = new ArrayList<>();
            for (String sql : QUERIES) {
                try {
                    PlanNode plan = engine.plan(sql);
                    Schema schema = plan.schema();
                    boolean ordered = Plans.ordered(plan);
                    List<Object[]> actual = engine.rows(plan);
                    ResultComparison.Outcome modes = ResultComparison.compare(schema, off.rows(plan), actual, ordered);
                    if (!modes.same()) {
                        failures.add(sql + "\n  with and without pushdown differ:\n" + modes.report());
                        continue;
                    }
                    ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema), actual,
                            ordered);
                    if (!oracle.same()) {
                        failures.add(sql + "\n" + oracle.report() + "\n" + engine.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
            if (!failures.isEmpty()) {
                fail(failures.size() + " of " + QUERIES.size() + " queries differ:\n\n"
                        + String.join("\n\n", failures));
            }
        }
    }

    /**
     * With pushdown, the filter on the day reads only the row group of its
     * week, the limit delivers just its rows, and the products read only
     * the fields of {@code dims} in use; without pushdown, every row group
     * and every field is read, and the engine stops reading after the
     * limit within the second row group. The engine runs on one thread,
     * whose scans read no split ahead.
     */
    @Test
    void withPushdownTheOrdersSkipRowGroupsAndTheProductsReadOnlyTheFieldsInUse() {
        try (FyrQL engine = builder(null).threads(1).build()) {
            int all = reads(engine, "SELECT * FROM orders").size();
            assertEquals(10, all, "the row groups of the orders");
            int week = reads(engine, QUERIES.get(1)).size();
            List<ParquetConnector.Read> limit = reads(engine, QUERIES.get(3));
            long delivered = limit.stream().mapToLong(ParquetConnector.Read::rows).sum();
            assertEquals(2, limit.size(), "row groups read for the limit");
            List<String> leaves = reads(engine, QUERIES.get(5)).stream().flatMap(r -> r.columns().stream())
                    .distinct().toList();
            assertTrue(leaves.contains("dims.w") && leaves.contains("dims.h"), leaves.toString());
            if (engine.pushdown()) {
                assertEquals(1, week, "row groups read for a week");
                assertEquals(3000, delivered);
                assertFalse(leaves.contains("dims.d"), leaves.toString());
            } else {
                assertEquals(all, week);
                assertTrue(delivered > 3000 && delivered <= 4096, "rows delivered: " + delivered);
                assertTrue(leaves.contains("dims.d"), leaves.toString());
            }
        }
    }

    /** Runs a query and returns the row groups the Parquet source read for it. */
    private static List<ParquetConnector.Read> reads(FyrQL engine, String sql) {
        ParquetConnector parquet = (ParquetConnector) engine.connector("parquet");
        parquet.clearReads();
        engine.rows(sql);
        return parquet.reads();
    }

    private static FyrQL engine(Boolean pushdown) {
        return builder(pushdown).build();
    }

    private static FyrQL.Builder builder(Boolean pushdown) {
        FyrQL.Builder b = FyrQL.builder()
                .register(ParquetConnector.builder("parquet").path("orders", orders).path("products", products)
                        .recordReads(true).build())
                .register(CsvConnector.builder("files").file("customers", customers).build())
                .register(MemoryConnector.builder("memory").table("warehouses", WAREHOUSES, WAREHOUSE_ROWS).build());
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        return b;
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
