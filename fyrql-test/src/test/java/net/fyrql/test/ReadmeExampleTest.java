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

import net.fyrql.FyrQL;
import net.fyrql.exec.CodegenMode;
import net.fyrql.QueryResult;
import net.fyrql.codegen.JaninoCompiler;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.spi.PipelineSpec;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.date;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;

/**
 * The examples of the README: the query, with the data it describes, whose
 * printed rows and plan are those of the README, the window functions and
 * the statements that write over the same data, the same query through the
 * JDBC driver, and the generated code of a filter and of the pipeline of
 * TPC-H Q6, which the README shows.
 */
class ReadmeExampleTest {

    record Order(long id, Integer customerId, BigDecimal amount) {
    }

    @TempDir
    Path data;

    @Test
    void theExampleRuns() throws IOException {
        Files.writeString(data.resolve("customers.csv"), "id,region\n1,north\n2,south\n3,north\n",
                StandardCharsets.UTF_8);
        List<Order> orders = List.of(new Order(1, 1, new BigDecimal("10.50")), new Order(2, 2, new BigDecimal("20.00")),
                new Order(3, 3, new BigDecimal("5.25")), new Order(4, 1, null), new Order(5, null, new BigDecimal("7.00")));
        List<String> printed = new ArrayList<>();
        String plan;
        String pipeline;
        String parallel;

        // From here on as in the README, with the directory of the test for "data".
        try (FyrQL engine = FyrQL.builder()
                .register(CsvConnector.builder("csv").directory(data).build())
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
                    printed.add(Arrays.toString(row));
                }
            }
            plan = engine.explain(sql);
            // The README shows the plan of AUTO: the tables are too small for a pipeline unless code is always
            // generated.
            pipeline = engine.codegen() == CodegenMode.ALWAYS ? " [codegen pipeline]" : "";
            // With more than one thread, the chains over the scans run as segments: the one of the orders up to the
            // projection above the join, and the customers the join builds on.
            parallel = engine.threads() > 1 ? " [parallel]" : "";

            assertEquals("[region VARCHAR, orders BIGINT, revenue DECIMAL(38,2)]",
                    engine.plan(sql).schema().fields().toString());
            SqlException e = assertThrows(SqlException.class, () -> engine.rows("SELECT amount FROM orders WHERE x"));
            assertEquals("Referenced column \"x\" not found in FROM clause! at line 1, column 33", e.getMessage());
        }

        assertEquals(List.of("[south, 1, 20.00]", "[north, 3, 15.75]"), printed);
        assertEquals("""
                Sort #2 DESC
                  Project #0 AS region, #1 AS orders, #2 AS revenue
                    Aggregate GROUP BY #0: count(*) AS "count_star()", sum(#1) AS "sum(o.amount)" [codegen]
                      Project #3 AS "#group0", #0 AS "#argument0"%s%s
                        Join INNER ON l#1 = r#0 [codegen]
                          Project #1 AS amount, CAST(#0 AS BIGINT) AS "#key0" [codegen]
                            Scan mem.main.orders (customerId INTEGER, amount DECIMAL(10,2))
                          Scan csv.main.customers (id BIGINT, region VARCHAR)%s
                """.formatted(pipeline, parallel, parallel), plan);
    }

    @Test
    void theWritingExampleRuns() throws IOException {
        Files.writeString(data.resolve("customers.csv"), "id,region\n1,north\n2,south\n3,north\n",
                StandardCharsets.UTF_8);
        List<Order> orders = List.of(new Order(1, 1, new BigDecimal("10.50")), new Order(2, 2, new BigDecimal("20.00")),
                new Order(3, 3, new BigDecimal("5.25")), new Order(4, 1, null), new Order(5, null, new BigDecimal("7.00")));
        String file = data.resolve("orders-by-region.csv").toString().replace('\\', '/');
        String printed;

        try (FyrQL engine = FyrQL.builder()
                .register(CsvConnector.builder("csv").directory(data).build())
                .register(MemoryConnector.builder("mem")
                        .table("orders", Order.class, orders, Map.of("amount", DataType.decimal(10, 2)))
                        .build())
                .build()) {
            // From here on as in the README, with the directory of the test for "data".
            long written = engine.update("""
                    COPY (SELECT o.id, c.region, o.amount
                          FROM orders AS o JOIN customers AS c ON o.customerId = c.id)
                    TO '%s'""".formatted(file));
            engine.update("CREATE TABLE mem.regions (region VARCHAR NOT NULL, revenue DECIMAL(12,2))");
            engine.update("INSERT INTO mem.regions SELECT region, sum(amount) FROM '" + file + "' GROUP BY ALL");
            printed = written + " " + engine.rows("SELECT * FROM regions ORDER BY region").stream()
                    .map(Arrays::toString).toList();
            // The example of loading, with the same file.
            engine.update("CREATE TABLE mem.archive (id BIGINT NOT NULL, region VARCHAR, amount DECIMAL(10,2))");
            long loaded = engine.update("COPY mem.archive FROM '" + file + "'");
            assertEquals(4, loaded);
            assertEquals("[[1, north, 10.50], [2, south, 20.00], [3, north, 5.25], [4, north, null]]", engine.rows(
                    "SELECT * FROM mem.archive ORDER BY id").stream().map(Arrays::toString).toList().toString());
            assertTrue(engine.explain("COPY mem.archive FROM '" + file + "'").startsWith("Copy from '" + file
                    + "' into mem.main.archive [format: csv]\n"));

            assertTrue(engine.explain("INSERT INTO mem.regions SELECT region, 0 FROM customers")
                    .startsWith("Insert into mem.main.regions\n"));
            assertTrue(engine.explain("COPY regions TO '" + file + "'").startsWith("Copy to '" + file
                    + "' [format: csv]\n"));
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> engine.update("INSERT INTO mem.regions VALUES (NULL, 1)"));
            assertTrue(e.getMessage().startsWith("NOT NULL constraint failed: regions.region"), e.getMessage());
        }
        assertEquals("4 [[north, 15.75], [south, 20.00]]", printed);
    }

    @Test
    void theWindowExampleRuns() throws IOException {
        Files.writeString(data.resolve("customers.csv"), "id,region\n1,north\n2,south\n3,north\n",
                StandardCharsets.UTF_8);
        List<Order> orders = List.of(new Order(1, 1, new BigDecimal("10.50")), new Order(2, 2, new BigDecimal("20.00")),
                new Order(3, 3, new BigDecimal("5.25")), new Order(4, 1, null), new Order(5, null, new BigDecimal("7.00")));
        List<String> printed = new ArrayList<>();
        String plan;
        String pipeline;
        String parallel;

        try (FyrQL engine = FyrQL.builder()
                .register(CsvConnector.builder("csv").directory(data).build())
                .register(MemoryConnector.builder("mem")
                        .table("orders", Order.class, orders, Map.of("amount", DataType.decimal(10, 2)))
                        .build())
                .build()) {
            // From here on as in the README.
            String sql = """
                    SELECT c.region, o.id, o.amount,
                           rank() OVER (PARTITION BY c.region ORDER BY o.amount DESC) AS place,
                           sum(o.amount) OVER (PARTITION BY c.region) AS total
                    FROM orders AS o JOIN customers AS c ON o.customerId = c.id
                    ORDER BY c.region, place""";
            for (Object[] row : engine.rows(sql)) {
                printed.add(Arrays.toString(row));
            }
            String largest = """
                    SELECT c.region, o.id
                    FROM orders AS o JOIN customers AS c ON o.customerId = c.id
                    WINDOW w AS (PARTITION BY c.region ORDER BY o.amount DESC)
                    QUALIFY row_number() OVER w = 1
                    ORDER BY c.region""";
            for (Object[] row : engine.rows(largest)) {
                printed.add(Arrays.toString(row));
            }
            plan = engine.explain(sql);
            pipeline = engine.codegen() == CodegenMode.ALWAYS ? "[codegen pipeline]" : "[codegen]";
            parallel = engine.threads() > 1 ? " [parallel]" : "";
        }

        assertEquals(List.of("[north, 1, 10.50, 1, 15.75]", "[north, 3, 5.25, 2, 15.75]", "[north, 4, null, 3, 15.75]",
                "[south, 2, 20.00, 1, 20.00]", "[north, 1]", "[south, 2]"), printed);
        assertEquals("""
                Sort #0, #3
                  Project #4 AS region, #0 AS id, #1 AS amount, #5 AS place, #6 AS total
                    Window PARTITION BY #4: sum(#1) AS "sum(o.amount) OVER (PARTITION BY c.region)"
                      Window PARTITION BY #4 ORDER BY #1 DESC: rank() AS "rank() OVER (PARTITION BY c.region ORDER BY o.amount DESC NULLS LAST)"
                        Join INNER ON l#2 = r#0 %s%s
                          Project #0 AS id, #2 AS amount, CAST(#1 AS BIGINT) AS "#key0" [codegen]
                            Scan mem.main.orders (id BIGINT, customerId INTEGER, amount DECIMAL(10,2))
                          Scan csv.main.customers (id BIGINT, region VARCHAR)%s
                """.formatted(pipeline, parallel, parallel), plan);
    }

    @Test
    void theJdbcExampleRuns() throws IOException, SQLException {
        Path files = Files.createDirectories(data.resolve("data"));
        Files.writeString(files.resolve("customers.csv"), "id,region\n1,north\n2,south\n3,north\n",
                StandardCharsets.UTF_8);
        Files.writeString(files.resolve("orders.csv"), "id,customer,amount\n1,1,10.50\n2,2,20.00\n3,3,5.25\n4,1,\n"
                + "5,,7.00\n", StandardCharsets.UTF_8);
        Path config = Files.writeString(data.resolve("fyrql.yaml"), """
                sources:
                  - name: files
                    type: csv
                    directory: data
                """, StandardCharsets.UTF_8);
        List<String> printed = new ArrayList<>();

        // From here on as in the README, with the path of the file of the test in the URL.
        try (Connection connection = DriverManager.getConnection("jdbc:fyrql:?config="
                + config.toAbsolutePath().toString().replace('\\', '/'))) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
                while (tables.next()) {
                    printed.add(tables.getString("TABLE_CAT") + "." + tables.getString("TABLE_SCHEM") + "."
                            + tables.getString("TABLE_NAME"));
                }
            }
            String sql = """
                    SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
                    FROM orders AS o JOIN customers AS c ON o.customer = c.id
                    WHERE o.amount > ?
                    GROUP BY ALL
                    ORDER BY revenue DESC""";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBigDecimal(1, new BigDecimal("6.00"));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        printed.add(rs.getString("region") + " " + rs.getLong("orders") + " "
                                + rs.getString("revenue"));
                    }
                }
            }
        }

        assertEquals(List.of("files.main.customers", "files.main.orders", "south 1 20.0", "north 1 10.5"), printed);
    }

    @Test
    void theGeneratedFilterIsTheOneOfTheReadme() throws IOException {
        Schema schema = Schema.of(new Schema.Field("quantity", DataType.INTEGER),
                new Schema.Field("discount", DataType.decimal(4, 2)));
        String source = new JaninoCompiler().filter(and(lt(col(schema, 0), lit(24)),
                gt(col(schema, 1), lit(new BigDecimal("0.05")))), schema).source();
        String method = source.substring(source.indexOf("public int[] select(Batch in)"));
        // The tests run in the directory of the module.
        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("```java\n" + method + "```\n"), method);
    }

    @Test
    void theGeneratedPipelineIsTheOneOfTheReadme() throws IOException {
        // The filter and the projection of TPC-H Q6 over the four columns of lineitem it reads.
        DataType money = DataType.decimal(15, 2);
        Schema lineitem = Schema.of(new Schema.Field("l_quantity", money),
                new Schema.Field("l_extendedprice", money), new Schema.Field("l_discount", money),
                new Schema.Field("l_shipdate", DataType.DATE));
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("files", "lineitem"), lineitem);
        Expression shipdate = col(lineitem, 3);
        PlanNode.Filter filter = new PlanNode.Filter(scan, and(and(and(
                ge(shipdate, date((int) LocalDate.of(1994, 1, 1).toEpochDay())),
                lt(shipdate, date((int) LocalDate.of(1995, 1, 1).toEpochDay()))),
                between(col(lineitem, 2), lit(money, new BigDecimal("0.05")), lit(money, new BigDecimal("0.07")))),
                lt(col(lineitem, 0), lit(24))));
        PlanNode.Project project = new PlanNode.Project(filter, List.of(
                new NamedExpression("#argument0", mul(col(lineitem, 1), col(lineitem, 2)))));
        String source = new JaninoCompiler().pipeline(new PipelineSpec(lineitem, List.of(filter, project))).source();
        // The README shows the loop, not the method the filter is split into.
        int start = source.indexOf("public Batch consume(Batch in, JoinTable[] tables)");
        String method = source.substring(start, source.indexOf("\n}\n", start) + 3);
        String readme = Files.readString(Path.of("..", "README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("```java\n" + method + "```\n"), method);
    }
}
