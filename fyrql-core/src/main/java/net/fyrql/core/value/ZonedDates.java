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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The calendar of {@code TIMESTAMP WITH TIME ZONE} in a time zone, as DuckDB
 * computes it with ICU: a value is an instant, the microseconds since
 * 1970-01-01 00:00:00 UTC, and its date and time of day are those of the
 * time zone. A local date and time becomes an instant with the offset that
 * is valid then; a local time in the gap of a change to summer time is moved
 * forward by the length of the gap, and a local time that occurs twice when
 * summer time ends takes the later of its two instants.
 * <p>
 * Months, days and the parts of a date count in the local calendar,
 * hours and smaller units on the instant.
 */
public final class ZonedDates {

    /** The time zones by their names in lower case, with the names of {@link ZoneId#SHORT_IDS}. */
    private static final Map<String, String> ZONES = new HashMap<>();

    static {
        for (String id : ZoneId.getAvailableZoneIds()) {
            ZONES.put(id.toLowerCase(Locale.ROOT), id);
        }
        for (String id : ZoneId.SHORT_IDS.keySet()) {
            ZONES.putIfAbsent(id.toLowerCase(Locale.ROOT), id);
        }
        ZONES.put("utc", "UTC");
        ZONES.put("gmt", "GMT");
    }

    private ZonedDates() {
    }

    /**
     * Returns the time zone of a name as DuckDB accepts it: a zone of the
     * time zone database in any case, or an abbreviation of
     * {@link ZoneId#SHORT_IDS}; offsets such as {@code +02:00} are no names.
     *
     * @throws ConversionException if there is no such time zone
     */
    public static ZoneId zone(String name) {
        String id = ZONES.get(name.toLowerCase(Locale.ROOT));
        if (id == null) {
            throw new ConversionException("Unknown TimeZone '" + name + "'!");
        }
        return ZoneId.of(id, ZoneId.SHORT_IDS);
    }

    /** Returns the name of a time zone as {@code current_setting('TimeZone')} shows it. */
    public static String name(ZoneId zone) {
        return zone.getId();
    }

    /** Returns the offset of a time zone at an instant, in seconds. */
    public static int offsetSeconds(long micros, ZoneId zone) {
        if (zone instanceof ZoneOffset o) {
            return o.getTotalSeconds();
        }
        return zone.getRules().getOffset(instant(micros)).getTotalSeconds();
    }

    private static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, Dates.MICROS_PER_SECOND));
    }

    /**
     * Returns the local date and time of an instant in a time zone, as the
     * microseconds of a {@code TIMESTAMP}.
     *
     * @throws ArithmeticException if it does not fit 64 bits
     */
    public static long toLocal(long micros, ZoneId zone) {
        return Math.addExact(micros, offsetSeconds(micros, zone) * Dates.MICROS_PER_SECOND);
    }

    /**
     * Returns the instant of a local date and time in a time zone, given as
     * the microseconds of a {@code TIMESTAMP}: in a gap, the local time moved
     * forward by the length of the gap; in an overlap, the later instant.
     *
     * @throws ArithmeticException if it does not fit 64 bits
     */
    public static long toInstant(long local, ZoneId zone) {
        if (zone instanceof ZoneOffset o) {
            return Math.subtractExact(local, o.getTotalSeconds() * Dates.MICROS_PER_SECOND);
        }
        ZoneRules rules = zone.getRules();
        if (rules.isFixedOffset()) {
            int offset = rules.getOffset(Instant.EPOCH).getTotalSeconds();
            return Math.subtractExact(local, offset * Dates.MICROS_PER_SECOND);
        }
        LocalDateTime ldt;
        try {
            ldt = Conversions.toLocalDateTime(local);
        } catch (DateTimeException e) {
            throw new ArithmeticException("Timestamp out of range: " + local);
        }
        List<ZoneOffset> valid = rules.getValidOffsets(ldt);
        int offset;
        if (valid.size() == 1) {
            offset = valid.get(0).getTotalSeconds();
        } else if (valid.size() > 1) {
            offset = valid.get(valid.size() - 1).getTotalSeconds();
        } else {
            ZoneOffsetTransition gap = rules.getTransition(ldt);
            offset = gap.getOffsetBefore().getTotalSeconds();
        }
        return Math.subtractExact(local, offset * Dates.MICROS_PER_SECOND);
    }

    /** Returns the day of the local date of an instant. */
    public static int dayOf(long micros, ZoneId zone) {
        return Dates.dayOf(toLocal(micros, zone));
    }

    /** Returns the instant of the local midnight that starts a day. */
    public static long startOfDay(int days, ZoneId zone) {
        return toInstant(Dates.timestamp(days, 0), zone);
    }

    /**
     * Adds an interval to an instant as DuckDB does, in three steps: first
     * the microseconds to the instant, then the months to the local date,
     * keeping the local time of day, a day of the month beyond the end of the
     * new month becoming its last day, then the days, keeping the local time
     * of day (see {@link #addDays}).
     *
     * @throws ArithmeticException if the result is out of range
     */
    public static long add(long micros, Interval interval, ZoneId zone) {
        long result = Math.addExact(micros, interval.micros());
        if (interval.months() != 0) {
            result = toInstant(Dates.addMonthsAndDays(toLocal(result, zone), interval.months(), 0), zone);
        }
        if (interval.days() != 0) {
            result = addDays(result, interval.days(), zone);
        }
        return Dates.checkTimestamp(result);
    }

    /**
     * Adds days to an instant as DuckDB does: whole days of 24 hours, then,
     * if the local time of day changed with the offset, the change of the
     * offset, unless that misses the local time of day again and moves
     * backward (a gap); so a time in an overlap keeps the offset it came from.
     */
    private static long addDays(long micros, int days, ZoneId zone) {
        long time = Dates.timeOf(toLocal(micros, zone));
        int offset = offsetSeconds(micros, zone);
        long result = Math.addExact(micros, Math.multiplyExact((long) days, Dates.MICROS_PER_DAY));
        int newOffset = offsetSeconds(result, zone);
        if (newOffset == offset || Dates.timeOf(toLocal(result, zone)) == time) {
            return result;
        }
        long shift = (offset - newOffset) % 86_400 * Dates.MICROS_PER_SECOND;
        long adjusted = Math.addExact(result, shift);
        if (Dates.timeOf(toLocal(adjusted, zone)) != time && shift < 0) {
            return result;
        }
        return adjusted;
    }

    /**
     * Subtracts one instant from another as DuckDB does in a time zone: the
     * whole local days from the second to the first, such that adding them
     * to the second does not pass the first, and the microseconds that
     * remain; never months.
     */
    public static Interval subtract(long a, long b, ZoneId zone) {
        if (a < b) {
            return subtract(b, a, zone).negate();
        }
        long localA = toLocal(a, zone);
        long localB = toLocal(b, zone);
        long days = (long) Dates.dayOf(localA) - Dates.dayOf(localB);
        long start = days == 0 ? b
                : toInstant(Math.addExact(localB, Math.multiplyExact(days, Dates.MICROS_PER_DAY)), zone);
        while (days > 0 && start > a) {
            days--;
            start = toInstant(Math.addExact(localB, days * Dates.MICROS_PER_DAY), zone);
        }
        return Interval.of(0, Math.toIntExact(days), Math.subtractExact(a, start));
    }

    /**
     * Truncates an instant to a part in the local calendar, as
     * {@code date_trunc} does.
     *
     * @throws ConversionException for {@code era}
     */
    public static long trunc(Dates.Part part, long micros, ZoneId zone) {
        if (part == Dates.Part.ERA) {
            // As DuckDB truncates an instant to its era: the first day of the year 0, 1 BC.
            return toInstant(Dates.timestamp((int) LocalDate.of(0, 1, 1).toEpochDay(), 0), zone);
        }
        long local = toLocal(micros, zone);
        long truncated = Dates.trunc(part, Dates.dayOf(local), Dates.timeOf(local));
        if (unit(part) != 0 && part != Dates.Part.HOUR) {
            // Shorter than an hour: the instant keeps its offset, as in DuckDB.
            return Math.subtractExact(micros, Math.subtractExact(local, truncated));
        }
        return toInstant(truncated, zone);
    }

    /** Returns the microseconds of an hour or a shorter part, or 0 for the others. */
    private static long unit(Dates.Part part) {
        return switch (part) {
            case HOUR -> Dates.MICROS_PER_HOUR;
            case MINUTE -> Dates.MICROS_PER_MINUTE;
            case SECOND -> Dates.MICROS_PER_SECOND;
            case MILLISECOND -> 1000;
            case MICROSECOND -> 1;
            default -> 0;
        };
    }

    /**
     * Extracts a part of an instant in the local calendar, as
     * {@code date_part} does; {@code timezone} is the offset in seconds,
     * {@code timezone_hour} and {@code timezone_minute} its hours and the
     * minutes beyond them, with its sign.
     *
     * @throws ConversionException for the parts that are no integers
     */
    public static long extract(Dates.Part part, long micros, ZoneId zone) {
        int offset = offsetSeconds(micros, zone);
        return switch (part) {
            case TIMEZONE -> offset;
            case TIMEZONE_HOUR -> offset / 3600;
            case TIMEZONE_MINUTE -> offset / 60 % 60;
            default -> {
                long local = Math.addExact(micros, offset * Dates.MICROS_PER_SECOND);
                yield Dates.extract(part, Dates.dayOf(local), Dates.timeOf(local));
            }
        };
    }

    /**
     * Counts the boundaries of a part between two instants, as
     * {@code date_diff} does: days and longer parts in the local calendar,
     * hours and shorter ones as the whole parts between the instants
     * truncated to the part (see {@link #trunc}).
     */
    public static long diff(Dates.Part part, long start, long end, ZoneId zone) {
        long unit = unit(part);
        if (unit != 0) {
            return Math.subtractExact(trunc(part, end, zone), trunc(part, start, zone)) / unit;
        }
        switch (part) {
            case EPOCH -> {
                return Dates.diff(part, Dates.dayOf(start), Dates.timeOf(start), Dates.dayOf(end), Dates.timeOf(end));
            }
            case YEARWEEK -> {
                // The Mondays passed, as DuckDB counts the weeks of the year of instants.
                long s = Dates.trunc(Dates.Part.WEEK, Dates.dayOf(toLocal(start, zone)), 0);
                long e = Dates.trunc(Dates.Part.WEEK, Dates.dayOf(toLocal(end, zone)), 0);
                return Math.floorDiv(e - s, Dates.MICROS_PER_DAY) / 7;
            }
            case ERA -> {
                long s = toLocal(start, zone);
                long e = toLocal(end, zone);
                return Dates.extract(Dates.Part.ERA, Dates.dayOf(e), Dates.timeOf(e))
                        - Dates.extract(Dates.Part.ERA, Dates.dayOf(s), Dates.timeOf(s));
            }
            default -> {
                long s = toLocal(start, zone);
                long e = toLocal(end, zone);
                return Dates.diff(part, Dates.dayOf(s), Dates.timeOf(s), Dates.dayOf(e), Dates.timeOf(e));
            }
        }
    }

    /** Returns the {@code age} of two instants on their local dates and times, see {@link Dates#age}. */
    public static Interval age(long a, long b, ZoneId zone) {
        return Dates.age(toLocal(a, zone), toLocal(b, zone));
    }

    /** Returns the local date of an instant. */
    public static LocalDate localDate(long micros, ZoneId zone) {
        return LocalDate.ofEpochDay(dayOf(micros, zone));
    }
}
