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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.WrittenFiles;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FyrQL moves TPC-H tables at scale factor 0.01 between the sources of
 * {@link TpchEngine}: {@code orders} from the database (PostgreSQL, or H2
 * if it is off) into a Parquet file, {@code partsupp} from a CSV file into
 * a table of the memory source that {@code CREATE TABLE} creates with its
 * columns and {@code COPY ... FROM} loads, and {@code part} from a file of
 * JSON lines into an H2 database of its own. Q3, Q9 and Q13 over the copies
 * give the rows that DuckDB gives over the original files.
 * <p>
 * {@code MERGE} brings rows of the files into the databases: suppliers of
 * the JSON file into a table of the database with its primary key, and
 * quantities of the Parquet file of {@code lineitem} into a table of H2
 * with a key of two columns. Counts and rows are those of DuckDB.
 */
class WriteAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void queriesOverTheCopiesGiveTheRowsOfDuckDb() {
        Path data = TpchTest.directory(TpchEngine.SMALL);
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, data);
        TestDatabase h2 = TestDatabases.h2();
        Path orders = dir.resolve("copy-of-o.parquet");
        try (FyrQL engine = tpch.open(null, null, 0, List.of(h2.connector("h2", b -> {
        })));
                DuckDbReference duck = new DuckDbReference()) {
            tpch.views(duck);
            long orderRows = count(engine, "db.orders");
            long partsuppRows = count(engine, "files.partsupp");
            long partRows = count(engine, "json.part");

            assertEquals(orderRows, engine.update("COPY db.orders TO '" + WrittenFiles.path(orders) + "'"));
            assertTrue(Files.isRegularFile(orders), orders.toString());

            List<String> columns = new ArrayList<>();
            for (Schema.Field f : TpchTable.PARTSUPP.schema().fields()) {
                columns.add(f.name() + " " + f.type().sqlName() + " NOT NULL");
            }
            assertEquals(0, engine.update("CREATE TABLE mem.partsupp (" + String.join(", ", columns) + ")"));
            assertEquals(partsuppRows, engine.update("COPY mem.partsupp FROM '" + WrittenFiles.path(TpchData.file(
                    data, TpchTable.PARTSUPP)) + "' (DELIMITER '" + TpchData.DELIMITER + "')"));

            assertEquals(partRows, engine.update("CREATE TABLE h2.part AS SELECT * FROM json.part"));
            assertEquals(names(TpchTable.PART.schema()), names(engine.connector("h2").catalog().schema(
                    TableRef.of("h2", "part"))));

            for (String name : List.of("q03", "q09", "q13")) {
                TpchQueries.Query query = TpchQueries.ALL.stream().filter(q -> q.name().equals(name)).findFirst()
                        .orElseThrow();
                String copyOfOrders = "read_parquet('" + WrittenFiles.path(orders) + "') AS orders";
                String sql = query.sql().replaceAll("\\bpartsupp\\b", "mem.partsupp")
                        .replaceAll("\\bpart\\b", "h2.part")
                        .replaceAll("\\borders\\b", Matcher.quoteReplacement(copyOfOrders));
                assertFalse(sql.equals(query.sql()), sql);
                PlanNode plan = engine.plan(sql);
                List<Object[]> actual = engine.rows(plan);
                assertFalse(actual.isEmpty(), name + " gives no rows");
                Optional<String> oracle = TpchQueries.compare(plan, duck.query(query.sql(), plan.schema()), actual);
                if (oracle.isPresent()) {
                    fail(name + " over the copies differs from DuckDB:\n" + oracle.get() + "\n" + engine.explain(plan));
                }
            }

            assertEquals(0, engine.update("DROP TABLE h2.part"));
            assertEquals(0, engine.update("DROP TABLE mem.partsupp"));
            assertEquals(partsuppRows, count(engine, "partsupp"), "partsupp is the one of the CSV file again");
        }
    }

    @Test
    void mergeBringsRowsOfFilesIntoDatabases() {
        Path data = TpchTest.directory(TpchEngine.SMALL);
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, data);
        TestDatabase h2 = TestDatabases.h2();
        try (FyrQL engine = tpch.open(null, null, 0, List.of(h2.connector("h2", b -> {
        })));
                DuckDbReference duck = new DuckDbReference()) {
            tpch.views(duck);
            List<String> statements = List.of(
                    "CREATE TABLE db.supp (s_suppkey INTEGER PRIMARY KEY, s_name VARCHAR, s_nationkey INTEGER,"
                            + " s_acctbal DECIMAL(15,2))",
                    "INSERT INTO db.supp SELECT s_suppkey, s_name, s_nationkey, s_acctbal FROM json.supplier"
                            + " WHERE s_suppkey % 2 = 0",
                    "MERGE INTO db.supp USING (SELECT * FROM json.supplier WHERE s_suppkey % 3 <> 0) s"
                            + " ON supp.s_suppkey = s.s_suppkey"
                            + " WHEN MATCHED AND s.s_nationkey < 5 THEN DELETE"
                            + " WHEN MATCHED THEN UPDATE SET s_acctbal = s.s_acctbal + 1, s_name = upper(s.s_name)"
                            + " WHEN NOT MATCHED THEN INSERT VALUES (s.s_suppkey, s.s_name, s.s_nationkey, 0)"
                            + " WHEN NOT MATCHED BY SOURCE AND supp.s_suppkey < 30 THEN DELETE",
                    "CREATE TABLE h2.ps (ps_partkey INTEGER, ps_suppkey INTEGER, ps_availqty INTEGER,"
                            + " PRIMARY KEY (ps_partkey, ps_suppkey))",
                    "INSERT INTO h2.ps SELECT ps_partkey, ps_suppkey, ps_availqty FROM files.partsupp"
                            + " WHERE ps_partkey < 100",
                    "MERGE INTO h2.ps USING (SELECT l_partkey, l_suppkey, sum(l_quantity) AS q FROM"
                            + " parquet.lineitem WHERE l_partkey < 200 GROUP BY ALL) l"
                            + " ON ps.ps_partkey = l.l_partkey AND ps.ps_suppkey = l.l_suppkey"
                            + " WHEN MATCHED THEN UPDATE SET ps_availqty = ps_availqty - CAST(l.q AS INTEGER)"
                            + " WHEN NOT MATCHED THEN INSERT VALUES (l.l_partkey, l.l_suppkey, 0)");
            for (String sql : statements) {
                String theirs = sql.replaceAll("\\b(db|h2|json|files|parquet)\\.", "");
                long expected;
                try (java.sql.Statement s = duck.connection().createStatement()) {
                    expected = s.executeUpdate(theirs);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("DuckDB rejects " + theirs, e);
                }
                long count = engine.update(sql);
                if (sql.startsWith("MERGE") || sql.startsWith("INSERT")) {
                    assertTrue(count > 0, sql);
                    assertEquals(expected, count, sql);
                }
            }
            for (TableRef table : List.of(TableRef.of("db", "supp"), TableRef.of("h2", "ps"))) {
                PlanNode.Scan scan = engine.scan(table);
                List<Object[]> theirs = duck.query("SELECT * FROM " + table.table(), scan.schema());
                net.fyrql.test.ResultComparison.Outcome outcome = net.fyrql.test.ResultComparison.compare(
                        scan.schema(), theirs, engine.rows(scan), false);
                assertTrue(outcome.same(), table + ":\n" + outcome.report());
            }
            assertEquals(List.of("s_suppkey"), engine.connector("db").catalog().primaryKey(TableRef.of("db", "supp"))
                    .stream().map(n -> n.toLowerCase(java.util.Locale.ROOT)).toList());
            assertEquals(0, engine.update("DROP TABLE db.supp"));
            assertEquals(0, engine.update("DROP TABLE h2.ps"));
        }
    }

    private static long count(FyrQL engine, String table) {
        return ((Number) engine.rows("SELECT count(*) FROM " + table).get(0)[0]).longValue();
    }

    private static List<String> names(Schema schema) {
        return schema.fields().stream().map(Schema.Field::name).toList();
    }
}
