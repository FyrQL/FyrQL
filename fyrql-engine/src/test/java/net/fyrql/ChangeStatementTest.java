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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RowChanges;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.BoundStatement;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code UPDATE} and {@code DELETE} end to end in memory: run by the source
 * as whole statements, and, through a source that changes rows by key
 * only, by the engine; both ways give the same rows and counts.
 */
class ChangeStatementTest {

    record Person(int id, String name) {
    }

    /** A memory source whose writer changes rows by key only, so that the engine finds the rows itself. */
    private record KeyedOnly(MemoryConnector inner) implements Connector {
        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public Catalog catalog() {
            return inner.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return inner.plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return inner.splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return inner.open(split);
        }

        @Override
        public Optional<Writer> writer() {
            Writer w = inner.writer().orElseThrow();
            return Optional.of(new Writer() {
                @Override
                public Sink create(TableRef table, Schema schema) {
                    return w.create(table, schema);
                }

                @Override
                public Sink create(TableRef table, Schema schema, List<String> primaryKey) {
                    return w.create(table, schema, primaryKey);
                }

                @Override
                public Sink append(TableRef table) {
                    return w.append(table);
                }

                @Override
                public void drop(TableRef table) {
                    w.drop(table);
                }

                @Override
                public RowChanges change(TableRef table, int[] keys, int[] updated) {
                    return w.change(table, keys, updated);
                }
            });
        }
    }

    private static FyrQL engine(boolean keyed) {
        MemoryConnector mem = MemoryConnector.builder("mem").table("people", Person.class,
                List.of(new Person(1, "Ann"), new Person(2, "Bob"))).build();
        return FyrQL.builder().register(keyed ? new KeyedOnly(mem) : mem).defaultSource("mem").build();
    }

    private static List<List<Object>> rows(FyrQL engine, String sql) {
        return engine.rows(sql).stream().map(Arrays::asList).toList();
    }

    private static String fails(FyrQL engine, String sql) {
        return assertThrows(SqlException.class, () -> engine.update(sql), sql).reason();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateSetsColumnsOfTheRowsTheConditionSelects(boolean keyed) {
        try (FyrQL engine = engine(keyed)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR, n INTEGER)");
            engine.update("INSERT INTO t VALUES (1, 'a', 10), (2, 'b', 20), (3, 'c', 30)");
            assertEquals(2, engine.update("UPDATE t SET n = n + 1, v = v || '!' WHERE k >= 2"));
            assertEquals(List.of(List.of(1, "a", 10), List.of(2, "b!", 21), List.of(3, "c!", 31)),
                    rows(engine, "SELECT * FROM t ORDER BY k"));
            // The new values come from the rows before the update.
            assertEquals(3, engine.update("UPDATE t AS x SET n = x.k, k = x.n"));
            assertEquals(List.of(List.of(10, "a", 1), List.of(21, "b!", 2), List.of(31, "c!", 3)),
                    rows(engine, "SELECT * FROM t ORDER BY k"));
            assertEquals(0, engine.update("UPDATE t SET v = 'z' WHERE k > 100"));
            // A value is cast as INSERT casts it; a NULL takes the type of its column.
            assertEquals(1, engine.update("UPDATE t SET n = '7', v = NULL WHERE k = 10"));
            assertEquals(List.of(Arrays.asList(10, null, 7)), rows(engine, "SELECT * FROM t WHERE k = 10"));
            assertEquals(1, engine.update("UPDATE t SET n = 2.6 WHERE k = 10"));
            assertEquals(List.of(List.of(3)), rows(engine, "SELECT n FROM t WHERE k = 10"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateWithFromAndSubqueries(boolean keyed) {
        try (FyrQL engine = engine(keyed)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR)");
            engine.update("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            engine.update("CREATE TABLE s (k INTEGER, v VARCHAR)");
            engine.update("INSERT INTO s VALUES (1, 'x'), (3, 'y'), (4, 'z')");
            assertEquals(2, engine.update("UPDATE t SET v = s.v FROM s WHERE t.k = s.k"));
            assertEquals(List.of(List.of(1, "x"), List.of(2, "b"), List.of(3, "y")),
                    rows(engine, "SELECT * FROM t ORDER BY k"));
            assertEquals(3, engine.update("UPDATE t SET v = (SELECT max(v) FROM s)"));
            assertEquals(List.of(List.of("z")), rows(engine, "SELECT DISTINCT v FROM t"));
            assertEquals(1, engine.update("UPDATE t SET v = 'in' WHERE k IN (SELECT k + 1 FROM s WHERE v = 'x')"));
            assertEquals(List.of(List.of(2, "in")), rows(engine, "SELECT * FROM t WHERE v = 'in'"));
            // A row that FROM matches twice counts twice, and one of the matches sets it.
            engine.update("INSERT INTO s VALUES (2, 'w'), (2, 'w')");
            assertEquals(3, engine.update("UPDATE t SET v = s.v FROM s WHERE t.k + 1 = s.k AND t.k <= 2"));
            assertEquals(List.of(List.of(1L)), rows(engine, "SELECT count(*) FROM t WHERE v IN ('w')"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteRemovesTheRowsTheConditionSelects(boolean keyed) {
        try (FyrQL engine = engine(keyed)) {
            engine.update("CREATE TABLE t (k INTEGER, v VARCHAR)");
            engine.update("INSERT INTO t VALUES (1, 'a'), (1, 'a'), (2, 'b'), (3, NULL), (4, 'd')");
            // Without a primary key, rows alike are deleted together, and each counts.
            assertEquals(2, engine.update("DELETE FROM t WHERE k = 1"));
            assertEquals(1, engine.update("DELETE FROM t AS x WHERE x.v IS NULL"));
            assertEquals(List.of(List.of(2, "b"), List.of(4, "d")), rows(engine, "SELECT * FROM t ORDER BY k"));
            engine.update("CREATE TABLE s (k INTEGER)");
            engine.update("INSERT INTO s VALUES (2), (2), (5)");
            // A row that USING matches twice is deleted once.
            assertEquals(1, engine.update("DELETE FROM t USING s WHERE t.k = s.k"));
            assertEquals(0, engine.update("DELETE FROM t WHERE k IN (SELECT k FROM s)"));
            assertEquals(1, engine.update("DELETE FROM t"));
            assertEquals(List.of(), rows(engine, "SELECT * FROM t"));
            assertEquals(0, engine.update("DELETE FROM t"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedChangeLeavesTheTableAsItWas(boolean keyed) {
        try (FyrQL engine = engine(keyed)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR NOT NULL)");
            engine.update("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            // A key changes for all rows at once; only the result must be unique.
            assertEquals(3, engine.update("UPDATE t SET k = k + 1"));
            assertEquals(List.of(List.of(2), List.of(3), List.of(4)), rows(engine, "SELECT k FROM t ORDER BY k"));
            assertTrue(fails(engine, "UPDATE t SET k = 3 WHERE k = 2").contains("duplicate key"));
            assertEquals("NOT NULL constraint failed: t.k", fails(engine, "UPDATE t SET k = NULL WHERE k = 2"));
            assertEquals("NOT NULL constraint failed: t.v", fails(engine, "UPDATE t SET v = NULL"));
            assertTrue(assertThrows(RuntimeException.class, () -> engine.update("UPDATE t SET k = 'x'")).getMessage()
                    .startsWith("Could not convert string 'x' to an integer"));
            assertEquals(List.of(List.of(2, "a"), List.of(3, "b"), List.of(4, "c")),
                    rows(engine, "SELECT * FROM t ORDER BY k"));
        }
    }

    @Test
    void errorsOfTheStatement() {
        try (FyrQL engine = engine(false)) {
            engine.update("CREATE TABLE t (k INTEGER, v VARCHAR)");
            engine.update("CREATE VIEW w AS SELECT * FROM t");
            assertEquals("Referenced update column nope not found in table!", fails(engine, "UPDATE t SET nope = 1"));
            assertEquals("Multiple assignments to same column \"v\"", fails(engine, "UPDATE t SET v = 'a', v = 'b'"));
            assertEquals("SET clause cannot contain aggregates!", fails(engine, "UPDATE t SET k = max(k)"));
            assertEquals("WHERE clause cannot contain aggregates!", fails(engine, "DELETE FROM t WHERE count(*) > 1"));
            assertEquals("SET clause cannot contain window functions!",
                    fails(engine, "UPDATE t SET k = row_number() OVER ()"));
            assertEquals("Cannot update view w: a view has no rows of its own", fails(engine, "UPDATE w SET k = 1"));
            assertEquals("Cannot delete from view w: a view has no rows of its own", fails(engine, "DELETE FROM w"));
            assertEquals("Table with name nope does not exist", fails(engine, "DELETE FROM nope"));
            assertEquals("Table people of the memory source mem holds Java objects and cannot be written",
                    fails(engine, "DELETE FROM people"));
            assertEquals("Cannot assign a value of type INTEGER[] to column k of type INTEGER - an explicit cast is"
                    + " required", fails(engine, "UPDATE t SET k = [1]"));
        }
    }

    @Test
    void parametersTakeTheirTypesFromTheirColumns() {
        try (FyrQL engine = engine(false)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR, d DATE)");
            engine.update("INSERT INTO t VALUES (1, 'a', DATE '2026-10-28')");
            PreparedQuery update = engine.prepare("UPDATE t SET v = ?, d = ? WHERE k = ?");
            assertEquals(List.of(DataType.VARCHAR, DataType.DATE, DataType.INTEGER), update.parameterTypes());
            assertEquals(BoundStatement.COUNT, update.schema());
            assertEquals(1, engine.update(update, List.of("b", java.time.LocalDate.of(2026, 1, 2), 1)));
            assertEquals(0, engine.update(update, List.of("c", java.time.LocalDate.of(2026, 1, 2), 2)));
            PreparedQuery delete = engine.prepare("DELETE FROM t WHERE k = ?");
            assertEquals(List.of(DataType.INTEGER), delete.parameterTypes());
            assertEquals(List.of(List.of(1, "b")), rows(engine, "SELECT k, v FROM t"));
            assertEquals(1, engine.update(delete, List.of(1)));
        }
    }

    @Test
    void theBoundStatementComesAsAWholeAndByKey() {
        try (FyrQL engine = engine(false)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR, n INTEGER)");
            engine.update("CREATE TABLE s (k INTEGER)");
            BoundStatement.Update u = assertInstanceOf(BoundStatement.Update.class,
                    engine.statement("UPDATE t SET n = n + 1 WHERE k = 1").bound());
            assertEquals(List.of(2), Arrays.stream(u.columns()).boxed().toList());
            assertEquals(List.of(0), Arrays.stream(u.keys()).boxed().toList());
            assertNotNull(u.values());
            assertNotNull(u.condition());
            assertEquals(List.of("k", "n"), u.keyed().schema().fields().stream().map(Schema.Field::name).toList());
            u = assertInstanceOf(BoundStatement.Update.class,
                    engine.statement("UPDATE t SET n = 1 FROM s WHERE t.k = s.k").bound());
            assertNull(u.values());
            BoundStatement.Delete d = assertInstanceOf(BoundStatement.Delete.class,
                    engine.statement("DELETE FROM t WHERE k IN (SELECT k FROM s)").bound());
            assertTrue(!d.simple() && d.condition() == null);
            d = assertInstanceOf(BoundStatement.Delete.class, engine.statement("DELETE FROM t").bound());
            assertTrue(d.simple() && d.condition() == null);

            String explain = engine.explain("UPDATE t SET n = n + 1, v = 'x' WHERE k = 1");
            assertTrue(explain.startsWith("Update mem.main.t [set: n, v; keys: k]\n  "), explain);
            explain = engine.explain("DELETE FROM t USING s WHERE t.k = s.k");
            assertTrue(explain.startsWith("Delete from mem.main.t [keys: k]\n  "), explain);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mergeAppliesThePairsInTheirOrder(boolean keyed) {
        try (FyrQL engine = engine(keyed)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR NOT NULL)");
            engine.update("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            // Two rows of the source for row 2: the delete counts, the update after it does nothing.
            assertEquals(4, engine.update("MERGE INTO t USING (VALUES (2, 1), (2, 2), (3, 3), (4, 4)) AS s(k, o)"
                    + " ON t.k = s.k WHEN MATCHED AND s.o = 1 THEN DELETE WHEN MATCHED THEN UPDATE SET v = v || s.o"
                    + " WHEN NOT MATCHED THEN INSERT VALUES (s.k, 'new')"));
            assertEquals(List.of(List.of(1, "a"), List.of(3, "c3"), List.of(4, "new")),
                    rows(engine, "SELECT * FROM t ORDER BY k"));
            assertEquals("NOT NULL constraint failed: t.v, which the INSERT gives no value", fails(engine,
                    "MERGE INTO t USING (SELECT 5 AS k) s"
                    + " ON t.k = s.k WHEN NOT MATCHED THEN INSERT (k) VALUES (s.k)"));
            assertTrue(fails(engine, "MERGE INTO t USING (SELECT 1 AS k) s ON t.k = s.k"
                    + " WHEN MATCHED THEN UPDATE SET k = 3").contains("duplicate key"));
            assertEquals(List.of(List.of(3L)), rows(engine, "SELECT count(*) FROM t"));
        }
    }

    @Test
    void mergeErrorsAndExplain() {
        try (FyrQL engine = engine(false)) {
            engine.update("CREATE TABLE t (k INTEGER PRIMARY KEY, v VARCHAR)");
            assertEquals("Column count mismatch: UPDATE without a list in MERGE gives 1 columns of the source to the 2"
                    + " columns of t", fails(engine, "MERGE INTO t USING (SELECT 1 AS k) s ON t.k = s.k"
                    + " WHEN MATCHED THEN UPDATE"));
            assertEquals("Table t has 2 columns but 1 values were supplied", fails(engine, "MERGE INTO t USING"
                    + " (SELECT 1 AS k) s ON t.k = s.k WHEN NOT MATCHED THEN INSERT VALUES (1)"));
            assertEquals("Referenced update column w not found in table!", fails(engine, "MERGE INTO t USING"
                    + " (SELECT 1 AS k) s ON t.k = s.k WHEN MATCHED THEN UPDATE SET w = 1"));
            engine.update("CREATE VIEW w AS SELECT 1 AS k");
            assertEquals("Cannot merge into view w: a view has no rows of its own", fails(engine,
                    "MERGE INTO w USING (SELECT 1 AS k) s ON w.k = s.k WHEN MATCHED THEN DELETE"));
            String explain = engine.explain("MERGE INTO t USING (SELECT 1 AS k) s ON t.k = s.k"
                    + " WHEN MATCHED THEN DELETE WHEN NOT MATCHED THEN INSERT VALUES (s.k, 'x')");
            assertTrue(explain.startsWith("Merge into mem.main.t [delete, insert; keys: k]\n  "), explain);
            BoundStatement.Merge m = assertInstanceOf(BoundStatement.Merge.class, engine.statement("MERGE INTO t"
                    + " USING (SELECT 1 AS k) s ON t.k = s.k WHEN MATCHED THEN UPDATE SET v = 'y'").bound());
            assertEquals(List.of(1), Arrays.stream(m.updated()).boxed().toList());
            assertEquals(List.of("$branch", "k", "k_1", "v"), m.changes().schema().fields().stream()
                    .map(Schema.Field::name).toList());
        }
    }

    @Test
    void theRowsToChangeAreHeldInTheBudget() {
        try (FyrQL engine = FyrQL.builder().register(new KeyedOnly(MemoryConnector.builder("mem").build()))
                .defaultSource("mem").memoryBudget(64L << 20).build()) {
            engine.update("CREATE TABLE t AS SELECT range AS k, 'x' AS v FROM range(10000)");
            assertEquals(5000, engine.update("UPDATE t SET v = 'y' WHERE k % 2 = 0"));
            assertEquals(5000, engine.update("DELETE FROM t WHERE v = 'x'"));
            assertEquals(0, engine.execution().executor().budget().used());
            assertEquals(List.of(List.of(5000L, "y")), rows(engine, "SELECT count(*), min(v) FROM t"));
        }
    }
}
