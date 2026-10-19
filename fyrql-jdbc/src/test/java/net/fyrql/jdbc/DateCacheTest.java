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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cache of dates gives what {@code Date.valueOf} gives, in every default time zone. */
class DateCacheTest {

    /** Days around the start of the Gregorian calendar, the epoch, changes of daylight saving time, and far off. */
    private static final List<LocalDate> DAYS = List.of(LocalDate.of(1, 1, 1), LocalDate.of(1582, 10, 4),
            LocalDate.of(1582, 10, 15), LocalDate.of(1893, 4, 1), LocalDate.of(1969, 12, 31),
            LocalDate.of(1970, 1, 1), LocalDate.of(1992, 1, 2), LocalDate.of(1998, 12, 1),
            LocalDate.of(2011, 12, 30), LocalDate.of(2024, 2, 29), LocalDate.of(2026, 3, 29),
            LocalDate.of(2026, 10, 25), LocalDate.of(9999, 12, 31), LocalDate.of(-4000, 6, 15));

    private static final List<String> ZONES = List.of("UTC", "Europe/Berlin", "America/Sao_Paulo",
            "Pacific/Apia", "Asia/Kolkata", "America/St_Johns");

    @Test
    void aDateIsTheOneOfDateValueOfInEveryZone() {
        TimeZone before = TimeZone.getDefault();
        DateCache cache = new DateCache();
        try {
            for (String zone : ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                cache.check();
                for (int round = 0; round < 2; round++) {
                    for (LocalDate day : DAYS) {
                        assertSame(day, zone, cache);
                    }
                    for (long day = LocalDate.of(1990, 1, 1).toEpochDay(); day < LocalDate.of(2030, 1, 1)
                            .toEpochDay(); day += 7) {
                        assertSame(LocalDate.ofEpochDay(day), zone, cache);
                    }
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    void daysOfOneSlotDoNotMix() {
        DateCache cache = new DateCache();
        for (int round = 0; round < 3; round++) {
            for (int day = 20_000; day <= 20_000 + 4 * 1024; day += 1024) {
                assertEquals(Date.valueOf(LocalDate.ofEpochDay(day)), cache.date(day));
            }
        }
    }

    @Test
    void eachDateIsANewObject() {
        DateCache cache = new DateCache();
        Date first = cache.date(20_000);
        first.setTime(0);
        Date second = cache.date(20_000);
        assertNotSame(first, second);
        assertEquals(Date.valueOf(LocalDate.ofEpochDay(20_000)), second);
    }

    @Test
    void getDateAndGetObjectReadThroughTheCache() throws SQLException {
        TimeZone before = TimeZone.getDefault();
        try (Connection c = FyrQLDriver.connect(FyrQL.builder().register(AllTypesTable.connector("mem")).build(),
                true); Statement s = c.createStatement()) {
            for (String zone : ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                try (ResultSet rs = s.executeQuery("SELECT DATE '1582-10-15' AS a, DATE '2026-03-29' AS b,"
                        + " CAST(NULL AS DATE) AS n")) {
                    assertTrue(rs.next());
                    assertEquals(Date.valueOf("1582-10-15"), rs.getDate("a"), zone);
                    assertEquals(Date.valueOf("2026-03-29"), rs.getDate("b"), zone);
                    assertEquals(Date.valueOf("2026-03-29"), rs.getObject("b"), zone);
                    assertEquals(null, rs.getDate("n"));
                    assertTrue(rs.wasNull());
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    private static void assertSame(LocalDate day, String zone, DateCache cache) {
        Date expected = Date.valueOf(day);
        Date actual = cache.date((int) day.toEpochDay());
        assertEquals(expected.getTime(), actual.getTime(), day + " in " + zone);
        assertEquals(expected.toString(), actual.toString(), day + " in " + zone);
        // Not the day itself: a java.sql.Date turns a year before the common era into one after it
        assertEquals(expected.toLocalDate(), actual.toLocalDate(), day + " in " + zone);
    }
}
