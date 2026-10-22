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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@linkplain Metamorphic metamorphic checks} over the memory source of
 * the random tables, in four engines: interpreted on the threads of the
 * default, with all code compiled, on one thread, and with a small budget
 * that spills. Every query is checked in each of them; a difference that is
 * not known fails the test, and the report shows it shrunk.
 */
class MetamorphicTest {

    @Test
    void tlpAndNorecHoldInEveryConfiguration() throws IOException {
        long seed = RandomRun.SEED;
        Path spillDirectory = Files.createTempDirectory("fyrql-metamorphic-");
        try (FyrQL interpreted = RandomTables.engine(RandomTables.rows(seed));
                FyrQL compiled = RandomTables.engine(RandomTables.rows(seed), CodegenMode.ALWAYS);
                FyrQL sequential = RandomTables.engine(RandomTables.rows(seed), CodegenMode.OFF, 1);
                FyrQL spilling = RandomTables.engine(RandomTables.rows(seed), CodegenMode.OFF,
                        Executor.threadsFromSystemProperty(), List.of(),
                        b -> b.memoryBudget(TwoEngines.SPILL_BUDGET).spillDirectory(spillDirectory))) {
            Map<String, List<Object[]>> samples = Metamorphic.samples(interpreted, RandomTables.SCHEMAS);
            List<Metamorphic> runs = List.of(new Metamorphic("TLP and NoREC, memory, interpreted", interpreted),
                    new Metamorphic("TLP and NoREC, memory, compiled code", compiled),
                    new Metamorphic("TLP and NoREC, memory, one thread", sequential),
                    new Metamorphic("TLP and NoREC, memory, spilling", spilling));
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                SqlGenerator.Partition p = new SqlGenerator(RandomRun.random(i), interpreted, RandomTables.SCHEMAS,
                        samples).partition();
                for (Metamorphic run : runs) {
                    run.check(i, p);
                }
            }
            runs.forEach(run -> System.out.println(run.report()));
            for (Metamorphic run : runs) {
                assertTrue(run.unexplained().isEmpty(), run.report());
            }
            assertTrue(spilling.spillStatistics().parts() > 0, "no query spilled");
        } finally {
            Files.deleteIfExists(spillDirectory);
        }
    }

    /**
     * A source that takes over every filter and applies none, as a wrong
     * connector might: the checks must see it.
     */
    @Test
    void theChecksSeeASourceThatIgnoresFilters() {
        MemoryConnector.Builder memory = MemoryConnector.builder(RandomTables.SOURCE).batchSize(16).splitRows(16);
        RandomTables.rows(RandomRun.SEED).forEach((t, rows) -> memory.table(t, RandomTables.SCHEMAS.get(t), rows));
        try (FyrQL engine = FyrQL.builder().register(new IgnoringFilters(memory.build())).pushdown(true).batchSize(16)
                .timeZone(RandomTables.ZONE).build()) {
            Metamorphic run = new Metamorphic("TLP and NoREC, a source that ignores filters", engine);
            Map<String, List<Object[]>> samples = Metamorphic.samples(engine, RandomTables.SCHEMAS);
            for (int i = 0; i < 50; i++) {
                run.check(i, new SqlGenerator(RandomRun.random(i), engine, RandomTables.SCHEMAS, samples).partition());
            }
            System.out.println(run.report());
            assertFalse(run.unexplained().isEmpty(), run.report());
        }
    }

    /**
     * {@code DISTINCT} keeps one of two equal intervals of different forms,
     * whichever comes first, so the parts of a partition may keep the other
     * one: no difference. Found by a long run over PostgreSQL.
     */
    @Test
    void equalIntervalsOfDifferentFormsAreOneValue() {
        SqlGenerator.Partition p = partition(SqlGenerator.Kind.DISTINCT, "SELECT DISTINCT iv FROM (VALUES "
                + "(INTERVAL '30 days', 1), (INTERVAL '1 month', 2), (INTERVAL '720 hours', 3)) AS t(iv, n)", "n > 1");
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED), CodegenMode.OFF, 1)) {
            Metamorphic.Check check = new Metamorphic("intervals", engine).tlp(p);
            assertEquals(Metamorphic.Result.SAME, check.result(), check.report());
        }
    }

    /** Takes over every filter of a scan and reads all rows. */
    private record IgnoringFilters(Connector delegate) implements Connector {

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public Catalog catalog() {
            return delegate.catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            ScanPlan read = delegate.plan(table, new ScanRequest(request.columns(), List.of(), OptionalLong.empty(),
                    List.of(), Optional.empty()));
            return new ScanPlan(table, read.output(), read.columns(), request.filters(), false, false, false, read);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return delegate.splits((ScanPlan) plan.state());
        }

        @Override
        public BatchReader open(Split split) {
            return delegate.open(split);
        }
    }

    private static SqlGenerator.Partition partition(SqlGenerator.Kind kind, String base, String predicate) {
        Query q = SqlParser.parse(base);
        SqlExpr p = ((SelectItem.ExpressionItem) ((QueryBody.Select) SqlParser.parse("SELECT " + predicate).body())
                .items().get(0)).expression();
        return new SqlGenerator.Partition(kind, q.with(), (QueryBody.Select) q.body(), p, p);
    }

    /** The queries of each form of TLP and of NoREC over a fixed query. */
    @Test
    void theFormsAreWrittenAsExpected() {
        Schema none = new Schema(List.of());
        SqlGenerator.Partition where = partition(SqlGenerator.Kind.WHERE, "SELECT i + 1 AS x FROM p WHERE k > 2",
                "v = 'a' OR b");
        SqlGenerator.Partition distinct = partition(SqlGenerator.Kind.DISTINCT, "SELECT DISTINCT v FROM p",
                "i < 3");
        SqlGenerator.Partition having = new SqlGenerator.Partition(SqlGenerator.Kind.HAVING, List.of(),
                (QueryBody.Select) SqlParser.parse("SELECT k, sum(i) FROM p GROUP BY k HAVING count(*) > 1").body(),
                partition(SqlGenerator.Kind.WHERE, "SELECT 1", "sum(i) > k").predicate(),
                partition(SqlGenerator.Kind.WHERE, "SELECT 1", "i IS NULL").predicate());
        SqlGenerator.Partition aggregate = partition(SqlGenerator.Kind.AGGREGATE, "WITH w0 AS (SELECT * FROM q) "
                + "SELECT k AS g0, count(*) AS a0, sum(i) AS a1, min(v) AS a2, bool_or(i > 2) AS a3 FROM w0 "
                + "GROUP BY k", "dt IS NULL");
        List<String> written = new ArrayList<>();
        written.addAll(Metamorphic.tlpQueries(where, none));
        written.addAll(Metamorphic.norecQueries(where));
        written.addAll(Metamorphic.tlpQueries(distinct, none));
        written.addAll(Metamorphic.tlpQueries(having, none));
        written.addAll(Metamorphic.norecQueries(having));
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED))) {
            written.addAll(Metamorphic.tlpQueries(aggregate, engine.plan(SqlPrinter.print(aggregate.query()))
                    .schema()));
        }
        assertEquals("""
                SELECT (i + 1) AS x FROM p WHERE (k > 2)
                SELECT (i + 1) AS x FROM p WHERE ((k > 2) AND ((v = 'a') OR b)) \
                UNION ALL SELECT (i + 1) AS x FROM p WHERE ((k > 2) AND (NOT ((v = 'a') OR b))) \
                UNION ALL SELECT (i + 1) AS x FROM p WHERE ((k > 2) AND (((v = 'a') OR b) IS NULL))
                SELECT count(*) FROM p WHERE ((v = 'a') OR b)
                SELECT coalesce(sum(CASE WHEN ((v = 'a') OR b) THEN 1 ELSE 0 END), 0) FROM p
                SELECT DISTINCT v FROM p
                SELECT DISTINCT v FROM p WHERE (i < 3) UNION SELECT DISTINCT v FROM p WHERE (NOT (i < 3)) \
                UNION SELECT DISTINCT v FROM p WHERE ((i < 3) IS NULL)
                SELECT k, sum(i) FROM p GROUP BY k HAVING (count(*) > 1)
                SELECT k, sum(i) FROM p GROUP BY k HAVING ((count(*) > 1) AND (sum(i) > k)) \
                UNION ALL SELECT k, sum(i) FROM p GROUP BY k HAVING ((count(*) > 1) AND (NOT (sum(i) > k))) \
                UNION ALL SELECT k, sum(i) FROM p GROUP BY k HAVING ((count(*) > 1) AND ((sum(i) > k) IS NULL))
                SELECT count(*) FROM p WHERE (i IS NULL)
                SELECT coalesce(sum(CASE WHEN (i IS NULL) THEN 1 ELSE 0 END), 0) FROM p
                WITH w0 AS (SELECT * FROM q) SELECT k AS g0, count(*) AS a0, sum(i) AS a1, min(v) AS a2, \
                bool_or((i > 2)) AS a3 FROM w0 GROUP BY k
                WITH w0 AS (SELECT * FROM q) SELECT g0 AS g0, CAST(sum(a0) AS BIGINT) AS a0, \
                CAST(sum(a1) AS HUGEINT) AS a1, CAST(min(a2) AS VARCHAR) AS a2, CAST(bool_or(a3) AS BOOLEAN) AS a3 \
                FROM (SELECT k AS g0, count(*) AS a0, sum(i) AS a1, min(v) AS a2, bool_or((i > 2)) AS a3 FROM w0 \
                WHERE (dt IS NULL) GROUP BY k \
                UNION ALL SELECT k AS g0, count(*) AS a0, sum(i) AS a1, min(v) AS a2, bool_or((i > 2)) AS a3 FROM w0 \
                WHERE (NOT (dt IS NULL)) GROUP BY k \
                UNION ALL SELECT k AS g0, count(*) AS a0, sum(i) AS a1, min(v) AS a2, bool_or((i > 2)) AS a3 FROM w0 \
                WHERE ((dt IS NULL) IS NULL) GROUP BY k) AS u GROUP BY g0""", String.join("\n", written));
    }
}
