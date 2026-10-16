/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The life of a connection over an engine, and the methods that do not run queries. */
class ConnectionTest {

    record Row(int id, String name) {
    }

    /**
     * A connector over a table {@code t} in memory that counts how often it
     * and its readers are closed, and whose readers fail on demand.
     */
    static final class CountingConnector implements Connector {
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger readerCloses = new AtomicInteger();
        /** The error the readers throw, if not {@code null}. */
        volatile RuntimeException failure;
        private final MemoryConnector memory;

        CountingConnector(String name) {
            memory = MemoryConnector.builder(name).table("t", Row.class, List.of(new Row(1, "a"), new Row(2, "b")))
                    .build();
        }

        /** A table {@code t} with the ids 1 to {@code rows} and the names {@code r1}, {@code r2}, ... */
        CountingConnector(String name, int rows, int batchSize) {
            List<Row> list = new ArrayList<>();
            for (int i = 1; i <= rows; i++) {
                list.add(new Row(i, "r" + i));
            }
            memory = MemoryConnector.builder(name).batchSize(batchSize).table("t", Row.class, list).build();
        }

        @Override
        public String name() {
            return memory.name();
        }

        @Override
        public Catalog catalog() {
            return memory.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return memory.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return memory.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            BatchReader reader = memory.open(split);
            return new BatchReader() {
                @Override
                public Batch next() {
                    RuntimeException f = failure;
                    if (f != null) {
                        throw f;
                    }
                    return reader.next();
                }

                @Override
                public void close() {
                    readerCloses.incrementAndGet();
                    reader.close();
                }
            };
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @Test
    void closingTheConnectionClosesItsEngineOnlyIfItOwnsIt() throws SQLException {
        CountingConnector shop = new CountingConnector("shop");
        FyrQL engine = FyrQL.builder().register(shop).build();
        Connection shared = FyrQLDriver.connect(engine, false);
        shared.close();
        assertTrue(shared.isClosed());
        assertEquals(0, shop.closes.get());
        Connection owning = FyrQLDriver.connect(engine, true);
        assertSame(engine, owning.unwrap(FyrQL.class));
        assertTrue(owning.isWrapperFor(FyrQL.class));
        assertTrue(owning.isWrapperFor(Connection.class));
        assertFalse(owning.isWrapperFor(String.class));
        assertThrows(SQLException.class, () -> owning.unwrap(String.class));
        owning.close();
        owning.close();
        assertEquals(1, shop.closes.get(), "closing twice closes the engine once");
    }

    @Test
    void aClosedConnectionRefusesItsMethods() throws SQLException {
        Connection c = FyrQLDriver.connect(FyrQL.builder().register(new CountingConnector("shop")).build(), true);
        assertTrue(c.isValid(0));
        c.close();
        assertFalse(c.isValid(5));
        List<ThrowingCall> calls = List.of(c::createStatement, () -> c.prepareStatement("SELECT 1"), c::getMetaData,
                c::getAutoCommit, c::commit, c::rollback, c::getCatalog, c::getSchema, () -> c.setCatalog("shop"),
                c::getTransactionIsolation, c::getWarnings, c::isReadOnly, c::getHoldability, c::getTypeMap,
                c::getClientInfo, () -> c.nativeSQL("SELECT 1"), c::getNetworkTimeout);
        for (ThrowingCall call : calls) {
            SQLException e = assertThrows(SQLException.class, call::run);
            assertEquals("08003", e.getSQLState());
            assertEquals("The connection is closed", e.getMessage());
        }
        SQLClientInfoException info = assertThrows(SQLClientInfoException.class, () -> c.setClientInfo("a", "b"));
        assertEquals("08003", info.getSQLState());
    }

    @Test
    void transactionsAreAcceptedAndDoNothing() throws SQLException {
        try (Connection c = FyrQLDriver.connect(FyrQL.builder().register(new CountingConnector("shop")).build(),
                true)) {
            assertTrue(c.getAutoCommit());
            c.setAutoCommit(false);
            assertTrue(c.getAutoCommit());
            c.commit();
            c.rollback();
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            assertEquals(Connection.TRANSACTION_NONE, c.getTransactionIsolation());
            c.setReadOnly(false);
            assertTrue(c.isReadOnly());
            assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, c.getHoldability());
            c.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
            assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, c.getHoldability());
            assertEquals("22023", assertThrows(SQLException.class, () -> c.setHoldability(42)).getSQLState());
            assertEquals("SELECT 1", c.nativeSQL("SELECT 1"));
            assertTrue(c.getTypeMap().isEmpty());
            c.setTypeMap(Map.of());
            for (ThrowingCall call : List.<ThrowingCall>of(c::setSavepoint, () -> c.setSavepoint("x"),
                    () -> c.prepareCall("CALL x()"), c::createBlob, c::createClob, c::createNClob, c::createSQLXML,
                    () -> c.createArrayOf("INTEGER", new Object[0]), () -> c.createStruct("S", new Object[0]),
                    () -> c.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS),
                    () -> c.setTypeMap(Map.of("t", String.class)))) {
                SQLFeatureNotSupportedException e = assertThrows(SQLFeatureNotSupportedException.class, call::run);
                assertEquals("0A000", e.getSQLState());
            }
        }
    }

    @Test
    void theCatalogIsTheDefaultSource() throws SQLException {
        FyrQL engine = FyrQL.builder().register(new CountingConnector("Shop")).register(new CountingConnector("crm"))
                .build();
        try (Connection c = FyrQLDriver.connect(engine, true)) {
            assertNull(c.getCatalog());
            c.setCatalog("shop");
            assertEquals("Shop", c.getCatalog(), "the name matches ignoring case");
            c.setCatalog("crm");
            assertEquals("crm", c.getCatalog());
            SQLException e = assertThrows(SQLException.class, () -> c.setCatalog("erp"));
            assertEquals("3D000", e.getSQLState());
            assertEquals("There is no source erp; the sources are Shop, crm", e.getMessage());
            assertEquals("crm", c.getCatalog());
            c.setCatalog(null);
            assertEquals("crm", c.getCatalog());
            c.setSchema("other");
            assertEquals("other", c.getSchema());
            assertEquals("crm", c.getCatalog());
        }
    }

    @Test
    void scrollableResultSetsAreDowngradedWithAWarning() throws SQLException {
        try (Connection c = FyrQLDriver.connect(FyrQL.builder().register(new CountingConnector("shop")).build(),
                true)) {
            try (Statement s = c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
                    ResultSet rs = s.executeQuery("SELECT * FROM t")) {
                assertEquals(ResultSet.TYPE_FORWARD_ONLY, rs.getType());
                assertEquals(ResultSet.CONCUR_READ_ONLY, rs.getConcurrency());
                assertEquals(ResultSet.TYPE_FORWARD_ONLY, s.getResultSetType());
            }
            SQLWarning w = c.getWarnings();
            assertNotNull(w);
            assertEquals("Result sets are forward only; the type 1004 is not supported", w.getMessage());
            assertEquals("Result sets are read only; the concurrency 1008 is not supported",
                    w.getNextWarning().getMessage());
        }
    }

    @Test
    void clientInfoIsKept() throws SQLException {
        try (Connection c = FyrQLDriver.connect(FyrQL.builder().register(new CountingConnector("shop")).build(),
                true)) {
            c.setClientInfo("ApplicationName", "test");
            assertEquals("test", c.getClientInfo("ApplicationName"));
            Properties p = new Properties();
            p.setProperty("ClientUser", "ann");
            c.setClientInfo(p);
            assertNull(c.getClientInfo("ApplicationName"));
            assertEquals("ann", c.getClientInfo().getProperty("ClientUser"));
            c.setClientInfo("ClientUser", null);
            assertTrue(c.getClientInfo().isEmpty());
            c.setNetworkTimeout(Runnable::run, 1000);
            assertEquals(1000, c.getNetworkTimeout());
            assertThrows(SQLException.class, () -> c.isValid(-1));
        }
    }

    /** A call of a method of a connection that may throw. */
    interface ThrowingCall {
        void run() throws SQLException;
    }
}
