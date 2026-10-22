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
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.DataType.Struct;
import net.fyrql.core.type.Schema;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The format in which an operator spills batches to disk and reads them
 * back. A batch is its row count and its columns; a column, and each child
 * of a nested one, is a byte of flags, the mask of its {@code NULL}s as bits
 * if it has any, and the arrays of its vector as little-endian blocks:
 * strings and blobs as offsets and bytes, lists and maps as offsets and
 * their elements, structs as their fields, decimals of more than 18 digits
 * as the bytes of each unscaled value. A batch comes back with the same
 * values, also under its {@code NULL}s. Nothing is compressed, and nothing
 * describes the batches: the format lives no longer than the process that
 * writes it, and the reader is given the schema of the writer.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class SpillFormat {

    /** The default size of the buffer of a writer or reader, in bytes. */
    public static final int DEFAULT_BUFFER_BYTES = 1 << 16;

    private static final int MIN_BUFFER_BYTES = 16;
    private static final int HAS_NULLS = 1;
    private static final int BIG = 2;

    private SpillFormat() {
    }

    private static ByteBuffer buffer(int bytes) {
        if (bytes < MIN_BUFFER_BYTES) {
            throw new IllegalArgumentException("bufferBytes: " + bytes);
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Writes batches to a channel, which closing the writer closes. */
    public static final class Writer implements Closeable {
        private final WritableByteChannel channel;
        private final ByteBuffer buf;
        private long written;

        /** A writer with a buffer of the default size. */
        public Writer(WritableByteChannel channel) {
            this(channel, DEFAULT_BUFFER_BYTES);
        }

        /** A writer with a buffer of the given size, at least 16 bytes. */
        public Writer(WritableByteChannel channel, int bufferBytes) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.buf = buffer(bufferBytes);
        }

        /** Writes a batch. */
        public void write(Batch batch) throws IOException {
            write(batch, 0, batch.rowCount());
        }

        /** Writes the rows of a batch from one index to another as a batch of its own. */
        public void write(Batch batch, int from, int to) throws IOException {
            Objects.checkFromToIndex(from, to, batch.rowCount());
            ensure(4);
            buf.putInt(to - from);
            for (Vector v : batch.columns()) {
                put(v, from, to);
            }
        }

        /** Returns the bytes written so far, including those still in the buffer. */
        public long bytes() {
            return written + buf.position();
        }

        /** Writes what the buffer holds to the channel. */
        public void flush() throws IOException {
            buf.flip();
            written += buf.remaining();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            buf.clear();
        }

        /** Flushes the buffer and closes the channel. */
        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                channel.close();
            }
        }

        private void put(Vector v, int from, int to) throws IOException {
            int n = to - from;
            boolean nulls = false;
            for (int r = from; r < to && !nulls; r++) {
                nulls = v.isNull(r);
            }
            boolean big = v instanceof DecimalVector d && d.big != null;
            ensure(1);
            buf.put((byte) ((nulls ? HAS_NULLS : 0) | (big ? BIG : 0)));
            if (nulls) {
                putNulls(v, from, to);
            }
            switch (v) {
                case BooleanVector x -> putBooleans(x.values, from, n);
                case TinyIntVector x -> putBytes(x.values, from, n);
                case SmallIntVector x -> putShorts(x.values, from, n);
                case IntVector x -> putInts(x.values, from, n);
                case BigIntVector x -> putLongs(x.values, from, n);
                case HugeIntVector x -> {
                    putLongs(x.high, from, n);
                    putLongs(x.low, from, n);
                }
                case FloatVector x -> putFloats(x.values, from, n);
                case DoubleVector x -> putDoubles(x.values, from, n);
                case DecimalVector x when x.small != null -> putLongs(x.small, from, n);
                case DecimalVector x -> putBigs(x.big, from, n);
                case BytesVector x -> {
                    int base = x.offsets[from];
                    if (base == 0) {
                        putInts(x.offsets, from, n + 1);
                    } else {
                        for (int r = from; r <= to; r++) {
                            putInt(x.offsets[r] - base);
                        }
                    }
                    putBytes(x.data(), base, x.offsets[to] - base);
                }
                case UuidVector x -> {
                    putLongs(x.most, from, n);
                    putLongs(x.least, from, n);
                }
                case IntervalVector x -> {
                    putInts(x.months, from, n);
                    putInts(x.days, from, n);
                    putLongs(x.micros, from, n);
                }
                case StructVector x -> {
                    for (Vector child : x.children()) {
                        put(child, from, to);
                    }
                }
                case ListVector x -> {
                    int base = x.start(from);
                    for (int r = from; r <= to; r++) {
                        putInt(x.start(r) - base);
                    }
                    put(x.child(), base, x.start(to));
                }
                case MapVector x -> {
                    int base = x.start(from);
                    for (int r = from; r <= to; r++) {
                        putInt(x.start(r) - base);
                    }
                    put(x.keys(), base, x.start(to));
                    put(x.values(), base, x.start(to));
                }
            }
        }

        private void putNulls(Vector v, int from, int to) throws IOException {
            byte[] bits = new byte[(to - from + 7) >>> 3];
            for (int r = from; r < to; r++) {
                if (v.isNull(r)) {
                    int i = r - from;
                    bits[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            putBytes(bits, 0, bits.length);
        }

        private void putBooleans(boolean[] a, int from, int n) throws IOException {
            for (int i = 0; i < n; i++) {
                ensure(1);
                buf.put((byte) (a[from + i] ? 1 : 0));
            }
        }

        private void putBytes(byte[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(1);
                int k = Math.min(n - i, buf.remaining());
                buf.put(a, from + i, k);
                i += k;
            }
        }

        private void putShorts(short[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(2);
                int k = Math.min(n - i, buf.remaining() / 2);
                buf.asShortBuffer().put(a, from + i, k);
                buf.position(buf.position() + k * 2);
                i += k;
            }
        }

        private void putInt(int value) throws IOException {
            ensure(4);
            buf.putInt(value);
        }

        private void putInts(int[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(4);
                int k = Math.min(n - i, buf.remaining() / 4);
                buf.asIntBuffer().put(a, from + i, k);
                buf.position(buf.position() + k * 4);
                i += k;
            }
        }

        private void putLongs(long[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(8);
                int k = Math.min(n - i, buf.remaining() / 8);
                buf.asLongBuffer().put(a, from + i, k);
                buf.position(buf.position() + k * 8);
                i += k;
            }
        }

        private void putFloats(float[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(4);
                int k = Math.min(n - i, buf.remaining() / 4);
                buf.asFloatBuffer().put(a, from + i, k);
                buf.position(buf.position() + k * 4);
                i += k;
            }
        }

        private void putDoubles(double[] a, int from, int n) throws IOException {
            int i = 0;
            while (i < n) {
                ensure(8);
                int k = Math.min(n - i, buf.remaining() / 8);
                buf.asDoubleBuffer().put(a, from + i, k);
                buf.position(buf.position() + k * 8);
                i += k;
            }
        }

        /** Writes each value as its length and its bytes, -1 for a missing one. */
        private void putBigs(BigInteger[] a, int from, int n) throws IOException {
            for (int i = 0; i < n; i++) {
                BigInteger value = a[from + i];
                if (value == null) {
                    putInt(-1);
                } else {
                    byte[] bytes = value.toByteArray();
                    putInt(bytes.length);
                    putBytes(bytes, 0, bytes.length);
                }
            }
        }

        private void ensure(int n) throws IOException {
            if (buf.remaining() < n) {
                flush();
            }
        }
    }

    /** Reads batches from a channel, one at a time; closing the reader closes the channel. */
    public static final class Reader implements Closeable {
        private final ReadableByteChannel channel;
        private final Schema schema;
        private final ByteBuffer buf;
        private long read;
        private boolean end;

        /** A reader of batches of a schema with a buffer of the default size. */
        public Reader(ReadableByteChannel channel, Schema schema) {
            this(channel, schema, DEFAULT_BUFFER_BYTES);
        }

        /** A reader of batches of a schema with a buffer of the given size, at least 16 bytes. */
        public Reader(ReadableByteChannel channel, Schema schema, int bufferBytes) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.schema = Objects.requireNonNull(schema, "schema");
            this.buf = buffer(bufferBytes);
            buf.flip();
        }

        /**
         * Returns the next batch, or {@code null} at the end of the channel.
         *
         * @throws IOException if the channel fails or ends inside a batch
         */
        public Batch next() throws IOException {
            if (!fill(4)) {
                if (buf.hasRemaining()) {
                    throw truncated();
                }
                return null;
            }
            int rows = count(buf.getInt());
            List<Vector> columns = new ArrayList<>(schema.size());
            for (int c = 0; c < schema.size(); c++) {
                columns.add(get(schema.type(c), rows));
            }
            return new Batch(schema, rows, columns);
        }

        /** Returns the bytes read from the channel so far. */
        public long bytes() {
            return read;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        private Vector get(DataType type, int n) throws IOException {
            need(1);
            int flags = buf.get();
            boolean[] nulls = (flags & HAS_NULLS) != 0 ? getNulls(n) : null;
            switch (type) {
                case Decimal d -> {
                    return (flags & BIG) != 0 ? new DecimalVector(d, null, getBigs(n), nulls)
                            : new DecimalVector(d, getLongs(n), null, nulls);
                }
                case Struct s -> {
                    List<Vector> children = new ArrayList<>(s.fields().size());
                    for (Struct.Field f : s.fields()) {
                        children.add(get(f.type(), n));
                    }
                    return new StructVector(s, children, nulls, n);
                }
                case ListType l -> {
                    int[] offsets = getOffsets(n);
                    return new ListVector(l, offsets, get(l.element(), offsets[n]), nulls);
                }
                case MapType m -> {
                    int[] offsets = getOffsets(n);
                    Vector keys = get(m.key(), offsets[n]);
                    return new MapVector(m, offsets, keys, get(m.value(), offsets[n]), nulls);
                }
                case Scalar s -> {
                    return scalar(s, n, nulls);
                }
            }
        }

        private Vector scalar(Scalar type, int n, boolean[] nulls) throws IOException {
            return switch (type) {
                case BOOLEAN -> new BooleanVector(getBooleans(n), nulls);
                case TINYINT -> new TinyIntVector(getBytes(n), nulls);
                case SMALLINT -> new SmallIntVector(getShorts(n), nulls);
                case INTEGER, DATE -> new IntVector(type, getInts(n), nulls);
                case BIGINT, TIMESTAMP, TIME, TIMESTAMPTZ -> new BigIntVector(type, getLongs(n), nulls);
                case HUGEINT -> {
                    long[] high = getLongs(n);
                    yield new HugeIntVector(high, getLongs(n), nulls);
                }
                case FLOAT -> new FloatVector(getFloats(n), nulls);
                case DOUBLE -> new DoubleVector(getDoubles(n), nulls);
                case VARCHAR, BLOB -> {
                    int[] offsets = getOffsets(n);
                    yield new BytesVector(type, getBytes(offsets[n]), offsets, nulls);
                }
                case UUID -> {
                    long[] most = getLongs(n);
                    yield new UuidVector(most, getLongs(n), nulls);
                }
                case INTERVAL -> {
                    int[] months = getInts(n);
                    int[] days = getInts(n);
                    yield new IntervalVector(months, days, getLongs(n), nulls);
                }
            };
        }

        private boolean[] getNulls(int n) throws IOException {
            byte[] bits = getBytes((n + 7) >>> 3);
            boolean[] nulls = new boolean[n];
            for (int i = 0; i < n; i++) {
                nulls[i] = (bits[i >>> 3] & (1 << (i & 7))) != 0;
            }
            return nulls;
        }

        /** Reads the offsets of {@code n} rows, which start at 0 and do not decrease. */
        private int[] getOffsets(int n) throws IOException {
            int[] offsets = getInts(n + 1);
            for (int i = 0; i <= n; i++) {
                if (i == 0 ? offsets[0] != 0 : offsets[i] < offsets[i - 1]) {
                    throw new IOException("Corrupt spill data: offsets out of order");
                }
            }
            return offsets;
        }

        private boolean[] getBooleans(int n) throws IOException {
            boolean[] a = new boolean[n];
            for (int i = 0; i < n; i++) {
                need(1);
                a[i] = buf.get() != 0;
            }
            return a;
        }

        private byte[] getBytes(int n) throws IOException {
            byte[] a = new byte[n];
            int i = 0;
            while (i < n) {
                need(1);
                int k = Math.min(n - i, buf.remaining());
                buf.get(a, i, k);
                i += k;
            }
            return a;
        }

        private short[] getShorts(int n) throws IOException {
            short[] a = new short[n];
            int i = 0;
            while (i < n) {
                need(2);
                int k = Math.min(n - i, buf.remaining() / 2);
                buf.asShortBuffer().get(a, i, k);
                buf.position(buf.position() + k * 2);
                i += k;
            }
            return a;
        }

        private int[] getInts(int n) throws IOException {
            int[] a = new int[n];
            int i = 0;
            while (i < n) {
                need(4);
                int k = Math.min(n - i, buf.remaining() / 4);
                buf.asIntBuffer().get(a, i, k);
                buf.position(buf.position() + k * 4);
                i += k;
            }
            return a;
        }

        private long[] getLongs(int n) throws IOException {
            long[] a = new long[n];
            int i = 0;
            while (i < n) {
                need(8);
                int k = Math.min(n - i, buf.remaining() / 8);
                buf.asLongBuffer().get(a, i, k);
                buf.position(buf.position() + k * 8);
                i += k;
            }
            return a;
        }

        private float[] getFloats(int n) throws IOException {
            float[] a = new float[n];
            int i = 0;
            while (i < n) {
                need(4);
                int k = Math.min(n - i, buf.remaining() / 4);
                buf.asFloatBuffer().get(a, i, k);
                buf.position(buf.position() + k * 4);
                i += k;
            }
            return a;
        }

        private double[] getDoubles(int n) throws IOException {
            double[] a = new double[n];
            int i = 0;
            while (i < n) {
                need(8);
                int k = Math.min(n - i, buf.remaining() / 8);
                buf.asDoubleBuffer().get(a, i, k);
                buf.position(buf.position() + k * 8);
                i += k;
            }
            return a;
        }

        private BigInteger[] getBigs(int n) throws IOException {
            BigInteger[] a = new BigInteger[n];
            for (int i = 0; i < n; i++) {
                need(4);
                int length = buf.getInt();
                if (length >= 0) {
                    a[i] = new BigInteger(getBytes(length));
                } else if (length != -1) {
                    throw new IOException("Corrupt spill data: a value of " + length + " bytes");
                }
            }
            return a;
        }

        private static int count(int rows) throws IOException {
            if (rows < 0) {
                throw new IOException("Corrupt spill data: a batch of " + rows + " rows");
            }
            return rows;
        }

        private static IOException truncated() {
            return new IOException("The spill data ends inside a batch");
        }

        private void need(int n) throws IOException {
            if (!fill(n)) {
                throw truncated();
            }
        }

        /** Makes at least {@code n} bytes available; false if the channel ends before. */
        private boolean fill(int n) throws IOException {
            if (buf.remaining() >= n) {
                return true;
            }
            buf.compact();
            while (buf.position() < n && !end) {
                int k = channel.read(buf);
                if (k < 0) {
                    end = true;
                } else {
                    read += k;
                }
            }
            buf.flip();
            return buf.remaining() >= n;
        }
    }
}
