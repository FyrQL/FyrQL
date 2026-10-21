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
import net.fyrql.core.value.Interval;

import java.util.Arrays;

/** An {@code INTERVAL} vector: the months, days and microseconds per row. */
public final class IntervalVector implements Vector {

    final int[] months;
    final int[] days;
    final long[] micros;
    private final boolean[] nulls;

    IntervalVector(int[] months, int[] days, long[] micros, boolean[] nulls) {
        this.months = months;
        this.days = days;
        this.micros = micros;
        this.nulls = nulls;
    }

    /**
     * Creates a vector over arrays, which are not copied: the components and
     * a mask with {@code true} where the value is {@code NULL}, or
     * {@code null} if none is. The caller must not change the arrays
     * afterwards.
     */
    public static IntervalVector wrap(int[] months, int[] days, long[] micros, boolean[] nulls) {
        if (days.length != months.length || micros.length != months.length) {
            throw new IllegalArgumentException("The components differ in length");
        }
        return new IntervalVector(months, days, micros, Nulls.wrap(nulls, months.length));
    }

    @Override
    public DataType type() {
        return DataType.INTERVAL;
    }

    @Override
    public int size() {
        return months.length;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    @Override
    public boolean hasNulls() {
        return Nulls.any(nulls, size());
    }

    public int getMonths(int row) {
        return months[row];
    }

    public int getDays(int row) {
        return days[row];
    }

    public long getMicros(int row) {
        return micros[row];
    }

    /** Returns the value at a row, which must not be {@code NULL}. */
    public Interval getInterval(int row) {
        return Interval.of(months[row], days[row], micros[row]);
    }

    @Override
    public Object get(int row) {
        return isNull(row) ? null : getInterval(row);
    }

    @Override
    public IntervalVector select(int[] rows) {
        int[] m = new int[rows.length];
        int[] d = new int[rows.length];
        long[] u = new long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            m[i] = months[rows[i]];
            d[i] = days[rows[i]];
            u[i] = micros[rows[i]];
        }
        return new IntervalVector(m, d, u, Nulls.select(nulls, rows));
    }

    /** Builds an {@code INTERVAL} vector. */
    public static final class Builder implements Vector.Builder {
        private int[] months;
        private int[] days;
        private long[] micros;
        private boolean[] nulls;
        private int size;

        public Builder(int capacity) {
            months = new int[Math.max(capacity, 1)];
            days = new int[months.length];
            micros = new long[months.length];
        }

        @Override
        public DataType type() {
            return DataType.INTERVAL;
        }

        public void appendInterval(int monthsValue, int daysValue, long microsValue) {
            ensure();
            months[size] = monthsValue;
            days[size] = daysValue;
            micros[size++] = microsValue;
        }

        @Override
        public void appendNull() {
            ensure();
            nulls = Nulls.mark(nulls, size++, months.length);
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
            } else {
                Interval v = (Interval) value;
                appendInterval(v.months(), v.days(), v.micros());
            }
        }

        private void ensure() {
            if (size == months.length) {
                months = Arrays.copyOf(months, size * 2);
                days = Arrays.copyOf(days, size * 2);
                micros = Arrays.copyOf(micros, size * 2);
                nulls = Nulls.grow(nulls, size * 2);
            }
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public IntervalVector build() {
            return new IntervalVector(Arrays.copyOf(months, size), Arrays.copyOf(days, size),
                    Arrays.copyOf(micros, size), Nulls.trim(nulls, size));
        }
    }
}
