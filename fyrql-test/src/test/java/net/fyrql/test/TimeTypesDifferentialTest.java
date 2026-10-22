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

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code TIME}, {@code TIMESTAMP WITH TIME ZONE} and {@code INTERVAL}
 * compared with DuckDB in fixed queries over a table whose instants lie
 * around the changes to and from summer time in Europe/Berlin in 2024: in
 * the time zones UTC and Europe/Berlin of both engines, and in FyrQL with
 * and without code generation. The types of the columns and the rows must
 * agree; an interval compares by its text, so that equal intervals of
 * other components differ. A second table holds random intervals of all
 * three components with numbers of several types, for the fractions that
 * multiplication, division and {@code avg} carry from one component to the
 * next.
 */
class TimeTypesDifferentialTest {

    private static final Schema T = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("d", DataType.DATE), new Schema.Field("ts", DataType.TIMESTAMP),
            new Schema.Field("tz", DataType.TIMESTAMPTZ), new Schema.Field("tm", DataType.TIME),
            new Schema.Field("iv", DataType.INTERVAL), new Schema.Field("n", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR));

    private static final Schema U = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("iv", DataType.INTERVAL), new Schema.Field("n", DataType.BIGINT),
            new Schema.Field("t", DataType.TINYINT), new Schema.Field("x", DataType.DOUBLE),
            new Schema.Field("m", DataType.decimal(10, 3)), new Schema.Field("g", DataType.INTEGER));

    private static final List<String> ZONES = List.of("UTC", "Europe/Berlin");

    private static final List<String> QUERIES = List.of(
            "SELECT id, d, ts, tz, tm, iv FROM t",
            "SELECT id, CAST(tz AS VARCHAR), CAST(tm AS VARCHAR), CAST(iv AS VARCHAR), CAST(ts AS TIMESTAMPTZ),"
                    + " CAST(d AS TIMESTAMPTZ), CAST(tz AS TIMESTAMP), CAST(tz AS DATE), CAST(ts AS TIME) FROM t",
            "SELECT id, TRY_CAST(s AS TIMESTAMPTZ), TRY_CAST(s AS TIME), TRY_CAST(s AS INTERVAL) FROM t",
            "SELECT id, ts = tz, tz < ts, d = tz, d < tz, tz BETWEEN d AND ts, ts IN (tz) FROM t",
            "SELECT id, tz + iv, tz - iv, iv + tz, tz + INTERVAL '1 day', tz - INTERVAL '1 month',"
                    + " tz - INTERVAL '90 minutes' FROM t",
            // Apart, since DuckDB computes constants that compare equal, as '1 day' and '24 hours', once.
            "SELECT id, tz + INTERVAL '24 hours' FROM t",
            "SELECT id, tz - TIMESTAMPTZ '2024-03-30 12:00:00+01', ts - tz, tz - d, d - tz FROM t",
            "SELECT id, d + iv, ts + iv, ts - iv, tm + iv, tm - iv, iv + iv, iv - iv, -iv, iv * n, n * iv,"
                    + " iv / n, iv * 1.5, iv / 2.5, d - ts, ts - ts, d + tm, tm + d, iv + ts FROM t",
            "SELECT id, d + INTERVAL '1 day', ts - INTERVAL '1 month', tm + INTERVAL '25 hours' FROM t",
            "SELECT id, year(tz), month(tz), day(tz), hour(tz), minute(tz), date_part('timezone', tz),"
                    + " date_part('timezone_hour', tz), dayofweek(tz), epoch(tz), epoch_ms(tz), last_day(tz),"
                    + " monthname(tz), dayname(tz) FROM t",
            "SELECT id, date_trunc('day', tz), date_trunc('hour', tz), date_trunc('month', tz),"
                    + " date_trunc('week', tz), date_trunc('year', tz) FROM t",
            "SELECT id, date_diff('day', tz, TIMESTAMPTZ '2024-06-01 00:00:00+00'),"
                    + " date_diff('hour', tz, TIMESTAMPTZ '2024-06-01 00:00:00+00'),"
                    + " date_diff('month', TIMESTAMPTZ '2024-01-31 23:30:00+00', tz) FROM t",
            "SELECT id, strftime(tz, '%Y-%m-%d %H:%M:%S %z %Z'), strftime(ts, '%Y %z') FROM t",
            // The format first writes an instant in UTC; %z has the seconds of a local mean time.
            "SELECT id, strftime('%Y-%m-%d %H:%M %z', tz) FROM t",
            "SELECT strftime(TIMESTAMPTZ '0001-01-01 00:00:00+00', '%Y-%m-%d %H:%M:%S %z'),"
                    + " CAST(TIMESTAMPTZ '0001-01-01 00:00:00+00' AS VARCHAR) FROM t WHERE id = 0",
            "SELECT string_agg(DISTINCT tz, ',' ORDER BY tz DESC) FROM t",
            // The epoch of an instant from its milliseconds, the weeks of the year and the eras of instants.
            "SELECT id, epoch(tz), date_diff('yearweek', tz, TIMESTAMPTZ '2024-06-01 00:00:00+00'),"
                    + " date_diff('era', tz, TIMESTAMPTZ '2024-06-01 00:00:00+00') FROM t",
            "SELECT id, date_trunc('epoch', iv), date_trunc('week', iv), date_trunc('isoyear', iv),"
                    + " date_trunc('doy', iv), date_trunc('quarter', iv) FROM t",
            // A NULL takes the type of its side.
            "SELECT d - NULL, ts - NULL, tz - NULL, tm - NULL, iv - NULL, NULL - d, NULL - ts, NULL - tz, d + NULL,"
                    + " NULL + d, tz + NULL FROM t WHERE id = 0",
            "SELECT id, hour(tm), minute(tm), second(tm), microsecond(tm), epoch(tm),"
                    + " date_part('millisecond', tm) FROM t",
            "SELECT id, year(iv), month(iv), day(iv), hour(iv), minute(iv), second(iv), epoch(iv),"
                    + " date_trunc('day', iv), date_trunc('month', iv), date_part('quarter', iv) FROM t",
            "SELECT id, age(ts, TIMESTAMP '2020-01-31 00:00:00'), age(tz, TIMESTAMPTZ '2020-01-31 00:00:00+00')"
                    + " FROM t",
            "SELECT id, timezone('UTC', tz), timezone('America/New_York', ts), tz AT TIME ZONE 'Asia/Kolkata'"
                    + " FROM t",
            "SELECT count(DISTINCT iv), min(iv), max(iv), avg(iv), min(tm), max(tm), avg(tm), min(tz), max(tz)"
                    + " FROM t",
            "SELECT count(*) FROM t GROUP BY iv",
            "SELECT count(*), min(id) FROM t GROUP BY tz, tm",
            "SELECT id, sum(n) OVER (ORDER BY tz RANGE BETWEEN INTERVAL '6 hours' PRECEDING AND CURRENT ROW)"
                    + " FROM t",
            "SELECT string_agg(CAST(tz AS VARCHAR), ',' ORDER BY id) FROM t",
            "SELECT id FROM t WHERE tz > '2024-07-01 13:00:00'",
            "SELECT id FROM t WHERE tm BETWEEN '01:00:00' AND '13:00:00'",
            "SELECT id FROM t WHERE iv > INTERVAL '29 days' AND iv <> '30 days'",
            "SELECT id, CASE WHEN n > 0 THEN ts ELSE tz END, coalesce(ts, tz), greatest(d, tz), least(tz, ts)"
                    + " FROM t",
            "SELECT tz FROM t UNION ALL SELECT ts FROM t",
            "SELECT id, make_timestamptz(2024, 3, 31, 2, 30, 0.0), make_time(1, 2, 3.5), to_timestamp(n),"
                    + " to_days(n), to_hours(n), to_months(n) FROM t",
            "SELECT id, INTERVAL '1 year 2 months -3 days 04:05:06', TIME '24:00:00' > tm,"
                    + " TIMESTAMPTZ '2024-10-27 02:30:00' FROM t",
            "SELECT id, current_setting('TimeZone') FROM t",
            "SELECT id, iv / n, iv * n, n * iv, iv / t, iv * t FROM u",
            "SELECT id, iv / x, iv * x, x * iv, iv / m, iv * m FROM u",
            "SELECT g, avg(iv), avg(-iv), avg(iv * 3) FROM u GROUP BY g",
            "SELECT avg(iv), count(iv) FROM u",
            "SELECT id, avg(iv) OVER (PARTITION BY g ORDER BY id ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) FROM u");

    private static final List<FyrQL> ENGINES = new ArrayList<>();
    private static DuckDbReference duck;

    @BeforeAll
    static void setUp() {
        List<Object[]> rows = new ArrayList<>();
        String[][] values = {
            {"2024-03-30", "2024-03-30 12:00:00", "2024-03-30 11:00:00+00", "12:00:00", "1 month", "1", "x"},
            {"2024-03-31", "2024-03-31 02:30:00", "2024-03-31 00:30:00+00", "02:30:00", "30 days", "2",
                "2024-03-31 02:30:00"},
            {"2024-03-31", "2024-03-31 03:30:00", "2024-03-31 01:30:00+00", "23:59:59.999999", "720 hours", "-3",
                "2024-07-01 12:00:00+05:30"},
            {"2024-10-27", "2024-10-27 02:30:00", "2024-10-27 00:30:00+00", "00:00:00", "1 day -1 hour", "4",
                "12:30:00"},
            {"2024-10-27", "2024-10-27 02:30:00", "2024-10-27 01:30:00+00", "13:00:00", "23 hours", "0",
                "1 day 02:00:00"},
            {"2024-07-01", "2024-07-01 12:00:00", "2024-07-01 12:00:00+00", "12:00:00.5", "-1 month 2 days", "5",
                "2024-07-01"},
            {"2024-01-31", "2024-01-31 23:30:00", "2024-01-31 23:30:00+00", "01:00:00", "1 year 1 month 15 days",
                "-1", "epoch"},
            {"1969-12-31", "1969-12-31 23:59:59.5", "1969-12-31 23:59:59.5+00", "06:00:00", "-00:00:00.5", "7",
                "-1 days"},
            {null, null, null, null, null, null, null}};
        int id = 0;
        for (String[] v : values) {
            rows.add(new Object[] {id++, v[0] == null ? null : Conversions.parseDate(v[0]),
                v[1] == null ? null : Conversions.parseTimestamp(v[1]),
                v[2] == null ? null : Conversions.parseTimestampTz(v[2], ZoneOffset.UTC),
                v[3] == null ? null : Conversions.parseTime(v[3]), v[4] == null ? null : Interval.parse(v[4]),
                v[5] == null ? null : Integer.parseInt(v[5]), v[6]});
        }
        List<Object[]> numbers = numbers();
        for (CodegenMode mode : List.of(CodegenMode.OFF, CodegenMode.ALWAYS)) {
            ENGINES.add(FyrQL.builder()
                    .register(MemoryConnector.builder("mem").table("t", T, rows).table("u", U, numbers)
                            .batchSize(3).build())
                    .batchSize(3)
                    .codegen(mode)
                    .build());
        }
        duck = new DuckDbReference();
        duck.load("t", T, List.of(Batch.ofRows(T, rows)));
        duck.load("u", U, List.of(Batch.ofRows(U, numbers)));
    }

    /**
     * The rows of the second table: intervals whose months, days and
     * microseconds are each zero or random, up to about 115 days of
     * microseconds, and numbers without zero, so that no product leaves the
     * range of an interval.
     */
    private static List<Object[]> numbers() {
        Random random = new Random(20261022);
        long[] integers = {1, -1, 2, 3, 7, 13, -24, 99, 187, -561, 592};
        String[] decimals = {"1.500", "-0.370", "0.333", "7.000", "123.456", "-2.750", "0.001"};
        List<Object[]> rows = new ArrayList<>();
        for (int id = 0; id < 40; id++) {
            Interval iv = Interval.of(random.nextInt(4) == 0 ? 0 : random.nextInt(-200, 201),
                    random.nextInt(3) == 0 ? 0 : random.nextInt(-5000, 5001),
                    random.nextInt(4) == 0 ? 0 : random.nextLong(-10_000_000_000_000L, 10_000_000_000_000L));
            long n = integers[random.nextInt(integers.length)];
            byte t = (byte) integers[random.nextInt(8)];
            double x = random.nextInt(3) == 0 ? 1.0 / integers[random.nextInt(integers.length)]
                    : random.nextDouble(0.01, 50) * (random.nextBoolean() ? 1 : -1);
            rows.add(new Object[] {id, iv, n, t, x, new BigDecimal(decimals[random.nextInt(decimals.length)]),
                id % 5});
        }
        rows.add(new Object[] {40, null, null, null, null, null, 0});
        return rows;
    }

    @AfterAll
    static void tearDown() {
        for (FyrQL engine : ENGINES) {
            engine.close();
        }
        duck.close();
    }

    @Test
    void sameTypesAndRowsAsDuckDb() throws SQLException {
        List<String> differences = new ArrayList<>();
        for (String zone : ZONES) {
            duck.execute("SET TimeZone = '" + zone + "'");
            for (FyrQL engine : ENGINES) {
                engine.update("SET TimeZone = '" + zone + "'");
            }
            for (String sql : QUERIES) {
                for (int e = 0; e < ENGINES.size(); e++) {
                    String difference = compare(ENGINES.get(e), sql);
                    if (difference != null) {
                        differences.add("[" + zone + (e == 0 ? "" : ", codegen") + "] " + sql + "\n  " + difference);
                    }
                }
            }
        }
        assertEquals("", String.join("\n", differences));
    }

    @Test
    void settingsOfTheSession() {
        FyrQL engine = ENGINES.get(0);
        engine.update("SET TimeZone = 'utc'");
        assertEquals("UTC", engine.rows("SELECT current_setting('TimeZone')").get(0)[0]);
        engine.update("SET TimeZone 'Asia/Kolkata'");
        assertEquals("Asia/Kolkata", engine.rows("SHOW TimeZone").get(0)[0]);
        assertEquals("2024-07-01 17:30:00+05:30",
                engine.rows("SELECT CAST(TIMESTAMPTZ '2024-07-01 12:00:00Z' AS VARCHAR)").get(0)[0]);
        engine.update("RESET TimeZone");
        assertEquals(java.time.ZoneId.systemDefault(), engine.timeZone());
    }

    /** Runs a query in both engines; {@code null} if they agree, else the difference. */
    private static String compare(FyrQL engine, String sql) throws SQLException {
        PlanNode plan;
        List<Object[]> actual;
        String fyrqlError = null;
        try {
            plan = engine.plan(sql);
            actual = engine.rows(plan);
        } catch (RuntimeException e) {
            plan = null;
            actual = null;
            fyrqlError = e.toString();
        }
        List<String> types = new ArrayList<>();
        List<Object[]> expected = new ArrayList<>();
        String duckError = null;
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int c = 1; c <= md.getColumnCount(); c++) {
                types.add(md.getColumnTypeName(c));
            }
            boolean typed = plan != null && plan.schema().types().stream().map(DataType::sqlName).toList()
                    .equals(types);
            while (rs.next()) {
                Object[] row = new Object[types.size()];
                for (int c = 0; c < row.length; c++) {
                    if (!typed) {
                        row[c] = rs.getObject(c + 1);
                        continue;
                    }
                    DataType type = plan.schema().type(c);
                    // DuckDB's driver gives an INTERVAL as text, the other values as objects.
                    Object value = type == DataType.TIMESTAMP ? rs.getObject(c + 1, LocalDateTime.class)
                            : rs.getObject(c + 1);
                    row[c] = Normalizer.normalize(value, type);
                }
                expected.add(row);
            }
        } catch (SQLException e) {
            duckError = e.getMessage().split("\n")[0];
        }
        if (fyrqlError != null || duckError != null) {
            return fyrqlError != null && duckError != null ? null
                    : "FyrQL: " + (fyrqlError == null ? "runs" : fyrqlError) + "\n  DuckDB: "
                            + (duckError == null ? "runs" : duckError);
        }
        List<String> fyrqlTypes = plan.schema().types().stream().map(DataType::sqlName).toList();
        if (!fyrqlTypes.equals(types)) {
            return "types: DuckDB " + types + ", FyrQL " + fyrqlTypes;
        }
        ResultComparison.Outcome outcome = ResultComparison.compare(plan.schema(), expected, actual, false, 0);
        return outcome.same() ? null : outcome.report();
    }
}
