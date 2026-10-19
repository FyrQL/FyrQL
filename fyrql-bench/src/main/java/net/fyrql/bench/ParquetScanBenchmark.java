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

package net.fyrql.bench;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * The scan of the Parquet connector over a file in the form of
 * {@code lineitem} of TPC-H: the one million rows of
 * {@link CsvScanBenchmark}, which DuckDB turns into a Parquet file with its
 * defaults (Snappy, row groups of 122,880 rows), as the files of the TPC-H
 * tests. The file is written once into the directory for temporary files
 * and deleted at the end. The scan reads either the four columns of Q6
 * (quantity, price, discount and ship date) or all sixteen, one row group
 * after the other.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar ParquetScanBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ParquetScanBenchmark {

    /** The columns the scan reads: the four of Q6, or all. */
    @Param({"FOUR", "ALL"})
    public String columns;

    private Path file;
    private ParquetConnector connector;
    private ScanRequest request;

    @Setup
    public void setUp() throws IOException, SQLException {
        Path csv = Files.createTempFile("fyrql-lineitem-", ".csv");
        file = Files.createTempFile("fyrql-lineitem-", ".parquet");
        try {
            CsvScanBenchmark.write(csv);
            StringBuilder types = new StringBuilder("{");
            for (int c = 0; c < CsvScanBenchmark.LINEITEM.size(); c++) {
                types.append(c > 0 ? ", " : "").append('\'').append(CsvScanBenchmark.LINEITEM.name(c))
                        .append("': '").append(CsvScanBenchmark.LINEITEM.type(c)).append('\'');
            }
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                    Statement s = duck.createStatement()) {
                s.execute("COPY (SELECT * FROM read_csv('" + path(csv) + "', delim = '|', header = true, columns = "
                        + types.append('}') + ")) TO '" + path(file) + "' (FORMAT parquet)");
            }
        } finally {
            Files.deleteIfExists(csv);
        }
        connector = ParquetConnector.builder("parquet").path("lineitem", file).build();
        request = ScanRequest.columns(columns.equals("FOUR") ? List.of(4, 5, 6, 10)
                : IntStream.range(0, CsvScanBenchmark.LINEITEM.size()).boxed().toList());
    }

    @TearDown
    public void tearDown() throws IOException {
        connector.close();
        Files.deleteIfExists(file);
    }

    /** Reads the file once and returns the number of rows. */
    @Benchmark
    public long scan(Blackhole bh) {
        ScanPlan plan = connector.plan(TableRef.of("parquet", "lineitem"), request);
        long rows = 0;
        for (Split split : connector.splits(plan)) {
            BatchReader reader = connector.open(split);
            try {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    rows += b.rowCount();
                    bh.consume(b);
                }
            } finally {
                reader.close();
            }
        }
        return rows;
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
