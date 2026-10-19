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

import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.ArrowTypes;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.flight.FlightSqlServer;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JAR of {@code fyrql-flight-sql-all} as a user starts it: with
 * {@code java -jar} in a process of its own, whose manifest opens the
 * package of the JDK that Arrow needs, and with its class name and a JDBC
 * driver next to it on the class path. A client of Flight SQL reads the
 * catalog, a query over CSV, JSON and Parquet with compiled expressions, a
 * prepared statement, a cancelled stream and a query with H2; the JAR
 * carries the server, the libraries it renames, their licenses and no
 * native code.
 */
class ShadedFlightSqlServerTest {

    /** The original packages of the renamed libraries, put together so that no tool rewrites them. */
    private static final List<String> ORIGINALS = List.of(String.join(".", "org", "apache", "parquet"),
            String.join(".", "org", "apache", "hadoop"), String.join(".", "shaded", "parquet"),
            String.join(".", "org", "codehaus"), String.join(".", "com", "fasterxml"),
            String.join(".", "org", "yaml"), String.join(".", "net", "sf", "jsqlparser"),
            String.join(".", "de", "siegmar"), String.join(".", "io", "airlift"),
            String.join(".", "org", "locationtech"));

    private static final Pattern LISTENING = Pattern.compile(
            "FyrQL Flight SQL server (\\S+) listening on grpc\\+tcp://127\\.0\\.0\\.1:(\\d+)");

    private static final String FEDERATED = """
            SELECT c.name, sum(o.amount * i.qty) AS total
            FROM orders o
            JOIN customers c ON o.customer = c.id
            JOIN items i ON i.order_id = o.id
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
                assertEquals(FlightSqlServer.VERSION, m.group(1));
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

        @Override
        public void close() {
            stop(process);
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
        String property = System.getProperty("fyrql.flight.all.jar");
        assumeTrue(property != null && !property.isEmpty(), "the build names no JAR of fyrql-flight-sql-all");
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

    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    private static List<Object[]> rows(FlightSqlClient client, FlightInfo info) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = client.getStream(endpoint.getTicket())) {
                while (stream.next()) {
                    rows.addAll(ArrowToBatch.batch(stream.getRoot(), stream.getDictionaryProvider()).rows());
                }
            }
        }
        return rows;
    }

    private static List<Object> column(List<Object[]> rows) {
        return rows.stream().map(r -> r[0]).toList();
    }

    private static FlightSqlClient client(BufferAllocator allocator, Child server) {
        return new FlightSqlClient(FlightClient.builder(allocator, Location.forGrpcInsecure("127.0.0.1",
                server.port())).build());
    }

    @Test
    @SuppressWarnings("try") // FlightSqlClient.close() and FlightStream.close() may throw InterruptedException.
    void theJarStartsWithJavaJarAndAnswersAFederatedQuery() throws Exception {
        Path jar = jar();
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("orders.csv"), "id,customer,amount\n1,1,10.50\n2,2,20.00\n3,1,5.25\n",
                StandardCharsets.UTF_8);
        StringBuilder numbers = new StringBuilder("n\n");
        for (int n = 0; n < 100; n++) {
            numbers.append(n).append('\n');
        }
        Files.writeString(data.resolve("numbers.csv"), numbers, StandardCharsets.UTF_8);
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
                BufferAllocator allocator = new RootAllocator();
                FlightSqlClient client = client(allocator, server)) {
            assertEquals(List.of("files", "docs", "pq"), column(rows(client, client.getCatalogs())));

            List<Object[]> totals = rows(client, client.execute(FEDERATED));
            assertEquals(2, totals.size());
            // The CSV connector reads the amounts as DOUBLE.
            assertEquals(List.of("Ann", 73.5), List.of(totals.get(0)));
            assertEquals(List.of("Bob", 20.0), List.of(totals.get(1)));
            String plan = String.join("\n", column(rows(client, client.execute("EXPLAIN " + FEDERATED))).stream()
                    .map(String::valueOf).toList());
            assertTrue(plan.contains("[codegen"), "the expressions are compiled:\n" + plan);

            try (FlightSqlClient.PreparedStatement p = client.prepare("SELECT name FROM customers WHERE id = ?")) {
                Schema parameters = ArrowTypes.toFyrQL(p.getParameterSchema());
                try (VectorSchemaRoot values = BatchToArrow.root(parameters, allocator)) {
                    BatchToArrow.fill(Batch.ofRows(parameters, List.<Object[]>of(new Object[] {2L})), values);
                    p.setParameters(values);
                    assertEquals(List.of("Bob"), column(rows(client, p.execute())));
                }
            }

            FlightInfo million = client.execute("SELECT a.n + b.n + c.n AS s FROM numbers a, numbers b, numbers c");
            try (FlightStream stream = client.getStream(million.getEndpoints().get(0).getTicket())) {
                assertTrue(stream.next());
            }
            assertEquals(List.of(100L), column(rows(client, client.execute("SELECT count(*) FROM numbers"))));

            String errors = server.errors();
            assertFalse(errors.contains("Stream Error"), "the warning of gRPC about a closed stream:\n" + errors);
            assertFalse(errors.contains(" INFO "), "only warnings and errors are logged:\n" + errors);
            assertFalse(errors.contains("Exception"), errors);
        }
    }

    @Test
    @SuppressWarnings("try") // FlightSqlClient.close() may throw InterruptedException.
    void aJdbcDriverGoesOnTheClassPathNextToTheJar() throws Exception {
        Path jar = jar();
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("customers.csv"), "id,name,country\n1,Ann,DE\n2,Bob,FR\n3,Eve,IT\n",
                StandardCharsets.UTF_8);
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
                  - name: db
                    type: jdbc
                    url: "%s"
                """.formatted(h2), StandardCharsets.UTF_8);
        Path driver = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        try (Child server = Child.start(dir.resolve("server.log"), List.of(java(),
                "--add-opens=java.base/java.nio=ALL-UNNAMED", "-cp", jar + File.pathSeparator + driver,
                "net.fyrql.flight.FlightSqlServerMain", config.toString(), "--port", "0"));
                BufferAllocator allocator = new RootAllocator();
                FlightSqlClient client = client(allocator, server)) {
            List<Object[]> rows = rows(client, client.execute("SELECT c.name, k.name AS country FROM customers c"
                    + " JOIN countries k ON k.code = c.country ORDER BY c.name"));
            assertEquals(List.of(List.of("Ann", "Germany"), List.of("Bob", "France")),
                    rows.stream().map(List::of).toList());
        }
    }

    @Test
    void theJarCarriesTheServerAndTheLicensesAndNoNativeCode() throws IOException {
        Path location = jar();
        try (JarFile jar = new JarFile(location.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();
            assertEquals(List.of(), names.stream().filter(n -> n.matches(".*\\.(dll|so|dylib|jnilib)(\\.\\d+)*"))
                    .toList(), "native libraries");
            for (String original : ORIGINALS) {
                String prefix = original.replace('.', '/') + "/";
                assertTrue(names.stream().noneMatch(n -> n.startsWith(prefix)), "classes of " + original);
                assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/services/" + original)),
                        "services of " + original);
            }
            assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/versions/")), "classes for newer JDKs");
            assertTrue(names.stream().noneMatch(n -> n.endsWith("module-info.class")), "module descriptors");
            assertTrue(names.stream().noneMatch(n -> n.matches("META-INF/[^/]*\\.(SF|DSA|RSA)")), "signature files");
            assertFalse(names.contains("META-INF/INDEX.LIST"), "an index of a single JAR");
            assertTrue(names.stream().noneMatch(n -> n.startsWith("javax/")), "annotations only for compilers");
            for (String renamed : List.of("jackson", "snakeyaml", "jsqlparser", "fastcsv", "janino", "parquet")) {
                assertTrue(names.contains("net/fyrql/internal/" + renamed + "/"), renamed);
            }
            for (String kept : List.of("org/apache/arrow/flight/sql/", "org/apache/arrow/memory/unsafe/",
                    "io/grpc/netty/", "io/netty/handler/codec/http2/", "com/google/protobuf/", "org/slf4j/simple/")) {
                assertTrue(names.contains(kept), kept);
            }

            Manifest manifest = jar.getManifest();
            assertEquals("net.fyrql.flight.FlightSqlServerMain", manifest.getMainAttributes().getValue("Main-Class"));
            assertEquals("java.base/java.nio java.base/sun.nio.ch",
                    manifest.getMainAttributes().getValue("Add-Opens"));
            assertEquals("net.fyrql.flight.all", manifest.getMainAttributes().getValue("Automatic-Module-Name"));

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
            assertTrue(services(jar, "io.grpc.ServerProvider").contains("io.grpc.netty.NettyServerProvider"));
            assertTrue(services(jar, "io.grpc.NameResolverProvider").size() >= 2, "the providers of two JARs");
            String nettyVersions = text(jar, "META-INF/io.netty.versions.properties");
            for (String artifact : List.of("netty-common", "netty-codec-http2", "netty-handler")) {
                assertTrue(nettyVersions.lines().anyMatch(l -> l.startsWith(artifact + ".version=")), artifact);
            }
            assertTrue(text(jar, "simplelogger.properties").contains("org.slf4j.simpleLogger.defaultLogLevel=warn"));

            for (String license : List.of("THIRD-PARTY.txt", "apache-2.0-LICENSE.txt", "jackson-NOTICE.txt",
                    "fastcsv-LICENSE.txt", "janino-LICENSE.txt", "parquet-java-LICENSE.txt",
                    "parquet-java-NOTICE.txt", "jts-LICENSE.txt", "slf4j-LICENSE.txt", "arrow-NOTICE.txt",
                    "grpc-NOTICE.txt", "netty-NOTICE.txt", "protobuf-LICENSE.txt", "commons-codec-NOTICE.txt")) {
                assertTrue(names.contains("META-INF/licenses/" + license), license);
            }
            String thirdParty = text(jar, "META-INF/licenses/THIRD-PARTY.txt");
            for (String library : List.of("Jackson", "SnakeYAML", "JSqlParser", "FastCSV", "Janino",
                    "Apache Parquet Java", "aircompressor", "JTS", "SLF4J", "Apache Arrow Java", "gRPC Java",
                    "Netty", "Protocol Buffers", "proto-google-common-protos", "Guava", "Gson", "PerfMark",
                    "FlatBuffers", "Apache Commons Codec")) {
                assertTrue(thirdParty.contains(library), library);
            }
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
