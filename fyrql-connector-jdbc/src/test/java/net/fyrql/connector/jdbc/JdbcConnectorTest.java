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
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcConnectorTest {

    private static final String ALL = "all_types";

    private static TestDatabase allTypes(JdbcDialect dialect) {
        TestDatabase db = TestDatabase.create(dialect);
        db.load(ALL, TestDatabase.allTypes(dialect), TestDatabase.allTypesRows(dialect));
        return db;
    }

    /** Reads a whole table through the interface of a connector, batch by batch. */
    private static List<Batch> readAll(JdbcConnector connector, TableRef table) {
        Schema schema = connector.catalog().schema(table);
        ScanPlan plan = connector.plan(table,
                ScanRequest.columns(IntStream.range(0, schema.size()).boxed().toList()));
        List<Batch> batches = new ArrayList<>();
        for (Split split : connector.splits(plan)) {
            try (BatchReader reader = connector.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    batches.add(b);
                }
            }
        }
        return batches;
    }

    private static List<Object[]> rows(List<Batch> batches) {
        List<Object[]> rows = new ArrayList<>();
        for (Batch b : batches) {
            rows.addAll(b.rows());
        }
        return rows;
    }

    private static void assertRows(List<Object[]> expected, List<Object[]> actual) {
        assertEquals(expected.size(), actual.size(), "rows");
        for (int r = 0; r < expected.size(); r++) {
            assertTrue(Arrays.deepEquals(expected.get(r), actual.get(r)),
                    "row " + r + ": " + Arrays.deepToString(expected.get(r)) + " != "
                            + Arrays.deepToString(actual.get(r)));
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void everyTypeIsReadBackWithItsNulls(JdbcDialect dialect) {
        TestDatabase db = allTypes(dialect);
        JdbcConnector connector = db.connector("db");
        assertEquals(dialect, connector.dialect());
        assertEquals(List.of(TableRef.MAIN), connector.catalog().schemas());
        assertEquals(List.of(ALL), connector.catalog().tables(TableRef.MAIN));
        TableRef table = TableRef.of("db", ALL);
        assertEquals(TestDatabase.allTypes(dialect), connector.catalog().schema(table));
        assertRows(TestDatabase.allTypesRows(dialect), rows(readAll(connector, table)));
        String schema = dialect == JdbcDialect.H2 ? "\"PUBLIC\"" : "\"main\"";
        assertEquals(List.of("SELECT t.\"id\", t.\"b\", t.\"ti\", t.\"si\", t.\"i\", t.\"bi\", t.\"de\", t.\"f\", "
                + "t.\"d\", t.\"s\", t.\"bl\", t.\"dt\", t.\"ts\", t.\"u\""
                + (dialect == JdbcDialect.H2 ? ", t.\"h\", t.\"dw\"" : "") + " FROM " + schema + ".\"all_types\" t"),
                connector.statements());
        assertThrows(IllegalArgumentException.class, () -> connector.catalog().schema(TableRef.of("db", "nope")));
        assertThrows(IllegalArgumentException.class, () -> connector.catalog().schema(TableRef.of("db", "ALL_TYPES")));
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void aScanIsOneFlatStatementWithWhatTheDatabaseTakesOver(JdbcDialect dialect) {
        TestDatabase db = allTypes(dialect);
        JdbcConnector connector = db.connector("db");
        try (FyrQL engine = FyrQL.builder().register(connector).pushdown(true).build()) {
            List<Object[]> rows = engine.rows("SELECT s, i FROM all_types WHERE i > -5 AND d > 1 ORDER BY id LIMIT 2");
            assertRows(List.<Object[]>of(new Object[] {"x'y\\z", 3}), rows);
            String table = dialect == JdbcDialect.H2 ? "\"PUBLIC\".\"all_types\"" : "\"main\".\"all_types\"";
            String five = dialect == JdbcDialect.H2 ? "CAST(-5 AS INTEGER)" : "-5";
            // The filter on the double stays with the engine, so the limit does too; the order goes to the database.
            assertEquals(List.of("SELECT t.\"id\", t.\"i\", t.\"d\", t.\"s\" FROM " + table + " t WHERE (t.\"i\" > "
                    + five + ") ORDER BY t.\"id\" ASC NULLS LAST"), connector.statements());
            assertTrue(engine.explain("SELECT s FROM all_types WHERE i > -5 LIMIT 2").contains("[pushed: WHERE"));
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void theDialectDecidesWhatTheDatabaseTakesOver(JdbcDialect dialect) {
        TestDatabase db = allTypes(dialect);
        JdbcConnector on = db.connector("db");
        JdbcConnector off = db.connector("db");
        boolean sqlite = dialect == JdbcDialect.SQLITE;
        try (FyrQL pushing = FyrQL.builder().register(on).pushdown(true).build();
                FyrQL plain = FyrQL.builder().register(off).pushdown(false).build()) {
            Map<String, String> queries = Map.of(
                    // Strings are equal alike everywhere, but only SQLite orders them as FyrQL does.
                    "SELECT id FROM all_types WHERE s = 'x''y\\z'", "WHERE (t.\"s\" = 'x''y\\z')",
                    "SELECT s FROM all_types ORDER BY s DESC LIMIT 2",
                    sqlite ? "ORDER BY t.\"s\" DESC NULLS LAST LIMIT 2" : "FROM \"PUBLIC\".\"all_types\" t",
                    // SQLite stores a decimal as a double, so the engine compares it.
                    "SELECT id FROM all_types WHERE de > 0", sqlite ? "FROM \"main\".\"all_types\" t" : "WHERE",
                    "SELECT id FROM all_types WHERE f < 0", "\"all_types\" t",
                    "SELECT id, i FROM all_types ORDER BY i DESC NULLS FIRST LIMIT 2", "DESC NULLS FIRST LIMIT 2",
                    "SELECT id FROM all_types WHERE b IS NOT NULL AND ti IN (1, 2) OR si BETWEEN 0 AND 3", "WHERE",
                    "SELECT id FROM all_types WHERE dt >= DATE '2024-01-01'", sqlite ? "\"all_types\" t" : "WHERE",
                    "SELECT id FROM all_types WHERE bi IS DISTINCT FROM 4",
                    sqlite ? "WHERE (t.\"bi\" IS NOT 4)" : "WHERE (t.\"bi\" IS DISTINCT FROM CAST(4 AS INTEGER))",
                    "SELECT count(*) FROM all_types WHERE i IS NULL", "WHERE (t.\"i\" IS NULL)",
                    // A UUID stays with the engine.
                    "SELECT count(*) FROM all_types WHERE u IS NULL", "\"all_types\" t");
            for (Map.Entry<String, String> q : queries.entrySet()) {
                on.clearStatements();
                List<Object[]> expected = plain.rows(q.getKey());
                assertRows(expected, pushing.rows(q.getKey()));
                assertEquals(1, on.statements().size(), q.getKey());
                String sent = on.statements().get(0);
                assertTrue(sent.endsWith(q.getValue()) || q.getValue().startsWith("WHERE") && sent.contains(q.getValue()),
                        q.getKey() + " sends " + sent);
                if (q.getValue().endsWith("\"all_types\" t")) {
                    assertFalse(sent.contains("WHERE") || sent.contains("ORDER BY"), sent);
                }
            }
            for (String sent : off.statements()) {
                assertFalse(sent.contains("WHERE") || sent.contains("ORDER BY") || sent.contains("LIMIT"), sent);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void theDatabaseAggregatesWhatItComputesAsFyrQLDoes(JdbcDialect dialect) {
        TestDatabase db = allTypes(dialect);
        JdbcConnector on = db.connector("db");
        JdbcConnector off = db.connector("db");
        boolean sqlite = dialect == JdbcDialect.SQLITE;
        String table = (sqlite ? "\"main\"" : "\"PUBLIC\"") + ".\"all_types\" t";
        try (FyrQL pushing = FyrQL.builder().register(on).pushdown(true).build();
                FyrQL plain = FyrQL.builder().register(off).pushdown(false).build()) {
            Map<String, String> queries = Map.of(
                    "SELECT count(*) FROM all_types", "SELECT count(*) FROM " + table,
                    "SELECT b, count(*), count(s), min(i), max(bi) FROM all_types WHERE id > 0 GROUP BY b "
                            + "ORDER BY b",
                    "SELECT t.\"b\", count(*), count(t.\"s\"), min(t.\"i\"), max(t.\"bi\") FROM " + table
                            + " WHERE (t.\"id\" > " + (sqlite ? "0" : "CAST(0 AS INTEGER)") + ") GROUP BY t.\"b\"",
                    // SQLite has no exact type for the sum, and stores decimals and dates as other types.
                    "SELECT sum(i) FROM all_types",
                    sqlite ? "SELECT t.\"i\" FROM " + table : "SELECT CAST(sum(t.\"i\") AS NUMERIC(39)) FROM " + table,
                    "SELECT min(dt), max(de) FROM all_types",
                    sqlite ? "SELECT t.\"de\", t.\"dt\" FROM " + table
                            : "SELECT min(t.\"dt\"), max(t.\"de\") FROM " + table,
                    // Strings are grouped alike everywhere; doubles are neither grouped nor aggregated in the database.
                    "SELECT s, count(*) FROM all_types GROUP BY s ORDER BY s",
                    "SELECT t.\"s\", count(*) FROM " + table + " GROUP BY t.\"s\"",
                    "SELECT d, count(*) FROM all_types GROUP BY d ORDER BY d", "SELECT t.\"d\" FROM " + table,
                    "SELECT max(d) FROM all_types", "SELECT t.\"d\" FROM " + table,
                    // A filter left to the engine keeps the aggregation there too, and so does DISTINCT.
                    "SELECT count(*) FROM all_types WHERE f < 0", "SELECT t.\"f\" FROM " + table,
                    "SELECT count(DISTINCT i) FROM all_types", "SELECT t.\"i\" FROM " + table);
            for (Map.Entry<String, String> q : queries.entrySet()) {
                on.clearStatements();
                assertRows(plain.rows(q.getKey()), pushing.rows(q.getKey()));
                assertEquals(List.of(q.getValue()), on.statements(), q.getKey());
            }
            assertTrue(pushing.explain("SELECT b, count(*) FROM all_types GROUP BY b")
                    .contains("[pushed: GROUP BY #0: count(*)"));
        }
    }

    /** A database with the tables {@code customers} and {@code orders}. */
    private static TestDatabase shop(JdbcDialect dialect) {
        TestDatabase db = TestDatabase.create(dialect);
        db.load("customers", Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("name", DataType.VARCHAR), new Schema.Field("segment", DataType.VARCHAR)),
                List.of(new Object[] {1, "ann", "a"}, new Object[] {2, "bob", "b"}, new Object[] {3, "cy", "a"},
                        new Object[] {4, null, "a"}, new Object[] {null, "nil", "a"}));
        db.load("orders", Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("customer", DataType.INTEGER), new Schema.Field("amount", DataType.INTEGER)),
                List.of(new Object[] {10, 1, 5}, new Object[] {11, 1, 20}, new Object[] {12, 2, 30},
                        new Object[] {13, 3, 40}, new Object[] {14, null, 50}, new Object[] {15, 3, null},
                        new Object[] {16, 9, 7}));
        return db;
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void aJoinOfTablesOfTheDatabaseIsOneStatement(JdbcDialect dialect) {
        TestDatabase db = shop(dialect);
        JdbcConnector on = db.connector("db");
        JdbcConnector off = db.connector("db");
        boolean sqlite = dialect == JdbcDialect.SQLITE;
        try (FyrQL pushing = FyrQL.builder().register(on).pushdown(true).build();
                FyrQL plain = FyrQL.builder().register(off).pushdown(false).build()) {
            String full = "SELECT count(*) FROM orders o FULL JOIN customers c ON c.id = o.customer";
            Map<String, String> queries = new HashMap<>(Map.of(
                    // SQLite leaves the sum to the engine, and with it the order; H2 the order of strings.
                    "SELECT c.name, count(*), sum(o.amount) FROM orders o JOIN customers c ON o.customer = c.id "
                            + "WHERE c.segment = 'a' AND o.amount > 6 GROUP BY c.name ORDER BY c.name",
                    sqlite ? " INNER JOIN " : " GROUP BY ",
                    "SELECT c.id, o.id FROM customers c LEFT JOIN orders o ON c.id = o.customer ORDER BY 1, 2",
                    "ORDER BY t0.c0 ASC NULLS LAST, t0.c1 ASC NULLS LAST",
                    "SELECT o.id, c.name FROM orders o RIGHT JOIN customers c ON c.id = o.customer AND o.amount < 35 "
                            + "ORDER BY 2, 1 LIMIT 3",
                    sqlite ? " LIMIT 3" : " RIGHT JOIN ",
                    full, "SELECT count(*)",
                    "SELECT o.id, c.id FROM orders o, customers c WHERE o.customer = c.id ORDER BY 1", " INNER JOIN "));
            if (!sqlite) {
                // H2 has no full join.
                queries.remove(full);
                on.clearStatements();
                assertRows(plain.rows(full), pushing.rows(full));
                assertEquals(2, on.statements().size(), on.statements().toString());
            }
            for (Map.Entry<String, String> q : queries.entrySet()) {
                on.clearStatements();
                assertRows(plain.rows(q.getKey()), pushing.rows(q.getKey()));
                assertEquals(1, on.statements().size(), q.getKey() + " sends " + on.statements());
                String sent = on.statements().get(0);
                assertTrue(sent.contains(" JOIN ") && sent.contains(q.getValue()), q.getKey() + " sends " + sent);
                String explain = pushing.explain(q.getKey());
                assertTrue(explain.contains("Remote db [SELECT ") && explain.matches("(?s).*\\| +Scan db\\.main\\..*"),
                        explain);
            }
            for (String sent : off.statements()) {
                assertFalse(sent.contains(" JOIN "), sent);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void aJoinTheDatabaseWouldEvaluateOtherwiseStaysWithTheEngine(JdbcDialect dialect) {
        TestDatabase db = shop(dialect);
        JdbcConnector connector = db.connector("db");
        try (FyrQL engine = FyrQL.builder().register(connector).pushdown(true).build()) {
            List<String> queries = List.of(
                    // The database would compare the pattern under rules of its own.
                    "SELECT o.id FROM orders o JOIN customers c ON o.customer = c.id WHERE c.name LIKE 'a%'",
                    // The product would be sent whole.
                    "SELECT o.id, c.id FROM orders o CROSS JOIN customers c",
                    // A key that is computed.
                    "SELECT o.id FROM orders o JOIN customers c ON o.customer + 1 = c.id");
            for (String sql : queries) {
                connector.clearStatements();
                assertFalse(engine.rows(sql).isEmpty(), sql);
                assertEquals(2, connector.statements().size(), sql + " sends " + connector.statements());
                for (String sent : connector.statements()) {
                    assertFalse(sent.contains(" JOIN "), sent);
                }
            }
        }
    }

    @Test
    void aJoinOnInstantsStaysWithTheEngine() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE a (id INTEGER, tz TIMESTAMP WITH TIME ZONE)",
                "CREATE TABLE b (id INTEGER, tz TIMESTAMP WITH TIME ZONE)",
                "INSERT INTO a VALUES (1, TIMESTAMP WITH TIME ZONE '2020-01-01 12:00:00+02')",
                "INSERT INTO b VALUES (2, TIMESTAMP WITH TIME ZONE '2020-01-01 10:00:00+00')");
        JdbcConnector connector = db.connector("db");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(1, engine.rows("SELECT a.\"ID\", b.\"ID\" FROM a JOIN b ON a.\"TZ\" = b.\"TZ\"").size());
            assertEquals(2, connector.statements().size(), connector.statements().toString());
            connector.clearStatements();
            // The instants are only read, and the join is on the ids.
            assertEquals(0, engine.rows("SELECT a.\"TZ\", b.\"TZ\" FROM a JOIN b ON a.\"ID\" = b.\"ID\"").size());
            assertEquals(1, connector.statements().size(), connector.statements().toString());
        }
    }

    @Test
    void batchesEndAtTheirSizeAndAfterTheLastRow() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE four (n INTEGER)", "INSERT INTO four VALUES (1), (2), (3), (4)",
                "CREATE TABLE three (n INTEGER)", "INSERT INTO three VALUES (1), (2), (3)", "CREATE TABLE none (n INTEGER)");
        JdbcConnector connector = JdbcConnector.builder("db").url(db.url()).batchSize(2).build();
        assertEquals(List.of(2, 2), sizes(readAll(connector, TableRef.of("db", "FOUR"))));
        assertEquals(List.of(2, 1), sizes(readAll(connector, TableRef.of("db", "THREE"))));
        assertEquals(List.of(), sizes(readAll(connector, TableRef.of("db", "NONE"))));
    }

    private static List<Integer> sizes(List<Batch> batches) {
        return batches.stream().map(Batch::rowCount).toList();
    }

    @Test
    void aLargeResultIsReadInParts() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE big AS SELECT CAST(X AS BIGINT) AS n FROM SYSTEM_RANGE(1, 100000)");
        JdbcConnector connector = JdbcConnector.builder("db").url(db.url()).fetchSize(100).batchSize(1000)
                .recordStatements(true).build();
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            Object[] row = engine.rows("SELECT count(*), sum(n), min(n), max(n) FROM big").get(0);
            assertEquals(100_000L, row[0]);
            assertEquals(5_000_050_000L, ((Number) row[1]).longValue());
            assertEquals(1L, row[2]);
            assertEquals(100_000L, row[3]);
            assertEquals(10, engine.rows("SELECT n FROM big WHERE n > 99990").size());
            assertTrue(connector.statements().get(1).endsWith("WHERE (t.\"N\" > CAST(99990 AS INTEGER))"),
                    connector.statements().get(1));
        }
    }

    @Test
    void aValueOfSqliteThatIsNotOfItsColumnTypeIsAnError() {
        TestDatabase db = TestDatabase.create(JdbcDialect.SQLITE);
        db.execute("CREATE TABLE bad (n INT4, s VARCHAR)", "INSERT INTO bad VALUES (1, 'a'), ('x', 'b')");
        try (FyrQL engine = FyrQL.builder().register(db.connector("db")).build()) {
            assertEquals(2, engine.rows("SELECT s FROM bad").size());
            RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows("SELECT n FROM bad"));
            String message = messages(e);
            assertTrue(message.contains("row 2, column n") && message.contains("x, which is not of the type INTEGER"), message);
        }
    }

    private static String messages(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            b.append(t.getMessage()).append('\n');
        }
        return b.toString();
    }

    @Test
    void aSharedConnectionStaysOpen() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE t (n INT4)");
                s.execute("INSERT INTO t VALUES (1), (2), (3)");
            }
            JdbcConnector connector = JdbcConnector.builder("mem").connection(c).build();
            assertEquals(JdbcDialect.SQLITE, connector.dialect());
            try (FyrQL engine = FyrQL.builder().register(connector).build()) {
                assertEquals(2, engine.rows("SELECT n FROM t WHERE n > 1").size());
                assertEquals(3, engine.rows("SELECT n FROM mem.t").size());
            }
            assertFalse(c.isClosed());
        }
    }

    @Test
    void theBuilderNeedsOneWayToConnect() {
        assertThrows(IllegalArgumentException.class, () -> JdbcConnector.builder("db").build());
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        assertThrows(IllegalArgumentException.class, () -> JdbcConnector.builder("db").url(db.url())
                .dataSource(new org.h2.jdbcx.JdbcDataSource()).build());
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(db.url());
        assertEquals(JdbcDialect.H2, JdbcConnector.builder("db").dataSource(ds).build().dialect());
        assertEquals(JdbcDialect.POSTGRESQL, JdbcDialect.ofUrl("jdbc:postgresql://localhost/x").orElseThrow());
        assertEquals(JdbcDialect.MYSQL, JdbcDialect.ofUrl("jdbc:mysql://localhost/x").orElseThrow());
        assertEquals(JdbcDialect.MYSQL, JdbcDialect.ofName("mysql"));
        assertThrows(IllegalArgumentException.class, () -> JdbcDialect.ofName("oracle"));
    }

    @Test
    void aDriverThatIsNotRegisteredIsFoundInTheClassLoaderOfTheConnector() throws SQLException {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE t (n INTEGER)", "INSERT INTO t VALUES (1), (2), (3)");
        String url = UnregisteredDriver.PREFIX + db.url().substring("jdbc:h2:".length());
        assertThrows(SQLException.class, () -> DriverManager.getConnection(url), "the driver is not registered");
        int connects = UnregisteredDriver.CONNECTS.get();
        JdbcConnector connector = JdbcConnector.builder("db").url(url).build();
        assertEquals(JdbcDialect.H2, connector.dialect(), "from the database the driver connects to");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(2L, engine.rows("SELECT count(*) FROM t WHERE \"N\" > 1").get(0)[0]);
            assertEquals(3, engine.rows("SELECT \"N\" FROM t").size());
        }
        assertTrue(UnregisteredDriver.CONNECTS.get() - connects >= 3, "every connection comes from the driver");
    }

    @Test
    void aUrlNoDriverAcceptsFailsWithTheMessageOfTheDriverManager() {
        SQLException e = assertThrows(SQLException.class,
                () -> Connections.url("jdbc:nothing:x", new Properties()).open());
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().startsWith("No suitable driver"), e.getMessage());
    }

    @Test
    void aSourceCannotBeFyrQLItself() {
        for (String url : List.of("jdbc:fyrql:", "JDBC:FyrQL:?config=engine.yaml", " jdbc:fyrql:?codegen=off")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> JdbcConnector.builder("self").url(url));
            assertEquals("The JDBC source self has the URL jdbc:fyrql: of FyrQL itself; its engine cannot be a"
                    + " source of another one", e.getMessage());
        }
    }

    /** A table {@code items} of the keys 1 to n in {@code id}, its primary key, and twice the key in {@code v}. */
    private static TestDatabase items(JdbcDialect dialect, int n) {
        TestDatabase db = TestDatabase.create(dialect);
        StringBuilder insert = new StringBuilder("INSERT INTO \"items\" VALUES ");
        for (int i = 1; i <= n; i++) {
            insert.append(i > 1 ? ", " : "").append('(').append(i).append(", ").append(2 * i).append(')');
        }
        db.execute("CREATE TABLE \"items\" (\"id\" INTEGER PRIMARY KEY, \"v\" INTEGER)", insert.toString());
        return db;
    }

    private static JdbcConnector splitting(TestDatabase db, long splitRows, Consumer<JdbcConnector.Builder> more) {
        JdbcConnector.Builder b = JdbcConnector.builder("db").url(db.url()).recordStatements(true)
                .splitRows(splitRows);
        more.accept(b);
        return b.build();
    }

    private static String qualified(JdbcDialect dialect, String table) {
        return (dialect == JdbcDialect.H2 ? "\"PUBLIC\"." : "\"main\".") + "\"" + table + "\"";
    }

    private static List<Integer> ids(List<Object[]> rows) {
        return rows.stream().map(r -> ((Number) r[0]).intValue()).toList();
    }

    @ParameterizedTest
    @EnumSource(names = {"H2", "SQLITE"})
    void aLargeTableIsReadInRangesOfItsPrimaryKey(JdbcDialect dialect) {
        TestDatabase db = items(dialect, 100);
        JdbcConnector connector = splitting(db, 30, b -> {
        });
        TableRef table = TableRef.of("db", "items");
        assertEquals(IntStream.rangeClosed(1, 100).boxed().toList(), ids(rows(readAll(connector, table))));
        DataType type = connector.catalog().schema(table).type(0);
        String select = "SELECT t.\"id\", t.\"v\" FROM " + qualified(dialect, "items") + " t WHERE ";
        String id = "t.\"id\"";
        List<String> expected = new ArrayList<>(List.of(
                "SELECT min(" + id + "), max(" + id + ") FROM " + qualified(dialect, "items") + " t",
                select + "(" + id + " < " + literal(dialect, 25, type) + ")",
                select + "((" + id + " >= " + literal(dialect, 25, type) + ") AND (" + id + " < "
                        + literal(dialect, 50, type) + "))",
                select + "((" + id + " >= " + literal(dialect, 50, type) + ") AND (" + id + " < "
                        + literal(dialect, 75, type) + "))",
                select + "(" + id + " >= " + literal(dialect, 75, type) + ")"));
        if (connector.catalog().schema(table).nullable(0)) {
            // SQLite tells that a key of type INTEGER may be NULL.
            expected.add(select + "(" + id + " IS NULL)");
        }
        assertEquals(expected, connector.statements());

        // No more ranges than threads read them, and for one thread no range at all.
        int nulls = expected.size() - 5;
        ScanPlan plan = connector.plan(table, ScanRequest.columns(List.of(0, 1)));
        connector.clearStatements();
        assertEquals(1, connector.splits(plan, 1).size());
        assertEquals(List.of(), connector.statements());
        assertEquals(2 + nulls, connector.splits(plan, 2).size());
        assertEquals(4 + nulls, connector.splits(plan, 12).size());

        // The engine reads the splits on several threads, and one statement on one; the filter comes before the
        // range.
        for (int threads : new int[] {1, 4}) {
            connector.clearStatements();
            try (FyrQL engine = FyrQL.builder().register(connector).threads(threads).build()) {
                assertEquals(IntStream.rangeClosed(51, 100).boxed().toList(),
                        ids(engine.rows("SELECT \"id\" FROM items WHERE \"v\" > 100")).stream().sorted().toList());
                assertEquals(threads > 1, engine.explain("SELECT \"id\" FROM items WHERE \"v\" > 100")
                        .contains("[parallel]"));
            }
            List<String> sent = connector.statements();
            assertEquals(threads > 1 ? 1 + 4 + nulls : 1, sent.size(), sent.toString());
            String last = sent.get(sent.size() - 1);
            assertTrue(last.contains(" WHERE (t.\"v\" > "), last);
            assertEquals(threads > 1, last.contains(") AND (t.\"id\" "), last);
        }
    }

    private static String literal(JdbcDialect dialect, long value, DataType type) {
        Object v = type == DataType.INTEGER ? (Object) (int) value : (Object) value;
        return dialect.sql().literal(v, type);
    }

    @Test
    void rowsOutsideTheRangeAskedForAndRowsWithoutAKeyAreReadAsWell() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE \"loose\" (\"k\" BIGINT, \"s\" VARCHAR(10))",
                "INSERT INTO \"loose\" SELECT X, 'r' || X FROM SYSTEM_RANGE(1, 100)",
                "INSERT INTO \"loose\" VALUES (NULL, 'none'), (NULL, 'none')");
        JdbcConnector connector = splitting(db, 26, b -> b.splitColumn("loose", "k"));
        TableRef table = TableRef.of("db", "loose");
        ScanPlan plan = connector.plan(table, ScanRequest.columns(List.of(0, 1)));
        List<Split> splits = connector.splits(plan);
        assertEquals(5, splits.size(), "four ranges and the keys that are NULL");
        // Rows written after the range was asked for fall into the first and the last split.
        db.execute("INSERT INTO \"loose\" VALUES (-7, 'low'), (1000, 'high')");
        List<Object[]> rows = new ArrayList<>();
        for (Split split : splits) {
            try (BatchReader reader = connector.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    rows.addAll(b.rows());
                }
            }
        }
        assertEquals(104, rows.size());
        assertEquals(1L, rows.stream().filter(r -> "low".equals(r[1])).count());
        assertEquals(1L, rows.stream().filter(r -> "high".equals(r[1])).count());
        assertEquals("none", rows.get(103)[1]);
        assertEquals(1L, rows.stream().filter(r -> "r50".equals(r[1])).count());
    }

    @Test
    void aScanStaysOneStatementWhereSplitsWouldNotHelpOrCannotBeMade() throws SQLException {
        TestDatabase db = items(JdbcDialect.H2, 100);
        db.execute("CREATE TABLE \"empty\" (\"id\" INTEGER PRIMARY KEY)",
                "CREATE TABLE \"plain\" (\"id\" INTEGER, \"v\" INTEGER)",
                "INSERT INTO \"plain\" SELECT X, X FROM SYSTEM_RANGE(1, 100)",
                "CREATE TABLE \"pair\" (\"a\" INTEGER, \"b\" INTEGER, PRIMARY KEY (\"a\", \"b\"))",
                "INSERT INTO \"pair\" SELECT X, X FROM SYSTEM_RANGE(1, 100)",
                "CREATE TABLE \"named\" (\"name\" VARCHAR(10) PRIMARY KEY)",
                "INSERT INTO \"named\" SELECT 'n' || X FROM SYSTEM_RANGE(1, 100)",
                "CREATE TABLE \"same\" (\"id\" INTEGER, \"v\" INTEGER)",
                "INSERT INTO \"same\" SELECT 7, X FROM SYSTEM_RANGE(1, 100)");
        // Not switched on, more rows asked for than the table has, an empty table, no key of one column of
        // a type that has ranges.
        JdbcConnector off = splitting(db, 0, b -> {
        });
        assertEquals(1, readAll(off, TableRef.of("db", "items")).size());
        assertEquals(1, off.statements().size());
        JdbcConnector few = splitting(db, 100, b -> {
        });
        readAll(few, TableRef.of("db", "items"));
        assertEquals(1, few.statements().size());
        JdbcConnector on = splitting(db, 10, b -> b.splitColumn("same", "id"));
        for (String table : List.of("empty", "plain", "pair", "named")) {
            on.clearStatements();
            readAll(on, TableRef.of("db", table));
            assertEquals(1, on.statements().size(), table);
        }
        // One key: its range is asked for, but there is nothing to split.
        on.clearStatements();
        assertEquals(100, rows(readAll(on, TableRef.of("db", "same"))).size());
        assertEquals(2, on.statements().size());
        assertTrue(on.statements().get(0).startsWith("SELECT min("), on.statements().get(0));

        // What the database aggregates, orders or limits.
        try (FyrQL engine = FyrQL.builder().register(on).threads(4).build()) {
            on.clearStatements();
            assertEquals(100L, engine.rows("SELECT count(*) FROM items").get(0)[0]);
            assertEquals(List.of(1, 2, 3), ids(engine.rows("SELECT \"id\" FROM items ORDER BY \"id\" LIMIT 3")));
            assertEquals(2, on.statements().size(), on.statements().toString());
            assertTrue(on.statements().get(0).contains("count(*)"), on.statements().get(0));
            assertTrue(on.statements().get(1).contains("LIMIT"), on.statements().get(1));
        }

        // A shared connection reads one statement at a time.
        try (Connection c = db.connect()) {
            JdbcConnector shared = JdbcConnector.builder("db").connection(c).recordStatements(true).splitRows(10)
                    .build();
            assertEquals(100, rows(readAll(shared, TableRef.of("db", "items"))).size());
            assertEquals(1, shared.statements().size());
        }
    }

    @Test
    void theColumnSetForATableMustBeOneOfItWithRanges() {
        TestDatabase db = items(JdbcDialect.H2, 100);
        db.execute("CREATE TABLE \"days\" (\"d\" DATE, \"ts\" TIMESTAMP, \"s\" VARCHAR(10))",
                "INSERT INTO \"days\" SELECT DATEADD('DAY', X, DATE '2020-01-01'), "
                        + "DATEADD('HOUR', X, TIMESTAMP '2020-01-01 00:00:00'), 's' || X FROM SYSTEM_RANGE(0, 99)");
        // By a column other than the primary key, the table named with its schema.
        JdbcConnector byValue = splitting(db, 50, b -> b.splitColumn("main.items", "v"));
        assertEquals(100, rows(readAll(byValue, TableRef.of("db", "items"))).size());
        assertEquals("SELECT min(t.\"v\"), max(t.\"v\") FROM \"PUBLIC\".\"items\" t", byValue.statements().get(0));
        assertEquals(4, byValue.statements().size(), "two ranges and the values that are NULL");

        for (String column : List.of("d", "ts")) {
            JdbcConnector byTime = splitting(db, 30, b -> b.splitColumn("days", column));
            List<Object[]> rows = rows(readAll(byTime, TableRef.of("db", "days")));
            assertEquals(100, rows.size(), column);
            assertEquals(LocalDate.of(2020, 1, 1).toEpochDay(), ((Number) rows.get(0)[0]).longValue(), column);
            assertEquals(6, byTime.statements().size(), column + ": " + byTime.statements());
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> readAll(splitting(db, 30, b -> b.splitColumn("days", "nope")), TableRef.of("db", "days")));
        assertEquals("The JDBC source db splits db.main.days by nope, which is no column of it", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> readAll(splitting(db, 30, b -> b.splitColumn("days", "s")), TableRef.of("db", "days")));
        assertTrue(e.getMessage().contains("by s of type VARCHAR; it takes an integral type"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> JdbcConnector.builder("db").splitRows(-1));
        assertThrows(IllegalArgumentException.class, () -> JdbcConnector.builder("db").splitColumn(" ", "v"));
    }

    @Test
    void theFactoryCreatesASourceFromOptions() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream().map(ServiceLoader.Provider::get)
                .filter(f -> f.type().equals("jdbc")).findFirst().orElseThrow();
        TestDatabase db = allTypes(JdbcDialect.H2);
        JdbcConnector connector = (JdbcConnector) factory.create("db", Map.of("url", db.url(), "dialect", "h2",
                "fetchSize", "10", "batchSize", "1", "recordStatements", "true", "schemas", "PUBLIC, OTHER",
                "properties.ACCESS_MODE_DATA", "r"));
        assertEquals(3, readAll(connector, TableRef.of("db", ALL)).size());
        assertEquals(1, connector.statements().size());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("db", Map.of("url", db.url(), "fetchsize", "10")));
        assertTrue(e.getMessage().contains("unknown option fetchsize"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> factory.create("db", Map.of("user", "sa")));
        assertTrue(e.getMessage().contains("needs the option url"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("db", Map.of("url", db.url(), "batchSize", "many")));
        assertTrue(e.getMessage().contains("batchSize"), e.getMessage());

        TestDatabase items = items(JdbcDialect.H2, 100);
        JdbcConnector split = (JdbcConnector) factory.create("db", Map.of("url", items.url(), "recordStatements",
                "true", "splitRows", "50", "splitColumn.items", "v"));
        assertEquals(100, rows(readAll(split, TableRef.of("db", "items"))).size());
        assertEquals("SELECT min(t.\"v\"), max(t.\"v\") FROM \"PUBLIC\".\"items\" t", split.statements().get(0));
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("db", Map.of("url", db.url(), "splitRows", "many")));
        assertTrue(e.getMessage().contains("splitRows"), e.getMessage());
    }

    @Test
    void anUnknownColumnTypeIsReadAsAString() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE odd (t TIME, j JSON, a INTEGER ARRAY, n NUMERIC)",
                "INSERT INTO odd VALUES (TIME '12:34:56', JSON '{\"a\":1}', ARRAY[1, 2], 15)");
        JdbcConnector connector = db.connector("db");
        Schema schema = connector.catalog().schema(TableRef.of("db", "ODD"));
        assertEquals(List.of(DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR, DataType.VARCHAR), schema.types());
        Object[] row = rows(readAll(connector, TableRef.of("db", "ODD"))).get(0);
        assertEquals("12:34:56", row[0]);
        assertEquals("15", row[3]);
    }

    @Test
    void aTimestampWithATimeZoneIsItsInstantInUtcAndFilteredByTheEngine() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE zoned (id INTEGER, tz TIMESTAMP WITH TIME ZONE)",
                "INSERT INTO zoned VALUES (1, TIMESTAMP WITH TIME ZONE '2020-01-01 12:00:00+02'), "
                        + "(2, TIMESTAMP WITH TIME ZONE '2020-01-01 09:59:59+00')");
        JdbcConnector connector = db.connector("db");
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            // The database would read the literal in the time zone of its session.
            List<Object[]> rows = engine.rows("SELECT \"ID\", \"TZ\" FROM zoned WHERE \"TZ\" >= TIMESTAMP '2020-01-01 10:00:00'");
            assertEquals(1, rows.size());
            assertEquals(1, rows.get(0)[0]);
            assertEquals(Conversions.toMicros(LocalDateTime.of(2020, 1, 1, 10, 0)), rows.get(0)[1]);
            assertFalse(connector.statements().get(0).contains("WHERE"), connector.statements().get(0));
            assertEquals(1, engine.rows("SELECT \"ID\" FROM zoned WHERE \"ID\" = 1").size());
            assertTrue(connector.statements().get(1).contains("WHERE"), connector.statements().get(1));
            // The instants are neither grouped nor aggregated in the database either.
            assertEquals(Conversions.toMicros(LocalDateTime.of(2020, 1, 1, 10, 0)),
                    engine.rows("SELECT max(\"TZ\") FROM zoned").get(0)[0]);
            assertTrue(connector.statements().get(2).startsWith("SELECT t.\"TZ\" FROM"), connector.statements().get(2));
            assertEquals(2, engine.rows("SELECT \"TZ\", count(*) FROM zoned GROUP BY \"TZ\"").size());
            assertFalse(connector.statements().get(3).contains("GROUP BY"), connector.statements().get(3));
            assertEquals(2L, engine.rows("SELECT count(\"TZ\") FROM zoned").get(0)[0]);
            assertTrue(connector.statements().get(4).startsWith("SELECT t.\"TZ\" FROM"), connector.statements().get(4));
        }
    }
}
