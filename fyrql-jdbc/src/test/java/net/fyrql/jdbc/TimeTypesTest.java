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

import net.fyrql.core.value.Interval;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Times of day, instants and intervals through the driver, in the time zone of the session. */
class TimeTypesTest {

    private static final String QUERY = "SELECT TIME '12:34:56.123456' AS tm,"
            + " TIMESTAMPTZ '2024-07-01 12:00:00.5+00' AS tz, INTERVAL '1 month 2 days 03:04:05' AS iv";

    @Test
    void theTimeZoneOfTheUrlIsTheOneOfTheSession() throws SQLException {
        try (Connection c = new FyrQLDriver().connect("jdbc:fyrql:?timeZone=Europe/Berlin", null);
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(QUERY)) {
                assertTrue(rs.next());
                assertEquals(Types.TIME, rs.getMetaData().getColumnType(1));
                assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, rs.getMetaData().getColumnType(2));
                assertEquals(Types.OTHER, rs.getMetaData().getColumnType(3));
                assertEquals("12:34:56.123456", rs.getString(1));
                assertEquals("2024-07-01 14:00:00.5+02", rs.getString(2));
                assertEquals("1 month 2 days 03:04:05", rs.getString(3));
                assertEquals(LocalTime.of(12, 34, 56, 123_456_000), rs.getObject(1, LocalTime.class));
                assertEquals("12:34:56", rs.getTime(1).toString());
                OffsetDateTime instant = OffsetDateTime.parse("2024-07-01T14:00:00.5+02:00");
                assertEquals(instant, rs.getObject(2));
                assertEquals(instant.toInstant(), rs.getObject(2, Instant.class));
                assertEquals(instant.atZoneSameInstant(ZoneId.of("Europe/Berlin")),
                        rs.getObject(2, ZonedDateTime.class));
                assertEquals(LocalDateTime.of(2024, 7, 1, 14, 0, 0, 500_000_000), rs.getObject(2,
                        LocalDateTime.class));
                assertEquals(Timestamp.from(instant.toInstant()), rs.getTimestamp(2));
                assertEquals(Interval.parse("1 month 2 days 03:04:05"), rs.getObject(3));
                // A Period has no time, a Duration no months.
                assertEquals("22018", assertThrows(SQLException.class, () -> rs.getObject(3, Period.class))
                        .getSQLState());
                assertEquals("22018", assertThrows(SQLException.class, () -> rs.getObject(3, Duration.class))
                        .getSQLState());
            }
            s.execute("SET TimeZone = 'UTC'");
            try (ResultSet rs = s.executeQuery(QUERY)) {
                assertTrue(rs.next());
                assertEquals("2024-07-01 12:00:00.5+00", rs.getString(2));
            }
        }
    }

    @Test
    void parametersOfTheNewTypes() throws SQLException {
        try (Connection c = new FyrQLDriver().connect("jdbc:fyrql:?timeZone=Europe/Berlin", null);
                PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            assertEquals("TIME 12:30:00.5", converted(ps, LocalTime.of(12, 30, 0, 500_000_000)));
            assertEquals("TIMESTAMP WITH TIME ZONE 2024-07-01 14:00:00+02",
                    converted(ps, Instant.parse("2024-07-01T12:00:00Z")));
            assertEquals("TIMESTAMP WITH TIME ZONE 2024-07-01 14:00:00+02",
                    converted(ps, OffsetDateTime.parse("2024-07-01T17:00:00+05:00")));
            assertEquals("INTERVAL 26:00:00", converted(ps, Duration.ofHours(26)));
            assertEquals("INTERVAL 1 year 2 months 3 days", converted(ps, Period.of(1, 2, 3)));
            assertEquals("INTERVAL -00:00:00.5", converted(ps, Interval.parse("-0.5 seconds")));
            ps.setObject(1, "2024-07-01 12:00:00", Types.TIMESTAMP_WITH_TIMEZONE);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                // A timestamp without an offset is in the time zone of the session.
                assertEquals("2024-07-01 12:00:00+02", rs.getString(1));
            }
        }
    }

    private static String converted(PreparedStatement ps, Object value) throws SQLException {
        ps.setObject(1, value);
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getMetaData().getColumnTypeName(1) + " " + rs.getString(1);
        }
    }
}
