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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connections of the driver with the same configuration share one engine,
 * and so its tables in memory, each with a session of its own; the last
 * one closes the engine, at once or after {@code closeDelay}. Each test has
 * a {@code batchSize} of its own, so that it shares no engine with another.
 */
class SharedEngineTest {

    private final FyrQLDriver driver = new FyrQLDriver();

    @TempDir
    Path dir;

    @AfterEach
    void closeTheEnginesThatNoConnectionHolds() {
        SharedEngines.closeIdle();
    }

    @Test
    void connectionsOfTheSameUrlShareTheEngineAndItsTables() throws SQLException {
        String url = "jdbc:fyrql:?batchSize=1001";
        int before = SharedEngines.size();
        FyrQL engine;
        try (Connection a = driver.connect(url, null); Connection b = driver.connect(url, null)) {
            engine = a.unwrap(FyrQL.class);
            assertSame(engine, b.unwrap(FyrQL.class));
            assertEquals(before + 1, SharedEngines.size());
            update(a, "CREATE TABLE memory.t AS SELECT 7 AS k");
            assertEquals(List.of(7), DriverTest.first(b, "SELECT k FROM memory.t"));
            try (ResultSet rs = b.getMetaData().getTables("memory", null, "t", null)) {
                assertTrue(rs.next(), "the other connection sees the table in its metadata");
            }
        }
        assertEquals(before, SharedEngines.size(), "the last connection closes the engine");
        try (Connection c = driver.connect(url, null)) {
            assertNotSame(engine, c.unwrap(FyrQL.class));
            assertTrue(assertThrows(SQLException.class, () -> DriverTest.first(c, "SELECT k FROM memory.t"))
                    .getMessage().contains("does not exist"));
        }
    }

    @Test
    void otherSettingsOfTheEngineGiveAnotherEngine() throws SQLException {
        try (Connection a = driver.connect("jdbc:fyrql:?batchSize=1002&threads=2", null);
                Connection b = driver.connect("jdbc:fyrql:?batchSize=1002&threads=3", null);
                Connection c = driver.connect("jdbc:fyrql:?batchSize=1002&threads=2&fileAccess=read", null);
                Connection d = driver.connect("jdbc:fyrql:?batchSize=1002&threads=2&fileAccess=readWrite", null)) {
            List<FyrQL> engines = List.of(a.unwrap(FyrQL.class), b.unwrap(FyrQL.class), c.unwrap(FyrQL.class),
                    d.unwrap(FyrQL.class));
            assertEquals(4, distinct(engines).size(), "each setting of the engine counts, also its default value");
            Properties p = new Properties();
            p.setProperty("THREADS", "2");
            try (Connection e = driver.connect("jdbc:fyrql:?BATCHSIZE=1002", p)) {
                assertSame(engines.get(0), e.unwrap(FyrQL.class), "the case and the order of the keys do not count");
            }
        }
    }

    @Test
    void eachConnectionSetsTheSettingsOfItsSession() throws SQLException {
        int before = SharedEngines.size();
        try (Connection a = driver.connect("jdbc:fyrql:?batchSize=1003&timeZone=Asia/Tokyo&queryTimeout=30s", null);
                Connection b = driver.connect("jdbc:fyrql:?batchSize=1003&timeZone=Europe/Berlin"
                        + "&defaultSchema=other", null)) {
            FyrQL engine = a.unwrap(FyrQL.class);
            assertSame(engine, b.unwrap(FyrQL.class));
            assertEquals(List.of("Asia/Tokyo"), DriverTest.first(a, "SHOW TimeZone"));
            assertEquals(List.of("Europe/Berlin"), DriverTest.first(b, "SHOW TimeZone"));
            assertEquals(List.of("30s"), DriverTest.first(a, "SHOW statement_timeout"));
            assertEquals(List.of("0"), DriverTest.first(b, "SHOW statement_timeout"));
            assertEquals("main", a.getSchema());
            assertEquals("other", b.getSchema());
            update(a, "RESET TimeZone");
            assertEquals(List.of(engine.timeZone().getId()), DriverTest.first(a, "SHOW TimeZone"),
                    "RESET returns to the time zone of the engine");
            SQLException e = assertThrows(SQLException.class, () -> driver.connect("jdbc:fyrql:?batchSize=1003"
                    + "&timeZone=Mars/Base", null));
            assertEquals("08001", e.getSQLState());
            assertTrue(e.getMessage().contains("Mars/Base"), e.getMessage());
            assertEquals(List.of(1), DriverTest.first(b, "SELECT 1"), "the failure leaves the engine open");
        }
        assertEquals(before, SharedEngines.size());
        assertEquals("08001", assertThrows(SQLException.class, () -> driver.connect("jdbc:fyrql:?batchSize=1004"
                + "&defaultSource=shop", null)).getSQLState());
        assertEquals(before, SharedEngines.size(), "a connection that fails gives its share back");
    }

    @Test
    void aChangedConfigurationGivesANewEngine() throws Exception {
        Files.writeString(dir.resolve("t.csv"), "a\n1\n2\n", StandardCharsets.UTF_8);
        Path config = dir.resolve("fyrql.yaml");
        Files.writeString(config, csvSources("files"), StandardCharsets.UTF_8);
        String url = "jdbc:fyrql:?batchSize=1005&config=" + DriverTest.path(config);
        int before = SharedEngines.size();
        try (Connection a = driver.connect(url, null)) {
            update(a, "CREATE TABLE mem.t AS SELECT 1 AS k");
            try (Connection same = driver.connect(url, null)) {
                assertSame(a.unwrap(FyrQL.class), same.unwrap(FyrQL.class), "the file is read anew, unchanged");
            }
            Files.writeString(config, csvSources("files", "more"), StandardCharsets.UTF_8);
            try (Connection b = driver.connect(url, null)) {
                assertNotSame(a.unwrap(FyrQL.class), b.unwrap(FyrQL.class));
                assertEquals(before + 2, SharedEngines.size());
                assertEquals(List.of(BigDecimal.valueOf(3)), DriverTest.first(b, "SELECT sum(a) FROM more.t"));
                assertFalse(a.unwrap(FyrQL.class).sources().contains("more"));
            }
            Files.writeString(config, "sources: [", StandardCharsets.UTF_8);
            SQLException e = assertThrows(SQLException.class, () -> driver.connect(url, null));
            assertEquals("08001", e.getSQLState());
            assertEquals(List.of(BigDecimal.valueOf(3)), DriverTest.first(a, "SELECT sum(a) FROM files.t"),
                    "the old engine works on");
            assertEquals(List.of(1), DriverTest.first(a, "SELECT k FROM mem.t"));
        }
        assertEquals(before, SharedEngines.size());
    }

    @Test
    void twoEnginesOpenTheSameDatabaseFile() throws Exception {
        String h2 = "jdbc:h2:file:" + DriverTest.path(dir.resolve("shop"));
        try (Connection db = DriverManager.getConnection(h2, "sa", ""); Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE customers (id INT PRIMARY KEY, name VARCHAR(20))");
            s.executeUpdate("INSERT INTO customers VALUES (1, 'Ada'), (2, 'Linus')");
        }
        Path config = dir.resolve("fyrql.yaml");
        Files.writeString(config, h2Source(h2, 100), StandardCharsets.UTF_8);
        String url = "jdbc:fyrql:?batchSize=1006&config=" + DriverTest.path(config);
        try (Connection a = driver.connect(url, null)) {
            Files.writeString(config, h2Source(h2, 50), StandardCharsets.UTF_8);
            try (Connection b = driver.connect(url, null)) {
                assertNotSame(a.unwrap(FyrQL.class), b.unwrap(FyrQL.class));
                update(b, "INSERT INTO customers VALUES (3, 'Grace')");
                assertEquals(List.of(3L), DriverTest.first(a, "SELECT count(*) FROM customers"));
            }
            assertEquals(List.of("Ada"), DriverTest.first(a, "SELECT name FROM customers WHERE id = 1"));
        }
    }

    @Test
    void connectionsThatComeAtOnceBuildOneEngine() throws Exception {
        String url = "jdbc:fyrql:?batchSize=1007";
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Connection> connections = new ArrayList<>();
        try {
            List<Future<Connection>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return driver.connect(url, null);
                }));
            }
            start.countDown();
            for (Future<Connection> f : futures) {
                connections.add(f.get());
            }
            List<FyrQL> engines = new ArrayList<>();
            for (Connection c : connections) {
                engines.add(c.unwrap(FyrQL.class));
            }
            assertEquals(1, distinct(engines).size());
        } finally {
            pool.shutdown();
            for (Connection c : connections) {
                c.close();
            }
        }
    }

    @Test
    void aFailedBuildIsNotRemembered() throws Exception {
        Path config = dir.resolve("fyrql.yaml");
        Files.writeString(config, csvSources("files") + """
                views:
                  - name: mem.v
                    sql: SELECT a FROM files.t
                """, StandardCharsets.UTF_8);
        String url = "jdbc:fyrql:?batchSize=1008&config=" + DriverTest.path(config);
        int before = SharedEngines.size();
        SQLException e = assertThrows(SQLException.class, () -> driver.connect(url, null));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("the view mem.v cannot be created"), e.getMessage());
        assertEquals(before, SharedEngines.size());
        Files.writeString(dir.resolve("t.csv"), "a\n5\n", StandardCharsets.UTF_8);
        try (Connection c = driver.connect(url, null)) {
            assertEquals(List.of(5L), DriverTest.first(c, "SELECT a FROM mem.v"));
        }
    }

    @Test
    void aCloseDelayKeepsTheEngineOpenForAWhile() throws Exception {
        String url = "jdbc:fyrql:?batchSize=1009&closeDelay=1s";
        int before = SharedEngines.size();
        FyrQL engine;
        try (Connection a = driver.connect(url, null)) {
            engine = a.unwrap(FyrQL.class);
            update(a, "CREATE TABLE memory.t AS SELECT 7 AS k");
        }
        try (Connection b = driver.connect(url, null)) {
            assertSame(engine, b.unwrap(FyrQL.class), "a connection within the delay gets the engine");
            assertEquals(List.of(7), DriverTest.first(b, "SELECT k FROM memory.t"));
        }
        assertEquals(before + 1, SharedEngines.size());
        long end = System.nanoTime() + 10_000_000_000L;
        while (SharedEngines.size() > before && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertEquals(before, SharedEngines.size(), "the engine closes after the delay");
        try (Connection c = driver.connect(url, null)) {
            assertNotSame(engine, c.unwrap(FyrQL.class));
        }
    }

    @Test
    void aCloseDelayOfMinusOneNeverClosesTheEngine() throws SQLException {
        String url = "jdbc:fyrql:?batchSize=1010&closeDelay=-1";
        int before = SharedEngines.size();
        try (Connection a = driver.connect(url, null)) {
            update(a, "CREATE TABLE memory.t AS SELECT 7 AS k");
        }
        assertEquals(before + 1, SharedEngines.size());
        try (Connection b = driver.connect(url, null)) {
            assertEquals(List.of(7), DriverTest.first(b, "SELECT k FROM memory.t"));
        }
        SharedEngines.closeIdle();
        assertEquals(before, SharedEngines.size());
        assertEquals("08001", assertThrows(SQLException.class, () -> driver.connect("jdbc:fyrql:?closeDelay=soon",
                null)).getSQLState());
        assertEquals("08001", assertThrows(SQLException.class, () -> driver.connect("jdbc:fyrql:?shared=maybe",
                null)).getSQLState());
    }

    @Test
    void connectionsThatDoNotShareHaveAnEngineEach() throws SQLException {
        String url = "jdbc:fyrql:?batchSize=1011&shared=false";
        int before = SharedEngines.size();
        try (Connection a = driver.connect(url, null); Connection b = driver.connect(url, null)) {
            assertNotSame(a.unwrap(FyrQL.class), b.unwrap(FyrQL.class));
            assertEquals(before, SharedEngines.size());
            update(a, "CREATE TABLE memory.t AS SELECT 7 AS k");
            assertThrows(SQLException.class, () -> DriverTest.first(b, "SELECT k FROM memory.t"));
        }
    }

    @Test
    void closingTwiceAndAbortingGiveTheShareBackOnce() throws SQLException {
        String url = "jdbc:fyrql:?batchSize=1012";
        int before = SharedEngines.size();
        Connection a = driver.connect(url, null);
        Connection b = driver.connect(url, null);
        update(b, "CREATE TABLE memory.t AS SELECT 7 AS k");
        a.close();
        a.close();
        a.abort(Runnable::run);
        assertEquals(List.of(7), DriverTest.first(b, "SELECT k FROM memory.t"), "b still holds the engine");
        b.abort(Runnable::run);
        assertTrue(b.isClosed());
        assertEquals(before, SharedEngines.size());
    }

    private static String csvSources(String... names) {
        StringBuilder yaml = new StringBuilder("sources:\n");
        for (String name : names) {
            yaml.append("  - name: ").append(name).append("\n    type: csv\n    directory: .\n");
        }
        return yaml.append("  - name: mem\n    type: memory\n").toString();
    }

    private static String h2Source(String url, int fetchSize) {
        return "sources:\n  - name: shop\n    type: jdbc\n    url: " + url + "\n    user: sa\n    fetchSize: " + fetchSize + "\n";
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static Set<FyrQL> distinct(List<FyrQL> engines) {
        Set<FyrQL> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(engines);
        return set;
    }
}
