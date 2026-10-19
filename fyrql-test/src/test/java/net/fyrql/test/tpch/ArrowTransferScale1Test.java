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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.arrow.ArrowResults;
import net.fyrql.arrow.FyrQLArrowReader;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.vector.Batch;
import net.fyrql.flight.FlightSqlServer;
import net.fyrql.flight.ServerConfig;
import net.fyrql.jdbc.FyrQLDriver;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * All rows and columns of {@code lineitem} at scale factor 1, about six
 * million rows in a Parquet file, read in six ways from one engine on all
 * threads: as the batches of the engine, through an {@link FyrQLArrowReader},
 * through the Flight SQL server on localhost with a {@link FlightSqlClient}
 * that only counts the rows of the vectors it receives, and through the JDBC
 * driver of FyrQL with {@code getObject} for every value, with the getter of
 * the type of each column ({@code getInt}, {@code getLong},
 * {@code getBigDecimal}, {@code getString}, {@code getDate}), and the same
 * with {@code getDouble} for a {@code DECIMAL}. The ways run one
 * after the other, {@link #RUNS} times; {@code target/arrow-report.md} has
 * the fastest run of each, in rows per second and in megabytes of the Arrow
 * vectors per second. Only in the profile {@code tpch}:
 * <pre>
 * mvnw -o -B -pl fyrql-test -P tpch verify -Dtest=ArrowTransferScale1Test -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "fyrql.tpch.sf", matches = "1")
class ArrowTransferScale1Test {

    private static final double SCALE_FACTOR = 1;

    /** How often each way runs, by default 3. */
    private static final int RUNS = Integer.getInteger("fyrql.tpch.runs", 3);

    private static final String SQL = "SELECT * FROM lineitem";

    /** The rows and the bytes in Arrow vectors that one way read, or 0 bytes if it read no vectors. */
    private record Read(long rows, long bytes) {
    }

    @FunctionalInterface
    private interface Transfer {
        Read run() throws Exception;
    }

    private record Way(String name, Transfer transfer) {
    }

    @Test
    @SuppressWarnings("try")
    void lineitemGoesOutInEveryWay() throws Exception {
        Path dir = TpchTest.directory(SCALE_FACTOR);
        TpchData.generate(SCALE_FACTOR, dir);
        try (FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("parquet")
                .path("lineitem", TpchData.file(dir, TpchTable.LINEITEM)).build()).build();
             BufferAllocator allocator = new RootAllocator();
             FlightSqlServer server = FlightSqlServer.builder(engine).config(ServerConfig.defaults().withPort(0))
                     .build().start();
             FlightSqlClient client = new FlightSqlClient(FlightClient.builder(allocator,
                     Location.forGrpcInsecure("127.0.0.1", server.port())).build());
             Connection connection = FyrQLDriver.connect(engine, false)) {
            List<Way> ways = List.of(new Way("Batches of the engine", () -> batches(engine)),
                    new Way("ArrowReader", () -> reader(engine, allocator)),
                    new Way("Flight SQL server on localhost", () -> flightSql(client)),
                    new Way("JDBC driver of FyrQL, getObject", () -> jdbc(connection)),
                    new Way("JDBC driver of FyrQL, typed getters", () -> jdbcTyped(connection, false)),
                    new Way("JDBC driver of FyrQL, typed getters, getDouble for DECIMAL",
                            () -> jdbcTyped(connection, true)));
            long[] fastest = new long[ways.size()];
            Arrays.fill(fastest, Long.MAX_VALUE);
            long rows = -1;
            long bytes = 0;
            for (int run = 0; run < RUNS; run++) {
                for (int w = 0; w < ways.size(); w++) {
                    long start = System.nanoTime();
                    Read read = ways.get(w).transfer().run();
                    long nanos = System.nanoTime() - start;
                    fastest[w] = Math.min(fastest[w], nanos);
                    if (rows < 0) {
                        rows = read.rows();
                    }
                    assertEquals(rows, read.rows(), ways.get(w).name());
                    bytes = Math.max(bytes, read.bytes());
                    System.out.printf(Locale.ROOT, "run %d: %s %.2f s%n", run + 1, ways.get(w).name(), nanos / 1e9);
                }
            }
            report(ways, fastest, rows, bytes);
        }
    }

    private static Read batches(FyrQL engine) {
        long rows = 0;
        try (QueryResult result = engine.sql(SQL)) {
            for (Batch b = result.nextBatch(); b != null; b = result.nextBatch()) {
                rows += b.rowCount();
            }
        }
        return new Read(rows, 0);
    }

    private static Read reader(FyrQL engine, BufferAllocator allocator) throws IOException {
        long bytes = 0;
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, SQL, allocator)) {
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            while (reader.loadNextBatch()) {
                bytes += size(root);
            }
            return new Read(reader.rowCount(), bytes);
        }
    }

    @SuppressWarnings("try")
    private static Read flightSql(FlightSqlClient client) throws Exception {
        long rows = 0;
        long bytes = 0;
        FlightInfo info = client.execute(SQL);
        try (FlightStream stream = client.getStream(info.getEndpoints().getFirst().getTicket())) {
            while (stream.next()) {
                rows += stream.getRoot().getRowCount();
                bytes += size(stream.getRoot());
            }
        }
        return new Read(rows, bytes);
    }

    private static Read jdbc(Connection connection) throws Exception {
        long rows = 0;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(SQL)) {
            int columns = rs.getMetaData().getColumnCount();
            Object last = null;
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    last = rs.getObject(c);
                }
                rows++;
            }
            if (rows > 0 && last == null) {
                throw new AssertionError("The last value of lineitem is NULL");
            }
        }
        return new Read(rows, 0);
    }

    /**
     * Reads every value with the getter of the type of its column, a
     * {@code DECIMAL} with {@code getBigDecimal} or with {@code getDouble};
     * the values add up, so that none of them is read for nothing.
     */
    private static Read jdbcTyped(Connection connection, boolean decimalAsDouble) throws Exception {
        long rows = 0;
        long sum = 0;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(SQL)) {
            ResultSetMetaData meta = rs.getMetaData();
            int columns = meta.getColumnCount();
            int[] types = new int[columns + 1];
            for (int c = 1; c <= columns; c++) {
                types[c] = meta.getColumnType(c);
            }
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    sum += switch (types[c]) {
                        case Types.INTEGER -> rs.getInt(c);
                        case Types.BIGINT -> rs.getLong(c);
                        case Types.DECIMAL -> decimalAsDouble ? (long) rs.getDouble(c) : rs.getBigDecimal(c).signum();
                        case Types.DATE -> rs.getDate(c).getTime();
                        default -> rs.getString(c).length();
                    };
                }
                rows++;
            }
        }
        if (rows > 0 && sum == 0) {
            throw new AssertionError("The values of lineitem add up to 0");
        }
        return new Read(rows, 0);
    }

    /** The bytes of the buffers of the vectors of a root. */
    private static long size(VectorSchemaRoot root) {
        long bytes = 0;
        for (FieldVector v : root.getFieldVectors()) {
            bytes += v.getBufferSize();
        }
        return bytes;
    }

    private static void report(List<Way> ways, long[] fastest, long rows, long bytes) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "# %s at scale factor 1", SQL));
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%,d rows, %.1f MB in Arrow vectors, the fastest of %d runs, %d cores.",
                rows, bytes / 1e6, RUNS, Runtime.getRuntime().availableProcessors()));
        lines.add("");
        lines.add("| Way | Seconds | Million rows/s | MB/s |");
        lines.add("| --- | ---: | ---: | ---: |");
        for (int w = 0; w < ways.size(); w++) {
            double seconds = fastest[w] / 1e9;
            lines.add(String.format(Locale.ROOT, "| %s | %.2f | %.1f | %.0f |", ways.get(w).name(), seconds,
                    rows / seconds / 1e6, bytes / seconds / 1e6));
        }
        Path report = Path.of("target", "arrow-report.md");
        Files.write(report, lines);
        lines.forEach(System.out::println);
    }
}
