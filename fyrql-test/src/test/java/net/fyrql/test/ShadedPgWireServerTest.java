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

import net.fyrql.pgwire.PgWireServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JAR of {@code fyrql-pgwire-all} as a user starts it: with
 * {@code java -jar} in a process of its own, and with its class name and a
 * JDBC driver next to it on the class path. The JDBC driver of PostgreSQL
 * reads the databases, a federated query over CSV, JSON and Parquet with
 * compiled expressions and a prepared statement; files that statements
 * name only as the configuration allows; the JAR carries the server, the
 * libraries it renames and their licenses, and no Arrow, gRPC or Netty.
 */
class ShadedPgWireServerTest {

    /** The original packages of the renamed libraries, put together so that no tool rewrites them. */
    private static final List<String> ORIGINALS = List.of(String.join(".", "org", "apache", "parquet"),
            String.join(".", "org", "apache", "hadoop"), String.join(".", "shaded", "parquet"),
            String.join(".", "org", "codehaus"), String.join(".", "com", "fasterxml"),
            String.join(".", "org", "yaml"), String.join(".", "net", "sf", "jsqlparser"),
            String.join(".", "de", "siegmar"), String.join(".", "io", "airlift"),
            String.join(".", "org", "locationtech"));

    /** What the JAR of the PostgreSQL server leaves out, which the Flight SQL server brings. */
    private static final List<String> ABSENT = List.of("org/apache/arrow/", "io/grpc/", "io/netty/",
            "com/google/protobuf/", "org/slf4j/simple/");

    private static final Pattern LISTENING = Pattern.compile(
            "FyrQL PostgreSQL wire protocol server (\\S+) listening on 127\\.0\\.0\\.1:(\\d+)");

    /** A query in the database files, which names the tables of the other databases by three parts. */
    private static final String FEDERATED = """
            SELECT c.name, sum(o.amount * i.qty) AS total
            FROM orders o
            JOIN docs.main.customers c ON o.customer = c.id
            JOIN pq.main.items i ON i.order_id = o.id
            WHERE o.amount > 1
            GROUP BY c.name
            ORDER BY c.name""";

    @TempDir
    Path dir;

    /** A server in a process of its own; closing it ends the process. */
    private record Child(Process process, int port, Path log) implements AutoCloseable {

        /** Starts the command and waits for the line with the address of the server. */
        static Child start(Path log, List<String> command) throws Exception {
            Process process = new ProcessBuilder(command).redirectError(log.toFile()).start();
            try {
                BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8));
                String line = CompletableFuture.supplyAsync(() -> {
                    try {
                        return out.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).get(60, TimeUnit.SECONDS);
                assertNotNull(line, () -> "the server does not start: " + read(log));
                Matcher m = LISTENING.matcher(line);
                assertTrue(m.matches(), line);
                assertEquals(PgWireServer.VERSION, m.group(1));
                return new Child(process, Integer.parseInt(m.group(2)), log);
            } catch (Exception | AssertionError e) {
                stop(process);
                throw e;
            }
        }

        /** Returns what the server wrote to its standard error stream so far. */
        String errors() {
            return read(log);
        }

        /** Opens a connection of the JDBC driver of PostgreSQL to a database. */
        Connection connect(String database) throws SQLException {
            Properties p = new Properties();
            p.setProperty("user", "tester");
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database, p);
        }

        /**
         * Ends the process and deletes its log. On Windows, the file may stay
         * open for a moment after the process ended, so that the deletion of
         * the temporary directory would fail.
         */
        @Override
        public void close() {
            stop(process);
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.deleteIfExists(log);
                    return;
                } catch (IOException e) {
                    if (attempt == 50) {
                        return;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private static void stop(Process process) {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        private static String read(Path log) {
            try {
                return Files.readString(log);
            } catch (IOException e) {
                return e.toString();
            }
        }
    }

    /** The JAR the build made, or a skipped test when it is not there, e.g. in an IDE. */
    private static Path jar() {
        String property = System.getProperty("fyrql.pgwire.all.jar");
        assumeTrue(property != null && !property.isEmpty(), "the build names no JAR of fyrql-pgwire-all");
        Path jar = Path.of(property);
        assumeTrue(Files.isRegularFile(jar), "there is no JAR " + jar);
        return jar.toAbsolutePath();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static List<List<String>> rows(ResultSet rs) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                row.add(rs.getString(i));
            }
            rows.add(row);
        }
        rs.close();
        return rows;
    }

    private static List<List<String>> query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return rows(s.executeQuery(sql));
        }
    }

    @Test
    void theJarStartsWithJavaJarAndAnswersAFederatedQuery() throws Exception {
        Path jar = jar();
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("orders.csv"), "id,customer,amount\n1,1,10.50\n2,2,20.00\n3,1,5.25\n",
                StandardCharsets.UTF_8);
        Files.writeString(data.resolve("customers.json"), """
                [{"id": 1, "name": "Ann", "country": "DE"}, {"id": 2, "name": "Bob", "country": "FR"}]""",
                StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM (VALUES (1, 2), (1, 3), (2, 1), (3, 4)) v(order_id, qty)) TO '"
                    + path(data.resolve("items.parquet")) + "' (FORMAT parquet)");
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
                engine:
                  codegen: always
                """, StandardCharsets.UTF_8);

        try (Child server = Child.start(dir.resolve("server.log"), List.of(java(), "-jar", jar.toString(),
                config.toString(), "--port", "0"));
                Connection c = server.connect("files")) {
            List<String> databases = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getCatalogs()) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            }
            assertTrue(databases.containsAll(List.of("files", "docs", "pq")), databases.toString());
            assertEquals(List.of(List.of("Ann", "73.5"), List.of("Bob", "20")), query(c, FEDERATED));
            String plan = String.join("\n", query(c, "EXPLAIN " + FEDERATED).stream().map(r -> r.get(0)).toList());
            assertTrue(plan.contains("[codegen"), "the expressions are compiled:\n" + plan);
            try (PreparedStatement p = c.prepareStatement("SELECT name FROM docs.main.customers WHERE id = ?")) {
                p.setLong(1, 2);
                assertEquals(List.of(List.of("Bob")), rows(p.executeQuery()));
            }
            String file = path(data.resolve("orders.csv"));
            SQLException denied = assertThrows(SQLException.class, () -> query(c, "SELECT * FROM '" + file + "'"));
            assertEquals("42501", denied.getSQLState(), "without fileAccess in the file");
            assertFalse(server.errors().contains("Exception"), server.errors());
        }
    }

    @Test
    void aJdbcDriverGoesOnTheClassPathNextToTheJar() throws Exception {
        Path jar = jar();
        String h2 = "jdbc:h2:file:" + path(dir.resolve("countries"));
        try (Connection c = DriverManager.getConnection(h2); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE \"countries\" (\"code\" VARCHAR(2), \"name\" VARCHAR(20))");
            s.execute("INSERT INTO \"countries\" VALUES ('DE', 'Germany'), ('FR', 'France')");
        }
        Path config = Files.writeString(dir.resolve("fyrql.yaml"), """
                sources:
                  - name: db
                    type: jdbc
                    url: "%s"
                """.formatted(h2), StandardCharsets.UTF_8);
        Path driver = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Child server = Child.start(dir.resolve("server.log"), List.of(java(), "-cp",
                jar + File.pathSeparator + driver, "net.fyrql.pgwire.PgWireServerMain", config.toString(), "--port",
                "0"));
                Connection c = server.connect("db")) {
            assertEquals(List.of(List.of("France"), List.of("Germany")), query(c,
                    "SELECT name FROM countries ORDER BY name"));
            assertTrue(query(c, "SELECT relname FROM pg_class WHERE relname = 'countries'").size() == 1);
        }
    }

    @Test
    void theJarCarriesTheServerAndTheLicensesAndNoArrow() throws IOException {
        Path location = jar();
        try (JarFile jar = new JarFile(location.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();
            assertEquals(List.of(), names.stream().filter(n -> n.matches(".*\\.(dll|so|dylib|jnilib)(\\.\\d+)*"))
                    .toList(), "native libraries");
            for (String original : ORIGINALS) {
                String prefix = original.replace('.', '/') + "/";
                assertTrue(names.stream().noneMatch(n -> n.startsWith(prefix)), "classes of " + original);
            }
            for (String absent : ABSENT) {
                assertTrue(names.stream().noneMatch(n -> n.startsWith(absent)), absent);
            }
            assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/versions/")), "classes for newer JDKs");
            assertTrue(names.stream().noneMatch(n -> n.endsWith("module-info.class")), "module descriptors");
            for (String renamed : List.of("jackson", "config/jackson", "config/snakeyaml", "jsqlparser", "fastcsv",
                    "janino", "parquet")) {
                assertTrue(names.contains("net/fyrql/internal/" + renamed + "/"), renamed);
            }
            assertTrue(names.contains("net/fyrql/pgwire/PgWireServerMain.class"));
            Manifest manifest = jar.getManifest();
            assertEquals("net.fyrql.pgwire.PgWireServerMain", manifest.getMainAttributes().getValue("Main-Class"));
            assertEquals("net.fyrql.pgwire.all", manifest.getMainAttributes().getValue("Automatic-Module-Name"));
            assertEquals(Set.of("net.fyrql.connector.csv.CsvConnectorFactory",
                    "net.fyrql.connector.json.JsonConnectorFactory",
                    "net.fyrql.connector.parquet.ParquetConnectorFactory",
                    "net.fyrql.connector.http.HttpConnectorFactory",
                    "net.fyrql.connector.odata.ODataConnectorFactory",
                    "net.fyrql.connector.jdbc.JdbcConnectorFactory",
                    "net.fyrql.connector.memory.MemoryConnectorFactory",
                    "net.fyrql.connector.s3.S3ConnectorFactory",
                    "net.fyrql.connector.iceberg.IcebergConnectorFactory",
                    "net.fyrql.connector.xlsx.XlsxConnectorFactory"),
                    Set.copyOf(services(jar, "net.fyrql.connector.ConnectorFactory")));
            assertEquals(List.of("net.fyrql.codegen.JaninoCompiler"),
                    services(jar, "net.fyrql.exec.compiled.ExpressionCompiler"));
            for (String license : List.of("THIRD-PARTY.txt", "apache-2.0-LICENSE.txt", "jackson-NOTICE.txt",
                    "fastcsv-LICENSE.txt", "janino-LICENSE.txt", "parquet-java-LICENSE.txt",
                    "parquet-java-NOTICE.txt", "jts-LICENSE.txt", "slf4j-LICENSE.txt")) {
                assertTrue(names.contains("META-INF/licenses/" + license), license);
            }
        }
    }

    private static List<String> services(JarFile jar, String service) throws IOException {
        JarEntry entry = jar.getJarEntry("META-INF/services/" + service);
        assertTrue(entry != null, service);
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }
    }
}
