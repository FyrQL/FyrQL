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

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The calendar arithmetic of {@code DATE}, {@code TIMESTAMP}, {@code TIME}
 * and {@code INTERVAL}, measured
 * against DuckDB 1.5.6: the range of dates, and the parts that
 * {@code date_part} extracts, {@code date_trunc} truncates to and
 * {@code date_diff} counts. The calendar is the proleptic Gregorian one,
 * year 0 being 1 BC. A timestamp splits into its day and the microseconds
 * of that day, both rounded down, so that the time of day is never
 * negative.
 */
public final class Dates {

    /** The first day DuckDB represents; the day before stands for minus infinity. */
    public static final int MIN_DAYS = -2_147_483_646;

    /** The last day DuckDB represents; the day after stands for infinity. */
    public static final int MAX_DAYS = 2_147_483_646;

    public static final long MICROS_PER_SECOND = 1_000_000L;
    public static final long MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND;
    public static final long MICROS_PER_HOUR = 60 * MICROS_PER_MINUTE;
    public static final long MICROS_PER_DAY = 24 * MICROS_PER_HOUR;

    private static final long SECONDS_PER_DAY = 86_400L;

    private Dates() {
    }

    /**
     * A part of a date or timestamp, with every name DuckDB accepts for it;
     * the names are not case-sensitive.
     */
    public enum Part {
        YEAR("year", "yr", "y", "years", "yrs"),
        MONTH("month", "mon", "months", "mons"),
        DAY("day", "days", "d", "dayofmonth"),
        DECADE("decade", "dec", "decades", "decs"),
        CENTURY("century", "cent", "centuries", "c"),
        MILLENNIUM("millennium", "mil", "millenniums", "millennia", "mils", "millenium"),
        MICROSECOND("microseconds", "microsecond", "us", "usec", "usecs", "usecond", "useconds"),
        MILLISECOND("milliseconds", "millisecond", "ms", "msec", "msecs", "msecond", "mseconds"),
        SECOND("second", "sec", "seconds", "secs", "s"),
        MINUTE("minute", "min", "minutes", "mins", "m"),
        HOUR("hour", "hr", "hours", "hrs", "h"),
        EPOCH("epoch"),
        DOW("dow", "dayofweek", "weekday"),
        ISODOW("isodow"),
        WEEK("week", "weeks", "w", "weekofyear"),
        DOY("doy", "dayofyear"),
        QUARTER("quarter", "quarters"),
        YEARWEEK("yearweek"),
        ISOYEAR("isoyear"),
        ERA("era"),
        JULIAN("julian"),
        TIMEZONE("timezone"),
        TIMEZONE_HOUR("timezone_hour"),
        TIMEZONE_MINUTE("timezone_minute");

        private static final Map<String, Part> BY_NAME = new HashMap<>();

        static {
            for (Part p : values()) {
                for (String name : p.names) {
                    BY_NAME.put(name, p);
                }
            }
        }

        private final String[] names;

        Part(String... names) {
            this.names = names;
        }

        /**
         * Returns the part a name stands for.
         *
         * @throws ConversionException if the name is unknown
         */
        public static Part of(String name) {
            Part p = BY_NAME.get(name.toLowerCase(Locale.ROOT));
            if (p == null) {
                throw new ConversionException("extract specifier \"" + name + "\" not recognized");
            }
            return p;
        }
    }

    /**
     * Checks that a day lies in the range of DuckDB.
     *
     * @throws ArithmeticException otherwise
     */
    public static int checkDays(long days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new ArithmeticException("Date out of range");
        }
        return (int) days;
    }

    /** Returns the day of a timestamp. */
    public static int dayOf(long micros) {
        return Math.toIntExact(Math.floorDiv(micros, MICROS_PER_DAY));
    }

    /** Returns the microseconds since midnight of a timestamp. */
    public static long timeOf(long micros) {
        return Math.floorMod(micros, MICROS_PER_DAY);
    }

    /**
     * Returns a timestamp from a day and the microseconds since midnight.
     *
     * @throws ArithmeticException if it does not fit into 64 bits
     */
    public static long timestamp(long days, long time) {
        return Math.addExact(Math.multiplyExact(days, MICROS_PER_DAY), time);
    }

    /**
     * Returns the day of a year, month and day, as {@code make_date} builds
     * it.
     *
     * @throws ConversionException if there is no such day in the range of
     *         DuckDB
     */
    public static int makeDate(long year, long month, long day) {
        try {
            if (year == (int) year && month == (int) month && day == (int) day) {
                long days = LocalDate.of((int) year, (int) month, (int) day).toEpochDay();
                if (days >= MIN_DAYS && days <= MAX_DAYS) {
                    return (int) days;
                }
            }
        } catch (DateTimeException e) {
            // reported below
        }
        throw new ConversionException("Date out of range: " + year + "-" + month + "-" + day);
    }

    /**
     * Returns the microseconds since midnight of a time of day, as
     * {@code make_timestamp} builds it: the seconds may have a fraction,
     * which is rounded to microseconds, and may reach 60 as a leap second;
     * 24:00:00 is the midnight that follows.
     *
     * @throws ConversionException if the fields are out of range
     */
    public static long makeTime(long hour, long minute, double seconds) {
        // The fraction is rounded separately, so 59.9999999 seconds are 59 seconds and a million microseconds.
        long whole = (long) seconds;
        long micros = Math.round((seconds - whole) * MICROS_PER_SECOND);
        boolean valid;
        if (Double.isNaN(seconds)) {
            valid = false;
        } else if (hour < 0 || hour >= 24) {
            valid = hour == 24 && minute == 0 && whole == 0 && micros == 0;
        } else {
            valid = minute >= 0 && minute < 60 && whole >= 0 && whole <= 60 && micros >= 0 && micros <= MICROS_PER_SECOND;
        }
        if (!valid) {
            throw new ConversionException("Time out of range: " + hour + ":" + minute + ":" + seconds);
        }
        return hour * MICROS_PER_HOUR + minute * MICROS_PER_MINUTE + whole * MICROS_PER_SECOND + micros;
    }

    /**
     * Returns a part of a date or timestamp, given as its day and the
     * microseconds since midnight, as {@code date_part} extracts it: the
     * seconds include no fraction, the milliseconds and microseconds include
     * the seconds; the day of the week counts from Sunday = 0, the ISO day
     * of the week from Monday = 1; week and ISO year follow ISO 8601.
     *
     * @throws ConversionException for the parts that are no integers
     */
    public static long extract(Part part, int days, long time) {
        LocalDate date = LocalDate.ofEpochDay(days);
        int year = date.getYear();
        return switch (part) {
            case YEAR -> year;
            case MONTH -> date.getMonthValue();
            case DAY -> date.getDayOfMonth();
            case DECADE -> year / 10;
            case CENTURY -> year > 0 ? (year - 1) / 100 + 1 : year / 100 - 1;
            case MILLENNIUM -> year > 0 ? (year - 1) / 1000 + 1 : year / 1000 - 1;
            case MICROSECOND -> time % MICROS_PER_MINUTE;
            case MILLISECOND -> time % MICROS_PER_MINUTE / 1000;
            case SECOND -> time % MICROS_PER_MINUTE / MICROS_PER_SECOND;
            case MINUTE -> time % MICROS_PER_HOUR / MICROS_PER_MINUTE;
            case HOUR -> time / MICROS_PER_HOUR;
            case DOW -> date.getDayOfWeek().getValue() % 7;
            case ISODOW -> date.getDayOfWeek().getValue();
            case WEEK -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case DOY -> date.getDayOfYear();
            case QUARTER -> (date.getMonthValue() - 1) / 3 + 1;
            case YEARWEEK -> {
                long isoYear = date.get(IsoFields.WEEK_BASED_YEAR);
                int week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                yield isoYear > 0 ? isoYear * 100 + week : isoYear * 100 - week;
            }
            case ISOYEAR -> date.get(IsoFields.WEEK_BASED_YEAR);
            case ERA -> year > 0 ? 1 : 0;
            case TIMEZONE, TIMEZONE_HOUR, TIMEZONE_MINUTE -> 0;
            case EPOCH, JULIAN -> throw new ConversionException("Specifier type not implemented for DATEPART");
        };
    }

    /**
     * Truncates a date or timestamp, given as its day and the microseconds
     * since midnight, to a part, as {@code date_trunc} does: decades,
     * centuries and millennia truncate the year towards zero, a week starts
     * on Monday, an ISO year on the Monday of its first week; the parts that
     * name a day truncate to the day, {@code epoch} to the second.
     *
     * @return the truncated timestamp
     * @throws ConversionException for {@code era}
     * @throws ArithmeticException if the result is out of range
     */
    public static long trunc(Part part, int days, long time) {
        LocalDate date = LocalDate.ofEpochDay(days);
        int year = date.getYear();
        LocalDate day = switch (part) {
            case MILLENNIUM -> LocalDate.of(year / 1000 * 1000, 1, 1);
            case CENTURY -> LocalDate.of(year / 100 * 100, 1, 1);
            case DECADE -> LocalDate.of(year / 10 * 10, 1, 1);
            case YEAR -> LocalDate.of(year, 1, 1);
            case QUARTER -> LocalDate.of(year, (date.getMonthValue() - 1) / 3 * 3 + 1, 1);
            case MONTH -> date.withDayOfMonth(1);
            case WEEK, YEARWEEK -> date.with(ChronoField.DAY_OF_WEEK, 1);
            case ISOYEAR -> date.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(ChronoField.DAY_OF_WEEK, 1);
            case ERA, TIMEZONE, TIMEZONE_HOUR, TIMEZONE_MINUTE ->
                    throw new ConversionException("Specifier type not implemented for DATETRUNC");
            default -> date;
        };
        long truncated = switch (part) {
            case HOUR -> time - time % MICROS_PER_HOUR;
            case MINUTE -> time - time % MICROS_PER_MINUTE;
            case SECOND, EPOCH -> time - time % MICROS_PER_SECOND;
            case MILLISECOND -> time - time % 1000;
            case MICROSECOND -> time;
            default -> 0;
        };
        return timestamp(day.toEpochDay(), truncated);
    }

    /**
     * Counts the boundaries of a part between two dates or timestamps, each
     * given as its day and the microseconds since midnight, as
     * {@code date_diff} does: the years, months and quarters that begin in
     * between, the days, the whole weeks, and the hours, minutes, seconds
     * and milliseconds that begin in between; decades, centuries and
     * millennia divide the years towards zero.
     *
     * @throws ConversionException for {@code era}
     * @throws ArithmeticException if the microseconds overflow
     */
    public static long diff(Part part, int startDays, long startTime, int endDays, long endTime) {
        return switch (part) {
            case YEAR -> year(endDays) - year(startDays);
            case MONTH -> months(endDays) - months(startDays);
            case QUARTER -> months(endDays) / 3 - months(startDays) / 3;
            case DECADE -> year(endDays) / 10 - year(startDays) / 10;
            case CENTURY -> year(endDays) / 100 - year(startDays) / 100;
            case MILLENNIUM -> year(endDays) / 1000 - year(startDays) / 1000;
            case ISOYEAR -> isoYear(endDays) - isoYear(startDays);
            case DAY, DOW, ISODOW, DOY, JULIAN -> (long) endDays - startDays;
            case WEEK, YEARWEEK -> ((long) endDays - startDays) / 7;
            case HOUR -> Math.floorDiv(seconds(endDays, endTime), 3600) - Math.floorDiv(seconds(startDays, startTime), 3600);
            case MINUTE -> Math.floorDiv(seconds(endDays, endTime), 60) - Math.floorDiv(seconds(startDays, startTime), 60);
            case SECOND, EPOCH -> seconds(endDays, endTime) - seconds(startDays, startTime);
            case MILLISECOND -> (endDays * SECONDS_PER_DAY * 1000 + endTime / 1000)
                    - (startDays * SECONDS_PER_DAY * 1000 + startTime / 1000);
            case MICROSECOND -> Math.subtractExact(timestamp(endDays, endTime), timestamp(startDays, startTime));
            case ERA, TIMEZONE, TIMEZONE_HOUR, TIMEZONE_MINUTE ->
                    throw new ConversionException("Specifier type not implemented for DATEDIFF");
        };
    }

    /**
     * Checks that a timestamp is none of the two values with which DuckDB
     * stands for infinity, nor below them.
     *
     * @throws ArithmeticException otherwise
     */
    public static long checkTimestamp(long micros) {
        if (micros >= Long.MAX_VALUE || micros <= -Long.MAX_VALUE) {
            throw new ArithmeticException("Timestamp out of range");
        }
        return micros;
    }

    /**
     * Adds months, then days to the date of a timestamp and keeps its time
     * of day; a day of the month beyond the end of the new month becomes its
     * last day.
     *
     * @throws ArithmeticException if the date leaves the range of DuckDB
     */
    public static long addMonthsAndDays(long micros, int months, int days) {
        try {
            LocalDate date = LocalDate.ofEpochDay(dayOf(micros)).plusMonths(months).plusDays(days);
            return timestamp(checkDays(date.toEpochDay()), timeOf(micros));
        } catch (DateTimeException e) {
            throw new ArithmeticException("Date out of range");
        }
    }

    /**
     * Adds an interval to a timestamp: the months and days as
     * {@link #addMonthsAndDays} adds them, then the microseconds.
     *
     * @throws ArithmeticException if the result is out of range
     */
    public static long add(long micros, Interval interval) {
        long shifted = interval.months() == 0 && interval.days() == 0 ? micros
                : addMonthsAndDays(micros, interval.months(), interval.days());
        return checkTimestamp(Math.addExact(shifted, interval.micros()));
    }

    /**
     * Subtracts one timestamp from another: the whole days, rounded towards
     * zero, and the microseconds that remain, with the same sign.
     *
     * @throws ArithmeticException if the difference does not fit 64 bits
     */
    public static Interval subtract(long a, long b) {
        long difference = Math.subtractExact(a, b);
        return Interval.of(0, Math.toIntExact(difference / MICROS_PER_DAY), difference % MICROS_PER_DAY);
    }

    /** Adds microseconds to a time of day around the clock, as {@code TIME + INTERVAL} does. */
    public static long addToTime(long time, long micros) {
        return Math.floorMod(time % MICROS_PER_DAY + micros % MICROS_PER_DAY, MICROS_PER_DAY);
    }

    /**
     * Returns a part of a time of day, as {@code date_part} extracts it:
     * the hours, minutes, seconds, milliseconds and microseconds, and zero
     * for the parts of the time zone.
     *
     * @throws ConversionException for the parts of a date
     */
    public static long extractTime(Part part, long time) {
        return switch (part) {
            case HOUR, MINUTE, SECOND, MILLISECOND, MICROSECOND -> extract(part, 0, time);
            case TIMEZONE, TIMEZONE_HOUR, TIMEZONE_MINUTE -> 0;
            default -> throw new ConversionException("\"time\" units \"" + part.name().toLowerCase(Locale.ROOT)
                    + "\" not recognized");
        };
    }

    /**
     * Returns a part of an interval, as {@code date_part} extracts it: the
     * years and the months beyond them, the days, the hours of the
     * microseconds and the minutes, seconds, milliseconds and microseconds
     * beyond the hours and minutes, each with the sign of its component.
     *
     * @throws ConversionException for the parts that intervals do not have
     */
    public static long extract(Part part, Interval interval) {
        long months = interval.months();
        long micros = interval.micros();
        return switch (part) {
            case YEAR -> months / 12;
            case MONTH -> months % 12;
            case DAY -> interval.days();
            case DECADE -> months / 120;
            case CENTURY -> months / 1_200;
            case MILLENNIUM -> months / 12_000;
            case QUARTER -> months % 12 / 3 + 1;
            case HOUR -> micros / MICROS_PER_HOUR;
            case MINUTE -> micros % MICROS_PER_HOUR / MICROS_PER_MINUTE;
            case SECOND -> micros % MICROS_PER_MINUTE / MICROS_PER_SECOND;
            case MILLISECOND -> micros % MICROS_PER_MINUTE / 1000;
            case MICROSECOND -> micros % MICROS_PER_MINUTE;
            default -> throw new ConversionException("interval units \"" + part.name().toLowerCase(Locale.ROOT)
                    + "\" not recognized");
        };
    }

    /**
     * Truncates an interval to a part, as {@code date_trunc} does: the
     * months to whole millennia, centuries, decades, years or quarters, the
     * days to whole weeks, the microseconds to whole hours, minutes, seconds
     * or milliseconds; the smaller components become zero. As in DuckDB, an
     * ISO year is a year, a week of the year a week, a day of the week or
     * the year a day, and the epoch a second.
     *
     * @throws ConversionException for the parts that intervals do not have
     */
    public static Interval trunc(Part part, Interval interval) {
        int months = interval.months();
        int days = interval.days();
        long micros = interval.micros();
        return switch (part) {
            case MILLENNIUM -> Interval.ofMonths(months / 12_000 * 12_000);
            case CENTURY -> Interval.ofMonths(months / 1_200 * 1_200);
            case DECADE -> Interval.ofMonths(months / 120 * 120);
            case YEAR, ISOYEAR -> Interval.ofMonths(months / 12 * 12);
            case QUARTER -> Interval.ofMonths(months / 3 * 3);
            case MONTH -> Interval.ofMonths(months);
            case WEEK, YEARWEEK -> Interval.of(months, days / 7 * 7, 0);
            case DAY, DOW, ISODOW, DOY -> Interval.of(months, days, 0);
            case HOUR -> Interval.of(months, days, micros / MICROS_PER_HOUR * MICROS_PER_HOUR);
            case MINUTE -> Interval.of(months, days, micros / MICROS_PER_MINUTE * MICROS_PER_MINUTE);
            case SECOND, EPOCH -> Interval.of(months, days, micros / MICROS_PER_SECOND * MICROS_PER_SECOND);
            case MILLISECOND -> Interval.of(months, days, micros / 1000 * 1000);
            case MICROSECOND -> interval;
            default -> throw new ConversionException("interval units \"" + part.name().toLowerCase(Locale.ROOT)
                    + "\" not recognized");
        };
    }

    /**
     * Returns the {@code age} of one timestamp at another, as DuckDB and
     * PostgreSQL compute it: the differences of the years, months, days and
     * the time of day, each negative one borrowed from the next larger field,
     * a day from the number of days of the month of the later timestamp;
     * computed for the earlier timestamp first and negated if so.
     */
    public static Interval age(long a, long b) {
        LocalDate dateA = LocalDate.ofEpochDay(dayOf(a));
        LocalDate dateB = LocalDate.ofEpochDay(dayOf(b));
        long timeA = timeOf(a);
        long timeB = timeOf(b);
        long years = dateA.getYear() - dateB.getYear();
        long months = dateA.getMonthValue() - dateB.getMonthValue();
        long days = dateA.getDayOfMonth() - dateB.getDayOfMonth();
        long hours = timeA / MICROS_PER_HOUR - timeB / MICROS_PER_HOUR;
        long minutes = timeA % MICROS_PER_HOUR / MICROS_PER_MINUTE - timeB % MICROS_PER_HOUR / MICROS_PER_MINUTE;
        long seconds = timeA % MICROS_PER_MINUTE / MICROS_PER_SECOND - timeB % MICROS_PER_MINUTE / MICROS_PER_SECOND;
        long micros = timeA % MICROS_PER_SECOND - timeB % MICROS_PER_SECOND;
        boolean flipped = a < b;
        if (flipped) {
            years = -years;
            months = -months;
            days = -days;
            hours = -hours;
            minutes = -minutes;
            seconds = -seconds;
            micros = -micros;
        }
        while (micros < 0) {
            micros += MICROS_PER_SECOND;
            seconds--;
        }
        while (seconds < 0) {
            seconds += 60;
            minutes--;
        }
        while (minutes < 0) {
            minutes += 60;
            hours--;
        }
        while (hours < 0) {
            hours += 24;
            days--;
        }
        LocalDate borrowFrom = flipped ? dateA : dateB;
        while (days < 0) {
            days += borrowFrom.lengthOfMonth();
            months--;
        }
        while (months < 0) {
            months += 12;
            years--;
        }
        long time = hours * MICROS_PER_HOUR + minutes * MICROS_PER_MINUTE + seconds * MICROS_PER_SECOND + micros;
        long totalMonths = years * 12 + months;
        if (flipped) {
            totalMonths = -totalMonths;
            days = -days;
            time = -time;
        }
        return Interval.of(Math.toIntExact(totalMonths), Math.toIntExact(days), time);
    }

    private static long year(int days) {
        return LocalDate.ofEpochDay(days).getYear();
    }

    /** The months since the start of year 0, negative before it. */
    private static long months(int days) {
        LocalDate date = LocalDate.ofEpochDay(days);
        return date.getYear() * 12L + date.getMonthValue() - 1;
    }

    private static long isoYear(int days) {
        return LocalDate.ofEpochDay(days).get(IsoFields.WEEK_BASED_YEAR);
    }

    /** The whole seconds since 1970-01-01 00:00:00, rounded down. */
    private static long seconds(int days, long time) {
        return days * SECONDS_PER_DAY + time / MICROS_PER_SECOND;
    }
}
