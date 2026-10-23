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

package net.fyrql.core.type;

import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for the Java values of the types: ranges of the integral types,
 * narrowing with a range check, the check of a value against its type, and
 * the conversion between the values in vectors and those of
 * {@code java.time}.
 */
public final class Values {

    /** The smallest {@code HUGEINT}, {@code -2^127}. */
    public static final BigInteger HUGEINT_MIN = BigInteger.ONE.shiftLeft(127).negate();

    /** The largest {@code HUGEINT}, {@code 2^127 - 1}. */
    public static final BigInteger HUGEINT_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;

    private Values() {
    }

    /** Returns the smallest value of an integral type that fits into a {@code long}. */
    public static long min(DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> Byte.MIN_VALUE;
            case SMALLINT -> Short.MIN_VALUE;
            case INTEGER -> Integer.MIN_VALUE;
            case BIGINT -> Long.MIN_VALUE;
            default -> throw new IllegalArgumentException("Not an integral type within long: " + type);
        };
    }

    /** Returns the largest value of an integral type that fits into a {@code long}. */
    public static long max(DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> Byte.MAX_VALUE;
            case SMALLINT -> Short.MAX_VALUE;
            case INTEGER -> Integer.MAX_VALUE;
            case BIGINT -> Long.MAX_VALUE;
            default -> throw new IllegalArgumentException("Not an integral type within long: " + type);
        };
    }

    /** Tells whether a value lies within the 128-bit range of {@code HUGEINT}. */
    public static boolean inHugeIntRange(BigInteger value) {
        return value.compareTo(HUGEINT_MIN) >= 0 && value.compareTo(HUGEINT_MAX) <= 0;
    }

    /**
     * Returns the value unchanged if it is in the range of the type.
     *
     * @throws ArithmeticException otherwise, as DuckDB raises an out-of-range
     *         error
     */
    public static long checkRange(long value, DataType type) {
        if (type == DataType.HUGEINT) {
            return value;
        }
        if (value < min(type) || value > max(type)) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return value;
    }

    /**
     * Boxes a value that is known to be in the range of an integral type;
     * {@code HUGEINT} becomes a {@code BigInteger}.
     */
    public static Object box(long value, DataType type) {
        return switch (scalar(type)) {
            case TINYINT -> (byte) value;
            case SMALLINT -> (short) value;
            case INTEGER -> (int) value;
            case BIGINT -> value;
            case HUGEINT -> BigInteger.valueOf(value);
            default -> throw new IllegalArgumentException("Not integral: " + type);
        };
    }

    /**
     * Boxes a value with a range check.
     *
     * @throws ArithmeticException if the value is out of range
     */
    public static Object boxChecked(long value, DataType type) {
        return box(checkRange(value, type), type);
    }

    /**
     * Boxes a {@code BigInteger} into an integral type.
     *
     * @throws ArithmeticException if the value is out of range
     */
    public static Object boxChecked(BigInteger value, DataType type) {
        if (type == DataType.HUGEINT) {
            if (!inHugeIntRange(value)) {
                throw new ArithmeticException("Out of range for HUGEINT: " + value);
            }
            return value;
        }
        if (value.bitLength() >= 64) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return boxChecked(value.longValue(), type);
    }

    /**
     * Returns a decimal with exactly the scale of the type, rounding half away
     * from zero as DuckDB does.
     *
     * @throws ArithmeticException if the value does not fit into the precision
     */
    public static BigDecimal toDecimal(BigDecimal value, DataType.Decimal type) {
        BigDecimal scaled = value.setScale(type.scale(), java.math.RoundingMode.HALF_UP);
        if (scaled.unscaledValue().abs().compareTo(type.maxUnscaled()) > 0) {
            throw new ArithmeticException("Out of range for " + type + ": " + value);
        }
        return scaled;
    }

    /**
     * Checks that a value belongs to a type.
     *
     * @return the value
     * @throws IllegalArgumentException otherwise
     */
    public static Object require(DataType type, Object value) {
        if (!type.accepts(value)) {
            throw new IllegalArgumentException("Not a " + type + ": " + describe(value));
        }
        return value;
    }

    /** Describes a value for a message, with its class. */
    public static String describe(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "byte[" + bytes.length + "]";
        }
        return value.getClass().getSimpleName() + " " + value;
    }

    /**
     * Tells whether {@link #toJava} changes the values of a type: a date or
     * time type, or a nested type with one inside.
     */
    public static boolean hasJavaTime(DataType type) {
        return hasTime(type, false);
    }

    /**
     * Converts a value of a type, as it is in a vector, to the value of
     * {@code java.time} that {@code rows()} returns: a {@code DATE} becomes a
     * {@link LocalDate}, a {@code TIME} a {@link LocalTime} ({@code 24:00:00}
     * becomes {@link LocalTime#MAX}), a {@code TIMESTAMP} a
     * {@link LocalDateTime} and a {@code TIMESTAMP WITH TIME ZONE} an
     * {@link OffsetDateTime} in the given time zone. The elements of a
     * {@code LIST}, the fields of a {@code STRUCT} and the keys and values of a
     * {@code MAP} are converted the same way, into a new {@code List} or
     * {@code Map}. The values of all other types, {@code INTERVAL} among them,
     * are returned unchanged.
     *
     * @param zone the time zone of a {@code TIMESTAMP WITH TIME ZONE}
     */
    public static Object toJava(Object value, DataType type, ZoneId zone) {
        if (value == null || !hasJavaTime(type)) {
            return value;
        }
        return switch (type) {
            case DataType.ListType l -> {
                List<Object> result = new ArrayList<>();
                for (Object element : (List<?>) value) {
                    result.add(toJava(element, l.element(), zone));
                }
                yield result;
            }
            case DataType.Struct s -> {
                List<?> fields = (List<?>) value;
                List<Object> result = new ArrayList<>(fields.size());
                for (int i = 0; i < fields.size(); i++) {
                    result.add(toJava(fields.get(i), s.fields().get(i).type(), zone));
                }
                yield result;
            }
            case DataType.MapType m -> {
                Map<Object, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    result.put(toJava(e.getKey(), m.key(), zone), toJava(e.getValue(), m.value(), zone));
                }
                yield result;
            }
            case DataType.Scalar s -> switch (s) {
                case DATE -> LocalDate.ofEpochDay((Integer) value);
                case TIME -> {
                    long micros = (Long) value;
                    yield micros >= MICROS_PER_DAY ? LocalTime.MAX : LocalTime.ofNanoOfDay(micros * 1000);
                }
                case TIMESTAMP -> Conversions.toLocalDateTime((Long) value);
                case TIMESTAMPTZ -> {
                    long micros = (Long) value;
                    Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                            Math.floorMod(micros, MICROS_PER_SECOND) * 1000);
                    yield instant.atZone(zone).toOffsetDateTime();
                }
                default -> value;
            };
            case DataType.Decimal d -> value;
        };
    }

    /**
     * Converts a value of {@code java.time} to the value of a type as it is in
     * a vector, the reverse of {@link #toJava}: a {@link LocalDate} for a
     * {@code DATE}, a {@link LocalTime} for a {@code TIME}, a
     * {@link LocalDateTime} for a {@code TIMESTAMP}, an {@link Instant},
     * {@link OffsetDateTime} or {@link ZonedDateTime} for a
     * {@code TIMESTAMP WITH TIME ZONE}, and a {@link Duration} or
     * {@link Period} for an {@code INTERVAL}, also inside a {@code LIST},
     * {@code STRUCT} or {@code MAP}. Digits below a microsecond are cut off.
     * Every other value, the form in a vector among them, is returned
     * unchanged, so that {@link #require} names a value of the wrong class.
     *
     * @throws ArithmeticException if a value is out of the range of its type
     */
    public static Object fromJava(Object value, DataType type) {
        if (value == null || !hasTime(type, true)) {
            return value;
        }
        return switch (type) {
            case DataType.ListType l -> {
                if (!(value instanceof List<?> elements)) {
                    yield value;
                }
                List<Object> result = new ArrayList<>(elements.size());
                for (Object element : elements) {
                    result.add(fromJava(element, l.element()));
                }
                yield result;
            }
            case DataType.Struct s -> {
                if (!(value instanceof List<?> fields) || fields.size() != s.fields().size()) {
                    yield value;
                }
                List<Object> result = new ArrayList<>(fields.size());
                for (int i = 0; i < fields.size(); i++) {
                    result.add(fromJava(fields.get(i), s.fields().get(i).type()));
                }
                yield result;
            }
            case DataType.MapType m -> {
                if (!(value instanceof Map<?, ?> entries)) {
                    yield value;
                }
                Map<Object, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : entries.entrySet()) {
                    result.put(fromJava(e.getKey(), m.key()), fromJava(e.getValue(), m.value()));
                }
                yield result;
            }
            case DataType.Scalar s -> switch (s) {
                case DATE -> value instanceof LocalDate d ? Math.toIntExact(d.toEpochDay()) : value;
                case TIME -> value instanceof LocalTime t ? t.toNanoOfDay() / 1000 : value;
                case TIMESTAMP -> value instanceof LocalDateTime dt ? Conversions.toMicros(dt) : value;
                case TIMESTAMPTZ -> switch (value) {
                    case Instant i -> micros(i);
                    case OffsetDateTime o -> micros(o.toInstant());
                    case ZonedDateTime z -> micros(z.toInstant());
                    default -> value;
                };
                case INTERVAL -> switch (value) {
                    case Duration d -> Interval.from(d);
                    case Period p -> Interval.from(p);
                    default -> value;
                };
                default -> value;
            };
            case DataType.Decimal d -> value;
        };
    }

    /** The microseconds since 1970-01-01 00:00:00 UTC of an instant. */
    private static long micros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), MICROS_PER_SECOND),
                instant.getNano() / 1000);
    }

    /** Tells whether a type is a date or time type, or with {@code interval} also {@code INTERVAL}, or has one. */
    private static boolean hasTime(DataType type, boolean interval) {
        return switch (type) {
            case DataType.Scalar s -> switch (s) {
                case DATE, TIME, TIMESTAMP, TIMESTAMPTZ -> true;
                case INTERVAL -> interval;
                default -> false;
            };
            case DataType.Decimal d -> false;
            case DataType.ListType l -> hasTime(l.element(), interval);
            case DataType.MapType m -> hasTime(m.key(), interval) || hasTime(m.value(), interval);
            case DataType.Struct s -> s.fields().stream().anyMatch(f -> hasTime(f.type(), interval));
        };
    }

    private static DataType.Scalar scalar(DataType type) {
        if (type instanceof DataType.Scalar s) {
            return s;
        }
        throw new IllegalArgumentException("Not a scalar type: " + type);
    }
}
