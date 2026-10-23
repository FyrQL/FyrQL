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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** What is particular about PostgreSQL: its own types, its schemas, reading through a cursor. */
class PostgresConnectorTest {

    private static TestDatabase db;

    @BeforeAll
    static void start() {
        var pg = TestDatabases.postgres();
        assumeTrue(pg.isPresent(), "PostgreSQL is off");
        db = pg.get();
        db.execute("DROP TABLE IF EXISTS odd", "DROP VIEW IF EXISTS failing", "DROP SCHEMA IF EXISTS sales CASCADE",
                """
                CREATE TABLE odd (id serial PRIMARY KEY, n numeric, n2 numeric(5, 2), n40 numeric(40, 0),
                    tz timestamptz, t time, iv interval, j jsonb, a int[], c char(3), vc varchar(10), tx text,
                    big bigserial, r real, dp double precision, b bool, bt bytea, u uuid, d date, ts timestamp(3))""",
                """
                INSERT INTO odd (n, n2, n40, tz, t, iv, j, a, c, vc, tx, r, dp, b, bt, u, d, ts) VALUES
                    (1.5, 3.14, 1234567890123456789012345678901234567890, '2020-01-01 12:00:00+02', '13:45:00',
                     '1 day 2 hours', '{"a": 1}', '{1,2,3}', 'ab', 'xyz', 'text', 0.5, 0.25, true, '\\x0102',
                     '123e4567-e89b-12d3-a456-426614174000', '2020-02-29', '2020-01-01 00:00:00.123'),
                    (NULL, NULL, NULL, '2020-01-01 09:59:59+00', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                     NULL, NULL, NULL, NULL, NULL, NULL),
                    (NULL, NULL, NULL, NULL, '24:00:00', '-1 year -2 mons +3 days -04:05:06.789', NULL, NULL, NULL,
                     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)""",
                "CREATE SCHEMA sales",
                "CREATE TABLE sales.orders (id integer, amount numeric(10, 2))",
                "INSERT INTO sales.orders VALUES (1, 10.50), (2, 20.00)",
                "CREATE VIEW sales.big_orders AS SELECT * FROM sales.orders WHERE amount > 15",
                "CREATE VIEW failing AS SELECT i, 1 / (50000 - i) AS x FROM generate_series(1, 100000) AS g(i)");
    }

    @Test
    void theTypesOfPostgresAreReadAsTheNearestTypeOfFyrQL() {
        JdbcConnector connector = db.connector("pg");
        Schema schema = connector.catalog().schema(TableRef.of("pg", "odd"));
        assertEquals(List.of(DataType.INTEGER, DataType.DOUBLE, DataType.decimal(5, 2), DataType.VARCHAR,
                DataType.TIMESTAMPTZ, DataType.TIME, DataType.INTERVAL, DataType.VARCHAR, DataType.VARCHAR,
                DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR, DataType.BIGINT, DataType.FLOAT, DataType.DOUBLE,
                DataType.BOOLEAN, DataType.BLOB, DataType.UUID, DataType.DATE, DataType.TIMESTAMP), schema.types());
        assertFalse(schema.fields().get(0).nullable());
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            List<Object[]> rows = engine.rows("SELECT * FROM odd ORDER BY id");
            Object[] expected = {1, 1.5, new BigDecimal("3.14"), "1234567890123456789012345678901234567890",
                    Instant.parse("2020-01-01T10:00:00Z").atZone(engine.timeZone()).toOffsetDateTime(),
                    LocalTime.of(13, 45),
                    Interval.parse("1 day 02:00:00"),
                    "{\"a\": 1}", "{1,2,3}", "ab ", "xyz", "text", 1L, 0.5f, 0.25, true, new byte[] {1, 2},
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    LocalDate.of(2020, 2, 29), LocalDateTime.of(2020, 1, 1, 0, 0, 0, 123_000_000)};
            assertTrue(Arrays.deepEquals(expected, rows.get(0)), Arrays.deepToString(rows.get(0)));
            Object[] nulls = rows.get(1);
            for (int c = 1; c < nulls.length; c++) {
                if (c != 4 && c != 12) {
                    assertEquals(null, nulls[c], schema.fields().get(c).name());
                }
            }
            // The text of an interval in the style of PostgreSQL, with a sign before each part.
            assertEquals(LocalTime.MAX, rows.get(2)[5]);
            assertEquals(Interval.parse("-1 year -2 months 3 days -04:05:06.789"), rows.get(2)[6]);
        }
    }

    @Test
    void aTimestampWithATimeZoneIsAnInstantThatPostgresCompares() {
        JdbcConnector connector = db.connector("pg");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            // 12:00 at +02 is 10:00 in UTC, after 09:59:59 in UTC; the literal has its offset, whatever the zone of
            // the session of the database.
            List<Object[]> rows = engine.rows("SELECT id FROM odd WHERE tz >= TIMESTAMPTZ '2020-01-01 12:00:00+02'");
            assertEquals(1, rows.size());
            assertEquals(1, rows.get(0)[0]);
            String sent = connector.statements().get(connector.statements().size() - 1);
            assertEquals(engine.pushdown(), sent.contains("WHERE"), sent);
            rows = engine.rows("SELECT id FROM odd WHERE tz IS NOT NULL ORDER BY tz LIMIT 1");
            assertEquals(2, rows.get(0)[0]);
            // A time of day and an interval come back as written.
            assertEquals(1, engine.rows("SELECT id FROM odd WHERE t = TIME '13:45:00'").size());
            assertEquals(List.of(3), engine.rows("SELECT id FROM odd WHERE iv < INTERVAL '0 seconds'").stream()
                    .map(r -> r[0]).toList());
        }
    }

    @Test
    void theCatalogListsPublicAsMainAndTheOtherSchemasByName() {
        JdbcConnector connector = db.connector("pg");
        assertEquals(List.of("main", "sales"), connector.catalog().schemas());
        assertEquals(List.of("failing", "odd"), connector.catalog().tables("main"));
        assertEquals(List.of("big_orders", "orders"), connector.catalog().tables("sales"));
        boolean pushdown;
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            pushdown = engine.pushdown();
            List<Object[]> rows = engine.rows("SELECT o.id, o.amount FROM pg.sales.orders o "
                    + "JOIN pg.sales.big_orders b ON o.id = b.id");
            assertEquals(1, rows.size());
            assertEquals(new BigDecimal("20.00"), rows.get(0)[1]);
        }
        List<String> sent = connector.statements();
        if (pushdown) {
            // The database joins its own tables, in one statement.
            assertEquals(1, sent.size(), sent.toString());
            assertTrue(sent.get(0).contains(" FROM \"sales\".\"orders\") t1 INNER JOIN (SELECT ")
                    && sent.get(0).contains(" FROM \"sales\".\"big_orders\") t2 ON "), sent.get(0));
        } else {
            assertTrue(sent.stream().anyMatch(s -> s.contains("FROM \"sales\".\"orders\" t")), sent.toString());
        }
    }

    @Test
    void aResultIsReadThroughACursorInPartsOfTheFetchSize() {
        JdbcConnector connector = JdbcConnector.builder("pg").url(db.url()).user(db.user()).fetchSize(100)
                .batchSize(1000).build();
        TableRef failing = TableRef.of("pg", "failing");
        ScanPlan plan = connector.plan(failing, ScanRequest.columns(List.of(0, 1)));
        Split split = connector.splits(plan).get(0);
        // The view fails at row 50000; read at once, not even the first batch would come.
        try (BatchReader reader = connector.open(split)) {
            Batch first = reader.next();
            assertNotNull(first);
            assertEquals(1000, first.rowCount());
            RuntimeException e = assertThrows(RuntimeException.class, () -> {
                while (reader.next() != null) {
                    // Read on to the failing row.
                }
            });
            assertTrue(e.getMessage().contains("fails at row 49901 ") && e.getMessage().contains("division by zero"),
                    e.getMessage());
        }
        // The connection of the connector was given back in order: the next statement runs.
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(49_999L, engine.rows("SELECT count(*) FROM failing WHERE i < 50000").get(0)[0]);
        }
    }

    @Test
    void aSharedConnectionKeepsItsSettings() throws SQLException {
        try (Connection c = db.connect()) {
            JdbcConnector connector = JdbcConnector.builder("pg").connection(c).build();
            try (FyrQL engine = FyrQL.builder().register(connector).build()) {
                assertEquals(3, engine.rows("SELECT id FROM odd").size());
            }
            assertTrue(c.getAutoCommit());
            assertFalse(c.isClosed());
        }
    }
}
