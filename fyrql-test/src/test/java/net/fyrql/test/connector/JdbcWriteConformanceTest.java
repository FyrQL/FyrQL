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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.connector.JdbcConformance.Target;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JDBC connector writes into every database the tests reach, as
 * {@link JdbcConformanceTest} reads from them: {@code CREATE TABLE AS}
 * copies the table {@code items} of {@link JdbcConformance}, with a column
 * of every type the dialect creates, from a memory source; the database
 * gives back its rows as DuckDB casts them to the types the database
 * stored them in. {@code INSERT INTO ... SELECT} and {@code ... VALUES}
 * add rows; a failure in the database in the middle of the rows, or of the
 * query that gives them, rolls back and leaves no new table; {@code DROP
 * TABLE}, {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE OR REPLACE
 * TABLE} do as they say. The generic dialect and SQLite create no
 * {@code HUGEINT}.
 */
class JdbcWriteConformanceTest {

    /** The rows a write sends at a time: few, so that a failure comes after some of them were sent. */
    private static final int BATCH_ROWS = 7;
    /** The rows of a batch of the memory source. */
    private static final int BATCH_SIZE = 16;

    @Test
    void h2() {
        check(Target.of(TestDatabases.h2()));
    }

    @Test
    void sqlite() {
        check(Target.of(TestDatabases.sqlite()));
    }

    @Test
    void postgres() {
        var db = TestDatabases.postgres();
        assumeTrue(db.isPresent(), "PostgreSQL is off");
        check(Target.of(db.get()));
    }

    @Test
    void mysql() {
        var db = TestDatabases.mysql();
        assumeTrue(db.isPresent(), "MySQL runs in Docker, in the profile containers");
        check(Target.of(db.get()));
    }

    @Test
    void h2Oracle() {
        check(JdbcConformance.h2("Oracle", b -> {
        }));
    }

    @Test
    void h2SqlServer() {
        check(JdbcConformance.h2("MSSQLServer", b -> b.quote("[").limitStyle(JdbcConnector.LimitStyle.TOP)));
    }

    @Test
    void h2MySql() {
        check(JdbcConformance.h2("MySQL", b -> b.limitStyle(JdbcConnector.LimitStyle.LIMIT)));
    }

    @Test
    void derby() {
        check(JdbcConformance.derby());
    }

    @Test
    void hsqldb() {
        check(JdbcConformance.hsqldb());
    }

    private static void check(Target target) {
        TestDatabase db = target.db();
        String name = target.name();
        // The table without HUGEINT is the one of SQLite.
        JdbcDialect kind = target.generic() ? JdbcDialect.SQLITE : db.dialect();
        Schema items = JdbcConformance.items(kind);
        List<Object[]> rows = JdbcConformance.itemRows(kind);
        JdbcConnector connector = db.connector(JdbcConformance.SOURCE, target.settings()
                .andThen(b -> b.batchRows(BATCH_ROWS)));
        MemoryConnector memory = MemoryConnector.builder("memory").batchSize(BATCH_SIZE).table("items", items, rows)
                .build();
        try (FyrQL engine = FyrQL.builder().register(connector).register(memory).build();
                DuckDbReference duck = new DuckDbReference()) {
            duck.load("items", items, List.of(Batch.ofRows(items, rows)));

            assertEquals(rows.size(), engine.update("CREATE TABLE db.copy AS SELECT * FROM memory.items"), name);
            Schema stored = connector.catalog().schema(TableRef.of(JdbcConformance.SOURCE, "copy"));
            assertEquals(names(items), names(stored), name);
            // As in DuckDB, the columns of a query are created without NOT NULL.
            assertTrue(stored.nullable(0), name);
            same(target, engine, duck, stored, "SELECT * FROM items", "the copy");

            assertEquals(50, engine.update("INSERT INTO db.copy SELECT * FROM memory.items WHERE id < 50"), name);
            assertEquals(2, engine.update("INSERT INTO db.copy (id, s, i) VALUES (1000, 'x', 7), (1001, NULL, NULL)"),
                    name);
            same(target, engine, duck, stored, "SELECT * FROM items UNION ALL SELECT * FROM items WHERE id < 50",
                    "the copy with the rows inserted");
            assertEquals(List.of("1000 x 7", "1001 null null"), engine.rows("SELECT id, s, i FROM db.copy"
                    + " WHERE id >= 1000 ORDER BY id").stream().map(r -> ((Number) r[0]).intValue() + " " + r[1] + " "
                    + (r[2] == null ? null : ((Number) r[2]).intValue())).toList(), name);

            checkRollback(target, engine, connector);

            assertEquals(0, engine.update("DROP TABLE db.copy"), name);
            assertFalse(tables(connector).contains("copy"), name);
            assertEquals(0, engine.update("DROP TABLE IF EXISTS db.copy"), name);
            assertEquals(0, engine.update("CREATE TABLE db.empty (a INTEGER NOT NULL, s VARCHAR)"), name);
            assertEquals(0, engine.update("CREATE TABLE IF NOT EXISTS db.empty AS SELECT * FROM memory.items"), name);
            assertEquals(List.of("a", "s"), names(connector.catalog().schema(TableRef.of(JdbcConformance.SOURCE,
                    "empty"))), name);
            assertEquals(3, engine.update("CREATE OR REPLACE TABLE db.empty AS SELECT id, s FROM memory.items"
                    + " WHERE id < 3"), name);
            assertEquals(List.of(0, 1, 2), engine.rows("SELECT id FROM db.empty ORDER BY id").stream()
                    .map(r -> ((Number) r[0]).intValue()).toList(), name);
            assertEquals(0, engine.update("DROP TABLE db.empty"), name);
        }
    }

    /**
     * A key the database refuses after some batches were sent, and a value
     * the query cannot cast after some rows: the database keeps none of
     * the rows, and no new table.
     */
    private static void checkRollback(Target target, FyrQL engine, JdbcConnector connector) {
        String name = target.name();
        target.db().execute("CREATE TABLE " + connector.sql().quoteIdentifier("uniq") + " ("
                + connector.sql().quoteIdentifier("id") + " INTEGER NOT NULL PRIMARY KEY)");
        connector.refresh();
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> engine.update("INSERT INTO db.uniq SELECT id % 100 FROM memory.items"));
        assertTrue(e.getMessage().contains("fails on INSERT INTO ") && e.getMessage().contains(" in the rows "),
                name + ": " + e.getMessage());
        assertEquals(0L, engine.rows("SELECT count(*) FROM db.uniq").get(0)[0], name);
        assertEquals(0, engine.update("DROP TABLE db.uniq"), name);

        assertThrows(RuntimeException.class, () -> engine.update("CREATE TABLE db.broken AS SELECT id,"
                + " CAST(CASE WHEN id < 200 THEN '1' ELSE 'x' END AS INTEGER) AS n FROM memory.items"));
        assertFalse(tables(connector).contains("broken"), name);
        assertFalse(tables(target.db().connector("again")).contains("broken"), name);
    }

    private static List<String> tables(JdbcConnector connector) {
        return connector.catalog().tables(TableRef.MAIN);
    }

    private static List<String> names(Schema schema) {
        return schema.fields().stream().map(Schema.Field::name).toList();
    }

    /**
     * The rows of the copy are those of a query of DuckDB over
     * {@code items}, cast to the types the database stored, with an empty
     * string as {@code NULL} where the database stores it so.
     */
    private static void same(Target target, FyrQL engine, DuckDbReference duck, Schema stored, String query,
            String what) {
        List<String> casts = new ArrayList<>();
        for (Schema.Field f : stored.fields()) {
            casts.add("CAST(\"" + f.name() + "\" AS " + f.type().sqlName() + ") AS \"" + f.name() + "\"");
        }
        Schema schema = new Schema(stored.fields().stream().map(f -> new Schema.Field(f.name(), f.type())).toList());
        boolean emptyIsNull = target.storedValue().apply(DataType.VARCHAR, "") == null;
        List<Object[]> expected = new ArrayList<>();
        for (Object[] row : duck.query("SELECT " + String.join(", ", casts) + " FROM (" + query + ") q", schema)) {
            for (int c = 0; c < row.length; c++) {
                if (emptyIsNull && "".equals(row[c])) {
                    row[c] = null;
                }
            }
            expected.add(row);
        }
        List<Object[]> actual = engine.rows("SELECT * FROM db.copy WHERE id < 1000");
        ResultComparison.Outcome outcome = ResultComparison.compare(schema, expected, actual, false);
        assertTrue(outcome.same(), target.name() + ", " + what + ":\n" + outcome.report());
    }
}
