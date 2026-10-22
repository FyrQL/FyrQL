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

package net.fyrql.flight;

import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line of the server: a configuration file, the options that
 * override it, its errors, and the warning of gRPC it drops.
 */
class MainTest {

    @TempDir
    Path dir;

    private static String usage(String... args) {
        return assertThrows(FlightSqlServerMain.UsageException.class,
                () -> FlightSqlServerMain.start(args, System.out)).getMessage();
    }

    @Test
    void wrongArgumentsAreUsageErrors() {
        assertEquals("No configuration file", usage());
        assertEquals("Unknown option --verbose", usage("--verbose"));
        assertEquals("The option --port needs a value", usage("a.yaml", "--port"));
        assertEquals("The port eighty is no number", usage("a.yaml", "--port", "eighty"));
        assertEquals("More than one configuration file: a.yaml and b.yaml", usage("a.yaml", "b.yaml"));
        assertEquals("The configuration file " + dir.resolve("none.yaml") + " does not exist",
                usage(dir.resolve("none.yaml").toString()));
    }

    @Test
    @SuppressWarnings("try") // FlightSqlClient.close() may throw InterruptedException.
    void theServerStartsFromAConfigurationFile() throws Exception {
        Path file = Files.writeString(dir.resolve("fyrql.yaml"), """
                sources: []
                server:
                  port: 1
                  handleTimeout: 1m
                  sessionTimeout: 0
                """);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try (FlightSqlServerMain.Running running = FlightSqlServerMain.start(new String[] {file.toString(),
                "--port", "0"}, out);
                BufferAllocator allocator = new RootAllocator()) {
            int port = running.server().port();
            assertEquals("FyrQL Flight SQL server " + FlightSqlServer.VERSION + " listening on grpc+tcp://127.0.0.1:"
                    + port + System.lineSeparator() + "File access from SQL: none; query timeout: none;"
                    + " session timeout: none" + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
            try (FlightSqlClient client = new FlightSqlClient(FlightClient.builder(allocator,
                    Location.forGrpcInsecure("127.0.0.1", port)).build())) {
                assertEquals(List.of(List.of(1)), TestServer.comparable(TestServer.rows(client,
                        client.execute("SELECT 1 AS one"))));
            }
        }
    }

    @Test
    @SuppressWarnings("try") // FlightSqlClient.close() may throw InterruptedException.
    void theServerAllowsNoFilesFromSqlUnlessTheFileDoes() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        String inside = data.resolve("c.csv").toString().replace('\\', '/');
        String outside = dir.resolve("c.csv").toString().replace('\\', '/');
        Path closed = Files.writeString(dir.resolve("closed.yaml"), "sources: []\n");
        Path open = Files.writeString(dir.resolve("open.yaml"), "sources: []\nengine:\n  fileAccess: readWrite\n"
                + "  allowedLocations: [data]\n  queryTimeout: 30s\n  maxQueryTimeout: 5m\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try (FlightSqlServerMain.Running running = FlightSqlServerMain.start(new String[] {closed.toString(),
                "--port", "0"}, out);
                BufferAllocator allocator = new RootAllocator();
                FlightSqlClient client = new FlightSqlClient(FlightClient.builder(allocator,
                        Location.forGrpcInsecure("127.0.0.1", running.server().port())).build())) {
            TestServer.fails(FlightStatusCode.UNAUTHORIZED, "Writing " + inside + " is not allowed: the engine"
                    + " allows no access to files from SQL (fileAccess none)", () -> client.executeUpdate(
                    "COPY (SELECT 1 AS k) TO '" + inside + "'"));
        }
        bytes.reset();
        try (FlightSqlServerMain.Running running = FlightSqlServerMain.start(new String[] {open.toString(),
                "--port", "0"}, out);
                BufferAllocator allocator = new RootAllocator();
                FlightSqlClient client = new FlightSqlClient(FlightClient.builder(allocator,
                        Location.forGrpcInsecure("127.0.0.1", running.server().port())).build())) {
            assertTrue(bytes.toString(StandardCharsets.UTF_8).endsWith("File access from SQL: readWrite under "
                    + data.toRealPath() + "; query timeout: 30s, at most 5m; session timeout: 1h" + System.lineSeparator()),
                    bytes.toString(StandardCharsets.UTF_8));
            TestServer.fails(FlightStatusCode.UNAUTHORIZED, "Writing " + outside + " is not allowed: it is not under"
                    + " the allowed locations", () -> client.executeUpdate("COPY (SELECT 1 AS k) TO '" + outside
                    + "'"));
            TestServer.fails(FlightStatusCode.INVALID_ARGUMENT, "No source writes files of the format csv",
                    () -> client.executeUpdate("COPY (SELECT 1 AS k) TO '" + inside + "'"));
        }
        Path locationsOnly = Files.writeString(dir.resolve("locations.yaml"), "sources: []\nengine:\n"
                + "  allowedLocations: [data]\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlightSqlServerMain.start(
                new String[] {locationsOnly.toString(), "--port", "0"}, System.out));
        assertEquals(locationsOnly + ": Cannot build the engine: allowedLocations needs fileAccess read or readWrite,"
                + " not none", e.getMessage());
    }

    @Test
    void aServerBeyondTheLoopbackAddressNeedsSignIn() throws Exception {
        Path file = Files.writeString(dir.resolve("fyrql.yaml"), "sources: []\n");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FlightSqlServerMain.start(
                new String[] {file.toString(), "--host", "0.0.0.0"}, System.out));
        assertTrue(e.getMessage().startsWith(file + ": The server would listen on 0.0.0.0 without sign-in"),
                e.getMessage());
        Path bad = Files.writeString(dir.resolve("bad.yaml"), "sources: []\nserver:\n  prot: 1\n");
        e = assertThrows(IllegalArgumentException.class, () -> FlightSqlServerMain.start(new String[] {
            bad.toString()}, System.out));
        assertTrue(e.getMessage().startsWith(bad + ": The server has an unknown option prot"), e.getMessage());
    }

    @Test
    void theWarningAboutAStreamTheClientClosedIsDropped() {
        FlightSqlServerMain.quietClosedStreams();
        Logger logger = Logger.getLogger("io.grpc.netty.NettyServerHandler");
        LogRecord closed = new LogRecord(Level.WARNING, "Stream Error");
        closed.setThrown(Http2Exception.streamError(3, Http2Error.STREAM_CLOSED, "Stream closed before write could"
                + " take place"));
        assertFalse(logger.getFilter().isLoggable(closed));
        LogRecord other = new LogRecord(Level.WARNING, "Stream Error");
        other.setThrown(Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, "Bad frame"));
        assertTrue(logger.getFilter().isLoggable(other));
        assertTrue(logger.getFilter().isLoggable(new LogRecord(Level.WARNING, "Something else")));
    }
}
