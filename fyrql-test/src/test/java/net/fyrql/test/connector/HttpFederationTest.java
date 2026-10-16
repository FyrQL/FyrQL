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

import com.sun.net.httpserver.HttpServer;
import net.fyrql.FyrQL;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.http.HttpConnector;
import net.fyrql.connector.http.HttpSourceException;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Products as JSON that an HTTP endpoint answers, joined with customers
 * from a CSV file and orders in memory. Every query runs in an engine with
 * the pushdown of the run and in one without, which must agree, and in
 * DuckDB, which reads the same products from a file with its
 * {@code read_json}. The orders take over filters, so that the pushdown
 * of the run shows.
 */
class HttpFederationTest {

    /** The products, with nested values; the endpoint wraps them in a page. */
    private static final String PRODUCTS = """
            [{"sku": "p1", "name": "Pen", "price": 1.5, "stock": 10, "dims": {"w": 1, "h": 14},
              "tags": ["office", "blue"], "added": "2024-01-02"},
             {"sku": "p2", "name": "Ink", "price": 4, "stock": 3, "dims": {"w": 5, "h": 5},
              "tags": [], "added": "2024-03-04"},
             {"sku": "p3", "name": "Pad", "price": 2.25, "stock": 0, "dims": null,
              "tags": ["paper"], "added": null},
             {"sku": "p4", "name": null, "price": null, "stock": 7, "dims": {"w": 21, "h": null},
              "tags": null, "added": "2023-12-31"}]""";

    private static final Schema ORDERS = Schema.of(new Schema.Field("order_id", DataType.INTEGER),
            new Schema.Field("customer_id", DataType.BIGINT), new Schema.Field("sku", DataType.VARCHAR),
            new Schema.Field("qty", DataType.INTEGER));

    private static final List<Object[]> ORDER_ROWS = List.of(new Object[] {1, 1L, "p1", 3},
            new Object[] {2, 1L, "p2", 1}, new Object[] {3, 2L, "p1", 10}, new Object[] {4, 3L, "p3", 2},
            new Object[] {5, 2L, "p9", 1}, new Object[] {6, null, "p2", 4}, new Object[] {7, 4L, null, 5});

    private static final List<String> QUERIES = List.of(
            "SELECT * FROM products",
            "SELECT sku, price FROM products WHERE price > 2 ORDER BY sku",
            "SELECT sku, dims, tags FROM products WHERE stock < 8",
            "SELECT count(*), count(name), min(added), max(price), sum(stock) FROM products",
            "SELECT o.order_id, c.name, p.name, o.qty * p.price AS amount FROM orders o"
                    + " JOIN customers c ON o.customer_id = c.id JOIN products p ON o.sku = p.sku ORDER BY o.order_id",
            "SELECT c.city, count(*), sum(o.qty), max(p.added) FROM (SELECT * FROM orders WHERE qty > 1) o"
                    + " JOIN customers c ON o.customer_id = c.id JOIN products p ON o.sku = p.sku GROUP BY c.city",
            "SELECT p.sku FROM products p LEFT JOIN orders o ON p.sku = o.sku WHERE o.order_id IS NULL",
            "SELECT o.order_id, p.tags FROM (SELECT order_id, sku FROM orders WHERE customer_id IN (1, 2)) o"
                    + " LEFT JOIN products p ON o.sku = p.sku ORDER BY o.order_id",
            "SELECT c.name, p.dims FROM customers c, products p WHERE c.id = p.stock - 6 ORDER BY c.name");

    @TempDir
    Path dir;

    @Test
    void productsFromHttpJoinCsvAndMemoryAsInDuckDb() throws IOException {
        AtomicInteger gets = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/products", exchange -> {
            gets.incrementAndGet();
            byte[] body = ("{\"total\": 4, \"data\": " + PRODUCTS + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        Path products = dir.resolve("products.json");
        Files.writeString(products, PRODUCTS, StandardCharsets.UTF_8);
        Path customers = dir.resolve("customers.csv");
        Files.writeString(customers, "id,name,city\n1,Ann,Rome\n2,Bob,Oslo\n3,Cid,Rome\n4,Dee,\n",
                StandardCharsets.UTF_8);
        String url = "http://localhost:" + server.getAddress().getPort() + "/v1/products";
        try (FyrQL engine = engine(url, customers, null); FyrQL off = engine(url, customers, false);
                DuckDbReference duck = new DuckDbReference()) {
            duck.execute("CREATE VIEW products AS SELECT * FROM read_json('" + path(products) + "')");
            duck.execute("CREATE VIEW customers AS SELECT * FROM read_csv('" + path(customers) + "')");
            duck.load("orders", ORDERS, List.of(Batch.ofRows(ORDERS, ORDER_ROWS)));
            List<String> failures = new ArrayList<>();
            int pushed = 0;
            for (String sql : QUERIES) {
                try {
                    PlanNode plan = engine.plan(sql);
                    if (engine.explain(plan).contains("[pushed:")) {
                        pushed++;
                    }
                    Schema schema = plan.schema();
                    boolean ordered = Plans.ordered(plan);
                    int before = gets.get();
                    List<Object[]> actual = engine.rows(plan);
                    assertTrue(gets.get() > before, "every query reads the products anew: " + sql);
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
            if (engine.pushdown()) {
                assertTrue(pushed >= 2, "the orders take over filters in " + pushed + " queries");
            } else {
                assertEquals(0, pushed);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anEndpointThatFailsIsAnErrorWithItsUrlAndStatus() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort() + "/v1/missing";
        try (FyrQL engine = FyrQL.builder().register(HttpConnector.builder("api").table("missing", url).build())
                .build()) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("SELECT * FROM missing"));
            HttpSourceException cause = null;
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof HttpSourceException h) {
                    cause = h;
                }
            }
            assertTrue(cause != null, () -> "caused by an HttpSourceException: " + e);
            assertEquals(404, cause.status());
            assertTrue(cause.getMessage().startsWith("GET " + url + " answered with the status 404"),
                    cause.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static FyrQL engine(String url, Path customers, Boolean pushdown) {
        FyrQL.Builder b = FyrQL.builder()
                .register(HttpConnector.builder("api").table("products", url).pointer("products", "/data").build())
                .register(CsvConnector.builder("files").file("customers", customers).build())
                .register(MemoryConnector.builder("memory").pushdown(Capabilities.filtersAndLimit(e -> true))
                        .table("orders", ORDERS, ORDER_ROWS).build());
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        return b.build();
    }

    private static String path(Path p) {
        return p.toString().replace('\\', '/');
    }
}
