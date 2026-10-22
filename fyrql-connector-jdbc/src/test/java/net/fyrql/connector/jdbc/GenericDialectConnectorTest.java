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

package net.fyrql.connector.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.jdbc.JdbcConnector.LimitStyle;
import net.fyrql.connector.jdbc.JdbcConnector.Trust;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.sql.render.GenericDialect;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic dialect over H2: a database without a dialect of its own
 * falls back to it, a statement binds its literals and carries its limit,
 * strings are hints, and dates and timestamps keep their values in every
 * default time zone.
 */
class GenericDialectConnectorTest {

    private static final String TABLE = "\"PUBLIC\".\"items\"";

    /** A table {@code items} with an integer key, a string, a date, a timestamp, a double and a decimal. */
    private static TestDatabase items(TestDatabase db) {
        db.execute("CREATE TABLE \"items\" (\"id\" INTEGER PRIMARY KEY, \"s\" VARCHAR(20), \"d\" DATE, "
                        + "\"ts\" TIMESTAMP(6), \"x\" DOUBLE PRECISION, \"n\" DECIMAL(10,2))",
                "INSERT INTO \"items\" VALUES (1, 'ab', DATE '2026-03-29', TIMESTAMP '2026-03-29 02:30:00.123456', "
                        + "1.5, 1.25), (2, 'AB', DATE '0001-01-01', TIMESTAMP '1969-12-31 23:59:59.999999', 2.5, "
                        + "2.50), (3, 'ab ', DATE '1582-10-10', TIMESTAMP '1582-10-10 12:00:00', NULL, NULL), "
                        + "(4, NULL, NULL, NULL, 4.5, 4.75), (5, '', DATE '1970-01-01', TIMESTAMP '1970-01-01 00:00:00',"
                        + " 5.5, 5.00)");
        return db;
    }

    private static TestDatabase items() {
        return items(TestDatabase.create(JdbcDialect.GENERIC));
    }

    private static List<Integer> ids(FyrQL engine, String sql) {
        return engine.rows(sql).stream().map(r -> (Integer) r[0]).sorted().toList();
    }

    @Test
    void aDatabaseWithoutADialectFallsBackToTheGenericOne() {
        TestDatabase db = items();
        String url = FakeProductDriver.PREFIX + db.url().substring("jdbc:h2:".length());
        JdbcConnector connector = JdbcConnector.builder("db").url(url).recordStatements(true).build();
        assertEquals(JdbcDialect.GENERIC, connector.dialect());
        assertEquals(GenericDialect.DEFAULT, connector.sql(), "the quote from the metadata");
        assertEquals(List.of(TableRef.MAIN), connector.catalog().schemas());
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(List.of(2, 3), ids(engine, "SELECT id FROM items WHERE id > 1 AND id < 4"));
            assertEquals(List.of("SELECT t.\"id\" FROM " + TABLE + " t WHERE ((t.\"id\" > ?) AND (t.\"id\" < ?)) "
                    + "-- parameters: 1, 4"), connector.statements());
        }
    }

    @Test
    void theColumnsComeFromAnEmptyResultWhereTheMetadataListsNone() {
        TestDatabase db = items();
        String url = FakeProductDriver.NO_COLUMNS + db.url().substring("jdbc:h2:".length());
        JdbcConnector connector = JdbcConnector.builder("db").url(url).build();
        Schema expected = JdbcConnector.builder("db").url(db.url()).dialect(JdbcDialect.GENERIC).build().catalog()
                .schema(TableRef.of("db", "items"));
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.DATE, DataType.TIMESTAMP, DataType.DOUBLE,
                DataType.decimal(10, 2)), expected.types());
        assertEquals(expected, connector.catalog().schema(TableRef.of("db", "items")));
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(5, engine.rows("SELECT * FROM items").size());
        }
    }

    @Test
    void stringsAreHintsTheEngineFiltersAgain() {
        TestDatabase db = items();
        JdbcConnector on = db.connector("db");
        try (FyrQL pushing = FyrQL.builder().register(on).pushdown(true).build();
                FyrQL plain = FyrQL.builder().register(db.connector("db")).pushdown(false).build()) {
            Map<String, String> queries = Map.of(
                    "SELECT id FROM items WHERE s = 'ab'", " WHERE (t.\"s\" = ?) -- parameters: 'ab'",
                    "SELECT id FROM items WHERE s IN ('ab', 'AB')", " WHERE (t.\"s\" IN (?, ?)) -- parameters: 'ab', 'AB'",
                    "SELECT id FROM items WHERE s LIKE 'a%'", " WHERE (t.\"s\" LIKE ?) -- parameters: 'a%'",
                    "SELECT id FROM items WHERE s IS NULL", " WHERE (t.\"s\" IS NULL)",
                    "SELECT id FROM items WHERE s = 'ab' AND id > 0", " WHERE ((t.\"id\" > ?) AND (t.\"s\" = ?)) "
                            + "-- parameters: 0, 'ab'",
                    "SELECT id FROM items WHERE s = ''", " t",
                    "SELECT id FROM items WHERE s IS NOT NULL", " t",
                    "SELECT id FROM items WHERE s <> 'ab'", " t",
                    "SELECT id FROM items WHERE s < 'b'", " t",
                    "SELECT id FROM items WHERE s ILIKE 'a%'", " t");
            for (Map.Entry<String, String> q : queries.entrySet()) {
                on.clearStatements();
                assertEquals(ids(plain, q.getKey()), ids(pushing, q.getKey()), q.getKey());
                String sent = on.statements().get(0);
                assertTrue(sent.endsWith(q.getValue()), q.getKey() + ": " + sent);
            }
            // A hint is not taken over: the engine filters again, and keeps the limit.
            assertFalse(pushing.explain("SELECT id FROM items WHERE s = 'ab'").contains("[pushed:"));
            assertEquals(List.of(1), ids(pushing, "SELECT id FROM items WHERE s = 'ab' LIMIT 1"));
            on.clearStatements();
            ids(pushing, "SELECT id FROM items WHERE s = 'ab' LIMIT 1");
            assertFalse(on.statements().get(0).contains("max rows"), on.statements().get(0));
        }
    }

    @Test
    void theFirstRowsAreKeptAsTheSettingsSay() {
        TestDatabase db = items();
        JdbcConnector maxRows = db.connector("db");
        JdbcConnector limit = db.builder("db").limitStyle(LimitStyle.LIMIT).build();
        JdbcConnector fetchFirst = db.builder("db").limitStyle(LimitStyle.FETCH_FIRST).build();
        TestDatabase sqlServer = items(TestDatabase.h2Mode("MSSQLServer"));
        JdbcConnector top = sqlServer.builder("db").quote("[").limitStyle(LimitStyle.TOP).build();
        String query = "SELECT id FROM items WHERE id > 1 LIMIT 2";
        Map<JdbcConnector, String> expected = Map.of(
                maxRows, "SELECT t.\"id\" FROM " + TABLE + " t WHERE (t.\"id\" > ?) -- parameters: 1; max rows: 2",
                limit, "SELECT t.\"id\" FROM " + TABLE + " t WHERE (t.\"id\" > ?) LIMIT 2 -- parameters: 1",
                fetchFirst, "SELECT t.\"id\" FROM " + TABLE + " t WHERE (t.\"id\" > ?) FETCH FIRST 2 ROWS ONLY "
                        + "-- parameters: 1",
                top, "SELECT TOP 2 t.[id] FROM [PUBLIC].[items] t WHERE (t.[id] > ?) -- parameters: 1");
        for (Map.Entry<JdbcConnector, String> e : expected.entrySet()) {
            try (FyrQL engine = FyrQL.builder().register(e.getKey()).build()) {
                assertEquals(2, engine.rows(query).size(), e.getValue());
                assertEquals(List.of(e.getValue()), e.getKey().statements());
                assertTrue(engine.explain(query).contains("[pushed:"), e.getValue());
            }
        }
    }

    @Test
    void theSettingsDecideWhatTheDatabaseEvaluates() {
        TestDatabase db = items();
        JdbcConnector none = db.builder("db").pushdownFilters(false).build();
        JdbcConnector trusting = db.builder("db").trust(Set.of(Trust.DOUBLE, Trust.DECIMAL, Trust.TIMESTAMP)).build();
        JdbcConnector plain = db.connector("db");
        try (FyrQL off = FyrQL.builder().register(none).build();
                FyrQL trusted = FyrQL.builder().register(trusting).build();
                FyrQL standard = FyrQL.builder().register(plain).build()) {
            String query = "SELECT id FROM items WHERE id > 1 AND x > 2 AND n < 5 AND s = 'ab '";
            List<Integer> rows = ids(standard, query);
            assertEquals(List.of(), rows);
            assertEquals(rows, ids(off, query));
            assertEquals(rows, ids(trusted, query));
            assertEquals("SELECT t.\"id\", t.\"s\", t.\"x\", t.\"n\" FROM " + TABLE + " t", none.statements().get(0));
            assertEquals("SELECT t.\"id\", t.\"s\", t.\"x\", t.\"n\" FROM " + TABLE + " t WHERE ((t.\"id\" > ?) AND "
                    + "(t.\"s\" = ?)) -- parameters: 1, 'ab '", plain.statements().get(0));
            assertEquals("SELECT t.\"id\", t.\"s\", t.\"x\", t.\"n\" FROM " + TABLE + " t WHERE ((((t.\"id\" > ?) AND "
                    + "(t.\"x\" > ?)) AND (t.\"n\" < ?)) AND (t.\"s\" = ?)) -- parameters: 1, 2, 5, 'ab '",
                    trusting.statements().get(0));
            assertEquals(List.of(4, 5), ids(trusted, "SELECT id FROM items WHERE x > 4 AND n >= 4.75"));
        }
        // The options belong to the generic dialect only.
        for (JdbcConnector.Builder b : List.of(JdbcConnector.builder("db").url(db.url()).quote("\""),
                JdbcConnector.builder("db").url(db.url()).dialect(JdbcDialect.H2).trust(Set.of(Trust.DOUBLE)),
                JdbcConnector.builder("db").url(db.url()).pushdownFilters(true),
                JdbcConnector.builder("db").url(db.url()).limitStyle(LimitStyle.TOP))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, b::build);
            assertTrue(e.getMessage().contains("options of the generic dialect only"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> db.builder("db").quote("'").build());
    }

    /** A date and a timestamp in a gap of daylight saving time, and before 1582, keep their values in every zone. */
    @Test
    void datesAndTimestampsKeepTheirValuesInEveryDefaultTimeZone() {
        TestDatabase db = items();
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : List.of("Europe/Berlin", "America/Sao_Paulo", "UTC", "Pacific/Apia")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                JdbcConnector connector = db.builder("db").trust(Set.of(Trust.TIMESTAMP)).build();
                try (FyrQL engine = FyrQL.builder().register(connector).build()) {
                    List<Object[]> rows = engine.rows("SELECT id, d, ts FROM items ORDER BY id");
                    assertEquals(day(2026, 3, 29), rows.get(0)[1], zone);
                    assertEquals(micros(LocalDateTime.of(2026, 3, 29, 2, 30, 0, 123_456_000)), rows.get(0)[2], zone);
                    assertEquals(day(1, 1, 1), rows.get(1)[1], zone);
                    assertEquals(-1L, rows.get(1)[2], zone);
                    assertEquals(day(1582, 10, 10), rows.get(2)[1], zone);
                    assertEquals(micros(LocalDateTime.of(1582, 10, 10, 12, 0)), rows.get(2)[2], zone);
                    // The database compares the values bound as parameters as it stores them.
                    assertEquals(List.of(3), ids(engine, "SELECT id FROM items WHERE d = DATE '1582-10-10'"));
                    assertEquals(List.of(2), ids(engine, "SELECT id FROM items WHERE d < DATE '1000-01-01'"));
                    assertEquals(List.of(1), ids(engine,
                            "SELECT id FROM items WHERE ts = TIMESTAMP '2026-03-29 02:30:00.123456'"));
                    assertEquals(List.of(2), ids(engine,
                            "SELECT id FROM items WHERE ts BETWEEN TIMESTAMP '1969-12-31 23:59:59.999999' "
                                    + "AND TIMESTAMP '1969-12-31 23:59:59.999999'"));
                    assertTrue(connector.statements().stream().skip(1).allMatch(s -> s.contains("?")),
                            connector.statements().toString());
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    private static int day(int year, int month, int day) {
        return Math.toIntExact(LocalDate.of(year, month, day).toEpochDay());
    }

    private static long micros(LocalDateTime t) {
        return Conversions.toMicros(t);
    }

    @Test
    void theFactoryReadsTheOptionsOfTheGenericDialect() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream().map(ServiceLoader.Provider::get)
                .filter(f -> f.type().equals("jdbc")).findFirst().orElseThrow();
        TestDatabase db = items(TestDatabase.h2Mode("MSSQLServer"));
        JdbcConnector connector = (JdbcConnector) factory.create("db", Map.of("url", db.url(), "dialect", "generic",
                "pushdown", "none", "quote", "[", "limit", "top", "trust", "decimal, Double"));
        assertEquals(new GenericDialect.Settings("[", GenericDialect.LimitStyle.TOP,
                Set.of(GenericDialect.Trust.DECIMAL, GenericDialect.Trust.DOUBLE), false),
                ((GenericDialect) connector.sql()).settings());
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(List.of(new BigDecimal("1.25")), engine.rows("SELECT n FROM items WHERE id = 1").stream()
                    .map(r -> r[0]).toList());
        }
        JdbcConnector defaults = (JdbcConnector) factory.create("db", Map.of("url", db.url(), "dialect", "generic",
                "quote", "none", "limit", "fetchFirst"));
        assertEquals(new GenericDialect.Settings("", GenericDialect.LimitStyle.FETCH_FIRST, Set.of(), true),
                ((GenericDialect) defaults.sql()).settings());
        for (Map.Entry<String, String> bad : Map.of("pushdown", "all", "limit", "rownum", "trust", "uuid", "quote",
                "'").entrySet()) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> factory.create("db",
                    Map.of("url", db.url(), "dialect", "generic", bad.getKey(), bad.getValue())));
            assertTrue(e.getMessage().contains(bad.getKey()) || e.getMessage().contains("quote"), e.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("db", Map.of("url", db.url(), "dialect", "h2", "limit", "top")));
        assertTrue(e.getMessage().contains("options of the generic dialect only"), e.getMessage());
    }
}
