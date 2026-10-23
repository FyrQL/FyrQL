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

package net.fyrql.test.flight;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Conversions;
import net.fyrql.flight.FlightSqlServer;
import net.fyrql.flight.ServerConfig;
import net.fyrql.jdbc.AllTypesTable;
import net.fyrql.test.Rows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every type of {@link AllTypesTable} through the Flight SQL server and the
 * Flight SQL JDBC driver of Arrow, which maps them so: a {@code FLOAT} is a
 * {@code Double}, a {@code HUGEINT} a {@code BigDecimal} of at most 38
 * digits, a {@code UUID} a {@code java.util.UUID}, a {@code TIMESTAMP} a
 * {@code java.sql.Timestamp} moved by the offset of the time zone of the JVM
 * (with a calendar of UTC, {@code getTimestamp} gives it as it is), a
 * {@code LIST} a {@code java.sql.Array} whose
 * elements are the values of Arrow Java, and a {@code STRUCT} or
 * {@code MAP} a map of the type {@code JAVA_OBJECT}, in which a field
 * that is {@code NULL} is missing. A {@code HUGEINT} of 39 digits does not
 * fit Arrow and fails with the message of FyrQL.
 */
class FlightSqlJdbcDriverTest {

    private static final BigInteger MAX_DECIMAL_38 = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

    private static FyrQL engine;
    private static FlightSqlServer server;
    private static Connection connection;

    @BeforeAll
    static void setUp() throws Exception {
        int hi = AllTypesTable.SCHEMA.indexOf("hi");
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : AllTypesTable.ROWS) {
            Object[] copy = row.clone();
            if (copy[hi] != null && ((BigInteger) copy[hi]).bitLength() > 126) {
                copy[hi] = MAX_DECIMAL_38;
            }
            rows.add(copy);
        }
        engine = FyrQL.builder()
                .register(MemoryConnector.builder("mem").table("t", AllTypesTable.SCHEMA, rows).build())
                .register(AllTypesTable.connector("wide"))
                .build();
        server = FlightSqlServer.builder(engine).config(ServerConfig.defaults().withPort(0)).build().start();
        connection = DriverManager.getConnection("jdbc:arrow-flight-sql://localhost:" + server.port()
                + "/?useEncryption=false");
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
            if (server != null) {
                server.close();
            }
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }

    @Test
    void everyTypeComesThroughTheDriverOfArrow() throws SQLException {
        Schema schema = AllTypesTable.SCHEMA;
        List<Object[]> expected = Rows.internal(schema, engine.rows("SELECT * FROM mem.t"));
        // The driver gives a TIME as a java.sql.Time, of milliseconds.
        int tm = schema.indexOf("tm");
        for (Object[] row : expected) {
            row[tm] = row[tm] == null ? null : (Long) row[tm] / 1000 * 1000;
        }
        List<Object[]> actual = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM mem.t")) {
            ResultSetMetaData md = rs.getMetaData();
            assertEquals(Types.OTHER, md.getColumnType(schema.indexOf("u") + 1));
            assertEquals(Types.ARRAY, md.getColumnType(schema.indexOf("li") + 1));
            assertEquals(Types.JAVA_OBJECT, md.getColumnType(schema.indexOf("st") + 1));
            assertEquals(Types.JAVA_OBJECT, md.getColumnType(schema.indexOf("m") + 1));
            while (rs.next()) {
                Object[] row = new Object[schema.size()];
                for (int c = 0; c < row.length; c++) {
                    if (schema.type(c) == DataType.TIMESTAMP) {
                        Timestamp t = rs.getTimestamp(c + 1, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                        row[c] = t == null ? null : Conversions.toMicros(t.toLocalDateTime());
                    } else {
                        row[c] = normalize(rs.getObject(c + 1), schema.type(c));
                    }
                }
                actual.add(row);
            }
        }
        assertEquals(comparable(expected), comparable(actual));
    }

    @Test
    void eachConnectionOfTheDriverOfArrowIsASessionOfItsOwn() throws SQLException {
        String url = "jdbc:arrow-flight-sql://localhost:" + server.port() + "/?useEncryption=false";
        try (Connection berlin = DriverManager.getConnection(url);
                Connection tokyo = DriverManager.getConnection(url);
                Statement b = berlin.createStatement();
                Statement t = tokyo.createStatement()) {
            b.executeUpdate("SET TimeZone = 'Europe/Berlin'");
            t.executeUpdate("SET TimeZone = 'Asia/Tokyo'");
            assertEquals("Europe/Berlin", first(b, "SHOW TimeZone"));
            assertEquals("Asia/Tokyo", first(t, "SHOW TimeZone"));
            String now = "SELECT CAST(TIMESTAMPTZ '2024-01-01 12:00:00+00' AS VARCHAR)";
            assertEquals("2024-01-01 13:00:00+01", first(b, now));
            assertEquals("2024-01-01 21:00:00+09", first(t, now));
            b.executeUpdate("USE wide");
            assertEquals("wide.main", first(b, "SHOW search_path"));
            assertEquals("", first(t, "SHOW search_path"));
        }
    }

    private static String first(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void aHugeintOf39DigitsDoesNotFitArrow() {
        SQLException e = assertThrows(SQLException.class, () -> {
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT hi FROM wide.t")) {
                while (rs.next()) {
                    rs.getObject(1);
                }
            }
        });
        assertTrue(String.valueOf(e.getMessage()).contains("has more than 38 digits"), e.getMessage());
    }

    /** Turns a value of the driver, or of Arrow Java inside an array or map, into the value of FyrQL. */
    private static Object normalize(Object v, DataType type) throws SQLException {
        if (v == null) {
            return null;
        }
        return switch (type) {
            case DataType.Decimal d -> Values.toDecimal((BigDecimal) v, d);
            case DataType.ListType l -> {
                Object[] items = v instanceof Array a ? (Object[]) a.getArray() : ((List<?>) v).toArray();
                List<Object> values = new ArrayList<>(items.length);
                for (Object item : items) {
                    values.add(normalize(item, l.element()));
                }
                yield values;
            }
            case DataType.Struct s -> {
                Map<?, ?> fields = (Map<?, ?>) v;
                List<Object> values = new ArrayList<>(s.fields().size());
                for (DataType.Struct.Field f : s.fields()) {
                    values.add(normalize(fields.get(f.name()), f.type()));
                }
                yield values;
            }
            case DataType.MapType m -> {
                Map<Object, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    values.put(normalize(e.getKey(), m.key()), normalize(e.getValue(), m.value()));
                }
                yield values;
            }
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> (Boolean) v;
                case TINYINT -> ((Number) v).byteValue();
                case SMALLINT -> ((Number) v).shortValue();
                case INTEGER -> ((Number) v).intValue();
                case BIGINT -> ((Number) v).longValue();
                case HUGEINT -> ((BigDecimal) v).toBigIntegerExact();
                case FLOAT -> ((Number) v).floatValue();
                case DOUBLE -> ((Number) v).doubleValue();
                case VARCHAR -> v.toString();
                case BLOB -> (byte[]) v;
                case DATE -> v instanceof java.sql.Date d ? Math.toIntExact(d.toLocalDate().toEpochDay())
                        : ((Number) v).intValue();
                case TIMESTAMP -> Conversions.toMicros((LocalDateTime) v);
                case UUID -> (UUID) v;
                case TIME -> v instanceof java.sql.Time t ? Math.floorMod(t.getTime(), 86_400_000L) * 1000
                        : ((java.time.LocalTime) v).toNanoOfDay() / 1000;
                case TIMESTAMPTZ -> v instanceof java.sql.Timestamp t ? Conversions.toMicros(LocalDateTime.ofInstant(
                        t.toInstant(), java.time.ZoneOffset.UTC)) : Conversions.toMicros((LocalDateTime) v);
                case INTERVAL -> interval(v);
            };
        };
    }

    /** The interval of a PeriodDuration of Arrow, whose class the driver shades. */
    private static net.fyrql.core.value.Interval interval(Object periodDuration) {
        try {
            java.time.Period p = (java.time.Period) periodDuration.getClass().getMethod("getPeriod")
                    .invoke(periodDuration);
            java.time.Duration d = (java.time.Duration) periodDuration.getClass().getMethod("getDuration")
                    .invoke(periodDuration);
            return net.fyrql.core.value.Interval.of((int) p.toTotalMonths(), p.getDays(), d.toNanos() / 1000);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Not a PeriodDuration: " + periodDuration, e);
        }
    }

    /**
     * Returns rows with values that compare by content; a struct with only
     * {@code NULL} fields, which the driver gives as an empty map, stays a
     * list of {@code NULL}.
     */
    private static List<List<Object>> comparable(List<Object[]> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>();
            for (Object v : row) {
                values.add(comparable(v));
            }
            out.add(values);
        }
        return out;
    }

    private static Object comparable(Object value) {
        return switch (value) {
            case null -> null;
            case byte[] bytes -> "0x" + HexFormat.of().formatHex(bytes);
            case List<?> list -> list.stream().map(FlightSqlJdbcDriverTest::comparable).toList();
            case Map<?, ?> map -> map.entrySet().stream().map(e -> List.of(String.valueOf(comparable(e.getKey())),
                    String.valueOf(comparable(e.getValue())))).toList();
            default -> value;
        };
    }
}
