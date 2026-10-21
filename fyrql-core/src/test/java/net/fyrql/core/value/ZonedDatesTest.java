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
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Times of day, timestamps with time zone and the calendar in a time zone,
 * with the values and texts that DuckDB 1.5.6 gives, mostly in
 * Europe/Berlin and around its changes to and from summer time in 2024.
 */
class ZonedDatesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static long tz(String s) {
        return Conversions.parseTimestampTz(s, BERLIN);
    }

    private static String text(long micros) {
        return Conversions.formatTimestampTz(micros, BERLIN);
    }

    @Test
    void timesOfDay() {
        assertEquals("12:00:00.000001", Conversions.formatTime(Conversions.parseTime("12:00:00.000001")));
        assertEquals("24:00:00", Conversions.formatTime(Conversions.parseTime("24:00:00")));
        assertEquals("12:00:00.12", Conversions.formatTime(Conversions.parseTime("12:00:00.120")));
        assertEquals("01:02:03", Conversions.formatTime(Conversions.parseTime("1:2:3")));
        assertEquals("12:00:00", Conversions.formatTime(Conversions.parseTime("12:00")));
        assertEquals("12:00:00", Conversions.formatTime(Conversions.parseTime(" 12:00:00 ")));
        assertEquals("12:00:00", Conversions.formatTime(Conversions.parseTime("12:00:00Z")));
        assertEquals("12:00:00", Conversions.formatTime(Conversions.parseTime("12:00:00+02")));
        assertEquals("12:00:00.123", Conversions.formatTime(Conversions.parseTime("12:00:00.123+02:00")));
        assertEquals("12:00:00", Conversions.formatTime(Conversions.parseTime("2024-07-01 12:00:00")));
        assertEquals("12:00:00.123456", Conversions.formatTime(Conversions.parseTime("12:00:00.1234567")));
        for (String bad : List.of("12", "24:00:01", "12:00:60", "12:60:00", "1 day", "")) {
            assertEquals("time field value out of range: \"" + bad
                    + "\", expected format is ([YYYY-MM-DD ]HH:MM:SS[.MS])",
                    assertThrows(ConversionException.class, () -> Conversions.parseTime(bad)).getMessage(), bad);
        }
    }

    @Test
    void textOfTimestampsWithTimeZone() {
        assertEquals("2024-07-01 14:00:00+02", text(tz("2024-07-01 12:00:00Z")));
        assertEquals("2024-07-01 12:00:00+02", text(tz("2024-07-01 12:00:00")));
        assertEquals("2024-07-01 08:30:00+02", text(tz("2024-07-01 12:00:00+05:30")));
        assertEquals("2024-07-01 12:30:00.123456+02", text(tz("2024-07-01T12:00:00.123456+0130")));
        assertEquals("2024-07-01 14:30:15+02", text(tz("2024-07-01 12:00:00-00:30:15")));
        assertEquals("2024-07-01 18:00:00+02", text(tz("2024-07-01 12:00:00 America/New_York")));
        assertEquals("2024-07-01 14:00:00+02", text(tz("2024-07-01 12:00:00 UTC")));
        assertEquals("2024-07-01 14:00:00+02", text(tz("2024-07-01 12:00:00 utc")));
        assertEquals("2024-07-01 12:00:00+02", text(tz("2024-07-01 12:00:00 CET")));
        assertEquals("2024-07-01 21:00:00+02", text(tz("2024-07-01 12:00:00 PST")));
        assertEquals("2024-07-01 19:00:00+02", text(tz("2024-07-01 12:00:00 EST")));
        assertEquals("2024-07-01 12:00:00+02", text(tz("2024-07-01 12:00")));
        assertEquals("2024-07-01 00:00:00+02", text(tz("2024-07-01")));
        assertEquals("2024-07-02 00:00:00+02", text(tz("2024-07-01 24:00:00")));
        assertEquals("1970-01-01 01:00:00+01", text(tz("epoch")));
        assertEquals("2024-07-01 14:00:00.5+02", text(tz("2024-07-01 12:00:00.5+00")));
        assertEquals("0001-01-01 00:53:28+00:53", text(tz("0001-01-01 00:00:00+00")));
        assertEquals("1893-04-01 01:00:00+01", text(tz("1893-04-01 00:00:00+00")));
        assertEquals("2024-07-01 09:30:00-02:30",
                Conversions.formatTimestampTz(tz("2024-07-01 12:00:00+00"), ZoneId.of("America/St_Johns")));
        assertEquals("2024-07-01 17:30:00+05:30",
                Conversions.formatTimestampTz(tz("2024-07-01 12:00:00+00"), ZoneId.of("Asia/Kolkata")));
        assertEquals("Unknown TimeZone 'Foo/Bar'!",
                assertThrows(ConversionException.class, () -> tz("2024-07-01 12:00:00 Foo/Bar")).getMessage());
        assertThrows(ConversionException.class, () -> tz("2024-07-01 12:00:00 Z"));
        assertThrows(ConversionException.class, () -> tz("2024-07-01 12:00:00 +02:00"));
        assertEquals("invalid timestamp field format: \"2024-07-01 12:00:00+2\", expected format is"
                + " (YYYY-MM-DD HH:MM:SS[.US][\u00B1HH[:MM[:SS]]| ZONE])",
                assertThrows(ConversionException.class, () -> tz("2024-07-01 12:00:00+2")).getMessage());
    }

    @Test
    void gapsAndOverlaps() {
        assertEquals("2024-03-31 03:30:00+02", text(tz("2024-03-31 02:30:00")));
        assertEquals("2024-10-27 02:30:00+01", text(tz("2024-10-27 02:30:00")));
        long local = Conversions.parseTimestamp("2024-10-27 02:30:00");
        assertEquals(Conversions.parseTimestamp("2024-10-27 01:30:00"), ZonedDates.toInstant(local, BERLIN));
        assertEquals(local, ZonedDates.toLocal(ZonedDates.toInstant(local, BERLIN), BERLIN));
    }

    @Test
    void calendarInTheTimeZone() {
        Interval day = Interval.ofDays(1);
        assertEquals("2024-03-30 12:00:00+01",
                text(ZonedDates.add(tz("2024-03-31 12:00:00+02"), day.negate(), BERLIN)));
        assertEquals("2024-03-31 13:00:00+02",
                text(ZonedDates.add(tz("2024-03-30 12:00:00+01"), Interval.parse("24 hours"), BERLIN)));
        assertEquals("2024-02-29 12:00:00+01",
                text(ZonedDates.add(tz("2024-01-31 12:00:00+01"), Interval.ofMonths(1), BERLIN)));
        assertEquals("2024-03-31 03:30:00+02", text(ZonedDates.add(tz("2024-03-30 02:30:00+01"), day, BERLIN)));
        // Into an overlap: a day keeps the offset it came from, a month takes the later one.
        assertEquals("2024-10-27 02:30:00+02", text(ZonedDates.add(tz("2024-10-26 02:30:00+02"), day, BERLIN)));
        assertEquals("2024-10-27 02:30:00+01",
                text(ZonedDates.add(tz("2024-09-27 02:30:00+02"), Interval.ofMonths(1), BERLIN)));
        // The microseconds come first, then the months, then the days.
        assertEquals("2024-03-31 11:00:00+02",
                text(ZonedDates.add(tz("2024-03-30 12:00:00+01"), Interval.parse("1 day -1 hour"), BERLIN)));
        assertEquals("2024-10-26 02:30:00+02",
                text(ZonedDates.add(tz("2024-10-27 02:30:00+02"), Interval.parse("-1 day 1 hour"), BERLIN)));
        assertEquals("2024-03-01 13:00:00+01",
                text(ZonedDates.add(tz("2024-01-31 13:00:00+01"), Interval.parse("1 month 1 day"), BERLIN)));
        assertEquals("2024-07-30 14:00:00+02",
                text(ZonedDates.add(tz("2024-07-01 14:00:00+02"), Interval.parse("1 month -2 days"), BERLIN)));
        assertEquals("2024-11-28 01:30:00+01",
                text(ZonedDates.add(tz("2024-10-27 02:30:00+02"), Interval.parse("1 month 1 day -1 hour"), BERLIN)));
        assertEquals("2024-03-30 03:30:00+01",
                text(ZonedDates.add(tz("2024-03-31 03:30:00+02"), day.negate(), BERLIN)));
        assertEquals("2024-10-27 02:30:00+01",
                text(ZonedDates.add(tz("2024-10-27 02:30:00+02"), Interval.parse("1 hour"), BERLIN)));
        assertEquals("1 day", ZonedDates.subtract(tz("2024-03-31 12:00:00+02"), tz("2024-03-30 12:00:00+01"), BERLIN)
                .toString());
        assertEquals("22:00:00", ZonedDates.subtract(tz("2024-03-31 11:00:00+02"), tz("2024-03-30 12:00:00+01"),
                BERLIN).toString());
        assertEquals("31 days", ZonedDates.subtract(tz("2024-04-30 12:00:00+02"), tz("2024-03-30 12:00:00+01"),
                BERLIN).toString());
        assertEquals("-1 day", ZonedDates.subtract(tz("2024-03-30 12:00:00+01"), tz("2024-03-31 12:00:00+02"),
                BERLIN).toString());
        assertEquals("1 day", ZonedDates.subtract(tz("2024-10-28 00:00:00+01"), tz("2024-10-27 00:00:00+02"),
                BERLIN).toString());
        assertEquals("04:00:00", ZonedDates.subtract(tz("2024-03-31 04:00:00+02"), tz("2024-03-30 23:00:00+01"),
                BERLIN).toString());
        assertEquals("2024-03-31 00:00:00+01",
                text(ZonedDates.trunc(Dates.Part.DAY, tz("2024-03-31 12:00:00+02"), BERLIN)));
        assertEquals("2024-10-27 02:00:00+01",
                text(ZonedDates.trunc(Dates.Part.HOUR, tz("2024-10-27 02:30:00+02"), BERLIN)));
        assertEquals("2024-10-27 02:30:00+02",
                text(ZonedDates.trunc(Dates.Part.MINUTE, tz("2024-10-27 02:30:15+02"), BERLIN)));
        assertEquals("2024-07-01 00:00:00+02",
                text(ZonedDates.trunc(Dates.Part.WEEK, tz("2024-07-03 12:00:00+00"), BERLIN)));
        assertEquals(2025L, ZonedDates.extract(Dates.Part.YEAR, tz("2024-12-31 23:30:00Z"), BERLIN));
        assertEquals(7200L, ZonedDates.extract(Dates.Part.TIMEZONE, tz("2024-07-01 12:00:00Z"), BERLIN));
        assertEquals(1L, ZonedDates.extract(Dates.Part.TIMEZONE_HOUR, tz("2024-01-01 12:00:00Z"), BERLIN));
        ZoneId newfoundland = ZoneId.of("America/St_Johns");
        assertEquals(-9000L, ZonedDates.extract(Dates.Part.TIMEZONE, tz("2024-07-01 12:00:00Z"), newfoundland));
        assertEquals(-2L, ZonedDates.extract(Dates.Part.TIMEZONE_HOUR, tz("2024-07-01 12:00:00Z"), newfoundland));
        assertEquals(-30L, ZonedDates.extract(Dates.Part.TIMEZONE_MINUTE, tz("2024-07-01 12:00:00Z"), newfoundland));
        assertEquals(1L, ZonedDates.diff(Dates.Part.DAY, tz("2024-03-30 12:00:00+01"), tz("2024-03-31 11:00:00+02"),
                BERLIN));
        assertEquals(23L, ZonedDates.diff(Dates.Part.HOUR, tz("2024-03-30 12:00:00+01"), tz("2024-03-31 12:00:00+02"),
                BERLIN));
        long overlap = tz("2024-10-27 00:30:15.123456Z");
        long later = tz("2024-10-27 03:00:00Z");
        assertEquals(2L, ZonedDates.diff(Dates.Part.HOUR, overlap, later, BERLIN));
        assertEquals(-2L, ZonedDates.diff(Dates.Part.HOUR, later, overlap, BERLIN));
        assertEquals(150L, ZonedDates.diff(Dates.Part.MINUTE, overlap, later, BERLIN));
        assertEquals(8985L, ZonedDates.diff(Dates.Part.SECOND, overlap, later, BERLIN));
        assertEquals(8984877L, ZonedDates.diff(Dates.Part.MILLISECOND, overlap, later, BERLIN));
        assertEquals(8984876544L, ZonedDates.diff(Dates.Part.MICROSECOND, overlap, later, BERLIN));
    }

    @Test
    void ageAsPostgreSql() {
        assertEquals("2 months", Dates.age(Conversions.parseTimestamp("2024-03-01"),
                Conversions.parseTimestamp("2024-01-01")).toString());
        assertEquals("-1 month -1 day -02:30:00", Dates.age(Conversions.parseTimestamp("2024-01-01 00:00:00"),
                Conversions.parseTimestamp("2024-02-02 02:30:00")).toString());
        assertEquals("1 month", Dates.age(Conversions.parseTimestamp("2024-01-31"),
                Conversions.parseTimestamp("2023-12-31")).toString());
        assertEquals("1 month 2 days", Dates.age(Conversions.parseTimestamp("2024-03-31"),
                Conversions.parseTimestamp("2024-02-29")).toString());
    }

    @Test
    void castsInTheTimeZone() {
        long instant = tz("2024-07-01 12:00:00Z");
        assertEquals("2024-07-01 14:00:00", Casts.format(Casts.cast(instant, DataType.TIMESTAMPTZ,
                DataType.TIMESTAMP, BERLIN), DataType.TIMESTAMP));
        assertEquals("2024-07-01 12:00:00+02", Casts.cast(Casts.cast(Conversions.parseTimestamp("2024-07-01 12:00:00"),
                DataType.TIMESTAMP, DataType.TIMESTAMPTZ, BERLIN), DataType.TIMESTAMPTZ, DataType.VARCHAR, BERLIN));
        assertEquals("2024-07-02", Casts.format(Casts.cast(tz("2024-07-01 23:30:00+00"), DataType.TIMESTAMPTZ,
                DataType.DATE, BERLIN), DataType.DATE));
        assertEquals("2024-07-01 00:00:00+02", Casts.format(Casts.cast(Conversions.parseDate("2024-07-01"),
                DataType.DATE, DataType.TIMESTAMPTZ, BERLIN), DataType.TIMESTAMPTZ, BERLIN));
        long gap = Conversions.parseTimestamp("2024-03-31 02:30:00");
        assertEquals("2024-03-31 03:30:00+02", Casts.format(Casts.cast(gap, DataType.TIMESTAMP, DataType.TIMESTAMPTZ,
                BERLIN), DataType.TIMESTAMPTZ, BERLIN));
        assertEquals("12:34:56", Casts.format(Casts.cast(Conversions.parseTimestamp("2024-07-01 12:34:56"),
                DataType.TIMESTAMP, DataType.TIME), DataType.TIME));
        assertEquals("2024-07-01 12:00:00+00", Casts.format(instant, DataType.TIMESTAMPTZ));
        assertEquals("1 day", Casts.cast("1 day", DataType.VARCHAR, DataType.INTERVAL).toString());
        assertEquals(null, Casts.tryCast("x", DataType.VARCHAR, DataType.INTERVAL));
        assertEquals("['2024-07-01 14:00:00+02']", Casts.format(List.of(instant),
                DataType.listOf(DataType.TIMESTAMPTZ), BERLIN));
        for (DataType[] pair : new DataType[][] {{DataType.TIME, DataType.TIMESTAMP},
            {DataType.TIMESTAMPTZ, DataType.TIME}, {DataType.INTERVAL, DataType.DOUBLE},
            {DataType.TIMESTAMPTZ, DataType.BIGINT}, {DataType.INTEGER, DataType.INTERVAL},
            {DataType.TIME, DataType.INTERVAL}, {DataType.INTERVAL, DataType.TIME}}) {
            assertFalse(Casts.allowed(pair[0], pair[1]), pair[0] + " to " + pair[1]);
        }
        assertTrue(Casts.needsZone(DataType.TIMESTAMP, DataType.TIMESTAMPTZ));
        assertTrue(Casts.needsZone(DataType.TIMESTAMPTZ, DataType.VARCHAR));
        assertTrue(Casts.needsZone(DataType.listOf(DataType.TIMESTAMPTZ), DataType.VARCHAR));
        assertFalse(Casts.needsZone(DataType.TIMESTAMP, DataType.VARCHAR));
        assertFalse(Casts.needsZone(DataType.TIME, DataType.VARCHAR));
        assertEquals(ZoneOffset.UTC, new net.fyrql.core.expr.Expression.Cast(
                net.fyrql.core.expr.Expressions.lit(DataType.TIME, 0L), DataType.VARCHAR).zoneOrUtc());
    }

    @Test
    void zoneNames() {
        assertEquals("UTC", ZonedDates.name(ZonedDates.zone("utc")));
        assertEquals("Europe/Berlin", ZonedDates.name(ZonedDates.zone("europe/berlin")));
        assertEquals("CET", ZonedDates.name(ZonedDates.zone("CET")));
        assertThrows(ConversionException.class, () -> ZonedDates.zone("+02:00"));
        assertThrows(ConversionException.class, () -> ZonedDates.zone("Foo/Bar"));
    }
}
