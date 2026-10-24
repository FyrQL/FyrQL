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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.FileStores;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How long a scan of columns of a Parquet file takes on one thread: the
 * reader of the connector with the column readers of parquet-java, the
 * same reader with its own decoder of pages, and DuckDB summing the same
 * columns. Only with {@code fyrql.parquet.probe} set to the file, such as
 * {@code lineitem.parquet} of TPC-H; the report goes to
 * {@code target/parquet-probe.md}.
 */
@EnabledIfSystemProperty(named = "fyrql.parquet.probe", matches = ".+")
class ParquetScanProbeTest {

    private static final int WARMUP = 2;
    private static final int RUNS = 5;
    /** The columns of TPC-H the scans read, alone and as Q6 and Q1 read them. */
    private static final List<List<String>> LINEITEM = List.of(List.of("l_orderkey"), List.of("l_quantity"),
            List.of("l_extendedprice"), List.of("l_discount"), List.of("l_tax"), List.of("l_shipdate"),
            List.of("l_returnflag"), List.of("l_linestatus"), List.of("l_shipmode"), List.of("l_comment"),
            List.of("l_quantity", "l_extendedprice", "l_discount", "l_shipdate"),
            List.of("l_returnflag", "l_linestatus", "l_quantity", "l_extendedprice", "l_discount", "l_tax",
                    "l_shipdate"));
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static volatile long sink;

    private interface Stage {
        long run() throws Exception;
    }

    private record Timing(double millis, double megabytes) {
    }

    @Test
    void probe() throws Exception {
        Path file = Path.of(System.getProperty("fyrql.parquet.probe")).toAbsolutePath();
        ParquetFile parquet = ParquetFile.open(FileStores.local(file.getParent()), file.toString(),
                ParquetFiles.READ_AHEAD);
        Schema schema = parquet.schema();
        List<List<String>> sets = new ArrayList<>();
        for (List<String> set : LINEITEM) {
            if (set.stream().allMatch(c -> schema.indexOf(c) >= 0)) {
                sets.add(set);
            }
        }
        if (sets.isEmpty()) {
            for (int i = 0; i < schema.size(); i++) {
                sets.add(List.of(schema.name(i)));
            }
        }
        List<Integer> every = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            every.add(i);
        }
        List<String> decoded;
        try (RowGroupReader r = parquet.read(0, parquet.rows(0), schema, ColumnPath.whole(every), List.of(), 2048,
                null, true)) {
            while (r.next() != null) {
                sink++;
            }
            decoded = r.decodedLeaves();
        }
        List<String> others = schema.fields().stream().map(Schema.Field::name).filter(c -> !decoded.contains(c))
                .toList();
        StringBuilder out = new StringBuilder("# Scan of " + file.getFileName() + " on one thread\n\n")
                .append("Columns the decoder does not read in row group 0: ")
                .append(others.isEmpty() ? "none" : String.join(", ", others)).append(".\n\n")
                .append(parquet.rows()).append(" rows in ").append(parquet.rowGroups()).append(" row groups. ")
                .append("Median of ").append(RUNS).append(" runs in ms, MB allocated in parentheses.\n\n")
                .append("| Columns | column readers | page decoder | DuckDB |\n| --- | ---: | ---: | ---: |\n");
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement s = duck.createStatement()) {
            s.execute("SET threads = 1");
            for (List<String> set : sets) {
                List<Integer> indexes = set.stream().map(schema::indexOf).toList();
                Schema output = schema.select(indexes);
                List<ColumnPath> paths = ColumnPath.whole(indexes);
                Timing readers = time(() -> scan(parquet, output, paths, false));
                Timing decoder = time(() -> scan(parquet, output, paths, true));
                String sql = "SELECT " + String.join(", ", indexes.stream().map(i -> aggregate(schema, i)).toList())
                        + " FROM read_parquet('" + file.toString().replace('\\', '/') + "')";
                Timing duckdb = time(() -> {
                    try (ResultSet rs = s.executeQuery(sql)) {
                        rs.next();
                        return rs.getObject(1).hashCode();
                    }
                });
                out.append("| ").append(String.join(", ", set));
                for (Timing t : List.of(readers, decoder, duckdb)) {
                    out.append(String.format(Locale.ROOT, " | %.0f (%.0f)", t.millis(), t.megabytes()));
                }
                out.append(" |\n");
                System.out.println(out);
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "parquet-probe.md"), out);
    }

    private static long scan(ParquetFile parquet, Schema output, List<ColumnPath> paths, boolean decodePages) {
        long n = 0;
        for (int g = 0; g < parquet.rowGroups(); g++) {
            try (RowGroupReader r = parquet.read(g, parquet.rows(g), output, paths, List.of(), 2048, null,
                    decodePages)) {
                for (Batch b = r.next(); b != null; b = r.next()) {
                    n += b.rowCount();
                }
            }
        }
        return n;
    }

    private static String aggregate(Schema schema, int column) {
        String name = schema.name(column);
        DataType type = schema.type(column);
        return type == DataType.VARCHAR || type == DataType.BLOB ? "sum(length(" + name + "))"
                : type.isNumeric() ? "sum(" + name + ")" : "max(" + name + ")";
    }

    private static Timing time(Stage stage) throws Exception {
        double[] millis = new double[RUNS];
        double[] megabytes = new double[RUNS];
        for (int r = -WARMUP; r < RUNS; r++) {
            System.gc();
            long allocated = THREADS.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            sink += stage.run();
            long elapsed = System.nanoTime() - start;
            if (r >= 0) {
                millis[r] = elapsed / 1e6;
                megabytes[r] = (THREADS.getCurrentThreadAllocatedBytes() - allocated) / 1e6;
            }
        }
        Arrays.sort(millis);
        Arrays.sort(megabytes);
        return new Timing(millis[RUNS / 2], megabytes[RUNS / 2]);
    }
}
