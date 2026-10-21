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
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.FloatVector;
import net.fyrql.core.vector.HugeIntVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.IntervalVector;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.MapVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.UuidVector;
import net.fyrql.core.vector.Vector;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import java.math.BigInteger;
import java.util.List;

/**
 * Writes batches of FyrQL into Arrow vectors, in the types of
 * {@link ArrowTypes}. The values are copied: the vectors of FyrQL live on
 * the heap, those of Arrow outside of it. Values of fixed width go straight
 * into the buffers of Arrow, the bytes of strings and blobs in one copy per
 * batch, and the validity bits at once where a column has no
 * {@code NULL}; decimals are written little-endian, as Arrow stores them on
 * the machines Java runs on.
 */
public final class BatchToArrow {

    private static final int DECIMAL_WIDTH = 16;

    /** A byte of validity bits whose eight rows are all valid. */
    private static final int ALL_VALID = 0xFF;

    /** The largest {@code HUGEINT} that fits {@code Decimal128(38, 0)}: {@code 10^38 - 1}, as halves. */
    private static final long MAX_HIGH;
    private static final long MAX_LOW;

    static {
        BigInteger max = BigInteger.TEN.pow(DataType.MAX_DECIMAL_PRECISION).subtract(BigInteger.ONE);
        MAX_HIGH = max.shiftRight(64).longValueExact();
        MAX_LOW = max.longValue();
    }

    private BatchToArrow() {
    }

    /** Creates an empty root for batches of a schema; the caller closes it. */
    public static VectorSchemaRoot root(Schema schema, BufferAllocator allocator) {
        return VectorSchemaRoot.create(ArrowTypes.toArrow(schema), allocator);
    }

    /**
     * Writes a batch into a root of its schema, as {@link #root} creates it,
     * replacing the rows it held before; the buffers it held are released,
     * so that a reader that still holds them, such as a stream being sent,
     * keeps them unchanged.
     *
     * @throws ArrowTypeException       if a {@code HUGEINT} has more than 38 digits, or an interval more
     *                                  nanoseconds than 64 bits hold
     * @throws IllegalArgumentException if the root has another number of columns
     */
    public static void fill(Batch batch, VectorSchemaRoot root) {
        List<FieldVector> vectors = root.getFieldVectors();
        if (vectors.size() != batch.schema().size()) {
            throw new IllegalArgumentException("The root has " + vectors.size() + " columns, the batch "
                    + batch.schema().size());
        }
        int rows = batch.rowCount();
        for (int c = 0; c < vectors.size(); c++) {
            Vector v = batch.column(c);
            FieldVector out = vectors.get(c);
            if (out instanceof BaseVariableWidthVector bytes && v instanceof BytesVector in) {
                bytes.allocateNew(Math.max(1, rows == 0 ? 0 : in.end(rows - 1) - in.start(0)), Math.max(1, rows));
            } else {
                out.setInitialCapacity(Math.max(1, rows));
                out.allocateNew();
            }
            write(v, 0, rows, out, batch.schema().name(c));
        }
        root.setRowCount(rows);
    }

    /** Writes the rows {@code from} to {@code to} of a vector into the positions from 0 of an Arrow vector. */
    private static void write(Vector v, int from, int to, FieldVector out, String path) {
        switch (v) {
            case BooleanVector b -> {
                ArrowBuf data = fixed((BitVector) out, b, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (!b.isNull(r) && b.getBoolean(r)) {
                        BitVectorHelper.setBit(data, i);
                    }
                }
            }
            case TinyIntVector t -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, t, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setByte(i, t.getByte(r));
                }
            }
            case SmallIntVector s -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, s, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setShort((long) i * Short.BYTES, s.getShort(r));
                }
            }
            case IntVector n -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, n, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setInt((long) i * Integer.BYTES, n.getInt(r));
                }
            }
            case BigIntVector n -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, n, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setLong((long) i * Long.BYTES, n.getLong(r));
                }
            }
            case HugeIntVector h -> writeHugeInt(h, from, to, (org.apache.arrow.vector.DecimalVector) out, path);
            case FloatVector f -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, f, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setFloat((long) i * Float.BYTES, f.getFloat(r));
                }
            }
            case DoubleVector d -> {
                ArrowBuf data = fixed((BaseFixedWidthVector) out, d, from, to);
                for (int r = from, i = 0; r < to; r++, i++) {
                    data.setDouble((long) i * Double.BYTES, d.getDouble(r));
                }
            }
            case DecimalVector d -> {
                org.apache.arrow.vector.DecimalVector o = (org.apache.arrow.vector.DecimalVector) out;
                ArrowBuf data = fixed(o, d, from, to);
                if (d.isSmall()) {
                    for (int r = from, i = 0; r < to; r++, i++) {
                        long unscaled = d.getUnscaledLong(r);
                        data.setLong((long) i * DECIMAL_WIDTH, unscaled);
                        data.setLong((long) i * DECIMAL_WIDTH + Long.BYTES, unscaled >> (Long.SIZE - 1));
                    }
                } else {
                    for (int r = from, i = 0; r < to; r++, i++) {
                        if (!d.isNull(r)) {
                            o.setBigEndianSafe(i, d.getUnscaled(r).toByteArray());
                        }
                    }
                }
            }
            case BytesVector b -> writeBytes(b, from, to, (BaseVariableWidthVector) out);
            case UuidVector u -> {
                org.apache.arrow.vector.UuidVector o = (org.apache.arrow.vector.UuidVector) out;
                byte[] bytes = new byte[16];
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (u.isNull(r)) {
                        o.setNull(i);
                    } else {
                        putLong(bytes, 0, u.getMost(r));
                        putLong(bytes, 8, u.getLeast(r));
                        o.setSafe(i, bytes);
                    }
                }
            }
            case IntervalVector n -> {
                org.apache.arrow.vector.IntervalMonthDayNanoVector o =
                        (org.apache.arrow.vector.IntervalMonthDayNanoVector) out;
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (n.isNull(r)) {
                        o.setNull(i);
                    } else {
                        long nanos;
                        try {
                            nanos = Math.multiplyExact(n.getMicros(r), 1000L);
                        } catch (ArithmeticException e) {
                            throw new ArrowTypeException(path, "the interval " + n.get(r) + " has more nanoseconds"
                                    + " than Interval(MONTH_DAY_NANO) holds");
                        }
                        o.setSafe(i, n.getMonths(r), n.getDays(r), nanos);
                    }
                }
            }
            case ListVector l -> {
                org.apache.arrow.vector.complex.ListVector o = (org.apache.arrow.vector.complex.ListVector) out;
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (l.isNull(r)) {
                        o.setNull(i);
                    } else {
                        o.startNewValue(i);
                        o.endValue(i, l.length(r));
                    }
                }
                if (to > from) {
                    write(l.child(), l.start(from), l.end(to - 1), o.getDataVector(), path + "."
                            + ArrowTypes.LIST_ELEMENT);
                }
            }
            case MapVector m -> {
                org.apache.arrow.vector.complex.MapVector o = (org.apache.arrow.vector.complex.MapVector) out;
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (m.isNull(r)) {
                        o.setNull(i);
                    } else {
                        o.startNewValue(i);
                        o.endValue(i, m.length(r));
                    }
                }
                if (to > from) {
                    org.apache.arrow.vector.complex.StructVector entries =
                            (org.apache.arrow.vector.complex.StructVector) o.getDataVector();
                    int start = m.start(from);
                    int end = m.end(to - 1);
                    for (int e = 0; e < end - start; e++) {
                        entries.setIndexDefined(e);
                    }
                    List<FieldVector> kv = entries.getChildrenFromFields();
                    write(m.keys(), start, end, kv.get(0), path + ".key");
                    write(m.values(), start, end, kv.get(1), path + ".value");
                }
            }
            case StructVector s -> {
                org.apache.arrow.vector.complex.StructVector o = (org.apache.arrow.vector.complex.StructVector) out;
                for (int r = from, i = 0; r < to; r++, i++) {
                    if (s.isNull(r)) {
                        o.setNull(i);
                    } else {
                        o.setIndexDefined(i);
                    }
                }
                List<FieldVector> children = o.getChildrenFromFields();
                for (int f = 0; f < children.size(); f++) {
                    write(s.child(f), from, to, children.get(f), path + "." + s.type().fields().get(f).name());
                }
            }
        }
    }

    /**
     * Makes room for the rows {@code from} to {@code to} of a vector in an
     * Arrow vector of fixed width, from position 0, and marks the rows that
     * are not {@code NULL} as valid. The buffers of an Arrow vector are zero
     * after it is allocated, so that a {@code NULL} stays unmarked. Returns
     * the buffer of the values, which the caller writes, also at the rows
     * that are {@code NULL}.
     */
    private static ArrowBuf fixed(BaseFixedWidthVector out, Vector v, int from, int to) {
        while (out.getValueCapacity() < to - from) {
            out.reAlloc();
        }
        valid(out.getValidityBuffer(), v, from, to);
        return out.getDataBuffer();
    }

    /** Sets the validity bits from position 0 of the rows {@code from} to {@code to} that are not {@code NULL}. */
    private static void valid(ArrowBuf validity, Vector v, int from, int to) {
        if (v.hasNulls()) {
            for (int r = from, i = 0; r < to; r++, i++) {
                if (!v.isNull(r)) {
                    BitVectorHelper.setBit(validity, i);
                }
            }
            return;
        }
        int rows = to - from;
        int bytes = rows >>> 3;
        for (int k = 0; k < bytes; k++) {
            validity.setByte(k, ALL_VALID);
        }
        for (int i = bytes << 3; i < rows; i++) {
            BitVectorHelper.setBit(validity, i);
        }
    }

    /**
     * Writes strings or blobs: the bytes of the rows {@code from} to
     * {@code to}, which lie one after the other, in one copy, and their
     * offsets, moved to start at 0.
     */
    private static void writeBytes(BytesVector b, int from, int to, BaseVariableWidthVector out) {
        int rows = to - from;
        if (rows == 0) {
            return;
        }
        int base = b.start(from);
        int bytes = b.end(to - 1) - base;
        while (out.getValueCapacity() < rows) {
            out.reallocValidityAndOffsetBuffers();
        }
        if (out.getDataBuffer().capacity() < bytes) {
            out.reallocDataBuffer(bytes);
        }
        valid(out.getValidityBuffer(), b, from, to);
        out.getDataBuffer().setBytes(0, b.data(), base, bytes);
        ArrowBuf offsets = out.getOffsetBuffer();
        offsets.setInt(0, 0);
        for (int r = from, i = 1; r < to; r++, i++) {
            offsets.setInt((long) i * BaseVariableWidthVector.OFFSET_WIDTH, b.end(r) - base);
        }
        out.setLastSet(rows - 1);
    }

    /**
     * Writes {@code HUGEINT} values as 128-bit decimals, the low half first
     * as Arrow stores them on little-endian machines.
     */
    private static void writeHugeInt(HugeIntVector h, int from, int to, org.apache.arrow.vector.DecimalVector out,
            String path) {
        for (int r = from, i = 0; r < to; r++, i++) {
            if (h.isNull(r)) {
                out.setNull(i);
                continue;
            }
            long high = h.getHigh(r);
            long low = h.getLow(r);
            if (HugeIntVector.compare(high, low, MAX_HIGH, MAX_LOW) > 0
                    || HugeIntVector.compare(high, low, ~MAX_HIGH, -MAX_LOW) < 0) {
                throw new ArrowTypeException(path, "the HUGEINT " + HugeIntVector.toBigInteger(high, low)
                        + " has more than 38 digits and does not fit DECIMAL(38,0)");
            }
            out.setSafe(i, 0L);
            ArrowBuf data = out.getDataBuffer();
            data.setLong((long) i * DECIMAL_WIDTH, low);
            data.setLong((long) i * DECIMAL_WIDTH + 8, high);
        }
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        for (int b = 7; b >= 0; b--) {
            bytes[offset + b] = (byte) value;
            value >>>= 8;
        }
    }
}
