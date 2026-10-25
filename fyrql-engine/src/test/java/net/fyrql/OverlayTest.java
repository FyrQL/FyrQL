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

import net.fyrql.catalog.DatabasePath;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A session with sources and functions that the engine and its other sessions do not see. */
class OverlayTest {

    private static FyrQL engine;

    /** {@code twice(INTEGER)}: the argument times two. */
    private static final ScalarFunction TWICE = new ScalarFunction() {
        @Override
        public String name() {
            return "twice";
        }

        @Override
        public DataType resultType(List<DataType> arguments) {
            return arguments.equals(List.of(DataType.INTEGER)) ? DataType.INTEGER : null;
        }

        @Override
        public Object evaluate(List<DataType> argumentTypes, Object[] arguments) {
            return (Integer) arguments[0] * 2;
        }
    };

    @BeforeAll
    static void start() {
        engine = FyrQL.builder().register(MemoryConnector.builder("mem")
                .table("t", Schema.of(new Schema.Field("a", DataType.INTEGER)),
                        List.of(new Object[] {1}, new Object[] {2}))
                .build()).build();
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static Session overlay() {
        MemoryConnector x = MemoryConnector.builder("x")
                .table("t", Schema.of(new Schema.Field("b", DataType.VARCHAR)),
                        List.of(new Object[] {"p"}, new Object[] {"q"}))
                .build();
        FunctionRegistry functions = engine.functions().copy();
        functions.register(TWICE);
        return Overlay.newSession(engine, Map.of("x", x), functions);
    }

    private static List<List<Object>> rows(List<Object[]> rows) {
        return rows.stream().map(Arrays::asList).toList();
    }

    @Test
    void theSessionSeesItsSourcesAndFunctions() {
        Session session = overlay();
        assertEquals(List.of(List.of("p"), List.of("q")), rows(session.rows("SELECT b FROM x.t ORDER BY b")));
        assertEquals(List.of(List.of(2), List.of(4)), rows(session.rows("SELECT twice(a) FROM mem.t ORDER BY 1")));
        // Along the search path of the engine, and joined with a source of the engine.
        assertEquals(List.of(List.of(4L)), rows(session.rows("SELECT count(*) FROM x.t, t")));
        assertTrue(session.explain("SELECT b FROM x.t").contains("Scan"), session.explain("SELECT b FROM x.t"));
        PreparedQuery prepared = session.prepare("SELECT b FROM x.t WHERE b = $1");
        assertEquals(List.of(List.of("q")), rows(session.rows(prepared, List.of("q"))));
        // It writes into its own sources as into those of the engine.
        assertEquals(1, session.update("CREATE TABLE x.u AS SELECT twice(a) AS c FROM mem.t WHERE a = 2"));
        assertEquals(List.of(List.of(4)), rows(session.rows("SELECT c FROM x.u")));
        assertTrue(session.explainAnalyze("SELECT c FROM x.u").contains("Analyzed"));
    }

    @Test
    void theEngineAndOtherSessionsDoNotSeeThem() {
        overlay();
        assertThrows(SqlException.class, () -> engine.rows("SELECT b FROM x.t"));
        assertThrows(SqlException.class, () -> engine.newSession().rows("SELECT twice(a) FROM t"));
        assertThrows(SqlException.class, () -> engine.rows("SELECT twice(1)"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.functions().resolve("twice", List.of(DataType.INTEGER)));
        assertEquals(List.of(List.of(1), List.of(2)), rows(engine.rows("SELECT a FROM t ORDER BY a")));
    }

    @Test
    void namesResolveAsInPostgreSqlAlongTheDatabasePath() {
        Session session = overlay();
        Overlay.use(session, new DatabasePath("mem", List.of("main"), List.of("x")));
        List<List<Object>> pq = List.of(List.of("p"), List.of("q"));
        // The first catalog comes before the schemas of the path; database.catalog.table qualifies its columns.
        assertEquals(pq, rows(session.rows("SELECT b FROM t ORDER BY b")));
        assertEquals(pq, rows(session.rows("SELECT x.t.b FROM x.t ORDER BY 1")));
        assertEquals(pq, rows(session.rows("SELECT mem.x.t.b FROM mem.x.t ORDER BY 1")));
        assertEquals(List.of(List.of(1), List.of(2)), rows(session.rows("SELECT main.t.a FROM main.t ORDER BY 1")));
        assertEquals(List.of(List.of(2)), rows(session.rows("SELECT a FROM mem.main.t WHERE a > 1")));
        SqlException e = assertThrows(SqlException.class, () -> session.update("CREATE TABLE nosuch.u AS SELECT 1"));
        assertEquals("3F000", SqlStates.state(e), e.getMessage());
        Overlay.use(session, new DatabasePath("mem", List.of("nowhere"), List.of("x")));
        e = assertThrows(SqlException.class, () -> session.update("CREATE TABLE u AS SELECT 1"));
        assertEquals("No schema has been selected to create in; set the search_path to a schema of mem",
                e.reason());
        assertEquals("3F000", SqlStates.state(e));
        assertEquals("42P01", SqlStates.state(assertThrows(SqlException.class,
                () -> session.rows("SELECT * FROM main"))));
        // Without the path, the rules of FyrQL again.
        Overlay.use(session, null);
        assertEquals(List.of(List.of(1), List.of(2)), rows(session.rows("SELECT t.a FROM t ORDER BY 1")));
        assertThrows(SqlException.class, () -> session.rows("SELECT x.t.b FROM x.t"));
    }

    @Test
    void aSourceOfTheEngineCannotBeOverlaid() {
        MemoryConnector other = MemoryConnector.builder("mem").build();
        assertThrows(IllegalArgumentException.class,
                () -> Overlay.newSession(engine, Map.of("mem", other), engine.functions()));
    }
}
