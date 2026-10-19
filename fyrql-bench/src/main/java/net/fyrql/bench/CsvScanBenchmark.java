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
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * The scan of the CSV connector over a file in the form of {@code lineitem}
 * of TPC-H: one million rows with integers, decimals, dates and texts,
 * separated by {@code |}. The file is written once into the directory for
 * temporary files and deleted at the end. The scan reads either the four
 * columns of Q6 (quantity, price, discount and ship date) or all sixteen.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar CsvScanBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CsvScanBenchmark {

    static final int ROWS = 1_000_000;

    static final Schema LINEITEM = schema("l_orderkey", DataType.INTEGER, "l_partkey", DataType.INTEGER,
            "l_suppkey", DataType.INTEGER, "l_linenumber", DataType.INTEGER, "l_quantity", money(),
            "l_extendedprice", money(), "l_discount", money(), "l_tax", money(), "l_returnflag", DataType.VARCHAR,
            "l_linestatus", DataType.VARCHAR, "l_shipdate", DataType.DATE, "l_commitdate", DataType.DATE,
            "l_receiptdate", DataType.DATE, "l_shipinstruct", DataType.VARCHAR, "l_shipmode", DataType.VARCHAR,
            "l_comment", DataType.VARCHAR);

    private static final String[] INSTRUCTIONS = {"DELIVER IN PERSON", "COLLECT COD", "NONE", "TAKE BACK RETURN"};
    private static final String[] MODES = {"REG AIR", "AIR", "RAIL", "SHIP", "TRUCK", "MAIL", "FOB"};
    private static final String[] WORDS = {"furiously", "carefully", "quickly", "slyly", "blithely", "regular",
            "special", "final", "pending", "ironic", "express", "bold", "deposits", "requests", "accounts",
            "packages", "theodolites", "foxes", "pinto", "beans", "ideas", "sleep", "wake", "haggle", "nag"};

    /** The columns the scan reads: the four of Q6, or all. */
    @Param({"FOUR", "ALL"})
    public String columns;

    private Path file;
    private CsvConnector connector;
    private ScanRequest request;

    @Setup
    public void setUp() throws IOException {
        file = Files.createTempFile("fyrql-lineitem-", ".csv");
        write(file);
        connector = CsvConnector.builder("files").delimiter('|').header(true).schema("lineitem", LINEITEM)
                .file("lineitem", file).build();
        request = ScanRequest.columns(columns.equals("FOUR") ? List.of(4, 5, 6, 10)
                : IntStream.range(0, LINEITEM.size()).boxed().toList());
    }

    @TearDown
    public void tearDown() throws IOException {
        connector.close();
        Files.deleteIfExists(file);
    }

    /** Reads the file once and returns the number of rows. */
    @Benchmark
    public long scan(Blackhole bh) {
        ScanPlan plan = connector.plan(TableRef.of("files", "lineitem"), request);
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

    /** Writes the rows in the form of the generator of TPC-H, with a header. */
    static void write(Path file) throws IOException {
        SplittableRandom random = new SplittableRandom(20261016);
        int first = (int) LocalDate.of(1992, 1, 2).toEpochDay();
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < LINEITEM.size(); c++) {
                line.append(c > 0 ? "|" : "").append(LINEITEM.name(c));
            }
            out.write(line.append('\n').toString());
            for (int r = 0; r < ROWS; r++) {
                int quantity = 1 + random.nextInt(50);
                long price = quantity * (90_000L + random.nextInt(110_000));
                int ship = first + random.nextInt(2526);
                line.setLength(0);
                line.append(1 + r / 4).append('|').append(1 + random.nextInt(200_000)).append('|')
                        .append(1 + random.nextInt(10_000)).append('|').append(1 + r % 4).append('|')
                        .append(quantity).append(".00|").append(price / 100).append('.')
                        .append(cents(price % 100)).append("|0.").append(cents(random.nextInt(11))).append("|0.")
                        .append(cents(random.nextInt(9))).append('|').append("RAN".charAt(random.nextInt(3)))
                        .append('|').append(random.nextBoolean() ? 'O' : 'F').append('|')
                        .append(LocalDate.ofEpochDay(ship)).append('|')
                        .append(LocalDate.ofEpochDay(ship + random.nextInt(-60, 60))).append('|')
                        .append(LocalDate.ofEpochDay(ship + 1 + random.nextInt(30))).append('|')
                        .append(INSTRUCTIONS[random.nextInt(INSTRUCTIONS.length)]).append('|')
                        .append(MODES[random.nextInt(MODES.length)]).append('|');
                int words = 2 + random.nextInt(5);
                for (int w = 0; w < words; w++) {
                    line.append(w > 0 ? " " : "").append(WORDS[random.nextInt(WORDS.length)]);
                }
                out.write(line.append('\n').toString());
            }
        }
    }

    private static String cents(long cents) {
        return cents < 10 ? "0" + cents : Long.toString(cents);
    }

    private static DataType money() {
        return DataType.decimal(15, 2);
    }

    private static Schema schema(Object... namesAndTypes) {
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            fields.add(new Schema.Field((String) namesAndTypes[i], (DataType) namesAndTypes[i + 1]));
        }
        return Schema.of(fields.toArray(Schema.Field[]::new));
    }
}
