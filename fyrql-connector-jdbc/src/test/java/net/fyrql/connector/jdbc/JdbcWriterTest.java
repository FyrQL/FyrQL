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
import net.fyrql.QueryResult;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writer of the JDBC connector over H2, SQLite and, through the
 * generic dialect, H2: tables of every type the dialect stores are created
 * and filled in batches and read back with their values and
 * {@code NULL}s; a failure rolls back and leaves no table; a type the
 * dialect does not have is refused; the statements and the catalog follow
 * each write.
 */
class JdbcWriterTest {

    private static final TableRef T = TableRef.of("db", "t");

    private static Writer writer(JdbcConnector connector) {
        return connector.writer().orElseThrow();
    }

    /** The table of every type of the module's tests, without {@code HUGEINT} in the generic dialect. */
    private static Schema allTypes(JdbcDialect dialect) {
        Schema all = TestDatabase.allTypes(dialect);
        if (dialect != JdbcDialect.GENERIC) {
            return all;
        }
        List<Schema.Field> fields = new ArrayList<>(all.fields());
        fields.remove(all.indexOf("h"));
        return new Schema(fields);
    }

    private static List<Object[]> allTypesRows(JdbcDialect dialect) {
        List<Object[]> rows = TestDatabase.allTypesRows(dialect);
        if (dialect != JdbcDialect.GENERIC) {
            return rows;
        }
        int h = TestDatabase.allTypes(dialect).indexOf("h");
        List<Object[]> without = new ArrayList<>();
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>(Arrays.asList(row));
            values.remove(h);
            without.add(values.toArray());
        }
        return without;
    }

    /** The rows of a table, read through an engine and ordered by the first column, in the form of vectors. */
    private static List<Object[]> read(JdbcConnector connector, String table) {
        try (FyrQL engine = FyrQL.builder().register(connector).build();
                QueryResult result = engine.sql("SELECT * FROM db." + table + " ORDER BY 1")) {
            List<DataType> types = result.schema().types();
            List<Object[]> rows = result.rows();
            for (Object[] row : rows) {
                for (int c = 0; c < row.length; c++) {
                    row[c] = Values.fromJava(row[c], types.get(c));
                }
            }
            return rows;
        }
    }

    /** A value as a column of a type the database stores it in comes back: a smaller integer or a UUID as text. */
    private static Object stored(Object value, DataType type) {
        if (value instanceof Byte b && type == DataType.SMALLINT) {
            return (short) b;
        }
        if (value instanceof UUID u && type == DataType.VARCHAR) {
            return u.toString();
        }
        return value;
    }

    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "rows");
        for (int r = 0; r < expected.size(); r++) {
            assertTrue(Arrays.deepEquals(expected.get(r), actual.get(r)),
                    "row " + r + ": " + Arrays.deepToString(expected.get(r)) + " != "
                            + Arrays.deepToString(actual.get(r)));
        }
    }

    /** Tells whether the database has a table, asked over a connection of its own. */
    private static boolean exists(TestDatabase db, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.url());
                ResultSet rs = c.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static long count(TestDatabase db, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.url()); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE", "GENERIC"})
    void everyTypeComesBackAsWrittenWithItsNulls(JdbcDialect dialect) {
        TestDatabase db = TestDatabase.create(dialect);
        JdbcConnector connector = db.builder("db").batchRows(2).build();
        Schema schema = allTypes(dialect);
        List<Object[]> rows = allTypesRows(dialect);
        try (Sink sink = writer(connector).create(T, schema)) {
            sink.write(Batch.ofRows(schema, rows.subList(0, 1)));
            sink.write(Batch.ofRows(schema, rows.subList(1, 3)));
            assertEquals(3, sink.commit());
        }
        assertEquals(List.of("t"), connector.catalog().tables(TableRef.MAIN));
        assertEquals(3, connector.catalog().rowCount(T).getAsLong());
        Schema stored = connector.catalog().schema(T);
        List<Object[]> expected = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] e = row.clone();
            for (int c = 0; c < e.length; c++) {
                e[c] = stored(e[c], stored.type(c));
            }
            expected.add(e);
        }
        assertRows(expected, read(connector, "t"));
        if (dialect != JdbcDialect.GENERIC) {
            assertEquals(schema, stored);
        }

        // Rows appended to the table.
        try (Sink sink = writer(connector).append(T)) {
            Object[] fourth = expected.get(0).clone();
            fourth[0] = 4;
            sink.write(Batch.ofRows(stored, List.<Object[]>of(fourth)));
            assertEquals(1, sink.commit());
        }
        assertEquals(4, connector.catalog().rowCount(T).getAsLong());
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE", "GENERIC"})
    void theStatementsAndTheCatalogFollowEachWrite(JdbcDialect dialect) {
        TestDatabase db = TestDatabase.create(dialect);
        JdbcConnector connector = db.connector("db");
        Schema schema = Schema.of(new Schema.Field("id", DataType.INTEGER, false),
                new Schema.Field("s", DataType.VARCHAR));
        assertEquals(List.of(), connector.catalog().tables(TableRef.MAIN));
        try (Sink sink = writer(connector).create(T, schema)) {
            assertEquals(0, sink.commit());
        }
        String name = (dialect == JdbcDialect.SQLITE ? "\"main\"" : "\"PUBLIC\"") + ".\"t\"";
        String varchar = dialect == JdbcDialect.GENERIC ? "VARCHAR(4000)" : "VARCHAR";
        // A column declared INTEGER holds 64 bits in SQLite.
        String integer = dialect == JdbcDialect.SQLITE ? "INT4" : "INTEGER";
        assertEquals(List.of("CREATE TABLE " + name + " (\"id\" " + integer + " NOT NULL, \"s\" " + varchar + ")",
                "INSERT INTO " + name + " (\"id\", \"s\") VALUES (?, ?)"), connector.statements());
        assertEquals(List.of("t"), connector.catalog().tables(TableRef.MAIN));
        assertEquals(schema, connector.catalog().schema(T));
        assertEquals(0, connector.catalog().rowCount(T).getAsLong());

        connector.clearStatements();
        writer(connector).drop(T);
        assertEquals(List.of("DROP TABLE " + name), connector.statements());
        assertEquals(List.of(), connector.catalog().tables(TableRef.MAIN));
    }

    /** A {@code NULL} in a column that is {@code NOT NULL} in the database, in the fifth of six rows. */
    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE", "GENERIC"})
    void aFailureRollsBackAndLeavesNoTable(JdbcDialect dialect) throws SQLException {
        TestDatabase db = TestDatabase.create(dialect);
        JdbcConnector connector = (JdbcConnector) ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("jdbc")).findFirst().orElseThrow()
                .create("db", Map.of("url", db.url(), "dialect", dialect.sql().name(), "batchRows", "2",
                        "recordStatements", "true"), Path.of(""));
        Schema schema = Schema.of(new Schema.Field("id", DataType.INTEGER, false));
        Schema nullable = Schema.of(new Schema.Field("id", DataType.INTEGER));
        List<Object[]> rows = new ArrayList<>();
        for (Integer id : Arrays.asList(1, 2, 3, 4, null, 6)) {
            rows.add(new Object[] {id});
        }
        Sink sink = writer(connector).create(T, schema);
        JdbcSourceException e = assertThrows(JdbcSourceException.class, () -> {
            try (sink) {
                sink.write(Batch.ofRows(nullable, rows));
                sink.commit();
            }
        });
        assertTrue(e.getMessage().startsWith("The JDBC source db fails on INSERT INTO "), e.getMessage());
        assertTrue(e.getMessage().contains(" VALUES (?) in the rows 5 to 6: "), e.getMessage());
        assertFalse(exists(db, "t"));
        assertEquals(List.of(), connector.catalog().tables(TableRef.MAIN));

        // An append that fails leaves the rows as they were.
        try (Sink first = writer(connector).create(T, schema)) {
            first.write(Batch.ofRows(schema, rows.subList(0, 2)));
            first.commit();
        }
        Sink append = writer(connector).append(T);
        assertThrows(JdbcSourceException.class, () -> {
            try (append) {
                append.write(Batch.ofRows(nullable, rows.subList(2, 6)));
                append.commit();
            }
        });
        assertEquals(2, count(db, "t"));
        assertEquals(2, connector.catalog().rowCount(T).getAsLong());
    }

    @Test
    void aTypeTheDialectDoesNotHaveIsRefusedBeforeAnyStatement() {
        Schema huge = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("h", DataType.HUGEINT));
        Schema nested = Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("p", DataType.listOf(DataType.INTEGER)));
        JdbcConnector sqlite = TestDatabase.create(JdbcDialect.SQLITE).connector("db");
        assertEquals("The JDBC source db cannot create db.main.t: sqlite has no type for HUGEINT (column h)",
                assertThrows(IllegalArgumentException.class, () -> writer(sqlite).create(T, huge)).getMessage());
        JdbcConnector generic = TestDatabase.create(JdbcDialect.GENERIC).connector("db");
        assertEquals("The JDBC source db cannot create db.main.t: generic has no portable type for HUGEINT"
                + " (column h)", assertThrows(IllegalArgumentException.class, () -> writer(generic).create(T, huge))
                .getMessage());
        JdbcConnector h2 = TestDatabase.create(JdbcDialect.H2).connector("db");
        assertEquals("The JDBC source db cannot create db.main.t: h2 has no type for INTEGER[] (column p)",
                assertThrows(IllegalArgumentException.class, () -> writer(h2).create(T, nested)).getMessage());
        assertEquals(List.of(), sqlite.statements());
        assertEquals(List.of(), h2.statements());
    }

    /** Statements of FyrQL write through the engine: the count of rows, {@code INSERT}, {@code DROP}. */
    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE", "GENERIC"})
    void theEngineCreatesFillsAndDropsATable(JdbcDialect dialect) {
        TestDatabase db = TestDatabase.create(dialect);
        JdbcConnector connector = db.connector("db");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(3, engine.update("CREATE TABLE db.items AS SELECT * FROM (VALUES (1, 'a', DATE '2026-10-20'),"
                    + " (2, NULL, NULL), (3, 'c', DATE '0001-01-01')) v(id, s, d)"));
            assertEquals(2, engine.update("INSERT INTO db.items (id, s) VALUES (4, 'd'), (5, 'it''s')"));
            assertEquals(3, engine.update("INSERT INTO db.items SELECT id + 10, s, d FROM db.items WHERE id < 4"));
            List<Object[]> rows = engine.rows("SELECT id, s, d FROM db.items ORDER BY id");
            assertEquals(List.of(1, 2, 3, 4, 5, 11, 12, 13), rows.stream().map(r -> r[0]).toList());
            assertEquals(Arrays.asList("a", null, "c", "d", "it's", "a", null, "c"),
                    rows.stream().map(r -> r[1]).toList());
            assertEquals(LocalDate.of(1, 1, 1), rows.get(7)[2]);
            assertEquals("Table with name items already exists", assertThrows(SqlException.class,
                    () -> engine.update("CREATE TABLE db.items (a INTEGER)")).getMessage().split(" at line")[0]);
            assertEquals(0, engine.update("DROP TABLE db.items"));
            assertEquals(0, engine.update("DROP TABLE IF EXISTS db.items"));
        }
    }

    /** A date and a timestamp in a gap of daylight saving time, and before 1582, are written as they are. */
    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE", "GENERIC"})
    void datesAndTimestampsKeepTheirValuesInEveryDefaultTimeZone(JdbcDialect dialect) {
        Schema schema = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("d", DataType.DATE),
                new Schema.Field("ts", DataType.TIMESTAMP));
        List<Object[]> rows = List.of(
                new Object[] {1, day(2026, 3, 29), micros(LocalDateTime.of(2026, 3, 29, 2, 30, 0, 123_456_000))},
                new Object[] {2, day(1, 1, 1), -1L},
                new Object[] {3, day(1582, 10, 10), micros(LocalDateTime.of(1582, 10, 10, 12, 0))});
        TestDatabase db = TestDatabase.create(dialect);
        TimeZone before = TimeZone.getDefault();
        try {
            for (String zone : List.of("Europe/Berlin", "America/Sao_Paulo", "UTC", "Pacific/Apia")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                JdbcConnector connector = db.connector("db");
                try (Sink sink = writer(connector).create(T, schema)) {
                    sink.write(Batch.ofRows(schema, rows));
                    sink.commit();
                }
                assertRows(rows, read(connector, "t"));
                writer(connector).drop(T);
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    void aSharedConnectionKeepsItsAutoCommit() throws SQLException {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        try (Connection c = db.connect()) {
            JdbcConnector connector = JdbcConnector.builder("db").connection(c).build();
            Schema schema = Schema.of(new Schema.Field("id", DataType.INTEGER));
            try (Sink sink = writer(connector).create(T, schema)) {
                sink.write(Batch.ofRows(schema, List.of(new Object[] {1}, new Object[] {2})));
                sink.commit();
            }
            assertTrue(c.getAutoCommit());
            assertFalse(c.isClosed());
            assertEquals(2, count(db, "t"));
        }
    }

    private static int day(int year, int month, int day) {
        return Math.toIntExact(LocalDate.of(year, month, day).toEpochDay());
    }

    private static long micros(LocalDateTime t) {
        return Conversions.toMicros(t);
    }
}
