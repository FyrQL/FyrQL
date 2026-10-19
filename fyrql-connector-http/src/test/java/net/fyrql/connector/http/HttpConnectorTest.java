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

package net.fyrql.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP connector against a server of the JDK on a free port of the
 * loopback address; JSON is written with single quotes for readability.
 */
class HttpConnectorTest {

    private static final String PRODUCTS = "[{'id': 1, 'name': 'pen', 'price': 1.5, 'tags': ['a', 'b']},"
            + " {'id': 2, 'name': 'ink', 'price': 4, 'tags': []}, {'id': 3, 'name': null, 'price': null}]";

    private HttpServer server;
    private ExecutorService executor;
    /** The number of requests by path, and the headers of the last one. */
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    /** The body of /changing, which a test may change. */
    private volatile String changing = "[{'a': 1}]";
    /** Lets /stalling finish its body. */
    private final CountDownLatch resume = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(",", v)));
            switch (path) {
                case "/products" -> send(exchange, 200, PRODUCTS);
                case "/page" -> send(exchange, 200, "{'meta': {'page': 1}, 'data': " + PRODUCTS + ", 'next': null}");
                case "/lines" -> send(exchange, 200, "{'n': 1}\n{'n': 2}\n");
                case "/nested" -> send(exchange, 200, "[{'id': 1, 'p': {'name': 'pen', 'size': {'w': 1, 'h': 2}}},"
                        + " {'id': 2, 'p': {'name': 'ink', 'size': null}}]");
                case "/empty" -> send(exchange, 200, "[]");
                case "/changing" -> send(exchange, 200, changing);
                case "/secure" -> {
                    if ("Bearer s3cret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        send(exchange, 200, "[{'ok': true}]");
                    } else {
                        send(exchange, 401, "{'error':\n  'no token'}");
                    }
                }
                case "/zipped" -> {
                    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                    send(exchange, 200, gzip(PRODUCTS));
                }
                case "/products.json.gz" -> send(exchange, 200, gzip(PRODUCTS));
                case "/brotli" -> {
                    exchange.getResponseHeaders().add("Content-Encoding", "br");
                    send(exchange, 200, "[]");
                }
                case "/stalling" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write("[{\"n\": 1},".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        resume.await(10, TimeUnit.SECONDS);
                        out.write("{\"n\": 2}]".getBytes(StandardCharsets.UTF_8));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (IOException e) {
                        // The client went away.
                    }
                }
                case "/slow" -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    send(exchange, 200, "[]");
                }
                default -> send(exchange, 404, "<h1>404 Not Found</h1>No such resource: " + path);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        resume.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private static byte[] gzip(String json) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, json.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private int requests(String path) {
        AtomicInteger n = requests.get(path);
        return n == null ? 0 : n.get();
    }

    private static String columns(HttpConnector c, String table) {
        Schema schema = c.catalog().schema(TableRef.of(c.name(), table));
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(schema.name(i) + " " + schema.type(i).sqlName());
        }
        return String.join(", ", columns);
    }

    /** Reads the columns of a table, all rows. */
    private static List<List<Object>> read(HttpConnector c, String table, Integer... columns) {
        ScanPlan plan = c.plan(TableRef.of(c.name(), table), ScanRequest.columns(List.of(columns)));
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }

    @Test
    void anArrayIsATableWhoseColumnsAreInferredOnceOnFirstUse() {
        try (HttpConnector c = HttpConnector.builder("api").table("products", url("/products")).batchSize(2)
                .build()) {
            assertEquals(List.of("products"), c.catalog().tables(TableRef.MAIN));
            assertEquals(0, requests("/products"), "building sends no request");
            assertEquals("id BIGINT, name VARCHAR, price DOUBLE, tags VARCHAR[]", columns(c, "products"));
            assertEquals("id BIGINT, name VARCHAR, price DOUBLE, tags VARCHAR[]", columns(c, "products"));
            assertEquals(1, requests("/products"), "the columns are inferred once");
            assertEquals(List.of(Arrays.asList(1L, List.of("a", "b")), Arrays.asList(2L, List.of()),
                    Arrays.asList(3L, null)), read(c, "products", 0, 3));
            assertEquals(List.of(List.of(1.5), List.of(4.0), Arrays.asList((Object) null)), read(c, "products", 2));
            assertEquals(3, requests("/products"), "every scan is a GET of its own");
            assertEquals("gzip", lastHeaders.get("accept-encoding"));
            assertTrue(lastHeaders.get("accept").startsWith("application/json"), lastHeaders.get("accept"));
        }
    }

    @Test
    void aPointerSaysWhereInTheAnswerTheRecordsAre() {
        try (HttpConnector c = HttpConnector.builder("api").table("products", url("/page"))
                .pointer("products", "/data").table("meta", url("/page")).pointer("meta", "/meta")
                .table("lines", url("/lines")).build()) {
            assertEquals("id BIGINT, name VARCHAR, price DOUBLE, tags VARCHAR[]", columns(c, "products"));
            assertEquals(List.of(List.of("pen"), List.of("ink"), Arrays.asList((Object) null)),
                    read(c, "products", 1));
            assertEquals("page BIGINT", columns(c, "meta"));
            assertEquals(List.of(List.of(1L)), read(c, "meta", 0));
            assertEquals(List.of(List.of(1L), List.of(2L)), read(c, "lines", 0));
        }
    }

    @Test
    void ofAStructOnlyTheFieldsAskedForAreRead() {
        try (HttpConnector c = HttpConnector.builder("api").table("nested", url("/nested")).build()) {
            assertEquals("id BIGINT, p STRUCT(\"name\" VARCHAR, size STRUCT(w BIGINT, h BIGINT))",
                    columns(c, "nested"));
            ScanRequest fields = new ScanRequest(List.of(new ColumnPath(1, List.of("size", "h")),
                    new ColumnPath(1, List.of("name"))), List.of(), OptionalLong.empty(), List.of(), Optional.empty());
            ScanPlan plan = c.plan(TableRef.of("api", "nested"), fields);
            assertEquals(fields.columns(), plan.columns(), "the connector reads fields");
            List<List<Object>> rows = new ArrayList<>();
            for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
                for (int r = 0; r < b.rowCount(); r++) {
                    rows.add(Arrays.asList(b.row(r)));
                }
            }
            assertEquals(List.of(Arrays.asList(2L, "pen"), Arrays.asList(null, "ink")), rows);
        }
    }

    @Test
    void headersAreSentAndAnErrorStatusNamesTheUrlAndTheStatusButNoHeaderValue() {
        try (HttpConnector without = HttpConnector.builder("api").table("secure", url("/secure")).build();
                HttpConnector with = HttpConnector.builder("api").table("secure", url("/secure"))
                        .headers(Map.of("Authorization", "Bearer s3cret", "Accept", "application/json")).build()) {
            HttpSourceException e = assertThrows(HttpSourceException.class, () -> columns(without, "secure"));
            assertEquals(401, e.status());
            assertEquals(URI.create(url("/secure")), e.uri());
            assertEquals("GET " + url("/secure") + " answered with the status 401: {\"error\": \"no token\"}",
                    e.getMessage());
            assertEquals("ok BOOLEAN", columns(with, "secure"));
            assertEquals(List.of(List.of(true)), read(with, "secure", 0));
            assertEquals("application/json", lastHeaders.get("accept"));
            assertFalse(e.getMessage().contains("s3cret"));
        }
    }

    @Test
    void aMissingResourceIsAnErrorWithTheStatus404() {
        try (HttpConnector c = HttpConnector.builder("api").table("missing", url("/missing")).build()) {
            HttpSourceException e = assertThrows(HttpSourceException.class, () -> columns(c, "missing"));
            assertEquals(404, e.status());
            assertTrue(e.getMessage().startsWith("GET " + url("/missing") + " answered with the status 404: <h1>404"),
                    e.getMessage());
        }
    }

    @Test
    void anEmptyArrayIsTheColumnJsonWithoutRows() {
        try (HttpConnector c = HttpConnector.builder("api").table("empty", url("/empty")).build()) {
            assertEquals("json VARCHAR", columns(c, "empty"));
            assertEquals(List.of(), read(c, "empty", 0));
        }
    }

    @Test
    void aGivenSchemaSendsNoRequestUntilAScanCastsValuesAndSkipsOtherKeys() {
        Schema schema = Schema.of(new Schema.Field("price", DataType.decimal(10, 2)),
                new Schema.Field("id", DataType.INTEGER));
        try (HttpConnector c = HttpConnector.builder("api").table("products", url("/products"))
                .schema("products", schema).build()) {
            assertEquals("price DECIMAL(10,2), id INTEGER", columns(c, "products"));
            assertEquals(0, requests("/products"));
            assertEquals(List.of(List.of(1, new java.math.BigDecimal("1.50")), List.of(2,
                    new java.math.BigDecimal("4.00")), Arrays.asList(3, null)), read(c, "products", 1, 0));
            assertEquals(1, requests("/products"));
        }
    }

    @Test
    void gzipIsUncompressedWithOrWithoutContentEncoding() {
        try (HttpConnector c = HttpConnector.builder("api").table("zipped", url("/zipped"))
                .table("file", url("/products.json.gz")).table("brotli", url("/brotli")).build()) {
            assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), read(c, "zipped", 0));
            assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), read(c, "file", 0));
            HttpSourceException e = assertThrows(HttpSourceException.class, () -> columns(c, "brotli"));
            assertTrue(e.getMessage().endsWith("answered with the Content-Encoding br, of which only gzip is"
                    + " supported"), e.getMessage());
        }
    }

    @Test
    void refreshInfersTheColumnsAgainAndAnUnknownKeyIsAnErrorUntilThen() {
        try (HttpConnector c = HttpConnector.builder("api").table("changing", url("/changing")).build()) {
            assertEquals("a BIGINT", columns(c, "changing"));
            changing = "[{'a': 1, 'b': 'x'}]";
            ConversionException e = assertThrows(ConversionException.class, () -> read(c, "changing", 0));
            assertTrue(e.getMessage().contains("the object has the key \"b\", which is not a column"),
                    e.getMessage());
            c.refresh();
            assertEquals("a BIGINT, b VARCHAR", columns(c, "changing"));
            assertEquals(List.of(List.of(1L, "x")), read(c, "changing", 0, 1));
        }
    }

    @Test
    void noAnswerIsAnErrorWithoutAStatus() throws IOException {
        String slow = url("/slow");
        try (HttpConnector c = HttpConnector.builder("api").table("slow", slow).timeout(Duration.ofMillis(200))
                .build()) {
            HttpSourceException e = assertThrows(HttpSourceException.class, () -> columns(c, "slow"));
            assertEquals(-1, e.status());
            assertEquals("GET " + slow + " timed out after 200 ms", e.getMessage());
        }
        String refused;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            refused = "http://localhost:" + socket.getLocalPort() + "/products";
        }
        try (HttpConnector c = HttpConnector.builder("api").table("gone", refused).build()) {
            HttpSourceException e = assertThrows(HttpSourceException.class, () -> columns(c, "gone"));
            assertEquals(-1, e.status());
            assertTrue(e.getMessage().startsWith("GET " + refused + " failed: "), e.getMessage());
        }
    }

    @Test
    void cancelingAReaderThatWaitsOnTheBodyEndsItAtOnce() throws Exception {
        Schema schema = Schema.of(new Schema.Field("n", DataType.BIGINT));
        try (HttpConnector c = HttpConnector.builder("api").table("stalling", url("/stalling"))
                .schema("stalling", schema).build()) {
            ScanPlan plan = c.plan(TableRef.of("api", "stalling"), ScanRequest.columns(List.of(0)));
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
            try (BatchReader reader = c.open(c.splits(plan).get(0))) {
                long start = System.nanoTime();
                timer.schedule(reader::cancel, 300, TimeUnit.MILLISECONDS);
                assertThrows(RuntimeException.class, reader::next);
                long millis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(millis < 5000, "the reader waited " + millis + " ms");
                reader.cancel();
            } finally {
                timer.shutdownNow();
            }
            resume.countDown();
            assertEquals(List.of(List.of(1L), List.of(2L)), read(c, "stalling", 0), "the next scan reads all");
        }
    }

    @Test
    void theBuilderChecksWhatItIsGiven() {
        HttpConnector.Builder b = HttpConnector.builder("api");
        assertThrows(IllegalArgumentException.class, () -> b.table("t", "ftp://example.com/x.json"));
        assertThrows(IllegalArgumentException.class, () -> b.table("t", "relative/x.json"));
        assertThrows(IllegalArgumentException.class, () -> b.header("Host", "example.com"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> b.pointer("t", "data"));
        assertTrue(e.getMessage().startsWith("Invalid JSON pointer \"data\""), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> HttpConnector.builder("api").table("t", url("/products")).pointer("u", "/data").build());
        assertEquals("A pointer or schema is set for tables without a URL: [u]; the tables are [t]", e.getMessage());
        try (HttpConnector c = HttpConnector.builder("api").table("t", url("/products")).build()) {
            assertThrows(IllegalArgumentException.class, () -> c.catalog().schema(TableRef.of("api", "u")));
        }
    }

    @Test
    void theFactoryCreatesASourceFromOptions() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("http")).findFirst().orElseThrow();
        try (HttpConnector c = (HttpConnector) factory.create("api", Map.of("tables.page.url", url("/page"),
                "tables.page.pointer", "/data", "tables.secure", url("/secure"), "headers.Authorization",
                "Bearer s3cret", "timeout", "5s", "batchSize", "2"), Path.of(""))) {
            assertEquals(List.of("page", "secure"), c.catalog().tables(TableRef.MAIN));
            assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), read(c, "page", 0));
            assertEquals(List.of(List.of(true)), read(c, "secure", 0));
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("api", Map.of("timeout", "5"), Path.of("")));
        assertEquals("The option timeout of the HTTP source api must be a duration such as 500ms, 30s, 2m or PT30S: 5",
                e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("api", Map.of("tables.page.pointer", "/data"), Path.of("")));
        assertEquals("The table page of the HTTP source api needs tables.page.url", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> factory.create("api",
                Map.of("tables.page", url("/page"), "tables.page.url", url("/page")), Path.of("")));
        assertEquals("The table page of the HTTP source api has both tables.page and tables.page.url",
                e.getMessage());
    }
}
