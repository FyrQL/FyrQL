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

package net.fyrql.config;

import net.fyrql.FyrQL;
import net.fyrql.connector.jdbc.JdbcConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A configuration file alone builds an engine over CSV files, a JSON file
 * and a database, which answers a query that joins all three.
 */
class FyrQLConfigTest {

    private static final String QUERY = "SELECT o.order_id, c.name, p.name, o.qty * p.price AS amount"
            + " FROM (SELECT * FROM orders WHERE qty > 1) o JOIN files.customers c ON o.customer_id = c.id"
            + " JOIN docs.products p ON o.sku = p.sku ORDER BY o.order_id";

    @TempDir
    Path dir;

    private String shop;

    @BeforeEach
    void files() throws IOException, SQLException {
        Files.createDirectories(dir.resolve("data"));
        Files.writeString(dir.resolve("data/customers.csv"), "id,name,city\n1,Ann,Rome\n2,Bob,Oslo\n3,Cid,Rome\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("products.json"), "[{\"sku\": \"p1\", \"name\": \"Pen\", \"price\": 1.5},"
                + " {\"sku\": \"p2\", \"name\": \"Ink\", \"price\": 4}]", StandardCharsets.UTF_8);
        shop = "jdbc:h2:file:" + dir.toAbsolutePath().resolve("shop").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(shop, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE orders (order_id INTEGER, customer_id BIGINT, sku VARCHAR(10), qty INTEGER)");
            s.execute("INSERT INTO orders VALUES (1, 1, 'p1', 3), (2, 1, 'p2', 1), (3, 2, 'p1', 10), (4, 3, 'p2', 2),"
                    + " (5, 9, 'p1', 5)");
        }
    }

    private Path config(String engine) throws IOException {
        return Files.writeString(dir.resolve("fyrql.yaml"), """
                sources:
                  - name: shop
                    type: jdbc
                    url: %s
                    user: sa
                    recordStatements: true
                  - name: files
                    type: csv
                    directory: data
                  - name: docs
                    type: json
                    tables:
                      products: products.json
                engine:
                %s""".formatted(shop, engine.indent(2)), StandardCharsets.UTF_8);
    }

    @Test
    void aFileAloneBuildsAnEngineThatJoinsCsvJsonAndADatabase() throws IOException {
        Path file = config("defaultSource: shop\nbatchSize: 2\nmemoryBudget: 64MiB\ncodegen: off\npushdown: true\n"
                + "threads: 3\npreserveOrder: false\n");
        try (FyrQL engine = FyrQLConfig.open(file)) {
            assertEquals(List.of("shop", "files", "docs"), engine.sources());
            assertTrue(engine.pushdown());
            assertEquals(3, engine.threads());
            assertFalse(engine.preserveOrder());
            List<Object[]> rows = engine.rows(QUERY);
            assertEquals(List.of(List.of(1, "Ann", "Pen", 4.5), List.of(3, "Bob", "Pen", 15.0),
                    List.of(4, "Cid", "Ink", 8.0)), rows.stream().map(Arrays::asList).toList());
            List<String> statements = ((JdbcConnector) engine.connector("shop")).statements();
            assertEquals(1, statements.size());
            assertTrue(statements.get(0).contains("WHERE"), statements.get(0));
        }
    }

    @Test
    void withoutPushdownTheDatabaseOnlyReadsColumns() throws IOException {
        try (FyrQL engine = FyrQLConfig.open(config("defaultSource: shop\npushdown: off\n"))) {
            assertFalse(engine.pushdown());
            assertTrue(engine.preserveOrder());
            assertEquals(3, engine.rows(QUERY).size());
            List<String> statements = ((JdbcConnector) engine.connector("shop")).statements();
            assertFalse(statements.get(0).contains("WHERE"), statements.get(0));
        }
    }

    @Test
    void anApplicationMayAddToTheBuilder() throws IOException {
        try (FyrQL engine = FyrQLConfig.builder(config("{}")).defaultSource("docs").build()) {
            assertEquals(2, engine.rows("SELECT * FROM products").size());
        }
    }

    @Test
    void anInvalidSettingOrSourceNamesTheFileAndWhatIsWrong() throws IOException {
        Path file = config("pushdown: maybe\n");
        assertEquals(file + ": The option pushdown of the engine must be true, false, on or off: maybe",
                assertThrows(ConfigException.class, () -> FyrQLConfig.open(file)).getMessage());
        Path none = config("threads: 0\n");
        assertEquals(none + ": threads: 0", assertThrows(ConfigException.class, () -> FyrQLConfig.open(none))
                .getMessage());
        Path other = config("defaultSource: shops\n");
        assertEquals(other + ": The default source shops of the engine is none of the sources shop, files, docs",
                assertThrows(ConfigException.class, () -> FyrQLConfig.open(other)).getMessage());
        Path typo = config("batchsize: 2\n");
        assertTrue(assertThrows(ConfigException.class, () -> FyrQLConfig.open(typo)).getMessage().startsWith(
                typo + ": The engine has an unknown option batchsize; known are defaultSource, defaultSchema,"
                        + " batchSize, memoryBudget, codegen, codegenThreshold, pushdown, threads,"
                        + " preserveOrder"));
        Files.delete(dir.resolve("products.json"));
        Path missing = config("{}");
        String message = assertThrows(ConfigException.class, () -> FyrQLConfig.open(missing)).getMessage();
        assertTrue(message.startsWith(missing + ": Cannot create the source docs: "), message);
    }
}
