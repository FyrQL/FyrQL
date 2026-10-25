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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static net.fyrql.pgwire.TestServer.query;
import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A connection of pgjdbc: its start, its database and search path, and the settings of the server. */
class ConnectionTest {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void aClientConnectsAndQueries() throws SQLException {
        Connection c = server.connect();
        assertEquals("1", value(c, "SELECT 1"));
        assertEquals(List.of(List.of("1", "one"), List.of("2", "two")), query(c, "SELECT k, v FROM tiny ORDER BY k"));
        assertEquals("1000", value(c, "SELECT count(*) FROM numbers"));
        assertTrue(c.isValid(5));
        assertEquals("PostgreSQL", c.getMetaData().getDatabaseProductName());
        assertEquals("18.0", c.getMetaData().getDatabaseProductVersion());
        assertEquals("test", value(c, "SELECT current_user"));
        assertEquals("test", value(c, "SELECT session_user"));
        assertTrue(value(c, "SELECT version()").startsWith("PostgreSQL 18.0 (FyrQL "), value(c, "SELECT version()"));
        assertEquals("mem", value(c, "SELECT current_database()"));
        assertEquals("mem", value(c, "select current_catalog"));
        assertEquals("main", value(c, "SELECT current_schema()"));
        assertEquals("{pg_catalog,main}", value(c, "SELECT current_schemas(true)"));
    }

    @Test
    void theDatabaseIsASourceAndItsSchemasAreThoseOfPostgreSql() throws SQLException {
        Connection c = server.connect("shop", "");
        assertEquals("shop", value(c, "SELECT current_database()"));
        // The default schema of a database, here public of H2, is main in FyrQL.
        assertEquals("main", value(c, "SELECT current_schema()"));
        assertEquals("2", value(c, "SELECT count(*) FROM customers"));
        c.setSchema("sales");
        assertEquals("sales", c.getSchema());
        assertEquals("3", value(c, "SELECT count(*) FROM orders"));
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path = \"$user\", nope, main");
        }
        assertEquals("\"$user\", nope, main", value(c, "SHOW search_path"));
        assertEquals("main", value(c, "SELECT current_schema()"));
        // A name of three parts reaches every source.
        assertEquals("1000", value(c, "SELECT count(*) FROM mem.main.numbers"));
        SQLException e = assertThrows(SQLException.class, () -> server.connect("nope", ""));
        assertEquals("3D000", e.getSQLState());
    }

    @Test
    void theSettingsOfTheServerAndOfTheSession() throws SQLException {
        Connection c = server.connect();
        try (Statement s = c.createStatement()) {
            s.execute("SET application_name = 'tests'");
            assertEquals("tests", value(c, "SHOW application_name"));
            s.execute("SET TIME ZONE 'Asia/Tokyo'");
            assertEquals("Asia/Tokyo", value(c, "SHOW TimeZone"));
            assertEquals("2024-01-01 21:00:00+09", value(c,
                    "SELECT CAST(TIMESTAMPTZ '2024-01-01 12:00:00+00' AS VARCHAR)"));
            s.execute("RESET TimeZone");
            s.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            assertEquals("serializable", value(c, "SHOW default_transaction_isolation"));
            assertEquals("read committed", value(c, "SHOW TRANSACTION ISOLATION LEVEL"));
            s.execute("SET extra_float_digits = 3");
            s.execute("SET myapp.flag TO on");
            assertEquals("on", value(c, "SHOW myapp.flag"));
            assertEquals("180000", value(c, "SELECT current_setting('server_version_num')"));
            s.execute("SET statement_timeout = '5s'");
            assertEquals("5s", value(c, "SHOW statement_timeout"));
            assertFalse(query(c, "SHOW ALL").isEmpty());
            SQLException e = assertThrows(SQLException.class, () -> s.execute("SET nope = 1"));
            assertEquals("42704", e.getSQLState());
            e = assertThrows(SQLException.class, () -> s.execute("SET DateStyle = 'German'"));
            assertEquals("0A000", e.getSQLState());
            e = assertThrows(SQLException.class, () -> s.execute("SET server_version = '9.0'"));
            assertEquals("55P02", e.getSQLState());
            s.execute("RESET ALL");
            assertEquals("", value(c, "SHOW application_name"));
        }
    }
}
