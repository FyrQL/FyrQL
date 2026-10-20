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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The URL and the properties of the driver, and the engine it builds from them. */
class DriverTest {

    @TempDir
    Path dir;

    private final FyrQLDriver driver = new FyrQLDriver();

    @Test
    void theDriverAcceptsItsUrlsOnly() throws SQLException {
        assertTrue(driver.acceptsURL("jdbc:fyrql:"));
        assertTrue(driver.acceptsURL("JDBC:FyrQL:?config=x.yaml"));
        assertFalse(driver.acceptsURL("jdbc:h2:mem:"));
        assertFalse(driver.acceptsURL("jdbc:fyrq"));
        assertThrows(SQLException.class, () -> driver.acceptsURL(null));
        assertNull(driver.connect("jdbc:h2:mem:", new Properties()));
    }

    @Test
    void theDriverIsRegisteredWithTheDriverManager() throws SQLException {
        assertInstanceOf(FyrQLDriver.class, DriverManager.getDriver("jdbc:fyrql:"));
        try (Connection c = DriverManager.getConnection("jdbc:fyrql:")) {
            assertEquals(List.of(1), first(c, "SELECT 1"));
        }
    }

    @Test
    void theVersionComesFromTheBuild() {
        assertTrue(FyrQLDriver.VERSION.matches("\\d+\\.\\d+.*"), FyrQLDriver.VERSION);
        assertEquals(Integer.parseInt(FyrQLDriver.VERSION.split("\\.")[0]), driver.getMajorVersion());
        assertEquals(Integer.parseInt(FyrQLDriver.VERSION.split("\\.")[1]), driver.getMinorVersion());
        assertFalse(driver.jdbcCompliant());
    }

    @Test
    void withoutAConfigurationTheEngineHasTheSourcesOfFiles() throws Exception {
        Path csv = Files.writeString(dir.resolve("t.csv"), "a,b\n1,x\n2,y\n", StandardCharsets.UTF_8);
        Path json = Files.writeString(dir.resolve("t.json"), "[{\"a\": 1}, {\"a\": 5}]", StandardCharsets.UTF_8);
        try (Connection c = driver.connect("jdbc:fyrql:", null)) {
            FyrQL engine = c.unwrap(FyrQL.class);
            assertEquals(List.of("csv", "json", "parquet", "memory"), engine.sources());
            assertNull(c.getCatalog());
            assertEquals("main", c.getSchema());
            assertEquals(List.of(2L), first(c, "SELECT count(*) FROM read_csv('" + path(csv) + "')"));
            assertEquals(List.of(BigDecimal.valueOf(6)), first(c, "SELECT sum(a) FROM '" + path(json) + "'"));
            assertNull(c.getWarnings());
        }
    }

    @Test
    void theUrlSetsTheEngine() throws Exception {
        try (Connection c = driver.connect("jdbc:fyrql:?codegen=off&pushdown=false&batchSize=7&memoryBudget=64MB"
                + "&codegenThreshold=5&defaultSchema=other&threads=3&preserveOrder=false", null)) {
            FyrQL engine = c.unwrap(FyrQL.class);
            assertEquals(CodegenMode.OFF, engine.codegen());
            assertFalse(engine.pushdown());
            assertEquals(3, engine.threads());
            assertFalse(engine.preserveOrder());
            assertEquals("other", c.getSchema());
        }
        try (Connection c = driver.connect("jdbc:fyrql:?CODEGEN=off&&", null)) {
            assertEquals(CodegenMode.OFF, c.unwrap(FyrQL.class).codegen());
        }
    }

    @Test
    void propertiesWinOverTheUrl() throws Exception {
        Properties p = new Properties();
        p.setProperty("pushdown", "true");
        p.setProperty("user", "sa");
        p.setProperty("password", "");
        p.setProperty("codegen", " ");
        try (Connection c = driver.connect("jdbc:fyrql:?pushdown=false&codegen=off", p)) {
            assertTrue(c.unwrap(FyrQL.class).pushdown());
            assertEquals(CodegenMode.OFF, c.unwrap(FyrQL.class).codegen(), "a blank property counts as not set");
            assertNull(c.getWarnings(), "user and password are ignored");
        }
    }

    @Test
    void anUnknownPropertyIsAWarning() throws Exception {
        Properties p = new Properties();
        p.setProperty("color", "red");
        p.setProperty("shape", "round");
        try (Connection c = driver.connect("jdbc:fyrql:", p)) {
            List<String> messages = new ArrayList<>();
            for (SQLWarning w = c.getWarnings(); w != null; w = w.getNextWarning()) {
                messages.add(w.getMessage());
            }
            assertEquals(2, messages.size(), messages.toString());
            assertTrue(messages.get(0).startsWith("The property color is unknown and ignored; known are config,"),
                    messages.get(0));
            assertTrue(messages.get(1).startsWith("The property shape is unknown"), messages.get(1));
            c.clearWarnings();
            assertNull(c.getWarnings());
        }
    }

    @Test
    void aBadUrlCannotConnect() {
        failsToConnect("jdbc:fyrql:?colour=red", "The URL has the unknown key colour; known are config, defaultSource,"
                + " defaultSchema, batchSize, memoryBudget, codegen, codegenThreshold, pushdown, threads,"
                + " preserveOrder");
        failsToConnect("jdbc:fyrql:foo", "The URL jdbc:fyrql:foo is not of the form jdbc:fyrql: or"
                + " jdbc:fyrql:?config=<file>&<key>=<value>...");
        failsToConnect("jdbc:fyrql:?codegen", "The URL has the part codegen, which is not of the form <key>=<value>");
        failsToConnect("jdbc:fyrql:?codegen=off&Codegen=auto", "The URL sets the key codegen twice");
        failsToConnect("jdbc:fyrql:?config=%zz", "The URL has the part %zz, which is not validly encoded");
        failsToConnect("jdbc:fyrql:?codegen=sometimes", "The option codegen of the engine must be off, auto or always: sometimes");
        failsToConnect("jdbc:fyrql:?defaultSource=shop", "The default source shop of the engine is none of the"
                + " sources csv, json, parquet");
        failsToConnect("jdbc:fyrql:?config=" + path(dir.resolve("missing.yaml")), "Cannot read the configuration");
    }

    @Test
    void aConfigurationGivesTheSourcesAndTheUrlOverlaysItsSettings() throws Exception {
        Path data = Files.createDirectories(dir.resolve("a b+c").resolve("data"));
        Files.writeString(data.resolve("t.csv"), "a,b\n1,x\n2,y\n3,z\n", StandardCharsets.UTF_8);
        Path config = Files.writeString(data.getParent().resolve("fyrql.yaml"), """
                sources:
                  - name: files
                    type: csv
                    directory: data
                engine:
                  pushdown: true
                  codegen: off
                """, StandardCharsets.UTF_8);
        String url = "jdbc:fyrql:?config=" + path(config).replace(" ", "%20") + "&pushdown=false";
        try (Connection c = driver.connect(url, null)) {
            FyrQL engine = c.unwrap(FyrQL.class);
            assertEquals(List.of("files", "json", "parquet"), engine.sources(), "with the implicit sources");
            assertEquals("files", c.getCatalog(), "the only registered source is the default");
            assertFalse(engine.pushdown(), "the URL wins over the file");
            assertEquals(CodegenMode.OFF, engine.codegen());
            assertEquals(List.of(3L), first(c, "SELECT count(*) FROM t"));
        }
        Properties p = new Properties();
        p.setProperty("config", path(config));
        try (Connection c = driver.connect("jdbc:fyrql:", p)) {
            assertEquals(List.of(BigDecimal.valueOf(6)), first(c, "SELECT sum(a) FROM files.t"));
        }
    }

    @Test
    void anInvalidConfigurationNamesTheFile() throws IOException {
        Path config = Files.writeString(dir.resolve("bad.yaml"), """
                sources:
                  - name: files
                    type: csv
                    colour: red
                """, StandardCharsets.UTF_8);
        SQLException e = assertThrows(SQLException.class, () -> driver.connect("jdbc:fyrql:?config="
                + path(config), null));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().startsWith(Path.of(path(config)) + ": "), e.getMessage());
        assertTrue(e.getMessage().contains("colour"), e.getMessage());
    }

    @Test
    void thePropertyInfoDescribesTheKeys() throws SQLException {
        DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:fyrql:?codegen=always", new Properties());
        assertEquals(List.of("config", "defaultSource", "defaultSchema", "batchSize", "memoryBudget", "codegen",
                "codegenThreshold", "pushdown", "threads", "preserveOrder"),
                Arrays.stream(info).map(i -> i.name).toList());
        DriverPropertyInfo codegen = info[5];
        assertEquals("always", codegen.value);
        assertArrayEquals(new String[] {"off", "auto", "always"}, codegen.choices);
        assertFalse(codegen.required);
        assertTrue(codegen.description.startsWith("When expressions are compiled"));
        assertNull(info[0].value);
        assertNull(info[0].choices);
        assertEquals(10, driver.getPropertyInfo("jdbc:fyrql:?colour=red", null).length, "a bad URL is no error here");
    }

    private void failsToConnect(String url, String message) {
        SQLException e = assertThrows(SQLException.class, () -> driver.connect(url, null), url);
        assertEquals("08001", e.getSQLState(), url);
        assertTrue(e.getMessage().startsWith(message) || e.getMessage().contains(message), e.getMessage());
    }

    /** Runs a query on a connection and returns its first row, as {@code getObject} reads it. */
    static List<Object> first(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.add(rs.getObject(i));
            }
            return row;
        }
    }

    static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
