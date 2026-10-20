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
 * the memory source, and {@code part} from a file of JSON lines into an H2
 * database of its own. Q3, Q9 and Q13 over the copies give the rows that
 * DuckDB gives over the original files.
 */
class WriteAcceptanceTest {

    @TempDir
    Path dir;

    @Test
    void queriesOverTheCopiesGiveTheRowsOfDuckDb() {
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, TpchTest.directory(TpchEngine.SMALL));
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

            long half = engine.update("CREATE TABLE mem.partsupp AS SELECT * FROM files.partsupp"
                    + " WHERE ps_partkey <= 1000");
            assertEquals(partsuppRows, half + engine.update("INSERT INTO mem.partsupp SELECT * FROM files.partsupp"
                    + " WHERE ps_partkey > 1000"));

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

    private static long count(FyrQL engine, String table) {
        return ((Number) engine.rows("SELECT count(*) FROM " + table).get(0)[0]).longValue();
    }

    private static List<String> names(Schema schema) {
        return schema.fields().stream().map(Schema.Field::name).toList();
    }
}
