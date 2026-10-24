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

package net.fyrql.arrow;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.jdbc.AllTypesTable;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batches of FyrQL to Arrow and back, directly and through the stream
 * format of Arrow: the rows come back as they were, value for value, with
 * {@code NULL} at every level of the nested types.
 */
class RoundTripTest {

    private BufferAllocator allocator;

    @BeforeEach
    void open() {
        allocator = new RootAllocator();
    }

    @AfterEach
    void close() {
        allocator.close();
    }

    /** Writes batches into one root, one after the other, and reads each back at once. */
    private List<Batch> direct(Schema schema, List<Batch> batches) {
        List<Batch> back = new ArrayList<>();
        try (VectorSchemaRoot root = BatchToArrow.root(schema, allocator)) {
            for (Batch b : batches) {
                BatchToArrow.fill(b, root);
                back.add(ArrowToBatch.batch(root));
            }
        }
        return back;
    }

    /** Writes batches as an Arrow stream and reads the stream back. */
    private List<Batch> stream(Schema schema, List<Batch> batches) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (VectorSchemaRoot root = BatchToArrow.root(schema, allocator);
                ArrowStreamWriter writer = new ArrowStreamWriter(root, null, bytes)) {
            writer.start();
            for (Batch b : batches) {
                BatchToArrow.fill(b, root);
                writer.writeBatch();
            }
            writer.end();
        }
        List<Batch> back = new ArrayList<>();
        try (ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(bytes.toByteArray()),
                allocator)) {
            while (reader.loadNextBatch()) {
                back.add(ArrowToBatch.batch(reader.getVectorSchemaRoot(), reader));
            }
        }
        return back;
    }

    private void assertRoundTrip(Schema schema, List<Batch> batches) throws IOException {
        for (List<Batch> back : List.of(direct(schema, batches), stream(schema, batches))) {
            assertEquals(batches.size(), back.size());
            for (Batch b : back) {
                assertEquals(schema, b.schema());
            }
            assertEquals(TestRows.comparable(batches), TestRows.comparable(back));
        }
    }

    @Test
    void theTableWithEveryTypeComesBack() throws IOException {
        Batch all = Batch.ofRows(AllTypesTable.SCHEMA, TestRows.allTypes());
        assertRoundTrip(AllTypesTable.SCHEMA, List.of(all, all.select(new int[] {2, 1}), all.select(new int[0]),
                all.select(new int[] {1, 1, 0})));
    }

    @Test
    void aHugeIntOf39DigitsDoesNotFit() {
        Batch all = Batch.ofRows(AllTypesTable.SCHEMA, AllTypesTable.ROWS);
        try (VectorSchemaRoot root = BatchToArrow.root(AllTypesTable.SCHEMA, allocator)) {
            ArrowTypeException e = assertThrows(ArrowTypeException.class, () -> BatchToArrow.fill(all, root));
            assertEquals("Field hi: the HUGEINT 170141183460469231731687303715884105727 has more than 38 digits"
                    + " and does not fit DECIMAL(38,0)", e.getMessage());
        }
        Schema schema = Schema.of(new Schema.Field("h", DataType.listOf(DataType.HUGEINT)));
        Batch nested = Batch.ofRows(schema, List.<Object[]>of(new Object[] {List.of(TestRows.MAX_DECIMAL_38
                .negate().subtract(BigInteger.ONE))}));
        try (VectorSchemaRoot root = BatchToArrow.root(schema, allocator)) {
            ArrowTypeException e = assertThrows(ArrowTypeException.class, () -> BatchToArrow.fill(nested, root));
            assertEquals("h.item", e.field());
        }
    }

    @Test
    void theLimitsOfEachTypeComeBack() throws IOException {
        Schema schema = Schema.of(
                new Schema.Field("h", DataType.HUGEINT),
                new Schema.Field("d0", DataType.decimal(38, 0)),
                new Schema.Field("d18", DataType.decimal(18, 18)),
                new Schema.Field("d19", DataType.decimal(19, 0)),
                new Schema.Field("f", DataType.FLOAT),
                new Schema.Field("d", DataType.DOUBLE),
                new Schema.Field("bi", DataType.BIGINT),
                new Schema.Field("ts", DataType.TIMESTAMP),
                new Schema.Field("dt", DataType.DATE),
                new Schema.Field("v", DataType.VARCHAR));
        BigInteger max = TestRows.MAX_DECIMAL_38;
        BigDecimal d18 = new BigDecimal("0.999999999999999999");
        BigDecimal d19 = new BigDecimal("9999999999999999999");
        List<Object[]> rows = List.of(
                new Object[] {max, new BigDecimal(max), d18, d19, Float.MAX_VALUE, Double.MAX_VALUE, Long.MAX_VALUE,
                        Long.MAX_VALUE, Integer.MAX_VALUE, "\u00E4\u20AC\uD83D\uDE00"},
                new Object[] {max.negate(), new BigDecimal(max.negate()), d18.negate(), d19.negate(), Float.NaN,
                        Double.NEGATIVE_INFINITY, Long.MIN_VALUE, Long.MIN_VALUE, Integer.MIN_VALUE, ""},
                new Object[] {BigInteger.ONE.shiftLeft(64), BigDecimal.ZERO, new BigDecimal("0E-18"),
                        BigDecimal.ONE, -0.0f, -0.0, -1L, -1L, -1, "a\u0000b"});
        assertRoundTrip(schema, List.of(Batch.ofRows(schema, rows)));
    }

    @Test
    void randomBatchesComeBack() throws IOException {
        SplittableRandom random = new SplittableRandom(20261019L);
        for (int run = 0; run < 300; run++) {
            int columns = 1 + random.nextInt(4);
            List<Schema.Field> fields = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                fields.add(new Schema.Field("c" + c, type(random, 3)));
            }
            Schema schema = new Schema(fields);
            List<Batch> batches = new ArrayList<>();
            for (int b = random.nextInt(4); b >= 0; b--) {
                int size = random.nextInt(4) == 0 ? 0 : random.nextInt(1, 40);
                List<Object[]> rows = new ArrayList<>();
                for (int r = 0; r < size; r++) {
                    Object[] row = new Object[columns];
                    for (int c = 0; c < columns; c++) {
                        row[c] = value(schema.type(c), random);
                    }
                    rows.add(row);
                }
                Batch batch = Batch.ofRows(schema, rows);
                if (size > 2 && random.nextBoolean()) {
                    int[] selected = random.ints(random.nextInt(size), 0, size).toArray();
                    batch = batch.select(selected);
                }
                batches.add(batch);
            }
            assertRoundTrip(schema, batches);
        }
    }

    private static final DataType[] SCALARS = {DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.INTEGER, DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE, DataType.VARCHAR,
            DataType.BLOB, DataType.DATE, DataType.TIMESTAMP, DataType.UUID};

    private static final DataType[] KEYS = {DataType.INTEGER, DataType.BIGINT, DataType.VARCHAR, DataType.DATE,
            DataType.UUID, DataType.decimal(5, 2)};

    private static DataType type(SplittableRandom random, int depth) {
        int pick = random.nextInt(depth > 0 ? 18 : 14);
        if (pick < SCALARS.length) {
            return SCALARS[pick];
        }
        if (pick == 13) {
            int precision = random.nextInt(1, 39);
            return DataType.decimal(precision, random.nextInt(precision + 1));
        }
        return switch (pick) {
            case 14, 15 -> DataType.listOf(type(random, depth - 1));
            case 16 -> {
                List<DataType.Struct.Field> fields = new ArrayList<>();
                for (int f = random.nextInt(1, 4); f > 0; f--) {
                    fields.add(new DataType.Struct.Field("f" + fields.size(), type(random, depth - 1)));
                }
                yield new DataType.Struct(fields);
            }
            default -> DataType.mapOf(KEYS[random.nextInt(KEYS.length)], type(random, depth - 1));
        };
    }

    private static Object value(DataType type, SplittableRandom random) {
        if (random.nextInt(6) == 0) {
            return null;
        }
        return nonNull(type, random);
    }

    private static Object nonNull(DataType type, SplittableRandom random) {
        return switch (type) {
            case DataType.Decimal d -> {
                BigInteger max = d.maxUnscaled();
                BigInteger unscaled = switch (random.nextInt(4)) {
                    case 0 -> max;
                    case 1 -> max.negate();
                    case 2 -> BigInteger.ZERO;
                    default -> new BigInteger(max.bitLength() + 1, new java.util.Random(random.nextLong()))
                            .mod(max.add(BigInteger.ONE)).multiply(BigInteger.valueOf(random.nextBoolean() ? 1 : -1));
                };
                yield new BigDecimal(unscaled, d.scale());
            }
            case DataType.ListType l -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    list.add(value(l.element(), random));
                }
                yield list;
            }
            case DataType.Struct s -> {
                List<Object> values = new ArrayList<>();
                for (DataType.Struct.Field f : s.fields()) {
                    values.add(value(f.type(), random));
                }
                yield values;
            }
            case DataType.MapType m -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    map.put(nonNull(m.key(), random), value(m.value(), random));
                }
                yield map;
            }
            default -> scalar((DataType.Scalar) type, random);
        };
    }

    private static Object scalar(DataType.Scalar type, SplittableRandom random) {
        boolean edge = random.nextInt(4) == 0;
        return switch (type) {
            case BOOLEAN -> random.nextBoolean();
            case TINYINT -> edge ? (random.nextBoolean() ? Byte.MIN_VALUE : Byte.MAX_VALUE) : (byte) random.nextInt();
            case SMALLINT -> edge ? (random.nextBoolean() ? Short.MIN_VALUE : Short.MAX_VALUE)
                    : (short) random.nextInt();
            case INTEGER, DATE -> edge ? (random.nextBoolean() ? Integer.MIN_VALUE : Integer.MAX_VALUE)
                    : random.nextInt();
            case BIGINT, TIMESTAMP, TIMESTAMPTZ -> edge ? (random.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE)
                    : random.nextLong();
            case TIME -> edge ? (random.nextBoolean() ? 0L : 86_400_000_000L) : random.nextLong(86_400_000_000L);
            case INTERVAL -> edge ? net.fyrql.core.value.Interval.of(Integer.MIN_VALUE, Integer.MAX_VALUE,
                    -9_000_000_000_000_000L) : net.fyrql.core.value.Interval.of(random.nextInt(), random.nextInt(),
                    random.nextLong(-9_000_000_000_000_000L, 9_000_000_000_000_000L));
            case HUGEINT -> edge ? (random.nextBoolean() ? TestRows.MAX_DECIMAL_38
                    : TestRows.MAX_DECIMAL_38.negate()) : BigInteger.valueOf(random.nextLong())
                    .shiftLeft(random.nextInt(60)).add(BigInteger.valueOf(random.nextLong()));
            case FLOAT -> edge ? new float[] {Float.NaN, -0.0f, Float.MIN_VALUE, Float.NEGATIVE_INFINITY}[
                    random.nextInt(4)] : (float) random.nextDouble(-1e6, 1e6);
            case DOUBLE -> edge ? new double[] {Double.NaN, -0.0, Double.MIN_VALUE, Double.POSITIVE_INFINITY}[
                    random.nextInt(4)] : random.nextDouble(-1e12, 1e12);
            case VARCHAR -> {
                String[] parts = {"", "a", "\u00E4", "\u20AC", "\uD83D\uDE00", "xyz", " "};
                StringBuilder b = new StringBuilder();
                for (int i = random.nextInt(5); i > 0; i--) {
                    b.append(parts[random.nextInt(parts.length)]);
                }
                yield b.toString();
            }
            case BLOB -> {
                byte[] bytes = new byte[random.nextInt(6)];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) random.nextInt();
                }
                yield bytes;
            }
            case UUID -> new UUID(random.nextLong(), random.nextLong());
        };
    }

    @Test
    void theOffsetsOfStringsStartAtZeroInEveryBatch() throws IOException {
        Schema schema = Schema.of(new Schema.Field("v", DataType.VARCHAR));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(new Object[] {i % 7 == 0 ? null : "row " + i});
        }
        Batch batch = Batch.ofRows(schema, rows);
        Batch tail = batch.select(new int[] {98, 99});
        assertRoundTrip(schema, List.of(batch, tail));
        try (VectorSchemaRoot root = BatchToArrow.root(schema, allocator)) {
            BatchToArrow.fill(batch, root);
            BatchToArrow.fill(tail, root);
            org.apache.arrow.vector.VarCharVector v = (org.apache.arrow.vector.VarCharVector) root.getVector(0);
            assertEquals(2, v.getValueCount());
            assertTrue(v.isNull(0), "row 98 is NULL");
            assertEquals(0, v.getStartOffset(1));
            assertEquals(6, v.getEndOffset(1));
            assertEquals("row 99", new String(v.get(1), StandardCharsets.UTF_8));
        }
    }

    @Test
    void stringsWithADictionaryGoOutFlat() throws IOException {
        byte[] bytes = "##MAILSHIPTRUCKä".getBytes(StandardCharsets.UTF_8);
        BytesVector.Dictionary dictionary = BytesVector.Dictionary.of(bytes, new int[] {2, 6, 10, 10, 15, 17});
        SplittableRandom random = new SplittableRandom(20261024);
        int n = 200;
        int[] codes = new int[n];
        boolean[] nulls = new boolean[n];
        for (int i = 0; i < n; i++) {
            codes[i] = random.nextInt(dictionary.size());
            nulls[i] = random.nextInt(5) == 0;
        }
        nulls[0] = true;
        Schema schema = Schema.of(new Schema.Field("v", DataType.VARCHAR), new Schema.Field("b", DataType.BLOB));
        Batch batch = new Batch(schema, n, List.of(BytesVector.dictionary(DataType.VARCHAR, codes, dictionary, nulls),
                BytesVector.dictionary(DataType.BLOB, codes, dictionary, null)));
        assertRoundTrip(schema, List.of(batch, batch.select(new int[] {199, 0, 3, 3}), batch.select(new int[0])));
        try (VectorSchemaRoot root = BatchToArrow.root(schema, allocator)) {
            BatchToArrow.fill(batch, root);
            org.apache.arrow.vector.VarCharVector v = (org.apache.arrow.vector.VarCharVector) root.getVector(0);
            assertTrue(v.isNull(0));
            assertEquals(0, v.getEndOffset(0), "a NULL has no bytes");
            for (int i = 1; i < n; i++) {
                if (!nulls[i]) {
                    assertEquals(dictionary.getString(codes[i]), new String(v.get(i), StandardCharsets.UTF_8));
                }
            }
        }
    }
}
