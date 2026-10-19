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

import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.Vector;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
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

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * The four million rows of {@link CodegenBenchmark} written into Arrow
 * vectors and read back, in batches of 2048 rows, on one thread, in three
 * shapes: {@code NARROW}, four columns of fixed width ({@code BIGINT},
 * {@code INTEGER}, {@code DECIMAL(12,2)}, {@code DATE}); {@code WIDE}, all
 * eight columns with a comment of 10 to 43 characters as a ninth, so that
 * three are {@code VARCHAR}; and {@code NESTED}, a key with an
 * {@code INTEGER[]} of up to four elements and a
 * {@code STRUCT(flag VARCHAR, tax DOUBLE)}. {@link #toArrow} fills one root
 * with every batch, as a reader or the Flight SQL server does;
 * {@link #fromArrow} reads batches from roots that hold the same rows.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar ArrowBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "--add-opens=java.base/java.nio=ALL-UNNAMED"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ArrowBenchmark {

    /** The shapes of the rows. */
    public enum Shape {
        NARROW, WIDE, NESTED
    }

    private static final String[] WORDS = {"carefully", "final", "deposits", "sleep", "quickly", "among", "the",
            "furiously", "regular", "packages", "ironic", "accounts", "boost", "blithely"};

    private static final Schema NARROW = Schema.of(lineitem(0), lineitem(1), lineitem(2), lineitem(5));

    private static final DataType.Struct ITEM = DataType.struct(new DataType.Struct.Field("flag", DataType.VARCHAR),
            new DataType.Struct.Field("tax", DataType.DOUBLE));

    private static final Schema NESTED = Schema.of(lineitem(0),
            new Schema.Field("parts", DataType.listOf(DataType.INTEGER)), new Schema.Field("item", ITEM));

    @Param({"NARROW", "WIDE", "NESTED"})
    public Shape shape;

    private BufferAllocator allocator;
    private List<Batch> batches;
    private List<VectorSchemaRoot> roots;
    private VectorSchemaRoot target;

    @Setup
    public void setUp() {
        allocator = new RootAllocator();
        batches = batches(shape);
        target = BatchToArrow.root(batches.getFirst().schema(), allocator);
        roots = new ArrayList<>(batches.size());
        for (Batch b : batches) {
            VectorSchemaRoot root = BatchToArrow.root(b.schema(), allocator);
            BatchToArrow.fill(b, root);
            roots.add(root);
        }
    }

    @TearDown
    public void tearDown() {
        target.close();
        roots.forEach(VectorSchemaRoot::close);
        allocator.close();
    }

    private static Schema.Field lineitem(int column) {
        return CodegenBenchmark.LINEITEM.fields().get(column);
    }

    /** The rows of {@link CodegenBenchmark#lineitem} in a shape. */
    static List<Batch> batches(Shape shape) {
        List<Batch> lineitem = CodegenBenchmark.lineitem();
        SplittableRandom random = new SplittableRandom(20261019);
        List<Batch> batches = new ArrayList<>(lineitem.size());
        for (Batch b : lineitem) {
            int n = b.rowCount();
            batches.add(switch (shape) {
                case NARROW -> new Batch(NARROW, n, List.of(b.column(0), b.column(1), b.column(2), b.column(5)));
                case WIDE -> wide(b, random);
                case NESTED -> nested(b, random);
            });
        }
        return batches;
    }

    private static Batch wide(Batch b, SplittableRandom random) {
        int n = b.rowCount();
        BytesVector.Builder comment = new BytesVector.Builder(DataType.VARCHAR, n);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.setLength(0);
            int length = 10 + random.nextInt(34);
            while (text.length() < length) {
                text.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
            }
            text.setLength(length);
            comment.appendString(text.toString());
        }
        List<Schema.Field> fields = new ArrayList<>(CodegenBenchmark.LINEITEM.fields());
        fields.add(new Schema.Field("comment", DataType.VARCHAR));
        List<Vector> columns = new ArrayList<>(b.columns());
        columns.add(comment.build());
        return new Batch(Schema.of(fields.toArray(Schema.Field[]::new)), n, columns);
    }

    private static Batch nested(Batch b, SplittableRandom random) {
        int n = b.rowCount();
        ListVector.Builder parts = new ListVector.Builder(DataType.listOf(DataType.INTEGER), n);
        IntVector.Builder elements = (IntVector.Builder) parts.elements();
        StructVector.Builder item = new StructVector.Builder(ITEM, n);
        BytesVector.Builder flag = (BytesVector.Builder) item.field(0);
        DoubleVector.Builder tax = (DoubleVector.Builder) item.field(1);
        BigIntVector key = (BigIntVector) b.column(0);
        IntVector quantity = (IntVector) b.column(1);
        BytesVector returnflag = (BytesVector) b.column(6);
        DoubleVector taxes = (DoubleVector) b.column(4);
        for (int i = 0; i < n; i++) {
            int count = (int) (key.getLong(i) % 5);
            for (int e = 0; e < count; e++) {
                elements.appendInt(quantity.getInt(i) + e);
            }
            parts.endList();
            flag.appendString(returnflag.getString(i));
            tax.appendDouble(taxes.getDouble(i));
            item.endRow();
        }
        return new Batch(NESTED, n, List.of(key, parts.build(), item.build()));
    }

    /** Writes every batch into the same root. */
    @Benchmark
    public long toArrow(Blackhole bh) {
        long rows = 0;
        for (Batch b : batches) {
            BatchToArrow.fill(b, target);
            bh.consume(target);
            rows += target.getRowCount();
        }
        return rows;
    }

    /** Reads a batch from every root. */
    @Benchmark
    public long fromArrow(Blackhole bh) {
        long rows = 0;
        for (VectorSchemaRoot root : roots) {
            Batch b = ArrowToBatch.batch(root);
            bh.consume(b);
            rows += b.rowCount();
        }
        return rows;
    }
}
