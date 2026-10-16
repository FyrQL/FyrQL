/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.core.function;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Dates.Part;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The functions on {@code DATE} and {@code TIMESTAMP}, with the result types
 * and semantics of DuckDB 1.5.6 (see {@link Dates} for the calendar rules).
 * <ul>
 * <li>The date parts ({@code year}, {@code month}, ..., {@code era}) and
 * {@code date_part} give a {@code BIGINT}; {@code date_part} takes the name
 * of the part as a string, as {@link Part} lists them, and rejects
 * {@code epoch} and {@code julian}, which DuckDB answers with a
 * {@code DOUBLE} only for a constant name.</li>
 * <li>{@code date_trunc} gives a {@code TIMESTAMP}, also for a date;
 * {@code date_diff} counts in {@code BIGINT} and compares a date with a
 * timestamp as timestamps.</li>
 * <li>{@code epoch} gives the seconds as a {@code DOUBLE}; {@code epoch_ms},
 * {@code epoch_us} and {@code epoch_ns} give whole units as a
 * {@code BIGINT}, where milliseconds are rounded towards zero;
 * {@code epoch_ms} of an integer is the inverse, a timestamp.</li>
 * <li>{@code make_date} takes year, month and day, or the days since
 * 1970-01-01; {@code make_timestamp} takes year, month, day, hour, minute
 * and seconds with a fraction, or the microseconds since 1970-01-01.</li>
 * <li>{@code strftime} writes and {@code strptime} reads a timestamp in a
 * format of {@link DateFormats}; {@code try_strptime} gives {@code NULL}
 * where the string does not match. DuckDB takes the format only as a
 * constant.</li>
 * </ul>
 * The current date and time are not functions of the catalog; a binder
 * fixes them per query.
 */
final class DateFunctions {

    private DateFunctions() {
    }

    static List<ScalarFunction> all() {
        List<ScalarFunction> all = new ArrayList<>(List.of(
                part("year", Part.YEAR), part("month", Part.MONTH), part("day", Part.DAY),
                part("dayofmonth", Part.DAY), part("hour", Part.HOUR), part("minute", Part.MINUTE),
                part("second", Part.SECOND), part("millisecond", Part.MILLISECOND),
                part("microsecond", Part.MICROSECOND), part("dayofweek", Part.DOW), part("isodow", Part.ISODOW),
                part("dayofyear", Part.DOY), part("week", Part.WEEK), part("weekofyear", Part.WEEK),
                part("quarter", Part.QUARTER), part("yearweek", Part.YEARWEEK), part("isoyear", Part.ISOYEAR),
                part("decade", Part.DECADE), part("century", Part.CENTURY), part("millennium", Part.MILLENNIUM),
                part("era", Part.ERA)));
        all.addAll(List.of(DATE_PART, DATE_PART.alias("datepart"), DATE_TRUNC, DATE_TRUNC.alias("datetrunc"), DATE_DIFF,
                DATE_DIFF.alias("datediff"), EPOCH, EPOCH_MS, EPOCH_MS_TO_TIMESTAMP, EPOCH_US, EPOCH_NS, LAST_DAY,
                MONTHNAME, DAYNAME, MAKE_DATE, MAKE_DATE_FROM_DAYS, MAKE_TIMESTAMP, MAKE_TIMESTAMP_FROM_MICROS,
                STRFTIME, STRFTIME_FORMAT_FIRST, STRPTIME, TRY_STRPTIME));
        return all;
    }

    private static boolean isDateTime(DataType type) {
        return type == DataType.DATE || type == DataType.TIMESTAMP;
    }

    /** The day of a {@code DATE} or {@code TIMESTAMP} value. */
    private static int days(DataType type, Object value) {
        return type == DataType.DATE ? (Integer) value : Dates.dayOf((Long) value);
    }

    /** The microseconds since midnight of a {@code DATE} or {@code TIMESTAMP} value. */
    private static long time(DataType type, Object value) {
        return type == DataType.DATE ? 0 : Dates.timeOf((Long) value);
    }

    /** The microseconds since 1970-01-01 of a {@code DATE} or {@code TIMESTAMP} value. */
    private static long micros(DataType type, Object value) {
        return type == DataType.DATE ? Dates.timestamp((Integer) value, 0) : (Long) value;
    }

    /** The rule of a function of one date or timestamp. */
    private static Function<List<DataType>, DataType> ofDateTime(DataType result) {
        return types -> types.size() == 1 && isDateTime(types.get(0)) ? result : null;
    }

    /** A function that extracts a part of a date or timestamp. */
    private static SimpleFunction part(String name, Part part) {
        return new SimpleFunction(name, ofDateTime(DataType.BIGINT)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return Dates.extract(part, days(types.get(0), args[0]), time(types.get(0), args[0]));
            }
        };
    }

    /** {@code date_part(part, x)}: the part with the given name. */
    static final SimpleFunction DATE_PART = new SimpleFunction("date_part",
            types -> types.size() == 2 && types.get(0) == DataType.VARCHAR && isDateTime(types.get(1))
                    ? DataType.BIGINT : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Dates.extract(Part.of((String) args[0]), days(types.get(1), args[1]), time(types.get(1), args[1]));
        }
    };

    /** {@code date_trunc(part, x)}: the timestamp truncated to the part. */
    static final SimpleFunction DATE_TRUNC = new SimpleFunction("date_trunc",
            types -> types.size() == 2 && types.get(0) == DataType.VARCHAR && isDateTime(types.get(1))
                    ? DataType.TIMESTAMP : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Dates.trunc(Part.of((String) args[0]), days(types.get(1), args[1]), time(types.get(1), args[1]));
        }
    };

    /** {@code date_diff(part, start, end)}: the boundaries of the part from start to end. */
    static final SimpleFunction DATE_DIFF = new SimpleFunction("date_diff",
            types -> types.size() == 3 && types.get(0) == DataType.VARCHAR && isDateTime(types.get(1))
                    && isDateTime(types.get(2)) ? DataType.BIGINT : null,
            types -> types.get(1) == DataType.DATE && types.get(2) == DataType.DATE ? types
                    : List.of(DataType.VARCHAR, DataType.TIMESTAMP, DataType.TIMESTAMP)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DataType t = types.get(1);
            return Dates.diff(Part.of((String) args[0]), days(t, args[1]), time(t, args[1]), days(t, args[2]),
                    time(t, args[2]));
        }
    };

    /** {@code epoch(x)}: the seconds since 1970-01-01 with their fraction. */
    static final SimpleFunction EPOCH = new SimpleFunction("epoch", ofDateTime(DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            if (types.get(0) == DataType.DATE) {
                return (Integer) args[0] * 86_400.0;
            }
            return (Long) args[0] / (double) Dates.MICROS_PER_SECOND;
        }
    };

    /** {@code epoch_ms(x)}: the milliseconds since 1970-01-01, rounded towards zero. */
    static final SimpleFunction EPOCH_MS = new SimpleFunction("epoch_ms", ofDateTime(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return micros(types.get(0), args[0]) / 1000;
        }
    };

    /** {@code epoch_ms(n)}: the timestamp n milliseconds after 1970-01-01. */
    static final SimpleFunction EPOCH_MS_TO_TIMESTAMP = new SimpleFunction("epoch_ms",
            types -> types.size() == 1 && SimpleFunction.isInt64(types.get(0)) ? DataType.TIMESTAMP : null,
            SimpleFunction.allTo(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return finiteTimestamp(Math.multiplyExact((Long) args[0], 1000L));
        }
    };

    /** {@code epoch_us(x)}: the microseconds since 1970-01-01. */
    static final SimpleFunction EPOCH_US = new SimpleFunction("epoch_us", ofDateTime(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return micros(types.get(0), args[0]);
        }
    };

    /** {@code epoch_ns(x)}: the nanoseconds since 1970-01-01. */
    static final SimpleFunction EPOCH_NS = new SimpleFunction("epoch_ns", ofDateTime(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Math.multiplyExact(micros(types.get(0), args[0]), 1000L);
        }
    };

    /** {@code last_day(x)}: the last day of the month. */
    static final SimpleFunction LAST_DAY = new SimpleFunction("last_day", ofDateTime(DataType.DATE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            LocalDate date = LocalDate.ofEpochDay(days(types.get(0), args[0]));
            return (int) date.with(TemporalAdjusters.lastDayOfMonth()).toEpochDay();
        }
    };

    /** {@code monthname(x)}: the English name of the month. */
    static final SimpleFunction MONTHNAME = new SimpleFunction("monthname", ofDateTime(DataType.VARCHAR)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return LocalDate.ofEpochDay(days(types.get(0), args[0])).getMonth().getDisplayName(TextStyle.FULL,
                    Locale.ENGLISH);
        }
    };

    /** {@code dayname(x)}: the English name of the day of the week. */
    static final SimpleFunction DAYNAME = new SimpleFunction("dayname", ofDateTime(DataType.VARCHAR)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return LocalDate.ofEpochDay(days(types.get(0), args[0])).getDayOfWeek().getDisplayName(TextStyle.FULL,
                    Locale.ENGLISH);
        }
    };

    /** {@code make_date(year, month, day)}. */
    static final SimpleFunction MAKE_DATE = new SimpleFunction("make_date",
            types -> types.size() == 3 && types.stream().allMatch(SimpleFunction::isInt64) ? DataType.DATE : null,
            SimpleFunction.allTo(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Dates.makeDate((Long) args[0], (Long) args[1], (Long) args[2]);
        }
    };

    /** {@code make_date(days)}: the date the given number of days after 1970-01-01. */
    static final SimpleFunction MAKE_DATE_FROM_DAYS = new SimpleFunction("make_date",
            types -> types.size() == 1 && SimpleFunction.isInt32(types.get(0)) ? DataType.DATE : null,
            SimpleFunction.allTo(DataType.INTEGER)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Dates.checkDays((Integer) args[0]);
        }
    };

    /** {@code make_timestamp(year, month, day, hour, minute, seconds)}. */
    static final SimpleFunction MAKE_TIMESTAMP = new SimpleFunction("make_timestamp",
            types -> types.size() == 6 && types.subList(0, 5).stream().allMatch(SimpleFunction::isInt64)
                    && types.get(5).isNumeric() ? DataType.TIMESTAMP : null,
            types -> List.of(DataType.BIGINT, DataType.BIGINT, DataType.BIGINT, DataType.BIGINT, DataType.BIGINT,
                    DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            int days = Dates.makeDate((Long) args[0], (Long) args[1], (Long) args[2]);
            return Dates.timestamp(days, Dates.makeTime((Long) args[3], (Long) args[4], (Double) args[5]));
        }
    };

    /** {@code make_timestamp(micros)}: the timestamp the given microseconds after 1970-01-01. */
    static final SimpleFunction MAKE_TIMESTAMP_FROM_MICROS = new SimpleFunction("make_timestamp",
            types -> types.size() == 1 && SimpleFunction.isInt64(types.get(0)) ? DataType.TIMESTAMP : null,
            SimpleFunction.allTo(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return finiteTimestamp((Long) args[0]);
        }
    };

    /**
     * Checks that a timestamp is none of the two values with which DuckDB
     * stands for infinity, nor below them.
     */
    private static long finiteTimestamp(long micros) {
        if (micros >= Long.MAX_VALUE || micros <= -Long.MAX_VALUE) {
            throw new ConversionException("Timestamp microseconds out of range: " + micros);
        }
        return micros;
    }

    /** {@code strftime(x, format)}: the date or timestamp written in the format. */
    static final SimpleFunction STRFTIME = new SimpleFunction("strftime",
            types -> types.size() == 2 && isDateTime(types.get(0)) && types.get(1) == DataType.VARCHAR
                    ? DataType.VARCHAR : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return DateFormats.format(DateFormats.parse((String) args[1]), days(types.get(0), args[0]),
                    time(types.get(0), args[0]));
        }
    };

    /** {@code strftime(format, x)}, the same with the arguments swapped. */
    static final SimpleFunction STRFTIME_FORMAT_FIRST = new SimpleFunction("strftime",
            types -> types.size() == 2 && types.get(0) == DataType.VARCHAR && isDateTime(types.get(1))
                    ? DataType.VARCHAR : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return DateFormats.format(DateFormats.parse((String) args[0]), days(types.get(1), args[1]),
                    time(types.get(1), args[1]));
        }
    };

    private static final Function<List<DataType>, DataType> STRING_AND_FORMAT =
            types -> SimpleFunction.varchars(types, 2) ? DataType.TIMESTAMP : null;

    /** {@code strptime(s, format)}: the timestamp the string names in the format. */
    static final SimpleFunction STRPTIME = new SimpleFunction("strptime", STRING_AND_FORMAT) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return DateFormats.parse(DateFormats.parse((String) args[1]), (String) args[0]);
        }
    };

    /** {@code try_strptime(s, format)}: as {@code strptime}, but {@code NULL} where the string does not match. */
    static final SimpleFunction TRY_STRPTIME = new SimpleFunction("try_strptime", STRING_AND_FORMAT) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            DateFormats.Format format = DateFormats.parse((String) args[1]);
            try {
                return DateFormats.parse(format, (String) args[0]);
            } catch (ConversionException | ArithmeticException e) {
                return null;
            }
        }
    };
}
