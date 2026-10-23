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

package net.fyrql.config;

import net.fyrql.FyrQL;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileAccess;
import net.fyrql.connector.FileAccessException;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.s3.FakeS3Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void anIcebergSourceReadsATableWithoutSnapshots() throws IOException {
        Path metadata = Files.createDirectories(dir.resolve("lake/orders/metadata"));
        Files.writeString(metadata.resolve("v1.metadata.json"), """
                {"format-version": 2, "table-uuid": "4b5c4f1e-0d6f-4f3a-9d8e-2a1b3c4d5e6f",
                 "location": "s3://elsewhere/orders", "last-sequence-number": 0, "last-updated-ms": 1,
                 "last-column-id": 2, "current-schema-id": 0,
                 "schemas": [{"type": "struct", "schema-id": 0, "fields": [
                   {"id": 1, "name": "id", "required": true, "type": "long"},
                   {"id": 2, "name": "note", "required": false, "type": "string"}]}],
                 "default-spec-id": 0, "partition-specs": [{"spec-id": 0, "fields": []}],
                 "last-partition-id": 999, "default-sort-order-id": 0,
                 "sort-orders": [{"order-id": 0, "fields": []}], "properties": {},
                 "current-snapshot-id": -1, "snapshots": []}""", StandardCharsets.UTF_8);
        Path file = Files.writeString(dir.resolve("iceberg.yaml"), """
                sources:
                  - name: lake
                    type: iceberg
                    tables:
                      orders: lake/orders
                """, StandardCharsets.UTF_8);
        try (FyrQL engine = FyrQLConfig.open(file)) {
            assertEquals(List.of("lake", "csv", "json", "parquet"), engine.sources());
            assertEquals(List.of(List.of(0L)), engine.rows("SELECT count(*) FROM lake.orders").stream()
                    .map(Arrays::asList).toList());
            assertEquals(List.of(List.of(0L)), engine.rows("SELECT count(*) FROM iceberg_scan('"
                    + metadata.getParent() + "')").stream().map(Arrays::asList).toList());
            assertEquals(List.of("id", "note"), engine.plan("SELECT * FROM lake.orders").schema().fields().stream()
                    .map(f -> f.name()).toList());
        }
    }

    @Test
    void aFileAloneBuildsAnEngineThatJoinsCsvJsonAndADatabase() throws IOException {
        Path file = config("defaultSource: shop\nbatchSize: 2\nmemoryBudget: 64MiB\ncodegen: off\npushdown: true\n"
                + "threads: 3\npreserveOrder: false\nspill: on\nspillDirectory: spill\n");
        try (FyrQL engine = FyrQLConfig.open(file)) {
            assertEquals(List.of("shop", "files", "docs", "iceberg", "parquet"), engine.sources());
            assertEquals(Optional.of(file.getParent().resolve("spill")), engine.spillDirectory(),
                    "a relative directory lies next to the file");
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
    void theFormatsOfFilesNeedNoSourceOfTheirOwn() throws IOException {
        String file = dir.resolve("n.parquet").toString().replace('\\', '/');
        try (FyrQL engine = FyrQLConfig.open(config("{}"))) {
            assertEquals(2, engine.update("COPY (SELECT * FROM (VALUES (1), (2)) v(n)) TO '" + file + "'"));
            assertEquals(List.of(List.of(1), List.of(2)), engine.rows("SELECT * FROM '" + file + "' ORDER BY n")
                    .stream().map(Arrays::asList).toList());
        }
        try (FyrQL engine = FyrQLConfig.open(config("implicitSources: false\n"))) {
            assertEquals(List.of("shop", "files", "docs"), engine.sources());
        }
    }

    @Test
    void withoutPushdownTheDatabaseOnlyReadsColumns() throws IOException {
        try (FyrQL engine = FyrQLConfig.open(config("defaultSource: shop\npushdown: off\nspill: false\n"))) {
            assertFalse(engine.pushdown());
            assertEquals(Optional.empty(), engine.spillDirectory());
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
    void aCustomizerAddsToTheBuilderAndItsFailureClosesTheConnectors() throws IOException {
        EngineConfig config = EngineConfig.read(config("{}"));
        try (FyrQL engine = FyrQLConfig.open(config, b -> b.defaultSource("docs"))) {
            assertEquals(2, engine.rows("SELECT * FROM products").size());
        }
        List<String> closed = new ArrayList<>();
        Map<String, ConnectorFactory> factories = new TreeMap<>();
        Connectors.factories().forEach((type, f) -> factories.put(type, closing(f, closed)));
        ConfigException e = assertThrows(ConfigException.class, () -> FyrQLConfig.open(config, factories, b -> {
            throw new IllegalStateException("no functions");
        }));
        assertEquals("Cannot build the engine: no functions", e.getMessage());
        assertEquals(List.of("shop", "files", "docs"), closed);
        closed.clear();
        FyrQLConfig.open(config, factories, b -> {
        }).close();
        assertEquals(List.of("docs", "files", "shop"), closed.stream().sorted().toList());
    }

    /** A factory whose connectors note their names when they close. */
    private static ConnectorFactory closing(ConnectorFactory factory, List<String> closed) {
        return new ConnectorFactory() {
            @Override
            public String type() {
                return factory.type();
            }

            @Override
            public Connector create(String name, Map<String, String> options, Path directory) {
                Connector c = factory.create(name, options, directory);
                return (Connector) Proxy.newProxyInstance(Connector.class.getClassLoader(),
                        new Class<?>[] {Connector.class}, (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                closed.add(name);
                            }
                            try {
                                return method.invoke(c, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }
        };
    }

    @Test
    void theFileAccessComesFromTheFileWithLocationsRelativeToIt() throws IOException {
        Path file = config("fileAccess: read\nallowedLocations: [data, S3://lake]\n");
        try (FyrQL engine = FyrQLConfig.open(file)) {
            assertEquals(FileAccess.READ, engine.fileAccess());
            assertEquals(List.of(dir.resolve("data").toRealPath().toString(), "s3://lake/"), engine.allowedLocations());
            String customers = dir.resolve("data/customers.csv").toString().replace('\\', '/');
            assertEquals(3, engine.rows("SELECT * FROM '" + customers + "'").size());
            String products = dir.resolve("products.json").toString().replace('\\', '/');
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("SELECT * FROM '" + products
                    + "'"));
            assertInstanceOf(FileAccessException.class, e.getCause());
            assertEquals(2, engine.rows("SELECT * FROM docs.products").size(), "a registered table");
        }
        try (FyrQL engine = FyrQLConfig.open(config("allowedLocations: data\n"))) {
            assertEquals(FileAccess.READ_WRITE, engine.fileAccess());
            assertEquals(1, engine.allowedLocations().size());
        }
        Path unknown = config("fileAccess: all\n");
        assertEquals(unknown + ": The option fileAccess of the engine must be none, read or readWrite: all",
                assertThrows(ConfigException.class, () -> FyrQLConfig.open(unknown)).getMessage());
        Path none = config("fileAccess: none\nallowedLocations: [data]\n");
        assertEquals(none + ": Cannot build the engine: allowedLocations needs fileAccess read or readWrite, not none",
                assertThrows(ConfigException.class, () -> FyrQLConfig.open(none)).getMessage());
    }

    @Test
    void theTimeoutsComeFromTheFile() throws IOException {
        try (FyrQL engine = FyrQLConfig.open(config("queryTimeout: 30s\nmaxQueryTimeout: 5m\n"))) {
            assertEquals(Optional.of(Duration.ofSeconds(30)), engine.queryTimeout());
            assertEquals(Optional.of(Duration.ofMinutes(5)), engine.maxQueryTimeout());
        }
        Path above = config("queryTimeout: 10m\nmaxQueryTimeout: 5m\n");
        assertEquals(above + ": Cannot build the engine: The queryTimeout 10m is above the maxQueryTimeout 5m",
                assertThrows(ConfigException.class, () -> FyrQLConfig.open(above)).getMessage());
        Path zero = config("queryTimeout: 0s\n");
        assertTrue(assertThrows(ConfigException.class, () -> FyrQLConfig.open(zero)).getMessage().startsWith(
                zero + ": The option queryTimeout of the engine must be a duration"));
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
                        + " batchSize, memoryBudget, spill, spillDirectory, codegen, codegenThreshold, pushdown,"
                        + " threads,"
                        + " preserveOrder, implicitSources"));
        Files.delete(dir.resolve("products.json"));
        Path missing = config("{}");
        String message = assertThrows(ConfigException.class, () -> FyrQLConfig.open(missing)).getMessage();
        assertTrue(message.startsWith(missing + ": Cannot create the source docs: "), message);
    }

    @Test
    void aSourceOfS3HoldsTheFilesOfS3LocationsForTheSourcesOfFormats() throws IOException {
        try (FakeS3Server s3 = FakeS3Server.start("fyrql", "fyrql-secret")) {
            s3.createBucket("lake");
            Path file = Files.writeString(dir.resolve("s3.yaml"), """
                    sources:
                      - name: s3
                        type: s3
                        endpoint: %s
                        accessKey: fyrql
                        secretKey: fyrql-secret
                        timeout: 10s
                      - name: lake
                        type: parquet
                        tables:
                          events: s3://lake/events/*.parquet
                    """.formatted(s3.endpoint()), StandardCharsets.UTF_8);
            try (FyrQL engine = FyrQLConfig.open(file)) {
                assertEquals(List.of("s3", "lake", "csv", "iceberg", "json"), engine.sources());
                assertEquals(2, engine.update("COPY (SELECT * FROM (VALUES (1), (2)) v(n)) TO"
                        + " 's3://lake/events/a.parquet'"));
                assertEquals(1, engine.update("COPY (SELECT 3 AS n) TO 's3://lake/events/b.parquet'"));
                assertEquals(List.of(List.of(1), List.of(2), List.of(3)), engine.rows("SELECT n FROM lake.events"
                        + " ORDER BY n").stream().map(Arrays::asList).toList());
                assertEquals(List.of("events/a.parquet", "events/b.parquet"), s3.keys("lake"));
            }
        }
    }
}
