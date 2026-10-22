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

import net.fyrql.FyrQL;
import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.jdbc.AllTypesTable;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server on a free port of the loopback address with an engine over a
 * source {@code mem} of memory: the table {@code t} of
 * {@link AllTypesTable} (with a {@code HUGEINT} of 38 digits, the most
 * Arrow holds), {@code numbers} with 1000 rows in batches of 64, and
 * {@code tiny} with two rows. Closing it closes the clients it opened, the
 * server, which fails if a buffer of Arrow was not released, and the
 * engine.
 */
final class TestServer implements AutoCloseable {

    /** The rows of {@code numbers}. */
    static final int NUMBERS = 1000;

    /** The largest {@code HUGEINT} that fits {@code Decimal128(38, 0)}. */
    static final BigInteger MAX_DECIMAL_38 = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

    final FyrQL engine;
    final FlightSqlServer server;
    final BufferAllocator allocator = new RootAllocator();
    private final List<AutoCloseable> clients = new ArrayList<>();

    TestServer() throws IOException {
        this(ServerConfig.defaults().withPort(0), FyrQL.builder());
    }

    TestServer(ServerConfig config) throws IOException {
        this(config, FyrQL.builder());
    }

    TestServer(ServerConfig config, FyrQL.Builder builder) throws IOException {
        List<Object[]> numbers = new ArrayList<>();
        for (long i = 0; i < NUMBERS; i++) {
            numbers.add(new Object[] {i, "n" + i});
        }
        engine = builder.register(MemoryConnector.builder("mem")
                        .table("t", AllTypesTable.SCHEMA, allTypes())
                        .table("numbers", Schema.of(new Schema.Field("n", DataType.BIGINT),
                                new Schema.Field("s", DataType.VARCHAR)), numbers)
                        .table("tiny", Schema.of(new Schema.Field("k", DataType.INTEGER),
                                new Schema.Field("v", DataType.VARCHAR)), List.of(new Object[] {1, "one"},
                                new Object[] {2, "two"}))
                        .batchSize(64)
                        .build())
                .build();
        try {
            server = FlightSqlServer.builder(engine).config(config).build().start();
        } catch (IOException | RuntimeException e) {
            engine.close();
            throw e;
        }
    }

    /** Returns the rows of {@link AllTypesTable} with a {@code HUGEINT} of 38 digits instead of 39. */
    static List<Object[]> allTypes() {
        int hi = AllTypesTable.SCHEMA.indexOf("hi");
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : AllTypesTable.ROWS) {
            Object[] copy = row.clone();
            if (copy[hi] != null && ((BigInteger) copy[hi]).bitLength() > 126) {
                copy[hi] = MAX_DECIMAL_38;
            }
            rows.add(copy);
        }
        return rows;
    }

    /** Returns the location of the server for a client. */
    Location location() {
        return server.config().tls() ? Location.forGrpcTls("localhost", server.port())
                : Location.forGrpcInsecure("localhost", server.port());
    }

    /** Opens a client of Flight SQL, which the server closes. */
    FlightSqlClient client() {
        return client(FlightClient.builder(allocator, location()).build());
    }

    /** Wraps a client of Flight, which the server closes. */
    FlightSqlClient client(FlightClient flight) {
        FlightSqlClient client = new FlightSqlClient(flight);
        clients.add(client);
        return client;
    }

    /** Reads the batches of all endpoints of a flight. */
    @SuppressWarnings("try") // FlightStream.close() may throw InterruptedException.
    static List<Batch> batches(FlightSqlClient client, FlightInfo info, CallOption... options) throws Exception {
        List<Batch> batches = new ArrayList<>();
        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = client.getStream(endpoint.getTicket(), options)) {
                while (stream.next()) {
                    batches.add(ArrowToBatch.batch(stream.getRoot(), stream.getDictionaryProvider()));
                }
            }
        }
        return batches;
    }

    /** Returns the schema of a flight, which the server always sends. */
    static org.apache.arrow.vector.types.pojo.Schema schema(FlightInfo info) {
        return info.getSchemaOptional().orElseThrow();
    }

    /** Reads the rows of all endpoints of a flight. */
    static List<Object[]> rows(FlightSqlClient client, FlightInfo info, CallOption... options) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (Batch b : batches(client, info, options)) {
            rows.addAll(b.rows());
        }
        return rows;
    }

    /** Returns rows with values that compare by content: bytes as hex, a map as its list of entries in order. */
    static List<List<Object>> comparable(List<Object[]> rows) {
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>(row.length);
            for (Object v : row) {
                values.add(comparable(v));
            }
            out.add(values);
        }
        return out;
    }

    private static Object comparable(Object value) {
        return switch (value) {
            case null -> null;
            case byte[] bytes -> "0x" + HexFormat.of().formatHex(bytes);
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                for (Object v : list) {
                    out.add(comparable(v));
                }
                yield out;
            }
            case Map<?, ?> map -> {
                List<Object> out = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.add(List.of(String.valueOf(comparable(e.getKey())), String.valueOf(comparable(e.getValue()))));
                }
                yield out;
            }
            default -> value;
        };
    }

    /** Asserts that a call fails with a status and a message that starts with a text. */
    static FlightRuntimeException fails(FlightStatusCode code, String message, Executable call) {
        FlightRuntimeException e = assertThrows(FlightRuntimeException.class, call);
        assertEquals(code, e.status().code(), e.toString());
        assertTrue(String.valueOf(e.status().description()).startsWith(message), e.status().description());
        return e;
    }

    /**
     * Waits until the server runs no query: the server releases a query only
     * after it has sent the end of its stream, so a client that has read all
     * of its rows may still see it run for a moment.
     */
    void awaitNoQuery() throws InterruptedException {
        awaitTrue("the server runs no query", 2000, () -> server.runningQueries() == 0);
    }

    /** Waits until a condition holds, at most a time in milliseconds; fails after it. */
    static void awaitTrue(String what, long millis, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError(what + " within " + millis + " ms");
            }
            Thread.sleep(10);
        }
    }

    @Override
    public void close() {
        try {
            for (AutoCloseable c : clients) {
                try {
                    c.close();
                } catch (Exception e) {
                    throw new IllegalStateException("A client does not close", e);
                }
            }
            server.close();
            allocator.close();
        } finally {
            engine.close();
        }
    }
}
