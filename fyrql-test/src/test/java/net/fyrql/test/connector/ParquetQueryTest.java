/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Queries over Parquet files of random values of every flat type and of
 * nested {@code STRUCT}, {@code LIST} and {@code MAP} columns, which DuckDB
 * writes from the values of {@link DataGenerator} and then reads with its
 * own {@code read_parquet}: the rows must agree, in the run with pushdown,
 * where the connector takes over a limit without filters and
 * {@code count(*)}, reads only the fields of structs in use and skips row
 * groups by the filters, and in the one without.
 */
class ParquetQueryTest {

    private static final Schema FLAT = new Schema(List.of(
            new Schema.Field("id", DataType.BIGINT, false),
            new Schema.Field("b", DataType.BOOLEAN, true),
            new Schema.Field("t", DataType.TINYINT, true),
            new Schema.Field("s", DataType.SMALLINT, true),
            new Schema.Field("i", DataType.INTEGER, true),
            new Schema.Field("l", DataType.BIGINT, true),
            new Schema.Field("h", DataType.HUGEINT, true),
            new Schema.Field("f", DataType.FLOAT, true),
            new Schema.Field("d", DataType.DOUBLE, true),
            new Schema.Field("d4", DataType.decimal(4, 2), true),
            new Schema.Field("d18", DataType.decimal(18, 3), true),
            new Schema.Field("d38", DataType.decimal(38, 10), true),
            new Schema.Field("day", DataType.DATE, true),
            new Schema.Field("ts", DataType.TIMESTAMP, true),
            new Schema.Field("v", DataType.VARCHAR, true),
            new Schema.Field("bin", DataType.BLOB, true),
            new Schema.Field("u", DataType.UUID, true)));

    private static final DataType.Struct INNER = DataType.struct(new DataType.Struct.Field("x", DataType.DOUBLE),
            new DataType.Struct.Field("tags", DataType.listOf(DataType.VARCHAR)));

    private static final Schema NESTED = new Schema(List.of(
            new Schema.Field("id", DataType.BIGINT, false),
            new Schema.Field("s", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                    new DataType.Struct.Field("b", DataType.VARCHAR), new DataType.Struct.Field("c", INNER),
                    new DataType.Struct.Field("d", DataType.decimal(18, 3)),
                    new DataType.Struct.Field("ts", DataType.TIMESTAMP)), true),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER), true),
            new Schema.Field("ls", DataType.listOf(DataType.struct(new DataType.Struct.Field("p", DataType.SMALLINT),
                    new DataType.Struct.Field("q", DataType.listOf(DataType.BLOB)))), true),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.struct(
                    new DataType.Struct.Field("v", DataType.BIGINT), new DataType.Struct.Field("u", DataType.UUID))),
                    true),
            new Schema.Field("ll", DataType.listOf(DataType.listOf(DataType.decimal(38, 4))), true),
            new Schema.Field("v", DataType.VARCHAR, true)));

    @TempDir
    Path dir;

    /** Lets DuckDB write the random rows of a seed as a Parquet file with row groups of 2048 rows. */
    private Path write(DuckDbReference duckdb, long seed) {
        return write(duckdb, FLAT, 5000, seed);
    }

    /**
     * Lets DuckDB write random rows of a schema as a Parquet file with row
     * groups of 2048 rows; the first column is the number of the row.
     */
    private Path write(DuckDbReference duckdb, Schema schema, int count, long seed) {
        List<Object[]> rows = new DataGenerator(seed, 0.1, 0.05).generate(schema, count);
        for (int r = 0; r < rows.size(); r++) {
            rows.get(r)[0] = (long) r;
        }
        duckdb.load("random", schema, List.of(Batch.ofRows(schema, rows)));
        Path file = dir.resolve("random-" + schema.size() + "-" + seed + ".parquet");
        duckdb.execute("COPY (SELECT * FROM random ORDER BY id) TO '" + path(file)
                + "' (FORMAT parquet, ROW_GROUP_SIZE 2048)");
        return file;
    }

    @Test
    void queriesOverRandomFilesGiveTheRowsOfDuckDb() {
        List<String> failures = new ArrayList<>();
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("pq").batchSize(700).build())
                        .build()) {
            for (long seed : List.of(20261014L, 1L, 2L)) {
                String f = path(write(duckdb, seed));
                List<String> queries = List.of(
                        "SELECT * FROM '" + f + "'",
                        "SELECT d38, i, d38, v FROM read_parquet('" + f + "')",
                        "SELECT count(*) FROM '" + f + "'",
                        "SELECT count(*), count(*) AS again FROM parquet_scan('" + f + "')",
                        "SELECT * FROM '" + f + "' LIMIT 2500",
                        "SELECT id, v FROM '" + f + "' WHERE i > 0 LIMIT 100",
                        "SELECT count(v), sum(i), min(day), max(ts), min(d18), max(u), sum(d4) FROM '" + f + "'",
                        "SELECT b, count(*), max(bin), min(f) FROM '" + f + "' GROUP BY b",
                        "SELECT id, ts FROM '" + f + "' WHERE ts >= TIMESTAMP '2000-01-01' ORDER BY ts DESC, id"
                                + " LIMIT 7");
                for (String sql : queries) {
                    try {
                        PlanNode plan = engine.plan(sql);
                        Schema schema = plan.schema();
                        ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                                engine.rows(plan), Plans.ordered(plan));
                        if (!outcome.same()) {
                            failures.add(sql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                        }
                    } catch (RuntimeException e) {
                        failures.add(sql + "\n  fails: " + e);
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    /**
     * Filters of every form over the flat columns, with and without a
     * counterpart in the statistics, dictionaries and Bloom filters that
     * DuckDB writes, some with values of the rows {@code 1234} and
     * {@code 4321}: the rows must be those of DuckDB, whether the row
     * groups are skipped, with pushdown, or not.
     */
    @Test
    void filtersGiveTheRowsOfDuckDbWhetherTheySkipRowGroupsOrNot() {
        List<String> failures = new ArrayList<>();
        int skipping = 0;
        boolean pushdown;
        ParquetConnector parquet = ParquetConnector.builder("pq").batchSize(500).recordReads(true).build();
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder().register(parquet).build()) {
            pushdown = engine.pushdown();
            for (long seed : List.of(20261016L, 4L, 5L)) {
                String f = "'" + path(write(duckdb, seed)) + "'";
                String[] a = literals(duckdb, 1234);
                String[] b = literals(duckdb, 4321);
                List<String> filters = List.of(
                        "id = 1234",
                        "id BETWEEN 1000 AND 1999",
                        "id IN (1, 2047, 2048, 4999)",
                        "id < 100 OR id > 4900",
                        "NOT (id >= 100 AND id <= 4900)",
                        "id <> 7",
                        "id IS NOT DISTINCT FROM 4000",
                        "id IS DISTINCT FROM 4000",
                        "id > 2047.5",
                        "id = 2048.5",
                        "id NOT IN (1, 2, 3)",
                        "i > 0",
                        "i = " + a[0],
                        "i IN (" + a[0] + ", " + b[0] + ", 0)",
                        "i IS NULL",
                        "i IS NOT NULL AND id < 3000",
                        "t >= 100",
                        "s < -1000",
                        "l BETWEEN -1000000 AND 1000000",
                        "l = " + b[1],
                        "h > 0",
                        "b",
                        "NOT b",
                        "b = false OR id = 3",
                        "d4 > 1.5",
                        "d4 <= -10.25",
                        "d18 > 1000.123",
                        "d18 = " + a[2],
                        "d38 > 0",
                        "day > DATE '2000-01-01'",
                        "day BETWEEN DATE '1990-01-01' AND DATE '2010-12-31'",
                        "day >= TIMESTAMP '2000-06-01 12:00:00'",
                        "ts < TIMESTAMP '1999-12-31 23:59:59.5'",
                        "ts >= DATE '2000-01-01'",
                        "v = " + a[3],
                        "v >= 'm'",
                        "v IN (" + b[3] + ", 'zzz')",
                        "v IS NULL OR v < 'b'",
                        "NOT (v = " + b[3] + ")",
                        "f > 0.5",
                        "d < 0 AND id > 1000",
                        "id + 1 = 1235",
                        "u IS NULL",
                        "bin IS NOT NULL AND id < 10");
                for (String filter : filters) {
                    String sql = "SELECT * FROM " + f + " WHERE " + filter;
                    parquet.clearReads();
                    try {
                        PlanNode plan = engine.plan(sql);
                        Schema schema = plan.schema();
                        ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                                engine.rows(plan), Plans.ordered(plan));
                        if (!outcome.same()) {
                            failures.add(sql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                        }
                    } catch (RuntimeException e) {
                        failures.add(sql + "\n  fails: " + e);
                    }
                    int read = parquet.reads().size();
                    if (read < 3) {
                        skipping++;
                    }
                    if (filter.equals("id = 1234") && read != (engine.pushdown() ? 1 : 3)) {
                        failures.add(sql + "\n  reads " + read + " of 3 row groups");
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
        if (pushdown) {
            assertTrue(skipping >= 21, "filters that skip row groups: " + skipping);
        } else {
            assertEquals(0, skipping);
        }
    }

    /**
     * Returns the values of {@code i}, {@code l}, {@code d18} and {@code v}
     * of a row of the table {@code random} in DuckDB as SQL literals.
     */
    private static String[] literals(DuckDbReference duckdb, long id) {
        String sql = "SELECT coalesce(CAST(i AS VARCHAR), 'NULL'), coalesce(CAST(l AS VARCHAR), 'NULL'),"
                + " coalesce(CAST(d18 AS VARCHAR), 'NULL'),"
                + " coalesce(chr(39) || replace(v, chr(39), chr(39) || chr(39)) || chr(39), 'NULL') FROM random"
                + " WHERE id = " + id;
        try (Statement s = duckdb.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)};
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    @Test
    void queriesOverRandomNestedFilesGiveTheRowsOfDuckDb() {
        List<String> failures = new ArrayList<>();
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("pq").batchSize(300).build())
                        .build()) {
            for (long seed : List.of(20261016L, 3L)) {
                String f = "'" + path(write(duckdb, NESTED, 2500, seed)) + "'";
                List<String> queries = List.of(
                        "SELECT * FROM " + f,
                        "SELECT id, s.c.x, s.a, s.c.tags, v FROM " + f,
                        "SELECT s.c, s.c.x, s, s.d FROM " + f + " WHERE id % 3 = 0",
                        "SELECT id, ls, m, ll FROM " + f + " WHERE s.a > 0",
                        "SELECT s.b, count(*), sum(s.a), max(s.ts), min(s.d) FROM " + f + " GROUP BY s.b",
                        "SELECT id, unnest(l) AS x FROM " + f + " ORDER BY id, x NULLS FIRST",
                        "SELECT id, s.c.x FROM " + f + " WHERE s.c.x > 0 ORDER BY id LIMIT 10",
                        "SELECT count(*), count(s), count(s.c), count(l), count(ls), count(m), count(ll) FROM " + f,
                        "SELECT id, s.c FROM " + f + " LIMIT 2100",
                        "SELECT len(l), len(ls), m IS NULL, len(ll), ls[1].p, ll[1] FROM " + f);
                for (String sql : queries) {
                    try {
                        PlanNode plan = engine.plan(sql);
                        Schema schema = plan.schema();
                        ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                                engine.rows(plan), Plans.ordered(plan));
                        if (!outcome.same()) {
                            failures.add(sql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                        }
                    } catch (RuntimeException e) {
                        failures.add(sql + "\n  fails: " + e);
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    @Test
    void onlyTheFieldsOfStructsInUseAreReadWithPushdown() {
        try (DuckDbReference duckdb = new DuckDbReference()) {
            Path file = write(duckdb, NESTED, 2500, 5);
            ParquetConnector parquet = ParquetConnector.builder("pq").path("t", file).recordReads(true).build();
            try (FyrQL engine = FyrQL.builder().register(parquet).build()) {
                String sql = "SELECT id, s.c.x FROM t WHERE s.a > 0";
                engine.rows(sql);
                String explain = engine.explain(sql);
                List<String> leaves = parquet.reads().stream().flatMap(r -> r.columns().stream()).distinct().toList();
                if (engine.pushdown()) {
                    assertTrue(explain.contains("Scan pq.main.t (id BIGINT, s.c.x DOUBLE, s.a INTEGER)"), explain);
                    assertEquals(List.of("id", "s.a", "s.c.x"), leaves);
                } else {
                    assertTrue(explain.contains("Scan pq.main.t (id BIGINT, s STRUCT("), explain);
                    assertEquals(List.of("id", "s.a", "s.b", "s.c.x", "s.c.tags.list.element", "s.d", "s.ts"),
                            leaves);
                }
            }
        }
    }

    @Test
    void aLimitAndCountStarAreTakenOverWithPushdown() {
        try (DuckDbReference duckdb = new DuckDbReference()) {
            Path file = write(duckdb, 7);
            ParquetConnector parquet = ParquetConnector.builder("pq").path("t", file).recordReads(true).build();
            try (FyrQL engine = FyrQL.builder().register(parquet).build()) {
                assertEquals(2500, engine.rows("SELECT * FROM t LIMIT 2500").size());
                long delivered = parquet.reads().stream().mapToLong(ParquetConnector.Read::rows).sum();
                parquet.clearReads();
                assertEquals(List.of(5000L), List.of(engine.rows("SELECT count(*) FROM t").get(0)[0]));
                String limit = engine.explain("SELECT * FROM t LIMIT 2500");
                String count = engine.explain("SELECT count(*) FROM t");
                if (engine.pushdown()) {
                    assertEquals(2500, delivered);
                    assertTrue(limit.contains("[pushed: LIMIT 2500]"), limit);
                    assertTrue(count.contains("[pushed:"), count);
                    assertEquals(List.of(), parquet.reads());
                } else {
                    assertTrue(delivered >= 2500 && delivered < 5000, "rows delivered: " + delivered);
                    assertFalse(limit.contains("[pushed:"), limit);
                    assertFalse(count.contains("[pushed:"), count);
                    // Without pushdown every row group is read, of it at most the one column the engine asks for.
                    assertTrue(parquet.reads().stream().allMatch(r -> r.columns().size() <= 1),
                            parquet.reads()::toString);
                    assertEquals(5000, parquet.reads().stream().mapToLong(ParquetConnector.Read::rows).sum());
                }
            }
        }
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
