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

package net.fyrql.exec;

import net.fyrql.core.expr.Expression.ArithmeticOp;
import net.fyrql.core.expr.ScalarOps;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.IntervalUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Order;
import net.fyrql.core.vector.Vector;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The bounds of a {@code RANGE} frame with an offset, over the one order
 * key of a window. A bound is the key of the current row minus or plus the
 * offset, minus toward the rows before in the order of the window, and the
 * frame reaches to the rows whose keys lie within the bounds, which a
 * binary search finds among the sorted keys of a partition.
 * <p>
 * As in DuckDB, a number is computed in the type of the key minus the
 * offset and fails where that overflows. A date or a timestamp moves as a
 * timestamp: by days and smaller units in microseconds, by months and years
 * in the calendar, where the end of a month stays within a shorter month.
 * A negative offset fails as soon as a bound is computed.
 */
final class RangeBounds {

    private final Vector key;
    private final DataType keyType;
    private final boolean ascending;
    /** The type the keys and the bounds are compared in, which every bound fits. */
    private final DataType type;
    /** The keys of the rows in that type, {@code null} for {@code NULL}. */
    private final Object[] keys;
    private final Offset start;
    private final Offset end;

    /**
     * The offset of a bound: a value of the type of the bound, or a count of
     * a unit, and whether the bound lies before the key in the order.
     */
    private record Offset(BoundKind kind, DataType type, Object value, IntervalUnit unit, boolean subtract,
            boolean negative) {
    }

    /**
     * Takes the keys of all rows of a sorted batch.
     *
     * @param key the column of the order key
     * @param order the order key
     * @param frame a {@code RANGE} frame with an offset
     * @throws ArithmeticException if a date is out of the range of timestamps
     */
    RangeBounds(Vector key, SortKey order, Frame frame) {
        this.key = key;
        this.keyType = key.type();
        this.ascending = order.ascending();
        this.start = offset(frame.start());
        this.end = offset(frame.end());
        boolean temporal = keyType == DataType.DATE || keyType == DataType.TIMESTAMP;
        DataType common = null;
        for (Offset o : new Offset[] {start, end}) {
            if (o != null) {
                common = common == null ? o.type : TypeRules.numeric(common, o.type);
            }
        }
        this.type = temporal ? DataType.TIMESTAMP : common;
        this.keys = new Object[key.size()];
        for (int r = 0; r < keys.length; r++) {
            Object v = key.get(r);
            keys[r] = v == null ? null : temporal ? micros(v) : Casts.cast(v, keyType, type);
        }
    }

    private Offset offset(FrameBound b) {
        if (b.offset() == null) {
            return null;
        }
        boolean subtract = (b.kind() == BoundKind.PRECEDING) == ascending;
        Object value = b.offset().value();
        if (b.unit() != null) {
            long count = ((Number) value).longValue();
            return new Offset(b.kind(), DataType.BIGINT, count, b.unit(), subtract, count < 0);
        }
        DataType t = TypeRules.numeric(keyType, b.offset().type());
        Object v = Casts.cast(value, b.offset().type(), t);
        boolean negative = v instanceof BigDecimal d ? d.signum() < 0
                : v instanceof BigInteger i ? i.signum() < 0 : ((Number) v).doubleValue() < 0;
        return new Offset(b.kind(), t, v, null, subtract, negative);
    }

    /** Returns a date or a timestamp in microseconds. */
    private long micros(Object v) {
        if (keyType == DataType.TIMESTAMP) {
            return (Long) v;
        }
        try {
            return Conversions.dateToTimestamp((Integer) v);
        } catch (ArithmeticException e) {
            throw outOfRange();
        }
    }

    private static ArithmeticException outOfRange() {
        return new ArithmeticException("Date and time not in timestamp range");
    }

    /** Tells whether the key of a row is {@code NULL}, whose frame is its peers. */
    boolean isNull(int row) {
        return keys[row] == null;
    }

    /**
     * Returns the first row of the sorted keys {@code [from, to)} that the
     * start bound of a row whose key is not {@code NULL} takes in.
     */
    int start(int row, int from, int to) {
        return search(bound(start, row), from, to, false);
    }

    /**
     * Returns the row after the last row of the sorted keys
     * {@code [from, to)} that the end bound of a row whose key is not
     * {@code NULL} takes in.
     */
    int end(int row, int from, int to) {
        return search(bound(end, row), from, to, true);
    }

    /** Returns the first row whose key comes after the bound, or, if not {@code after}, not before it. */
    private int search(Object bound, int from, int to, boolean after) {
        int lo = from;
        int hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = Order.compare(keys[mid], bound, type);
            c = ascending ? c : -c;
            if (after ? c <= 0 : c < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Returns the bound of a row in the type of the comparisons. */
    private Object bound(Offset o, int row) {
        if (o.negative) {
            throw new ArithmeticException("Invalid RANGE " + o.kind.keywords() + " value");
        }
        if (o.unit != null) {
            long count = (Long) o.value;
            return shift((Long) keys[row], o.subtract ? -count : count, o.unit);
        }
        Object k = Casts.cast(key.get(row), keyType, o.type);
        Object b;
        try {
            b = ScalarOps.arithmetic(o.subtract ? ArithmeticOp.SUBTRACT : ArithmeticOp.ADD, k, o.value, o.type,
                    o.type);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("Overflow in " + (o.subtract ? "subtraction" : "addition") + " of "
                    + o.type + " (" + k + (o.subtract ? " - " : " + ") + o.value + ")");
        }
        return Casts.cast(b, o.type, type);
    }

    /** Moves a timestamp by a count of a unit. */
    private static long shift(long micros, long count, IntervalUnit unit) {
        try {
            return switch (unit) {
                case YEAR -> months(micros, Math.multiplyExact(count, 12));
                case MONTH -> months(micros, count);
                case DAY -> Math.addExact(micros, Math.multiplyExact(count, Dates.MICROS_PER_DAY));
                case HOUR -> Math.addExact(micros, Math.multiplyExact(count, Dates.MICROS_PER_HOUR));
                case MINUTE -> Math.addExact(micros, Math.multiplyExact(count, Dates.MICROS_PER_MINUTE));
                case SECOND -> Math.addExact(micros, Math.multiplyExact(count, Dates.MICROS_PER_SECOND));
                case MILLISECOND -> Math.addExact(micros, Math.multiplyExact(count, 1000));
                case MICROSECOND -> Math.addExact(micros, count);
            };
        } catch (RuntimeException e) {
            throw outOfRange();
        }
    }

    private static long months(long micros, long months) {
        return Conversions.toMicros(Conversions.toLocalDateTime(micros).plusMonths(months));
    }
}
