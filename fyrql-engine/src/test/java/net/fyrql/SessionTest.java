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

package net.fyrql;

import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sessions of one engine: each has its own settings, and the engine has one of its own. */
class SessionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** The sources a and b, which bind but cannot be scanned, and mem with a table m, the default source. */
    private static FyrQL engine() {
        return FyrQL.builder().register(new SearchPathTest.Source("a")).register(new SearchPathTest.Source("b"))
                .register(MemoryConnector.builder("mem").table("m", Schema.of(new Schema.Field("x", DataType.INTEGER)),
                        List.<Object[]>of(new Object[] {1})).build())
                .defaultSource("mem").timeZone(UTC).build();
    }

    private static Object value(Session session, String sql) {
        return session.rows(sql).get(0)[0];
    }

    @Test
    void twoSessionsOfAnEngineHaveTheirOwnTimeZones() {
        try (FyrQL engine = engine()) {
            Session berlin = engine.newSession();
            Session tokyo = engine.newSession();
            assertEquals(0L, berlin.update("SET TimeZone = 'Europe/Berlin'"));
            tokyo.set("timezone", "Asia/Tokyo");
            assertEquals(ZoneId.of("Europe/Berlin"), berlin.timeZone());
            assertEquals(ZoneId.of("Asia/Tokyo"), tokyo.timeZone());
            assertEquals(UTC, engine.timeZone(), "the session of the engine keeps its time zone");
            String sql = "SELECT CAST(TIMESTAMPTZ '2024-01-01 12:00:00+00' AS VARCHAR)";
            assertEquals("2024-01-01 13:00:00+01", value(berlin, sql));
            assertEquals("2024-01-01 21:00:00+09", value(tokyo, sql));
            assertEquals("2024-01-01 12:00:00+00", engine.rows(sql).get(0)[0]);
            assertEquals("Europe/Berlin", value(berlin, "SELECT current_setting('TimeZone')"));
            assertEquals("Asia/Tokyo", value(tokyo, "SHOW TimeZone"));
            tokyo.update("RESET TimeZone");
            assertEquals(UTC, tokyo.timeZone());
            assertEquals(ZoneId.of("Europe/Berlin"), berlin.timeZone());
        }
    }

    @Test
    void eachSessionHasItsSearchPath() {
        try (FyrQL engine = engine()) {
            Session a = engine.newSession();
            Session b = engine.newSession();
            a.update("USE a");
            b.update("SET search_path = 'B.Other'");
            assertEquals(new SearchPath("a", "main"), a.searchPath());
            assertEquals(new SearchPath("b", "other"), b.searchPath());
            assertEquals("a_t", a.plan("SELECT * FROM t").schema().name(0));
            assertEquals("b_u", b.plan("SELECT * FROM u").schema().name(0));
            assertEquals("a_x", a.plan("SELECT * FROM read_x('f')").schema().name(0));
            assertEquals("b_x", b.plan("SELECT * FROM read_x('f')").schema().name(0),
                    "the table functions of the default source come first");
            assertEquals("a.main", value(a, "SHOW search_path"));
            assertEquals("b.other", value(b, "SELECT current_setting('search_path')"));
            assertEquals(new SearchPath("mem", "main"), engine.session().searchPath());
            assertEquals(1, engine.rows("SELECT * FROM m").size());

            PreparedQuery q = a.prepare("SELECT * FROM t WHERE a_t = ?");
            assertEquals(new SearchPath("a", "main"), q.searchPath());
            a.update("USE b");
            assertEquals("a_t", a.plan(q, List.of(1)).schema().name(0), "a prepared query keeps its search path");

            SqlException e = assertThrows(SqlException.class, () -> a.update("USE c"));
            assertEquals("No source or schema c; the sources are a, b, mem, the schemas of b are main, other",
                    e.reason());
            e = assertThrows(SqlException.class, () -> a.update("USE c.main"));
            assertEquals("No source c; there are a, b, mem", e.reason());
            e = assertThrows(SqlException.class, () -> a.update("USE a.nope"));
            assertEquals("No schema nope in the source a; there are main, other", e.reason());
            assertEquals(new SearchPath("b", "main"), a.searchPath(), "a failed USE changes nothing");
            a.update("SET search_path = ''");
            assertEquals(new SearchPath(null, "main"), a.searchPath());
            assertEquals("", a.setting("search_path"));
            a.reset("search_path");
            assertEquals(new SearchPath("mem", "main"), a.searchPath());
            assertThrows(SqlException.class, () -> a.update("SET SESSION search_path TO 'OTHER'"),
                    "mem has no schema other");
            b.update("SET SESSION search_path TO 'main'");
            assertEquals(new SearchPath("b", "main"), b.searchPath(), "a schema of the default source, as pgjdbc"
                    + " sets it");
            b.update("USE other");
            assertEquals(new SearchPath("b", "other"), b.searchPath());
            b.use(new SearchPath("a", "other"));
            assertEquals("a_u", b.plan("SELECT * FROM u").schema().name(0));
            assertThrows(IllegalArgumentException.class, () -> b.use(new SearchPath("c", "main")));
        }
    }

    @Test
    void eachSessionHasItsTimeout() {
        try (FyrQL engine = FyrQL.builder().register(new QueryTimeoutTest.EndlessConnector()).threads(1)
                .queryTimeout(Duration.ofHours(1)).maxQueryTimeout(Duration.ofHours(2)).build()) {
            Session s = engine.newSession();
            Session other = engine.newSession();
            assertEquals(Optional.of(Duration.ofHours(1)), s.queryTimeout());
            assertEquals("1h", s.setting("STATEMENT_TIMEOUT"));
            s.update("SET statement_timeout = 200");
            assertEquals(Optional.of(Duration.ofMillis(200)), s.queryTimeout());
            long millis = QueryTimeoutTest.timesOut(() -> s.rows("SELECT count(*) FROM endless.t"));
            assertTrue(millis < 5000, "timed out after " + millis + " ms");
            assertEquals(Optional.of(Duration.ofHours(1)), other.queryTimeout());
            assertEquals(Optional.of(Duration.ofHours(1)), engine.session().queryTimeout());
            assertEquals(2, other.rows("SELECT * FROM endless.t LIMIT 2").size());

            s.update("SET statement_timeout = 0");
            assertEquals(Optional.empty(), s.queryTimeout());
            assertEquals("0", s.setting("statement_timeout"));
            assertEquals(Duration.ofHours(2), engine.timeouts().effective(null, s.timeout()));
            s.update("SET statement_timeout = '3h'");
            assertEquals("3h", s.setting("statement_timeout"));
            assertEquals(Duration.ofHours(2), engine.timeouts().effective(null, s.timeout()),
                    "the upper bound of the engine shortens the timeout of the session");

            s.update("SET statement_timeout = '1h'");
            QueryTimeoutTest.timesOut(() -> {
                try (QueryResult r = s.execute(s.statement("SELECT count(*) FROM endless.t"),
                        QueryContext.withTimeout(Duration.ofMillis(200)))) {
                    r.rows();
                }
            });
            s.reset("statement_timeout");
            assertEquals(Optional.of(Duration.ofHours(1)), s.queryTimeout());
        }
    }

    @Test
    void showAllAndTheSessionOfTheEngine() {
        try (FyrQL engine = engine()) {
            Session s = engine.newSession();
            assertSame(engine, s.engine());
            s.update("SET TimeZone = 'Europe/Berlin'");
            s.update("SET statement_timeout = '90s'");
            assertEquals(List.of(List.of("TimeZone", "Europe/Berlin"), List.of("search_path", "mem.main"),
                    List.of("statement_timeout", "90s")), s.rows("SHOW ALL").stream().map(Arrays::asList).toList());
            assertEquals(List.of("TimeZone", "search_path", "statement_timeout"), List.copyOf(s.settings().keySet()));
            s.update("RESET ALL");
            assertEquals(Map.of("TimeZone", "UTC", "search_path", "mem.main", "statement_timeout", "0"),
                    s.settings());

            engine.update("SET TimeZone = 'Asia/Tokyo'");
            assertEquals(ZoneId.of("Asia/Tokyo"), engine.timeZone());
            assertEquals(ZoneId.of("Asia/Tokyo"), engine.session().timeZone());
            assertEquals("Asia/Tokyo", engine.rows("SHOW TimeZone").get(0)[0]);
            assertEquals(UTC, engine.newSession().timeZone(), "a new session starts with the time zone of the builder");
            assertEquals(UTC, s.timeZone());
            engine.update("RESET TimeZone");
            assertEquals(UTC, engine.timeZone());

            assertEquals("unrecognized configuration parameter \"work_mem\"",
                    assertThrows(IllegalArgumentException.class, () -> s.setting("work_mem")).getMessage());
            assertEquals("Unknown TimeZone 'Nowhere/City'!",
                    assertThrows(SqlException.class, () -> s.set("TimeZone", "Nowhere/City")).reason());
            assertEquals("unrecognized configuration parameter \"work_mem\"",
                    assertThrows(SqlException.class, () -> s.reset("work_mem")).reason());
            assertEquals(UTC, s.timeZone());
        }
    }
}
