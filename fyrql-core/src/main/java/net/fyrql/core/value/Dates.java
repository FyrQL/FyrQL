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

package net.fyrql.core.value;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The calendar arithmetic of {@code DATE} and {@code TIMESTAMP}, measured
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
        JULIAN("julian");

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
            case ERA -> throw new ConversionException("Specifier type not implemented for DATETRUNC");
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
            case ERA -> throw new ConversionException("Specifier type not implemented for DATEDIFF");
        };
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
