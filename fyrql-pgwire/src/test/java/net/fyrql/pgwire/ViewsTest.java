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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Views over the wire: created and dropped in SQL, and in the catalogs as PostgreSQL has them. */
class ViewsTest {

    private static final String DEFINITION = "SELECT id, amount AS total FROM sales.orders WHERE amount > 2";

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static List<List<String>> query(Connection c, String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> column(ResultSet rs, String name) throws SQLException {
        List<String> values = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                values.add(rs.getString(name));
            }
        }
        return values;
    }

    @Test
    void aViewIsInTheCatalogsAsPostgreSqlHasIt() throws SQLException {
        Connection c = server.connect("shop", "");
        try (Statement s = c.createStatement()) {
            assertFalse(s.execute("CREATE VIEW sales.big AS " + DEFINITION));
            assertEquals(0, s.getUpdateCount());
        }
        try {
            assertEquals(List.of(List.of("10", "5.50"), List.of("11", "7.25")),
                    query(c, "SELECT * FROM sales.big ORDER BY id"));
            // Another client sees it, with the names of its columns.
            Connection other = server.connect("shop", "");
            try (Statement s = other.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM sales.big")) {
                assertEquals("total", rs.getMetaData().getColumnLabel(2));
            }

            DatabaseMetaData md = c.getMetaData();
            assertEquals(List.of("big"), column(md.getTables(null, "sales", "%", new String[] {"VIEW"}),
                    "TABLE_NAME"));
            assertEquals(List.of("orders"), column(md.getTables(null, "sales", "%", new String[] {"TABLE"}),
                    "TABLE_NAME"));
            assertEquals(List.of("id", "total"), column(md.getColumns(null, "sales", "big", "%"), "COLUMN_NAME"));

            assertEquals(List.of(List.of("big", DEFINITION)),
                    query(c, "SELECT viewname, definition FROM pg_catalog.pg_views WHERE schemaname = 'sales'"));
            assertEquals(List.of(List.of("orders")),
                    query(c, "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'sales'"));
            assertEquals(List.of(List.of("big", "v", "2")), query(c, "SELECT relname, relkind, relnatts FROM"
                    + " pg_catalog.pg_class WHERE relname = 'big'"));
            assertEquals(List.of(List.of("big", "VIEW"), List.of("orders", "BASE TABLE")), query(c, "SELECT"
                    + " table_name, table_type FROM information_schema.tables WHERE table_schema = 'sales'"
                    + " ORDER BY table_name"));
            assertEquals(List.of(List.of("big", DEFINITION, "NONE", "NO")), query(c, "SELECT table_name,"
                    + " view_definition, check_option, is_updatable FROM information_schema.views"));
            assertEquals(List.of(Arrays.asList(DEFINITION, DEFINITION, null)), query(c, "SELECT"
                    + " pg_catalog.pg_get_viewdef('sales.big'::regclass, true), pg_get_viewdef('sales.big'),"
                    + " pg_get_viewdef('sales.orders'::regclass)"));
            // A view is no table to write.
            SQLException e = assertThrows(SQLException.class,
                    () -> c.createStatement().executeUpdate("INSERT INTO sales.big VALUES (1, 2)"));
            assertEquals("42809", e.getSQLState());
        } finally {
            try (Statement s = c.createStatement()) {
                s.execute("DROP VIEW sales.big");
            }
        }
        assertEquals(List.of(), column(c.getMetaData().getTables(null, "sales", "%", new String[] {"VIEW"}),
                "TABLE_NAME"));
    }

    @Test
    void theTagsAreThoseOfPostgreSql() {
        assertEquals("CREATE VIEW", net.fyrql.pgwire.Connection.writeTag("CREATE VIEW v AS SELECT 1", 0));
        assertEquals("CREATE VIEW", net.fyrql.pgwire.Connection.writeTag("create or replace view v (a) as"
                + " select 1", 0));
        assertEquals("DROP VIEW", net.fyrql.pgwire.Connection.writeTag("DROP VIEW IF EXISTS v", 0));
        assertEquals("UPDATE 2", net.fyrql.pgwire.Connection.writeTag("update t set v = 1", 2));
        assertEquals("DELETE 0", net.fyrql.pgwire.Connection.writeTag("DELETE FROM t", 0));
        assertEquals("MERGE 3", net.fyrql.pgwire.Connection.writeTag("MERGE INTO t USING s ON t.k = s.k"
                + " WHEN MATCHED THEN DELETE", 3));
        assertEquals("SELECT 3", net.fyrql.pgwire.Connection.writeTag("CREATE TABLE t AS SELECT 1", 3));
        assertEquals("DROP TABLE", net.fyrql.pgwire.Connection.writeTag("DROP TABLE t", 0));
    }
}
