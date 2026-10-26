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

package net.fyrql.sql.bind;

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.Position;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CREATE VIEW}, {@code DROP VIEW} and queries that read views, over a
 * catalog that keeps the views as the engine does.
 */
class ViewBinderTest {

    /**
     * Tables in the schemas main and other of a source s, a search schema
     * along which names of one part resolve, and the views that the
     * statements create.
     */
    private static final class Views implements CatalogView {
        final Map<TableRef, Schema> tables = new LinkedHashMap<>();
        final Map<TableRef, ViewDefinition> views = new LinkedHashMap<>();
        String schema = "main";
        boolean postgresNames;

        Views() {
            tables.put(new TableRef("s", "main", "t"), Schema.of(new Schema.Field("a", DataType.INTEGER),
                    new Schema.Field("b", DataType.VARCHAR)));
            tables.put(new TableRef("s", "main", "u"), Schema.of(new Schema.Field("c", DataType.BIGINT)));
            tables.put(new TableRef("s", "other", "t"), Schema.of(new Schema.Field("z", DataType.DATE)));
        }

        @Override
        public TableRef resolve(List<String> name) {
            return find(name).orElseThrow(() -> new SqlException("Table with name " + String.join(".", name)
                    + " does not exist", Position.UNKNOWN));
        }

        @Override
        public Optional<TableRef> find(List<String> name) {
            TableRef table = new TableRef("s", name.size() > 1 ? name.get(name.size() - 2) : schema,
                    name.get(name.size() - 1));
            return views.containsKey(table) || tables.containsKey(table) ? Optional.of(table) : Optional.empty();
        }

        @Override
        public TableRef resolveNew(List<String> name) {
            return new TableRef("s", schema, name.get(name.size() - 1));
        }

        @Override
        public TableRef resolveView(List<String> name) {
            return resolveNew(name);
        }

        @Override
        public Optional<ViewDefinition> view(TableRef table) {
            return Optional.ofNullable(views.get(table));
        }

        @Override
        public boolean postgresNames() {
            return postgresNames;
        }

        @Override
        public Schema schema(TableRef table) {
            Schema s = tables.get(table);
            if (s == null) {
                throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
            }
            return s;
        }

        @Override
        public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
            throw new SqlException("No table functions", Position.UNKNOWN);
        }

        /** Binds a statement, and keeps a view it creates or drops it, as running it does. */
        BoundStatement run(String sql) {
            BoundStatement s = new Binder(this, FunctionRegistry.standard()).bind(SqlParser.parseStatement(sql));
            if (s instanceof BoundStatement.CreateView v) {
                views.put(v.view(), v.definition());
            } else if (s instanceof BoundStatement.DropView d && d.view() != null) {
                views.remove(d.view());
            }
            return s;
        }

        PlanNode plan(String sql) {
            return assertInstanceOf(BoundStatement.Query.class, run(sql)).plan();
        }

        String error(String sql) {
            SqlException e = assertThrows(SqlException.class, () -> run(sql), sql);
            return e.reason() + "@" + e.position().line() + ":" + e.position().column();
        }
    }

    private static List<String> names(PlanNode plan) {
        return plan.schema().fields().stream().map(Schema.Field::name).toList();
    }

    private static List<TableRef> scans(PlanNode plan) {
        return Plans.scans(plan).stream().map(PlanNode.Scan::table).toList();
    }

    @Test
    void createViewBindsItsQueryAndKeepsWhatItsNamesResolveTo() {
        Views c = new Views();
        BoundStatement.CreateView v = assertInstanceOf(BoundStatement.CreateView.class,
                c.run("CREATE VIEW v (x) AS SELECT a, b || '!' AS b2 FROM t WHERE a > 0"));
        assertEquals(new TableRef("s", "main", "v"), v.view());
        assertEquals(BoundStatement.CreateMode.CREATE, v.mode());
        assertEquals(BoundStatement.COUNT, v.schema());
        assertTrue(v.writes());
        ViewDefinition d = v.definition();
        assertEquals("SELECT a, b || '!' AS b2 FROM t WHERE a > 0", d.text());
        assertEquals(Schema.of(new Schema.Field("x", DataType.INTEGER), new Schema.Field("b2", DataType.VARCHAR)),
                d.schema());
        assertEquals(Map.of(List.of("t"), new TableRef("s", "main", "t")), d.names());
        assertEquals(BoundStatement.CreateMode.OR_REPLACE, ((BoundStatement.CreateView) c.run(
                "CREATE OR REPLACE VIEW v AS SELECT 1")).mode());
        assertEquals(BoundStatement.CreateMode.IF_NOT_EXISTS, ((BoundStatement.CreateView) c.run(
                "CREATE VIEW IF NOT EXISTS w AS SELECT 1")).mode());
    }

    @Test
    void aQueryReadsAViewAsATableOfItsColumns() {
        Views c = new Views();
        c.run("CREATE VIEW v (x) AS SELECT a, b FROM t WHERE a > 0");
        PlanNode plan = c.plan("SELECT v.x, b FROM v WHERE x < 10");
        assertEquals(List.of("x", "b"), names(plan));
        assertEquals(List.of(new TableRef("s", "main", "t")), scans(plan));
        assertEquals(List.of("x"), names(c.plan("SELECT s.main.v.x FROM v")));
        assertEquals(List.of("y"), names(c.plan("SELECT w.y FROM v AS w (y)")));
        assertEquals(List.of("x", "b", "c"), names(c.plan("SELECT * FROM v JOIN u ON x = c")));
        // A view of views, and a view in a common table expression and a subquery.
        c.run("CREATE VIEW w AS SELECT x + 1 AS y FROM v");
        plan = c.plan("WITH q AS (SELECT * FROM w) SELECT y FROM q WHERE y IN (SELECT x FROM v)");
        assertEquals(List.of(new TableRef("s", "main", "t"), new TableRef("s", "main", "t")), scans(plan));
    }

    @Test
    void theNamesOfAViewKeepTheirTables() {
        Views c = new Views();
        c.run("CREATE VIEW v AS SELECT * FROM t");
        c.schema = "other";
        assertEquals(List.of("z"), names(c.plan("SELECT * FROM t")));
        assertEquals(List.of("a", "b"), names(c.plan("SELECT * FROM main.v")));
        assertEquals(List.of(new TableRef("s", "main", "t")), scans(c.plan("SELECT * FROM main.v")));
    }

    @Test
    void theColumnsOfAViewKeepTheNamesTheyGotWhenItWasCreated() {
        Views c = new Views();
        c.run("CREATE VIEW v AS SELECT count(*), lower(b) FROM t GROUP BY b");
        c.postgresNames = true;
        c.run("CREATE VIEW w AS SELECT count(*), lower(b) FROM t GROUP BY b");
        assertEquals(List.of("count", "lower"), names(c.plan("SELECT count(*), lower(b) FROM t GROUP BY b")));
        assertEquals(List.of("count_star()", "lower(b)"), names(c.plan("SELECT * FROM main.v")));
        c.postgresNames = false;
        assertEquals(List.of("count", "lower"), names(c.plan("SELECT * FROM w")));
    }

    @Test
    void aViewMayNotReadItself() {
        Views c = new Views();
        c.run("CREATE VIEW v AS SELECT a FROM t");
        c.run("CREATE VIEW w AS SELECT a FROM v");
        assertEquals("View v would read itself@1:24", c.error("CREATE OR REPLACE VIEW v AS SELECT * FROM w"));
        assertEquals("View v would read itself@1:24", c.error("CREATE OR REPLACE VIEW v AS SELECT * FROM v"));
        // Should a cycle get in all the same, a query that reads it fails.
        TableRef v = new TableRef("s", "main", "v");
        ViewDefinition d = c.views.get(v);
        c.views.put(v, new ViewDefinition(d.query(), d.text(), d.schema(), Map.of(List.of("t"), v), d.paths(),
                false));
        assertEquals("In view s.main.v: View s.main.v reads itself@1:15", c.error("SELECT * FROM v"));
    }

    @Test
    void viewErrors() {
        Views c = new Views();
        assertEquals("Column with name a appears more than once in view d; give the columns names of their own@1:13",
                c.error("CREATE VIEW d AS SELECT a, a FROM t"));
        assertEquals("Column with name A appears more than once in view d; give the columns names of their own@1:13",
                c.error("CREATE VIEW d (a, A) AS SELECT a, b FROM t"));
        assertEquals("table \"d\" has 2 columns available but 3 columns specified@1:13",
                c.error("CREATE VIEW d (x, y, z) AS SELECT a, b FROM t"));
        assertEquals("Table with name nope does not exist@1:32", c.error("CREATE VIEW d AS SELECT * FROM nope"));
        c.run("CREATE VIEW v AS SELECT a FROM t");
        c.run("CREATE VIEW vu AS SELECT * FROM u");
        assertEquals("Cannot insert into view v: a view has no rows of its own@1:13",
                c.error("INSERT INTO v VALUES (1)"));
        assertEquals("Cannot copy into view v: a view has no rows of its own@1:6", c.error("COPY v FROM 'v.csv'"));
        assertEquals("Cannot create table v: there is a view of that name@1:14", c.error("CREATE TABLE v (a INTEGER)"));
        assertEquals("v is a view; drop it with DROP VIEW@1:12", c.error("DROP TABLE v"));
        assertEquals("v is a view; drop it with DROP VIEW@1:22", c.error("DROP TABLE IF EXISTS v"));
        assertEquals("t is a table, not a view; drop it with DROP TABLE@1:11", c.error("DROP VIEW t"));
        assertEquals("View with name nope does not exist@1:11", c.error("DROP VIEW nope"));
        assertNull(assertInstanceOf(BoundStatement.DropView.class, c.run("DROP VIEW IF EXISTS nope")).view());

        // The tables of a view change after it was created.
        c.tables.remove(new TableRef("s", "main", "u"));
        assertEquals("In view s.main.vu: Table with name s.main.u does not exist@1:18",
                c.error("SELECT 1 FROM t, vu"));
        c.tables.put(new TableRef("s", "main", "u"), Schema.of(new Schema.Field("c", DataType.BIGINT),
                new Schema.Field("e", DataType.BIGINT)));
        assertEquals("View s.main.vu has 1 columns, but its query now gives 2; its tables have changed, so create"
                + " it again@1:15", c.error("SELECT * FROM vu"));

        assertEquals(new TableRef("s", "main", "v"),
                assertInstanceOf(BoundStatement.DropView.class, c.run("DROP VIEW v")).view());
        assertEquals(List.of("a", "b"), names(c.plan("SELECT * FROM t")));
        assertEquals("Table with name v does not exist@1:15", c.error("SELECT * FROM v"));
    }
}
