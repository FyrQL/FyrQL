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
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The types of FyrQL through a result set: the values, the getters and the metadata. */
class TypeRoundTripTest {

    private static final String SCALARS = """
            SELECT true AS b, CAST(1 AS TINYINT) AS ti, CAST(2 AS SMALLINT) AS si, 3 AS i, CAST(4 AS BIGINT) AS bi,
                   CAST('170141183460469231731687303715884105727' AS HUGEINT) AS hi, CAST(1.5 AS FLOAT) AS f,
                   CAST(2.5 AS DOUBLE) AS d, CAST('123.45' AS DECIMAL(10,2)) AS dec, 'abc' AS v,
                   CAST('\\xAA\\x00' AS BLOB) AS bl, DATE '2024-02-29' AS dt,
                   TIMESTAMP '2024-02-29 12:34:56.123456' AS ts,
                   CAST('123e4567-e89b-12d3-a456-426614174000' AS UUID) AS u
            """;

    /** Per column: type code, type name, class, precision, scale, text. */
    private static final List<Object[]> COLUMNS = List.of(
            new Object[] {Types.BOOLEAN, "BOOLEAN", Boolean.class, 1, 0, "true"},
            new Object[] {Types.TINYINT, "TINYINT", Byte.class, 3, 0, "1"},
            new Object[] {Types.SMALLINT, "SMALLINT", Short.class, 5, 0, "2"},
            new Object[] {Types.INTEGER, "INTEGER", Integer.class, 10, 0, "3"},
            new Object[] {Types.BIGINT, "BIGINT", Long.class, 19, 0, "4"},
            new Object[] {Types.NUMERIC, "HUGEINT", BigDecimal.class, 39, 0,
                    "170141183460469231731687303715884105727"},
            new Object[] {Types.REAL, "FLOAT", Float.class, 7, 0, "1.5"},
            new Object[] {Types.DOUBLE, "DOUBLE", Double.class, 15, 0, "2.5"},
            new Object[] {Types.DECIMAL, "DECIMAL(10,2)", BigDecimal.class, 10, 2, "123.45"},
            new Object[] {Types.VARCHAR, "VARCHAR", String.class, Integer.MAX_VALUE, 0, "abc"},
            new Object[] {Types.VARBINARY, "BLOB", byte[].class, Integer.MAX_VALUE, 0, "\\xAA\\x00"},
            new Object[] {Types.DATE, "DATE", Date.class, 10, 0, "2024-02-29"},
            new Object[] {Types.TIMESTAMP, "TIMESTAMP", Timestamp.class, 26, 6, "2024-02-29 12:34:56.123456"},
            new Object[] {Types.OTHER, "UUID", UUID.class, 36, 0, "123e4567-e89b-12d3-a456-426614174000"});

    private Connection c;
    private Statement s;

    @BeforeEach
    void open() throws SQLException {
        c = FyrQLDriver.connect(FyrQL.builder().register(AllTypesTable.connector("mem")).build(), true);
        s = c.createStatement();
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void eachTypeHasItsClassCodeNameAndText() throws Exception {
        try (ResultSet rs = s.executeQuery(SCALARS)) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(COLUMNS.size(), md.getColumnCount());
            assertTrue(rs.next());
            for (int i = 1; i <= COLUMNS.size(); i++) {
                Object[] expected = COLUMNS.get(i - 1);
                String label = md.getColumnLabel(i);
                assertEquals(expected[0], md.getColumnType(i), label);
                assertEquals(expected[1], md.getColumnTypeName(i), label);
                Class<?> type = (Class<?>) expected[2];
                assertEquals(type.getName(), md.getColumnClassName(i), label);
                assertEquals(type, rs.getObject(i).getClass(), label);
                assertEquals(type, Class.forName(md.getColumnClassName(i)), label);
                assertEquals(expected[3], md.getPrecision(i), label);
                assertEquals(expected[4], md.getScale(i), label);
                assertEquals(expected[5], rs.getString(i), label);
                assertFalse(rs.wasNull());
                assertEquals(label, md.getColumnName(i));
                assertEquals("", md.getTableName(i));
                assertTrue(md.isReadOnly(i));
                assertFalse(md.isWritable(i));
                assertTrue(md.getColumnDisplaySize(i) >= String.valueOf(expected[5]).length(), label);
            }
            assertTrue(md.isCaseSensitive(10));
            assertFalse(md.isCaseSensitive(4));
            assertTrue(md.isSigned(4));
            assertFalse(md.isSigned(10));
            assertEquals("22023", assertThrows(SQLException.class, () -> md.getColumnType(15)).getSQLState());
            assertFalse(rs.next());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void theTypedGettersConvert() throws Exception {
        try (ResultSet rs = s.executeQuery(SCALARS)) {
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("b"));
            assertEquals(1, rs.getInt("b"));
            assertEquals(1, rs.getByte("ti"));
            assertEquals(2L, rs.getLong("si"));
            assertEquals(3.0, rs.getDouble("i"));
            assertEquals(new BigDecimal("4"), rs.getBigDecimal("bi"));
            assertEquals(new BigDecimal("170141183460469231731687303715884105727"), rs.getBigDecimal("hi"));
            assertEquals(1.5f, rs.getFloat("f"));
            assertEquals(2, rs.getInt("d"), "a fraction is cut off");
            assertEquals(new BigDecimal("2.5"), rs.getBigDecimal("d"));
            assertEquals(new BigDecimal("123.45"), rs.getBigDecimal("dec"));
            assertEquals(123, rs.getShort("dec"));
            assertEquals(new BigDecimal("123.5"), rs.getBigDecimal("dec", 1));
            assertTrue(rs.getBoolean("dec"));
            assertArrayEquals(new byte[] {(byte) 0xAA, 0}, rs.getBytes("bl"));
            try (InputStream in = rs.getBinaryStream("bl")) {
                assertArrayEquals(new byte[] {(byte) 0xAA, 0}, in.readAllBytes());
            }
            assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), rs.getBytes("v"));
            assertEquals(Date.valueOf("2024-02-29"), rs.getDate("dt"));
            assertEquals(Timestamp.valueOf("2024-02-29 00:00:00"), rs.getTimestamp("dt"));
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"), rs.getTimestamp("ts"));
            assertEquals(Date.valueOf("2024-02-29"), rs.getDate("ts"));
            assertEquals(LocalTime.of(12, 34, 56), rs.getTime("ts").toLocalTime());
            assertEquals(123, rs.getTime("ts").getTime() % 1000, "the time keeps its milliseconds");
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            assertEquals(LocalDate.of(2024, 2, 29).toEpochDay() * 86_400_000L, rs.getDate("dt", utc).getTime());
            assertEquals(Instant.parse("2024-02-29T12:34:56.123456Z"), rs.getTimestamp("ts", utc).toInstant());
            assertEquals("abc", rs.getNString("v"));
        }
    }

    @Test
    void getObjectReadsAsTheGivenClass() throws Exception {
        try (ResultSet rs = s.executeQuery(SCALARS)) {
            assertTrue(rs.next());
            assertEquals(LocalDate.of(2024, 2, 29), rs.getObject("dt", LocalDate.class));
            assertEquals(LocalDateTime.of(2024, 2, 29, 12, 34, 56, 123_456_000), rs.getObject("ts",
                    LocalDateTime.class));
            assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0), rs.getObject("dt", LocalDateTime.class));
            assertEquals(LocalDate.of(2024, 2, 29), rs.getObject("ts", LocalDate.class));
            assertEquals(LocalTime.of(12, 34, 56, 123_456_000), rs.getObject("ts", LocalTime.class));
            assertEquals(new BigInteger("170141183460469231731687303715884105727"), rs.getObject("hi",
                    BigInteger.class));
            assertEquals(BigInteger.valueOf(3), rs.getObject("i", BigInteger.class));
            assertEquals("123.45", rs.getObject("dec", String.class));
            assertEquals(3L, rs.getObject("i", Long.class));
            assertEquals((short) 1, rs.getObject("ti", Short.class));
            assertEquals(2.5f, rs.getObject("d", Float.class));
            assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), rs.getObject("u", UUID.class));
            assertEquals(Timestamp.valueOf("2024-02-29 12:34:56.123456"), rs.getObject("ts", java.util.Date.class));
            assertEquals(Boolean.TRUE, rs.getObject("b", Object.class));
            assertEquals(3, rs.getObject("i", Number.class));
            assertEquals("abc", rs.getObject("v", CharSequence.class));
            assertEquals(rs.getObject("i"), rs.getObject("i", java.util.Map.of()));
            SQLException e = assertThrows(SQLFeatureNotSupportedException.class, () -> rs.getObject("i",
                    Instant.class));
            assertEquals("0A000", e.getSQLState());
            assertEquals("FyrQL does not read a value of type INTEGER as java.time.Instant", e.getMessage());
            e = assertThrows(SQLDataException.class, () -> rs.getObject("dt", Integer.class));
            assertEquals("22018", e.getSQLState());
            assertEquals("The value 2024-02-29 of type DATE cannot be read as an int", e.getMessage());
            assertEquals("22023", assertThrows(SQLException.class, () -> rs.getObject("i", (Class<?>) null))
                    .getSQLState());
        }
    }

    @Test
    void aValueThatDoesNotFitIsAnError() throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT 300 AS i, CAST(1e40 AS DOUBLE) AS d, 'x1' AS v, '42' AS n,"
                + " CAST('170141183460469231731687303715884105727' AS HUGEINT) AS hi, CAST('nan' AS DOUBLE) AS nan,"
                + " DATE '2024-01-01' AS dt, 'yes' AS t")) {
            assertTrue(rs.next());
            SQLException e = assertThrows(SQLDataException.class, () -> rs.getByte("i"));
            assertEquals("22003", e.getSQLState());
            assertEquals("The value 300 of type INTEGER does not fit a byte", e.getMessage());
            assertEquals("22003", assertThrows(SQLException.class, () -> rs.getLong("hi")).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class, () -> rs.getLong("d")).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class, () -> rs.getFloat("d")).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class, () -> rs.getBigDecimal("nan")).getSQLState());
            assertTrue(Double.isNaN(rs.getDouble("nan")));
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getInt("v")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getBoolean("v")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getDate("i")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getTime("dt")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getLong("dt")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> rs.getBytes("i")).getSQLState());
            assertEquals(42, rs.getInt("n"), "text that spells a number is one");
            assertTrue(rs.getBoolean("t"));
        }
    }

    @Test
    void aDecimalReadsAsTheNearestDouble() throws SQLException {
        long[] edges = {0, 1, -1, 5, 12_345, (1L << 53) - 1, 1L << 53, (1L << 53) + 1, -(1L << 53) - 1,
                999_999_999_999_999_999L, Long.MAX_VALUE, Long.MIN_VALUE};
        SplittableRandom random = new SplittableRandom(20261020);
        for (int scale = 0; scale <= 25; scale++) {
            for (long u : edges) {
                assertEquals(BigDecimal.valueOf(u, scale).doubleValue(), JdbcValues.toDouble(u, scale),
                        u + "e-" + scale);
            }
            for (int i = 0; i < 10_000; i++) {
                long u = random.nextLong() >> random.nextInt(64);
                assertEquals(BigDecimal.valueOf(u, scale).doubleValue(), JdbcValues.toDouble(u, scale),
                        u + "e-" + scale);
            }
        }
        try (ResultSet rs = s.executeQuery("SELECT CAST('0.1' AS DECIMAL(18,1)) AS a,"
                + " CAST('-123456789012.345678' AS DECIMAL(18,6)) AS b")) {
            assertTrue(rs.next());
            assertEquals(0.1, rs.getDouble("a"));
            assertEquals(-123456789012.345678, rs.getDouble("b"));
        }
    }

    @Test
    void nullIsNullOrZero() throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT CAST(NULL AS INTEGER) AS i, CAST(NULL AS VARCHAR) AS v,"
                + " CAST(NULL AS DATE) AS dt, CAST(NULL AS BOOLEAN) AS b, 7 AS seven")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt("i"));
            assertTrue(rs.wasNull());
            assertEquals(7, rs.getInt("seven"));
            assertFalse(rs.wasNull());
            assertNull(rs.getObject("i"));
            assertTrue(rs.wasNull());
            assertNull(rs.getString("v"));
            assertNull(rs.getDate("dt"));
            assertNull(rs.getObject("dt", LocalDate.class));
            assertFalse(rs.getBoolean("b"));
            assertEquals(0.0, rs.getDouble("i"));
            assertNull(rs.getBigDecimal("i"));
            assertNull(rs.getBytes("v"));
            assertNull(rs.getCharacterStream("v"));
            assertEquals(ResultSetMetaData.columnNullable, rs.getMetaData().isNullable(1));
        }
    }

    /** Per nested column of {@link AllTypesTable}: label, type code, type name, class, text in the first row. */
    private static final List<Object[]> NESTED = List.of(
            new Object[] {"li", Types.ARRAY, "INTEGER[]", Array.class, "[1, NULL, 3]"},
            new Object[] {"ld", Types.ARRAY, "DATE[]", Array.class, "[2024-02-29, NULL]"},
            new Object[] {"lb", Types.ARRAY, "BLOB[]", Array.class, "[\\x01, NULL]"},
            new Object[] {"sa", Types.ARRAY, "STRUCT(a INTEGER, b VARCHAR)[]", Array.class,
                    "[{'a': 1, 'b': x}, NULL, {'a': NULL, 'b': y}]"},
            new Object[] {"st", Types.STRUCT, "STRUCT(x INTEGER, y VARCHAR, z DATE[])", Struct.class,
                    "{'x': 7, 'y': NULL, 'z': [2024-01-01]}"},
            new Object[] {"m", Types.OTHER, "MAP(VARCHAR, STRUCT(x DATE))", Map.class,
                    "{k1={'x': 2024-02-29}, k2=NULL, k3={'x': NULL}}"},
            new Object[] {"ll", Types.ARRAY, "INTEGER[][]", Array.class, "[[1, 2], NULL, [], [NULL]]"});

    @Test
    void everyColumnOfTheTableOfAllTypesReadsAsItsTypeSays() throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT * FROM mem.t")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(AllTypesTable.SCHEMA.size(), md.getColumnCount());
            for (int r = 0; r < AllTypesTable.ROWS.size(); r++) {
                assertTrue(rs.next());
                Object[] row = AllTypesTable.ROWS.get(r);
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    DataType type = AllTypesTable.SCHEMA.type(i - 1);
                    String label = md.getColumnLabel(i) + " in row " + (r + 1);
                    assertEquals(type.sqlName(), md.getColumnTypeName(i), label);
                    assertEquals(Casts.format(row[i - 1], type), rs.getString(i), label);
                    assertEquals(row[i - 1] == null, rs.wasNull(), label);
                    Object value = rs.getObject(i);
                    assertEquals(row[i - 1] == null, rs.wasNull(), label);
                    if (value == null) {
                        assertNull(row[i - 1], label);
                    } else {
                        assertInstanceOf(Class.forName(md.getColumnClassName(i)), value, label);
                        if (value instanceof Array || value instanceof Struct) {
                            assertEquals(rs.getString(i), value.toString(), label);
                        }
                    }
                }
            }
            assertFalse(rs.next());
        }
        try (ResultSet rs = s.executeQuery("SELECT * FROM mem.t")) {
            ResultSetMetaData md = rs.getMetaData();
            assertTrue(rs.next());
            for (Object[] expected : NESTED) {
                String label = (String) expected[0];
                int i = rs.findColumn(label);
                assertEquals(expected[1], md.getColumnType(i), label);
                assertEquals(expected[2], md.getColumnTypeName(i), label);
                assertEquals(((Class<?>) expected[3]).getName(), md.getColumnClassName(i), label);
                assertEquals(expected[4], rs.getString(i), label);
                assertEquals(Integer.MAX_VALUE, md.getPrecision(i), label);
            }
            assertEquals(38, md.getPrecision(rs.findColumn("dec")));
            assertEquals(10, md.getScale(rs.findColumn("dec")));
            assertEquals(new BigDecimal("1234567890123456789012345678.0123456789"), rs.getBigDecimal("dec"));
        }
    }

    @Test
    void anArrayReadsItsElements() throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT li, ld, lb, sa, ll, i, st FROM mem.t")) {
            assertTrue(rs.next());
            Array a = rs.getArray("li");
            assertFalse(rs.wasNull());
            assertEquals(Types.INTEGER, a.getBaseType());
            assertEquals("INTEGER", a.getBaseTypeName());
            assertEquals("[1, NULL, 3]", a.toString());
            Object elements = a.getArray();
            assertEquals(Integer[].class, elements.getClass());
            assertArrayEquals(new Integer[] {1, null, 3}, (Object[]) elements);
            assertArrayEquals(new Integer[] {null, 3}, (Object[]) a.getArray(2, 2));
            assertArrayEquals(new Integer[] {}, (Object[]) a.getArray(4, 0));
            assertArrayEquals(new Integer[] {1, null, 3}, (Object[]) a.getArray(Map.of()));
            assertEquals("22023", assertThrows(SQLException.class, () -> a.getArray(0, 1)).getSQLState());
            assertEquals("22023", assertThrows(SQLException.class, () -> a.getArray(3, 2)).getSQLState());
            assertEquals("22023", assertThrows(SQLException.class, () -> a.getArray(1, -1)).getSQLState());
            assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class,
                    () -> a.getArray(Map.of("INTEGER", String.class))).getSQLState());
            assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class,
                    () -> a.getResultSet(Map.of("INTEGER", String.class))).getSQLState());

            Array dates = rs.getArray("ld");
            assertEquals(Types.DATE, dates.getBaseType());
            assertArrayEquals(new Date[] {Date.valueOf("2024-02-29"), null}, (Date[]) dates.getArray());

            Object[] blobs = (Object[]) rs.getArray("lb").getArray();
            assertEquals(byte[][].class, blobs.getClass());
            assertArrayEquals(new byte[] {1}, (byte[]) blobs[0]);
            assertNull(blobs[1]);

            Array structs = rs.getArray("sa");
            assertEquals(Types.STRUCT, structs.getBaseType());
            assertEquals("STRUCT(a INTEGER, b VARCHAR)", structs.getBaseTypeName());
            Struct[] three = (Struct[]) structs.getArray();
            assertEquals(3, three.length);
            assertEquals("STRUCT(a INTEGER, b VARCHAR)", three[0].getSQLTypeName());
            assertArrayEquals(new Object[] {1, "x"}, three[0].getAttributes());
            assertNull(three[1]);
            assertArrayEquals(new Object[] {null, "y"}, three[2].getAttributes());

            Array lists = rs.getArray("ll");
            assertEquals(Types.ARRAY, lists.getBaseType());
            assertEquals("INTEGER[]", lists.getBaseTypeName());
            Array[] inner = (Array[]) lists.getArray();
            assertEquals(4, inner.length);
            assertArrayEquals(new Integer[] {1, 2}, (Object[]) inner[0].getArray());
            assertNull(inner[1]);
            assertArrayEquals(new Integer[] {}, (Object[]) inner[2].getArray());
            assertArrayEquals(new Integer[] {null}, (Object[]) inner[3].getArray());

            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getArray("i")).getSQLState());
            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getArray("st")).getSQLState());
            assertInstanceOf(Array.class, rs.getObject("li", Array.class));
            assertEquals(Arrays.asList(1, null, 3), rs.getObject("li", List.class));

            a.free();
            a.free();
            assertEquals("HY010", assertThrows(SQLException.class, a::getArray).getSQLState());
            assertEquals("HY010", assertThrows(SQLException.class, a::getResultSet).getSQLState());
            assertEquals("[1, NULL, 3]", rs.getArray("li").toString(), "each call returns a new array");
        }
    }

    @Test
    void theResultSetOfAnArrayHasAnIndexAndAValue() throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT li, ll FROM mem.t")) {
            assertTrue(rs.next());
            try (ResultSet elements = rs.getArray("li").getResultSet()) {
                ResultSetMetaData md = elements.getMetaData();
                assertEquals(2, md.getColumnCount());
                assertEquals("INDEX", md.getColumnLabel(1));
                assertEquals(Types.BIGINT, md.getColumnType(1));
                assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1));
                assertEquals("VALUE", md.getColumnLabel(2));
                assertEquals(Types.INTEGER, md.getColumnType(2));
                assertEquals(ResultSetMetaData.columnNullable, md.isNullable(2));
                assertEquals(ResultSet.TYPE_FORWARD_ONLY, elements.getType());
                assertNull(elements.getStatement());
                assertEquals(List.of(List.of(1L, 1), Arrays.asList(2L, null), List.of(3L, 3)), rows(elements));
            }
            try (ResultSet elements = rs.getArray("li").getResultSet(2, 1)) {
                assertTrue(elements.next());
                assertEquals(2, elements.getLong("index"));
                assertEquals(0, elements.getInt("value"));
                assertTrue(elements.wasNull());
                assertFalse(elements.next());
            }
            try (ResultSet elements = rs.getArray("ll").getResultSet()) {
                assertEquals(Types.ARRAY, elements.getMetaData().getColumnType(2));
                assertEquals("INTEGER[]", elements.getMetaData().getColumnTypeName(2));
                assertTrue(elements.next());
                assertEquals("[1, 2]", elements.getString(2));
                assertArrayEquals(new Integer[] {1, 2}, (Object[]) elements.getArray(2).getArray());
                assertTrue(elements.next());
                assertNull(elements.getArray(2));
                assertTrue(elements.wasNull());
                assertTrue(elements.next());
                assertEquals("[]", elements.getString(2));
                assertFalse(elements.wasNull());
                assertTrue(elements.next());
                try (ResultSet deeper = elements.getArray("value").getResultSet()) {
                    assertTrue(deeper.next());
                    assertNull(deeper.getObject("value"));
                    assertTrue(deeper.wasNull());
                    assertFalse(deeper.next());
                }
                assertFalse(elements.next());
            }
            assertTrue(rs.next());
            assertNull(rs.getArray("li"));
            assertTrue(rs.wasNull());
            assertTrue(rs.next());
            try (ResultSet elements = rs.getArray("li").getResultSet()) {
                assertFalse(elements.next());
            }
        }
    }

    @Test
    void aStructAndAMapReadTheirValues() throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT st, m, i, li FROM mem.t")) {
            assertTrue(rs.next());
            Struct st = (Struct) rs.getObject("st");
            assertEquals("STRUCT(x INTEGER, y VARCHAR, z DATE[])", st.getSQLTypeName());
            assertEquals("{'x': 7, 'y': NULL, 'z': [2024-01-01]}", st.toString());
            Object[] attributes = st.getAttributes();
            assertEquals(3, attributes.length);
            assertEquals(7, attributes[0]);
            assertNull(attributes[1]);
            assertArrayEquals(new Date[] {Date.valueOf("2024-01-01")}, (Object[]) ((Array) attributes[2]).getArray());
            assertEquals(3, st.getAttributes(Map.of()).length);
            assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class,
                    () -> st.getAttributes(Map.of("x", String.class))).getSQLState());
            assertEquals(st.toString(), rs.getObject("st", Struct.class).toString());
            List<?> asList = rs.getObject("st", List.class);
            assertEquals(Arrays.asList(7, null), asList.subList(0, 2));
            Map<?, ?> asMap = rs.getObject("st", Map.class);
            assertEquals(List.of("x", "y", "z"), List.copyOf(asMap.keySet()));
            assertEquals(7, asMap.get("x"));
            assertTrue(asMap.containsKey("y"));
            assertNull(asMap.get("y"));

            Map<?, ?> m = (Map<?, ?>) rs.getObject("m");
            assertEquals(List.of("k1", "k2", "k3"), List.copyOf(m.keySet()));
            assertArrayEquals(new Object[] {Date.valueOf("2024-02-29")}, ((Struct) m.get("k1")).getAttributes());
            assertTrue(m.containsKey("k2"));
            assertNull(m.get("k2"));
            assertArrayEquals(new Object[] {null}, ((Struct) m.get("k3")).getAttributes());
            assertThrows(UnsupportedOperationException.class, () -> m.remove("k1"));
            assertEquals(m.keySet(), rs.getObject("m", Map.class).keySet());

            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getObject("li", Map.class))
                    .getSQLState());
            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getObject("m", List.class))
                    .getSQLState());
            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getObject("i", List.class))
                    .getSQLState());
            assertEquals("22018", assertThrows(SQLDataException.class, () -> rs.getInt("st")).getSQLState());

            assertTrue(rs.next());
            assertNull(rs.getObject("st"));
            assertTrue(rs.wasNull());
            assertNull(rs.getObject("m", Map.class));
            assertTrue(rs.wasNull());
            assertNull(rs.getString("m"));

            assertTrue(rs.next());
            assertArrayEquals(new Object[] {null, null, null}, ((Struct) rs.getObject("st")).getAttributes());
            assertFalse(rs.wasNull());
            assertEquals(Map.of(), rs.getObject("m"));
            assertEquals("{}", rs.getString("m"));
        }
    }

    /** Returns the rows of a result set, each as a list of its values. */
    private static List<List<Object>> rows(ResultSet rs) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next()) {
            List<Object> row = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
