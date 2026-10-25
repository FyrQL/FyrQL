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

package net.fyrql.pgwire;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The types of FyrQL as types of PostgreSQL, in the text and in the binary format, for results and parameters. */
class TypesTest {

    private static TestServer server;

    /** The first row of {@code t} as PostgreSQL writes its values as text, in the time zone UTC. */
    private static final List<String> TEXT = List.of("t", "1", "2", "3", "4",
            "170141183460469231731687303715884105727", "1.5", "2.5", "1234567890123456789012345678.0123456789", "abc",
            "\\xaa00", "2024-02-29", "2024-02-29 12:34:56.123456", "123e4567-e89b-12d3-a456-426614174000",
            "12:34:56.123456", "2024-02-29 12:34:56.123456+00", "1 year 2 mons -3 days +04:05:06.789", "{1,NULL,3}",
            "{2024-02-29,NULL}", "{\"\\\\x01\",NULL}", "[{'a': 1, 'b': x}, NULL, {'a': NULL, 'b': y}]",
            "{'x': 7, 'y': NULL, 'z': [2024-01-01]}", "{k1={'x': 2024-02-29}, k2=NULL, k3={'x': NULL}}",
            "[[1, 2], NULL, [], [NULL]]");

    /** The names of the types of PostgreSQL of the columns of {@code t}. */
    private static final List<String> TYPES = List.of("bool", "int2", "int2", "int4", "int8", "numeric", "float4",
            "float8", "numeric", "text", "bytea", "date", "timestamp", "uuid", "time", "timestamptz", "interval",
            "_int4", "_date", "_bytea", "text", "text", "text", "text");

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static Connection utc(String parameters) throws SQLException {
        Connection c = server.connect("mem", parameters);
        try (Statement s = c.createStatement()) {
            s.execute("SET TimeZone = 'UTC'");
        }
        return c;
    }

    @Test
    void theTypesAndTheirTexts() throws SQLException {
        Connection c = utc("?preferQueryMode=simple");
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM t")) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> types = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                types.add(md.getColumnTypeName(i));
            }
            assertEquals(TYPES, types);
            assertTrue(rs.next());
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                row.add(rs.getString(i));
            }
            assertEquals(TEXT, row);
            assertEquals(38, md.getPrecision(9));
            assertEquals(10, md.getScale(9));
            assertTrue(rs.next());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                assertNull(rs.getObject(i), md.getColumnName(i));
            }
            assertTrue(rs.next());
            assertEquals("f", rs.getString("b"));
            assertEquals("{}", rs.getString("li"));
            assertEquals("00:00:00", rs.getString("iv"));
            assertFalse(rs.next());
        }
    }

    /** Returns the values of all rows of a query as text, as pgjdbc gives them as objects. */
    private static List<String> objects(ResultSet rs) throws SQLException {
        List<String> values = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            for (int i = 1; i <= n; i++) {
                Object o = rs.getObject(i);
                values.add(o instanceof Array a ? Arrays.deepToString((Object[]) a.getArray())
                        : o instanceof byte[] b ? Arrays.toString(b) : String.valueOf(o));
            }
        }
        rs.close();
        return values;
    }

    @Test
    void theBinaryFormatGivesTheSameValuesAsTheText() throws SQLException {
        String sql = "SELECT b, ti, si, i, bi, hi, f, d, dec, v, bl, dt, ts, u, tm, tz, iv, li, ld, lb FROM t";
        List<String> text;
        try (Statement s = utc("?preferQueryMode=simple").createStatement()) {
            text = objects(s.executeQuery(sql));
        }
        Connection binary = utc("?binaryTransfer=true&prepareThreshold=-1");
        for (int run = 0; run < 2; run++) {
            try (PreparedStatement ps = binary.prepareStatement(sql)) {
                assertEquals(text, objects(ps.executeQuery()), "run " + run);
            }
        }
    }

    @Test
    void parametersOfEveryTypeGoThereAndBack() throws SQLException {
        for (String parameters : List.of("", "?binaryTransfer=true&prepareThreshold=-1")) {
            Connection c = utc(parameters);
            try (PreparedStatement ps = c.prepareStatement("SELECT CAST(? AS INTEGER), CAST(? AS BIGINT),"
                    + " CAST(? AS VARCHAR), CAST(? AS BOOLEAN), CAST(? AS DOUBLE), CAST(? AS DECIMAL(10,2)),"
                    + " CAST(? AS DATE), CAST(? AS TIMESTAMP), CAST(? AS BLOB), CAST(? AS UUID), CAST(? AS TIME),"
                    + " CAST(? AS TIMESTAMPTZ), CAST(? AS INTEGER[]), CAST(? AS SMALLINT), CAST(? AS FLOAT)")) {
                ps.setInt(1, 7);
                ps.setLong(2, 1L << 40);
                ps.setString(3, "a'b");
                ps.setBoolean(4, true);
                ps.setDouble(5, 0.1);
                ps.setBigDecimal(6, new BigDecimal("12.34"));
                ps.setObject(7, LocalDate.of(2024, 2, 29));
                ps.setObject(8, LocalDateTime.of(2024, 2, 29, 1, 2, 3, 456_000_000));
                ps.setBytes(9, new byte[] {1, 2, (byte) 0xFF});
                ps.setObject(10, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
                ps.setObject(11, LocalTime.of(23, 59, 58));
                ps.setObject(12, OffsetDateTime.of(2024, 2, 29, 12, 0, 0, 0, ZoneOffset.ofHours(2)));
                ps.setArray(13, c.createArrayOf("int4", new Integer[] {1, null, 3}));
                ps.setShort(14, (short) -5);
                ps.setFloat(15, 2.5f);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(7, rs.getInt(1));
                    assertEquals(1L << 40, rs.getLong(2));
                    assertEquals("a'b", rs.getString(3));
                    assertTrue(rs.getBoolean(4));
                    assertEquals(0.1, rs.getDouble(5));
                    assertEquals(new BigDecimal("12.34"), rs.getBigDecimal(6));
                    assertEquals(LocalDate.of(2024, 2, 29), rs.getObject(7, LocalDate.class));
                    assertEquals(LocalDateTime.of(2024, 2, 29, 1, 2, 3, 456_000_000),
                            rs.getObject(8, LocalDateTime.class));
                    assertArrayEquals(new byte[] {1, 2, (byte) 0xFF}, rs.getBytes(9));
                    assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), rs.getObject(10));
                    assertEquals(LocalTime.of(23, 59, 58), rs.getObject(11, LocalTime.class));
                    assertEquals(OffsetDateTime.of(2024, 2, 29, 10, 0, 0, 0, ZoneOffset.UTC),
                            rs.getObject(12, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC));
                    assertArrayEquals(new Integer[] {1, null, 3}, (Object[]) rs.getArray(13).getArray());
                    assertEquals(-5, rs.getShort(14));
                    assertEquals(2.5f, rs.getFloat(15));
                }
                for (int i = 1; i <= 15; i++) {
                    ps.setNull(i, Types.NULL);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    for (int i = 1; i <= 15; i++) {
                        assertNull(rs.getObject(i), parameters + " " + i);
                    }
                }
            }
        }
    }

    @Test
    void timestampsAndDatesOfJdbcAndParametersWithoutAType() throws SQLException {
        Connection c = utc("");
        try (PreparedStatement ps = c.prepareStatement("SELECT CAST(? AS TIMESTAMP), CAST(? AS DATE), ?")) {
            ps.setTimestamp(1, Timestamp.valueOf("2024-02-29 01:02:03.5"));
            ps.setDate(2, Date.valueOf("2024-02-29"));
            ps.setString(3, "text");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(Timestamp.valueOf("2024-02-29 01:02:03.5"), rs.getTimestamp(1));
                assertEquals(Date.valueOf("2024-02-29"), rs.getDate(2));
                assertEquals("text", rs.getString(3));
                assertEquals("text", rs.getMetaData().getColumnTypeName(3));
            }
        }
    }
}
