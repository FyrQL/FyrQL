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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Builders for every type, and the order, equality and hashing of values
 * inside vectors.
 * <p>
 * The order is a total order per type, the one DuckDB sorts by: numbers by
 * value, with {@code -0.0} equal to {@code 0.0} and NaN above everything;
 * strings and blobs by their bytes; UUIDs as unsigned 128-bit numbers;
 * structs field by field, lists element by element and then by length, maps
 * entry by entry. {@code NULL} inside a nested value sorts last. The callers
 * handle {@code NULL} at the top level; {@link #compare} must not see one.
 * Equality for grouping and join keys agrees with the order: values that
 * compare equal are one key, and {@link #hash} is the same for them.
 */
public final class Vectors {

    private Vectors() {
    }

    /** Returns a builder for a type with an initial capacity. */
    public static Vector.Builder builder(DataType type, int capacity) {
        if (type instanceof Decimal d) {
            return new DecimalVector.Builder(d, capacity);
        }
        if (type instanceof Struct s) {
            return new StructVector.Builder(s, capacity);
        }
        if (type instanceof ListType l) {
            return new ListVector.Builder(l, capacity);
        }
        if (type instanceof MapType m) {
            return new MapVector.Builder(m, capacity);
        }
        return switch ((Scalar) type) {
            case BOOLEAN -> new BooleanVector.Builder(capacity);
            case TINYINT -> new TinyIntVector.Builder(capacity);
            case SMALLINT -> new SmallIntVector.Builder(capacity);
            case INTEGER, DATE -> new IntVector.Builder(type, capacity);
            case BIGINT, TIMESTAMP -> new BigIntVector.Builder(type, capacity);
            case HUGEINT -> new HugeIntVector.Builder(capacity);
            case FLOAT -> new FloatVector.Builder(capacity);
            case DOUBLE -> new DoubleVector.Builder(capacity);
            case VARCHAR, BLOB -> new BytesVector.Builder(type, capacity);
            case UUID -> new UuidVector.Builder(capacity);
        };
    }

    /** Builds a vector of a type from Java values, {@code null} for {@code NULL}. */
    public static Vector of(DataType type, List<?> values) {
        Vector.Builder b = builder(type, values.size());
        for (Object v : values) {
            b.append(v);
        }
        return b.build();
    }

    /** Builds a vector of a type from Java values, {@code null} for {@code NULL}. */
    public static Vector of(DataType type, Object... values) {
        return of(type, java.util.Arrays.asList(values));
    }

    /** Returns a vector of {@code NULL}s. */
    public static Vector nulls(DataType type, int size) {
        Vector.Builder b = builder(type, size);
        for (int i = 0; i < size; i++) {
            b.appendNull();
        }
        return b.build();
    }

    /**
     * Concatenates vectors of one type into one, without boxing; a single
     * vector comes back as it is.
     */
    public static Vector concat(DataType type, List<Vector> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        int n = 0;
        for (Vector p : parts) {
            if (!p.type().equals(type)) {
                throw new IllegalArgumentException("A " + p.type() + " vector among " + type + " vectors");
            }
            n += p.size();
        }
        if (parts.isEmpty()) {
            return builder(type, 0).build();
        }
        boolean[] nulls = null;
        int at = 0;
        for (Vector p : parts) {
            for (int i = 0; i < p.size(); i++) {
                if (p.isNull(i)) {
                    if (nulls == null) {
                        nulls = new boolean[n];
                    }
                    nulls[at + i] = true;
                }
            }
            at += p.size();
        }
        int k = 0;
        switch (parts.get(0)) {
            case BooleanVector x -> {
                boolean[] values = new boolean[n];
                for (Vector p : parts) {
                    BooleanVector y = (BooleanVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getBoolean(i);
                    }
                }
                return new BooleanVector(values, nulls);
            }
            case TinyIntVector x -> {
                byte[] values = new byte[n];
                for (Vector p : parts) {
                    TinyIntVector y = (TinyIntVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getByte(i);
                    }
                }
                return new TinyIntVector(values, nulls);
            }
            case SmallIntVector x -> {
                short[] values = new short[n];
                for (Vector p : parts) {
                    SmallIntVector y = (SmallIntVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getShort(i);
                    }
                }
                return new SmallIntVector(values, nulls);
            }
            case IntVector x -> {
                int[] values = new int[n];
                for (Vector p : parts) {
                    IntVector y = (IntVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getInt(i);
                    }
                }
                return new IntVector(type, values, nulls);
            }
            case BigIntVector x -> {
                long[] values = new long[n];
                for (Vector p : parts) {
                    BigIntVector y = (BigIntVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getLong(i);
                    }
                }
                return new BigIntVector(type, values, nulls);
            }
            case HugeIntVector x -> {
                long[] high = new long[n];
                long[] low = new long[n];
                for (Vector p : parts) {
                    HugeIntVector y = (HugeIntVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        high[k] = y.getHigh(i);
                        low[k++] = y.getLow(i);
                    }
                }
                return new HugeIntVector(high, low, nulls);
            }
            case FloatVector x -> {
                float[] values = new float[n];
                for (Vector p : parts) {
                    FloatVector y = (FloatVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getFloat(i);
                    }
                }
                return new FloatVector(values, nulls);
            }
            case DoubleVector x -> {
                double[] values = new double[n];
                for (Vector p : parts) {
                    DoubleVector y = (DoubleVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getDouble(i);
                    }
                }
                return new DoubleVector(values, nulls);
            }
            case DecimalVector x when x.isSmall() -> {
                long[] values = new long[n];
                for (Vector p : parts) {
                    DecimalVector y = (DecimalVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getUnscaledLong(i);
                    }
                }
                return new DecimalVector(x.type(), values, null, nulls);
            }
            case DecimalVector x -> {
                BigInteger[] values = new BigInteger[n];
                for (Vector p : parts) {
                    DecimalVector y = (DecimalVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        values[k++] = y.getUnscaled(i);
                    }
                }
                return new DecimalVector(x.type(), null, values, nulls);
            }
            case BytesVector x -> {
                int bytes = 0;
                for (Vector p : parts) {
                    BytesVector y = (BytesVector) p;
                    bytes += y.size() == 0 ? 0 : y.end(y.size() - 1) - y.start(0);
                }
                byte[] data = new byte[bytes];
                int[] offsets = new int[n + 1];
                int used = 0;
                for (Vector p : parts) {
                    BytesVector y = (BytesVector) p;
                    if (y.size() == 0) {
                        continue;
                    }
                    int from = y.start(0);
                    int length = y.end(y.size() - 1) - from;
                    System.arraycopy(y.data(), from, data, used, length);
                    for (int i = 0; i < y.size(); i++) {
                        offsets[k++] = used + y.start(i) - from;
                    }
                    used += length;
                }
                offsets[n] = used;
                return new BytesVector(type, data, offsets, nulls);
            }
            case UuidVector x -> {
                long[] most = new long[n];
                long[] least = new long[n];
                for (Vector p : parts) {
                    UuidVector y = (UuidVector) p;
                    for (int i = 0; i < y.size(); i++) {
                        most[k] = y.getMost(i);
                        least[k++] = y.getLeast(i);
                    }
                }
                return new UuidVector(most, least, nulls);
            }
            case StructVector x -> {
                List<Vector> children = new ArrayList<>(x.children().size());
                for (int f = 0; f < x.children().size(); f++) {
                    List<Vector> fields = new ArrayList<>(parts.size());
                    for (Vector p : parts) {
                        fields.add(((StructVector) p).child(f));
                    }
                    children.add(concat(x.type().fields().get(f).type(), fields));
                }
                return new StructVector(x.type(), children, nulls, n);
            }
            case ListVector x -> {
                int[] offsets = new int[n + 1];
                List<Vector> elements = new ArrayList<>(parts.size());
                int used = 0;
                for (Vector p : parts) {
                    ListVector y = (ListVector) p;
                    if (y.size() == 0) {
                        continue;
                    }
                    int from = y.start(0);
                    int to = y.end(y.size() - 1);
                    elements.add(range(y.child(), from, to));
                    for (int i = 0; i < y.size(); i++) {
                        offsets[k++] = used + y.start(i) - from;
                    }
                    used += to - from;
                }
                offsets[n] = used;
                return new ListVector(x.type(), offsets, concat(x.type().element(), elements), nulls);
            }
            case MapVector x -> {
                int[] offsets = new int[n + 1];
                List<Vector> keys = new ArrayList<>(parts.size());
                List<Vector> values = new ArrayList<>(parts.size());
                int used = 0;
                for (Vector p : parts) {
                    MapVector y = (MapVector) p;
                    if (y.size() == 0) {
                        continue;
                    }
                    int from = y.start(0);
                    int to = y.end(y.size() - 1);
                    keys.add(range(y.keys(), from, to));
                    values.add(range(y.values(), from, to));
                    for (int i = 0; i < y.size(); i++) {
                        offsets[k++] = used + y.start(i) - from;
                    }
                    used += to - from;
                }
                offsets[n] = used;
                return new MapVector(x.type(), offsets, concat(x.type().key(), keys), concat(x.type().value(), values),
                        nulls);
            }
        }
    }

    /** Returns the rows of a vector from one index to another, the vector itself if that is all of it. */
    private static Vector range(Vector v, int from, int to) {
        if (from == 0 && to == v.size()) {
            return v;
        }
        int[] rows = new int[to - from];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = from + i;
        }
        return v.select(rows);
    }

    /**
     * Returns a vector with the values at the given rows, in that order, as
     * {@link Vector#select} does, except that a row of -1 gives
     * {@code NULL}. Nothing is boxed.
     */
    public static Vector gather(Vector v, int[] rows) {
        int n = rows.length;
        boolean[] nulls = null;
        boolean missing = false;
        for (int i = 0; i < n; i++) {
            int r = rows[i];
            if (r < 0 || v.isNull(r)) {
                if (nulls == null) {
                    nulls = new boolean[n];
                }
                nulls[i] = true;
                missing |= r < 0;
            }
        }
        if (!missing) {
            return v.select(rows);
        }
        // The value under a NULL from -1 stays the default of the array, as a builder leaves it.
        switch (v) {
            case BooleanVector x -> {
                boolean[] values = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getBoolean(rows[i]);
                    }
                }
                return new BooleanVector(values, nulls);
            }
            case TinyIntVector x -> {
                byte[] values = new byte[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getByte(rows[i]);
                    }
                }
                return new TinyIntVector(values, nulls);
            }
            case SmallIntVector x -> {
                short[] values = new short[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getShort(rows[i]);
                    }
                }
                return new SmallIntVector(values, nulls);
            }
            case IntVector x -> {
                int[] values = new int[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getInt(rows[i]);
                    }
                }
                return new IntVector(x.type(), values, nulls);
            }
            case BigIntVector x -> {
                long[] values = new long[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getLong(rows[i]);
                    }
                }
                return new BigIntVector(x.type(), values, nulls);
            }
            case HugeIntVector x -> {
                long[] high = new long[n];
                long[] low = new long[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        high[i] = x.getHigh(rows[i]);
                        low[i] = x.getLow(rows[i]);
                    }
                }
                return new HugeIntVector(high, low, nulls);
            }
            case FloatVector x -> {
                float[] values = new float[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getFloat(rows[i]);
                    }
                }
                return new FloatVector(values, nulls);
            }
            case DoubleVector x -> {
                double[] values = new double[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getDouble(rows[i]);
                    }
                }
                return new DoubleVector(values, nulls);
            }
            case DecimalVector x when x.isSmall() -> {
                long[] values = new long[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        values[i] = x.getUnscaledLong(rows[i]);
                    }
                }
                return new DecimalVector(x.type(), values, null, nulls);
            }
            case DecimalVector x -> {
                BigInteger[] values = new BigInteger[n];
                for (int i = 0; i < n; i++) {
                    values[i] = rows[i] >= 0 ? x.getUnscaled(rows[i]) : BigInteger.ZERO;
                }
                return new DecimalVector(x.type(), null, values, nulls);
            }
            case BytesVector x -> {
                int[] offsets = new int[n + 1];
                int bytes = 0;
                for (int i = 0; i < n; i++) {
                    offsets[i] = bytes;
                    bytes += rows[i] >= 0 ? x.length(rows[i]) : 0;
                }
                offsets[n] = bytes;
                byte[] data = new byte[bytes];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        System.arraycopy(x.data(), x.start(rows[i]), data, offsets[i], x.length(rows[i]));
                    }
                }
                return new BytesVector(x.type(), data, offsets, nulls);
            }
            case UuidVector x -> {
                long[] most = new long[n];
                long[] least = new long[n];
                for (int i = 0; i < n; i++) {
                    if (rows[i] >= 0) {
                        most[i] = x.getMost(rows[i]);
                        least[i] = x.getLeast(rows[i]);
                    }
                }
                return new UuidVector(most, least, nulls);
            }
            case StructVector x -> {
                // A NULL struct has NULL in every field.
                List<Vector> children = new ArrayList<>(x.children().size());
                for (Vector child : x.children()) {
                    children.add(gather(child, rows));
                }
                return new StructVector(x.type(), children, nulls, n);
            }
            case ListVector x -> {
                int[] offsets = new int[n + 1];
                int[] elements = elements(rows, offsets, x::start, x::length);
                return new ListVector(x.type(), offsets, x.child().select(elements), nulls);
            }
            case MapVector x -> {
                int[] offsets = new int[n + 1];
                int[] entries = elements(rows, offsets, x::start, x::length);
                return new MapVector(x.type(), offsets, x.keys().select(entries), x.values().select(entries), nulls);
            }
        }
    }

    /**
     * Fills the offsets of the gathered rows of a list or map vector, an
     * empty range for -1, and returns the indexes of their elements.
     */
    private static int[] elements(int[] rows, int[] offsets, IntUnaryOperator start, IntUnaryOperator length) {
        int n = rows.length;
        int total = 0;
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            total += rows[i] >= 0 ? length.applyAsInt(rows[i]) : 0;
        }
        offsets[n] = total;
        int[] elements = new int[total];
        int k = 0;
        for (int r : rows) {
            if (r >= 0) {
                for (int i = start.applyAsInt(r), end = i + length.applyAsInt(r); i < end; i++) {
                    elements[k++] = i;
                }
            }
        }
        return elements;
    }

    /**
     * Compares the values at two rows of two vectors of the same type;
     * neither may be {@code NULL}.
     */
    public static int compare(Vector a, int rowA, Vector b, int rowB) {
        switch (a) {
            case BooleanVector x -> {
                return Boolean.compare(x.getBoolean(rowA), ((BooleanVector) b).getBoolean(rowB));
            }
            case TinyIntVector x -> {
                return Byte.compare(x.getByte(rowA), ((TinyIntVector) b).getByte(rowB));
            }
            case SmallIntVector x -> {
                return Short.compare(x.getShort(rowA), ((SmallIntVector) b).getShort(rowB));
            }
            case IntVector x -> {
                return Integer.compare(x.getInt(rowA), ((IntVector) b).getInt(rowB));
            }
            case BigIntVector x -> {
                return Long.compare(x.getLong(rowA), ((BigIntVector) b).getLong(rowB));
            }
            case HugeIntVector x -> {
                HugeIntVector y = (HugeIntVector) b;
                return HugeIntVector.compare(x.getHigh(rowA), x.getLow(rowA), y.getHigh(rowB), y.getLow(rowB));
            }
            case FloatVector x -> {
                return compare(x.getFloat(rowA), ((FloatVector) b).getFloat(rowB));
            }
            case DoubleVector x -> {
                return compare(x.getDouble(rowA), ((DoubleVector) b).getDouble(rowB));
            }
            case DecimalVector x -> {
                DecimalVector y = (DecimalVector) b;
                if (x.type().scale() != y.type().scale()) {
                    return x.getBigDecimal(rowA).compareTo(y.getBigDecimal(rowB));
                }
                if (x.isSmall() && y.isSmall()) {
                    return Long.compare(x.getUnscaledLong(rowA), y.getUnscaledLong(rowB));
                }
                return x.getUnscaled(rowA).compareTo(y.getUnscaled(rowB));
            }
            case BytesVector x -> {
                return BytesVector.compare(x, rowA, (BytesVector) b, rowB);
            }
            case UuidVector x -> {
                UuidVector y = (UuidVector) b;
                return UuidVector.compare(x.getMost(rowA), x.getLeast(rowA), y.getMost(rowB), y.getLeast(rowB));
            }
            case StructVector x -> {
                StructVector y = (StructVector) b;
                for (int f = 0; f < x.children().size(); f++) {
                    int c = compareNullable(x.child(f), rowA, y.child(f), rowB);
                    if (c != 0) {
                        return c;
                    }
                }
                return 0;
            }
            case ListVector x -> {
                ListVector y = (ListVector) b;
                int n = Math.min(x.length(rowA), y.length(rowB));
                for (int i = 0; i < n; i++) {
                    int c = compareNullable(x.child(), x.start(rowA) + i, y.child(), y.start(rowB) + i);
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(x.length(rowA), y.length(rowB));
            }
            case MapVector x -> {
                MapVector y = (MapVector) b;
                int n = Math.min(x.length(rowA), y.length(rowB));
                for (int i = 0; i < n; i++) {
                    int c = compareNullable(x.keys(), x.start(rowA) + i, y.keys(), y.start(rowB) + i);
                    if (c == 0) {
                        c = compareNullable(x.values(), x.start(rowA) + i, y.values(), y.start(rowB) + i);
                    }
                    if (c != 0) {
                        return c;
                    }
                }
                return Integer.compare(x.length(rowA), y.length(rowB));
            }
        }
    }

    /** Compares two rows where either may be {@code NULL}, which sorts last. */
    public static int compareNullable(Vector a, int rowA, Vector b, int rowB) {
        boolean nullA = a.isNull(rowA);
        boolean nullB = b.isNull(rowB);
        if (nullA || nullB) {
            return nullA == nullB ? 0 : nullA ? 1 : -1;
        }
        return compare(a, rowA, b, rowB);
    }

    /** The order of doubles: by value, {@code -0.0} equal to {@code 0.0}, NaN above everything. */
    public static int compare(double x, double y) {
        if (x < y) {
            return -1;
        }
        if (x > y) {
            return 1;
        }
        if (x == y) {
            return 0;
        }
        boolean nanX = Double.isNaN(x);
        boolean nanY = Double.isNaN(y);
        return nanX == nanY ? 0 : nanX ? 1 : -1;
    }

    /** The order of floats, as for doubles. */
    public static int compare(float x, float y) {
        if (x < y) {
            return -1;
        }
        if (x > y) {
            return 1;
        }
        if (x == y) {
            return 0;
        }
        boolean nanX = Float.isNaN(x);
        boolean nanY = Float.isNaN(y);
        return nanX == nanY ? 0 : nanX ? 1 : -1;
    }

    /** Tells whether two rows hold the same key value; {@code NULL} equals only {@code NULL}. */
    public static boolean equal(Vector a, int rowA, Vector b, int rowB) {
        return compareNullable(a, rowA, b, rowB) == 0;
    }

    /** The hash of a float in {@link #hash(Vector, int)}: all NaNs alike, {@code -0.0} as {@code 0.0}. */
    public static int hash(float f) {
        return Float.isNaN(f) ? 0x7fc00000 : Float.floatToIntBits(f == 0 ? 0.0f : f);
    }

    /** The hash of a double in {@link #hash(Vector, int)}: all NaNs alike, {@code -0.0} as {@code 0.0}. */
    public static int hash(double d) {
        return Long.hashCode(Double.isNaN(d) ? 0x7ff8000000000000L : Double.doubleToLongBits(d == 0 ? 0.0 : d));
    }

    /**
     * The hash of a decimal of at most 18 digits in {@link #hash(Vector, int)}:
     * the hash of {@code BigInteger} of the unscaled value without trailing
     * zeros, times 31, plus the remaining scale, computed without a
     * {@code BigInteger}.
     */
    public static int hashDecimal(long unscaled, int scale) {
        while (scale > 0 && unscaled != 0 && unscaled % 10 == 0) {
            unscaled /= 10;
            scale--;
        }
        // BigInteger.hashCode: 31 * h + word over the 32-bit words of the magnitude, most significant first,
        // without leading zero words, times the sign.
        long magnitude = Math.abs(unscaled);
        int high = (int) (magnitude >>> 32);
        int low = (int) magnitude;
        int h = high != 0 ? 31 * high + low : low;
        return h * Long.signum(unscaled) * 31 + (unscaled == 0 ? 0 : scale);
    }

    /** Returns a hash of the value at a row that agrees with {@link #equal}; 0 for {@code NULL}. */
    public static int hash(Vector v, int row) {
        if (v.isNull(row)) {
            return 0;
        }
        switch (v) {
            case BooleanVector x -> {
                return x.getBoolean(row) ? 1231 : 1237;
            }
            case TinyIntVector x -> {
                return x.getByte(row);
            }
            case SmallIntVector x -> {
                return x.getShort(row);
            }
            case IntVector x -> {
                return x.getInt(row);
            }
            case BigIntVector x -> {
                return Long.hashCode(x.getLong(row));
            }
            case HugeIntVector x -> {
                return Long.hashCode(x.getHigh(row)) * 31 + Long.hashCode(x.getLow(row));
            }
            case FloatVector x -> {
                return hash(x.getFloat(row));
            }
            case DoubleVector x -> {
                return hash(x.getDouble(row));
            }
            case DecimalVector x when x.isSmall() -> {
                return hashDecimal(x.getUnscaledLong(row), x.type().scale());
            }
            case DecimalVector x -> {
                // Across scales the same number must hash alike, so hash the value without trailing zeros.
                BigInteger unscaled = x.getUnscaled(row);
                int scale = x.type().scale();
                while (scale > 0 && unscaled.signum() != 0 && unscaled.mod(BigInteger.TEN).signum() == 0) {
                    unscaled = unscaled.divide(BigInteger.TEN);
                    scale--;
                }
                return unscaled.hashCode() * 31 + (unscaled.signum() == 0 ? 0 : scale);
            }
            case BytesVector x -> {
                return x.hash(row);
            }
            case UuidVector x -> {
                return Long.hashCode(x.getMost(row)) * 31 + Long.hashCode(x.getLeast(row));
            }
            case StructVector x -> {
                int h = 1;
                for (Vector child : x.children()) {
                    h = 31 * h + hash(child, row);
                }
                return h;
            }
            case ListVector x -> {
                int h = 1;
                for (int i = x.start(row); i < x.end(row); i++) {
                    h = 31 * h + hash(x.child(), i);
                }
                return h;
            }
            case MapVector x -> {
                int h = 7;
                for (int i = x.start(row); i < x.end(row); i++) {
                    h = 31 * h + hash(x.keys(), i);
                    h = 31 * h + hash(x.values(), i);
                }
                return h;
            }
        }
    }
}
