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
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.DataType.Struct;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The total order of Java values of a type, the same order as the vectors
 * use: numbers by value with NaN above everything and {@code -0.0} equal to
 * {@code 0.0}, strings by their UTF-8 bytes, UUIDs as unsigned numbers,
 * nested values element by element with {@code NULL} last.
 */
public final class Order {

    private Order() {
    }

    /** Compares two values of a type; neither may be {@code null}. */
    public static int compare(Object a, Object b, DataType type) {
        if (type instanceof Decimal) {
            return ((BigDecimal) a).compareTo((BigDecimal) b);
        }
        if (type instanceof Struct s) {
            List<?> x = (List<?>) a;
            List<?> y = (List<?>) b;
            for (int i = 0; i < s.fields().size(); i++) {
                int c = compareNullable(x.get(i), y.get(i), s.fields().get(i).type());
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
        if (type instanceof ListType l) {
            List<?> x = (List<?>) a;
            List<?> y = (List<?>) b;
            int n = Math.min(x.size(), y.size());
            for (int i = 0; i < n; i++) {
                int c = compareNullable(x.get(i), y.get(i), l.element());
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(x.size(), y.size());
        }
        if (type instanceof MapType m) {
            List<Map.Entry<?, ?>> x = new ArrayList<>(((Map<?, ?>) a).entrySet());
            List<Map.Entry<?, ?>> y = new ArrayList<>(((Map<?, ?>) b).entrySet());
            int n = Math.min(x.size(), y.size());
            for (int i = 0; i < n; i++) {
                int c = compareNullable(x.get(i).getKey(), y.get(i).getKey(), m.key());
                if (c == 0) {
                    c = compareNullable(x.get(i).getValue(), y.get(i).getValue(), m.value());
                }
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(x.size(), y.size());
        }
        return switch ((Scalar) type) {
            case BOOLEAN -> Boolean.compare((Boolean) a, (Boolean) b);
            case TINYINT, SMALLINT, INTEGER, BIGINT, DATE, TIMESTAMP ->
                    Long.compare(((Number) a).longValue(), ((Number) b).longValue());
            case HUGEINT -> ((BigInteger) a).compareTo((BigInteger) b);
            case FLOAT -> compare(((Float) a).floatValue(), ((Float) b).floatValue());
            case DOUBLE -> compare(((Double) a).doubleValue(), ((Double) b).doubleValue());
            case VARCHAR -> Arrays.compareUnsigned(((String) a).getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8));
            case BLOB -> Arrays.compareUnsigned((byte[]) a, (byte[]) b);
            case UUID -> {
                UUID x = (UUID) a;
                UUID y = (UUID) b;
                int c = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
                yield c != 0 ? c : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
            }
        };
    }

    /** Compares two values where either may be {@code null}, which sorts last. */
    public static int compareNullable(Object a, Object b, DataType type) {
        if (a == null || b == null) {
            return a == null ? b == null ? 0 : 1 : -1;
        }
        return compare(a, b, type);
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
}
