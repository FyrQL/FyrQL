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
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What is particular about MySQL: its own types, its databases as schemas,
 * reading row by row. Runs in the profile {@code containers}.
 */
class MySqlConnectorTest {

    private static TestDatabase db;

    @BeforeAll
    static void start() {
        var mysql = TestDatabases.mysql();
        assumeTrue(mysql.isPresent(), "MySQL runs in Docker, in the profile containers");
        db = mysql.get();
        db.execute("DROP TABLE IF EXISTS odd", "DROP TABLE IF EXISTS big",
                """
                CREATE TABLE odd (id INT NOT NULL PRIMARY KEY, b1 TINYINT(1), bit1 BIT(1), bit8 BIT(8),
                    tu TINYINT UNSIGNED, iu INT UNSIGNED, bu BIGINT UNSIGNED, mi MEDIUMINT, de DECIMAL(10, 2),
                    f FLOAT, d DOUBLE, vc VARCHAR(10), tx TEXT, e ENUM('x', 'y'), j JSON, vb VARBINARY(4),
                    dt DATE, ts DATETIME(6), tz TIMESTAMP(6) NULL, t TIME)""",
                "SET time_zone = '+02:00'",
                """
                INSERT INTO odd VALUES
                    (1, 1, b'1', b'00000101', 255, 4294967295, 18446744073709551615, -8388608, 12.34, 0.5, 0.25,
                     'abc', 'text', 'y', '{"a": 1}', X'0102', '2020-02-29', '2020-01-01 00:00:00.123456',
                     '2020-01-01 12:00:00', '13:45:00'),
                    (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                     NULL, NULL, '2020-01-01 11:59:59', NULL)""",
                "CREATE TABLE big (n BIGINT NOT NULL)",
                "INSERT INTO big WITH RECURSIVE d (k) AS (SELECT 0 UNION ALL SELECT k + 1 FROM d WHERE k < 9) "
                        + "SELECT a.k + 10 * b.k + 100 * c.k + 1000 * e.k + 10000 * f.k + 1 "
                        + "FROM d a, d b, d c, d e, d f");
    }

    @Test
    void theTypesOfMySqlAreReadAsTheNearestTypeOfFyrQL() {
        JdbcConnector connector = db.connector("my");
        Schema schema = connector.catalog().schema(TableRef.of("my", "odd"));
        assertEquals(List.of(DataType.INTEGER, DataType.BOOLEAN, DataType.BOOLEAN, DataType.BLOB, DataType.SMALLINT,
                DataType.BIGINT, DataType.HUGEINT, DataType.INTEGER, DataType.decimal(10, 2), DataType.FLOAT,
                DataType.DOUBLE, DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR, DataType.BLOB,
                DataType.DATE, DataType.TIMESTAMP, DataType.TIMESTAMP, DataType.VARCHAR), schema.types());
        assertFalse(schema.fields().get(0).nullable());
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            List<Object[]> rows = engine.rows("SELECT * FROM odd ORDER BY id");
            Object[] first = rows.get(0);
            Object[] expected = {1, true, true, new byte[] {5}, (short) 255, 4294967295L,
                    new BigInteger("18446744073709551615"), -8388608, new BigDecimal("12.34"), 0.5f, 0.25, "abc",
                    "text", "y", first[14], new byte[] {1, 2}, LocalDate.of(2020, 2, 29),
                    LocalDateTime.of(2020, 1, 1, 0, 0, 0, 123_456_000), LocalDateTime.of(2020, 1, 1, 10, 0), "13:45:00"};
            assertTrue(Arrays.deepEquals(expected, first), Arrays.deepToString(first));
            assertTrue(((String) first[14]).contains("\"a\""), (String) first[14]);
            Object[] nulls = rows.get(1);
            for (int c = 1; c < nulls.length; c++) {
                if (c != 18) {
                    assertEquals(null, nulls[c], schema.fields().get(c).name());
                }
            }
        }
    }

    @Test
    void aTimestampIsItsInstantInUtcAndFilteredByTheEngine() {
        JdbcConnector connector = db.connector("my");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            // 12:00 at +02 is 10:00 in UTC, after 09:59:59 in UTC.
            List<Object[]> rows = engine.rows("SELECT id FROM odd WHERE tz >= TIMESTAMP '2020-01-01 10:00:00'");
            assertEquals(1, rows.size());
            assertEquals(1, rows.get(0)[0]);
            String sent = connector.statements().get(connector.statements().size() - 1);
            assertFalse(sent.contains("WHERE"), sent);
        }
    }

    @Test
    void theDatabasesOfTheServerAreTheSchemas() {
        JdbcConnector connector = db.connector("my");
        List<String> schemas = connector.catalog().schemas();
        assertEquals("main", schemas.get(0));
        assertFalse(schemas.contains("mysql") || schemas.contains("information_schema")
                || schemas.contains("performance_schema") || schemas.contains("sys"), schemas.toString());
        assertEquals(List.of("big", "odd"), connector.catalog().tables("main"));
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(2, engine.rows("SELECT id FROM my.main.odd").size());
        }
        String sent = connector.statements().get(0);
        String database = db.url().substring(db.url().lastIndexOf('/') + 1, db.url().indexOf('?'));
        assertTrue(sent.contains("FROM `" + database + "`.`odd` t"), sent);
    }

    @Test
    void aLargeResultIsStreamed() {
        JdbcConnector connector = JdbcConnector.builder("my").url(db.url()).user(db.user()).password(db.password())
                .batchSize(1000).recordStatements(true).build();
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            Object[] row = engine.rows("SELECT count(*), sum(n), min(n), max(n) FROM big").get(0);
            assertEquals(100_000L, row[0]);
            assertEquals(5_000_050_000L, ((Number) row[1]).longValue());
            assertEquals(1L, row[2]);
            assertEquals(100_000L, row[3]);
            // The connection was given back in order after the streamed result: the next statement runs.
            assertEquals(List.of(100_000L, 99_999L),
                    engine.rows("SELECT n FROM big WHERE n > 99990 ORDER BY n DESC LIMIT 2").stream()
                            .map(r -> r[0]).toList());
        }
        assertTrue(connector.statements().get(1).endsWith(" LIMIT 2"), connector.statements().get(1));
    }
}
