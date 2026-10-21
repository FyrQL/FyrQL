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

package net.fyrql.core.value;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fixed edge values of each type, apart from {@code NULL}: the bounds,
 * zero, minus one, the values just beyond the next narrower type, the special
 * floating-point values, and strings that are empty, blank, outside the Basic
 * Multilingual Plane, or parseable as other types. For a nested type, the
 * empty value, a value with a {@code NULL} inside and values built from the
 * edge values of the element types.
 */
public final class EdgeValues {

    private EdgeValues() {
    }

    /** Returns the edge values of a type, without {@code NULL}. */
    public static List<Object> of(DataType type) {
        if (type instanceof Decimal d) {
            return decimals(d);
        }
        if (type instanceof DataType.Struct s) {
            List<Object> values = new ArrayList<>();
            List<List<Object>> fieldEdges = s.fields().stream().map(f -> of(f.type())).toList();
            int longest = fieldEdges.stream().mapToInt(List::size).max().orElse(0);
            for (int i = 0; i < Math.min(longest, 4); i++) {
                List<Object> row = new ArrayList<>();
                for (List<Object> edges : fieldEdges) {
                    row.add(edges.get(i % edges.size()));
                }
                values.add(row);
            }
            List<Object> withNull = new ArrayList<>(s.fields().size());
            for (int i = 0; i < s.fields().size(); i++) {
                withNull.add(null);
            }
            values.add(withNull);
            return values;
        }
        if (type instanceof DataType.ListType l) {
            List<Object> edges = of(l.element());
            List<Object> values = new ArrayList<>();
            values.add(List.of());
            values.add(Arrays.asList((Object) null));
            values.add(List.of(edges.get(0)));
            values.add(new ArrayList<>(edges.subList(0, Math.min(edges.size(), 3))));
            return values;
        }
        if (type instanceof DataType.MapType m) {
            List<Object> keys = of(m.key());
            List<Object> vals = of(m.value());
            List<Object> values = new ArrayList<>();
            values.add(new LinkedHashMap<>());
            Map<Object, Object> one = new LinkedHashMap<>();
            one.put(keys.get(0), vals.get(0));
            values.add(one);
            Map<Object, Object> withNull = new LinkedHashMap<>();
            withNull.put(keys.get(keys.size() - 1), null);
            values.add(withNull);
            return values;
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> List.of(true, false);
            case TINYINT -> List.of(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 0, (byte) -1, (byte) 1);
            case SMALLINT -> List.of(Short.MIN_VALUE, Short.MAX_VALUE, (short) 0, (short) -1, (short) 1,
                    (short) 127, (short) 128, (short) -129);
            case INTEGER -> List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, 32767, 32768, -32769, 65535);
            case BIGINT -> List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L,
                    (long) Integer.MAX_VALUE + 1, (long) Integer.MIN_VALUE - 1);
            case HUGEINT -> List.of(Values.HUGEINT_MIN, Values.HUGEINT_MAX, BigInteger.ZERO, BigInteger.ONE.negate(),
                    BigInteger.ONE, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                    BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE));
            case FLOAT -> List.of(Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 0.0f, -0.0f, -1.0f,
                    0.5f, -0.5f, 1e10f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
            case DOUBLE -> List.of(Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 0.0, -0.0, -1.0,
                    0.5, -0.5, 1e19, 0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
            case VARCHAR -> List.of("", " ", "a", "A", "\u00DF", "\uD834\uDD1E", "%", "_", "123", " 42 ",
                    "-0", "NaN", "true", "1970-01-01", "2026-13-01", "'", "\\");
            case BLOB -> List.of(new byte[0], new byte[] {0}, new byte[] {(byte) 0xFF}, new byte[] {'a', 'b'},
                    new byte[] {'\\', 'x', '0', '0'});
            case DATE -> List.of(0, -1, 1, (int) LocalDate.of(1, 1, 1).toEpochDay(),
                    (int) LocalDate.of(9999, 12, 31).toEpochDay(), (int) LocalDate.of(2000, 2, 29).toEpochDay());
            case TIMESTAMP -> List.of(0L, -1L, 1L, 86_400_000_000L - 1, -86_400_000_000L,
                    Conversions.parseTimestamp("9999-12-31 23:59:59.999999"),
                    Conversions.parseTimestamp("0001-01-01 00:00:00"));
            case UUID -> List.of(new UUID(0, 0), new UUID(-1, -1), new UUID(0x0123456789ABCDEFL, 0xFEDCBA9876543210L));
            // Not 24:00:00, which the JDBC driver of DuckDB cannot read.
            case TIME -> List.of(0L, 1L, 43_200_000_000L, 86_399_999_999L, 3_723_000_001L);
            case TIMESTAMPTZ -> List.of(0L, -1L, 1L, 86_400_000_000L - 1, -86_400_000_000L,
                    Conversions.parseTimestamp("9999-12-31 23:59:59.999999"),
                    Conversions.parseTimestamp("0001-01-01 00:00:00"),
                    Conversions.parseTimestamp("2024-03-31 01:30:00"),
                    Conversions.parseTimestamp("2024-10-27 00:30:00"));
            case INTERVAL -> List.of(Interval.ZERO, Interval.ofMonths(1), Interval.ofDays(30),
                    Interval.ofMicros(Interval.MICROS_PER_MONTH), Interval.ofDays(1), Interval.ofMicros(1),
                    Interval.ofMicros(-1), Interval.of(1, -30, 0), Interval.of(-1, 2, -3_600_000_000L),
                    Interval.of(1_000_000, 0, 0), Interval.ofDays(-1_000_000),
                    Interval.ofMicros(86_400_000_000L * 365 * 1000));
        };
    }

    private static List<Object> decimals(Decimal d) {
        BigInteger max = d.maxUnscaled();
        List<BigInteger> unscaled = new ArrayList<>(List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate(),
                max, max.negate(), BigInteger.TEN.pow(d.scale())));
        if (d.scale() > 0) {
            unscaled.add(BigInteger.valueOf(5).multiply(BigInteger.TEN.pow(d.scale() - 1)));
        }
        List<Object> values = new ArrayList<>();
        for (BigInteger u : unscaled) {
            if (u.abs().compareTo(max) <= 0) {
                BigDecimal value = new BigDecimal(u, d.scale());
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }
}
