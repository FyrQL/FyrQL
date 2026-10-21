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
import net.fyrql.core.vector.IntervalVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.UuidVector;
import net.fyrql.core.vector.Vector;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * A primitive column of a file as a column of its FyrQL type, see
 * {@link ParquetSchemas}: appends the physical values of the column to a
 * vector of the type.
 * <ul>
 * <li>An unsigned integer is read without its sign; an {@code INT(64,false)}
 * becomes a {@code HUGEINT}.</li>
 * <li>A {@code TIMESTAMP} in milliseconds or nanoseconds becomes one in
 * microseconds, nanoseconds divided by 1000 towards zero as DuckDB casts a
 * {@code TIMESTAMP_NS}; an {@code INT96} is the Julian day and the
 * nanoseconds of the day.</li>
 * <li>A {@code TIME} is the text DuckDB gives it, such as
 * {@code 01:02:03.5}, followed by {@code +00} if it is adjusted to UTC.</li>
 * <li>A {@code DECIMAL} on a byte array is the unscaled value in two's
 * complement, big-endian; a value beyond the precision is an error.</li>
 * </ul>
 */
final class ParquetLeaf {

    /** The days from the Julian day 0 to 1970-01-01. */
    private static final long JULIAN_EPOCH_DAY = 2_440_588L;
    private static final long MICROS_PER_DAY = 86_400_000_000L;

    /** What a value becomes. */
    enum Target {
        BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT, FLOAT, DOUBLE, FLOAT16, TIMESTAMP, INT96, TIME,
        TIME_OF_DAY, DECIMAL, BYTES, UUID, INTERVAL
    }

    private final PrimitiveType.PrimitiveTypeName physical;
    private final DataType type;
    private final Target target;
    /** The mask of an unsigned integer narrower than 64 bits, else -1. */
    private final long mask;
    /** The factor to nanoseconds of a {@code TIME} or {@code TIMESTAMP}. */
    private final long nanos;
    private final boolean utc;
    /** The largest unscaled value of a {@code DECIMAL} of at most 18 digits. */
    private final long maxUnscaled;

    private ParquetLeaf(PrimitiveType.PrimitiveTypeName physical, DataType type, Target target, long mask,
            long nanos, boolean utc) {
        this.physical = physical;
        this.type = type;
        this.target = target;
        this.mask = mask;
        this.nanos = nanos;
        this.utc = utc;
        this.maxUnscaled = type instanceof DataType.Decimal d && d.fitsLong() ? d.maxUnscaled().longValueExact() : 0;
    }

    /** Returns how a primitive column of a file becomes a column of a type, the one {@link ParquetSchemas} gives. */
    static ParquetLeaf of(PrimitiveType p, DataType type) {
        LogicalTypeAnnotation annotation = p.getLogicalTypeAnnotation();
        long mask = -1;
        long nanos = 1;
        boolean utc = false;
        if (annotation instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i && !i.isSigned()
                && i.getBitWidth() < 64) {
            mask = (1L << i.getBitWidth()) - 1;
        } else if (annotation instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation t) {
            nanos = nanos(t.getUnit());
            utc = t.isAdjustedToUTC();
        } else if (annotation instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation t) {
            nanos = nanos(t.getUnit());
        }
        Target target = switch (type) {
            case DataType.Decimal d -> Target.DECIMAL;
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> Target.BOOLEAN;
                case TINYINT -> Target.TINYINT;
                case SMALLINT -> Target.SMALLINT;
                case INTEGER, DATE -> Target.INTEGER;
                case BIGINT -> Target.BIGINT;
                case HUGEINT -> Target.HUGEINT;
                case FLOAT -> p.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.FLOAT ? Target.FLOAT
                        : Target.FLOAT16;
                case DOUBLE -> Target.DOUBLE;
                case TIMESTAMP -> p.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT96 ? Target.INT96
                        : Target.TIMESTAMP;
                case TIMESTAMPTZ -> Target.TIMESTAMP;
                case TIME -> Target.TIME_OF_DAY;
                case INTERVAL -> Target.INTERVAL;
                case VARCHAR -> annotation instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation ? Target.TIME
                        : Target.BYTES;
                case BLOB -> Target.BYTES;
                case UUID -> Target.UUID;
            };
            default -> throw new IllegalArgumentException("Not the type of a primitive column: " + type);
        };
        return new ParquetLeaf(p.getPrimitiveTypeName(), type, target, mask, nanos, utc);
    }

    private static long nanos(LogicalTypeAnnotation.TimeUnit unit) {
        return switch (unit) {
            case MILLIS -> 1_000_000L;
            case MICROS -> 1_000L;
            case NANOS -> 1L;
        };
    }

    PrimitiveType.PrimitiveTypeName physical() {
        return physical;
    }

    DataType type() {
        return type;
    }

    Target target() {
        return target;
    }

    /** Tells whether a value of the physical type goes into the vector as it is. */
    boolean isPlain() {
        return switch (physical) {
            case INT32 -> target == Target.INTEGER && mask == -1;
            case INT64 -> target == Target.BIGINT && mask == -1
                    || (target == Target.TIMESTAMP || target == Target.TIME_OF_DAY) && nanos == 1_000L;
            case BINARY, FIXED_LEN_BYTE_ARRAY -> target == Target.BYTES;
            case BOOLEAN, FLOAT, DOUBLE -> true;
            case INT96 -> false;
        };
    }

    void addBoolean(Vector.Builder b, boolean value) {
        ((BooleanVector.Builder) b).appendBoolean(value);
    }

    void addFloat(Vector.Builder b, float value) {
        ((FloatVector.Builder) b).appendFloat(value);
    }

    void addDouble(Vector.Builder b, double value) {
        ((DoubleVector.Builder) b).appendDouble(value);
    }

    /** Appends a value of an {@code INT32} column. */
    void addInt(Vector.Builder b, int value) {
        if (target == Target.TIME) {
            ((BytesVector.Builder) b).appendString(time(value * nanos, utc));
        } else {
            addLong(b, mask == -1 ? value : value & mask);
        }
    }

    /** Appends a value of an {@code INT64} column, or of an {@code INT32} column widened. */
    void addLong(Vector.Builder b, long value) {
        long v = mask == -1 ? value : value & mask;
        switch (target) {
            case TINYINT -> ((TinyIntVector.Builder) b).appendByte((byte) v);
            case SMALLINT -> ((SmallIntVector.Builder) b).appendShort((short) v);
            case INTEGER -> ((IntVector.Builder) b).appendInt((int) v);
            case BIGINT -> ((BigIntVector.Builder) b).appendLong(v);
            case HUGEINT -> ((HugeIntVector.Builder) b).appendHalves(0, v);
            case TIMESTAMP, TIME_OF_DAY -> ((BigIntVector.Builder) b).appendLong(micros(v));
            case TIME -> ((BytesVector.Builder) b).appendString(time(Math.multiplyExact(v, nanos), utc));
            case DECIMAL -> addUnscaled(b, v);
            default -> throw new IllegalStateException(target + " from an integer");
        }
    }

    private long micros(long v) {
        if (nanos == 1_000L) {
            return v;
        }
        return nanos == 1L ? v / 1000 : Math.multiplyExact(v, nanos / 1000);
    }

    /** Appends a value of a {@code BINARY}, {@code FIXED_LEN_BYTE_ARRAY} or {@code INT96} column. */
    void addBinary(Vector.Builder b, Binary value) {
        switch (target) {
            case BYTES -> {
                ByteBuffer bytes = value.toByteBuffer();
                if (bytes.hasArray()) {
                    ((BytesVector.Builder) b).appendBytes(bytes.array(), bytes.arrayOffset() + bytes.position(),
                            bytes.remaining());
                } else {
                    ((BytesVector.Builder) b).appendBytes(value.getBytesUnsafe());
                }
            }
            case DECIMAL -> {
                ByteBuffer bytes = value.toByteBuffer();
                if (maxUnscaled != 0) {
                    addUnscaled(b, unscaledLong(bytes));
                } else {
                    BigInteger unscaled = new BigInteger(value.getBytesUnsafe());
                    if (unscaled.abs().compareTo(((DataType.Decimal) type).maxUnscaled()) > 0) {
                        throw tooWide(unscaled);
                    }
                    ((DecimalVector.Builder) b).appendUnscaled(unscaled);
                }
            }
            case UUID -> {
                ByteBuffer bytes = value.toByteBuffer();
                ((UuidVector.Builder) b).appendUuid(new UUID(bytes.getLong(bytes.position()),
                        bytes.getLong(bytes.position() + 8)));
            }
            case FLOAT16 -> {
                ByteBuffer bytes = value.toByteBuffer();
                int p = bytes.position();
                short bits = (short) ((bytes.get(p) & 0xff) | (bytes.get(p + 1) & 0xff) << 8);
                ((FloatVector.Builder) b).appendFloat(Float.float16ToFloat(bits));
            }
            case INTERVAL -> {
                // Months, days and milliseconds as three little-endian integers, the milliseconds unsigned.
                ByteBuffer bytes = value.toByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int p = bytes.position();
                ((IntervalVector.Builder) b).appendInterval(bytes.getInt(p), bytes.getInt(p + 4),
                        Integer.toUnsignedLong(bytes.getInt(p + 8)) * 1_000L);
            }
            case INT96 -> {
                ByteBuffer bytes = value.toByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN);
                int p = bytes.position();
                long nanosOfDay = bytes.getLong(p);
                long julianDay = bytes.getInt(p + 8);
                ((BigIntVector.Builder) b).appendLong(Math.addExact(
                        Math.multiplyExact(julianDay - JULIAN_EPOCH_DAY, MICROS_PER_DAY), nanosOfDay / 1000));
            }
            default -> throw new IllegalStateException(target + " from a byte array");
        }
    }

    /** Returns the unscaled value of a {@code DECIMAL} of at most 18 digits in two's complement, big-endian. */
    private static long unscaledLong(ByteBuffer bytes) {
        int from = bytes.position();
        int to = bytes.limit();
        long v = from < to && bytes.get(from) < 0 ? -1 : 0;
        for (int i = from; i < to; i++) {
            v = v << 8 | (bytes.get(i) & 0xff);
        }
        return v;
    }

    private void addUnscaled(Vector.Builder b, long unscaled) {
        if (maxUnscaled != 0) {
            if (unscaled > maxUnscaled || unscaled < -maxUnscaled) {
                throw tooWide(BigInteger.valueOf(unscaled));
            }
        } else if (BigInteger.valueOf(unscaled).abs().compareTo(((DataType.Decimal) type).maxUnscaled()) > 0) {
            throw tooWide(BigInteger.valueOf(unscaled));
        }
        ((DecimalVector.Builder) b).appendUnscaled(unscaled);
    }

    private ArithmeticException tooWide(BigInteger unscaled) {
        return new ArithmeticException("The unscaled value " + unscaled + " does not fit " + type.sqlName());
    }

    /**
     * Returns a time of day as DuckDB writes it: {@code HH:MM:SS}, then the
     * fraction of the second without trailing zeros, then {@code +00} for a
     * time adjusted to UTC.
     */
    static String time(long nanosOfDay, boolean utc) {
        long seconds = Math.floorDiv(nanosOfDay, 1_000_000_000L);
        long fraction = Math.floorMod(nanosOfDay, 1_000_000_000L);
        StringBuilder s = new StringBuilder(24);
        two(s, seconds / 3600).append(':');
        two(s, seconds / 60 % 60).append(':');
        two(s, seconds % 60);
        if (fraction != 0) {
            String digits = Long.toString(1_000_000_000L + fraction).substring(1);
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') {
                end--;
            }
            s.append('.').append(digits, 0, end);
        }
        return utc ? s.append("+00").toString() : s.toString();
    }

    private static StringBuilder two(StringBuilder s, long v) {
        return v < 10 && v >= 0 ? s.append('0').append(v) : s.append(v);
    }
}
