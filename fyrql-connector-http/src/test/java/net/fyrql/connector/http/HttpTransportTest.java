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

package net.fyrql.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastHeaders.clear();
            exchange.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (exchange.getRequestURI().getPath()) {
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
    }

    @Test
    void headersThatTheClientRefuses() {
        assertThrows(IllegalArgumentException.class, () -> HttpTransport.builder().header("Host", "x"));
    }
}
