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
                String zone = engine.timeZone().getId().equals("Asia/Tokyo") ? "Europe/Berlin" : "Asia/Tokyo";
                a.createStatement().execute("SET TimeZone = '" + zone + "'");
                assertEquals(List.of(zone), StatementTest.rows(a.createStatement().executeQuery("SHOW TimeZone")));
                assertEquals(List.of(engine.timeZone().getId()), StatementTest.rows(b.createStatement()
                        .executeQuery("SHOW TimeZone")), "the connections share the engine, not its settings");
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
