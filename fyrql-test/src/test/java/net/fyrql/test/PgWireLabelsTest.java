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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.pgwire.PgWireConfig;
import net.fyrql.pgwire.PgWireServer;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The PostgreSQL wire protocol server names the items of a select list without an alias as PostgreSQL does: each
 * item runs on PostgreSQL and on the server over the same table, and the labels the JDBC driver of PostgreSQL reads
 * must be the same, but for the forms whose written shape the engine no longer knows.
 */
class PgWireLabelsTest {

    /** The items, each selected alone from {@code t}. */
    private static final List<String> ITEMS = List.of(
            "a", "t.a", "b", "\"b\"", "lower(b)", "upper(b)", "length(b)", "abs(a)", "round(x, 1)",
            "count(*)", "count(a)", "count(DISTINCT a)", "sum(a)", "avg(a)", "min(d)", "max(d)",
            "count(*) FILTER (WHERE a > 1)",
            "a + 1", "-a", "1", "-1", "1.5", "'x'", "NULL", "true", "false",
            "a = 1", "NOT flag", "a IS NULL", "a IS NOT NULL", "a BETWEEN 1 AND 2", "a IN (1, 2)", "b LIKE 'x%'",
            "b || 'x'", "a IS DISTINCT FROM 1",
            "CAST(a AS bigint)", "a::text", "1::int", "'1'::int", "CAST(1.5 AS numeric)", "CAST(NULL AS date)",
            "CAST(b AS varchar)", "CAST(a AS double precision)", "CAST(a + 1 AS bigint)",
            "DATE '2026-01-01'", "TIMESTAMP '2026-01-01 00:00:00'", "INTERVAL '1 day'",
            "CASE WHEN a > 1 THEN 1 ELSE 0 END", "CAST(CASE WHEN a > 1 THEN 1 END AS int)",
            "COALESCE(a, 0)", "NULLIF(a, 0)", "GREATEST(a, 1)", "LEAST(a, 1)", "CAST(COALESCE(a, 0) AS bigint)",
            "EXISTS (SELECT 1 FROM t)", "NOT EXISTS (SELECT 1 FROM t)", "(SELECT max(a) FROM t)", "(SELECT 1)",
            "(SELECT a AS z FROM t LIMIT 1)", "(SELECT b FROM t LIMIT 1)",
            "row_number() OVER (ORDER BY a)", "sum(a) OVER ()", "current_date",
            "trim(b)", "ltrim(b)", "substring(b FROM 1 FOR 2)", "substring(b, 1, 2)", "position('a' IN b)",
            "b ~ 'x'", "b !~* 'x'", "a & 1", "'pg_class'::regclass", "b::regclass",
            "concat(b, 'x')", "date_trunc('month', d)", "now()", "version()", "current_user",
            "extract(year FROM d)", "date_part('year', d)", "CAST('pg_class' AS regclass)",
            "CAST(GREATEST(a, 1) AS bigint)", "CAST(-a AS bigint)", "array(SELECT a FROM t)");

    /** Queries of several columns, whose labels may repeat, and that use the labels of a subquery. */
    private static final List<String> QUERIES = List.of(
            "SELECT lower(b), upper(b), lower(b), a, a FROM t",
            "SELECT * FROM (SELECT lower(b), count(*) FROM t GROUP BY b) s",
            "SELECT s.lower, s.count FROM (SELECT lower(b), count(*) FROM t GROUP BY b) s",
            "SELECT * FROM (SELECT 1, 2) s",
            "SELECT count(*) FROM t ORDER BY count",
            "SELECT lower(b) FROM t GROUP BY lower",
            "WITH w AS (SELECT max(a) FROM t) SELECT max FROM w");

    /**
     * The items whose labels differ from those of PostgreSQL, with the label of the server: forms that the parser
     * turns into a call of another function, whose written shape the engine no longer knows.
     */
    private static final Map<String, String> DIFFERENT = Map.of("position('a' IN b)", "strpos",
            "extract(year FROM d)", "date_part");

    /** The queries whose labels differ: the columns of a subquery get names of their own, as in DuckDB. */
    private static final Map<String, String> DIFFERENT_QUERIES = Map.of("SELECT * FROM (SELECT 1, 2) s",
            "[?column?, ?column?_1]");

    @Test
    void theServerNamesItemsAsPostgreSql() throws Exception {
        TestDatabase pg = TestDatabases.postgres().orElse(null);
        assumeTrue(pg != null, "no PostgreSQL");
        pg.execute("CREATE TABLE t (a INTEGER, b VARCHAR(20), d DATE, x NUMERIC(10, 2), flag BOOLEAN)",
                "INSERT INTO t VALUES (1, 'pg_class', DATE '2026-01-02', 1.25, true)");
        try (FyrQL engine = FyrQL.builder().register(pg.connector("pg")).defaultSource("pg").build();
                PgWireServer server = PgWireServer.builder(engine).config(PgWireConfig.defaults().withPort(0))
                        .build()) {
            server.start();
            Properties p = new Properties();
            p.setProperty("user", "test");
            List<String> differences = new ArrayList<>();
            try (Connection postgres = pg.connect();
                    Connection fyrql = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + server.port()
                            + "/pg", p)) {
                for (String item : ITEMS) {
                    String sql = "SELECT " + item + " FROM t";
                    String expected = labels(postgres, sql).toString();
                    String actual = labelsOrError(fyrql, sql);
                    String wanted = DIFFERENT.containsKey(item) ? List.of(DIFFERENT.get(item)).toString() : expected;
                    if (!wanted.equals(actual)) {
                        differences.add(item + ": PostgreSQL " + expected + ", FyrQL " + actual);
                    }
                }
                for (String sql : QUERIES) {
                    String expected = labels(postgres, sql).toString();
                    String actual = labelsOrError(fyrql, sql);
                    if (!DIFFERENT_QUERIES.getOrDefault(sql, expected).equals(actual)) {
                        differences.add(sql + ": PostgreSQL " + expected + ", FyrQL " + actual);
                    }
                }
            }
            assertEquals(List.of(), differences);
        }
    }

    private static List<String> labels(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            ResultSetMetaData m = r.getMetaData();
            List<String> labels = new ArrayList<>();
            for (int i = 1; i <= m.getColumnCount(); i++) {
                labels.add(m.getColumnLabel(i));
            }
            return labels;
        }
    }

    private static String labelsOrError(Connection c, String sql) {
        try {
            return labels(c, sql).toString();
        } catch (SQLException e) {
            return "error: " + e.getMessage();
        }
    }
}
