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

package net.fyrql;

import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rows of {@code rows()} and the values of parameters have the dates and times of {@code java.time}. */
class JavaValuesTest {

    private static FyrQL engine;

    @BeforeAll
    static void open() {
        engine = FyrQL.builder().timeZone(ZoneOffset.UTC).build();
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    @Test
    void rowsHaveTheDatesAndTimesOfJavaTime() {
        Object[] row = engine.rows("SELECT DATE '2026-01-12', TIME '12:34:56.789', TIME '24:00:00',"
                + " TIMESTAMP '1969-12-31 23:59:59.5', TIMESTAMPTZ '2026-01-12 12:34:56+01', INTERVAL '1 day',"
                + " 42, 'text', CAST(NULL AS DATE)").get(0);
        assertArrayEquals(new Object[] {LocalDate.of(2026, 1, 12), LocalTime.of(12, 34, 56, 789_000_000),
                LocalTime.MAX, LocalDateTime.of(1969, 12, 31, 23, 59, 59, 500_000_000),
                OffsetDateTime.of(2026, 1, 12, 11, 34, 56, 0, ZoneOffset.UTC), Interval.of(0, 1, 0), 42, "text",
                null}, row);
    }

    @Test
    void nestedValuesHaveThemToo() {
        Object[] row = engine.rows("SELECT [DATE '2026-01-12', NULL], {'at': TIMESTAMP '2026-01-12 08:00:00', 'n': 1},"
                + " map([DATE '2026-01-12'], [TIME '08:00:00'])").get(0);
        assertEquals(Arrays.asList(LocalDate.of(2026, 1, 12), null), row[0]);
        assertEquals(List.of(LocalDateTime.of(2026, 1, 12, 8, 0), 1), row[1]);
        assertEquals(Map.of(LocalDate.of(2026, 1, 12), LocalTime.of(8, 0)), row[2]);
    }

    @Test
    void aTimestampWithTimeZoneIsInTheZoneOfTheSession() {
        Session session = engine.newSession();
        session.update("SET TimeZone = 'Europe/Berlin'");
        String sql = "SELECT TIMESTAMPTZ '2026-07-01 12:00:00+00'";
        OffsetDateTime berlin = (OffsetDateTime) session.rows(sql).get(0)[0];
        OffsetDateTime utc = (OffsetDateTime) engine.rows(sql).get(0)[0];
        assertEquals(OffsetDateTime.of(2026, 7, 1, 14, 0, 0, 0, ZoneOffset.ofHours(2)), berlin);
        assertEquals(ZoneOffset.UTC, utc.getOffset());
        assertTrue(berlin.isEqual(utc));
        try (QueryResult result = session.sql(sql)) {
            Batch batch = result.nextBatch();
            assertEquals(1_782_907_200_000_000L, batch.row(0)[0], "a batch keeps the form of its vectors");
        }
    }

    @Test
    void parametersTakeTheValuesOfJavaTime() {
        // coalesce gives each parameter the type of its other argument.
        PreparedQuery query = engine.prepare("SELECT coalesce(?, DATE '2000-01-01') + 1,"
                + " coalesce(?, TIMESTAMP '2000-01-01 00:00:00'),"
                + " coalesce(?, TIMESTAMPTZ '2000-01-01 00:00:00+00') + INTERVAL '1 hour',"
                + " coalesce(?, TIMESTAMPTZ '2000-01-01 00:00:00+00'),"
                + " coalesce(?, DATE '2000-01-01') + coalesce(?, INTERVAL '0 seconds')");
        Object[] row = engine.rows(query, List.of(LocalDate.of(2026, 1, 12),
                LocalDateTime.of(2026, 1, 12, 8, 0), OffsetDateTime.of(2026, 1, 12, 8, 0, 0, 0, ZoneOffset.ofHours(1)),
                Instant.EPOCH, LocalDate.of(2026, 1, 12), Duration.ofHours(36))).get(0);
        assertArrayEquals(new Object[] {LocalDate.of(2026, 1, 13), LocalDateTime.of(2026, 1, 12, 8, 0),
                OffsetDateTime.of(2026, 1, 12, 8, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), LocalDateTime.of(2026, 1, 13, 12, 0)}, row);
        PreparedQuery months = engine.prepare("SELECT DATE '2026-01-31' + coalesce(?, INTERVAL '0 days')");
        assertEquals(LocalDateTime.of(2026, 2, 28, 0, 0), engine.rows(months, List.of(Period.ofMonths(1))).get(0)[0]);
    }

    @Test
    void theValuesOfRowsGoBackInAsParameters() {
        String values = "SELECT DATE '2026-01-12' AS d, TIME '08:00:00' AS t, TIMESTAMP '2026-01-12 08:00:00' AS ts,"
                + " TIMESTAMPTZ '2026-01-12 08:00:00+00' AS tz, INTERVAL '2 days' AS i, [DATE '2026-01-12'] AS l";
        Session session = engine.newSession();
        session.update("SET TimeZone = 'America/New_York'");
        Object[] row = session.rows(values).get(0);
        PreparedQuery query = session.prepare("SELECT count(*) FROM (" + values + ") v"
                + " WHERE d = ? AND t = ? AND ts = ? AND tz = ? AND i = ? AND l = ?");
        assertEquals(1L, session.rows(query, Arrays.asList(row)).get(0)[0]);
    }

    @Test
    void theFormOfAVectorIsStillAValueOfAParameter() {
        PreparedQuery query = engine.prepare("SELECT coalesce(?, DATE '2000-01-01') + 1");
        assertEquals(LocalDate.of(2026, 1, 13), engine.rows(query, List.of(20465)).get(0)[0]);
    }

    @Test
    void aValueOfTheWrongClassNamesTheParameter() {
        PreparedQuery query = engine.prepare("SELECT DATE '2026-01-12' = ?");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine.rows(query, List.of(LocalDateTime.of(2026, 1, 12, 0, 0))));
        assertTrue(e.getMessage().startsWith("Parameter 1: Not a DATE: LocalDateTime 2026-01-12T00:00"),
                e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> engine.rows(query, List.of(LocalDate.MAX)));
        assertTrue(e.getMessage().startsWith("Parameter 1: Out of range for DATE: LocalDate"), e.getMessage());
    }

    @Test
    void theZoneOfTheEngineIsThatOfItsBuilder() {
        try (FyrQL tokyo = FyrQL.builder().timeZone(ZoneId.of("Asia/Tokyo")).build()) {
            OffsetDateTime t = (OffsetDateTime) tokyo.rows("SELECT TIMESTAMPTZ '2026-01-12 00:00:00+00'").get(0)[0];
            assertEquals(ZoneOffset.ofHours(9), t.getOffset());
        }
    }
}
