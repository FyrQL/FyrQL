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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static net.fyrql.pgwire.TestServer.query;
import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalogs of PostgreSQL that the server builds from the catalog of a source. */
class PgCatalogTest {

    private static final String OID = "SELECT c.oid FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid"
            + " WHERE n.nspname = 'sales' AND c.relname = 'orders'";

    @Test
    void theOidsStayTheSameAcrossConnectionsAndServers() throws Exception {
        String first;
        try (TestServer server = new TestServer()) {
            first = value(server.connect("shop", ""), OID);
            assertEquals(first, value(server.connect("shop", ""), OID));
            assertEquals(first, value(server.connect("shop", ""), "SELECT 'sales.orders'::regclass"));
            assertEquals("1259", value(server.connect("shop", ""), "SELECT 'pg_class'::regclass"));
            assertEquals("23", value(server.connect("shop", ""), "SELECT 'integer'::regtype"));
        }
        try (TestServer server = new TestServer()) {
            assertEquals(first, value(server.connect("shop", ""), OID));
        }
        assertTrue(Integer.parseInt(first) >= PgCatalog.FIRST_OID);
        assertNotEquals(PgCatalog.hashOid("relation shop.sales.orders"), PgCatalog.hashOid(
                "relation shop.main.customers"));
    }

    @Test
    void theColumnsHaveTheTypesOfPostgreSql() throws Exception {
        try (TestServer server = new TestServer()) {
            Connection c = server.connect();
            assertEquals(List.of(List.of("b", "bool", "boolean"), List.of("ti", "int2", "smallint"),
                    List.of("hi", "numeric", "numeric"), List.of("dec", "numeric", "numeric(38,10)"),
                    List.of("v", "text", "text"), List.of("li", "_int4", "integer[]"), List.of("st", "text", "text")),
                    query(c, "SELECT a.attname, t.typname, format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                            + " JOIN pg_class c ON a.attrelid = c.oid JOIN pg_type t ON a.atttypid = t.oid WHERE"
                            + " c.relname = 't' AND a.attname IN ('b', 'ti', 'hi', 'dec', 'v', 'li', 'st')"
                            + " ORDER BY a.attnum"));
            assertEquals(List.of(List.of("mem"), List.of("shop")), query(c, "SELECT datname FROM pg_database WHERE"
                    + " datname IN ('mem', 'shop') ORDER BY 1"));
            assertEquals("3", value(c, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'main'"));
            assertEquals("YES", value(c, "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'tiny'"
                    + " AND column_name = 'v'"));
        }
    }

    @Test
    void aTableCreatedThroughTheServerIsInTheCatalogAtOnce() throws Exception {
        try (TestServer server = new TestServer()) {
            Connection c = server.connect();
            String count = "SELECT count(*) FROM pg_class WHERE relname = 'fresh'";
            assertEquals("0", value(c, count));
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE fresh (a INTEGER)");
            }
            assertEquals("1", value(c, count));
            assertEquals("1", value(server.connect(), count));
            assertEquals("0", value(c, "SELECT count(*) FROM pg_catalog.pg_index"));
        }
    }

    @Test
    void theCatalogsAreThoseOfTheServerOnly() throws Exception {
        try (TestServer server = new TestServer()) {
            assertThrows(Exception.class, () -> server.engine.rows("SELECT * FROM pg_catalog.pg_class"));
            assertThrows(SQLException.class, () -> value(server.connect(), "SELECT * FROM pg_catalog.nope"));
        }
    }
}
