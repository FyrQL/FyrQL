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

import net.fyrql.catalog.NamePattern;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Temporary tables, which each session has in a source {@code temp} of its
 * own, {@code CREATE SCHEMA} and {@code ALTER TABLE ... ADD COLUMN}.
 */
class TemporaryTablesTest {

    record Person(int id, String name) {
    }

    private static FyrQL engine() {
        return FyrQL.builder().register(MemoryConnector.builder("mem").table("people", Person.class,
                List.of(new Person(1, "Ann"))).build()).defaultSource("mem").build();
    }

    private static List<List<Object>> rows(Session session, String sql) {
        return session.rows(sql).stream().map(Arrays::asList).toList();
    }

    private static String fails(Session session, String sql) {
        return assertThrows(SqlException.class, () -> session.update(sql), sql).reason();
    }

    @Test
    void aTemporaryTableLivesInItsSessionAndHidesATableOfItsName() {
        try (FyrQL engine = engine()) {
            Session a = engine.newSession();
            Session b = engine.newSession();
            assertEquals(0, a.update("CREATE TEMP TABLE scratch (k INTEGER PRIMARY KEY, v VARCHAR)"));
            assertEquals(2, a.update("INSERT INTO scratch VALUES (1, 'x'), (2, 'y')"));
            assertEquals(1, a.update("UPDATE scratch SET v = 'z' WHERE k = 2"));
            assertEquals(List.of(List.of(1, "x"), List.of(2, "z")), rows(a, "SELECT * FROM scratch ORDER BY k"));
            assertEquals(List.of(List.of(2L)), rows(a, "SELECT count(*) FROM temp.scratch"));
            assertEquals(List.of(List.of(2L)), rows(a, "SELECT count(*) FROM temp.main.scratch"));
            assertEquals("Table with name scratch does not exist", fails(b, "SELECT * FROM scratch"));

            // A temporary table hides a table of its name in the default source, until it is dropped.
            assertEquals(1, a.update("CREATE TEMPORARY TABLE people AS SELECT 'temp' AS name"));
            assertEquals(List.of(List.of("temp")), rows(a, "SELECT name FROM people"));
            assertEquals(List.of(List.of("Ann")), rows(b, "SELECT name FROM people"));
            assertEquals(List.of(List.of("Ann")), rows(a, "SELECT name FROM mem.people"));
            assertEquals(0, a.update("DROP TABLE people"));
            assertEquals(List.of(List.of("Ann")), rows(a, "SELECT name FROM people"));

            assertEquals(0, a.update("CREATE TEMP TABLE IF NOT EXISTS scratch (z INTEGER)"));
            assertEquals(0, a.update("CREATE OR REPLACE TEMP TABLE scratch (z INTEGER)"));
            assertEquals(List.of(), rows(a, "SELECT * FROM scratch"));
            assertEquals("A temporary table goes to temp.main, not to mem", fails(a,
                    "CREATE TEMP TABLE mem.t (a INTEGER)"));
            assertEquals("View v cannot read the temporary table scratch, which only this session has", fails(a,
                    "CREATE VIEW v AS SELECT * FROM scratch"));

            // The metadata of a session shows its temporary tables, that of the engine does not.
            assertEquals(List.of("scratch"), a.catalogTables().tables(NamePattern.name("temp"), NamePattern.ANY,
                    NamePattern.ANY).stream().map(t -> t.table().table()).toList());
            assertEquals(List.of(), b.catalogTables().tables(NamePattern.name("temp"), NamePattern.ANY,
                    NamePattern.ANY));
            assertEquals(List.of("mem"), engine.catalogTables().catalogs());
            assertEquals(List.of("mem", "temp"), a.catalogTables().catalogs());
        }
    }

    @Test
    void anEngineWithASourceNamedTempHasNoTemporaryTables() {
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("temp").build()).build()) {
            assertEquals("Not supported: temporary tables, since a source of the engine is named temp",
                    fails(engine.newSession(), "CREATE TEMP TABLE t (a INTEGER)"));
            assertEquals(0, engine.update("CREATE TABLE t (a INTEGER)"));
            assertEquals(List.of("t"), engine.connector("temp").catalog().tables("main"));
        }
    }

    @Test
    void addColumnGivesTheRowsTheDefault() {
        try (FyrQL engine = engine()) {
            Session s = engine.session();
            s.update("CREATE TABLE t (k INTEGER PRIMARY KEY)");
            s.update("INSERT INTO t VALUES (1), (2)");
            assertEquals(0, s.update("ALTER TABLE t ADD COLUMN v VARCHAR"));
            assertEquals(0, s.update("ALTER TABLE t ADD d DECIMAL(5,2) DEFAULT 1.5"));
            assertEquals(0, s.update("ALTER TABLE t ADD COLUMN IF NOT EXISTS v INTEGER"));
            assertEquals(0, s.update("ALTER TABLE t ADD COLUMN n INTEGER DEFAULT '7'"));
            assertEquals(Schema.of(new Schema.Field("k", DataType.INTEGER, false),
                    new Schema.Field("v", DataType.VARCHAR), new Schema.Field("d", DataType.decimal(5, 2)),
                    new Schema.Field("n", DataType.INTEGER)), engine.schema(TableRef.of("mem", "t")));
            assertEquals(List.of(Arrays.asList(1, null, new BigDecimal("1.50"), 7),
                    Arrays.asList(2, null, new BigDecimal("1.50"), 7)), rows(s, "SELECT * FROM t ORDER BY k"));
            assertEquals(1, s.update("INSERT INTO t (k) VALUES (3)"));
            assertEquals(List.of(Arrays.asList(null, null)), rows(s, "SELECT d, n FROM t WHERE k = 3"));
            assertEquals("Column with name V already exists!", fails(s, "ALTER TABLE t ADD COLUMN V INTEGER"));
            assertEquals("Adding columns with constraints not yet supported",
                    fails(s, "ALTER TABLE t ADD COLUMN w INTEGER NOT NULL"));
            assertTrue(fails(s, "ALTER TABLE t DROP COLUMN v").startsWith("Not supported: ALTER TABLE ... DROP"));
            assertEquals("Table people of the memory source mem holds Java objects and cannot be written",
                    fails(s, "ALTER TABLE people ADD COLUMN x INTEGER"));
            assertEquals("Alter table mem.main.t add column x INTEGER [default: 5]\n",
                    engine.explain("ALTER TABLE t ADD COLUMN x INTEGER DEFAULT 5"));
        }
    }

    @Test
    void createSchemaGoesToTheDefaultSourceOrTheOneNamed() {
        try (FyrQL engine = engine()) {
            assertEquals("The memory source mem has only the schema main and creates no other",
                    fails(engine.session(), "CREATE SCHEMA sales"));
            assertEquals(0, engine.update("CREATE SCHEMA IF NOT EXISTS main"));
            assertEquals("Schema with name \"MAIN\" already exists!", fails(engine.session(), "CREATE SCHEMA MAIN"));
            assertEquals("Source with name nowhere does not exist",
                    fails(engine.session(), "CREATE SCHEMA nowhere.sales"));
            assertEquals("Create schema mem.sales [if not exists]\n",
                    engine.explain("CREATE SCHEMA IF NOT EXISTS mem.sales"));
        }
    }
}
