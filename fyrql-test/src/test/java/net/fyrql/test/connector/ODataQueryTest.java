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
import net.fyrql.connector.odata.FakeODataServer;
import net.fyrql.connector.odata.ODataConnector;
import net.fyrql.connector.odata.ODataVersion;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Queries over random rows of every type of OData that the fake server
 * answers in version 2 and 4, as they are and with the quirks of real
 * services, which DuckDB holds as FyrQL reads them: the rows must agree,
 * in the run with pushdown, where the filters go to the services as
 * hints, or as exact conditions to the services the source trusts, and
 * orders, limits and {@code count(*)} as {@code $orderby}, {@code $top}
 * and {@code /$count}, and in the one without, where no {@code $filter}
 * is sent.
 */
class ODataQueryTest {

    private static final int ROWS = 1500;

    /** The columns of both versions; version 2 has no {@code DATE}. */
    private static final Schema V4 = new Schema(List.of(
            new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("b", DataType.BOOLEAN, true),
            new Schema.Field("t", DataType.TINYINT, true),
            new Schema.Field("s", DataType.SMALLINT, true),
            new Schema.Field("i", DataType.INTEGER, true),
            new Schema.Field("l", DataType.BIGINT, true),
            new Schema.Field("f", DataType.DOUBLE, true),
            new Schema.Field("d", DataType.decimal(18, 3), true),
            new Schema.Field("ts", DataType.TIMESTAMP, true),
            new Schema.Field("v", DataType.VARCHAR, true),
            new Schema.Field("bin", DataType.BLOB, true),
            new Schema.Field("u", DataType.UUID, true),
            new Schema.Field("day", DataType.DATE, true)));

    private static final Schema V2 = new Schema(V4.fields().subList(0, V4.size() - 1));

    /**
     * The latest timestamp that the fake services of version 4 can write,
     * whose offset of two hours must keep the year below 10000.
     */
    private static final long LATEST = Conversions.parseTimestamp("9999-12-31 21:59:59.999999");

    /** Returns random rows of a schema; the first column is the number of the row. */
    private static List<Object[]> rows(Schema schema, long seed) {
        List<Object[]> rows = new DataGenerator(seed, 0.1, 0.05).generate(schema, ROWS);
        int ts = schema.indexOf("ts");
        for (int r = 0; r < rows.size(); r++) {
            Object[] row = rows.get(r);
            row[0] = r;
            if (row[ts] != null && (Long) row[ts] > LATEST) {
                row[ts] = LATEST;
            }
        }
        return rows;
    }

    /** Returns the rows as FyrQL reads them from a service of version 2: timestamps to the millisecond. */
    private static List<Object[]> asRead(List<Object[]> rows, int ts) {
        List<Object[]> read = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] copy = row.clone();
            if (copy[ts] != null) {
                copy[ts] = Math.floorDiv((Long) copy[ts], 1000L) * 1000L;
            }
            read.add(copy);
        }
        return read;
    }

    /**
     * The filters of a seed, over the columns of both versions, some with
     * values of the rows {@code 123} and {@code 1234}.
     */
    private static List<String> filters(String[] a, String[] b) {
        return List.of(
                "id = 123",
                "id BETWEEN 100 AND 199",
                "id IN (1, 7, 399, 400, 1499)",
                "id < 10 OR id > 1490",
                "NOT (id >= 10 AND id <= 1490)",
                "id <> 7",
                "id IS NOT DISTINCT FROM 400",
                "id IS DISTINCT FROM 400",
                "id > 799.5",
                "id = 800.5",
                "id NOT IN (1, 2, 3)",
                "i > 0",
                "i = " + a[0],
                "i IN (" + a[0] + ", " + b[0] + ", 0)",
                "i IS NULL",
                "i IS NOT NULL AND id < 700",
                "t >= 100",
                "s < -1000",
                "l BETWEEN -1000000 AND 1000000",
                "l = " + b[1],
                "b",
                "NOT b",
                "b = false OR id = 3",
                "d > 1000.123",
                "d = " + a[2],
                "d <= -10.25",
                "ts < TIMESTAMP '1999-12-31 23:59:59.5'",
                "ts >= DATE '2000-01-01'",
                "ts = " + a[4],
                "ts <= " + b[4],
                "ts > " + a[4] + " AND ts <= " + b[4],
                "v = " + a[3],
                "v >= 'm'",
                "v IN (" + b[3] + ", 'zzz')",
                "v IS NULL OR v < 'b'",
                "NOT (v = " + b[3] + ")",
                "v LIKE 'a%'",
                "v LIKE '%b%'",
                "v LIKE 'A_%'",
                "u = " + a[5],
                "u IS NULL",
                "f > 0.5",
                "d < 0 AND id > 300",
                "id + 1 = 124",
                "bin IS NOT NULL AND id < 10");
    }

    /** The filters of the {@code DATE} column of version 4. */
    private static final List<String> DAY_FILTERS = List.of(
            "day > DATE '2000-01-01'",
            "day BETWEEN DATE '1990-01-01' AND DATE '2010-12-31'",
            "day >= TIMESTAMP '2000-06-01 12:00:00'",
            "day IS NULL OR day = DATE '1970-01-01'");

    /** Queries with orders, limits and counts; {@code FROM T} stands for the table. */
    private static final List<String> QUERIES = List.of(
            "SELECT * FROM T LIMIT 700",
            "SELECT id, v FROM T WHERE i > 0 LIMIT 100",
            "SELECT id, ts FROM T WHERE ts >= TIMESTAMP '2000-01-01' ORDER BY ts DESC, id LIMIT 7",
            "SELECT id, d FROM T WHERE d IS NOT NULL ORDER BY d, id LIMIT 9",
            "SELECT id, i FROM T ORDER BY i DESC, id LIMIT 12",
            "SELECT id, l FROM T WHERE id > 1000 ORDER BY l DESC NULLS FIRST, id LIMIT 5",
            "SELECT count(*) FROM T",
            "SELECT count(*), count(*) AS again FROM T WHERE id >= 1000",
            "SELECT count(*) FROM T WHERE v = 'a'",
            "SELECT b, count(*), max(ts), min(d) FROM T GROUP BY b",
            "SELECT id, v FROM T ORDER BY id LIMIT 3 OFFSET 1498");

    @Test
    void queriesGiveTheRowsOfDuckDbWithAndWithoutPushdownAgainstServicesWithQuirks() {
        List<String> failures = new ArrayList<>();
        int queries = 0;
        boolean pushdown;
        try (FakeODataServer server = FakeODataServer.start(); DuckDbReference duckdb = new DuckDbReference()) {
            ODataConnector od = ODataConnector.builder("od").service("v2", server.url("v2"))
                    .service("v4", server.url("v4")).service("loose", server.url("loose"))
                    .service("ignoring", server.url("ignoring")).pageSize(400).batchSize(300).build();
            ODataConnector exact = ODataConnector.builder("exact").service("v2", server.url("v2"))
                    .service("v4", server.url("v4")).trustFilters(true).batchSize(500).build();
            try (FyrQL engine = FyrQL.builder().register(od).register(exact).build()) {
                pushdown = engine.pushdown();
                for (long seed : List.of(20261017L, 1L, 2L)) {
                    List<Object[]> v4 = rows(V4, seed);
                    List<Object[]> v2 = new ArrayList<>();
                    for (Object[] row : v4) {
                        v2.add(Arrays.copyOf(row, V2.size()));
                    }
                    server.service("v4", ODataVersion.V4).pageSize(500).entitySet("Random", V4, v4, "id");
                    server.service("v2", ODataVersion.V2).pageSize(500).relativeLinks(true)
                            .entitySet("Random", V2, v2, "id");
                    server.service("loose", ODataVersion.V4).caseInsensitive(true).ignoreTrailingSpaces(true)
                            .entitySet("Random", V4, v4, "id");
                    server.service("ignoring", ODataVersion.V2).ignoreFilter(true).ignoreTop(true).pageSize(600)
                            .entitySet("Random", V2, v2, "id");
                    od.refresh();
                    exact.refresh();
                    duckdb.load("random_v4", V4, List.of(Batch.ofRows(V4, v4)));
                    duckdb.load("random_v2", V2, List.of(Batch.ofRows(V2, asRead(v2, V2.indexOf("ts")))));
                    String[] a = literals(duckdb, 123);
                    String[] b = literals(duckdb, 1234);
                    List<String> sql = new ArrayList<>();
                    for (String f : filters(a, b)) {
                        sql.add("SELECT * FROM T WHERE " + f);
                    }
                    sql.addAll(QUERIES);
                    List<String> tables = List.of("od.v2.Random", "od.v4.Random", "od.loose.Random",
                            "od.ignoring.Random", "exact.v2.Random", "exact.v4.Random");
                    for (String table : tables) {
                        String reference = table.contains("v4") || table.contains("loose") ? "random_v4"
                                : "random_v2";
                        List<String> all = new ArrayList<>(sql);
                        if (reference.equals("random_v4")) {
                            for (String f : DAY_FILTERS) {
                                all.add("SELECT * FROM T WHERE " + f);
                            }
                        }
                        for (String q : all) {
                            queries++;
                            String fyrql = q.replace("FROM T", "FROM " + table);
                            try {
                                PlanNode plan = engine.plan(fyrql);
                                Schema schema = plan.schema();
                                List<Object[]> expected = duckdb.query(q.replace("FROM T", "FROM " + reference),
                                        schema);
                                ResultComparison.Outcome outcome = ResultComparison.compare(schema, expected,
                                        engine.rows(plan), Plans.ordered(plan));
                                if (!outcome.same()) {
                                    failures.add(fyrql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                                }
                            } catch (RuntimeException e) {
                                failures.add(fyrql + "\n  fails: " + e);
                            }
                        }
                    }
                }
            }
            if (!failures.isEmpty()) {
                fail(failures.size() + " of " + queries + " queries differ:\n\n" + String.join("\n\n",
                        failures.subList(0, Math.min(25, failures.size()))));
            }
            List<FakeODataServer.Request> requests = server.requests();
            long filtered = requests.stream().filter(r -> r.parameters().containsKey("$filter")).count();
            long selected = requests.stream().filter(r -> r.parameters().containsKey("$select")).count();
            long ordered = requests.stream().filter(r -> r.parameters().containsKey("$orderby")).count();
            long top = requests.stream().filter(r -> r.parameters().containsKey("$top")).count();
            long counted = requests.stream().filter(r -> r.path().endsWith("/$count")).count();
            assertTrue(selected > 100, "requests with $select: " + selected);
            if (pushdown) {
                assertTrue(filtered > 500, "requests with $filter: " + filtered);
                assertTrue(ordered >= 12, "requests with $orderby: " + ordered);
                assertTrue(top >= 30, "requests with $top: " + top);
                assertTrue(counted >= 20, "requests of $count: " + counted);
            } else {
                assertEquals(0, filtered);
                assertEquals(0, ordered);
                assertEquals(0, top);
                assertEquals(0, counted);
            }
        }
    }

    /**
     * Returns the values of {@code i}, {@code l}, {@code d}, {@code v},
     * {@code ts} and {@code u} of a row of the table {@code random_v2} in
     * DuckDB as SQL literals.
     */
    private static String[] literals(DuckDbReference duckdb, long id) {
        String sql = "SELECT coalesce(CAST(i AS VARCHAR), 'NULL'), coalesce(CAST(l AS VARCHAR), 'NULL'),"
                + " coalesce(CAST(d AS VARCHAR), 'NULL'),"
                + " coalesce(chr(39) || replace(v, chr(39), chr(39) || chr(39)) || chr(39), 'NULL'),"
                + " coalesce('TIMESTAMP ' || chr(39) || CAST(ts AS VARCHAR) || chr(39), 'NULL'),"
                + " coalesce('CAST(' || chr(39) || CAST(u AS VARCHAR) || chr(39) || ' AS UUID)', 'NULL')"
                + " FROM random_v2 WHERE id = " + id;
        try (Statement s = duckdb.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6)};
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
