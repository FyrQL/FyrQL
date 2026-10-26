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

import net.fyrql.catalog.CatalogTables;
import net.fyrql.catalog.NamePattern;
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.Sink;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.Position;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Views end to end: {@code CREATE VIEW}, {@code DROP VIEW}, the views of the builder, and their lists. */
class ViewTest {

    record Person(int id, String name, int age) {
    }

    record Order(int id, int person, double amount) {
    }

    private static FyrQL.Builder builder() {
        return FyrQL.builder()
                .register(MemoryConnector.builder("mem")
                        .table("people", Person.class, List.of(new Person(1, "Ann", 34), new Person(2, "Bob", 17),
                                new Person(3, "Cy", 52)))
                        .build())
                .register(MemoryConnector.builder("shop")
                        .table("orders", Order.class, List.of(new Order(10, 1, 5.5), new Order(11, 3, 2.0),
                                new Order(12, 1, 1.5)))
                        .build())
                .defaultSource("mem");
    }

    private static List<List<Object>> rows(FyrQL engine, String sql) {
        return rows(engine.rows(sql));
    }

    private static List<List<Object>> rows(List<Object[]> rows) {
        return rows.stream().map(Arrays::asList).toList();
    }

    private static String fails(FyrQL engine, String sql) {
        return assertThrows(SqlException.class, () -> engine.update(sql), sql).reason();
    }

    @Test
    void aViewReadsAsATableAcrossSources() {
        try (FyrQL engine = builder().build()) {
            assertEquals(0, engine.update("CREATE VIEW adults AS SELECT id, name FROM people WHERE age >= 18"));
            assertEquals(0, engine.update("CREATE VIEW shop.main.spend (person, total) AS"
                    + " SELECT p.name, sum(o.amount) FROM shop.orders o JOIN adults p ON p.id = o.person"
                    + " GROUP BY p.name"));
            assertEquals(List.of(List.of(1, "Ann"), List.of(3, "Cy")),
                    rows(engine, "SELECT * FROM adults ORDER BY id"));
            assertEquals(List.of(List.of("Ann", 7.0), List.of("Cy", 2.0)),
                    rows(engine, "SELECT person, total FROM shop.spend ORDER BY person"));
            assertEquals(List.of(List.of("Ann")), rows(engine, "SELECT s.person FROM shop.main.spend s"
                    + " JOIN mem.adults a ON a.name = s.person WHERE s.total > 5"));
            assertEquals(List.of(List.of(2L)), rows(engine, "WITH x AS (SELECT * FROM adults) SELECT count(*) FROM x"
                    + " WHERE id IN (SELECT id FROM adults)"));
            assertTrue(engine.explain("SELECT * FROM adults").contains("mem.main.people"),
                    engine.explain("SELECT * FROM adults"));
            assertEquals("Create view mem.main.v (id INTEGER, name VARCHAR) [or replace]",
                    engine.explain("EXPLAIN CREATE OR REPLACE VIEW v AS SELECT id, name FROM people").strip());
            assertEquals("Drop view mem.main.adults", engine.explain("DROP VIEW adults").strip());
            assertEquals("Drop view if exists nope [no such view]", engine.explain("DROP VIEW IF EXISTS nope").strip());
        }
    }

    @Test
    void everySessionSeesTheViewsAndTheirNamesKeepTheirTables() {
        try (FyrQL engine = builder().build()) {
            Session shop = engine.newSession();
            shop.use(new SearchPath("shop", "main"));
            assertEquals(0, shop.update("CREATE VIEW big AS SELECT * FROM orders WHERE amount > 1.8"));
            // A name of one part goes to the default source of the session, and its names resolve there.
            assertEquals(List.of(List.of(10), List.of(11)), rows(engine, "SELECT id FROM shop.big ORDER BY id"));
            Session other = engine.newSession();
            assertEquals(List.of(List.of(2L)), rows(other.rows("SELECT count(*) FROM shop.main.big")));
            assertEquals(0, shop.update("DROP VIEW big"));
            assertEquals("Table with name shop.big does not exist",
                    assertThrows(SqlException.class, () -> other.rows("SELECT * FROM shop.big")).reason());
        }
    }

    @Test
    void createAndDropFollowTheirModes() {
        try (FyrQL engine = builder().build()) {
            engine.update("CREATE VIEW v AS SELECT 1 AS a");
            assertEquals("View with name v already exists", fails(engine, "CREATE VIEW v AS SELECT 2 AS a"));
            assertEquals(0, engine.update("CREATE VIEW IF NOT EXISTS v AS SELECT 2 AS a"));
            assertEquals(List.of(List.of(1)), rows(engine, "SELECT * FROM v"));
            assertEquals(0, engine.update("CREATE OR REPLACE VIEW v AS SELECT 3 AS b"));
            assertEquals(List.of(List.of(3)), rows(engine, "SELECT b FROM v"));
            assertEquals("Table with name people already exists", fails(engine, "CREATE VIEW people AS SELECT 1"));
            assertEquals("Table with name people already exists; a view replaces only a view",
                    fails(engine, "CREATE OR REPLACE VIEW people AS SELECT 1"));
            assertEquals(0, engine.update("CREATE VIEW IF NOT EXISTS people AS SELECT 1"));
            assertEquals("Cannot insert into view v: a view has no rows of its own",
                    fails(engine, "INSERT INTO v VALUES (1)"));
            assertEquals("Cannot create table v: there is a view of that name",
                    fails(engine, "CREATE TABLE v (a INTEGER)"));
            assertEquals("v is a view; drop it with DROP VIEW", fails(engine, "DROP TABLE v"));
            assertEquals("people is a table, not a view; drop it with DROP TABLE", fails(engine, "DROP VIEW people"));
            assertEquals(0, engine.update("DROP VIEW v"));
            assertEquals("View with name v does not exist", fails(engine, "DROP VIEW v"));
            assertEquals(0, engine.update("DROP VIEW IF EXISTS v"));
        }
    }

    @Test
    void aViewMayNotReadItself() {
        try (FyrQL engine = builder().build()) {
            engine.update("CREATE VIEW v AS SELECT id FROM people");
            engine.update("CREATE VIEW w AS SELECT id FROM v");
            assertEquals("View v would read itself", fails(engine, "CREATE OR REPLACE VIEW v AS SELECT id FROM w"));
        }
    }

    @Test
    void aViewHidesATableOfItsNameAndOutlivesItsTables() {
        try (FyrQL engine = builder().build()) {
            engine.update("CREATE TABLE kids AS SELECT id, name FROM people WHERE age < 18");
            engine.update("CREATE VIEW later AS SELECT name FROM kids");
            assertEquals(List.of(List.of("Bob")), rows(engine, "SELECT * FROM later"));
            // A table of the name that appears in the source afterwards stays hidden.
            Sink sink = engine.connector("mem").writer().orElseThrow().create(new TableRef("mem", "main", "later"),
                    Schema.of(new Schema.Field("x", DataType.INTEGER)));
            sink.commit();
            sink.close();
            assertEquals(List.of(List.of("Bob")), rows(engine, "SELECT * FROM later"));
            CatalogTables tables = engine.catalogTables();
            List<CatalogTables.TableInfo> later = tables.tables(NamePattern.ANY, NamePattern.ANY,
                    NamePattern.name("later"));
            assertEquals(1, later.size());
            assertTrue(later.get(0).view());

            // The table of a view is dropped: the view fails, until the table is back.
            engine.update("DROP TABLE kids");
            SqlException e = assertThrows(SqlException.class, () -> engine.rows("SELECT 1 FROM people, later"));
            assertEquals("In view mem.main.later: Table with name mem.main.kids does not exist", e.reason());
            assertEquals(new Position(1, 23), e.position());
            engine.update("CREATE TABLE kids AS SELECT 7 AS id, 'Dee' AS name");
            assertEquals(List.of(List.of("Dee")), rows(engine, "SELECT * FROM later"));
        }
    }

    @Test
    void theListsHaveTheViewsWithTheirColumnsAndText() {
        try (FyrQL engine = builder().build()) {
            engine.update("CREATE VIEW shop.main.totals (person, total) AS SELECT person, sum(amount)"
                    + " FROM orders GROUP BY person");
            CatalogTables tables = engine.catalogTables();
            assertEquals(List.of(new CatalogTables.TableInfo(new TableRef("shop", "main", "totals"),
                    OptionalLong.empty(), true), new CatalogTables.TableInfo(
                    new TableRef("shop", "main", "orders"), OptionalLong.of(3))),
                    tables.tables(NamePattern.name("shop"), NamePattern.ANY, NamePattern.ANY));
            assertEquals(List.of("person INTEGER", "total DOUBLE"), tables.columns(NamePattern.ANY, NamePattern.ANY,
                    NamePattern.name("totals"), NamePattern.ANY).stream()
                    .map(c -> c.field().name() + " " + c.field().type().sqlName()).toList());
            assertEquals(List.of(new CatalogTables.ViewInfo(new TableRef("shop", "main", "totals"),
                    "SELECT person, sum(amount) FROM orders GROUP BY person")),
                    tables.views(NamePattern.ANY, NamePattern.ANY, NamePattern.ANY));
            assertEquals(Schema.of(new Schema.Field("person", DataType.INTEGER),
                    new Schema.Field("total", DataType.DOUBLE)), engine.schema(new TableRef("shop", "main", "totals")));
        }
    }

    @Test
    void theBuilderDefinesViewsInTurn() {
        try (FyrQL engine = builder()
                .view("adults", "SELECT id, name FROM people WHERE age >= 18;")
                .view("shop.main.\"Their Orders\"", List.of("who", "amount"),
                        "SELECT a.name, o.amount FROM adults a JOIN shop.orders o ON o.person = a.id")
                .build()) {
            assertEquals(List.of(List.of("Ann", 7.0), List.of("Cy", 2.0)), rows(engine,
                    "SELECT who, sum(amount) FROM shop.\"Their Orders\" GROUP BY who ORDER BY who"));
            assertEquals(new CatalogTables.ViewInfo(new TableRef("mem", "main", "adults"),
                    "SELECT id, name FROM people WHERE age >= 18"),
                    engine.catalogTables().views(NamePattern.ANY, NamePattern.ANY, NamePattern.ANY).get(0));
        }
        SqlException e = assertThrows(SqlException.class, () -> builder()
                .view("v", "SELECT 1")
                .view("w", "SELECT *\nFROM nope")
                .build());
        assertEquals("View w: Table with name nope does not exist", e.reason());
        assertEquals(new Position(2, 6), e.position());
        assertEquals("View x.y.z.v: A table name has one to three parts: x.y.z.v",
                assertThrows(SqlException.class, () -> builder().view("x.y.z.v", "SELECT 1").build()).reason());
        assertEquals("View w: Only queries are supported, not DROP",
                assertThrows(SqlException.class, () -> builder().view("w", "DROP TABLE people").build()).reason());
    }

    @Test
    void aViewGoesInASourceOfTheEngine() {
        try (FyrQL engine = builder().build()) {
            Session session = Overlay.newSession(engine, Map.of("tmp", MemoryConnector.builder("tmp")
                    .table("t", Person.class, List.of(new Person(9, "Eve", 40))).build()), engine.functions());
            assertEquals("Source tmp belongs to this session alone; a view goes in a source of the engine",
                    assertThrows(SqlException.class, () -> session.update("CREATE VIEW tmp.main.v AS SELECT 1"))
                            .reason());
            session.update("CREATE VIEW mem.main.v AS SELECT name FROM people WHERE id = 1");
            assertEquals(List.of(List.of("Ann")), rows(engine, "SELECT * FROM v"));
        }
    }

    @Test
    void aPreparedQueryReadsAView() {
        try (FyrQL engine = builder().build()) {
            engine.update("CREATE VIEW adults AS SELECT id, name FROM people WHERE age >= 18");
            PreparedQuery q = engine.session().prepare("SELECT name FROM adults WHERE id = ?");
            assertEquals(List.of(List.of("Cy")), rows(engine.session().rows(q, List.of(3))));
            assertEquals(List.of(), rows(engine.session().rows(q, List.of(2))));
            engine.update("CREATE VIEW shop.main.spend AS SELECT p.name, p.age > 40 AS old, count(*) AS orders,"
                    + " sum(o.amount) AS total FROM shop.orders AS o JOIN people AS p ON o.person = p.id"
                    + " GROUP BY p.name, p.age > 40");
            q = engine.session().prepare("SELECT name, orders, total FROM shop.spend WHERE old = ? ORDER BY total");
            assertEquals(List.of("name", "orders", "total"), q.schema().fields().stream().map(Schema.Field::name)
                    .toList());
            assertEquals(List.of(List.of("Cy", 1L, 2.0)), rows(engine.session().rows(q, List.of(true))));
        }
    }
}
