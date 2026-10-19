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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.odata.FakeODataServer;
import net.fyrql.connector.odata.ODataConnector;
import net.fyrql.connector.odata.ODataSourceException;
import net.fyrql.connector.odata.ODataVersion;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Orders of a service of OData version 2 and products of one of version 4,
 * both of the fake server and read over several pages, joined with
 * customers from a CSV file and the stock of an H2 database. Every query
 * runs in an engine with the pushdown of the run and in one without,
 * which must agree, and in DuckDB, which holds the same rows. The source
 * trusts the service of the products with filters, so that the run with
 * pushdown hands filters, orders, limits and {@code count(*)} over.
 */
class ODataFederationTest {

    private static final Schema ORDERS = new Schema(List.of(new Schema.Field("OrderID", DataType.INTEGER, false),
            new Schema.Field("CustomerID", DataType.VARCHAR, true),
            new Schema.Field("ProductID", DataType.INTEGER, true),
            new Schema.Field("Quantity", DataType.INTEGER, true),
            new Schema.Field("Discount", DataType.decimal(4, 2), true),
            new Schema.Field("OrderedAt", DataType.TIMESTAMP, true),
            new Schema.Field("Shipped", DataType.BOOLEAN, true)));

    private static final DataType DIMS = DataType.struct(new DataType.Struct.Field("w", DataType.INTEGER),
            new DataType.Struct.Field("h", DataType.INTEGER));

    private static final Schema PRODUCTS = new Schema(List.of(new Schema.Field("ProductID", DataType.INTEGER, false),
            new Schema.Field("Name", DataType.VARCHAR, true), new Schema.Field("Category", DataType.VARCHAR, true),
            new Schema.Field("Price", DataType.decimal(9, 2), true),
            new Schema.Field("Released", DataType.DATE, true), new Schema.Field("Dims", DIMS, true),
            new Schema.Field("Tags", DataType.listOf(DataType.VARCHAR), true),
            new Schema.Field("Updated", DataType.TIMESTAMP, true)));

    private static final Schema STOCK = new Schema(List.of(new Schema.Field("product_id", DataType.INTEGER, true),
            new Schema.Field("warehouse", DataType.VARCHAR, true), new Schema.Field("qty", DataType.INTEGER, true)));

    private static final String CUSTOMERS = """
            id,name,city,country
            ALFKI,Alfreds,Berlin,Germany
            ANATR,Ana Trujillo,Mexico City,Mexico
            BONAP,Bon app,Marseille,France
            FRANK,Frankenversand,Munich,Germany
            OCEAN,Oceano,Buenos Aires,Argentina
            WOLZA,Wolski,,Poland
            """;

    private static final List<String> QUERIES = List.of(
            "SELECT * FROM Orders",
            "SELECT * FROM Products",
            "SELECT OrderID, CustomerID, Discount FROM Orders WHERE Quantity > 10 AND Shipped ORDER BY OrderID",
            "SELECT Name, Price, Released FROM Products WHERE Price BETWEEN 5 AND 30"
                    + " AND Released >= DATE '2023-01-01' ORDER BY Price DESC, ProductID",
            "SELECT p.Name, p.Dims.w, p.Tags FROM Products p WHERE p.Category = 'Tools' AND p.Dims.h > 2",
            "SELECT count(*) FROM Orders WHERE OrderedAt >= TIMESTAMP '2024-03-01 00:00:00'",
            "SELECT count(*) FROM Products WHERE Price < 20",
            "SELECT OrderID, OrderedAt FROM Orders ORDER BY OrderedAt DESC LIMIT 5",
            "SELECT OrderID FROM Orders WHERE CustomerID = 'ALFKI' ORDER BY OrderID LIMIT 3 OFFSET 2",
            "SELECT ProductID, Name FROM Products WHERE Updated < TIMESTAMP '2024-06-01 12:00:00.5'"
                    + " ORDER BY ProductID LIMIT 4",
            "SELECT o.OrderID, c.name, p.Name, o.Quantity * p.Price AS amount FROM Orders o"
                    + " JOIN customers c ON o.CustomerID = c.id JOIN Products p ON o.ProductID = p.ProductID"
                    + " WHERE c.country = 'Germany' ORDER BY o.OrderID",
            "SELECT c.country, count(*), sum(o.Quantity), max(o.OrderedAt) FROM Orders o"
                    + " JOIN customers c ON o.CustomerID = c.id GROUP BY c.country",
            "SELECT p.Name, sum(s.qty) FROM Products p JOIN stock s ON p.ProductID = s.product_id"
                    + " WHERE s.warehouse = 'north' GROUP BY p.Name",
            "SELECT p.ProductID FROM Products p LEFT JOIN Orders o ON p.ProductID = o.ProductID"
                    + " WHERE o.OrderID IS NULL",
            "SELECT Name FROM Products WHERE ProductID IN (SELECT ProductID FROM Orders WHERE Discount > 0.1)"
                    + " ORDER BY Name",
            "SELECT c.name, count(o.OrderID), sum(s.qty) FROM customers c LEFT JOIN Orders o ON o.CustomerID = c.id"
                    + " LEFT JOIN stock s ON s.product_id = o.ProductID AND s.warehouse = 'south'"
                    + " GROUP BY c.name ORDER BY c.name");

    @TempDir
    Path dir;

    @Test
    void ordersAndProductsFromODataJoinCsvAndH2AsInDuckDb() throws IOException {
        List<Object[]> orders = orders();
        List<Object[]> products = products();
        List<Object[]> stock = stock();
        Path customers = dir.resolve("customers.csv");
        Files.writeString(customers, CUSTOMERS, StandardCharsets.UTF_8);
        TestDatabase h2 = TestDatabases.h2();
        h2.load("stock", STOCK, stock);
        try (FakeODataServer server = FakeODataServer.start(); DuckDbReference duck = new DuckDbReference()) {
            server.service("sales", ODataVersion.V2).pageSize(7).relativeLinks(true)
                    .entitySet("Orders", ORDERS, orders, "OrderID");
            server.service("catalog", ODataVersion.V4).entitySet("Products", PRODUCTS, products, "ProductID");
            duck.load("Orders", ORDERS, List.of(Batch.ofRows(ORDERS, orders)));
            duck.load("Products", PRODUCTS, List.of(Batch.ofRows(PRODUCTS, products)));
            duck.load("stock", STOCK, List.of(Batch.ofRows(STOCK, stock)));
            duck.execute("CREATE VIEW customers AS SELECT * FROM read_csv('" + path(customers) + "')");
            boolean pushdown;
            int pushed = 0;
            List<String> failures = new ArrayList<>();
            try (FyrQL engine = engine(server, customers, h2, null);
                    FyrQL off = engine(server, customers, h2, false)) {
                pushdown = engine.pushdown();
                for (String sql : QUERIES) {
                    try {
                        PlanNode plan = engine.plan(sql);
                        if (engine.explain(plan).contains("[pushed:")) {
                            pushed++;
                        }
                        Schema schema = plan.schema();
                        boolean ordered = Plans.ordered(plan);
                        List<Object[]> actual = engine.rows(plan);
                        ResultComparison.Outcome modes = ResultComparison.compare(schema, off.rows(plan), actual,
                                ordered);
                        if (!modes.same()) {
                            failures.add(sql + "\n  with and without pushdown differ:\n" + modes.report());
                            continue;
                        }
                        ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema),
                                actual, ordered);
                        if (!oracle.same()) {
                            failures.add(sql + "\n" + oracle.report() + "\n" + engine.explain(plan));
                        }
                    } catch (RuntimeException e) {
                        failures.add(sql + "\n  fails: " + e);
                    }
                }
            }
            if (!failures.isEmpty()) {
                fail(failures.size() + " of " + QUERIES.size() + " queries differ:\n\n"
                        + String.join("\n\n", failures));
            }
            List<FakeODataServer.Request> requests = server.requests();
            assertTrue(requests.stream().anyMatch(r -> r.path().equals("/sales/Orders")
                    && r.parameters().containsKey("$skiptoken")), "the orders are read over several pages");
            assertTrue(requests.stream().anyMatch(r -> r.path().equals("/catalog/Products")
                    && r.parameters().containsKey("$skiptoken")), "the products are read over several pages");
            long filtered = requests.stream().filter(r -> r.parameters().containsKey("$filter")).count();
            long limited = requests.stream().filter(r -> r.parameters().containsKey("$top")).count();
            // The catalog counts the rows of each entity set as well, without a filter.
            long counted = requests.stream().filter(r -> r.path().endsWith("/$count")
                    && r.parameters().containsKey("$filter")).count();
            if (pushdown) {
                assertTrue(pushed >= 8, "queries with pushdown: " + pushed);
                assertTrue(filtered >= 10, "requests with $filter: " + filtered);
                assertTrue(limited >= 2, "requests with $top: " + limited);
                assertTrue(counted >= 1, "requests of $count with $filter: " + counted);
            } else {
                assertEquals(0, pushed);
                assertEquals(0, filtered);
                assertEquals(0, limited);
            }
        }
    }

    @Test
    void aServiceThatFailsIsAnErrorWithItsMessage() {
        try (FakeODataServer server = FakeODataServer.start()) {
            server.service("sales", ODataVersion.V2).failing(500).entitySet("Orders", ORDERS, orders(), "OrderID");
            try (FyrQL engine = FyrQL.builder().register(ODataConnector.builder("erp")
                    .service("sales", server.url("sales")).build()).build()) {
                RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("SELECT * FROM Orders"));
                ODataSourceException cause = null;
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof ODataSourceException o) {
                        cause = o;
                    }
                }
                assertTrue(cause != null, () -> "caused by an ODataSourceException: " + e);
                assertEquals(500, cause.status());
                assertTrue(cause.getMessage().contains("The service sales of the OData source erp answered the query"
                        + " of the entity set Orders at " + server.url("sales") + "Orders with the status 500:"),
                        cause.getMessage());
                assertTrue(cause.getMessage().contains("The query of Orders failed"), cause.getMessage());
            }
        }
    }

    private static FyrQL engine(FakeODataServer server, Path customers, TestDatabase h2, Boolean pushdown) {
        ODataConnector erp = ODataConnector.builder("erp").service("sales", server.url("sales"))
                .service("catalog", server.url("catalog")).trustFilters("catalog", true).pageSize(5).batchSize(4)
                .rowCounts(true).build();
        FyrQL.Builder b = FyrQL.builder().register(erp)
                .register(CsvConnector.builder("files").file("customers", customers).build())
                .register(h2.connector("h2"));
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        return b.build();
    }

    /** Orders of the customers, some of products that are not there and some without a customer. */
    private static List<Object[]> orders() {
        String[] customers = {"ALFKI", "ANATR", "BONAP", "FRANK", "ALFKI", "XXXXX", null};
        Random random = new Random(20261018L);
        long start = Conversions.parseTimestamp("2024-01-01 08:00:00");
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            Integer product = i % 13 == 0 ? Integer.valueOf(99) : i % 11 == 0 ? null : 1 + random.nextInt(9);
            BigDecimal discount = i % 6 == 0 ? null : BigDecimal.valueOf(random.nextInt(26), 2);
            // Distinct milliseconds, so that an order by them has no ties.
            Long at = i % 9 == 0 ? null : start + i * 3_600_000_000L * 47 + random.nextInt(1000) * 1000L;
            Boolean shipped = i % 7 == 0 ? null : random.nextBoolean();
            rows.add(new Object[] {i, customers[random.nextInt(customers.length)], product,
                    i % 10 == 0 ? null : 1 + random.nextInt(25), discount, at, shipped});
        }
        return rows;
    }

    /** Products 1 to 10, of which 10 is never ordered. */
    private static List<Object[]> products() {
        String[] names = {"Saw", "Hammer", "Screws", "Tape", "Glue", "Drill", "Level", "Sander", "Nails", "Clamp"};
        String[] categories = {"Tools", "Tools", "Parts", "Supplies", "Supplies", "Tools", "Tools", "Tools", "Parts",
            null};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            BigDecimal price = i == 4 ? null : new BigDecimal(i * 3.75).setScale(2, java.math.RoundingMode.HALF_UP);
            Integer released = i == 5 ? null : Conversions.parseDate("2022-06-01") + i * 61;
            List<Object> dims = i == 3 ? null : Arrays.asList(i, i % 4 == 0 ? null : i * 2 % 7);
            List<String> tags = i == 2 ? null : i % 3 == 0 ? List.of() : List.of("t" + i, "all");
            Long updated = Conversions.parseTimestamp("2024-06-01 12:00:00") + (i - 5) * 250_000L + i;
            rows.add(new Object[] {i, names[i - 1], categories[i - 1], price, released, dims, tags, updated});
        }
        return rows;
    }

    private static List<Object[]> stock() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            rows.add(new Object[] {i, "north", i * 10});
            if (i % 2 == 0) {
                rows.add(new Object[] {i, "south", i});
            }
        }
        rows.add(new Object[] {null, "north", 5});
        rows.add(new Object[] {3, null, 7});
        return rows;
    }

    private static String path(Path p) {
        return p.toString().replace('\\', '/');
    }
}
