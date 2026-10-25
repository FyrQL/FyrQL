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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The rules that rewrite the forms of PostgreSQL into those of the engine, one by one. */
class PgSqlTest {

    /** A connection of the user {@code ann} on the schema {@code main}, which has the table {@code orders}. */
    private static final PgSql.Context CONTEXT = new PgSql.Context() {
        @Override
        public String user() {
            return "ann";
        }

        @Override
        public String version() {
            return "PostgreSQL 18.0 (FyrQL 1)";
        }

        @Override
        public List<String> schemas(boolean implicit) {
            return implicit ? List.of("pg_catalog", "main") : List.of("main");
        }

        @Override
        public String setting(String name) {
            return name.equals("server_version_num") ? "180000" : null;
        }

        @Override
        public int relation(String name) {
            if (name.equals("orders") || name.equals("\"main\".\"orders\"") || name.equals("main.orders")) {
                return 16_400;
            }
            throw new PgException("42P01", "relation \"" + name + "\" does not exist");
        }

        @Override
        public int backend() {
            return 7;
        }
    };

    private static String rewrite(String sql) {
        return PgSql.rewrite(sql, CONTEXT);
    }

    @Test
    void eachRule() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("SELECT current_user, session_user, user, t.user FROM t",
                        "SELECT 'ann' AS \"current_user\", 'ann' AS \"session_user\", 'ann' AS \"user\", t.user"
                                + " FROM t"),
                Map.entry("SELECT lower(current_user), current_user AS u, current_user::text",
                        "SELECT lower('ann'), 'ann' AS u, 'ann'::text"),
                Map.entry("select current_catalog, current_schema, current_schema(), pg_catalog.current_schema()",
                        "select current_database() AS \"current_catalog\", 'main' AS \"current_schema\","
                                + " 'main' AS \"current_schema\", 'main' AS \"current_schema\""),
                Map.entry("SELECT upper(current_schema()) AS s, current_schema = 'main'",
                        "SELECT upper('main') AS s, 'main' = 'main'"),
                Map.entry("SELECT version(), pg_catalog.version(), pg_backend_pid()",
                        "SELECT 'PostgreSQL 18.0 (FyrQL 1)' AS \"version\", 'PostgreSQL 18.0 (FyrQL 1)' AS \"version\","
                                + " 7 AS \"pg_backend_pid\""),
                Map.entry("SELECT current_schemas(true), current_schemas(false)",
                        "SELECT CAST(['pg_catalog', 'main'] AS VARCHAR[]) AS \"current_schemas\","
                                + " CAST(['main'] AS VARCHAR[]) AS \"current_schemas\""),
                Map.entry("SELECT current_setting('server_version_num'), current_setting('TimeZone', true),"
                        + " current_setting('nope', true)",
                        "SELECT '180000' AS \"current_setting\", current_setting('TimeZone') AS \"current_setting\","
                                + " NULL AS \"current_setting\""),
                Map.entry("SELECT pg_catalog.lower(x)::pg_catalog.text FROM pg_catalog.pg_class",
                        "SELECT lower(x)::text FROM pg_catalog.pg_class"),
                Map.entry("SELECT 'orders'::regclass, CAST('\"main\".\"orders\"' AS regclass), 'int4'::regtype,"
                        + " 'pg_catalog.array_in'::regproc, '42'::oid",
                        "SELECT 16400, 16400, 23, 'array_in', 42"),
                Map.entry("SELECT c.oid::regclass, a.atttypid::regtype, x::name, y::\"char\", '{a,b}'::text[]",
                        "SELECT to_regclass(c.oid), a.atttypid::INTEGER, x::VARCHAR, y::VARCHAR,"
                                + " CAST(['a', 'b'] AS VARCHAR[])"),
                Map.entry("SELECT a FROM t WHERE b COLLATE \"C\" = c COLLATE pg_catalog.default",
                        "SELECT a FROM t WHERE b  = c "),
                Map.entry("SELECT a ~ 'x', f(a)::text !~ '^p', a ~* $1, a OPERATOR(pg_catalog.~) 'y' FROM t",
                        "SELECT regexp_matches(a, 'x'), (NOT regexp_matches(f(a)::text, '^p')),"
                                + " regexp_matches(a, $1, 'i'), regexp_matches(a, 'y') FROM t"),
                Map.entry("SELECT n.nspname = ANY(current_schemas(true)), x <> ALL (l), y = ANY (SELECT 1)",
                        "SELECT list_contains(CAST(['pg_catalog', 'main'] AS VARCHAR[]), n.nspname),"
                                + " (NOT list_contains(l, x)), y = ANY (SELECT 1)"),
                Map.entry("SELECT s.r FROM generate_series(1, 3) AS s(r), pg_catalog.pg_get_keywords()",
                        "SELECT s.r FROM unnest(generate_series(1, 3)) AS s(r), pg_catalog.pg_get_keywords"),
                Map.entry("SELECT (information_schema._pg_expandarray(i.indkey)).n,"
                        + " information_schema._pg_expandarray(i.indkey) AS keys, (r.keys).x FROM i",
                        "SELECT CAST(NULL AS INTEGER), CAST(NULL AS STRUCT(x SMALLINT, n INTEGER)) AS keys,"
                                + " r.keys.x FROM i"),
                Map.entry("SELECT E'it\\'s', $t$a'b$t$, \"substring\"('abc', 2)",
                        "SELECT E'it''s', 'a''b', substring('abc', 2)"),
                Map.entry("SELECT x AS update, y AS table FROM t", "SELECT x AS \"update\", y AS \"table\" FROM t"),
                Map.entry("CREATE TABLE a AS SELECT 1", "CREATE TABLE a AS SELECT 1"),
                Map.entry("SELECT array(select rolname from pg_roles where oid > 1 order by 1)",
                        "SELECT (SELECT list(rolname ORDER BY rolname) from pg_roles where oid > 1)"),
                Map.entry("SELECT * FROM generate_series(0, 2) s, pg_attribute WHERE x = '{0}'",
                        "SELECT * FROM unnest(generate_series(0, 2)) AS s(s), pg_attribute WHERE x = [0]"),
                Map.entry("SELECT 1", "SELECT 1"));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            assertEquals(c.getValue(), rewrite(c.getKey()), c.getKey());
        }
        assertEquals("42P01", assertThrows(PgException.class, () -> rewrite("SELECT 'nope'::regclass")).state());
    }

    @Test
    void parametersTakeTheTypesTheClientNames() {
        assertEquals("SELECT CAST($1 AS INTEGER), $2, $1::text FROM t LIMIT $3",
                PgSql.typeParameters("SELECT $1, $2, $1::text FROM t LIMIT $3",
                        new int[] {PgTypes.INT4, PgTypes.NUMERIC, PgTypes.INT8}));
        assertEquals("SELECT $1", PgSql.typeParameters("SELECT $1", new int[] {0}));
    }
}
