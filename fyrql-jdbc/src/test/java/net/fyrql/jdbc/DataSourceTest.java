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
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Connections from a data source, by URL or over a shared engine. */
class DataSourceTest {

    @Test
    void eachConnectionOfAUrlHasAnEngineOfItsOwn() throws SQLException {
        FyrQLDataSource ds = new FyrQLDataSource("jdbc:fyrql:?pushdown=false");
        ds.setProperty("codegen", "off");
        assertEquals("jdbc:fyrql:?pushdown=false", ds.getUrl());
        try (Connection a = ds.getConnection(); Connection b = ds.getConnection("sa", "secret")) {
            FyrQL engine = a.unwrap(FyrQL.class);
            assertNotSame(engine, b.unwrap(FyrQL.class));
            assertEquals(CodegenMode.OFF, engine.codegen());
            assertFalse(engine.pushdown());
            assertEquals(List.of(1), DriverTest.first(a, "SELECT 1"));
        }
        ds.setProperty("codegen", null);
        ds.setUrl("jdbc:fyrql:");
        try (Connection c = ds.getConnection()) {
            assertTrue(c.unwrap(FyrQL.class).pushdown());
        }
        assertThrows(IllegalArgumentException.class, () -> ds.setUrl("jdbc:h2:mem:"));
        assertEquals("jdbc:fyrql:", new FyrQLDataSource().getUrl());
    }

    @Test
    void connectionsOverAnEngineShareItAndDoNotCloseIt() throws SQLException {
        ConnectionTest.CountingConnector shop = new ConnectionTest.CountingConnector("shop");
        try (FyrQL engine = FyrQL.builder().register(shop).build()) {
            DataSource ds = new FyrQLDataSource(engine);
            try (Connection a = ds.getConnection(); Connection b = ds.getConnection()) {
                assertSame(engine, a.unwrap(FyrQL.class));
                assertSame(engine, b.unwrap(FyrQL.class));
            }
            assertEquals(0, shop.closes.get());
            assertNull(((FyrQLDataSource) ds).getUrl());
            assertThrows(IllegalStateException.class, () -> ((FyrQLDataSource) ds).setUrl("jdbc:fyrql:"));
            assertTrue(ds.isWrapperFor(FyrQLDataSource.class));
            assertSame(ds, ds.unwrap(DataSource.class));
            ds.setLoginTimeout(5);
            assertEquals(5, ds.getLoginTimeout());
            assertEquals("net.fyrql.jdbc", ds.getParentLogger().getName());
        }
        assertEquals(1, shop.closes.get());
    }
}
