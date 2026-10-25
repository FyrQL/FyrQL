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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Names of tables resolve as in PostgreSQL: along the search path in the
 * database of the connection, with {@code pg_catalog} and
 * {@code information_schema} as schemas of every database; three parts
 * reach the other sources.
 */
class NamesTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String state(Connection c, String sql) {
        return assertThrows(SQLException.class, () -> execute(c, sql)).getSQLState();
    }

    @Test
    void otherDatabasesOnlyByThreeParts() throws SQLException {
        Connection c = server.connect("shop", "");
        assertEquals("42P01", state(c, "SELECT count(*) FROM numbers"));
        assertEquals("42P01", state(c, "SELECT count(*) FROM mem.numbers"));
        assertEquals("1000", value(c, "SELECT count(*) FROM mem.main.numbers"));
        assertEquals("2", value(c, "SELECT count(*) FROM customers"));
        assertEquals("2", value(c, "SELECT count(*) FROM main.customers"));
        assertEquals("2", value(c, "SELECT count(*) FROM shop.main.customers"));
        assertEquals("3", value(c, "SELECT count(*) FROM sales.orders AS o JOIN mem.main.tiny AS t"
                + " ON o.customer = t.k"));
    }

    @Test
    void aSearchPathWithoutSchemasOfTheDatabase() throws SQLException {
        Connection c = server.connect();
        execute(c, "SET search_path = nowhere");
        assertEquals("nowhere", value(c, "SHOW search_path"));
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT current_schema, current_schema()")) {
            assertEquals("current_schema", rs.getMetaData().getColumnLabel(1));
            assertEquals("current_schema", rs.getMetaData().getColumnLabel(2));
            assertTrue(rs.next());
            assertNull(rs.getString(1));
            assertNull(rs.getString(2));
        }
        assertEquals("{pg_catalog}", value(c, "SELECT current_schemas(true)"));
        assertEquals("{}", value(c, "SELECT current_schemas(false)"));
        assertEquals("42P01", state(c, "SELECT * FROM tiny"));
        assertEquals("2", value(c, "SELECT count(*) FROM main.tiny"));
        assertEquals("t", value(c, "SELECT count(*) > 0 FROM pg_class"));
        assertEquals("3F000", state(c, "CREATE TABLE made AS SELECT 1 AS x"));
        execute(c, "RESET search_path");
        assertEquals("main", value(c, "SELECT current_schema"));
        assertEquals("2", value(c, "SELECT count(*) FROM tiny"));
    }

    @Test
    void theSchemasOfThePathInTheirOrder() throws SQLException {
        Connection c = server.connect("shop", "");
        execute(c, "CREATE TABLE sales.customers AS SELECT 7 AS id");
        execute(c, "SET search_path = sales, main");
        assertEquals("{sales,main}", value(c, "SELECT current_schemas(false)"));
        assertEquals("{pg_catalog,sales,main}", value(c, "SELECT current_schemas(true)"));
        assertEquals("sales", value(c, "SELECT current_schema"));
        assertEquals("1", value(c, "SELECT count(*) FROM customers"));
        assertEquals("3", value(c, "SELECT count(*) FROM orders"));
        assertEquals("t", value(c, "SELECT to_regclass('orders') IS NOT NULL"));
        execute(c, "SET search_path TO main, sales");
        assertEquals("2", value(c, "SELECT count(*) FROM customers"));
        assertEquals("3", value(c, "SELECT count(*) FROM orders"));
        // A pg_catalog that the path names keeps its place; a new table goes to the first schema of the database.
        execute(c, "SET search_path = \"$user\", pg_catalog, sales");
        assertEquals("pg_catalog", value(c, "SELECT current_schema()"));
        assertEquals("{pg_catalog,sales}", value(c, "SELECT current_schemas(true)"));
        execute(c, "CREATE TABLE made AS SELECT 1 AS x");
        assertEquals("1", value(c, "SELECT count(*) FROM sales.made"));
        assertEquals("3F000", state(c, "CREATE TABLE nowhere.made AS SELECT 1 AS x"));
    }

    @Test
    void theCatalogsAreSchemasOfEveryDatabase() throws SQLException {
        Connection c = server.connect("shop", "");
        assertEquals("42P01", state(c, "SELECT count(*) FROM columns"));
        assertEquals("t", value(c, "SELECT to_regclass('columns') IS NULL"));
        assertEquals("3", value(c, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'orders'"));
        assertEquals("orders", value(c, "SELECT pg_catalog.pg_class.relname FROM pg_catalog.pg_class"
                + " WHERE relname = 'orders'"));
        assertEquals("orders", value(c, "SELECT pg_class.relname FROM pg_class WHERE pg_class.relname = 'orders'"));
        assertEquals("orders", value(c, "SELECT relname FROM shop.pg_catalog.pg_class WHERE relname = 'orders'"));
        execute(c, "SET search_path = information_schema");
        assertEquals("3", value(c, "SELECT count(*) FROM columns WHERE table_name = 'orders'"));
        assertEquals("information_schema", value(c, "SELECT current_schema"));
    }
}
