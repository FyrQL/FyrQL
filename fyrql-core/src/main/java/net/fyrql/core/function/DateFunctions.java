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

package net.fyrql.core.function;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Dates.Part;
import net.fyrql.core.value.Interval;
import net.fyrql.core.value.ZonedDates;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The functions on {@code DATE}, {@code TIMESTAMP}, {@code TIMESTAMP WITH
 * TIME ZONE}, {@code TIME} and {@code INTERVAL}, with the result types and
 * semantics of DuckDB 1.5.6 (see {@link Dates} for the calendar rules and
 * {@link ZonedDates} for the time zone). A function of a
 * {@code TIMESTAMP WITH TIME ZONE} computes in the time zone of the session,
 * which a binder fixes through {@link ScalarFunction#inZone}.
 * <ul>
 * <li>The date parts ({@code year}, {@code month}, ..., {@code era}) and
 * {@code date_part} give a {@code BIGINT}; {@code date_part} takes the name
 * of the part as a string, as {@link Part} lists them, and rejects
 * {@code epoch} and {@code julian}, which DuckDB answers with a
 * {@code DOUBLE} only for a constant name. A {@code TIME} has the parts of
 * the time of day, an {@code INTERVAL} the years to millennia, months,
 * quarters, days and the parts of the time of day.</li>
 * <li>{@code date_trunc} gives a {@code TIMESTAMP} for a date or timestamp,
 * a {@code TIMESTAMP WITH TIME ZONE} or an {@code INTERVAL} for those;
 * {@code date_diff} counts in {@code BIGINT} and compares a date with a
 * timestamp as timestamps.</li>
 * <li>{@code epoch} gives the seconds as a {@code DOUBLE}; {@code epoch_ms},
 * {@code epoch_us} and {@code epoch_ns} give whole units as a
 * {@code BIGINT}, where milliseconds are rounded towards zero;
 * {@code epoch_ms} of an integer is the inverse, a timestamp.</li>
 * <li>{@code make_date} takes year, month and day, or the days since
 * 1970-01-01; {@code make_timestamp} and {@code make_timestamptz} take
 * year, month, day, hour, minute and seconds with a fraction, the latter
 * also a time zone, or the microseconds since 1970-01-01; {@code make_time}
 * takes hour, minute and seconds; {@code to_timestamp} the seconds since
 * 1970-01-01 UTC.</li>
 * <li>{@code to_years}, {@code to_months}, ..., {@code to_microseconds}
 * build an interval of a number of a unit.</li>
 * <li>{@code age} subtracts as PostgreSQL does, {@code date_add}, {@code add}
 * and {@code subtract} add an interval and subtract timestamps with time
 * zone, {@code timezone(zone, x)} gives the local timestamp of an instant
 * in a time zone, or the instant of a local timestamp there.</li>
 * <li>{@code strftime} writes and {@code strptime} reads a timestamp in a
 * format of {@link DateFormats}; {@code try_strptime} gives {@code NULL}
 * where the string does not match; a format with {@code %z} or {@code %Z}
 * reads a {@code TIMESTAMP WITH TIME ZONE}. DuckDB takes the format only as
 * a constant.</li>
 * </ul>
 * The current date and time are not functions of the catalog; a binder
 * fixes them per query.
 */
final class DateFunctions {

    private DateFunctions() {
    }

    static List<ScalarFunction> all() {
        List<ScalarFunction> all = new ArrayList<>(List.of(
                part("year", Part.YEAR, false), part("month", Part.MONTH, false), part("day", Part.DAY, false),
                part("dayofmonth", Part.DAY, false), part("hour", Part.HOUR, true), part("minute", Part.MINUTE, true),
                part("second", Part.SECOND, true), part("millisecond", Part.MILLISECOND, true),
                part("microsecond", Part.MICROSECOND, true), part("dayofweek", Part.DOW, false),
                part("isodow", Part.ISODOW, false), part("dayofyear", Part.DOY, false), part("week", Part.WEEK, false),
                part("weekofyear", Part.WEEK, false), part("quarter", Part.QUARTER, false),
                part("yearweek", Part.YEARWEEK, false), part("isoyear", Part.ISOYEAR, false),
                part("decade", Part.DECADE, false), part("century", Part.CENTURY, false),
                part("millennium", Part.MILLENNIUM, false), part("era", Part.ERA, false)));
        all.addAll(List.of(DATE_PART, DATE_PART.alias("datepart"), DATE_TRUNC, DATE_TRUNC.alias("datetrunc"), DATE_DIFF,
                DATE_DIFF.alias("datediff"), EPOCH, EPOCH_MS, EPOCH_MS_TO_TIMESTAMP, EPOCH_US, EPOCH_NS, LAST_DAY,
                MONTHNAME, DAYNAME, MAKE_DATE, MAKE_DATE_FROM_DAYS, MAKE_TIMESTAMP, MAKE_TIMESTAMP_FROM_MICROS,
                MAKE_TIME, MAKE_TIMESTAMPTZ, MAKE_TIMESTAMPTZ_IN_ZONE, MAKE_TIMESTAMPTZ_FROM_MICROS, TO_TIMESTAMP,
                STRFTIME, STRFTIME_FORMAT_FIRST, STRPTIME, TRY_STRPTIME, AGE, TIMEZONE, TIMEZONE_OF));
        all.addAll(List.of(toUnit("to_years", Unit.MONTHS, 12), toUnit("to_months", Unit.MONTHS, 1),
                toUnit("to_decades", Unit.MONTHS, 120), toUnit("to_centuries", Unit.MONTHS, 1_200),
                toUnit("to_millennia", Unit.MONTHS, 12_000), toUnit("to_days", Unit.DAYS, 1),
                toUnit("to_weeks", Unit.DAYS, 7), toUnit("to_hours", Unit.MICROS, Dates.MICROS_PER_HOUR),
                toUnit("to_minutes", Unit.MICROS, Dates.MICROS_PER_MINUTE),
                toUnit("to_seconds", Unit.FRACTIONAL_MICROS, Dates.MICROS_PER_SECOND),
                toUnit("to_milliseconds", Unit.FRACTIONAL_MICROS, 1_000),
                toUnit("to_microseconds", Unit.MICROS, 1)));
        return all;
    }

    private static boolean isDateTime(DataType type) {
        return type == DataType.DATE || type == DataType.TIMESTAMP;
    }

    /** Tells whether a type is a date, a timestamp or a timestamp with time zone. */
    private static boolean isDated(DataType type) {
        return isDateTime(type) || type == DataType.TIMESTAMPTZ;
    }

    /** The local microseconds since 1970-01-01 of a {@code DATE}, {@code TIMESTAMP} or {@code TIMESTAMPTZ}. */
    private static long local(DataType type, Object value, ZoneId zone) {
        if (type == DataType.DATE) {
            return Dates.timestamp((Integer) value, 0);
        }
        return type == DataType.TIMESTAMPTZ ? ZonedDates.toLocal((Long) value, zone) : (Long) value;
    }

    /** The microseconds since 1970-01-01 UTC of a {@code DATE}, {@code TIMESTAMP} or {@code TIMESTAMPTZ}. */
    private static long micros(DataType type, Object value) {
        return type == DataType.DATE ? Dates.timestamp((Integer) value, 0) : (Long) value;
    }

    /** The local day of a {@code DATE}, {@code TIMESTAMP} or {@code TIMESTAMPTZ}. */
    private static LocalDate localDate(DataType type, Object value, ZoneId zone) {
        return LocalDate.ofEpochDay(Dates.dayOf(local(type, value, zone)));
    }

    /**
     * The type to which two dates, timestamps or timestamps with time zone
     * are cast to be compared: {@code TIMESTAMP WITH TIME ZONE} if one is
     * one, else {@code TIMESTAMP}.
     */
    private static DataType instants(List<DataType> types) {
        return types.contains(DataType.TIMESTAMPTZ) ? DataType.TIMESTAMPTZ : DataType.TIMESTAMP;
    }

    /** The microseconds of a date, timestamp, timestamp with time zone, time of day or interval for epoch_us. */
    private static long epochMicros(DataType type, Object value) {
        if (type == DataType.INTERVAL) {
            return ((Interval) value).epochMicros();
        }
        return type == DataType.TIME ? (Long) value : micros(type, value);
    }

    /** The rule of epoch_ms, epoch_us and epoch_ns. */
    private static final Function<List<DataType>, DataType> EPOCH_UNITS = types -> types.size() == 1
            && (isDated(types.get(0)) || types.get(0) == DataType.TIME || types.get(0) == DataType.INTERVAL)
            ? DataType.BIGINT : null;

    /** The rule of a function of one date, timestamp or timestamp with time zone. */
    private static Function<List<DataType>, DataType> ofDated(DataType result) {
        return types -> types.size() == 1 && isDated(types.get(0)) ? result : null;
    }

    /** Extracts a part of a value of any of the five types. */
    private static long extract(Part part, DataType type, Object value, ZoneId zone) {
        if (type == DataType.DATE && (part == Part.TIMEZONE || part == Part.TIMEZONE_HOUR
                || part == Part.TIMEZONE_MINUTE)) {
            throw new ConversionException("\"date\" units \"" + part.name().toLowerCase(Locale.ROOT)
                    + "\" not recognized");
        }
        if (type == DataType.TIME) {
            return Dates.extractTime(part, (Long) value);
        }
        if (type == DataType.INTERVAL) {
            return Dates.extract(part, (Interval) value);
        }
        if (type == DataType.TIMESTAMPTZ) {
            return ZonedDates.extract(part, (Long) value, zone);
        }
        long local = local(type, value, zone);
        return Dates.extract(part, Dates.dayOf(local), Dates.timeOf(local));
    }

    /**
     * A function that extracts a part, of an {@code INTERVAL} too, which
     * fails for the parts it does not have, and of a {@code TIME} if so
     * marked.
     */
    private static ScalarFunction part(String name, Part part, boolean ofTime) {
        return new ZonedFunction(name, types -> types.size() == 1 && (isDated(types.get(0))
                || ofTime && types.get(0) == DataType.TIME || types.get(0) == DataType.INTERVAL)
                ? DataType.BIGINT : null,
                (types, args, zone) -> extract(part, types.get(0), args[0], zone));
    }

    /** {@code date_part(part, x)}: the part with the given name. */
    static final ZonedFunction DATE_PART = new ZonedFunction("date_part",
            types -> types.size() == 2 && types.get(0) == DataType.VARCHAR && (isDated(types.get(1))
                    || types.get(1) == DataType.TIME || types.get(1) == DataType.INTERVAL) ? DataType.BIGINT : null,
            (types, args, zone) -> extract(Part.of((String) args[0]), types.get(1), args[1], zone));

    /** {@code date_trunc(part, x)}: the value truncated to the part. */
    static final ZonedFunction DATE_TRUNC = new ZonedFunction("date_trunc",
            types -> {
                if (types.size() != 2 || types.get(0) != DataType.VARCHAR) {
                    return null;
                }
                DataType t = types.get(1);
                return isDateTime(t) ? DataType.TIMESTAMP
                        : t == DataType.TIMESTAMPTZ || t == DataType.INTERVAL ? t : null;
            },
            (types, args, zone) -> {
                Part part = Part.of((String) args[0]);
                DataType t = types.get(1);
                if (t == DataType.INTERVAL) {
                    return Dates.trunc(part, (Interval) args[1]);
                }
                if (t == DataType.TIMESTAMPTZ) {
                    return ZonedDates.trunc(part, (Long) args[1], zone);
                }
                long local = local(t, args[1], zone);
                return Dates.trunc(part, Dates.dayOf(local), Dates.timeOf(local));
            });

    /** {@code date_diff(part, start, end)}: the boundaries of the part from start to end. */
    static final ZonedFunction DATE_DIFF = new ZonedFunction("date_diff",
            types -> types.size() == 3 && types.get(0) == DataType.VARCHAR
                    && (isDated(types.get(1)) && isDated(types.get(2))
                    || types.get(1) == DataType.TIME && types.get(2) == DataType.TIME) ? DataType.BIGINT : null,
            types -> types.get(1) == types.get(2) ? types : List.of(DataType.VARCHAR, instants(types.subList(1, 3)),
                    instants(types.subList(1, 3))),
            (types, args, zone) -> {
                Part part = Part.of((String) args[0]);
                DataType t = types.get(1);
                if (t == DataType.TIMESTAMPTZ) {
                    return ZonedDates.diff(part, (Long) args[1], (Long) args[2], zone);
                }
                if (t == DataType.TIME) {
                    Dates.extractTime(part, 0);
                    return Dates.diff(part, 0, (Long) args[1], 0, (Long) args[2]);
                }
                long start = local(t, args[1], zone);
                long end = local(t, args[2], zone);
                return Dates.diff(part, Dates.dayOf(start), Dates.timeOf(start), Dates.dayOf(end), Dates.timeOf(end));
            });

    /** {@code epoch(x)}: the seconds since 1970-01-01, of the day, or of the interval, with their fraction. */
    static final ZonedFunction EPOCH = new ZonedFunction("epoch",
            types -> types.size() == 1 && (isDated(types.get(0)) || types.get(0) == DataType.TIME
                    || types.get(0) == DataType.INTERVAL) ? DataType.DOUBLE : null,
            (types, args, zone) -> {
                DataType t = types.get(0);
                if (t == DataType.DATE) {
                    return (Integer) args[0] * 86_400.0;
                }
                if (t == DataType.INTERVAL) {
                    return ((Interval) args[0]).epochSeconds();
                }
                long micros = (Long) args[0];
                if (t == DataType.TIMESTAMPTZ) {
                    // As DuckDB computes it for an instant: its milliseconds, then the microseconds beyond them.
                    return Math.floorDiv(micros, 1000L) / 1e3 + Math.floorMod(micros, 1000L) / 1e6;
                }
                return micros / (double) Dates.MICROS_PER_SECOND;
            });

    /** {@code epoch_ms(x)}: the milliseconds since 1970-01-01, rounded towards zero. */
    static final ZonedFunction EPOCH_MS = new ZonedFunction("epoch_ms", EPOCH_UNITS,
            (types, args, zone) -> epochMicros(types.get(0), args[0]) / 1000);

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
    static final ZonedFunction EPOCH_US = new ZonedFunction("epoch_us", EPOCH_UNITS,
            (types, args, zone) -> epochMicros(types.get(0), args[0]));

    /** {@code epoch_ns(x)}: the nanoseconds since 1970-01-01. */
    static final ZonedFunction EPOCH_NS = new ZonedFunction("epoch_ns", EPOCH_UNITS,
            (types, args, zone) -> Math.multiplyExact(epochMicros(types.get(0), args[0]), 1000L));

    /** {@code last_day(x)}: the last day of the month. */
    static final ZonedFunction LAST_DAY = new ZonedFunction("last_day", ofDated(DataType.DATE),
            (types, args, zone) -> (int) localDate(types.get(0), args[0], zone)
                    .with(TemporalAdjusters.lastDayOfMonth()).toEpochDay());

    /** {@code monthname(x)}: the English name of the month. */
    static final ZonedFunction MONTHNAME = new ZonedFunction("monthname", ofDated(DataType.VARCHAR),
            (types, args, zone) -> localDate(types.get(0), args[0], zone).getMonth().getDisplayName(TextStyle.FULL,
                    Locale.ENGLISH));

    /** {@code dayname(x)}: the English name of the day of the week. */
    static final ZonedFunction DAYNAME = new ZonedFunction("dayname", ofDated(DataType.VARCHAR),
            (types, args, zone) -> localDate(types.get(0), args[0], zone).getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.ENGLISH));

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

    /** The rule of six arguments: five integers and the seconds as a number. */
    private static boolean dateAndTime(List<DataType> types) {
        return types.size() >= 6 && types.subList(0, 5).stream().allMatch(SimpleFunction::isInt64)
                && types.get(5).isNumeric();
    }

    private static final List<DataType> DATE_AND_TIME = List.of(DataType.BIGINT, DataType.BIGINT, DataType.BIGINT,
            DataType.BIGINT, DataType.BIGINT, DataType.DOUBLE);

    /** The local timestamp of the six arguments of {@code make_timestamp}. */
    private static long makeTimestamp(Object[] args) {
        int days = Dates.makeDate((Long) args[0], (Long) args[1], (Long) args[2]);
        return Dates.timestamp(days, Dates.makeTime((Long) args[3], (Long) args[4], (Double) args[5]));
    }

    /** {@code make_timestamp(year, month, day, hour, minute, seconds)}. */
    static final SimpleFunction MAKE_TIMESTAMP = new SimpleFunction("make_timestamp",
            types -> types.size() == 6 && dateAndTime(types) ? DataType.TIMESTAMP : null, types -> DATE_AND_TIME) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return makeTimestamp(args);
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

    /** {@code make_time(hour, minute, seconds)}: a time of day. */
    static final SimpleFunction MAKE_TIME = new SimpleFunction("make_time",
            types -> types.size() == 3 && SimpleFunction.isInt64(types.get(0)) && SimpleFunction.isInt64(types.get(1))
                    && types.get(2).isNumeric() ? DataType.TIME : null,
            types -> List.of(DataType.BIGINT, DataType.BIGINT, DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return Dates.makeTime((Long) args[0], (Long) args[1], (Double) args[2]);
        }
    };

    /** {@code make_timestamptz(year, month, day, hour, minute, seconds)}: in the time zone of the session. */
    static final ZonedFunction MAKE_TIMESTAMPTZ = new ZonedFunction("make_timestamptz",
            types -> types.size() == 6 && dateAndTime(types) ? DataType.TIMESTAMPTZ : null, types -> DATE_AND_TIME,
            (types, args, zone) -> ZonedDates.toInstant(makeTimestamp(args), zone));

    /** {@code make_timestamptz(year, month, day, hour, minute, seconds, zone)}: in the named time zone. */
    static final ZonedFunction MAKE_TIMESTAMPTZ_IN_ZONE = new ZonedFunction("make_timestamptz",
            types -> types.size() == 7 && dateAndTime(types) && types.get(6) == DataType.VARCHAR
                    ? DataType.TIMESTAMPTZ : null,
            types -> {
                List<DataType> result = new ArrayList<>(DATE_AND_TIME);
                result.add(DataType.VARCHAR);
                return result;
            },
            (types, args, zone) -> ZonedDates.toInstant(makeTimestamp(args), ZonedDates.zone((String) args[6])));

    /** {@code make_timestamptz(micros)}: the instant the given microseconds after 1970-01-01 UTC. */
    static final SimpleFunction MAKE_TIMESTAMPTZ_FROM_MICROS = new SimpleFunction("make_timestamptz",
            types -> types.size() == 1 && SimpleFunction.isInt64(types.get(0)) ? DataType.TIMESTAMPTZ : null,
            SimpleFunction.allTo(DataType.BIGINT)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            return finiteTimestamp((Long) args[0]);
        }
    };

    /** {@code to_timestamp(seconds)}: the instant the given seconds after 1970-01-01 UTC. */
    static final SimpleFunction TO_TIMESTAMP = new SimpleFunction("to_timestamp",
            types -> types.size() == 1 && types.get(0).isNumeric() ? DataType.TIMESTAMPTZ : null,
            SimpleFunction.allTo(DataType.DOUBLE)) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            double micros = Math.rint((Double) args[0] * Dates.MICROS_PER_SECOND);
            if (!(Math.abs(micros) < 0x1p63)) {
                throw new ConversionException("Epoch seconds out of range for TIMESTAMP WITH TIME ZONE");
            }
            return finiteTimestamp((long) micros);
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

    /** Writes a date, timestamp or timestamp with time zone in a format. */
    private static String strftime(DataType type, Object value, String format, ZoneId zone) {
        DateFormats.Format parsed = DateFormats.parse(format);
        long local = local(type, value, zone);
        if (type == DataType.TIMESTAMPTZ) {
            return DateFormats.format(parsed, Dates.dayOf(local), Dates.timeOf(local),
                    ZonedDates.offsetSeconds((Long) value, zone), ZonedDates.name(zone));
        }
        return DateFormats.format(parsed, Dates.dayOf(local), Dates.timeOf(local));
    }

    /** {@code strftime(x, format)}: the value written in the format. */
    static final ZonedFunction STRFTIME = new ZonedFunction("strftime",
            types -> types.size() == 2 && isDated(types.get(0)) && types.get(1) == DataType.VARCHAR
                    ? DataType.VARCHAR : null,
            (types, args, zone) -> strftime(types.get(0), args[0], (String) args[1], zone));

    /**
     * {@code strftime(format, x)}, the same with the arguments swapped, but
     * an instant written as its timestamp in UTC, as DuckDB writes it in this
     * order.
     */
    static final ZonedFunction STRFTIME_FORMAT_FIRST = new ZonedFunction("strftime",
            types -> types.size() == 2 && types.get(0) == DataType.VARCHAR && isDated(types.get(1))
                    ? DataType.VARCHAR : null,
            (types, args, zone) -> strftime(types.get(1) == DataType.TIMESTAMPTZ ? DataType.TIMESTAMP
                    : types.get(1), args[1], (String) args[0], zone));

    private static final Function<List<DataType>, DataType> STRING_AND_FORMAT =
            types -> SimpleFunction.varchars(types, 2) ? DataType.TIMESTAMP : null;

    private static final Function<List<DataType>, DataType> STRING_AND_ZONED_FORMAT =
            types -> SimpleFunction.varchars(types, 2) ? DataType.TIMESTAMPTZ : null;

    /** Reads a timestamp, or an instant if the format has a time zone. */
    private static Object strptime(Object[] args, ZoneId zone, boolean safe) {
        DateFormats.Format format = DateFormats.parse((String) args[1]);
        try {
            return format.zoned() ? DateFormats.parseZoned(format, (String) args[0], zone)
                    : DateFormats.parse(format, (String) args[0]);
        } catch (ConversionException | ArithmeticException e) {
            if (safe) {
                return null;
            }
            throw e;
        }
    }

    /** {@code strptime(s, format)} for a format with a time zone: an instant. */
    static final ZonedFunction STRPTIME_ZONED = new ZonedFunction("strptime", STRING_AND_ZONED_FORMAT,
            (types, args, zone) -> strptime(args, zone, false));

    /** {@code try_strptime(s, format)} for a format with a time zone: an instant. */
    static final ZonedFunction TRY_STRPTIME_ZONED = new ZonedFunction("try_strptime", STRING_AND_ZONED_FORMAT,
            (types, args, zone) -> strptime(args, zone, true));

    /** Returns the zoned variant for a constant format with {@code %z} or {@code %Z}. */
    private static ScalarFunction zonedIfFormat(ScalarFunction plain, ScalarFunction zoned, List<Expression> args) {
        if (args.size() == 2 && args.get(1) instanceof Expression.Literal l && l.value() instanceof String f) {
            try {
                return DateFormats.isZoned(f) ? zoned : plain;
            } catch (IllegalArgumentException e) {
                return plain;
            }
        }
        return plain;
    }

    /** {@code strptime(s, format)}: the timestamp the string names in the format. */
    static final ZonedFunction STRPTIME = new ZonedFunction("strptime", STRING_AND_FORMAT,
            (types, args, zone) -> strptime(args, zone, false)) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            return zonedIfFormat(this, STRPTIME_ZONED, arguments);
        }
    };

    /** {@code try_strptime(s, format)}: as {@code strptime}, but {@code NULL} where the string does not match. */
    static final ZonedFunction TRY_STRPTIME = new ZonedFunction("try_strptime", STRING_AND_FORMAT,
            (types, args, zone) -> strptime(args, zone, true)) {
        @Override
        public ScalarFunction specialize(List<Expression> arguments) {
            return zonedIfFormat(this, TRY_STRPTIME_ZONED, arguments);
        }
    };

    /** {@code age(a, b)}: the years, months, days and time from b to a, as PostgreSQL counts them. */
    static final ZonedFunction AGE = new ZonedFunction("age",
            types -> types.size() == 2 && isDated(types.get(0)) && isDated(types.get(1)) ? DataType.INTERVAL : null,
            types -> List.of(instants(types), instants(types)),
            (types, args, zone) -> types.get(0) == DataType.TIMESTAMPTZ
                    ? ZonedDates.age((Long) args[0], (Long) args[1], zone) : Dates.age((Long) args[0], (Long) args[1]));

    /**
     * {@code add(x, interval)}: {@code x + interval} for a timestamp with
     * time zone, in the time zone. In DuckDB {@code add} is every
     * {@code +}; FyrQL has it only for this, as the operator that a binder
     * binds, and not in the catalog, see {@link ZonedOperators}.
     */
    static final ZonedFunction ADD = new ZonedFunction("add",
            types -> types.size() == 2 && types.get(0) == DataType.TIMESTAMPTZ && types.get(1) == DataType.INTERVAL
                    ? DataType.TIMESTAMPTZ : null,
            (types, args, zone) -> ZonedDates.add((Long) args[0], (Interval) args[1], zone));

    /** {@code add(interval, x)}: {@code interval + x} for a timestamp with time zone, in the time zone. */
    static final ZonedFunction ADD_INTERVAL_FIRST = new ZonedFunction("add",
            types -> types.size() == 2 && types.get(0) == DataType.INTERVAL && types.get(1) == DataType.TIMESTAMPTZ
                    ? DataType.TIMESTAMPTZ : null,
            (types, args, zone) -> ZonedDates.add((Long) args[1], (Interval) args[0], zone));

    /**
     * {@code subtract(x, y)}: {@code x - y} for a timestamp with time zone
     * and an interval or another timestamp with time zone, in the time zone.
     */
    static final ZonedFunction SUBTRACT = new ZonedFunction("subtract",
            types -> types.size() != 2 || types.get(0) != DataType.TIMESTAMPTZ ? null
                    : types.get(1) == DataType.INTERVAL ? DataType.TIMESTAMPTZ
                    : types.get(1) == DataType.TIMESTAMPTZ ? DataType.INTERVAL : null,
            (types, args, zone) -> types.get(1) == DataType.INTERVAL
                    ? ZonedDates.add((Long) args[0], ((Interval) args[1]).negate(), zone)
                    : ZonedDates.subtract((Long) args[0], (Long) args[1], zone));

    /**
     * {@code timezone(zone, x)}: for an instant, its local timestamp in the
     * named time zone; for a local timestamp, its instant there.
     */
    static final SimpleFunction TIMEZONE = new SimpleFunction("timezone",
            types -> types.size() != 2 || types.get(0) != DataType.VARCHAR ? null
                    : types.get(1) == DataType.TIMESTAMPTZ ? DataType.TIMESTAMP
                    : isDateTime(types.get(1)) ? DataType.TIMESTAMPTZ : null) {
        @Override
        public Object evaluate(List<DataType> types, Object[] args) {
            ZoneId zone = ZonedDates.zone((String) args[0]);
            return types.get(1) == DataType.TIMESTAMPTZ ? ZonedDates.toLocal((Long) args[1], zone)
                    : ZonedDates.toInstant(micros(types.get(1), args[1]), zone);
        }
    };

    /** The component of an interval that {@code to_years} and its siblings fill. */
    private enum Unit {
        MONTHS, DAYS, MICROS, FRACTIONAL_MICROS
    }

    /**
     * {@code timezone(x)} of one argument, the part {@code timezone}: the
     * offset in seconds of a timestamp with time zone, 0 for a timestamp, an
     * error for a date or an interval.
     */
    static final ScalarFunction TIMEZONE_OF = part("timezone", Part.TIMEZONE, false);

    /** A number of a unit as a number of months or days, an INT32 as DuckDB takes it. */
    private static int toInt(String name, long value, long factor) {
        if (value != (int) value) {
            throw new ArithmeticException("Type INT64 with value " + value
                    + " can't be cast because the value is out of range for the destination type INT32");
        }
        long result = value * factor;
        if (result != (int) result) {
            throw new ArithmeticException("Interval value " + value + " " + name.substring(3) + " out of range");
        }
        return (int) result;
    }

    /** A function that builds an interval of a number of a unit. */
    private static ScalarFunction toUnit(String name, Unit unit, long factor) {
        DataType argument = unit == Unit.FRACTIONAL_MICROS ? DataType.DOUBLE : DataType.BIGINT;
        return new SimpleFunction(name, types -> types.size() == 1 && (unit == Unit.FRACTIONAL_MICROS
                ? types.get(0).isNumeric() : SimpleFunction.isInt64(types.get(0))) ? DataType.INTERVAL : null,
                SimpleFunction.allTo(argument)) {
            @Override
            public Object evaluate(List<DataType> types, Object[] args) {
                return switch (unit) {
                    case MONTHS -> Interval.ofMonths(toInt(name, (Long) args[0], factor));
                    case DAYS -> Interval.ofDays(toInt(name, (Long) args[0], factor));
                    case MICROS -> Interval.ofMicros(Math.multiplyExact((Long) args[0], factor));
                    case FRACTIONAL_MICROS -> {
                        double micros = Math.rint((Double) args[0] * factor);
                        if (!(Math.abs(micros) < 0x1p63)) {
                            throw new ArithmeticException("Interval value out of range: " + args[0]);
                        }
                        yield Interval.ofMicros((long) micros);
                    }
                };
            }
        };
    }
}
