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
import net.fyrql.sql.parse.Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prepared statements: the parameters, their values and types, and the runs. */
class PreparedStatementTest {

    private Connection c;

    @BeforeEach
    void open() {
        c = FyrQLDriver.connect(FyrQL.builder().register(AllTypesTable.connector("mem")).build(), true);
    }

    @AfterEach
    void close() throws SQLException {
        c.close();
    }

    @Test
    void aParameterIsAQuestionMarkOutsideOfStringsIdentifiersAndComments() throws SQLException {
        String sql = "SELECT ? AS \"?\", '?' AS a, 'it''s ?' AS b, -- ?\n /* ? */ $$ ? $$ AS c, ? AS d";
        Parameters p = Parameters.parse(sql);
        assertEquals(2, p.count());
        assertEquals(sql.replaceFirst("\\?", "1").replace("? AS d", "2 AS d"), p.render(new String[] {"1", "2"}));
        assertEquals(0, Parameters.parse("SELECT '?").count(), "an unterminated string runs to the end");
        assertEquals(0, Parameters.parse("SELECT 1 /* ?").count());
        assertEquals(1, Parameters.parse("SELECT x$$ ? $$ FROM t WHERE y = ?").count(),
                "a $ in an identifier starts no string");
        assertEquals("SELECT 1 WHERE x =7 AND y", Parameters.parse("SELECT 1 WHERE x =?AND y")
                .render(new String[] {"7"}));
        assertEquals("SELECT a 7 b", Parameters.parse("SELECT a?b").render(new String[] {"7"}));
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, 1);
            ps.setInt(2, 2);
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(List.of("?", "a", "b", "c", "d"), labels(md));
            assertEquals(List.of("1 ? it's ?  ?  2"), StatementTest.rows(rs));
        }
    }

    @Test
    void everySetterGivesAValueOfItsType() throws SQLException {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Calendar tokyo = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
        Timestamp ts = Timestamp.valueOf("2024-02-29 12:34:56.123456789");
        List<Object[]> cases = List.<Object[]>of(
                new Object[] {(Setter) ps -> ps.setBoolean(1, true), "BOOLEAN", "true"},
                new Object[] {(Setter) ps -> ps.setByte(1, (byte) -7), "TINYINT", "-7"},
                new Object[] {(Setter) ps -> ps.setShort(1, (short) 300), "SMALLINT", "300"},
                new Object[] {(Setter) ps -> ps.setInt(1, Integer.MIN_VALUE), "INTEGER", "-2147483648"},
                new Object[] {(Setter) ps -> ps.setLong(1, Long.MAX_VALUE), "BIGINT", "9223372036854775807"},
                new Object[] {(Setter) ps -> ps.setFloat(1, 1.5f), "FLOAT", "1.5"},
                new Object[] {(Setter) ps -> ps.setFloat(1, Float.NaN), "FLOAT", "nan"},
                new Object[] {(Setter) ps -> ps.setDouble(1, -0.1), "DOUBLE", "-0.1"},
                new Object[] {(Setter) ps -> ps.setDouble(1, Double.NEGATIVE_INFINITY), "DOUBLE", "-inf"},
                new Object[] {(Setter) ps -> ps.setBigDecimal(1, new BigDecimal("-12.50")), "DECIMAL(4,2)", "-12.50"},
                new Object[] {(Setter) ps -> ps.setString(1, "it's"), "VARCHAR", "it's"},
                new Object[] {(Setter) ps -> ps.setNString(1, "\u00E4"), "VARCHAR", "\u00E4"},
                new Object[] {(Setter) ps -> ps.setBytes(1, new byte[] {(byte) 0xAA, 'a'}), "BLOB", "\\xAAa"},
                new Object[] {(Setter) ps -> ps.setDate(1, Date.valueOf("2024-02-29")), "DATE", "2024-02-29"},
                new Object[] {(Setter) ps -> ps.setDate(1, new Date(Timestamp.valueOf("2024-02-29 23:00:00")
                        .getTime()), tokyo), "DATE", LocalDate.ofInstant(Timestamp.valueOf("2024-02-29 23:00:00")
                        .toInstant(), TimeZone.getTimeZone("Asia/Tokyo").toZoneId()).toString()},
                new Object[] {(Setter) ps -> ps.setTimestamp(1, ts), "TIMESTAMP", "2024-02-29 12:34:56.123456"},
                new Object[] {(Setter) ps -> ps.setTimestamp(1, Timestamp.from(java.time.Instant.parse(
                        "2024-02-29T12:00:00Z")), utc), "TIMESTAMP", "2024-02-29 12:00:00"},
                new Object[] {(Setter) ps -> ps.setObject(1, 'x'), "VARCHAR", "x"},
                new Object[] {(Setter) ps -> ps.setObject(1, new BigInteger("-170141183460469231731687303715884105728")),
                        "HUGEINT", "-170141183460469231731687303715884105728"},
                new Object[] {(Setter) ps -> ps.setObject(1, LocalDate.of(1, 1, 1)), "DATE", "0001-01-01"},
                new Object[] {(Setter) ps -> ps.setObject(1, LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_999)),
                        "TIMESTAMP", "1969-12-31 23:59:59.999999"},
                new Object[] {(Setter) ps -> ps.setObject(1, new java.util.Date(Timestamp.valueOf(
                        "2024-02-29 12:00:00").getTime())), "TIMESTAMP", "2024-02-29 12:00:00"},
                new Object[] {(Setter) ps -> ps.setObject(1, UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
                        "UUID", "123e4567-e89b-12d3-a456-426614174000"},
                new Object[] {(Setter) ps -> ps.setAsciiStream(1, ascii("abc")), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setAsciiStream(1, ascii("abc"), 2), "VARCHAR", "ab"},
                new Object[] {(Setter) ps -> ps.setAsciiStream(1, ascii("abc"), 9L), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> setUnicode(ps), "VARCHAR", "\u00E4b"},
                new Object[] {(Setter) ps -> ps.setBinaryStream(1, ascii("ab")), "BLOB", "ab"},
                new Object[] {(Setter) ps -> ps.setBinaryStream(1, ascii("ab"), 1), "BLOB", "a"},
                new Object[] {(Setter) ps -> ps.setBinaryStream(1, ascii("ab"), 1L), "BLOB", "a"},
                new Object[] {(Setter) ps -> ps.setCharacterStream(1, new StringReader("abc")), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setCharacterStream(1, new StringReader("abc"), 1), "VARCHAR", "a"},
                new Object[] {(Setter) ps -> ps.setCharacterStream(1, new StringReader("abc"), 2L), "VARCHAR", "ab"},
                new Object[] {(Setter) ps -> ps.setNCharacterStream(1, new StringReader("abc")), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setNCharacterStream(1, new StringReader("abc"), 0L), "VARCHAR", ""},
                new Object[] {(Setter) ps -> ps.setClob(1, new SerialClob("abc".toCharArray())), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setClob(1, new StringReader("abc")), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setClob(1, new StringReader("abc"), 1L), "VARCHAR", "a"},
                new Object[] {(Setter) ps -> ps.setNClob(1, new StringReader("abc")), "VARCHAR", "abc"},
                new Object[] {(Setter) ps -> ps.setNClob(1, new StringReader("abc"), 2L), "VARCHAR", "ab"},
                new Object[] {(Setter) ps -> ps.setBlob(1, new SerialBlob(new byte[] {1})), "BLOB", "\\x01"},
                new Object[] {(Setter) ps -> ps.setBlob(1, ascii("ab")), "BLOB", "ab"},
                new Object[] {(Setter) ps -> ps.setBlob(1, ascii("ab"), 1L), "BLOB", "a"});
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            for (Object[] x : cases) {
                ((Setter) x[0]).set(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    assertEquals(x[1], rs.getMetaData().getColumnTypeName(1), (String) x[2]);
                    assertTrue(rs.next());
                    assertEquals(x[2], rs.getString(1));
                }
            }
        }
    }

    @Test
    void aNullObjectSetterGivesANullOfItsType() throws SQLException {
        List<Object[]> cases = List.<Object[]>of(
                new Object[] {(Setter) ps -> ps.setBigDecimal(1, null), "DECIMAL(18,3)"},
                new Object[] {(Setter) ps -> ps.setString(1, null), "VARCHAR"},
                new Object[] {(Setter) ps -> ps.setNString(1, null), "VARCHAR"},
                new Object[] {(Setter) ps -> ps.setBytes(1, null), "BLOB"},
                new Object[] {(Setter) ps -> ps.setDate(1, null), "DATE"},
                new Object[] {(Setter) ps -> ps.setTimestamp(1, null), "TIMESTAMP"},
                new Object[] {(Setter) ps -> ps.setAsciiStream(1, null), "VARCHAR"},
                new Object[] {(Setter) ps -> ps.setBinaryStream(1, null), "BLOB"},
                new Object[] {(Setter) ps -> ps.setCharacterStream(1, null), "VARCHAR"},
                new Object[] {(Setter) ps -> ps.setClob(1, (java.sql.Clob) null), "VARCHAR"},
                new Object[] {(Setter) ps -> ps.setBlob(1, (java.sql.Blob) null), "BLOB"},
                new Object[] {(Setter) ps -> ps.setObject(1, null, Types.INTEGER), "INTEGER"},
                new Object[] {(Setter) ps -> ps.setObject(1, null), literalType("NULL")},
                new Object[] {(Setter) ps -> ps.setArray(1, null), literalType("NULL")});
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            for (Object[] x : cases) {
                ((Setter) x[0]).set(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    assertEquals(x[1], rs.getMetaData().getColumnTypeName(1));
                    assertTrue(rs.next());
                    assertNull(rs.getObject(1));
                    assertTrue(rs.wasNull());
                }
            }
        }
    }

    @Test
    void setNullTakesEveryTypeCodeOfTheMapping() throws SQLException {
        String untyped = literalType("NULL");
        Map<Integer, String> codes = Map.ofEntries(Map.entry(Types.BOOLEAN, "BOOLEAN"), Map.entry(Types.BIT, "BOOLEAN"),
                Map.entry(Types.TINYINT, "TINYINT"), Map.entry(Types.SMALLINT, "SMALLINT"),
                Map.entry(Types.INTEGER, "INTEGER"), Map.entry(Types.BIGINT, "BIGINT"),
                Map.entry(Types.NUMERIC, "DECIMAL(18,3)"), Map.entry(Types.DECIMAL, "DECIMAL(18,3)"),
                Map.entry(Types.REAL, "FLOAT"), Map.entry(Types.FLOAT, "DOUBLE"), Map.entry(Types.DOUBLE, "DOUBLE"),
                Map.entry(Types.CHAR, "VARCHAR"), Map.entry(Types.VARCHAR, "VARCHAR"),
                Map.entry(Types.LONGVARCHAR, "VARCHAR"), Map.entry(Types.NCHAR, "VARCHAR"),
                Map.entry(Types.NVARCHAR, "VARCHAR"), Map.entry(Types.LONGNVARCHAR, "VARCHAR"),
                Map.entry(Types.CLOB, "VARCHAR"), Map.entry(Types.NCLOB, "VARCHAR"), Map.entry(Types.BINARY, "BLOB"),
                Map.entry(Types.VARBINARY, "BLOB"), Map.entry(Types.LONGVARBINARY, "BLOB"),
                Map.entry(Types.BLOB, "BLOB"), Map.entry(Types.DATE, "DATE"), Map.entry(Types.TIMESTAMP, "TIMESTAMP"),
                Map.entry(Types.NULL, untyped), Map.entry(Types.OTHER, untyped), Map.entry(Types.JAVA_OBJECT, untyped),
                Map.entry(Types.ARRAY, untyped), Map.entry(Types.STRUCT, untyped));
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            for (Map.Entry<Integer, String> e : codes.entrySet()) {
                ps.setNull(1, e.getKey());
                assertEquals(e.getValue(), nullType(ps), JDBCType.valueOf(e.getKey()).getName());
            }
            ps.setNull(1, Types.ARRAY, "integer[]");
            assertEquals("INTEGER[]", nullType(ps));
            ps.setNull(1, Types.STRUCT, "STRUCT(a INTEGER, \"b c\" DATE[])");
            assertEquals("STRUCT(a INTEGER, \"b c\" DATE[])", nullType(ps));
            SQLException map = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> ps.setNull(1, Types.STRUCT, "STRUCT(a MAP(VARCHAR, DATE))"));
            assertEquals("FyrQL does not take a parameter of type STRUCT(a MAP(VARCHAR, DATE)): there is no literal "
                    + "of a MAP", map.getMessage());
            ps.setNull(1, Types.INTEGER, "no such type");
            assertEquals("INTEGER", nullType(ps), "a name that is no type of FyrQL leaves the code");
            ps.setNull(1, Types.BIGINT, null);
            assertEquals("BIGINT", nullType(ps));
            for (int code : new int[] {Types.TIME, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
                    Types.REF, Types.ROWID, Types.SQLXML, Types.DATALINK, Types.DISTINCT, Types.REF_CURSOR, 12345}) {
                SQLException e = assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setNull(1, code));
                assertEquals("0A000", e.getSQLState());
            }
            assertEquals("FyrQL has no type for the JDBC type TIME",
                    assertThrows(SQLException.class, () -> ps.setNull(1, Types.TIME)).getMessage());
        }
    }

    @Test
    void setObjectWithATargetTypeConvertsAsACast() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            assertEquals("INTEGER 42", converted(ps, ps2 -> ps2.setObject(1, " 42 ", Types.INTEGER)));
            assertEquals("BIGINT 1", converted(ps, ps2 -> ps2.setObject(1, true, JDBCType.BIGINT)));
            assertEquals("VARCHAR 1.5", converted(ps, ps2 -> ps2.setObject(1, 1.5, Types.VARCHAR)));
            assertEquals("DATE 2024-02-29", converted(ps, ps2 -> ps2.setObject(1, "2024-02-29", Types.DATE)));
            assertEquals("DATE 2024-02-29", converted(ps, ps2 -> ps2.setObject(1,
                    Timestamp.valueOf("2024-02-29 23:59:59"), Types.DATE)));
            assertEquals("TIMESTAMP 2024-02-29 00:00:00", converted(ps, ps2 -> ps2.setObject(1,
                    LocalDate.of(2024, 2, 29), Types.TIMESTAMP)));
            assertEquals("DECIMAL(3,2) 1.24", converted(ps, ps2 -> ps2.setObject(1, new BigDecimal("1.235"),
                    Types.DECIMAL, 2)));
            assertEquals("DECIMAL(3,2) -1.24", converted(ps, ps2 -> ps2.setObject(1, -1.235, JDBCType.NUMERIC, 2)));
            assertEquals("DECIMAL(1,0) 7", converted(ps, ps2 -> ps2.setObject(1, 7, Types.NUMERIC)));
            assertEquals("DECIMAL(4,3) 2.500", converted(ps, ps2 -> ps2.setObject(1, "2.500", Types.DECIMAL)));
            assertEquals("FLOAT 0.25", converted(ps, ps2 -> ps2.setObject(1, "0.25", Types.REAL)));
            assertEquals("DOUBLE 0.25", converted(ps, ps2 -> ps2.setObject(1, 0.25f, Types.FLOAT)));
            assertEquals("BLOB abc", converted(ps, ps2 -> ps2.setObject(1, "abc", Types.VARBINARY)));
            assertEquals("BOOLEAN true", converted(ps, ps2 -> ps2.setObject(1, "true", Types.BIT)));
            assertEquals("SMALLINT 300", converted(ps, ps2 -> ps2.setObject(1, 300, Types.SMALLINT, 5)),
                    "the length of a type that is not a decimal is ignored");
            assertEquals("UUID 123e4567-e89b-12d3-a456-426614174000", converted(ps, ps2 -> ps2.setObject(1,
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), Types.OTHER)));
            assertEquals("INTEGER 5", converted(ps, ps2 -> ps2.setObject(1, 5, Types.JAVA_OBJECT)));
            SQLException e = assertThrows(SQLException.class, () -> ps.setObject(1, "x", Types.INTEGER));
            assertEquals("22018", e.getSQLState());
            assertEquals("The value x of type VARCHAR cannot be read as the type INTEGER", e.getMessage());
            e = assertThrows(SQLException.class, () -> ps.setObject(1, 300, Types.TINYINT));
            assertEquals("22003", e.getSQLState());
            assertEquals("22018", assertThrows(SQLException.class,
                    () -> ps.setObject(1, LocalDate.of(2024, 2, 29), Types.INTEGER)).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class,
                    () -> ps.setObject(1, LocalDate.of(2024, 2, 29), Types.DECIMAL)).getSQLState());
            assertEquals("0A000", assertThrows(SQLException.class,
                    () -> ps.setObject(1, 1, Types.TIME)).getSQLState());
            SQLType vendor = new SQLType() {
                @Override
                public String getName() {
                    return "GEOMETRY";
                }

                @Override
                public String getVendor() {
                    return "x";
                }

                @Override
                public Integer getVendorTypeNumber() {
                    return 1;
                }
            };
            e = assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setObject(1, 1, vendor));
            assertEquals("FyrQL has no type GEOMETRY", e.getMessage());
            assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setObject(1, 1, vendor, 2));
        }
    }

    @Test
    void aBigDecimalKeepsItsDigitsAndScale() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            assertEquals("DECIMAL(3,2) 1.50", converted(ps, ps2 -> ps2.setBigDecimal(1, new BigDecimal("1.50"))));
            assertEquals("DECIMAL(3,2) 0.05", converted(ps, ps2 -> ps2.setBigDecimal(1, new BigDecimal("0.05"))));
            assertEquals("DECIMAL(4,0) 1000", converted(ps, ps2 -> ps2.setBigDecimal(1, new BigDecimal("1E+3"))),
                    "a negative scale becomes zero");
            assertEquals("DECIMAL(1,0) 0", converted(ps, ps2 -> ps2.setBigDecimal(1, BigDecimal.ZERO)));
            assertEquals("DECIMAL(2,1) 1.5", converted(ps, ps2 -> ps2.setBigDecimal(1,
                    new BigDecimal("1.5000000000000000000000000000000000000000"))),
                    "more than 38 digits lose their trailing zeros");
            String max = "1234567890123456789012345678.0123456789";
            assertEquals("DECIMAL(38,10) " + max, converted(ps, ps2 -> ps2.setObject(1, new BigDecimal(max))));
            assertEquals("DECIMAL(38,0) " + "9".repeat(38), converted(ps, ps2 -> ps2.setObject(1,
                    new BigDecimal("9".repeat(38)))));
            for (String tooLarge : new String[] {"1E+38", "1E-38", "0." + "1".repeat(38), "1".repeat(39)}) {
                SQLException e = assertThrows(SQLException.class, () -> ps.setBigDecimal(1, new BigDecimal(tooLarge)));
                assertEquals("22003", e.getSQLState(), tooLarge);
                assertTrue(e.getMessage().endsWith(" does not fit a DECIMAL of at most 38 digits"), e.getMessage());
            }
            assertEquals("22003", assertThrows(SQLException.class,
                    () -> ps.setObject(1, BigInteger.ONE.shiftLeft(127))).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class,
                    () -> ps.setObject(1, LocalDate.MAX)).getSQLState());
            assertEquals("22003", assertThrows(SQLException.class,
                    () -> ps.setObject(1, LocalDateTime.MAX)).getSQLState());
        }
    }

    @Test
    void aClassWithoutATypeIsNotSupported() throws Exception {
        URL url = URI.create("http://localhost/").toURL();
        Array foreign = (Array) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Array.class}, (proxy, method, args) -> null);
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            List<StatementTest.ThrowingCall> calls = List.of(() -> ps.setObject(1, new Object()),
                    () -> ps.setObject(1, LocalTime.NOON), () -> ps.setObject(1, Time.valueOf("12:00:00")),
                    () -> ps.setObject(1, OffsetDateTime.now()), () -> ps.setObject(1, Map.of("k", 1)),
                    () -> ps.setObject(1, List.of(1)), () -> ps.setObject(1, new int[] {1}),
                    () -> ps.setTime(1, Time.valueOf("12:00:00")),
                    () -> ps.setTime(1, Time.valueOf("12:00:00"), Calendar.getInstance()),
                    () -> ps.setTime(1, null), () -> ps.setArray(1, foreign), () -> ps.setRef(1, null),
                    () -> ps.setRowId(1, null), () -> ps.setSQLXML(1, null),
                    () -> ps.setURL(1, url));
            for (StatementTest.ThrowingCall call : calls) {
                SQLException e = assertThrows(SQLFeatureNotSupportedException.class, call::run);
                assertEquals("0A000", e.getSQLState());
            }
            assertEquals("FyrQL does not take a parameter of class java.lang.Object",
                    assertThrows(SQLException.class, () -> ps.setObject(1, new Object())).getMessage());
            assertEquals("FyrQL has no type TIME; set a timestamp or a string",
                    assertThrows(SQLException.class, () -> ps.setObject(1, LocalTime.NOON)).getMessage());
        }
    }

    @Test
    void aParameterNeedsAValueAndAnIndexInRange() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a, ? AS b")) {
            ps.setInt(1, 1);
            assertNull(ps.getMetaData(), "the columns are unknown while a parameter has no value");
            SQLException e = assertThrows(SQLException.class, ps::executeQuery);
            assertEquals("07001", e.getSQLState());
            assertEquals("No value is set for parameter 2", e.getMessage());
            assertEquals("07001", assertThrows(SQLException.class, ps::execute).getSQLState());
            for (int index : new int[] {0, 3, -1}) {
                e = assertThrows(SQLException.class, () -> ps.setInt(index, 1));
                assertEquals("22023", e.getSQLState());
                assertEquals("The parameter index " + index + " is out of range 1 to 2", e.getMessage());
                assertEquals("22023", assertThrows(SQLException.class, () -> ps.setNull(index, Types.INTEGER))
                        .getSQLState());
                assertEquals("22023", assertThrows(SQLException.class, () -> ps.setObject(index, new Object()))
                        .getSQLState(), "the index is checked before the value");
            }
            ps.setString(2, "x");
            assertEquals(List.of("1 x"), StatementTest.rows(ps.executeQuery()));
            ps.clearParameters();
            assertEquals("No value is set for parameter 1",
                    assertThrows(SQLException.class, ps::executeQuery).getMessage());
            assertEquals("22023", assertThrows(SQLException.class,
                    () -> ps.setCharacterStream(1, new StringReader("a"), -2L)).getSQLState());
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 AS one")) {
            SQLException e = assertThrows(SQLException.class, () -> ps.setInt(1, 1));
            assertEquals("The query has no parameters; the index 1 is out of range", e.getMessage());
            assertEquals(List.of("1"), StatementTest.rows(ps.executeQuery()));
            assertEquals("one", ps.getMetaData().getColumnLabel(1));
        }
        assertEquals("22023", assertThrows(SQLException.class, () -> c.prepareStatement(null)).getSQLState());
    }

    @Test
    void theRowsAndTypesAreThoseOfTheQueryWithTheLiterals() throws SQLException {
        String prepared = "SELECT i, v, dt, dec FROM t WHERE (dt = ? AND v = ? OR i = ?) AND ts < ? AND d > ? "
                + "ORDER BY i LIMIT ?";
        String literal = "SELECT i, v, dt, dec FROM t WHERE (dt = DATE '2024-02-29' AND v = 'abc' OR i = 0) "
                + "AND ts < TIMESTAMP '2030-01-01 00:00:00' AND d > -1.0 ORDER BY i LIMIT 5";
        try (Statement s = c.createStatement(); PreparedStatement ps = c.prepareStatement(prepared)) {
            ps.setDate(1, Date.valueOf("2024-02-29"));
            ps.setString(2, "abc");
            ps.setInt(3, 0);
            ps.setTimestamp(4, Timestamp.valueOf("2030-01-01 00:00:00"));
            ps.setDouble(5, -1.0);
            ps.setLong(6, 5);
            ResultSet expected = s.executeQuery(literal);
            ResultSet actual = ps.executeQuery();
            assertEquals(types(expected.getMetaData()), types(actual.getMetaData()));
            List<String> rows = StatementTest.rows(expected);
            assertEquals(2, rows.size());
            assertEquals(rows, StatementTest.rows(actual));
        }
    }

    @Test
    void everyValueOfTheTableOfAllTypesGoesBackInAsItCameOut() throws SQLException {
        try (Statement s = c.createStatement(); PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            ResultSet rs = s.executeQuery("SELECT * FROM t");
            ResultSetMetaData md = rs.getMetaData();
            int row = 0;
            while (rs.next()) {
                row++;
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String type = md.getColumnTypeName(i);
                    Object value = type.equals("HUGEINT") ? rs.getObject(i, BigInteger.class) : rs.getObject(i);
                    String where = "row " + row + ", column " + md.getColumnLabel(i);
                    if (value instanceof Map) {
                        assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setObject(1, value), where);
                        continue;
                    }
                    ps.setObject(1, value);
                    try (ResultSet back = ps.executeQuery()) {
                        assertTrue(back.next());
                        assertEquals(rs.getString(i), back.getString(1), where);
                        if (value != null && (row == 1 || !type.startsWith("DECIMAL"))) {
                            assertEquals(type, back.getMetaData().getColumnTypeName(1), where);
                        }
                    }
                }
            }
            assertEquals(3, row);
        }
    }

    @Test
    void aStatementRunsAgainWithTheValuesItHas() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT i, v FROM t WHERE i >= ? AND v <> ? ORDER BY i")) {
            ps.setInt(1, 0);
            ps.setString(2, "x");
            ResultSetMetaData md = ps.getMetaData();
            assertEquals(List.of("i", "v"), labels(md));
            assertEquals(List.of("INTEGER", "VARCHAR"), types(md));
            assertTrue(ps.execute());
            ResultSet first = ps.getResultSet();
            assertSame(ps, first.getStatement());
            assertEquals(-1, ps.getUpdateCount());
            assertEquals(List.of("0 ", "3 abc"), StatementTest.rows(first));
            ResultSet second = ps.executeQuery();
            ps.setInt(1, 1);
            ResultSet third = ps.executeQuery();
            assertTrue(second.isClosed(), "a new run closes the result set before");
            assertEquals(List.of("3 abc"), StatementTest.rows(third));
            ps.setString(2, "abc");
            assertEquals(List.of(), StatementTest.rows(ps.executeQuery()));
        }
    }

    @Test
    void theParametersHaveTheTypesTheEngineGivesThem() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT i FROM t WHERE i = ? AND v LIKE ? AND dec > ? "
                + "AND dt < CAST(? AS DATE)")) {
            ParameterMetaData pm = ps.getParameterMetaData();
            assertEquals(4, pm.getParameterCount());
            List<String> types = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                types.add(pm.getParameterTypeName(i) + " " + pm.getParameterType(i) + " " + pm.getPrecision(i) + " "
                        + pm.getScale(i) + " " + pm.isSigned(i) + " " + pm.getParameterClassName(i));
                assertEquals(ParameterMetaData.parameterModeIn, pm.getParameterMode(i));
                assertEquals(ParameterMetaData.parameterNullableUnknown, pm.isNullable(i));
            }
            ResultSetMetaData md = c.createStatement().executeQuery("SELECT i, v, dec, dt FROM t").getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                columns.add(md.getColumnTypeName(i) + " " + md.getColumnType(i) + " " + md.getPrecision(i) + " "
                        + md.getScale(i) + " " + md.isSigned(i) + " " + md.getColumnClassName(i));
            }
            assertEquals(columns, types);
            // A value keeps the type of its setter.
            ps.setLong(1, 3);
            ps.setString(2, "%");
            ps.setInt(3, -100);
            ps.setString(4, "2100-01-01");
            assertEquals(1, StatementTest.rows(ps.executeQuery()).size());
            assertEquals("22023", assertThrows(SQLException.class, () -> pm.getParameterType(5)).getSQLState());
        }
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN SELECT i FROM t WHERE i = ?")) {
            assertEquals("INTEGER", ps.getParameterMetaData().getParameterTypeName(1));
        }
    }

    @Test
    void theParametersOfAQueryTheEngineCannotPrepareHaveOnlyACount() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a, '?' AS b, ? AS c")) {
            ParameterMetaData pm = ps.getParameterMetaData();
            assertEquals(2, pm.getParameterCount());
            for (int i = 1; i <= 2; i++) {
                assertEquals(Types.OTHER, pm.getParameterType(i));
                assertEquals("UNKNOWN", pm.getParameterTypeName(i));
                assertEquals("java.lang.Object", pm.getParameterClassName(i));
                assertEquals(ParameterMetaData.parameterModeIn, pm.getParameterMode(i));
                assertEquals(ParameterMetaData.parameterNullableUnknown, pm.isNullable(i));
                assertFalse(pm.isSigned(i));
                assertEquals(0, pm.getPrecision(i));
                assertEquals(0, pm.getScale(i));
            }
            assertEquals("22023", assertThrows(SQLException.class, () -> pm.getParameterType(3)).getSQLState());
            assertTrue(pm.isWrapperFor(ParameterMetaData.class));
            assertSame(pm, pm.unwrap(ParameterMetaData.class));
        }
    }

    @Test
    void theMethodsWithAQueryTextAndUpdatesFail() throws SQLException {
        PreparedStatement ps = c.prepareStatement("SELECT ? AS v");
        List<StatementTest.ThrowingCall> withText = List.of(() -> ps.executeQuery("SELECT 1"),
                () -> ps.execute("SELECT 1"), () -> ps.execute("SELECT 1", Statement.NO_GENERATED_KEYS),
                () -> ps.execute("SELECT 1", new int[] {1}), () -> ps.execute("SELECT 1", new String[] {"a"}),
                () -> ps.executeUpdate("SELECT 1"), () -> ps.executeUpdate("SELECT 1", Statement.NO_GENERATED_KEYS),
                () -> ps.executeUpdate("SELECT 1", new int[] {1}), () -> ps.executeUpdate("SELECT 1", new String[] {}),
                () -> ps.executeLargeUpdate("SELECT 1"), () -> ps.executeLargeUpdate("SELECT 1", 2),
                () -> ps.executeLargeUpdate("SELECT 1", new int[] {1}),
                () -> ps.executeLargeUpdate("SELECT 1", new String[] {}), () -> ps.addBatch("SELECT 1"));
        for (StatementTest.ThrowingCall call : withText) {
            assertEquals("42809", assertThrows(SQLException.class, call::run).getSQLState());
        }
        assertEquals("07001", assertThrows(SQLException.class, ps::addBatch).getSQLState());
        assertEquals("07001", assertThrows(SQLException.class, ps::executeUpdate).getSQLState());
        assertArrayEquals(new int[0], ps.executeBatch());
        ps.setInt(1, 1);
        SQLException e = assertThrows(SQLException.class, ps::executeUpdate);
        assertEquals("42809", e.getSQLState());
        assertEquals("A query returns rows, not a number of rows written; run it with executeQuery or execute",
                e.getMessage());
        assertEquals("42809", assertThrows(SQLException.class, ps::executeLargeUpdate).getSQLState());
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO t (i) VALUES (?)")) {
            insert.setInt(1, 1);
            e = assertThrows(SQLException.class, insert::executeQuery);
            assertEquals("42809", e.getSQLState());
            assertEquals("executeQuery runs queries; run a statement that writes with executeUpdate or execute",
                    e.getMessage());
            assertNull(insert.getMetaData(), "a statement that writes has no result set");
            e = assertThrows(SQLException.class, insert::executeUpdate);
            assertTrue(e.getMessage().startsWith("Table t of the memory source mem holds Java objects and cannot be"
                    + " written"), e.getMessage());
        }
        ps.close();
        for (StatementTest.ThrowingCall call : List.<StatementTest.ThrowingCall>of(() -> ps.setInt(1, 1),
                ps::executeQuery, ps::clearParameters, ps::getMetaData, ps::getParameterMetaData,
                () -> ps.executeQuery("SELECT 1"))) {
            assertEquals("HY010", assertThrows(SQLException.class, call::run).getSQLState());
        }
    }

    @Test
    void anExplainOfAPreparedQueryHasItsPlan() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("EXPLAIN SELECT i FROM t WHERE i = ?")) {
            ps.setInt(1, 3);
            assertEquals(List.of("plan"), labels(ps.getMetaData()));
            List<String> plan = StatementTest.rows(ps.executeQuery());
            assertTrue(String.join("\n", plan).contains("3"), String.join("\n", plan));
        }
    }

    /** Sets the parameters of a statement. */
    private interface Setter {
        void set(PreparedStatement ps) throws SQLException;
    }

    /** Sets the parameter, runs the query and returns the type and the text of the one value. */
    private static String converted(PreparedStatement ps, Setter setter) throws SQLException {
        setter.set(ps);
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getMetaData().getColumnTypeName(1) + " " + rs.getString(1);
        }
    }

    /** Runs the query and returns the type of its one value, which must be {@code NULL}. */
    private static String nullType(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertNull(rs.getObject(1));
            return rs.getMetaData().getColumnTypeName(1);
        }
    }

    /** Returns the type of a literal, from a query of it. */
    private String literalType(String literal) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT " + literal + " AS v")) {
            return rs.getMetaData().getColumnTypeName(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setUnicode(PreparedStatement ps) throws SQLException {
        byte[] bytes = "\u00E4b".getBytes(StandardCharsets.UTF_8);
        setUnicodeStream(ps, bytes);
    }

    @SuppressWarnings("deprecation")
    private static void setUnicodeStream(PreparedStatement ps, byte[] bytes) throws SQLException {
        ps.setUnicodeStream(1, new ByteArrayInputStream(bytes), bytes.length);
    }

    private static ByteArrayInputStream ascii(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> labels(ResultSetMetaData md) throws SQLException {
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            labels.add(md.getColumnLabel(i));
        }
        return labels;
    }

    private static List<String> types(ResultSetMetaData md) throws SQLException {
        List<String> types = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            types.add(md.getColumnTypeName(i));
        }
        return types;
    }
}
