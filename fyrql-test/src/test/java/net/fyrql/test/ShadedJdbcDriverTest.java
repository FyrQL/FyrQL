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

package net.fyrql.test;

import net.fyrql.connector.odata.FakeODataServer;
import net.fyrql.connector.odata.ODataVersion;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.jdbc.FyrQLDriver;
import net.fyrql.optimizer.Optimizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JAR of {@code fyrql-jdbc-all} as a tool such as DBeaver uses it: in a
 * class loader of its own next to the driver of a database, which it does
 * not register, with a class loader of the thread that sees neither. The
 * driver reads CSV, JSON, Parquet and H2 in one query, and an OData service
 * of the fake server with H2 in another, and compiles its expressions; the
 * JAR carries the libraries it renames, their licenses and no class of
 * their original packages.
 */
class ShadedJdbcDriverTest {

    /** The original packages, put together so that no tool rewrites them. */
    private static final List<String> ORIGINALS = List.of(String.join(".", "org", "apache", "parquet"),
            String.join(".", "org", "apache", "hadoop"), String.join(".", "shaded", "parquet"),
            String.join(".", "org", "codehaus"), String.join(".", "com", "fasterxml"),
            String.join(".", "org", "yaml"), String.join(".", "net", "sf", "jsqlparser"),
            String.join(".", "de", "siegmar"), String.join(".", "io", "airlift"), String.join(".", "org", "slf4j"),
            String.join(".", "org", "locationtech"));

    @TempDir
    Path dir;

    /** The JAR the build made, or a skipped test when it is not there, e.g. in an IDE. */
    private static Path jar() {
        String property = System.getProperty("fyrql.jdbc.all.jar");
        assumeTrue(property != null && !property.isEmpty(), "the build names no JAR of fyrql-jdbc-all");
        Path jar = Path.of(property);
        assumeTrue(Files.isRegularFile(jar), "there is no JAR " + jar);
        return jar;
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    void aFederatedQueryRunsThroughTheJarInAClassLoaderOfItsOwn() throws Exception {
        Path jar = jar();
        try (FakeODataServer server = FakeODataServer.start()) {
            Schema discounts = new Schema(List.of(new Schema.Field("Country", DataType.VARCHAR, false),
                    new Schema.Field("Rate", DataType.decimal(4, 2), true)));
            server.service("pricing", ODataVersion.V4).pageSize(1).entitySet("Discounts", discounts,
                    List.of(new Object[] {"DE", new BigDecimal("0.10")}, new Object[] {"FR", new BigDecimal("0.05")},
                            new Object[] {"IT", null}), "Country");
            federatedQuery(jar, server);
        }
    }

    private void federatedQuery(Path jar, FakeODataServer server) throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("orders.csv"), "id,customer,amount\n1,1,10.50\n2,2,20.00\n3,1,5.25\n",
                StandardCharsets.UTF_8);
        Files.writeString(data.resolve("customers.json"),
                "[{\"id\": 1, \"name\": \"Ann\", \"country\": \"DE\"}, {\"id\": 2, \"name\": \"Bob\", \"country\": \"FR\"}]",
                StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1, 2), (1, 3), (2, 1), (3, 4)) v(order_id, qty)) TO '"
                    + path(data.resolve("items.parquet")) + "' (FORMAT parquet)");
        }
        String h2 = "jdbc:h2:file:" + path(dir.resolve("countries"));
        try (Connection c = DriverManager.getConnection(h2); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE \"countries\" (\"code\" VARCHAR(2), \"name\" VARCHAR(20))");
            s.execute("INSERT INTO \"countries\" VALUES ('DE', 'Germany'), ('FR', 'France')");
        }
        Path config = Files.writeString(dir.resolve("fyrql.yaml"), """
                sources:
                  - name: files
                    type: csv
                    directory: data
                  - name: docs
                    type: json
                    directory: data
                  - name: pq
                    type: parquet
                    directory: data
                  - name: db
                    type: jdbc
                    url: "%s"
                  - name: erp
                    type: odata
                    url: "%s"
                    trustFilters: true
                """.formatted(h2, server.url("pricing")), StandardCharsets.UTF_8);

        URL h2Jar = org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation();
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL(), h2Jar},
                ClassLoader.getPlatformClassLoader());
                URLClassLoader empty = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(empty);
            Driver driver = (Driver) Class.forName("net.fyrql.jdbc.FyrQLDriver", true, loader).getConstructor()
                    .newInstance();
            assertNotSame(FyrQLDriver.class, driver.getClass());
            assertEquals(jar.toUri().toURL(), driver.getClass().getProtectionDomain().getCodeSource().getLocation());
            FyrQLDriver unshaded = new FyrQLDriver();
            assertEquals(unshaded.getMajorVersion() + "." + unshaded.getMinorVersion(),
                    driver.getMajorVersion() + "." + driver.getMinorVersion());
            String sql = """
                    SELECT c.name, k.name AS country, sum(o.amount * i.qty) AS total
                    FROM orders o
                    JOIN customers c ON o.customer = c.id
                    JOIN items i ON i.order_id = o.id
                    JOIN countries k ON k.code = c.country
                    WHERE o.amount > 1
                    GROUP BY c.name, k.name
                    ORDER BY c.name""";
            try (Connection c = driver.connect("jdbc:fyrql:?config=" + path(config) + "&codegen=always",
                    new Properties())) {
                List<String> catalogs = new ArrayList<>();
                try (ResultSet rs = c.getMetaData().getCatalogs()) {
                    while (rs.next()) {
                        catalogs.add(rs.getString(1));
                    }
                }
                assertEquals(List.of("files", "docs", "pq", "db", "erp"), catalogs);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                    assertTrue(rs.next());
                    assertEquals("Ann", rs.getString(1));
                    assertEquals("Germany", rs.getString(2));
                    assertEquals(0, new BigDecimal("73.50").compareTo(rs.getBigDecimal(3)), rs.getString(3));
                    assertTrue(rs.next());
                    assertEquals("Bob", rs.getString(1));
                    assertEquals("France", rs.getString(2));
                    assertEquals(0, new BigDecimal("20.00").compareTo(rs.getBigDecimal(3)), rs.getString(3));
                    assertFalse(rs.next());
                }
                StringBuilder plan = new StringBuilder();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                assertTrue(plan.toString().contains("[codegen"), "the expressions are compiled:\n" + plan);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT k.name, d.Rate"
                        + " FROM Discounts d JOIN countries k ON k.code = d.Country WHERE d.Rate >= 0.05"
                        + " ORDER BY k.name")) {
                    assertTrue(rs.next());
                    assertEquals("France", rs.getString(1));
                    assertEquals(0, new BigDecimal("0.05").compareTo(rs.getBigDecimal(2)), rs.getString(2));
                    assertTrue(rs.next());
                    assertEquals("Germany", rs.getString(1));
                    assertEquals(0, new BigDecimal("0.10").compareTo(rs.getBigDecimal(2)), rs.getString(2));
                    assertFalse(rs.next());
                }
                // The driver reads the same system property of pushdown as the engines of the tests.
                boolean pushdown = Optimizer.Options.fromSystemProperty().equals(Optimizer.Options.DEFAULT);
                assertEquals(pushdown, server.requests("/pricing/Discounts").stream().anyMatch(r -> "Rate ge 0.05"
                        .equals(r.parameters().get("$filter"))), () -> "the filter goes to the service: "
                        + server.requests());
                assertTrue(server.requests("/pricing/Discounts").stream().anyMatch(r -> r.parameters()
                        .containsKey("$skiptoken")), "the answer has several pages");
                SQLException e = assertThrows(SQLException.class,
                        () -> c.createStatement().executeQuery("SELECT * FROM nowhere"));
                assertEquals("42P01", e.getSQLState(), e.getMessage());
            }
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    @Test
    void theJarCarriesTheDriverAndTheLicensesAndNoOriginalClasses() throws IOException {
        Path location = jar();
        try (JarFile jar = new JarFile(location.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();
            for (String original : ORIGINALS) {
                String prefix = original.replace('.', '/') + "/";
                assertTrue(names.stream().noneMatch(n -> n.startsWith(prefix)), "classes of " + original);
                assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/services/" + original)),
                        "services of " + original);
            }
            assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/versions/")), "classes for newer JDKs");
            assertTrue(names.stream().noneMatch(n -> n.endsWith("module-info.class")), "module descriptors");
            assertTrue(names.stream().noneMatch(n -> n.matches("META-INF/[^/]*\\.(SF|DSA|RSA)")), "signature files");
            for (String renamed : List.of("jackson", "snakeyaml", "jsqlparser", "fastcsv", "janino", "parquet")) {
                assertTrue(names.contains("net/fyrql/internal/" + renamed + "/"), renamed);
            }
            assertEquals(List.of("net.fyrql.jdbc.FyrQLDriver"), services(jar, "java.sql.Driver"));
            assertEquals(Set.of("net.fyrql.connector.csv.CsvConnectorFactory",
                    "net.fyrql.connector.json.JsonConnectorFactory",
                    "net.fyrql.connector.parquet.ParquetConnectorFactory",
                    "net.fyrql.connector.http.HttpConnectorFactory",
                    "net.fyrql.connector.odata.ODataConnectorFactory",
                    "net.fyrql.connector.jdbc.JdbcConnectorFactory"),
                    Set.copyOf(services(jar, "net.fyrql.connector.ConnectorFactory")));
            assertEquals(List.of("net.fyrql.codegen.JaninoCompiler"),
                    services(jar, "net.fyrql.exec.spi.ExpressionCompiler"));
            assertEquals(List.of("net.fyrql.internal.janino.CompilerFactory"),
                    services(jar, "net.fyrql.internal.commons.compiler.ICompilerFactory"));
            for (String license : List.of("THIRD-PARTY.txt", "apache-2.0-LICENSE.txt", "jackson-NOTICE.txt",
                    "fastcsv-LICENSE.txt", "janino-LICENSE.txt", "parquet-java-LICENSE.txt",
                    "parquet-java-NOTICE.txt", "jts-LICENSE.txt", "slf4j-LICENSE.txt")) {
                assertTrue(names.contains("META-INF/licenses/" + license), license);
            }
            String thirdParty = text(jar, "META-INF/licenses/THIRD-PARTY.txt");
            for (String library : List.of("Jackson", "SnakeYAML", "JSqlParser", "FastCSV", "Janino",
                    "Apache Parquet Java", "aircompressor", "JTS", "SLF4J")) {
                assertTrue(thirdParty.contains(library), library);
            }
            assertEquals("net.fyrql.jdbc.all",
                    jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
        }
    }

    private static List<String> services(JarFile jar, String service) throws IOException {
        return text(jar, "META-INF/services/" + service).lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static String text(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertTrue(entry != null, name);
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
