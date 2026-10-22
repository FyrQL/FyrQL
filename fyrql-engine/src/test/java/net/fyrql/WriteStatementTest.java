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
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.BoundStatement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The statements that write, end to end, into memory sources. */
class WriteStatementTest {

    record Person(int id, String name) {
    }

    /** A source that reads the tables of another one and writes none. */
    private record ReadOnly(String name, Connector inner) implements Connector {
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
    }

    private static FyrQL engine() {
        return FyrQL.builder()
                .register(MemoryConnector.builder("mem").table("people", Person.class,
                        List.of(new Person(1, "Ann"), new Person(2, "Bob"))).build())
                .register(MemoryConnector.builder("other").build())
                .register(new ReadOnly("ro", MemoryConnector.builder("ro").table("people", Person.class,
                        List.of(new Person(3, "Cy"))).build()))
                .defaultSource("mem").build();
    }

    private static List<List<Object>> rows(FyrQL engine, String sql) {
        return engine.rows(sql).stream().map(Arrays::asList).toList();
    }

    private static String fails(FyrQL engine, String sql) {
        return assertThrows(SqlException.class, () -> engine.update(sql), sql).reason();
    }

    @Test
    void createTableAsGivesTheCountOfItsRows() {
        try (FyrQL engine = engine()) {
            String sql = "CREATE TABLE t AS SELECT n, n * 2 AS d FROM (VALUES (1), (2), (3)) v(n)";
            assertInstanceOf(BoundStatement.Create.class, engine.statement(sql).bound());
            try (QueryResult result = engine.sql(sql)) {
                assertEquals(BoundStatement.COUNT, result.schema());
                assertEquals(List.of(List.of(3L)), result.rows().stream().map(Arrays::asList).toList());
            }
            assertEquals(List.of(List.of(1, 2), List.of(2, 4), List.of(3, 6)),
                    rows(engine, "SELECT * FROM t ORDER BY n"));
            assertEquals(Schema.of(new Schema.Field("n", DataType.INTEGER), new Schema.Field("d", DataType.INTEGER)),
                    engine.schema(TableRef.of("mem", "t")));
            assertEquals(List.of("people", "t"), engine.connector("mem").catalog().tables("main"));
        }
    }

    @Test
    void createInsertAndDrop() {
        try (FyrQL engine = engine()) {
            assertEquals(0, engine.update("CREATE TABLE s (a INTEGER NOT NULL, b VARCHAR, c DECIMAL(10,2))"));
            assertEquals(2, engine.update("INSERT INTO s VALUES (1, 'x', 1.5), (2, NULL, NULL)"));
            assertEquals(1, engine.update("INSERT INTO s (b, a) VALUES ('y', 3)"));
            assertEquals(2, engine.update("INSERT INTO s (a, c) SELECT a + 10, 2 FROM s WHERE a < 3"));
            assertEquals(List.of(List.of(1, "x", new BigDecimal("1.50")), Arrays.asList(2, null, null),
                    Arrays.asList(3, "y", null), Arrays.asList(11, null, new BigDecimal("2.00")),
                    Arrays.asList(12, null, new BigDecimal("2.00"))), rows(engine, "SELECT * FROM s ORDER BY a"));
            assertEquals(5, engine.update("INSERT INTO s SELECT * FROM s"));
            assertEquals(List.of(List.of(10L)), rows(engine, "SELECT count(*) FROM s"));

            assertEquals(List.of(List.of(0L)), rows(engine, "DROP TABLE s"));
            assertEquals(List.of("people"), engine.connector("mem").catalog().tables("main"));
            assertEquals(0, engine.update("DROP TABLE IF EXISTS s"));
            assertEquals("Table with name s does not exist", fails(engine, "DROP TABLE s"));
            assertEquals("Table with name s does not exist", fails(engine, "INSERT INTO s VALUES (1, 'x', 1)"));
        }
    }

    @Test
    void aTableThatExists() {
        try (FyrQL engine = engine()) {
            assertEquals(1, engine.update("CREATE TABLE t AS SELECT 1 AS a"));
            assertEquals("Table with name t already exists", fails(engine, "CREATE TABLE t AS SELECT 2 AS a"));
            assertEquals("Table with name t already exists", fails(engine, "CREATE TABLE T (x INTEGER)"));
            assertEquals(0, engine.update("CREATE TABLE IF NOT EXISTS t AS SELECT 2 AS a"));
            assertEquals(List.of(List.of(1)), rows(engine, "SELECT * FROM t"));

            // The query of OR REPLACE may read the table it replaces.
            assertEquals(1, engine.update("CREATE OR REPLACE TABLE t AS SELECT a + 1 AS a, 'x' AS b FROM t"));
            assertEquals(List.of(List.of(2, "x")), rows(engine, "SELECT * FROM t"));
            assertEquals(0, engine.update("CREATE OR REPLACE TABLE T (z VARCHAR)"));
            assertEquals(Schema.of(new Schema.Field("z", DataType.VARCHAR)), engine.schema(TableRef.of("mem", "t")));
            assertEquals(List.of(), rows(engine, "SELECT * FROM t"));
        }
    }

    @Test
    void aNullForAColumnThatIsNotNullFailsTheStatementAsAWhole() {
        try (FyrQL engine = engine()) {
            engine.update("CREATE TABLE s (a INTEGER NOT NULL, b INTEGER)");
            engine.update("INSERT INTO s VALUES (0, 0)");
            assertEquals("NOT NULL constraint failed: s.a",
                    fails(engine, "INSERT INTO s SELECT x, x FROM (VALUES (1), (NULL)) v(x)"));
            assertEquals("NOT NULL constraint failed: s.a", fails(engine, "INSERT INTO s VALUES (NULL, 1)"));
            assertEquals("NOT NULL constraint failed: s.a, which the INSERT gives no value",
                    fails(engine, "INSERT INTO s (b) VALUES (1)"));
            assertEquals(List.of(List.of(1L)), rows(engine, "SELECT count(*) FROM s"));
        }
    }

    @Test
    void explainShowsWhatIsWrittenAboveThePlanOfTheRows() {
        try (FyrQL engine = engine()) {
            engine.update("CREATE TABLE src AS SELECT 1 AS a");
            String query = engine.explain("SELECT a + 1 FROM src");
            assertEquals("Insert into mem.main.src\n" + query.indent(2),
                    engine.explain("INSERT INTO src SELECT a + 1 FROM src"));
            assertEquals("Create table mem.main.x (a INTEGER) [or replace]\n"
                    + engine.explain("SELECT 1 AS a").indent(2),
                    engine.explain("CREATE OR REPLACE TABLE x AS SELECT 1 AS a"));
            assertEquals("Create table mem.main.y (a INTEGER NOT NULL, b VARCHAR) [if not exists]\n",
                    engine.explain("CREATE TABLE IF NOT EXISTS y (a INTEGER NOT NULL, b VARCHAR)"));
            assertEquals("Drop table mem.main.src\n", engine.explain("DROP TABLE src"));
            assertEquals("Drop table if exists nothing [no such table]\n",
                    engine.explain("DROP TABLE IF EXISTS nothing"));
            assertEquals("Copy to 'it''s.csv.gz' [format: csv, header: false, compression: gzip]\n"
                    + engine.explain("SELECT * FROM src").indent(2),
                    engine.explain("COPY src TO 'it''s.csv.gz' (HEADER false)"));
            // Explaining runs nothing.
            assertEquals(List.of("people", "src"), engine.connector("mem").catalog().tables("main"));
        }
    }

    @Test
    void updateAndPreparedStatements() {
        try (FyrQL engine = engine()) {
            assertEquals("A query returns rows, not a number of rows written; run it with sql",
                    fails(engine, "SELECT 1"));
            engine.update("CREATE TABLE p (id INTEGER NOT NULL, name VARCHAR, amount DECIMAL(10,2))");
            PreparedQuery insert = engine.prepare("INSERT INTO p VALUES (?, ?, ?)");
            assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.decimal(10, 2)),
                    insert.parameterTypes());
            assertEquals(BoundStatement.COUNT, insert.schema());
            assertEquals(1, engine.update(insert, List.of(1, "a", new BigDecimal("2.50"))));
            assertEquals(1, engine.update(insert, Arrays.asList(2, null, null)));
            assertEquals(List.of(List.of(1L)), engine.rows(insert, List.of(3, "c", new BigDecimal("1.00"))).stream()
                    .map(Arrays::asList).toList());
            assertEquals(List.of(List.of(1, "a", new BigDecimal("2.50")), Arrays.asList(2, null, null),
                    List.of(3, "c", new BigDecimal("1.00"))), rows(engine, "SELECT * FROM p ORDER BY id"));

            PreparedQuery select = engine.prepare("SELECT name FROM p WHERE id = ?");
            assertEquals(List.of(DataType.INTEGER), select.parameterTypes());
            assertEquals(List.of(List.of("c")), engine.rows(select, List.of(3)).stream().map(Arrays::asList).toList());
            assertEquals("A query returns rows, not a number of rows written; run it with sql",
                    assertThrows(SqlException.class, () -> engine.update(select, List.of(3))).reason());
        }
    }

    @Test
    void namesSourcesAndSchemas() {
        try (FyrQL engine = engine()) {
            assertEquals(1, engine.update("CREATE TABLE other.t2 AS SELECT 1 AS a"));
            assertEquals(0, engine.update("CREATE TABLE other.main.t3 (a INTEGER)"));
            Session other = engine.newSession();
            other.use(new SearchPath("other", "main"));
            assertEquals(1, other.update("CREATE TABLE t4 AS SELECT 1 AS a"));
            assertEquals(List.of("t2", "t3", "t4"), engine.connector("other").catalog().tables("main"));
            assertEquals(1, other.update("INSERT INTO t3 VALUES (5)"));
            assertEquals(List.of(List.of(5)), rows(engine, "SELECT * FROM other.t3"));

            assertEquals("Source or schema with name nowhere does not exist",
                    fails(engine, "CREATE TABLE nowhere.t (a INTEGER)"));
            assertEquals("Source with name nowhere does not exist",
                    fails(engine, "CREATE TABLE nowhere.main.t (a INTEGER)"));
            assertEquals("Schema with name other.public does not exist",
                    fails(engine, "CREATE TABLE other.public.t (a INTEGER)"));
            assertEquals("Source ro does not write tables", fails(engine, "CREATE TABLE ro.t (a INTEGER)"));
            assertEquals("Source ro does not write tables", fails(engine, "INSERT INTO ro.people VALUES (4, 'Di')"));
            assertEquals("Source ro does not write tables", fails(engine, "DROP TABLE ro.people"));
            assertEquals("Table people of the memory source mem holds Java objects and cannot be written",
                    fails(engine, "INSERT INTO mem.people VALUES (4, 'Di')"));
            assertEquals(0, engine.update("DROP TABLE mem.people"));
            assertEquals("No source writes files of the format csv; put fyrql-connector-csv on the class path or"
                    + " register a source of the type csv", fails(engine, "COPY ro.people TO 'x.csv'"));
            assertEquals("No source reads files of the format csv; put fyrql-connector-csv on the class path or"
                    + " register a source of the type csv", fails(engine, "COPY ro.people FROM 'x.csv'"));
        }
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("a").build())
                .register(MemoryConnector.builder("b").build()).build()) {
            assertEquals("There is no default source for the table t; name its source, as in source.t",
                    fails(engine, "CREATE TABLE t (a INTEGER)"));
            assertEquals(0, engine.update("CREATE TABLE b.t (a INTEGER)"));
            assertEquals(1, engine.update("INSERT INTO t VALUES (1)"));
        }
    }

    @Test
    void aCanceledStatementLeavesNothingBehind() {
        try (FyrQL engine = engine()) {
            QueryContext context = new QueryContext();
            context.cancel();
            SqlStatement create = engine.statement("CREATE TABLE c AS SELECT * FROM people");
            assertThrows(QueryCancelledException.class, () -> engine.update(create, context));
            assertEquals(List.of("people"), engine.connector("mem").catalog().tables("main"));
        }
    }

    @Test
    void aWrittenTableIsReadOnSeveralThreads() {
        List<Object[]> numbers = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            numbers.add(new Object[] {i});
        }
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("mem").splitRows(1_000).batchSize(512)
                .table("numbers", Schema.of(new Schema.Field("n", DataType.INTEGER)), numbers).build())
                .threads(4).build()) {
            assertEquals(10_000, engine.update("CREATE TABLE copy AS SELECT n, n % 7 AS k FROM numbers"));
            assertEquals(List.of(List.of(10_000L, 49_995_000L)),
                    rows(engine, "SELECT count(*), CAST(sum(n) AS BIGINT) FROM copy"));
            assertEquals(List.of(List.of(0, 1429L), List.of(6, 1428L)),
                    rows(engine, "SELECT k, count(*) FROM copy WHERE k IN (0, 6) GROUP BY k ORDER BY k"));
            assertTrue(engine.explain("SELECT n FROM copy WHERE k = 1").contains("[parallel]"));
        }
    }

    @Test
    void theCatalogOfTheEngineSeesTheChanges() {
        try (FyrQL engine = engine()) {
            engine.update("CREATE TABLE t (a INTEGER)");
            assertEquals(TableRef.of("mem", "t"), engine.catalog().resolve(List.of("t")));
            engine.update("DROP TABLE t");
            assertTrue(engine.catalog().find(List.of("t")).isEmpty());
        }
    }
}
