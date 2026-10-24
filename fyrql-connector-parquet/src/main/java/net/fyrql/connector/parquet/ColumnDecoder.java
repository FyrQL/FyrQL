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
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.FloatVector;
import net.fyrql.core.vector.HugeIntVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.UuidVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.ColumnReader;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.page.DataPage;
import org.apache.parquet.column.page.DataPageV1;
import org.apache.parquet.column.page.DataPageV2;
import org.apache.parquet.column.page.DictionaryPage;
import org.apache.parquet.column.page.PageReader;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the rows of a primitive field outside of lists from the pages of
 * a column chunk into vectors, a whole page at a time instead of a value at
 * a time through a {@link ColumnReader} of parquet-java. It decodes data
 * pages v1 and v2 with values in {@code PLAIN}, in {@code PLAIN_DICTIONARY}
 * or {@code RLE_DICTIONARY} on a dictionary page, and booleans in
 * {@code RLE}; definition levels in {@code RLE} or {@code BIT_PACKED}. A
 * value becomes what {@link ParquetLeaf} makes of it; a level below the
 * maximum is a {@code NULL}.
 * <p>
 * The levels and the dictionary ids of a page are decoded when the page is
 * read, its plain values as the rows are; a batch can span pages. Most
 * types of values end up in arrays that the vectors wrap, the rest goes
 * through a builder of a vector. The bytes of a page are referred to only
 * until the next page is read, and those of the dictionary page not at
 * all, so that a decompressor can reuse its buffer for the next page.
 * <p>
 * Strings and blobs from the dictionary keep its ids as codes: the vectors
 * of the chunk share one {@link BytesVector.Dictionary} over the copy of
 * the dictionary page. A batch with rows of a page in {@code PLAIN}, or of
 * the rest of the chunk read through parquet-java, is flat, and so are the
 * batches after it.
 * <p>
 * The metadata of a column chunk need not name all encodings of its pages;
 * DuckDB, for one, leaves out some. At the first page in an encoding it
 * does not read, the decoder hands that page and the rest of the chunk to
 * a column reader of parquet-java, with the dictionary encoded again from
 * its copy, and takes the values from the reader from then on.
 */
final class ColumnDecoder {

    /** The encodings in the metadata of a column chunk this decoder reads. */
    private static final Set<Encoding> ENCODINGS = EnumSet.of(Encoding.PLAIN, Encoding.PLAIN_DICTIONARY,
            Encoding.RLE_DICTIONARY, Encoding.RLE, Encoding.BIT_PACKED);
    private static final byte[] NO_BYTES = new byte[0];

    private final PageReader pages;
    private final Function<PageReader, ColumnReader> fallback;
    private final ParquetLeaf leaf;
    private final ParquetLeaf.Target target;
    private final PrimitiveTypeName physical;
    private final int maxDefinition;
    private final int levelWidth;
    /** The length of a byte array of a fixed length, or -1. */
    private final int fixedWidth;
    /** Whether the values are byte arrays, {@code BINARY}, {@code FIXED_LEN_BYTE_ARRAY} or {@code INT96}. */
    private final boolean bytes;
    /** Whether the integers decoded are the values of the vector, so that each batch needs its own array. */
    private final boolean longOutput;
    private final PageDictionary dictionary;
    /** The dictionary of strings or blobs that the vectors of the chunk share, or {@code null}. */
    private final BytesVector.Dictionary shared;
    /** Whether a batch starts with codes into {@link #shared}: until the first page that is not in it. */
    private boolean coding;
    /** The values of the pages decoded so far. */
    private long valuesRead;
    /** The column reader of parquet-java the rest of the chunk is read through, or {@code null}. */
    private ColumnReader reader;

    // The page read last.
    private int pageRows;
    private int pageRow;
    private Encoding encoding;
    private int[] levels;
    /** Whether a value of the page is {@code NULL}. */
    private boolean pageNulls;
    private byte[] page;
    private int pos;
    private int end;
    /** The next bit of booleans in {@code PLAIN}, from {@link #pos} on. */
    private long bit;
    /** The dictionary ids of the values of the page, or its booleans in {@code RLE}, and the next one. */
    private int[] ids = new int[0];
    private int idAt;

    // The batch being read.
    private int n;
    private boolean[] nulls;
    private long[] raw;
    private long[] scratch = new long[0];
    private long[] stage = new long[0];
    private byte[] source;
    private int[] from = new int[0];
    private int[] length = new int[0];
    private byte[] data;
    private int used;
    private int[] offsets;
    /** The codes of the batch while all its rows come from {@link #shared}, else {@code null}. */
    private int[] codes;
    private long[] most;
    private long[] least;
    private Vector.Builder builder;

    /**
     * Starts to read a column chunk; reads its dictionary page, if any.
     *
     * @param pages the pages of the chunk
     * @param descriptor the column of the chunk
     * @param leaf what the values become
     * @param fallback makes a column reader of parquet-java over pages of
     *        the chunk
     * @throws IllegalArgumentException if the dictionary page cannot be decoded
     */
    ColumnDecoder(PageReader pages, ColumnDescriptor descriptor, ParquetLeaf leaf,
            Function<PageReader, ColumnReader> fallback) {
        if (descriptor.getMaxRepetitionLevel() != 0 || !fits(leaf)) {
            throw new IllegalStateException("Not a column for the decoder: " + descriptor);
        }
        this.pages = pages;
        this.fallback = fallback;
        this.leaf = leaf;
        this.target = leaf.target();
        this.physical = leaf.physical();
        this.maxDefinition = descriptor.getMaxDefinitionLevel();
        this.levelWidth = HybridDecoder.width(maxDefinition);
        this.levels = maxDefinition > 0 ? new int[0] : null;
        this.fixedWidth = switch (physical) {
            case FIXED_LEN_BYTE_ARRAY -> descriptor.getPrimitiveType().getTypeLength();
            case INT96 -> 12;
            default -> -1;
        };
        this.bytes = physical == PrimitiveTypeName.BINARY || fixedWidth >= 0;
        this.longOutput = !bytes && switch (target) {
            case BIGINT, HUGEINT, TIMESTAMP, TIME_OF_DAY -> true;
            case DECIMAL -> leaf.fitsLong();
            default -> false;
        };
        DictionaryPage d = pages.readDictionaryPage();
        this.dictionary = d == null ? null : PageDictionary.read(d, descriptor.getPrimitiveType());
        this.shared = dictionary != null && target == ParquetLeaf.Target.BYTES && dictionary.size() > 0
                ? BytesVector.Dictionary.of(dictionary.data(), dictionary.offsets()) : null;
        this.coding = shared != null;
    }

    /**
     * Tells whether the decoder is meant to read a column chunk: by the
     * encodings in its metadata and by the type of the column and of its
     * values.
     */
    static boolean supports(ColumnChunkMetaData chunk, ParquetLeaf leaf) {
        return chunk.getEncodings() != null && ENCODINGS.containsAll(chunk.getEncodings()) && fits(leaf);
    }

    /** Tells whether the decoder makes the values of a vector of a type from those of a physical type. */
    private static boolean fits(ParquetLeaf leaf) {
        PrimitiveTypeName p = leaf.physical();
        boolean integer = p == PrimitiveTypeName.INT32 || p == PrimitiveTypeName.INT64;
        boolean bytes = p == PrimitiveTypeName.BINARY || p == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY
                || p == PrimitiveTypeName.INT96;
        return switch (leaf.target()) {
            case BOOLEAN -> p == PrimitiveTypeName.BOOLEAN;
            case TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT, TIMESTAMP, TIME_OF_DAY, TIME -> integer;
            case FLOAT -> p == PrimitiveTypeName.FLOAT;
            case DOUBLE -> p == PrimitiveTypeName.FLOAT || p == PrimitiveTypeName.DOUBLE;
            case DECIMAL -> integer || bytes;
            case BYTES, UUID, FLOAT16, INTERVAL, INT96 -> bytes;
        };
    }

    /** Tells whether the rest of the chunk is read through a column reader of parquet-java. */
    boolean fellBack() {
        return reader != null;
    }

    /**
     * Reads the next rows.
     *
     * @param n the number of rows, at least 1
     * @throws IllegalArgumentException if a page cannot be decoded or the
     *         chunk ends before the rows
     * @throws ArithmeticException if a value does not fit its type
     */
    Vector read(int n) {
        start(n);
        int at = 0;
        while (at < n) {
            if (reader != null) {
                fill(at, n - at);
                break;
            }
            if (pageRow == pageRows) {
                nextPage();
                continue;
            }
            int count = Math.min(n - at, pageRows - pageRow);
            segment(at, count);
            pageRow += count;
            at += count;
        }
        return finish();
    }

    private void start(int n) {
        this.n = n;
        nulls = null;
        raw = null;
        builder = null;
        if (bytes) {
            switch (target) {
                case BYTES -> {
                    data = NO_BYTES;
                    used = 0;
                    offsets = coding ? null : new int[n + 1];
                    codes = coding ? new int[n] : null;
                }
                case UUID -> {
                    most = new long[n];
                    least = new long[n];
                }
                case INT96 -> raw = new long[n];
                case DECIMAL -> {
                    if (leaf.fitsLong()) {
                        raw = new long[n];
                    } else {
                        builder = Vectors.builder(leaf.type(), n);
                    }
                }
                default -> builder = Vectors.builder(leaf.type(), n);
            }
        } else if (longOutput) {
            raw = new long[n];
        } else {
            if (scratch.length < n) {
                scratch = new long[n];
            }
            raw = scratch;
        }
    }

    /** Reads the next rows of the current page into the rows of the batch from {@code at} on. */
    private void segment(int at, int count) {
        int present = count;
        if (pageNulls) {
            present = 0;
            for (int i = 0; i < count; i++) {
                if (levels[pageRow + i] == maxDefinition) {
                    present++;
                } else {
                    markNull(at + i);
                }
            }
        }
        if (bytes) {
            if (codes != null) {
                if (encoding != Encoding.PLAIN || present == 0) {
                    code(at, count, present);
                    return;
                }
                uncode(at);
            }
            locate(present);
            if (target == ParquetLeaf.Target.BYTES) {
                long total = used;
                for (int k = 0; k < present; k++) {
                    total += length[k];
                }
                reserve(total);
            }
            boolean all = present == count;
            for (int i = 0, k = 0; i < count; i++) {
                if (all || !nulls[at + i]) {
                    put(at + i, source, from[k], length[k]);
                    k++;
                } else {
                    putNull(at + i);
                }
            }
        } else if (present == count) {
            decode(raw, at, count);
        } else {
            if (stage.length < present) {
                stage = new long[Math.max(present, 2 * stage.length)];
            }
            decode(stage, 0, present);
            for (int i = 0, k = 0; i < count; i++) {
                raw[at + i] = nulls[at + i] ? 0 : stage[k++];
            }
        }
    }

    /** Takes the dictionary ids of the next rows of the page as their codes, 0 for a {@code NULL}. */
    private void code(int at, int count, int present) {
        if (present == count) {
            System.arraycopy(ids, idAt, codes, at, count);
        } else {
            for (int i = 0, k = idAt; i < count; i++) {
                codes[at + i] = nulls[at + i] ? 0 : ids[k++];
            }
        }
        idAt += present;
    }

    /**
     * Turns the first rows of the batch from codes into bytes, for a page
     * that is not in the dictionary; the batches after it start with bytes.
     */
    private void uncode(int rows) {
        int[] c = codes;
        codes = null;
        coding = false;
        offsets = new int[n + 1];
        long total = 0;
        for (int i = 0; i < rows; i++) {
            total += nulls != null && nulls[i] ? 0 : shared.length(c[i]);
        }
        reserve(total);
        for (int i = 0; i < rows; i++) {
            if (nulls != null && nulls[i]) {
                putNull(i);
            } else {
                put(i, shared.data(), shared.start(c[i]), shared.length(c[i]));
            }
        }
    }

    private void markNull(int row) {
        if (nulls == null) {
            nulls = new boolean[n];
        }
        nulls[row] = true;
    }

    /** Decodes the next numbers or booleans of the page. */
    private void decode(long[] out, int at, int count) {
        if (count == 0) {
            return;
        }
        if (encoding == Encoding.PLAIN) {
            switch (physical) {
                case INT32, FLOAT -> pos = PlainDecoder.ints(page, pos, end, count, out, at);
                case INT64, DOUBLE -> pos = PlainDecoder.longs(page, pos, end, count, out, at);
                case BOOLEAN -> bit = PlainDecoder.booleans(page, pos, end, bit, count, out, at);
                default -> throw new IllegalStateException(physical.name());
            }
        } else if (encoding == Encoding.RLE) {
            for (int k = 0; k < count; k++) {
                out[at + k] = ids[idAt + k];
            }
            idAt += count;
        } else {
            long[] values = dictionary.values();
            for (int k = 0; k < count; k++) {
                out[at + k] = values[ids[idAt + k]];
            }
            idAt += count;
        }
    }

    /** Locates the next byte arrays of the page or of the dictionary. */
    private void locate(int count) {
        if (from.length < count) {
            int size = Math.max(count, 2 * from.length);
            from = new int[size];
            length = new int[size];
        }
        if (count == 0) {
            return;
        }
        if (encoding == Encoding.PLAIN) {
            source = page;
            pos = fixedWidth < 0 ? PlainDecoder.binaries(page, pos, end, count, from, length)
                    : PlainDecoder.fixed(page, pos, end, fixedWidth, count, from, length);
        } else {
            source = dictionary.data();
            int[] o = dictionary.offsets();
            for (int k = 0; k < count; k++) {
                int id = ids[idAt + k];
                from[k] = o[id];
                length[k] = o[id + 1] - o[id];
            }
            idAt += count;
        }
    }

    /** Makes room for the bytes of a batch up to a total; a batch within one page gets exactly its bytes. */
    private void reserve(long total) {
        if (total > data.length) {
            data = Arrays.copyOf(data, Math.toIntExact(Math.max(total,
                    data.length == 0 ? 0 : Math.min(2L * data.length, Integer.MAX_VALUE - 16))));
        }
    }

    /** Puts a byte array into a row of the batch. */
    private void put(int row, byte[] b, int at, int len) {
        switch (target) {
            case BYTES -> {
                if (used + len > data.length) {
                    reserve((long) used + len);
                }
                System.arraycopy(b, at, data, used, len);
                used += len;
                offsets[row + 1] = used;
            }
            case UUID -> {
                most[row] = (long) PlainDecoder.BIG_LONG.get(b, at);
                least[row] = (long) PlainDecoder.BIG_LONG.get(b, at + 8);
            }
            case INT96 -> raw[row] = ParquetLeaf.int96(b, at);
            default -> {
                if (raw != null) {
                    raw[row] = leaf.unscaled(ParquetLeaf.unscaledLong(b, at, len));
                } else {
                    leaf.addBinary(builder, b, at, len);
                }
            }
        }
    }

    /** Puts a {@code NULL} byte array into a row of the batch, marked as such already. */
    private void putNull(int row) {
        if (target == ParquetLeaf.Target.BYTES) {
            offsets[row + 1] = used;
        } else if (builder != null) {
            builder.appendNull();
        }
    }

    /** Reads rows of the batch through the column reader of parquet-java. */
    private void fill(int at, int count) {
        if (codes != null) {
            uncode(at);
        }
        coding = false;
        ColumnReader r = reader;
        for (int row = at; row < at + count; row++) {
            if (r.getCurrentDefinitionLevel() < maxDefinition) {
                markNull(row);
                if (bytes) {
                    putNull(row);
                } else {
                    raw[row] = 0;
                }
            } else if (bytes) {
                Binary value = r.getBinary();
                ByteBuffer b = value.toByteBuffer();
                if (b.hasArray()) {
                    put(row, b.array(), b.arrayOffset() + b.position(), b.remaining());
                } else {
                    byte[] copy = value.getBytesUnsafe();
                    put(row, copy, 0, copy.length);
                }
            } else {
                raw[row] = switch (physical) {
                    case INT32 -> r.getInteger();
                    case INT64 -> r.getLong();
                    case FLOAT -> Float.floatToRawIntBits(r.getFloat());
                    case DOUBLE -> Double.doubleToRawLongBits(r.getDouble());
                    case BOOLEAN -> r.getBoolean() ? 1 : 0;
                    default -> throw new IllegalStateException(physical.name());
                };
            }
            r.consume();
        }
    }

    /** Returns the vector of the batch. */
    private Vector finish() {
        long[] v = raw;
        return switch (target) {
            case BOOLEAN -> {
                boolean[] out = new boolean[n];
                for (int i = 0; i < n; i++) {
                    out[i] = v[i] != 0;
                }
                yield BooleanVector.wrap(out, nulls);
            }
            case TINYINT -> {
                byte[] out = new byte[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (byte) leaf.unsigned(v[i]);
                }
                yield TinyIntVector.wrap(out, nulls);
            }
            case SMALLINT -> {
                short[] out = new short[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (short) leaf.unsigned(v[i]);
                }
                yield SmallIntVector.wrap(out, nulls);
            }
            case INTEGER -> {
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (int) leaf.unsigned(v[i]);
                }
                yield IntVector.wrap(leaf.type(), out, nulls);
            }
            case BIGINT -> {
                for (int i = 0; i < n; i++) {
                    v[i] = leaf.unsigned(v[i]);
                }
                yield BigIntVector.wrap(leaf.type(), v, nulls);
            }
            case HUGEINT -> {
                for (int i = 0; i < n; i++) {
                    v[i] = leaf.unsigned(v[i]);
                }
                yield HugeIntVector.wrap(new long[n], v, nulls);
            }
            case TIMESTAMP, TIME_OF_DAY -> {
                for (int i = 0; i < n; i++) {
                    v[i] = leaf.micros(leaf.unsigned(v[i]));
                }
                yield BigIntVector.wrap(leaf.type(), v, nulls);
            }
            case FLOAT -> {
                float[] out = new float[n];
                for (int i = 0; i < n; i++) {
                    out[i] = Float.intBitsToFloat((int) v[i]);
                }
                yield FloatVector.wrap(out, nulls);
            }
            case DOUBLE -> {
                double[] out = new double[n];
                if (physical == PrimitiveTypeName.FLOAT) {
                    for (int i = 0; i < n; i++) {
                        out[i] = Float.intBitsToFloat((int) v[i]);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        out[i] = Double.longBitsToDouble(v[i]);
                    }
                }
                yield DoubleVector.wrap(out, nulls);
            }
            case DECIMAL -> {
                if (builder != null) {
                    yield builder.build();
                }
                if (!leaf.fitsLong()) {
                    yield build();
                }
                if (!bytes) {
                    for (int i = 0; i < n; i++) {
                        v[i] = leaf.unscaled(leaf.unsigned(v[i]));
                    }
                }
                yield DecimalVector.wrap((DataType.Decimal) leaf.type(), v, nulls);
            }
            case TIME -> build();
            case BYTES -> codes != null ? BytesVector.dictionary(leaf.type(), codes, shared, nulls)
                    : BytesVector.wrap(leaf.type(), data.length == used ? data : Arrays.copyOf(data, used), offsets,
                            nulls);
            case UUID -> UuidVector.wrap(most, least, nulls);
            case INT96 -> BigIntVector.wrap(leaf.type(), v, nulls);
            case FLOAT16, INTERVAL -> builder.build();
        };
    }

    /** Returns the integers of the batch through a builder of a vector and the conversions of the leaf. */
    private Vector build() {
        Vector.Builder b = Vectors.builder(leaf.type(), n);
        for (int i = 0; i < n; i++) {
            if (nulls != null && nulls[i]) {
                b.appendNull();
            } else if (physical == PrimitiveTypeName.INT32) {
                leaf.addInt(b, (int) raw[i]);
            } else {
                leaf.addLong(b, raw[i]);
            }
        }
        return b.build();
    }

    /**
     * Reads the next data page: its definition levels, and its dictionary
     * ids or booleans in {@code RLE}. A page in another encoding goes to
     * a column reader of parquet-java with the rest of the chunk.
     */
    private void nextPage() {
        DataPage p = pages.readPage();
        if (p == null) {
            throw new IllegalArgumentException("The column chunk ends before its last row");
        }
        if (!decodes(p)) {
            reader = fallback.apply(new Rest(pages, p, dictionary == null ? null : dictionary.page(physical),
                    pages.getTotalValueCount() - valuesRead));
            return;
        }
        valuesRead += p.getValueCount();
        pageRows = p.getValueCount();
        pageRow = 0;
        idAt = 0;
        bit = 0;
        if (levels != null && levels.length < pageRows) {
            levels = new int[pageRows];
        }
        PlainDecoder.Slice values;
        if (p instanceof DataPageV1 v1) {
            values = PlainDecoder.slice(v1.getBytes());
            pos = values.from();
            end = values.to();
            if (levels != null) {
                pos = levels(v1.getDlEncoding(), values.array(), pos, end);
            }
            encoding = v1.getValueEncoding();
        } else {
            DataPageV2 v2 = (DataPageV2) p;
            if (levels != null) {
                PlainDecoder.Slice l = PlainDecoder.slice(v2.getDefinitionLevels());
                HybridDecoder.decode(l.array(), l.from(), l.to(), levelWidth, pageRows, levels);
            }
            values = PlainDecoder.slice(v2.getData());
            pos = values.from();
            end = values.to();
            encoding = v2.getDataEncoding();
        }
        page = values.array();
        int present = pageRows;
        if (levels != null) {
            present = 0;
            for (int i = 0; i < pageRows; i++) {
                present += levels[i] == maxDefinition ? 1 : 0;
            }
        }
        pageNulls = present < pageRows;
        if (present == 0 || encoding == Encoding.PLAIN) {
            return;
        }
        if (encoding == Encoding.RLE) {
            int start = lengthPrefixed(page, pos, end);
            growIds(present);
            HybridDecoder.decode(page, start, start + (int) PlainDecoder.INT.get(page, pos), 1, present, ids);
            return;
        }
        if (dictionary == null) {
            throw new IllegalArgumentException("A page refers to a dictionary the column chunk does not have");
        }
        if (pos >= end) {
            throw PlainDecoder.truncated();
        }
        growIds(present);
        HybridDecoder.decode(page, pos + 1, end, page[pos] & 0xff, present, ids);
        dictionary.check(ids, 0, present);
    }

    /** Tells whether the decoder reads the levels and values of a page. */
    private boolean decodes(DataPage p) {
        Encoding values;
        if (p instanceof DataPageV1 v1) {
            Encoding e = v1.getDlEncoding();
            if (levels != null && e != Encoding.RLE && e != Encoding.BIT_PACKED) {
                return false;
            }
            values = v1.getValueEncoding();
        } else {
            values = ((DataPageV2) p).getDataEncoding();
        }
        return switch (values) {
            case PLAIN, PLAIN_DICTIONARY, RLE_DICTIONARY -> true;
            case RLE -> physical == PrimitiveTypeName.BOOLEAN;
            default -> false;
        };
    }

    /** Decodes the definition levels of a page v1; returns the position after them. */
    private int levels(Encoding e, byte[] b, int pos, int end) {
        if (e == Encoding.BIT_PACKED) {
            return HybridDecoder.decodeBitPacked(b, pos, end, levelWidth, pageRows, levels);
        }
        int start = lengthPrefixed(b, pos, end);
        int stop = start + (int) PlainDecoder.INT.get(b, pos);
        HybridDecoder.decode(b, start, stop, levelWidth, pageRows, levels);
        return stop;
    }

    /** Checks the length in 4 bytes at a position against the end; returns the position after it. */
    private static int lengthPrefixed(byte[] b, int pos, int end) {
        if (end - pos < 4) {
            throw PlainDecoder.truncated();
        }
        int length = (int) PlainDecoder.INT.get(b, pos);
        if (length < 0 || length > end - pos - 4) {
            throw PlainDecoder.truncated();
        }
        return pos + 4;
    }

    private void growIds(int count) {
        if (ids.length < count) {
            ids = new int[Math.max(count, 2 * ids.length)];
        }
    }

    /** A page read already and the pages after it, for a column reader of parquet-java. */
    private static final class Rest implements PageReader {
        private final PageReader pages;
        private DataPage first;
        private final DictionaryPage dictionary;
        private final long values;

        Rest(PageReader pages, DataPage first, DictionaryPage dictionary, long values) {
            this.pages = pages;
            this.first = first;
            this.dictionary = dictionary;
            this.values = values;
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
            DataPage p = first;
            if (p == null) {
                return pages.readPage();
            }
            first = null;
            return p;
        }
    }
}
