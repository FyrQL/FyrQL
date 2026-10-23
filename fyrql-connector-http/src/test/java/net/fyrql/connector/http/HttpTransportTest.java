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
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transport of HTTP sources against a server of the JDK on a free port of the loopback address. */
class HttpTransportTest {

    private HttpServer server;
    private HttpTransport transport;
    /** The headers and body of the last request, the header names in lower case. */
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile String lastBody;
    private volatile String lastMethod;
    private volatile String lastPath;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastHeaders.clear();
            exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            switch (exchange.getRequestURI().getPath()) {
                case "/same" -> redirect(exchange, 302, "/ok");
                case "/see" -> redirect(exchange, 303, "/ok");
                case "/again" -> redirect(exchange, 307, "/token");
                case "/loop" -> redirect(exchange, 302, "/loop");
                // The same server under another name is another origin.
                case "/away" -> redirect(exchange, 302, "http://" + (url("").getHost().equals("localhost")
                        ? "127.0.0.1" : "localhost") + ":" + server.getAddress().getPort() + "/ok");
                case "/ok" -> send(exchange, 200, "application/json; charset=utf-8", "{\"a\": 1}");
                case "/missing" -> send(exchange, 404, "application/json", "{\"error\": {\"message\": \"gone\"}}");
                case "/token" -> send(exchange, 200, "application/json", "{\"access_token\": \"t\"}");
                default -> send(exchange, 500, "text/plain", "x".repeat(300));
            }
        });
        server.start();
        transport = HttpTransport.builder().header("Accept-Language", "de").header("X-Source", "fyrql").build();
    }

    @AfterEach
    void stop() {
        transport.close();
        server.stop(0);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private URI url(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                + path);
    }

    @Test
    void getWithTheHeadersOfTransportAndRequest() throws IOException {
        try (HttpTransport.Response response = transport.get(url("/ok"), Map.of("x-source", "request"))) {
            assertTrue(response.ok());
            assertEquals(200, response.status());
            assertEquals("application/json", response.contentType());
            assertEquals("{\"a\": 1}", new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("application/json", lastHeaders.get("accept"));
        assertEquals("gzip", lastHeaders.get("accept-encoding"));
        assertEquals("de", lastHeaders.get("accept-language"));
        // A header of the request replaces that of the transport of the same name, in any case.
        assertEquals("request", lastHeaders.get("x-source"));
        transport.get(url("/ok"), Map.of("Accept", "application/xml")).close();
        assertEquals("application/xml", lastHeaders.get("accept"));
    }

    @Test
    void eachRequestCountsForTheScanOfTheThreadWithTheBytesOfItsBody() throws IOException {
        SourceCounters counters = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(counters)) {
            try (HttpTransport.Response response = transport.get(url("/ok"), Map.of())) {
                response.body().readAllBytes();
            }
            try (HttpTransport.Response response = transport.getAllowingErrors(url("/missing"), Map.of(), "query")) {
                assertEquals("{\"error\": {\"message\": \"gone\"}}", response.excerpt());
            }
        }
        transport.get(url("/ok"), Map.of()).close();
        SourceStatistics read = counters.snapshot();
        assertEquals(2, read.requests());
        assertEquals("{\"a\": 1}".length() + "{\"error\": {\"message\": \"gone\"}}".length(), read.bytes());
    }

    @Test
    void errorStatus() {
        HttpSourceException e = assertThrows(HttpSourceException.class, () -> transport.get(url("/fail"), Map.of()));
        assertEquals(500, e.status());
        assertEquals("GET " + url("/fail") + " answered with the status 500: " + "x".repeat(200) + " ...",
                e.getMessage());
        try (HttpTransport.Response response = transport.getAllowingErrors(url("/missing"), Map.of())) {
            assertFalse(response.ok());
            assertEquals(404, response.status());
            assertEquals("{\"error\": {\"message\": \"gone\"}}", response.excerpt());
        }
    }

    @Test
    void post() throws IOException {
        byte[] form = "grant_type=client_credentials&scope=a+b".getBytes(StandardCharsets.UTF_8);
        try (HttpTransport.Response response = transport.post(url("/token"), "application/x-www-form-urlencoded",
                form, Map.of("Authorization", "Basic xyz"))) {
            assertEquals(200, response.status());
            assertEquals("{\"access_token\": \"t\"}",
                    new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("grant_type=client_credentials&scope=a+b", lastBody);
        assertEquals("application/x-www-form-urlencoded", lastHeaders.get("content-type"));
        assertEquals("Basic xyz", lastHeaders.get("authorization"));
        try (HttpTransport.Response response = transport.post(url("/missing"), "text/plain", new byte[0],
                Map.of())) {
            assertEquals(404, response.status());
        }
    }

    @Test
    void noAnswer() throws IOException {
        URI closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = URI.create("http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort()
                    + "/ok");
        }
        HttpSourceException e = assertThrows(HttpSourceException.class, () -> transport.get(closed, Map.of()));
        assertEquals(-1, e.status());
        assertTrue(e.getMessage().startsWith("GET " + closed + " failed: "), e.getMessage());
        URI withKey = URI.create(closed + "?apikey=secret#part");
        e = assertThrows(HttpSourceException.class, () -> transport.get(withKey, Map.of()));
        assertTrue(e.getMessage().startsWith("GET " + closed + " failed: "), e.getMessage());
        assertFalse(e.getMessage().contains("secret"), e.getMessage());
        assertEquals(withKey, e.uri());
    }

    @Test
    void redirectsKeepTheHeadersOnlyOnTheOriginOfTheRequest() throws IOException {
        try (HttpTransport t = HttpTransport.builder().header("Authorization", "Bearer secret")
                .header("Accept-Language", "de").build()) {
            t.get(url("/same"), Map.of("X-Key", "k")).close();
            assertEquals("/ok", lastPath);
            assertEquals("Bearer secret", lastHeaders.get("authorization"));
            assertEquals("k", lastHeaders.get("x-key"));
            t.get(url("/away"), Map.of("X-Key", "k")).close();
            assertEquals("/ok", lastPath);
            assertEquals(null, lastHeaders.get("authorization"));
            assertEquals(null, lastHeaders.get("x-key"));
            assertEquals("de", lastHeaders.get("accept-language"));
            assertEquals("gzip", lastHeaders.get("accept-encoding"));
            HttpSourceException e = assertThrows(HttpSourceException.class,
                    () -> t.get(url("/loop?key=secret"), Map.of()));
            assertEquals("GET " + url("/loop") + " was redirected more than 5 times", e.getMessage());
            byte[] body = "body".getBytes(StandardCharsets.UTF_8);
            try (HttpTransport.Response response = t.post(url("/again"), "text/plain", body, Map.of())) {
                assertEquals(200, response.status());
            }
            assertEquals("POST /token body", lastMethod + " " + lastPath + " " + lastBody);
            t.post(url("/see"), "text/plain", body, Map.of()).close();
            assertEquals("GET /ok ", lastMethod + " " + lastPath + " " + lastBody);
        }
    }

    @Test
    void anErrorStatusNamesTheUrlWithoutItsQuery() {
        HttpSourceException e = assertThrows(HttpSourceException.class,
                () -> transport.get(url("/missing?apikey=secret"), Map.of()));
        assertEquals("GET " + url("/missing") + " answered with the status 404: {\"error\": {\"message\": \"gone\"}}",
                e.getMessage());
    }

    @Test
    void headersThatTheClientRefuses() {
        assertThrows(IllegalArgumentException.class, () -> HttpTransport.builder().header("Host", "x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HttpTransport.builder().header("Authorization", "Bearer secret\r\nX-Other: y"));
        assertEquals("The header Authorization has a value that a request cannot carry, such as one with a line break",
                e.getMessage());
        assertEquals(null, e.getCause());
        e = assertThrows(IllegalArgumentException.class,
                () -> transport.get(url("/ok"), Map.of("X-Key", "secret\nx")));
        assertFalse(e.getMessage().contains("secret"), e.getMessage());
        assertEquals(null, e.getCause());
    }

    @Test
    void aUrlIsShownWithoutUserInformationQueryAndFragment() {
        assertEquals("https://example.com:8443/a/b%20c",
                HttpTransport.shown(URI.create("https://user:pw@example.com:8443/a/b%20c?key=secret#f")));
        assertEquals("http://example.com", HttpTransport.shown(URI.create("http://example.com?key=secret")));
        assertEquals("mailto:", HttpTransport.shown(URI.create("mailto:a@example.com")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HttpTransport.url("http://example.com/a b?key=secret", "The URL of the table t"));
        assertEquals("The URL of the table t is not a URL: Illegal character in path at index 20", e.getMessage());
    }
}
