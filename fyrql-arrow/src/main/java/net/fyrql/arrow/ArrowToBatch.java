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
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.MapVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.UuidVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DateMilliVector;
import org.apache.arrow.vector.Decimal256Vector;
import org.apache.arrow.vector.ExtensionTypeVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.NullVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TimeStampSecVector;
import org.apache.arrow.vector.UInt1Vector;
import org.apache.arrow.vector.UInt2Vector;
import org.apache.arrow.vector.UInt4Vector;
import org.apache.arrow.vector.UInt8Vector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VariableWidthFieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.FixedSizeListVector;
import org.apache.arrow.vector.dictionary.Dictionary;
import org.apache.arrow.vector.dictionary.DictionaryEncoder;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.types.pojo.Field;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads Arrow vectors into batches of FyrQL, in the types of
 * {@link ArrowTypes}. The values are copied, so the batch outlives the
 * vectors.
 */
public final class ArrowToBatch {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private static final int DECIMAL_WIDTH = 16;

    /** The largest unscaled value of each precision up to 18, at its index. */
    private static final long[] MAX_UNSCALED = new long[19];

    static {
        long max = 0;
        for (int p = 1; p < MAX_UNSCALED.length; p++) {
            max = max * 10 + 9;
            MAX_UNSCALED[p] = max;
        }
    }

    private ArrowToBatch() {
    }

    /**
     * Reads the rows of a root.
     *
     * @throws ArrowTypeException if a field has a type FyrQL does not read,
     *                            is dictionary-encoded, or holds a value
     *                            that does not fit its type
     */
    public static Batch batch(VectorSchemaRoot root) {
        return batch(root, null);
    }

    /**
     * Reads the rows of a root whose fields may be dictionary-encoded, such
     * as the root of an {@link org.apache.arrow.vector.ipc.ArrowReader},
     * which is also the provider of its dictionaries; a dictionary-encoded
     * field becomes a column of the values of its dictionary.
     *
     * @param dictionaries the dictionaries of the fields, or {@code null} if
     *                     none is dictionary-encoded
     * @throws ArrowTypeException if a field has a type FyrQL does not read,
     *                            is dictionary-encoded inside a nested type,
     *                            or holds a value that does not fit its type
     */
    public static Batch batch(VectorSchemaRoot root, DictionaryProvider dictionaries) {
        int rows = root.getRowCount();
        List<FieldVector> vectors = root.getFieldVectors();
        List<Schema.Field> fields = new ArrayList<>(vectors.size());
        List<Vector> columns = new ArrayList<>(vectors.size());
        for (FieldVector v : vectors) {
            Field field = v.getField();
            String name = field.getName();
            if (field.getDictionary() == null) {
                DataType type = ArrowTypes.toFyrQL(field, name);
                columns.add(read(v, type, rows, name));
                fields.add(new Schema.Field(name, type, field.isNullable()));
                continue;
            }
            Dictionary dictionary = dictionaries == null ? null : dictionaries.lookup(field.getDictionary().getId());
            if (dictionary == null) {
                throw new ArrowTypeException(name, "the dictionary " + field.getDictionary().getId()
                        + " of the field is missing");
            }
            try (ValueVector decoded = DictionaryEncoder.decode(v, dictionary)) {
                DataType type = ArrowTypes.toFyrQL(decoded.getField(), name);
                columns.add(read(decoded, type, rows, name));
                fields.add(new Schema.Field(name, type, field.isNullable()));
            }
        }
        return new Batch(new Schema(fields), rows, columns);
    }

    private static Vector read(ValueVector in, DataType type, int rows, String path) {
        Vector.Builder b = Vectors.builder(type, rows);
        append(in, type, 0, rows, b, path);
        return b.build();
    }

    /** Appends the values at the positions {@code from} to {@code to} of an Arrow vector to a builder. */
    private static void append(ValueVector in, DataType type, int from, int to, Vector.Builder out, String path) {
        switch (in) {
            case NullVector n -> {
                for (int r = from; r < to; r++) {
                    out.appendNull();
                }
            }
            case BitVector v -> {
                BooleanVector.Builder o = (BooleanVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendBoolean(v.get(r) != 0);
                    }
                }
            }
            case org.apache.arrow.vector.TinyIntVector v -> {
                TinyIntVector.Builder o = (TinyIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendByte(v.get(r));
                    }
                }
            }
            case org.apache.arrow.vector.SmallIntVector v -> {
                SmallIntVector.Builder o = (SmallIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendShort(v.get(r));
                    }
                }
            }
            case UInt1Vector v -> {
                SmallIntVector.Builder o = (SmallIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendShort((short) (v.get(r) & 0xFF));
                    }
                }
            }
            case org.apache.arrow.vector.IntVector v -> {
                IntVector.Builder o = (IntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendInt(v.get(r));
                    }
                }
            }
            case UInt2Vector v -> {
                IntVector.Builder o = (IntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendInt(v.get(r));
                    }
                }
            }
            case DateDayVector v -> {
                IntVector.Builder o = (IntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendInt(v.get(r));
                    }
                }
            }
            case DateMilliVector v -> {
                IntVector.Builder o = (IntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        long days = Math.floorDiv(v.get(r), MILLIS_PER_DAY);
                        if (days != (int) days) {
                            throw new ArrowTypeException(path, "the date " + v.get(r)
                                    + " ms is out of the range of DATE");
                        }
                        o.appendInt((int) days);
                    }
                }
            }
            case org.apache.arrow.vector.BigIntVector v -> {
                BigIntVector.Builder o = (BigIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendLong(v.get(r));
                    }
                }
            }
            case UInt4Vector v -> {
                BigIntVector.Builder o = (BigIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendLong(v.get(r) & 0xFFFF_FFFFL);
                    }
                }
            }
            case TimeStampMicroVector v -> {
                BigIntVector.Builder o = (BigIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendLong(v.get(r));
                    }
                }
            }
            case TimeStampSecVector v -> timestamps(v, from, to, 1_000_000L, (BigIntVector.Builder) out, path);
            case TimeStampMilliVector v -> timestamps(v, from, to, 1_000L, (BigIntVector.Builder) out, path);
            case TimeStampNanoVector v -> {
                BigIntVector.Builder o = (BigIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendLong(Math.floorDiv(v.get(r), 1_000L));
                    }
                }
            }
            case UInt8Vector v -> {
                HugeIntVector.Builder o = (HugeIntVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendHalves(0, v.get(r));
                    }
                }
            }
            case Float2Vector v -> {
                FloatVector.Builder o = (FloatVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendFloat(v.getValueAsFloat(r));
                    }
                }
            }
            case Float4Vector v -> {
                FloatVector.Builder o = (FloatVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendFloat(v.get(r));
                    }
                }
            }
            case Float8Vector v -> {
                DoubleVector.Builder o = (DoubleVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendDouble(v.get(r));
                    }
                }
            }
            case org.apache.arrow.vector.DecimalVector v -> decimals(v, type, from, to, out, path);
            case Decimal256Vector v -> {
                DecimalVector.Builder o = (DecimalVector.Builder) out;
                DataType.Decimal d = (DataType.Decimal) type;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendUnscaled(checked(v.getObject(r).unscaledValue(), d, path));
                    }
                }
            }
            case org.apache.arrow.vector.UuidVector v -> {
                UuidVector.Builder o = (UuidVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendUuid(v.getObject(r));
                    }
                }
            }
            case FixedSizeBinaryVector v -> {
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        out.appendNull();
                    } else if (type == DataType.UUID) {
                        ((UuidVector.Builder) out).appendUuid(uuid(v.get(r)));
                    } else {
                        ((BytesVector.Builder) out).appendBytes(v.get(r));
                    }
                }
            }
            case BaseVariableWidthVector v -> bytes(v, from, to, (BytesVector.Builder) out);
            case VariableWidthFieldVector v -> {
                BytesVector.Builder o = (BytesVector.Builder) out;
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        o.appendBytes(v.get(r));
                    }
                }
            }
            case org.apache.arrow.vector.complex.MapVector v -> maps(v, (DataType.MapType) type, from, to,
                    (MapVector.Builder) out, path);
            case org.apache.arrow.vector.complex.ListVector v -> {
                ListVector.Builder o = (ListVector.Builder) out;
                DataType element = ((DataType.ListType) type).element();
                ValueVector child = v.getDataVector();
                String childPath = path + "." + child.getName();
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        append(child, element, v.getElementStartIndex(r), v.getElementEndIndex(r), o.elements(),
                                childPath);
                        o.endList();
                    }
                }
            }
            case FixedSizeListVector v -> {
                ListVector.Builder o = (ListVector.Builder) out;
                DataType element = ((DataType.ListType) type).element();
                ValueVector child = v.getDataVector();
                String childPath = path + "." + child.getName();
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                    } else {
                        append(child, element, v.getElementStartIndex(r), v.getElementEndIndex(r), o.elements(),
                                childPath);
                        o.endList();
                    }
                }
            }
            case org.apache.arrow.vector.complex.StructVector v -> {
                StructVector.Builder o = (StructVector.Builder) out;
                DataType.Struct struct = (DataType.Struct) type;
                List<FieldVector> children = v.getChildrenFromFields();
                for (int r = from; r < to; r++) {
                    if (v.isNull(r)) {
                        o.appendNull();
                        continue;
                    }
                    for (int f = 0; f < children.size(); f++) {
                        DataType.Struct.Field field = struct.fields().get(f);
                        append(children.get(f), field.type(), r, r + 1, o.field(f), path + "." + field.name());
                    }
                    o.endRow();
                }
            }
            case ExtensionTypeVector<?> v -> append(v.getUnderlyingVector(), type, from, to, out, path);
            default -> throw new ArrowTypeException(path, "FyrQL does not read the Arrow vector "
                    + in.getClass().getSimpleName());
        }
    }

    private static void timestamps(org.apache.arrow.vector.TimeStampVector v, int from, int to, long factor,
            BigIntVector.Builder out, String path) {
        for (int r = from; r < to; r++) {
            if (v.isNull(r)) {
                out.appendNull();
                continue;
            }
            try {
                out.appendLong(Math.multiplyExact(v.get(r), factor));
            } catch (ArithmeticException e) {
                throw new ArrowTypeException(path, "the timestamp " + v.get(r) + " is out of the range of TIMESTAMP");
            }
        }
    }

    /** Reads 128-bit decimals, the low half first as Arrow stores them on little-endian machines. */
    private static void decimals(org.apache.arrow.vector.DecimalVector v, DataType type, int from, int to,
            Vector.Builder out, String path) {
        ArrowBuf data = v.getDataBuffer();
        if (type == DataType.HUGEINT) {
            HugeIntVector.Builder o = (HugeIntVector.Builder) out;
            for (int r = from; r < to; r++) {
                if (v.isNull(r)) {
                    o.appendNull();
                } else {
                    o.appendHalves(data.getLong((long) r * DECIMAL_WIDTH + 8), data.getLong((long) r * DECIMAL_WIDTH));
                }
            }
            return;
        }
        DataType.Decimal d = (DataType.Decimal) type;
        DecimalVector.Builder o = (DecimalVector.Builder) out;
        for (int r = from; r < to; r++) {
            if (v.isNull(r)) {
                o.appendNull();
                continue;
            }
            long low = data.getLong((long) r * DECIMAL_WIDTH);
            long high = data.getLong((long) r * DECIMAL_WIDTH + 8);
            if (d.fitsLong()) {
                if (high != low >> 63 || low > MAX_UNSCALED[d.precision()] || low < -MAX_UNSCALED[d.precision()]) {
                    throw tooLarge(HugeIntVector.toBigInteger(high, low), d, path);
                }
                o.appendUnscaled(low);
            } else {
                o.appendUnscaled(checked(HugeIntVector.toBigInteger(high, low), d, path));
            }
        }
    }

    private static BigInteger checked(BigInteger unscaled, DataType.Decimal type, String path) {
        if (unscaled.abs().compareTo(type.maxUnscaled()) > 0) {
            throw tooLarge(unscaled, type, path);
        }
        return unscaled;
    }

    private static ArrowTypeException tooLarge(BigInteger unscaled, DataType.Decimal type, String path) {
        return new ArrowTypeException(path, "the unscaled value " + unscaled + " has more digits than " + type);
    }

    /** Reads strings or bytes with 32-bit offsets, copying the data of all rows at once. */
    private static void bytes(BaseVariableWidthVector v, int from, int to, BytesVector.Builder out) {
        if (to == from) {
            return;
        }
        int start = v.getStartOffset(from);
        byte[] data = new byte[v.getEndOffset(to - 1) - start];
        v.getDataBuffer().getBytes(start, data);
        for (int r = from; r < to; r++) {
            if (v.isNull(r)) {
                out.appendNull();
            } else {
                int s = v.getStartOffset(r);
                out.appendBytes(data, s - start, v.getEndOffset(r) - s);
            }
        }
    }

    private static void maps(org.apache.arrow.vector.complex.MapVector v, DataType.MapType type, int from, int to,
            MapVector.Builder out, String path) {
        org.apache.arrow.vector.complex.StructVector entries =
                (org.apache.arrow.vector.complex.StructVector) v.getDataVector();
        List<FieldVector> kv = entries.getChildrenFromFields();
        FieldVector keys = kv.get(0);
        FieldVector values = kv.get(1);
        String keyPath = path + "." + entries.getName() + "." + keys.getName();
        String valuePath = path + "." + entries.getName() + "." + values.getName();
        for (int r = from; r < to; r++) {
            if (v.isNull(r)) {
                out.appendNull();
                continue;
            }
            int start = v.getElementStartIndex(r);
            int end = v.getElementEndIndex(r);
            for (int e = start; e < end; e++) {
                if (keys.isNull(e)) {
                    throw new ArrowTypeException(keyPath, "a key of a map is NULL");
                }
            }
            append(keys, type.key(), start, end, out.keys(), keyPath);
            append(values, type.value(), start, end, out.values(), valuePath);
            out.endMap();
        }
    }

    private static UUID uuid(byte[] bytes) {
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = most << 8 | (bytes[i] & 0xFF);
            least = least << 8 | (bytes[i + 8] & 0xFF);
        }
        return new UUID(most, least);
    }
}
