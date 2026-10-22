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

import net.fyrql.connector.Batches;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.compiled.InterpretedKeys;
import net.fyrql.exec.compiled.JoinTable;
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
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * The hash table of a join built from the batches of its build side, as
 * the readers hand them over: batches of 2048 rows whose keys are hashed
 * already. The keys are random {@code BIGINT}s, about four rows to a key,
 * as {@code l_orderkey} in {@code lineitem}. {@code narrow} has the key and
 * an {@code INTEGER}; {@code wide} adds a {@code DECIMAL(12, 2)} and a
 * {@code VARCHAR} of 10 to 40 characters. {@link #concat} copies the
 * batches into one, {@link #chain} chains the rows of one batch,
 * {@link #build} does both.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar JoinBuildBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class JoinBuildBenchmark {

    private static final int[] KEYS = {0};

    private static final Schema NARROW = Schema.of(new Schema.Field("key", DataType.BIGINT),
            new Schema.Field("quantity", DataType.INTEGER));
    private static final Schema WIDE = Schema.of(new Schema.Field("key", DataType.BIGINT),
            new Schema.Field("quantity", DataType.INTEGER), new Schema.Field("price", DataType.decimal(12, 2)),
            new Schema.Field("comment", DataType.VARCHAR));

    @Param({"1000000", "6000000"})
    public int rows;

    @Param({"narrow", "wide"})
    public String shape;

    private Schema schema;
    private List<Batch> batches;
    private List<int[]> hashes;
    private Batch whole;
    private List<int[]> wholeHashes;

    @Setup
    public void setUp() {
        schema = shape.equals("wide") ? WIDE : NARROW;
        SplittableRandom random = new SplittableRandom(20261014);
        batches = new ArrayList<>();
        hashes = new ArrayList<>();
        for (int from = 0; from < rows; from += CodegenBenchmark.BATCH) {
            int n = Math.min(CodegenBenchmark.BATCH, rows - from);
            long[] key = new long[n];
            int[] quantity = new int[n];
            long[] price = new long[n];
            BytesVector.Builder comment = new BytesVector.Builder(DataType.VARCHAR, n);
            for (int i = 0; i < n; i++) {
                key[i] = random.nextInt(Math.max(1, rows / 4));
                quantity[i] = 1 + random.nextInt(50);
                price[i] = 90_000 + random.nextLong(10_410_000);
                comment.appendString("x".repeat(10 + random.nextInt(31)));
            }
            List<Vector> columns = new ArrayList<>(List.of(BigIntVector.wrap(DataType.BIGINT, key, null),
                    IntVector.wrap(DataType.INTEGER, quantity, null)));
            if (schema == WIDE) {
                columns.add(DecimalVector.wrap(DataType.decimal(12, 2), price, null));
                columns.add(comment.build());
            }
            Batch b = new Batch(schema, n, columns);
            int[] h = new int[n];
            InterpretedKeys.INSTANCE.hash(b, KEYS, h);
            batches.add(b);
            hashes.add(h);
        }
        whole = Batches.concat(schema, batches);
        int[] all = new int[rows];
        InterpretedKeys.INSTANCE.hash(whole, KEYS, all);
        wholeHashes = List.of(all);
    }

    /** Copies the batches into one. */
    @Benchmark
    public Batch concat() {
        return Batches.concat(schema, batches);
    }

    /** Chains the rows of the build side in one batch, with a copy of their hashes. */
    @Benchmark
    public JoinTable chain() {
        return new JoinTable(schema, List.of(whole), wholeHashes, KEYS);
    }

    /** Copies the batches into one and chains its rows. */
    @Benchmark
    public JoinTable build() {
        return new JoinTable(schema, batches, hashes, KEYS);
    }
}
