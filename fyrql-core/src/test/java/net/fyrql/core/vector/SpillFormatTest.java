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

package net.fyrql.core.vector;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.DataGeneratorTest;
import net.fyrql.core.value.Keys;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpillFormatTest {

    private static final Schema ALL = new Schema(DataGeneratorTest.ALL_TYPES.stream()
            .map(t -> new Schema.Field(t.sqlName(), t)).toList());

    @Test
    void everyTypeComesBackAsItWasWritten() throws IOException {
        SplittableRandom random = new SplittableRandom(20261021);
        for (int bufferBytes : new int[] {16, 100, SpillFormat.DEFAULT_BUFFER_BYTES}) {
            List<Batch> batches = new ArrayList<>();
            for (int round = 0; round < 12; round++) {
                int rows = round == 0 ? 0 : round == 1 ? 1 : random.nextInt(400);
                batches.add(Batch.ofRows(ALL, DataGenerator.withSeed(round).generate(ALL, rows)));
            }
            byte[] data = write(batches, bufferBytes);
            List<Batch> back = read(ALL, data, bufferBytes);
            assertEquals(batches.size(), back.size(), "batches with a buffer of " + bufferBytes);
            for (int i = 0; i < batches.size(); i++) {
                assertIdentical(batches.get(i), back.get(i), "batch " + i + ", buffer " + bufferBytes);
            }
        }
    }

    @Test
    void rangesOfBatchesAndVectorsThatDoNotStartAtZero() throws IOException {
        Batch batch = Batch.ofRows(ALL, DataGenerator.withSeed(5).generate(ALL, 300));
        int[][] ranges = {{0, 0}, {0, 1}, {17, 18}, {5, 250}, {299, 300}, {0, 300}};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpillFormat.Writer w = new SpillFormat.Writer(Channels.newChannel(out), 64)) {
            for (int[] r : ranges) {
                w.write(batch, r[0], r[1]);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> w.write(batch, 10, 301));
        }
        List<Batch> back = read(ALL, out.toByteArray(), 64);
        assertEquals(ranges.length, back.size());
        for (int i = 0; i < ranges.length; i++) {
            int from = ranges[i][0];
            int[] rows = new int[ranges[i][1] - from];
            Arrays.setAll(rows, k -> from + k);
            assertIdentical(batch.select(rows), back.get(i), "range " + Arrays.toString(ranges[i]));
        }

        // Bytes that start inside their buffer, and lists whose elements do.
        byte[] buffer = "xxabcdyy".getBytes(StandardCharsets.UTF_8);
        BytesVector strings = new BytesVector(DataType.VARCHAR, buffer, new int[] {2, 4, 6}, null);
        DataType.ListType listType = DataType.listOf(DataType.VARCHAR);
        ListVector lists = new ListVector(listType, new int[] {1, 2, 3}, BytesVector.ofStrings("a", "b", "c"),
                null);
        Schema schema = Schema.of(new Schema.Field("s", DataType.VARCHAR), new Schema.Field("l", listType));
        Batch inner = new Batch(schema, 2, List.of(strings, lists));
        Batch backInner = read(schema, write(List.of(inner), 16), 16).get(0);
        assertIdentical(inner, backInner, "vectors inside their buffers");
        assertEquals(List.of("ab", "cd"), Arrays.asList(backInner.column(0).get(0), backInner.column(0).get(1)));
        assertEquals(List.of(List.of("b"), List.of("c")), Arrays.asList(backInner.column(1).get(0),
                backInner.column(1).get(1)));
    }

    @Test
    void valuesUnderNullsAndTheFormOfDecimalsStay() throws IOException {
        DataType.Decimal small = DataType.decimal(10, 2);
        DataType.Decimal large = DataType.decimal(38, 2);
        Schema schema = Schema.of(new Schema.Field("i", DataType.INTEGER), new Schema.Field("d", DataType.DOUBLE),
                new Schema.Field("s", small), new Schema.Field("b", large), new Schema.Field("x", large),
                new Schema.Field("e", DataType.BOOLEAN));
        boolean[] nulls = {false, true, false};
        Batch batch = new Batch(schema, 3, List.of(
                new IntVector(DataType.INTEGER, new int[] {1, 42, -3}, nulls),
                new DoubleVector(new double[] {-0.0, Double.longBitsToDouble(0x7ff8000000000001L), Double.NaN},
                        nulls),
                new DecimalVector(small, new long[] {1, 2, 3}, null, null),
                new DecimalVector(large, null, new BigInteger[] {BigInteger.TEN.pow(30), null, BigInteger.ONE},
                        nulls),
                new DecimalVector(large, new long[] {7, 8, 9}, null, null),
                new BooleanVector(new boolean[] {true, true, false}, nulls)));
        Batch back = read(schema, write(List.of(batch), 32), 32).get(0);
        assertIdentical(batch, back, "values under NULLs");
        assertEquals(42, ((IntVector) back.column(0)).getInt(1));
        assertEquals(0x7ff8000000000001L, Double.doubleToRawLongBits(((DoubleVector) back.column(1)).getDouble(1)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(
                ((DoubleVector) back.column(1)).getDouble(0)));
        assertTrue(((DecimalVector) back.column(2)).isSmall());
        assertTrue(!((DecimalVector) back.column(3)).isSmall());
        assertNull(((DecimalVector) back.column(3)).big[1]);
        assertTrue(((DecimalVector) back.column(4)).isSmall(), "a long decimal of 38 digits stays long");
        assertTrue(((BooleanVector) back.column(5)).getBoolean(1));
    }

    @Test
    void aBatchWithoutColumnsKeepsItsRows() throws IOException {
        Schema none = new Schema(List.of());
        Batch batch = new Batch(none, 5, List.of());
        List<Batch> back = read(none, write(List.of(batch, batch), 16), 16);
        assertEquals(2, back.size());
        assertEquals(5, back.get(1).rowCount());
    }

    @Test
    void truncatedOrCorruptDataFails() throws IOException {
        Batch batch = Batch.ofRows(ALL, DataGenerator.withSeed(9).generate(ALL, 50));
        byte[] data = write(List.of(batch), 64);
        for (int cut : new int[] {1, 3, 4, 5, data.length / 2, data.length - 1}) {
            byte[] part = Arrays.copyOf(data, cut);
            IOException e = assertThrows(IOException.class, () -> read(ALL, part, 64), "cut at " + cut);
            assertTrue(e.getMessage().contains("ends inside a batch"), e.getMessage());
        }
        byte[] negative = {-1, -1, -1, -1};
        assertThrows(IOException.class, () -> read(ALL, negative, 64));
        assertThrows(IllegalArgumentException.class, () -> new SpillFormat.Writer(Channels.newChannel(
                new ByteArrayOutputStream()), 8));
        assertEquals(0, read(ALL, new byte[0], 64).size());
    }

    @Test
    void theWriterAndReaderCountTheirBytes() throws IOException {
        Batch batch = Batch.ofRows(ALL, DataGenerator.withSeed(11).generate(ALL, 100));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long bytes;
        try (SpillFormat.Writer w = new SpillFormat.Writer(Channels.newChannel(out), 128)) {
            w.write(batch);
            w.write(batch);
            bytes = w.bytes();
        }
        assertEquals(out.size(), bytes);
        try (SpillFormat.Reader r = new SpillFormat.Reader(Channels.newChannel(new ByteArrayInputStream(
                out.toByteArray())), ALL, 128)) {
            while (r.next() != null) {
                assertTrue(r.bytes() <= bytes);
            }
            assertEquals(bytes, r.bytes());
        }
    }

    static byte[] write(List<Batch> batches, int bufferBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpillFormat.Writer w = new SpillFormat.Writer(Channels.newChannel(out), bufferBytes)) {
            for (Batch b : batches) {
                w.write(b);
            }
        }
        return out.toByteArray();
    }

    static List<Batch> read(Schema schema, byte[] data, int bufferBytes) throws IOException {
        List<Batch> batches = new ArrayList<>();
        try (SpillFormat.Reader r = new SpillFormat.Reader(Channels.newChannel(new ByteArrayInputStream(data)),
                schema, bufferBytes)) {
            for (Batch b = r.next(); b != null; b = r.next()) {
                batches.add(b);
            }
            assertNull(r.next());
        }
        return batches;
    }

    /**
     * Asserts that two batches hold the same values, NULLs and hashes, and
     * the same bits of the floating-point values, also under NULLs.
     */
    static void assertIdentical(Batch expected, Batch actual, String what) {
        assertEquals(expected.schema(), actual.schema(), what);
        assertEquals(expected.rowCount(), actual.rowCount(), what);
        for (int c = 0; c < expected.columns().size(); c++) {
            Vector e = expected.column(c);
            Vector a = actual.column(c);
            assertEquals(e.type(), a.type(), what);
            for (int r = 0; r < e.size(); r++) {
                String at = what + ", " + e.type() + " row " + r;
                assertEquals(e.isNull(r), a.isNull(r), at);
                assertTrue(Keys.equalObjects(Keys.canonical(e.get(r)), Keys.canonical(a.get(r))), at + ": "
                        + e.get(r) + " became " + a.get(r));
                if (!e.isNull(r)) {
                    assertEquals(Vectors.hash(e, r), Vectors.hash(a, r), at);
                }
                switch (e) {
                    case DoubleVector d -> assertEquals(Double.doubleToRawLongBits(d.getDouble(r)),
                            Double.doubleToRawLongBits(((DoubleVector) a).getDouble(r)), at);
                    case FloatVector f -> assertEquals(Float.floatToRawIntBits(f.getFloat(r)),
                            Float.floatToRawIntBits(((FloatVector) a).getFloat(r)), at);
                    default -> {
                    }
                }
            }
            if (e instanceof DecimalVector d) {
                assertEquals(d.isSmall(), ((DecimalVector) a).isSmall(), what);
            }
        }
    }
}
