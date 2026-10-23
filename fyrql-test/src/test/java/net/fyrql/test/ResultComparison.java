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

package net.fyrql.test;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.DataType.Struct;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Compares two results row by row in a canonical text form: floating-point
 * values by their bits with every NaN as one NaN, decimals with their
 * scale, blobs as hex, nested values recursively. An unordered result is
 * compared as a multiset.
 */
public final class ResultComparison {

    /** The outcome: whether the results agree, and a report of the differences if not. */
    public record Outcome(boolean same, String report) {
    }

    private ResultComparison() {
    }

    public static Outcome compare(Schema schema, List<Object[]> expected, List<Object[]> actual, boolean ordered) {
        List<String> e = canonical(schema, expected);
        List<String> a = canonical(schema, actual);
        if (!ordered) {
            Collections.sort(e);
            Collections.sort(a);
        }
        if (e.equals(a)) {
            return new Outcome(true, "");
        }
        StringBuilder report = new StringBuilder();
        report.append("expected ").append(e.size()).append(" rows, actual ").append(a.size()).append('\n');
        List<String> missing = new ArrayList<>(e);
        List<String> extra = new ArrayList<>(a);
        for (String row : e) {
            if (extra.remove(row)) {
                missing.remove(row);
            }
        }
        int shown = 0;
        for (String row : missing) {
            if (shown++ < 5) {
                report.append("  missing: ").append(row).append('\n');
            }
        }
        shown = 0;
        for (String row : extra) {
            if (shown++ < 5) {
                report.append("  extra:   ").append(row).append('\n');
            }
        }
        if (missing.isEmpty() && extra.isEmpty()) {
            for (int i = 0; i < Math.min(e.size(), a.size()); i++) {
                if (!e.get(i).equals(a.get(i))) {
                    report.append("  order differs at row ").append(i).append(": expected ").append(e.get(i))
                            .append(", actual ").append(a.get(i)).append('\n');
                    break;
                }
            }
        }
        return new Outcome(false, report.toString());
    }

    /**
     * Compares with a tolerance for floating-point values: two doubles or
     * floats agree if they lie within the given number of units in the last
     * place of each other, as results of transcendental functions differ
     * between math libraries. With a tolerance of 0 this is
     * {@link #compare(Schema, List, List, boolean)}; otherwise the report
     * shows the first row that differs and then the report of that method.
     */
    public static Outcome compare(Schema schema, List<Object[]> expected, List<Object[]> actual, boolean ordered,
            int ulps) {
        if (ulps == 0 || expected.size() != actual.size()) {
            return compare(schema, expected, actual, ordered);
        }
        List<Object[]> e = ordered ? expected : sortedByCanonical(schema, expected);
        List<Object[]> a = ordered ? actual : sortedByCanonical(schema, actual);
        for (int i = 0; i < e.size(); i++) {
            for (int c = 0; c < schema.size(); c++) {
                if (!close(e.get(i)[c], a.get(i)[c], schema.type(c), ulps)) {
                    // The first row that differs, then the rows that differ exactly, as a multiset if unordered.
                    return new Outcome(false, "row " + i + " differs at column " + c + " (" + schema.name(c)
                            + "): expected " + canonical(schema, List.<Object[]>of(e.get(i))).get(0) + ", actual "
                            + canonical(schema, List.<Object[]>of(a.get(i))).get(0) + "\n"
                            + compare(schema, expected, actual, ordered).report());
                }
            }
        }
        return new Outcome(true, "");
    }

    private static List<Object[]> sortedByCanonical(Schema schema, List<Object[]> rows) {
        List<String> keys = canonical(schema, rows);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort((x, y) -> keys.get(x).compareTo(keys.get(y)));
        List<Object[]> result = new ArrayList<>(rows.size());
        for (int i : order) {
            result.add(rows.get(i));
        }
        return result;
    }

    /** Tells whether two values agree, floating-point values within a number of units in the last place. */
    private static boolean close(Object x, Object y, DataType type, int ulps) {
        if (x == null || y == null) {
            return x == y;
        }
        if (type == DataType.DOUBLE || type == DataType.FLOAT) {
            double a = ((Number) x).doubleValue();
            double b = ((Number) y).doubleValue();
            if (Double.isNaN(a) || Double.isNaN(b)) {
                return Double.isNaN(a) && Double.isNaN(b);
            }
            if (Double.isInfinite(a) || Double.isInfinite(b)) {
                return a == b;
            }
            double ulp = type == DataType.FLOAT ? Math.ulp((float) Math.max(Math.abs(a), Math.abs(b)))
                    : Math.ulp(Math.max(Math.abs(a), Math.abs(b)));
            return Math.abs(a - b) <= ulps * ulp;
        }
        return canonical(x, type).equals(canonical(y, type));
    }

    /** Renders rows in their canonical form. */
    public static List<String> canonical(Schema schema, List<Object[]> rows) {
        List<String> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            StringBuilder b = new StringBuilder("[");
            for (int c = 0; c < schema.size(); c++) {
                b.append(c > 0 ? ", " : "").append(canonical(row[c], schema.type(c)));
            }
            result.add(b.append(']').toString());
        }
        return result;
    }

    /** The canonical text of one value, the same for the value of a vector and that of {@code rows()}. */
    public static String canonical(Object v, DataType type) {
        if (v == null) {
            return "NULL";
        }
        if (type instanceof Decimal) {
            BigDecimal d = (BigDecimal) v;
            return d.unscaledValue() + "e" + d.scale();
        }
        if (type instanceof Struct s) {
            List<?> values = (List<?>) v;
            StringBuilder b = new StringBuilder("{");
            for (int i = 0; i < values.size(); i++) {
                b.append(i > 0 ? ", " : "").append(canonical(values.get(i), s.fields().get(i).type()));
            }
            return b.append('}').toString();
        }
        if (type instanceof ListType l) {
            StringBuilder b = new StringBuilder("[");
            int i = 0;
            for (Object item : (List<?>) v) {
                b.append(i++ > 0 ? ", " : "").append(canonical(item, l.element()));
            }
            return b.append(']').toString();
        }
        if (type instanceof MapType m) {
            StringBuilder b = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                b.append(i++ > 0 ? ", " : "").append(canonical(e.getKey(), m.key())).append('=')
                        .append(canonical(e.getValue(), m.value()));
            }
            return b.append('}').toString();
        }
        return switch ((Scalar) type) {
            // Every NaN is one NaN, and -0.0 counts as 0.0: DuckDB treats them as equal and returns 0.0 for a
            // -0.0 that went through a sort key.
            case FLOAT -> {
                float f = (Float) v;
                yield "f" + Integer.toHexString(Float.isNaN(f) ? 0x7fc00000 : Float.floatToRawIntBits(f == 0 ? 0.0f : f));
            }
            case DOUBLE -> {
                double d = (Double) v;
                yield "d" + Long.toHexString(Double.isNaN(d) ? 0x7ff8000000000000L
                        : Double.doubleToRawLongBits(d == 0 ? 0.0 : d));
            }
            case VARCHAR -> "'" + v + "'";
            case BLOB -> "x" + HexFormat.of().formatHex((byte[]) v);
            // A value of rows() has the dates and times of java.time, a value of a batch those of its vector.
            default -> Casts.format(Values.fromJava(v, type), type);
        };
    }
}
