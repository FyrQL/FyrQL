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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * The equality of grouping and join keys on Java values. A key value is first
 * brought into its canonical form: {@code -0.0} becomes {@code 0.0} and every
 * NaN the one NaN of {@link Double#NaN} or {@link Float#NaN}, so that they
 * group as DuckDB groups them. Decimals keep their scale, which is the scale
 * of their type. Canonical values are equal if {@link Object#equals} says so;
 * {@code byte[]} compares by content.
 */
public final class Keys {

    private Keys() {
    }

    /** Returns the canonical form of a key value; {@code null} stays {@code null}. */
    public static Object canonical(Object value) {
        if (value instanceof Double d) {
            return canonical(d.doubleValue());
        }
        if (value instanceof Float f) {
            return canonical(f.floatValue());
        }
        if (value instanceof List<?> list) {
            Object[] items = new Object[list.size()];
            int i = 0;
            for (Object item : list) {
                items[i++] = canonical(item);
            }
            return Arrays.asList(items);
        }
        return value;
    }

    /** Returns the canonical form of a {@code double} key value. */
    public static double canonical(double value) {
        if (value == 0.0) {
            return 0.0;
        }
        return Double.isNaN(value) ? Double.NaN : value;
    }

    /** Returns the canonical form of a {@code float} key value. */
    public static float canonical(float value) {
        if (value == 0.0f) {
            return 0.0f;
        }
        return Float.isNaN(value) ? Float.NaN : value;
    }

    /** Tells whether two canonical {@code double} key values are equal. */
    public static boolean equal(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /** Tells whether two canonical {@code float} key values are equal. */
    public static boolean equal(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    /**
     * Tells whether two canonical key values are equal; both may be {@code null}.
     * Lists and maps compare element by element in their order, so two maps
     * with the same entries in a different order differ, as in DuckDB.
     */
    public static boolean equalObjects(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            return equalLists(x, y);
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            return equalMaps(x, y);
        }
        return a.equals(b);
    }

    private static boolean equalLists(List<?> x, List<?> y) {
        int size = x.size();
        if (size != y.size()) {
            return false;
        }
        if (x instanceof RandomAccess && y instanceof RandomAccess) {
            for (int i = 0; i < size; i++) {
                if (!equalObjects(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        Iterator<?> itX = x.iterator();
        Iterator<?> itY = y.iterator();
        while (itX.hasNext()) {
            if (!equalObjects(itX.next(), itY.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalMaps(Map<?, ?> x, Map<?, ?> y) {
        if (x.size() != y.size()) {
            return false;
        }
        Iterator<? extends Map.Entry<?, ?>> itX = x.entrySet().iterator();
        Iterator<? extends Map.Entry<?, ?>> itY = y.entrySet().iterator();
        while (itX.hasNext()) {
            Map.Entry<?, ?> ex = itX.next();
            Map.Entry<?, ?> ey = itY.next();
            if (!equalObjects(ex.getKey(), ey.getKey()) || !equalObjects(ex.getValue(), ey.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Returns the hash code of a canonical key value, 0 for {@code null}, consistent with {@link #equalObjects}. */
    public static int hash(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof byte[] bytes) {
            return Arrays.hashCode(bytes);
        }
        if (value instanceof BigDecimal d) {
            return d.stripTrailingZeros().hashCode();
        }
        if (value instanceof List<?> list) {
            int h = 1;
            for (Object v : list) {
                h = 31 * h + hash(v);
            }
            return h;
        }
        if (value instanceof Map<?, ?> map) {
            int h = 7;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                h = 31 * h + hash(e.getKey());
                h = 31 * h + hash(e.getValue());
            }
            return h;
        }
        return value.hashCode();
    }
}
