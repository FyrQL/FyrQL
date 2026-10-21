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

package net.fyrql.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down how DuckDB treats {@code TIME}, {@code TIMESTAMP WITH TIME ZONE}
 * and {@code INTERVAL}, the behavior that FyrQL follows: the result types of
 * the arithmetic, the casts, the common types, the text forms, the normalized
 * comparison of intervals, and the arithmetic in the time zone of the session
 * with its gaps and overlaps. A second part prints what DuckDB does in cases
 * that are not pinned down, for the record in the test output.
 */
class DuckDbTimeTypesTest {

    private static Connection connection;

    @BeforeAll
    static void open() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE t (d DATE, ts TIMESTAMP, tz TIMESTAMPTZ, tm TIME, iv INTERVAL, i INTEGER,"
                    + " v VARCHAR)");
            s.execute("INSERT INTO t VALUES (DATE '2024-07-01', TIMESTAMP '2024-07-01 12:00:00',"
                    + " TIMESTAMPTZ '2024-07-01 12:00:00+00', TIME '12:00:00', INTERVAL '1 day', 1, 'x'),"
                    + " (DATE '2024-07-02', TIMESTAMP '2024-07-02 12:00:00', TIMESTAMPTZ '2024-07-02 12:00:00+00',"
                    + " TIME '13:00:00', INTERVAL '30 days', 2, 'y')");
            s.execute("CREATE TABLE ivs AS SELECT * FROM (VALUES (INTERVAL '1 month'), (INTERVAL '30 days'),"
                    + " (INTERVAL '720 hours'), (INTERVAL '1 day')) v(iv)");
        }
    }

    @AfterAll
    static void close() throws SQLException {
        connection.close();
    }

    @Test
    void resultTypesOfTheArithmetic() throws SQLException {
        Checks c = new Checks("UTC");
        c.value("now()", "TIMESTAMP WITH TIME ZONE", null);
        c.value("to_timestamp(0)", "TIMESTAMP WITH TIME ZONE", "1970-01-01 00:00:00+00");
        c.value("current_time", "TIME WITH TIME ZONE", null);
        c.value("DATE '2024-01-31' + INTERVAL 1 MONTH", "TIMESTAMP", "2024-02-29 00:00:00");
        c.value("DATE '2024-03-01' - DATE '2024-01-01'", "BIGINT", "60");
        c.value("TIMESTAMP '2026-01-01 00:00:00' - TIMESTAMP '2024-01-01 00:00:00'", "INTERVAL", "731 days");
        c.value("TIMESTAMP '2024-01-02 03:00:00' - TIMESTAMP '2024-01-01 00:00:00'", "INTERVAL", "1 day 03:00:00");
        c.value("TIME '23:00:00' + INTERVAL '2 hours'", "TIME", "01:00:00");
        c.value("TIME '12:00:00' + INTERVAL '1 month 1 day 1 hour'", "TIME", "13:00:00");
        c.value("TIME '01:00:00' - INTERVAL '2 hours'", "TIME", "23:00:00");
        c.value("TIME '12:00:00' + DATE '2024-01-01'", "TIMESTAMP", "2024-01-01 12:00:00");
        c.value("DATE '2024-01-01' + TIME '12:00:00'", "TIMESTAMP", "2024-01-01 12:00:00");
        c.error("TIMESTAMP '2024-01-01 00:00:00' + TIME '12:00:00'");
        c.error("tm - tm FROM t");
        c.error("sum(iv) FROM t");
        c.value("avg(iv) FROM t", "INTERVAL", "15 days 12:00:00");
        c.value("max(iv) FROM t", "INTERVAL", "30 days");
        c.value("min(tm) FROM t", "TIME", "12:00:00");
        c.value("max(tz) FROM t", "TIMESTAMP WITH TIME ZONE", "2024-07-02 12:00:00+00");
        c.value("greatest(INTERVAL '1 month', INTERVAL '29 days')", "INTERVAL", "1 month");
        c.value("-INTERVAL '1 day 02:00:00'", "INTERVAL", "-1 day -02:00:00");
        c.value("INTERVAL '1 day' * 2", "INTERVAL", "2 days");
        c.value("2 * INTERVAL '1 day'", "INTERVAL", "2 days");
        c.done();
    }

    @Test
    void intervals() throws SQLException {
        Checks c = new Checks("UTC");
        c.value("INTERVAL '1 month' = INTERVAL '30 days'", "BOOLEAN", "true");
        c.value("INTERVAL '1 month' = INTERVAL '720 hours'", "BOOLEAN", "true");
        c.value("INTERVAL '1 month' < INTERVAL '31 days'", "BOOLEAN", "true");
        c.value("count(DISTINCT iv) FROM ivs", "BIGINT", "2");
        c.value("count(*) FROM (SELECT iv FROM ivs GROUP BY iv)", "BIGINT", "2");
        c.value("INTERVAL '1 month 1 day' - INTERVAL '31 days'", "INTERVAL", "1 month -30 days");
        c.value("INTERVAL '1 year 13 months'", "INTERVAL", "2 years 1 month");
        c.value("INTERVAL '25 hours'", "INTERVAL", "25:00:00");
        c.value("INTERVAL '1.5 days'", "INTERVAL", "1 day 12:00:00");
        c.value("INTERVAL '0.5 years'", "INTERVAL", "6 months");
        c.value("INTERVAL '1 year 1.5 months'", "INTERVAL", "1 year 1 month 15 days");
        c.value("INTERVAL '1 year' / 3", "INTERVAL", "4 months");
        c.value("INTERVAL '1 month 1 day 1 second' * 0.5", "INTERVAL", "15 days 12:00:00.5");
        c.value("INTERVAL '1 day' / 0", "INTERVAL", "NULL");
        c.value("INTERVAL '1 year 2 months -3 days 04:05:06'", "INTERVAL", "1 year 2 months -3 days 04:05:06");
        c.value("INTERVAL '-0.5 seconds'", "INTERVAL", "-00:00:00.5");
        c.value("INTERVAL '0 days'", "INTERVAL", "00:00:00");
        c.value("INTERVAL '100 hours'", "INTERVAL", "100:00:00");
        c.value("epoch(INTERVAL '1 month')", "DOUBLE", "2592000.0");
        c.error("INTERVAL 'P1Y2M'");
        c.error("INTERVAL '2000000000 months' + INTERVAL '2000000000 months'");
        c.error("INTERVAL '2000000000 months' * 2");
        c.error("CAST(iv AS DOUBLE) FROM t");
        c.error("CAST(i AS INTERVAL) FROM t");
        c.done();
    }

    @Test
    void timesAndCasts() throws SQLException {
        Checks c = new Checks("Europe/Berlin");
        c.value("TIME '12:00:00.000001'", "TIME", "12:00:00.000001");
        c.value("TIME '24:00:00'", "TIME", "24:00:00");
        c.error("TIME '12'");
        c.value("date_part('microsecond', TIME '12:34:56.789')", "BIGINT", "56789000");
        c.value("date_part('epoch', TIME '12:34:56.789')", "DOUBLE", null);
        c.error("strftime(TIME '12:00:00', '%H')");
        c.error("date_trunc('hour', TIME '12:34:56')");
        c.value("CAST(TIMESTAMP '2024-07-01 12:34:56' AS TIME)", "TIME", "12:34:56");
        c.value("CAST(DATE '2024-07-01' AS TIMESTAMPTZ)", "TIMESTAMP WITH TIME ZONE", "2024-07-01 00:00:00+02");
        c.value("CAST(TIMESTAMPTZ '2024-07-01 12:00:00Z' AS TIMESTAMP)", "TIMESTAMP", "2024-07-01 14:00:00");
        c.value("CAST(TIMESTAMP '2024-07-01 12:00:00' AS TIMESTAMPTZ)", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 12:00:00+02");
        c.value("CAST(TIMESTAMPTZ '2024-07-01 23:30:00Z' AS DATE)", "DATE", "2024-07-02");
        c.error("CAST(tm AS TIMESTAMP) FROM t");
        c.error("CAST(tz AS TIME) FROM t");
        c.error("CAST(tz AS BIGINT) FROM t");
        c.value("typeof(CASE WHEN i = 1 THEN d ELSE tz END) FROM t", "VARCHAR", "TIMESTAMP WITH TIME ZONE");
        c.value("typeof(CASE WHEN i = 1 THEN ts ELSE tz END) FROM t", "VARCHAR", "TIMESTAMP WITH TIME ZONE");
        c.value("typeof(CASE WHEN i = 1 THEN d ELSE ts END) FROM t", "VARCHAR", "TIMESTAMP");
        c.error("CASE WHEN i = 1 THEN tm ELSE ts END FROM t");
        c.error("CASE WHEN i = 1 THEN iv ELSE i END FROM t");
        c.value("ts = tz FROM t", "BOOLEAN", "false");
        c.value("tm = '12:00:00' FROM t", "BOOLEAN", "true");
        c.value("iv = '24 hours' FROM t", "BOOLEAN", "true");
        c.done();
    }

    @Test
    void timeZoneOfTheSession() throws SQLException {
        Checks c = new Checks("Europe/Berlin");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00Z'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 14:00:00+02");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 12:00:00+02");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00+05:30'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 08:30:00+02");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00 America/New_York'", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 18:00:00+02");
        c.value("TIMESTAMPTZ '2024-03-31 12:00:00+02' - INTERVAL '1 day'", "TIMESTAMP WITH TIME ZONE",
                "2024-03-30 12:00:00+01");
        c.value("TIMESTAMPTZ '2024-03-30 12:00:00+01' + INTERVAL '24 hours'", "TIMESTAMP WITH TIME ZONE",
                "2024-03-31 13:00:00+02");
        c.value("TIMESTAMPTZ '2024-03-31 02:30:00'", "TIMESTAMP WITH TIME ZONE", "2024-03-31 03:30:00+02");
        c.value("TIMESTAMPTZ '2024-10-27 02:30:00'", "TIMESTAMP WITH TIME ZONE", "2024-10-27 02:30:00+01");
        c.value("date_trunc('day', TIMESTAMPTZ '2024-07-01 01:00:00+02')", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 00:00:00+02");
        c.value("year(TIMESTAMPTZ '2024-12-31 23:30:00Z')", "BIGINT", "2025");
        c.value("date_part('timezone', TIMESTAMPTZ '2024-07-01 12:00:00Z')", "BIGINT", "7200");
        c.value("date_part('timezone_hour', TIMESTAMPTZ '2024-01-01 12:00:00Z')", "BIGINT", "1");
        c.value("strftime(TIMESTAMPTZ '2024-07-01 12:00:00Z', '%z')", "VARCHAR", "+02");
        c.value("strftime(TIMESTAMPTZ '2024-07-01 12:00:00Z', '%Z')", "VARCHAR", "Europe/Berlin");
        c.value("strptime('2024-07-01 12:00:00 +0200', '%Y-%m-%d %H:%M:%S %z')", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 12:00:00+02");
        c.value("age(TIMESTAMP '2024-03-01 00:00:00', TIMESTAMP '2024-01-01 00:00:00')", "INTERVAL", "2 months");
        c.value("current_setting('TimeZone')", "VARCHAR", "Europe/Berlin");
        c.value("TIMESTAMPTZ '2024-03-31 12:00:00+02' - TIMESTAMPTZ '2024-03-30 12:00:00+01'", "INTERVAL", "1 day");
        c.value("TIMESTAMPTZ '2024-03-31 11:00:00+02' - TIMESTAMPTZ '2024-03-30 12:00:00+01'", "INTERVAL",
                "22:00:00");
        c.value("TIMESTAMPTZ '2024-04-30 12:00:00+02' - TIMESTAMPTZ '2024-03-30 12:00:00+01'", "INTERVAL",
                "31 days");
        c.value("date_diff('hour', TIMESTAMPTZ '2024-03-30 12:00:00+01', TIMESTAMPTZ '2024-03-31 12:00:00+02')",
                "BIGINT", "23");
        c.value("TIMESTAMPTZ '2024-10-27 02:30:00+02' + INTERVAL '1 hour'", "TIMESTAMP WITH TIME ZONE",
                "2024-10-27 02:30:00+01");
        c.value("TIMESTAMPTZ '0001-01-01 00:00:00+00'", "TIMESTAMP WITH TIME ZONE", "0001-01-01 00:53:28+00:53");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00 PST'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 21:00:00+02");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00 EST'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 19:00:00+02");
        c.value("TIMESTAMPTZ '2024-07-01 12:00'", "TIMESTAMP WITH TIME ZONE", "2024-07-01 12:00:00+02");
        c.value("TIMESTAMPTZ 'epoch'", "TIMESTAMP WITH TIME ZONE", "1970-01-01 01:00:00+01");
        c.error("TIMESTAMPTZ '2024-07-01 12:00:00+2'");
        c.error("TIMESTAMPTZ '2024-07-01 12:00:00 Foo/Bar'");
        c.value("TIMESTAMPTZ '2024-07-01 12:00:00Z' AT TIME ZONE 'UTC'", "TIMESTAMP", "2024-07-01 12:00:00");
        c.value("TIMESTAMP '2024-07-01 12:00:00' AT TIME ZONE 'America/New_York'", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 18:00:00+02");
        c.value("strftime(TIMESTAMP '2024-07-01 12:00:00', '%z|%Z')", "VARCHAR", "+00|");
        c.done();
        Checks newfoundland = new Checks("America/St_Johns");
        newfoundland.value("TIMESTAMPTZ '2024-07-01 12:00:00+00'", "TIMESTAMP WITH TIME ZONE",
                "2024-07-01 09:30:00-02:30");
        newfoundland.value("date_part('timezone_minute', TIMESTAMPTZ '2024-07-01 12:00:00+00')", "BIGINT", "-30");
        newfoundland.value("strftime(TIMESTAMPTZ '2024-07-01 12:00:00+00', '%z')", "VARCHAR", "-02:30");
        newfoundland.done();
    }

    /**
     * The details of intervals: the normalization behind the comparison, the
     * fractions of a multiplication, which go from months to days in
     * millionths of a day, and of the text, which keep six digits and go
     * from a unit only to the next smaller one.
     */
    @Test
    void intervalDetails() throws SQLException {
        Checks c = new Checks("UTC");
        c.value("INTERVAL '1 day -1 hour' = INTERVAL '23 hours'", "BOOLEAN", "false");
        c.value("INTERVAL '1 month -1 day' = INTERVAL '29 days'", "BOOLEAN", "false");
        c.value("INTERVAL '-36 hours' = INTERVAL '-1 day -12 hours'", "BOOLEAN", "true");
        c.value("INTERVAL '1 day -24 hours' = INTERVAL '0 days'", "BOOLEAN", "true");
        c.value("INTERVAL '-36 hours' < INTERVAL '-1 day'", "BOOLEAN", "true");
        c.value("INTERVAL '1 month' / 7", "INTERVAL", "4 days 06:51:25.6896");
        c.value("INTERVAL '1 month' / 11", "INTERVAL", "2 days 17:27:16.3872");
        c.value("INTERVAL '1 day' * 0.3333333", "INTERVAL", "07:59:59.99712");
        c.value("INTERVAL '5 days' / 7", "INTERVAL", "17:08:34.285714");
        c.value("INTERVAL '7 months 3 days 1 second' / 2", "INTERVAL", "3 months 16 days 12:00:00.5");
        c.value("INTERVAL '-7 months' / 2", "INTERVAL", "-3 months -15 days");
        c.value("INTERVAL '1 microsecond' / 2", "INTERVAL", "00:00:00");
        c.value("INTERVAL '3 microseconds' / 2", "INTERVAL", "00:00:00.000002");
        c.value("INTERVAL '1 month' * 2147483647", "INTERVAL", "178956970 years 7 months");
        c.value("INTERVAL '-2147483648 months'", "INTERVAL", "-178956970 years -8 months");
        c.error("-INTERVAL '-2147483648 months'");
        c.error("INTERVAL '1 day' * 1e10");
        c.value("INTERVAL '1 day' / 0.0", "INTERVAL", "NULL");
        c.error("INTERVAL '1 day' * 'nan'::DOUBLE");
        c.value("INTERVAL '0.142857142857 months'", "INTERVAL", "4 days");
        c.value("INTERVAL '0.1 years'", "INTERVAL", "1 month");
        c.value("INTERVAL '1.3333333 days'", "INTERVAL", "1 day 07:59:59.9712");
        c.value("INTERVAL '1.0000015 seconds'", "INTERVAL", "00:00:01.000001");
        c.value("INTERVAL '0.5 microseconds'", "INTERVAL", "00:00:00.000001");
        c.value("INTERVAL '1.5 weeks'", "INTERVAL", "10 days 12:00:00");
        c.value("INTERVAL '1 day ago'", "INTERVAL", "-1 day");
        c.value("INTERVAL '@ 1 day'", "INTERVAL", "1 day");
        c.value("INTERVAL '1:2'", "INTERVAL", "01:02:00");
        c.value("INTERVAL '1.5'", "INTERVAL", "00:00:01.5");
        c.error("INTERVAL '+1 day'");
        c.error("INTERVAL '1 micros'");
        c.value("date_trunc('week', INTERVAL '40 days')", "INTERVAL", "35 days");
        c.value("date_trunc('month', INTERVAL '1 year 14 months 40 days')", "INTERVAL", "2 years 2 months");
        c.value("date_part('epoch', INTERVAL '1 year 14 months 40 days 25:01:02.5')", "DOUBLE", "71845262.5");
        c.value("date_part('millisecond', INTERVAL '90.5 seconds')", "BIGINT", "30500");
        c.value("age(TIMESTAMP '2024-01-01 00:00:00', TIMESTAMP '2024-02-02 02:30:00')", "INTERVAL",
                "-1 month -1 day -02:30:00");
        c.value("age(DATE '2024-03-31', DATE '2024-02-29')", "INTERVAL", "1 month 2 days");
        c.value("DATE '2024-07-01' - TIMESTAMP '2024-06-30 06:00:00'", "INTERVAL", "18:00:00");
        c.value("TIME '12:00:00' + INTERVAL '-25 hours'", "TIME", "11:00:00");
        c.value("TIME '24:00:00' + INTERVAL '0 hours'", "TIME", "00:00:00");
        c.value("avg(tm) FROM t", "TIME", "12:30:00");
        c.value("CAST('12:00:00+02' AS TIME)", "TIME", "12:00:00");
        c.value("CAST('2024-07-01 12:00:00' AS TIME)", "TIME", "12:00:00");
        c.error("CAST('24:00:01' AS TIME)");
        c.done();
    }

    /** Prints the cases that the design leaves open, without expectations. */
    @Test
    void probe(@TempDir Path dir) throws SQLException, IOException {
        List<String> units = new ArrayList<>();
        for (String unit : List.of("1 week", "1 weeks", "1 quarter", "1 quarters", "1 decade", "1 decades",
                "1 century", "1 centuries", "1 millennium", "1 millennia", "1 ms", "1 msec", "1 msecs",
                "1 millisecond", "1 us", "1 usec", "1 microsecond", "1 micros", "2 min", "2 mins", "2 minute",
                "3 s", "3 sec", "3 secs", "1 y", "1 yr", "1 yrs", "1 year", "1 mon", "1 mons", "1 month", "1 h",
                "1 hr", "1 hrs", "1 d", "1 day", "@ 1 day", "1 day ago", "+1 day", "1 day -1 hour", "1:2",
                "1:2:3", "-1 day +02:00", "1 2:00", "1 day 2", "P1D", "1", "1.5", "  1 day  ", "1DAY", "1 Day",
                "1 day 1 day", "-1.5 months", "1e2 days", "1 day 25:00:00", "00:00:00.1234567", "1 dayss",
                "2147483647 days", "2147483648 days", "9223372036854775807 microseconds", "1000000 years")) {
            units.add("SELECT INTERVAL '" + unit + "'");
        }
        print("UTC", units);
        print("UTC", List.of(
                "SELECT date_part(['year', 'month', 'day', 'hour', 'minute', 'second', 'millisecond',"
                        + " 'microsecond', 'quarter', 'decade', 'century', 'millennium'],"
                        + " INTERVAL '1 year 14 months 40 days 25:01:02.5')::VARCHAR",
                "SELECT date_part('epoch', INTERVAL '1 year 14 months 40 days 25:01:02.5')",
                "SELECT date_part('week', INTERVAL '40 days'), date_part('dow', INTERVAL '40 days')",
                "SELECT date_trunc('month', INTERVAL '1 year 14 months 40 days 25:01:02.5'),"
                        + " date_trunc('hour', INTERVAL '40 days 25:01:02.5')",
                "SELECT to_years(2), to_months(14), to_days(40), to_hours(25), to_minutes(61), to_seconds(1.5),"
                        + " to_milliseconds(2), to_microseconds(3), to_weeks(2), to_centuries(1)",
                "SELECT typeof(to_seconds(1.5)), typeof(to_hours(1)), to_hours(2147483648)",
                "SELECT make_time(1, 2, 3.5), typeof(make_time(1, 2, 3.5)), make_time(24, 0, 0)",
                "SELECT avg(tm), median(tm), typeof(avg(tm)) FROM t",
                "SELECT sum(tm) FROM t",
                "SELECT median(iv), typeof(median(iv)) FROM t",
                "SELECT TIME '24:00:00' > TIME '23:59:59.999999', TIME '24:00:00' + INTERVAL '1 hour'",
                "SELECT TIME '24:00:00' + INTERVAL '0 hours', TIME '00:00:00' - INTERVAL '0 hours'",
                "SELECT INTERVAL '1 day' / 7, INTERVAL '1 day' * 1.5, INTERVAL '1 month' / 7, INTERVAL '-1 month' / 7",
                "SELECT INTERVAL '1 month' * 1.0000001, INTERVAL '1 second' / 3",
                "SELECT INTERVAL '1 day' // 2",
                "SELECT abs(INTERVAL '-1 day')",
                "SELECT DATE '2024-01-01' - TIMESTAMP '2023-12-31 12:00:00'",
                "SELECT TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '1 month 1 day 1 hour'",
                "SELECT TIMESTAMP '2024-01-01 00:00:00' - TIMESTAMP '2024-01-02 03:00:00'",
                "SELECT TIMESTAMP '2024-03-01 00:00:00' - TIMESTAMP '2024-01-31 00:00:00'",
                "SELECT age(TIMESTAMP '2024-01-01 00:00:00', TIMESTAMP '2024-02-02 02:30:00'),"
                        + " age(TIMESTAMP '2024-01-31 00:00:00', TIMESTAMP '2023-12-31 00:00:00'),"
                        + " age(DATE '2024-03-31', DATE '2024-02-29')",
                "SELECT typeof(age(TIMESTAMPTZ '2024-01-01 00:00:00+00')), typeof(age(DATE '2024-01-01'))",
                "SELECT date_diff('month', TIMESTAMP '2024-01-31 00:00:00', TIMESTAMP '2024-02-29 00:00:00'),"
                        + " date_diff('hour', TIME '01:00:00', TIME '03:30:00')",
                "SELECT date_sub('minute', TIME '01:00:00', TIME '03:30:00'), date_part('hour', INTERVAL '-25 hours')",
                "SELECT CAST('1 day' AS INTERVAL), CAST(INTERVAL '1 day' AS VARCHAR), TRY_CAST('x' AS INTERVAL)",
                "SELECT CAST('12:00' AS TIME), CAST('12:00:00+02' AS TIME), CAST('2024-07-01 12:00:00' AS TIME)",
                "SELECT CAST('1 day' AS TIME)",
                "SELECT CAST(TIME '12:00:00' AS VARCHAR), CAST(TIME '12:00:00' AS INTERVAL)",
                "SELECT CAST(INTERVAL '1 hour' AS TIME)",
                "SELECT [INTERVAL '1 day']",
                "SELECT list(iv) FROM t",
                "SELECT first(iv), string_agg(iv, ',') FROM t",
                "SELECT {'a': INTERVAL '1 day'}",
                "SELECT [TIME '12:00:00'], [TIMESTAMPTZ '2024-07-01 12:00:00+00']",
                "SELECT list(tm), list(tz) FROM t"));
        print("Europe/Berlin", List.of(
                "SELECT TIMESTAMPTZ '0001-01-01 00:00:00Z', TIMESTAMPTZ '1890-01-01 00:00:00Z'",
                "SELECT TIMESTAMPTZ '9999-12-31 23:59:59Z', TIMESTAMPTZ '2024-07-01 12:00:00 CET'",
                "SELECT TIMESTAMPTZ '2024-07-01 12:00:00 PST', TIMESTAMPTZ '2024-07-01 12:00:00 EST'",
                "SELECT TIMESTAMPTZ '2024-07-01 12:00:00 UTC', TIMESTAMPTZ '2024-07-01T12:00:00.123456+0130'",
                "SELECT TIMESTAMPTZ '2024-07-01 12:00:00 Foo/Bar'",
                "SELECT TIMESTAMPTZ '2024-07-01', TIMESTAMPTZ '2024-07-01 12:00', TIMESTAMPTZ '2024-07-01 12:00:00+2'",
                "SELECT TIMESTAMPTZ '2024-07-01 12:00:00-00:30:15'",
                "SELECT TIMESTAMPTZ '2024-01-31 12:00:00+01' + INTERVAL '1 month',"
                        + " TIMESTAMPTZ '2024-03-30 02:30:00+01' + INTERVAL '1 day'",
                "SELECT TIMESTAMPTZ '2024-10-26 02:30:00+02' + INTERVAL '1 day',"
                        + " TIMESTAMPTZ '2024-10-27 02:30:00+02' + INTERVAL '1 hour'",
                "SELECT TIMESTAMPTZ '2024-03-31 12:00:00+02' - TIMESTAMPTZ '2024-03-30 12:00:00+01'",
                "SELECT date_diff('day', TIMESTAMPTZ '2024-03-30 12:00:00+01', TIMESTAMPTZ '2024-03-31 11:00:00+02'),"
                        + " date_diff('hour', TIMESTAMPTZ '2024-03-30 12:00:00+01',"
                        + " TIMESTAMPTZ '2024-03-31 12:00:00+02')",
                "SELECT date_trunc('day', TIMESTAMPTZ '2024-03-31 12:00:00+02'),"
                        + " date_trunc('hour', TIMESTAMPTZ '2024-10-27 02:30:00+01'),"
                        + " date_trunc('month', TIMESTAMPTZ '2024-03-31 12:00:00+02')",
                "SELECT typeof(date_trunc('day', TIMESTAMPTZ '2024-03-31 12:00:00+02')),"
                        + " typeof(date_trunc('day', TIMESTAMP '2024-03-31 12:00:00'))",
                "SELECT last_day(TIMESTAMPTZ '2024-02-29 23:30:00Z'), dayname(TIMESTAMPTZ '2024-07-01 23:30:00Z'),"
                        + " monthname(TIMESTAMPTZ '2024-07-31 23:30:00Z')",
                "SELECT date_part('timezone_minute', TIMESTAMPTZ '2024-07-01 12:00:00Z'),"
                        + " date_part('epoch', TIMESTAMPTZ '2024-07-01 12:00:00Z'),"
                        + " epoch_ms(TIMESTAMPTZ '2024-07-01 12:00:00Z')",
                "SELECT date_part('timezone', TIMESTAMP '2024-07-01 12:00:00')",
                "SELECT strftime(TIMESTAMP '2024-07-01 12:00:00', '%z|%Z'), strftime(DATE '2024-07-01', '%z|%Z')",
                "SELECT strptime('2024-07-01 12:00:00 Europe/Paris', '%Y-%m-%d %H:%M:%S %Z'),"
                        + " strptime('2024-07-01 12:00:00 +05:30', '%Y-%m-%d %H:%M:%S %z')",
                "SELECT TIMESTAMPTZ '2024-07-01 12:00:00Z' AT TIME ZONE 'UTC',"
                        + " TIMESTAMP '2024-07-01 12:00:00' AT TIME ZONE 'America/New_York'",
                "SELECT typeof(TIMESTAMPTZ '2024-07-01 12:00:00Z' AT TIME ZONE 'UTC'),"
                        + " typeof(TIMESTAMP '2024-07-01 12:00:00' AT TIME ZONE 'UTC')",
                "SELECT timezone('UTC', TIMESTAMPTZ '2024-07-01 12:00:00Z'),"
                        + " timezone('+05:30', TIMESTAMP '2024-07-01 12:00:00')",
                "SELECT make_timestamptz(2024, 7, 1, 12, 0, 0.5), make_timestamptz(2024, 7, 1, 12, 0, 0, 'UTC'),"
                        + " make_timestamptz(1719835200000000)",
                "SELECT current_localtime() IS NOT NULL, typeof(current_localtime()), typeof(current_localtimestamp()),"
                        + " typeof(localtime), typeof(localtimestamp), typeof(current_date), typeof(today())",
                "SELECT typeof(get_current_timestamp()), typeof(transaction_timestamp()), typeof(current_timestamp)",
                "SELECT to_timestamp(1.5), to_timestamp(-1), to_timestamp(1e300)",
                "SELECT tz, ts, tz = ts, tz > d FROM t",
                "SELECT coalesce(ts, tz), greatest(d, tz) FROM t",
                "SELECT tz FROM t UNION ALL SELECT ts FROM t",
                "SELECT tz + INTERVAL '1 day' FROM t WHERE tz > '2024-07-01 13:00:00'"));
        print("Europe/Berlin", List.of(
                "SET TimeZone = 'Foo/Bar'",
                "SET TimeZone = 'CET'",
                "SELECT current_setting('TimeZone'), TIMESTAMPTZ '2024-07-01 12:00:00Z'",
                "SET TimeZone = '+02:00'",
                "SELECT current_setting('TimeZone'), TIMESTAMPTZ '2024-07-01 12:00:00Z'",
                "SET TimeZone = 'utc'",
                "SELECT current_setting('TimeZone'), TIMESTAMPTZ '2024-07-01 12:00:00Z'",
                "RESET TimeZone",
                "SELECT current_setting('TimeZone')"));
        Path csv = dir.resolve("times.csv");
        Files.writeString(csv, "a,b,c,d,e\n12:30:00,2024-07-01 12:00:00+02,2024-07-01T12:00:00Z,1 day,12:30\n"
                + "23:59:59.5,2024-07-02 13:00:00+02,2024-07-02T12:00:00Z,2 days,13:45\n", StandardCharsets.UTF_8);
        Path json = dir.resolve("times.json");
        Files.writeString(json, "{\"a\": \"12:30:00\", \"b\": \"2024-07-01 12:00:00+02\", \"c\": \"1 day\"}\n",
                StandardCharsets.UTF_8);
        String csvPath = csv.toString().replace('\\', '/');
        String jsonPath = json.toString().replace('\\', '/');
        print("Europe/Berlin", List.of(
                "SELECT typeof(a), typeof(b), typeof(c), typeof(d), typeof(e) FROM read_csv('" + csvPath + "') LIMIT 1",
                "SELECT a, b, c FROM read_csv('" + csvPath + "') LIMIT 1",
                "SELECT typeof(a), typeof(b), typeof(c) FROM read_json('" + jsonPath + "') LIMIT 1",
                "SELECT b FROM read_json('" + jsonPath + "') LIMIT 1"));
        objects();
    }

    /** Prints the classes and values that the DuckDB driver gives for the three types. */
    private static void objects() throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("SET TimeZone = 'Europe/Berlin'");
            try (ResultSet rs = s.executeQuery("SELECT TIME '12:34:56.789', TIMESTAMPTZ '2024-07-01 12:00:00Z',"
                    + " INTERVAL '1 year 2 months 3 days 04:05:06.789', TIME '24:00:00'")) {
                rs.next();
                for (int i = 1; i <= 4; i++) {
                    Object o;
                    try {
                        o = rs.getObject(i);
                    } catch (RuntimeException e) {
                        o = "DRIVER ERROR " + e.getMessage();
                    }
                    System.out.println("PROBE getObject(" + i + ") " + rs.getMetaData().getColumnTypeName(i) + " "
                            + rs.getMetaData().getColumnType(i) + " " + rs.getMetaData().getColumnClassName(i) + " -> "
                            + (o == null ? "null" : o.getClass().getName() + " " + o) + " | getString "
                            + text(rs, i));
                }
            }
        }
    }

    /** Prints each query with its first row, or its error, in the given time zone. */
    private static void print(String zone, List<String> queries) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("SET TimeZone = '" + zone + "'");
        }
        for (String q : queries) {
            StringBuilder line = new StringBuilder("[").append(zone).append("] ").append(q).append(" -> ");
            try (Statement s = connection.createStatement()) {
                if (s.execute(q)) {
                    try (ResultSet rs = s.getResultSet()) {
                        if (rs.next()) {
                            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                line.append(i > 1 ? " | " : "").append(text(rs, i));
                            }
                        } else {
                            line.append("no rows");
                        }
                    }
                } else {
                    line.append("ok");
                }
            } catch (SQLException e) {
                line.append("ERROR ").append(e.getMessage().split("\n")[0]);
            }
            System.out.println("PROBE " + line);
        }
    }

    /** Returns the text of a value, or the message of the exception the driver throws for it. */
    private static String text(ResultSet rs, int column) {
        try {
            return rs.getString(column);
        } catch (SQLException | RuntimeException e) {
            return "DRIVER ERROR " + e.getMessage().split("\n")[0];
        }
    }

    /**
     * Collects the differences between DuckDB and the expected type and text
     * of expressions in one time zone, and fails with all of them at once.
     */
    private static final class Checks {

        private final List<String> mismatches = new ArrayList<>();

        Checks(String zone) throws SQLException {
            try (Statement s = connection.createStatement()) {
                s.execute("SET TimeZone = '" + zone + "'");
            }
        }

        /**
         * Expects the select item, which may end in a {@code FROM} clause, to
         * have the type and, unless the text is null, that text as its value
         * in the first row; a null value is written {@code NULL}.
         */
        void value(String item, String type, String text) {
            int from = item.indexOf(" FROM ");
            String expression = from < 0 ? item : item.substring(0, from);
            String rest = from < 0 ? "" : item.substring(from);
            String sql = "SELECT typeof(" + expression + "), CAST(" + expression + " AS VARCHAR)" + rest;
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                String actualType = rs.getString(1);
                String actualText = rs.getString(2);
                String shown = actualText == null ? "NULL" : actualText;
                if (!actualType.equals(type) || text != null && !text.equals(shown)) {
                    mismatches.add(item + ": expected " + type + " " + text + ", DuckDB " + actualType + " "
                            + shown);
                }
            } catch (SQLException e) {
                mismatches.add(item + ": expected " + type + " " + text + ", DuckDB error "
                        + e.getMessage().split("\n")[0]);
            }
        }

        /** Expects DuckDB to reject the select item. */
        void error(String item) {
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT " + item)) {
                rs.next();
                mismatches.add(item + ": expected an error, DuckDB " + rs.getString(1));
            } catch (SQLException e) {
                System.out.println("PROBE error " + item + " -> " + e.getMessage().split("\n")[0]);
            }
        }

        void done() {
            assertEquals(List.of(), mismatches);
        }
    }
}
