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

import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.Vector;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.bytes.HeapByteBufferAllocator;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.page.DataPage;
import org.apache.parquet.column.page.DataPageV1;
import org.apache.parquet.column.page.DataPageV2;
import org.apache.parquet.column.page.DictionaryPage;
import org.apache.parquet.column.page.PageReader;
import org.apache.parquet.column.values.bitpacking.ByteBitPackingValuesWriter;
import org.apache.parquet.column.values.bitpacking.Packer;
import org.apache.parquet.column.values.delta.DeltaBinaryPackingValuesWriterForInteger;
import org.apache.parquet.column.values.deltalengthbytearray.DeltaLengthByteArrayValuesWriter;
import org.apache.parquet.column.values.plain.BooleanPlainValuesWriter;
import org.apache.parquet.column.values.plain.FixedLenByteArrayPlainValuesWriter;
import org.apache.parquet.column.values.plain.PlainValuesWriter;
import org.apache.parquet.column.values.rle.RunLengthBitPackingHybridEncoder;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decoder of pages against the encoders of parquet-java: the hybrid of
 * runs and bit packing in all bit widths, the deprecated bit packing of
 * levels, plain values of all physical types, dictionary pages, and a
 * column chunk of pages v1 and v2 read in batches across pages, with a
 * fallback to a column reader of parquet-java in the middle.
 */
class PageDecoderTest {

    private static final int[] COUNTS = {1, 7, 8, 9, 100, 1003};

    @Test
    void hybridMatchesTheEncoderOfParquetJava() throws IOException {
        Random random = new Random(20261024);
        for (int width = 0; width <= 32; width++) {
            for (int count : COUNTS) {
                int[] values = values(random, width, count);
                byte[] encoded = hybrid(width, values);
                byte[] padded = new byte[encoded.length + 6];
                Arrays.fill(padded, (byte) 0x55);
                System.arraycopy(encoded, 0, padded, 3, encoded.length);
                int[] decoded = new int[count];
                int end = HybridDecoder.decode(padded, 3, 3 + encoded.length, width, count, decoded);
                assertArrayEquals(values, decoded, "width " + width + ", count " + count);
                assertEquals(3 + encoded.length, end, "width " + width + ", count " + count);
            }
        }
    }

    /** Returns values of a bit width, runs of one value mixed with values at random and at the limits. */
    private static int[] values(Random random, int width, int count) {
        long limit = 1L << width;
        int[] values = new int[count];
        for (int i = 0; i < count; ) {
            int run = random.nextBoolean() ? 1 + random.nextInt(40) : 1;
            int value = (int) switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> limit - 1;
                default -> random.nextLong(limit);
            };
            for (int k = 0; k < run && i < count; k++, i++) {
                values[i] = run == 1 && random.nextBoolean() ? (int) random.nextLong(limit) : value;
            }
        }
        return values;
    }

    private static byte[] hybrid(int width, int[] values) throws IOException {
        try (RunLengthBitPackingHybridEncoder e = new RunLengthBitPackingHybridEncoder(width, 64, 1 << 20,
                HeapByteBufferAllocator.getInstance())) {
            for (int v : values) {
                e.writeInt(v);
            }
            return e.toBytes().toByteArray();
        }
    }

    @Test
    void bitPackedLevelsMatchTheWriterOfParquetJava() throws IOException {
        Random random = new Random(20261025);
        for (int max = 1; max <= 255; max = max * 2 + 1) {
            int width = HybridDecoder.width(max);
            for (int count : COUNTS) {
                int[] values = new int[count];
                ByteBitPackingValuesWriter w = new ByteBitPackingValuesWriter(max, Packer.BIG_ENDIAN);
                for (int i = 0; i < count; i++) {
                    values[i] = random.nextInt(max + 1);
                    w.writeInteger(values[i]);
                }
                byte[] encoded = w.getBytes().toByteArray();
                int[] decoded = new int[count];
                int end = HybridDecoder.decodeBitPacked(encoded, 0, encoded.length, width, count, decoded);
                assertArrayEquals(values, decoded, "width " + width + ", count " + count);
                assertEquals((count * width + 7) / 8, end);
            }
        }
    }

    @Test
    void truncatedLevelsAndIdsAreErrors() throws IOException {
        int[] values = values(new Random(1), 5, 100);
        byte[] encoded = hybrid(5, values);
        for (int cut = 0; cut < encoded.length; cut++) {
            int at = cut;
            assertEquals("The page ends inside its RLE data", assertThrows(IllegalArgumentException.class,
                    () -> HybridDecoder.decode(encoded, 0, at, 5, 100, new int[100])).getMessage());
        }
        assertEquals("The bit width 33 of RLE data is beyond 32", assertThrows(IllegalArgumentException.class,
                () -> HybridDecoder.decode(encoded, 0, encoded.length, 33, 1, new int[1])).getMessage());
        assertEquals("The page ends inside its BIT_PACKED data", assertThrows(IllegalArgumentException.class,
                () -> HybridDecoder.decodeBitPacked(new byte[2], 0, 2, 3, 6, new int[6])).getMessage());
    }

    @Test
    void plainValuesMatchTheWritersOfParquetJava() throws IOException {
        Random random = new Random(20261026);
        int count = 1003;
        int[] ints = random.ints(count).toArray();
        ints[0] = Integer.MIN_VALUE;
        ints[1] = Integer.MAX_VALUE;
        long[] longs = random.longs(count).toArray();
        longs[0] = Long.MIN_VALUE;
        PlainValuesWriter intWriter = plain();
        PlainValuesWriter longWriter = plain();
        PlainValuesWriter floatWriter = plain();
        PlainValuesWriter doubleWriter = plain();
        BooleanPlainValuesWriter booleanWriter = new BooleanPlainValuesWriter();
        PlainValuesWriter binaryWriter = plain();
        FixedLenByteArrayPlainValuesWriter fixedWriter = new FixedLenByteArrayPlainValuesWriter(3, 64, 1 << 20,
                HeapByteBufferAllocator.getInstance());
        List<byte[]> binaries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            intWriter.writeInteger(ints[i]);
            longWriter.writeLong(longs[i]);
            floatWriter.writeFloat(Float.intBitsToFloat(ints[i]));
            doubleWriter.writeDouble(Double.longBitsToDouble(longs[i]));
            booleanWriter.writeBoolean((ints[i] & 1) != 0);
            byte[] b = new byte[random.nextInt(i % 10 == 0 ? 300 : 5)];
            random.nextBytes(b);
            binaries.add(b);
            binaryWriter.writeBytes(Binary.fromConstantByteArray(b));
            fixedWriter.writeBytes(Binary.fromConstantByteArray(new byte[] {(byte) i, (byte) (i >> 8), 7}));
        }
        long[] out = new long[count + 2];
        byte[] b = intWriter.getBytes().toByteArray();
        assertEquals(b.length, PlainDecoder.ints(b, 0, b.length, count, out, 2));
        for (int i = 0; i < count; i++) {
            assertEquals(ints[i], out[i + 2]);
        }
        b = floatWriter.getBytes().toByteArray();
        PlainDecoder.ints(b, 0, b.length, count, out, 0);
        for (int i = 0; i < count; i++) {
            assertEquals(ints[i], (int) out[i]);
        }
        b = longWriter.getBytes().toByteArray();
        assertEquals(b.length, PlainDecoder.longs(b, 0, b.length, count, out, 0));
        assertArrayEquals(longs, Arrays.copyOf(out, count));
        b = doubleWriter.getBytes().toByteArray();
        PlainDecoder.longs(b, 0, b.length, count, out, 0);
        for (int i = 0; i < count; i++) {
            assertEquals(longs[i], out[i]);
        }
        b = booleanWriter.getBytes().toByteArray();
        long bit = 0;
        for (int chunk : new int[] {1, 6, 9, 987}) {
            long next = PlainDecoder.booleans(b, 0, b.length, bit, chunk, out, (int) bit);
            assertEquals(bit + chunk, next);
            bit = next;
        }
        for (int i = 0; i < count; i++) {
            assertEquals(ints[i] & 1, out[i], "boolean " + i);
        }
        b = binaryWriter.getBytes().toByteArray();
        int[] from = new int[count];
        int[] length = new int[count];
        assertEquals(b.length, PlainDecoder.binaries(b, 0, b.length, count, from, length));
        for (int i = 0; i < count; i++) {
            assertArrayEquals(binaries.get(i), Arrays.copyOfRange(b, from[i], from[i] + length[i]));
        }
        b = fixedWriter.getBytes().toByteArray();
        assertEquals(b.length, PlainDecoder.fixed(b, 0, b.length, 3, count, from, length));
        assertEquals(3 * 500, from[500]);
        assertEquals(3, length[500]);
        byte[] bytes = b;
        assertEquals("The page ends before its values", assertThrows(IllegalArgumentException.class,
                () -> PlainDecoder.longs(bytes, 0, bytes.length, count + 1, out, 0)).getMessage());
        byte[] cut = binaryWriter.getBytes().toByteArray();
        assertEquals("The page ends before its values", assertThrows(IllegalArgumentException.class,
                () -> PlainDecoder.binaries(cut, 0, cut.length - 1, count, from, length)).getMessage());
    }

    private static PlainValuesWriter plain() {
        return new PlainValuesWriter(64, 1 << 20, HeapByteBufferAllocator.getInstance());
    }

    @Test
    void dictionaryPagesAreCopiedAtOnce() throws IOException {
        PlainValuesWriter w = plain();
        w.writeLong(-5);
        w.writeLong(Long.MAX_VALUE);
        byte[] b = w.getBytes().toByteArray();
        PageDictionary longs = PageDictionary.read(new DictionaryPage(BytesInput.from(b), 2,
                Encoding.PLAIN_DICTIONARY), primitive("required int64 x"));
        assertArrayEquals(new long[] {-5, Long.MAX_VALUE}, longs.values());
        w = plain();
        for (String s : List.of("", "Moin", "Grüße")) {
            w.writeBytes(Binary.fromString(s));
        }
        byte[] strings = w.getBytes().toByteArray();
        PageDictionary binary = PageDictionary.read(new DictionaryPage(BytesInput.from(strings), 3,
                Encoding.PLAIN), primitive("required binary x (STRING)"));
        Arrays.fill(strings, (byte) 0);
        assertArrayEquals(new int[] {0, 0, 4, 4 + "Grüße".getBytes(StandardCharsets.UTF_8).length},
                binary.offsets());
        assertEquals("MoinGrüße", new String(binary.data(), StandardCharsets.UTF_8));
        binary.check(new int[] {9, 0, 1, 2, 9}, 1, 3);
        assertEquals("The dictionary id 3 is beyond the 3 values of the dictionary",
                assertThrows(IllegalArgumentException.class, () -> binary.check(new int[] {0, 3}, 0, 2))
                        .getMessage());
        assertEquals("The dictionary id 4294967295 is beyond the 3 values of the dictionary",
                assertThrows(IllegalArgumentException.class, () -> binary.check(new int[] {-1}, 0, 1))
                        .getMessage());
        assertEquals("The page ends before its values", assertThrows(IllegalArgumentException.class,
                () -> PageDictionary.read(new DictionaryPage(BytesInput.from(b), 3, Encoding.PLAIN),
                        primitive("required int64 x"))).getMessage());
        assertEquals("The encoding RLE of a dictionary page is not supported",
                assertThrows(IllegalArgumentException.class, () -> PageDictionary.read(new DictionaryPage(
                        BytesInput.from(b), 2, Encoding.RLE), primitive("required int64 x"))).getMessage());
    }

    private static PrimitiveType primitive(String field) {
        return column(field).getPrimitiveType();
    }

    private static ColumnDescriptor column(String field) {
        return schema(field).getColumns().get(0);
    }

    private static MessageType schema(String field) {
        return MessageTypeParser.parseMessageType("message m { " + field + "; }");
    }

    /** Returns a decoder of the pages of a field that falls back to a column reader of parquet-java. */
    private static ColumnDecoder decoder(String field, DataType type, DictionaryPage dictionary, DataPage... pages) {
        MessageType m = schema(field);
        ColumnDescriptor d = m.getColumns().get(0);
        return new ColumnDecoder(new Pages(dictionary, pages), d, ParquetLeaf.of(d.getPrimitiveType(), type),
                rest -> RowGroupReader.columnReader(rest, 0, m, d, null));
    }

    /** Reads all rows in batches of a size. */
    private static List<Object> readAll(ColumnDecoder decoder, int rows, int batch) {
        List<Object> read = new ArrayList<>();
        for (int at = 0; at < rows; at += batch) {
            Vector v = decoder.read(Math.min(batch, rows - at));
            for (int r = 0; r < v.size(); r++) {
                read.add(v.get(r));
            }
        }
        return read;
    }

    @Test
    void aChunkOfPagesV1AndV2IsReadInBatchesAcrossPages() throws IOException {
        PlainValuesWriter dictionary = plain();
        dictionary.writeLong(100);
        dictionary.writeLong(-7);
        // Page 1, v1 on the dictionary: 100, NULL, -7, -7, 100.
        int[] levels1 = {1, 0, 1, 1, 1};
        DataPage page1 = v1(5, levels1, dictionaryIds(1, 0, 1, 1, 0), Encoding.RLE_DICTIONARY);
        // Page 2, v1 plain: NULL, NULL, 42.
        PlainValuesWriter plain2 = plain();
        plain2.writeLong(42);
        DataPage page2 = v1(3, new int[] {0, 0, 1}, plain2.getBytes().toByteArray(), Encoding.PLAIN);
        // Page 3, v2 plain: 1, 2, NULL, 3.
        PlainValuesWriter plain3 = plain();
        for (long v : new long[] {1, 2, 3}) {
            plain3.writeLong(v);
        }
        DataPage page3 = DataPageV2.uncompressed(4, 1, 4, BytesInput.empty(),
                BytesInput.from(hybrid(1, new int[] {1, 1, 0, 1})), Encoding.PLAIN, plain3.getBytes(), null);
        Long[] expected = {100L, null, -7L, -7L, 100L, null, null, 42L, 1L, 2L, null, 3L};
        for (int batch : new int[] {1, 2, 3, 5, 7, 12}) {
            ColumnDecoder decoder = decoder("optional int64 x", DataType.BIGINT, new DictionaryPage(
                    dictionary.getBytes(), 2, Encoding.PLAIN_DICTIONARY), page1, page2, page3);
            assertEquals(Arrays.asList(expected), readAll(decoder, expected.length, batch), "batch " + batch);
            assertFalse(decoder.fellBack());
            assertEquals("The column chunk ends before its last row", assertThrows(IllegalArgumentException.class,
                    () -> decoder.read(1)).getMessage());
        }
    }

    @Test
    void stringsComeFromTheDictionaryOrThePage() throws IOException {
        PlainValuesWriter dictionary = plain();
        dictionary.writeBytes(Binary.fromString("ab"));
        dictionary.writeBytes(Binary.fromString("c"));
        DataPage page1 = v1(4, new int[0], dictionaryIds(1, 1, 1, 0, 1), Encoding.PLAIN_DICTIONARY);
        PlainValuesWriter plain2 = plain();
        plain2.writeBytes(Binary.fromString("plain"));
        plain2.writeBytes(Binary.fromString(""));
        DataPage page2 = v1(2, new int[0], plain2.getBytes().toByteArray(), Encoding.PLAIN);
        ColumnDecoder decoder = decoder("required binary s (STRING)", DataType.VARCHAR, new DictionaryPage(
                dictionary.getBytes(), 2, Encoding.PLAIN_DICTIONARY), page1, page2);
        Vector first = decoder.read(2);
        Vector second = decoder.read(4);
        assertEquals(List.of("c", "c"), List.of(first.get(0), first.get(1)));
        assertEquals(List.of("ab", "c", "plain", ""), List.of(second.get(0), second.get(1), second.get(2),
                second.get(3)));
        assertTrue(((BytesVector) first).hasDictionary());
        assertFalse(((BytesVector) second).hasDictionary());
    }

    @Test
    void stringsKeepTheCodesOfTheDictionaryUntilAPageInPlain() throws IOException {
        PlainValuesWriter dictionary = plain();
        for (String s : new String[] {"ab", "c", "Grüße"}) {
            dictionary.writeBytes(Binary.fromString(s));
        }
        PlainValuesWriter plain3 = plain();
        plain3.writeBytes(Binary.fromString("plain"));
        plain3.writeBytes(Binary.fromString(""));
        Object[] expected = {"Grüße", null, "ab", "c", null, null, "c", "c", "plain", null, "", "ab",
            "Grüße"};
        // The page in PLAIN starts at row 8; the batches before it keep the codes, all from it on are flat.
        int plainFrom = 8;
        for (int batch : new int[] {1, 3, 5, 8, 13}) {
            ColumnDecoder decoder = decoder("optional binary s (STRING)", DataType.VARCHAR, new DictionaryPage(
                    dictionary.getBytes(), 3, Encoding.PLAIN_DICTIONARY),
                    v1(5, new int[] {1, 0, 1, 1, 0}, dictionaryIds(2, 2, 0, 1), Encoding.PLAIN_DICTIONARY),
                    v1(3, new int[] {0, 1, 1}, dictionaryIds(2, 1, 1), Encoding.RLE_DICTIONARY),
                    v1(3, new int[] {1, 0, 1}, plain3.getBytes().toByteArray(), Encoding.PLAIN),
                    v1(2, new int[] {1, 1}, dictionaryIds(2, 0, 2), Encoding.RLE_DICTIONARY));
            List<Object> read = new ArrayList<>();
            BytesVector.Dictionary shared = null;
            for (int at = 0; at < expected.length; at += batch) {
                BytesVector v = (BytesVector) decoder.read(Math.min(batch, expected.length - at));
                String what = "batch " + batch + " at row " + at;
                assertEquals(at + v.size() <= plainFrom, v.hasDictionary(), what);
                if (v.hasDictionary()) {
                    assertTrue(shared == null || shared == v.dictionary(), what + ": one dictionary");
                    shared = v.dictionary();
                }
                for (int r = 0; r < v.size(); r++) {
                    read.add(v.get(r));
                    if (v.isNull(r) && !v.hasDictionary()) {
                        assertEquals(0, v.length(r), what + ": a NULL has no bytes");
                    }
                }
            }
            assertEquals(Arrays.asList(expected), read, "batch " + batch);
            assertFalse(decoder.fellBack());
        }
    }

    @Test
    void anIdBeyondTheDictionaryOrWithoutOneIsAnError() throws IOException {
        PlainValuesWriter dictionary = plain();
        dictionary.writeInteger(1);
        ColumnDecoder beyond = decoder("required int32 x", DataType.INTEGER, new DictionaryPage(
                dictionary.getBytes(), 1, Encoding.PLAIN), v1(2, new int[0], dictionaryIds(1, 0, 1),
                Encoding.RLE_DICTIONARY));
        assertEquals("The dictionary id 1 is beyond the 1 values of the dictionary",
                assertThrows(IllegalArgumentException.class, () -> beyond.read(2)).getMessage());
        ColumnDecoder without = decoder("required int32 x", DataType.INTEGER, null, v1(2, new int[0],
                dictionaryIds(1, 0, 0), Encoding.RLE_DICTIONARY));
        assertEquals("A page refers to a dictionary the column chunk does not have",
                assertThrows(IllegalArgumentException.class, () -> without.read(2)).getMessage());
    }

    @Test
    void aPageInAnotherEncodingHandsTheRestOfTheChunkToParquetJava() throws IOException {
        PlainValuesWriter dictionary = plain();
        dictionary.writeInteger(10);
        dictionary.writeInteger(20);
        PlainValuesWriter plain1 = plain();
        for (int v = 1; v <= 3; v++) {
            plain1.writeInteger(v);
        }
        DeltaBinaryPackingValuesWriterForInteger delta = new DeltaBinaryPackingValuesWriterForInteger(64, 1 << 20,
                HeapByteBufferAllocator.getInstance());
        for (int v = 4; v <= 7; v++) {
            delta.writeInteger(v);
        }
        List<Object> expected = List.of(1, 2, 3, 4, 5, 6, 7, 20, 10);
        for (int batch : new int[] {1, 2, 4, 9}) {
            ColumnDecoder decoder = decoder("required int32 x", DataType.INTEGER, new DictionaryPage(
                    dictionary.getBytes(), 2, Encoding.PLAIN), v1(3, new int[0], plain1.getBytes().toByteArray(),
                    Encoding.PLAIN), v1(4, new int[0], delta.getBytes().toByteArray(), Encoding.DELTA_BINARY_PACKED),
                    v1(2, new int[0], dictionaryIds(1, 1, 0), Encoding.RLE_DICTIONARY));
            assertEquals(expected, readAll(decoder, expected.size(), batch), "batch " + batch);
            assertTrue(decoder.fellBack());
        }
    }

    @Test
    void stringsAndNullsAfterTheFallbackUseTheDictionaryEncodedAgain() throws IOException {
        PlainValuesWriter dictionary = plain();
        dictionary.writeBytes(Binary.fromString("Moin"));
        dictionary.writeBytes(Binary.fromString(""));
        dictionary.writeBytes(Binary.fromString("Grüße"));
        DeltaLengthByteArrayValuesWriter delta = new DeltaLengthByteArrayValuesWriter(64, 1 << 20,
                HeapByteBufferAllocator.getInstance());
        delta.writeBytes(Binary.fromString("delta"));
        delta.writeBytes(Binary.fromString("x".repeat(300)));
        Object[] expected = {"Grüße", null, "Moin", null, "delta", "x".repeat(300), null, "", "Grüße"};
        for (int batch : new int[] {1, 3, 5, 9}) {
            ColumnDecoder decoder = decoder("optional binary s (STRING)", DataType.VARCHAR, new DictionaryPage(
                    dictionary.getBytes(), 3, Encoding.PLAIN_DICTIONARY),
                    v1(3, new int[] {1, 0, 1}, dictionaryIds(2, 2, 0), Encoding.PLAIN_DICTIONARY),
                    v1(3, new int[] {0, 1, 1}, delta.getBytes().toByteArray(), Encoding.DELTA_LENGTH_BYTE_ARRAY),
                    v1(3, new int[] {0, 1, 1}, dictionaryIds(2, 1, 2), Encoding.PLAIN_DICTIONARY));
            assertEquals(Arrays.asList(expected), readAll(decoder, expected.length, batch), "batch " + batch);
            assertTrue(decoder.fellBack());
        }
    }

    @Test
    void booleansInRleAndRequiredColumnsWithoutNulls() throws IOException {
        ColumnDecoder decoder = decoder("required boolean b", DataType.BOOLEAN, null,
                rleBooleans(true, false, false, true));
        Vector v = decoder.read(4);
        assertEquals(List.of(true, false, false, true), List.of(v.get(0), v.get(1), v.get(2), v.get(3)));
        assertFalse(v.hasNulls());
    }

    /** Returns a page v2 of booleans in {@code RLE}, with the length in 4 bytes before the runs. */
    private static DataPage rleBooleans(boolean... values) throws IOException {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = values[i] ? 1 : 0;
        }
        byte[] runs = hybrid(1, bits);
        return DataPageV2.uncompressed(values.length, 0, values.length, BytesInput.empty(), BytesInput.empty(),
                Encoding.RLE, BytesInput.concat(BytesInput.fromInt(runs.length), BytesInput.from(runs)), null);
    }

    /** Returns the dictionary ids of a page: their bit width in a byte, then the runs. */
    private static byte[] dictionaryIds(int width, int... ids) throws IOException {
        byte[] runs = hybrid(width, ids);
        byte[] page = new byte[runs.length + 1];
        page[0] = (byte) width;
        System.arraycopy(runs, 0, page, 1, runs.length);
        return page;
    }

    /** Returns a page v1 with definition levels of width 1 in {@code RLE}, if any, then the values. */
    private static DataPage v1(int rows, int[] levels, byte[] values, Encoding encoding) throws IOException {
        BytesInput bytes = BytesInput.from(values);
        if (levels.length > 0) {
            byte[] runs = hybrid(1, levels);
            bytes = BytesInput.concat(BytesInput.fromInt(runs.length), BytesInput.from(runs), bytes);
        }
        return new DataPageV1(bytes, rows, (int) bytes.size(), null, Encoding.BIT_PACKED,
                levels.length > 0 ? Encoding.RLE : Encoding.BIT_PACKED, encoding);
    }

    /** The pages of a column chunk. */
    private static final class Pages implements PageReader {
        private final DictionaryPage dictionary;
        private final Deque<DataPage> pages;
        private final long values;

        Pages(DictionaryPage dictionary, DataPage... pages) {
            this.dictionary = dictionary;
            this.pages = new ArrayDeque<>(List.of(pages));
            this.values = this.pages.stream().mapToLong(DataPage::getValueCount).sum();
        }

        @Override
        public DictionaryPage readDictionaryPage() {
            return dictionary;
        }

        @Override
        public long getTotalValueCount() {
            return values;
        }

        @Override
        public DataPage readPage() {
            return pages.poll();
        }
    }
}
