/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.test;

import com.sun.net.httpserver.HttpServer;
import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.http.HttpConnector;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The acceptance tests of the milestones. The first: a CSV file joined
 * with a list of records, aggregated and sorted, gives the same result as
 * DuckDB, with the reference query rendered from the plan. The third: a
 * query over orders in PostgreSQL, customers in a CSV file and products as
 * JSON from an HTTP endpoint gives the rows of DuckDB with pushdown and
 * without, and PostgreSQL runs the filter and the projection.
 */
class AcceptanceTest {

    record Order(long id, Integer customerId, BigDecimal amount, LocalDate created) {
    }

    @TempDir
    Path dir;

    @Test
    void csvJoinedWithRecordsAndAggregated() throws IOException {
        StringBuilder csv = new StringBuilder("id,region,name\n");
        String[] regions = {"north", "south", "east", "west", ""};
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 1; i <= 200; i++) {
            csv.append(i).append(',').append(regions[random.nextInt(regions.length)]).append(",\"customer ")
                    .append(i).append("\"\n");
        }
        Files.writeString(dir.resolve("customers.csv"), csv.toString(), StandardCharsets.UTF_8);
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Integer customer = random.nextInt(20) == 0 ? null : random.nextInt(1, 230);
            BigDecimal amount = random.nextInt(10) == 0 ? null
                    : BigDecimal.valueOf(random.nextLong(-10_000, 1_000_000), 2);
            orders.add(new Order(i, customer, amount, LocalDate.of(2026, 1, 1).plusDays(random.nextInt(300))));
        }
        FyrQL engine = FyrQL.builder()
                .register(CsvConnector.builder("csv").directory(dir).build())
                .register(MemoryConnector.builder("mem").table("orders", Order.class, orders,
                        Map.of("amount", DataType.decimal(10, 2))).build())
                .batchSize(64)
                .build();
        try (Differential differential = new Differential(engine)) {
            PlanNode.Scan o = engine.scan(TableRef.of("mem", "orders"));
            PlanNode.Scan c = engine.scan(TableRef.of("csv", "customers"));
            assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.VARCHAR), c.schema().types());
            Schema oSchema = o.schema();
            Schema cSchema = c.schema();
            // orders.customerId (INTEGER) = customers.id (BIGINT): cast the order side to BIGINT first.
            PlanNode ordersWithKey = new PlanNode.Project(o, List.of(
                    new PlanNode.NamedExpression("customer", net.fyrql.core.expr.Expressions.cast(
                            net.fyrql.core.expr.Expressions.col(oSchema, 1), DataType.BIGINT)),
                    new PlanNode.NamedExpression("amount", net.fyrql.core.expr.Expressions.col(oSchema, 2))));
            PlanNode join = new PlanNode.Join(ordersWithKey, c, List.of(0), List.of(0), JoinType.INNER);
            PlanNode aggregate = new PlanNode.Aggregate(join, List.of(3), List.of(
                    new AggregateCall(AggregateFunction.SUM, 1, false, "revenue"),
                    new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "orders"),
                    new AggregateCall(AggregateFunction.COUNT, 0, true, "customers"),
                    new AggregateCall(AggregateFunction.MAX, 1, false, "largest")));
            PlanNode plan = new PlanNode.Sort(aggregate, List.of(SortKey.desc(1)));
            List<Object[]> rows = differential.check(plan);
            assertEquals(5, rows.size(), "four regions and the empty one");
            assertEquals(DataType.decimal(38, 2), plan.schema().type(1));
            assertTrue(cSchema.indexOf("region") == 1);

            // The CSV connector reads what DuckDB's own CSV reader reads.
            differential.duck().execute("CREATE TABLE duck_csv AS SELECT * FROM read_csv('"
                    + dir.resolve("customers.csv").toString().replace('\\', '/') + "', header = true, "
                    + "columns = {'id': 'BIGINT', 'region': 'VARCHAR', 'name': 'VARCHAR'})");
            List<Object[]> fromDuck = differential.duck().query("SELECT * FROM duck_csv", cSchema);
            ResultComparison.Outcome outcome = ResultComparison.compare(cSchema, fromDuck, engine.rows(c), false);
            assertTrue(outcome.same(), outcome.report());
        }
    }

    /** The orders of the third milestone, five columns of which the main query reads two. */
    private static final Schema PG_ORDERS = Schema.of(new Schema.Field("order_id", DataType.INTEGER, false),
            new Schema.Field("customer_id", DataType.BIGINT), new Schema.Field("sku", DataType.VARCHAR),
            new Schema.Field("qty", DataType.INTEGER), new Schema.Field("amount", DataType.decimal(10, 2)));

    private static final String PRODUCTS = """
            [{"sku": "p1", "name": "Pen", "price": 1.5, "tags": ["office", "blue"]},
             {"sku": "p2", "name": "Ink", "price": 4, "tags": []},
             {"sku": "p3", "name": "Pad", "price": 2.25, "tags": ["paper"]},
             {"sku": "p4", "name": null, "price": null, "tags": null},
             {"sku": "p5", "name": "Clip", "price": 0.1, "tags": ["office"]},
             {"sku": "p6", "name": "Tape", "price": 3, "tags": ["office", "paper"]}]""";

    /**
     * The main query: the derived table over the orders is what PostgreSQL
     * runs, a filter and two of the five columns; the joins with the
     * customers and the products, the grouping and the order stay with the
     * engine.
     */
    private static final String FEDERATED = "SELECT c.city, p.name, count(*) AS orders"
            + " FROM (SELECT customer_id, sku FROM orders WHERE customer_id > 10 AND sku <> 'p3') o"
            + " JOIN customers c ON o.customer_id = c.id JOIN products p ON o.sku = p.sku"
            + " GROUP BY c.city, p.name ORDER BY c.city, p.name";

    private static final List<String> FEDERATED_QUERIES = List.of(FEDERATED,
            "SELECT o.order_id, c.name, p.name, o.qty * p.price AS total"
                    + " FROM (SELECT * FROM orders WHERE qty >= 9) o LEFT JOIN customers c ON o.customer_id = c.id"
                    + " LEFT JOIN products p ON o.sku = p.sku ORDER BY o.order_id",
            "SELECT p.sku, p.tags, count(o.order_id), sum(o.amount) FROM products p"
                    + " LEFT JOIN orders o ON p.sku = o.sku GROUP BY p.sku, p.tags ORDER BY p.sku",
            "SELECT c.city, sum(o.amount), max(o.qty) FROM orders o JOIN customers c ON o.customer_id = c.id"
                    + " WHERE o.amount > 100 GROUP BY c.city");

    @Test
    void federatedQueryOverHttpJsonCsvAndPostgres() throws IOException {
        var pg = TestDatabases.postgres();
        assumeTrue(pg.isPresent(), "PostgreSQL is off");
        TestDatabase db = pg.get();
        SplittableRandom random = new SplittableRandom(3);
        List<Object[]> orders = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            Long customer = random.nextInt(25) == 0 ? null : (long) random.nextInt(1, 45);
            String sku = random.nextInt(30) == 0 ? null : "p" + random.nextInt(1, 9);
            BigDecimal amount = random.nextInt(15) == 0 ? null : BigDecimal.valueOf(random.nextLong(0, 50_000), 2);
            orders.add(new Object[] {i, customer, sku, random.nextInt(1, 11), amount});
        }
        db.load("orders", PG_ORDERS, orders);
        StringBuilder csv = new StringBuilder("id,name,city\n");
        String[] cities = {"Rome", "Oslo", "Lima", ""};
        for (int i = 1; i <= 40; i++) {
            csv.append(i).append(",customer ").append(i).append(',').append(cities[i % cities.length]).append('\n');
        }
        Path customers = dir.resolve("customers.csv");
        Files.writeString(customers, csv.toString(), StandardCharsets.UTF_8);
        Path products = dir.resolve("products.json");
        Files.writeString(products, PRODUCTS, StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/products", exchange -> {
            byte[] body = PRODUCTS.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/products";
        JdbcConnector primary = db.connector("pg");
        JdbcConnector plain = db.connector("pg");
        try (FyrQL engine = federated(primary, customers, url, null); FyrQL off = federated(plain, customers, url, false);
                DuckDbReference duck = new DuckDbReference()) {
            duck.load("orders", PG_ORDERS, List.of(Batch.ofRows(PG_ORDERS, orders)));
            duck.execute("CREATE VIEW customers AS SELECT * FROM read_csv('" + path(customers) + "')");
            duck.execute("CREATE VIEW products AS SELECT * FROM read_json('" + path(products) + "')");
            for (String sql : FEDERATED_QUERIES) {
                PlanNode plan = engine.plan(sql);
                Schema schema = plan.schema();
                boolean ordered = Plans.ordered(plan);
                List<Object[]> actual = engine.rows(plan);
                assertFalse(actual.isEmpty(), sql);
                ResultComparison.Outcome modes = ResultComparison.compare(schema, off.rows(plan), actual, ordered);
                assertTrue(modes.same(), () -> sql + "\nwith and without pushdown differ:\n" + modes.report());
                ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema), actual,
                        ordered);
                assertTrue(oracle.same(), () -> sql + "\n" + oracle.report() + "\n" + engine.explain(plan));
            }

            // PostgreSQL receives one statement with the two columns and, with pushdown, the filter.
            boolean on = engine.pushdown();
            assertEquals(on, engine.explain(FEDERATED).contains("[pushed: "), engine.explain(FEDERATED));
            primary.clearStatements();
            engine.rows(FEDERATED);
            assertEquals(1, primary.statements().size(), primary.statements().toString());
            String scan = "SELECT t.\"customer_id\", t.\"sku\" FROM \"public\".\"orders\" t";
            assertEquals(on ? scan + " WHERE ((t.\"customer_id\" > CAST(10 AS INTEGER)) AND (t.\"sku\" <> 'p3'))"
                    : scan, primary.statements().get(0));
            for (String s : plain.statements()) {
                assertFalse(s.contains(" WHERE "), s);
            }
        } finally {
            server.stop(0);
        }
    }

    private static FyrQL federated(JdbcConnector pg, Path customers, String url, Boolean pushdown) {
        FyrQL.Builder b = FyrQL.builder().register(pg)
                .register(CsvConnector.builder("files").file("customers", customers).build())
                .register(HttpConnector.builder("api").table("products", url).build());
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        return b.build();
    }

    private static String path(Path p) {
        return p.toString().replace('\\', '/');
    }
}
