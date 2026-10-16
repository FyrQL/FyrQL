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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlRendererTest {

    private final SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE,
            t -> t.source() + "_" + t.schema() + "_" + t.table());

    /** Renders a plan and checks that FyrQL's own parser reads the text, as the round trip tests need. */
    private String render(PlanNode plan) {
        String sql = renderer.render(plan);
        SqlParser.parse(sql);
        return sql;
    }

    @Test
    void joinConditionsGoIntoTheOnClause() {
        Schema orders = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("amount", DataType.decimal(10, 2)));
        Schema customers = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("name", DataType.VARCHAR));
        PlanNode.Scan o = PlanNode.Scan.all(TableRef.of("mem", "orders"), orders);
        PlanNode.Scan c = PlanNode.Scan.all(TableRef.of("mem", "customers"), customers);
        Schema pair = orders.concat(customers);
        PlanNode left = new PlanNode.Join(o, c, List.of(0), List.of(0), JoinType.LEFT,
                Expressions.and(gt(col(pair, 1), lit(new BigDecimal("1.0"))), Expressions.ne(col(pair, 3), lit("x"))));
        assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1, t1.c0 AS c2, t1.c1 AS c3 FROM ("
                + "SELECT \"id\" AS c0, \"amount\" AS c1 FROM mem_main_orders) t0 LEFT JOIN ("
                + "SELECT \"id\" AS c0, \"name\" AS c1 FROM mem_main_customers) t1 ON t0.c0 = t1.c0 AND "
                + "((t0.c1 > CAST('1.0' AS DECIMAL(2,1))) AND (t1.c1 <> 'x'))", render(left));
        // A semi join needs no keys; FyrQL does not read the DuckDB spelling.
        PlanNode semi = new PlanNode.Join(o, c, List.of(), List.of(), JoinType.SEMI,
                Expressions.lt(col(pair, 0), col(pair, 2)));
        assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1 FROM (SELECT \"id\" AS c0, \"amount\" AS c1 FROM "
                + "mem_main_orders) t0 SEMI JOIN (SELECT \"id\" AS c0, \"name\" AS c1 FROM mem_main_customers) t1 "
                + "ON (t0.c0 < t1.c0)", renderer.render(semi));
        for (JoinType type : List.of(JoinType.MARK, JoinType.SINGLE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> renderer.render(new PlanNode.Join(o, c, List.of(0), List.of(0), type)), type.name());
        }
    }

    @Test
    void literals() {
        Dialect d = DuckDbDialect.INSTANCE;
        assertEquals("CAST(1 AS TINYINT)", d.literal((byte) 1, DataType.TINYINT));
        assertEquals("'it''s'", d.literal("it's", DataType.VARCHAR));
        assertEquals("CAST(NULL AS INTEGER)", d.literal(null, DataType.INTEGER));
        assertEquals("CAST('1.50' AS DECIMAL(10,2))", d.literal(new BigDecimal("1.50"), DataType.decimal(10, 2)));
        assertEquals("DATE '1970-01-02'", d.literal(1, DataType.DATE));
        assertEquals("CAST('nan' AS DOUBLE)", d.literal(Double.NaN, DataType.DOUBLE));
        assertEquals("CAST([CAST(1 AS INTEGER), CAST(NULL AS INTEGER)] AS INTEGER[])",
                d.literal(java.util.Arrays.asList(1, null), DataType.listOf(DataType.INTEGER)));
        assertEquals("CAST(map(['k'], [CAST(1 AS INTEGER)]) AS MAP(VARCHAR, INTEGER))",
                d.literal(Map.of("k", 1), DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));
        assertEquals("CAST({'a': CAST(1 AS INTEGER)} AS STRUCT(a INTEGER))",
                d.literal(List.of(1), DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER))));
    }

    @Test
    void plans() {
        Schema orders = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("amount", DataType.decimal(10, 2)));
        Schema customers = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("name", DataType.VARCHAR));
        PlanNode.Scan o = PlanNode.Scan.all(TableRef.of("mem", "orders"), orders);
        PlanNode.Scan c = new PlanNode.Scan(TableRef.of("csv", "customers"), customers, List.of(1, 0));
        PlanNode plan = new PlanNode.Limit(new PlanNode.Sort(new PlanNode.Aggregate(
                new PlanNode.Join(new PlanNode.Filter(o, gt(col(orders, 1), lit(new BigDecimal("1.0")))), c,
                        List.of(0), List.of(1), JoinType.LEFT),
                List.of(2), List.of(new AggregateCall(AggregateFunction.SUM, 1, false, "total"),
                        new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"))),
                List.of(SortKey.desc(1))), 10, 0);
        // A limit over a sort shares its statement, so that the order is that of the limited rows.
        assertEquals("SELECT * FROM (SELECT t1.c2 AS c0, sum(t1.c1) AS c1, count(*) AS c2 FROM ("
                + "SELECT t2.c0 AS c0, t2.c1 AS c1, t3.c0 AS c2, t3.c1 AS c3 FROM ("
                + "SELECT * FROM (SELECT \"id\" AS c0, \"amount\" AS c1 FROM mem_main_orders) t4 "
                + "WHERE (t4.c1 > CAST('1.0' AS DECIMAL(2,1)))) t2 LEFT JOIN ("
                + "SELECT \"name\" AS c0, \"id\" AS c1 FROM csv_main_customers) t3 ON t2.c0 = t3.c1) t1 "
                + "GROUP BY t1.c2) t0 ORDER BY t0.c1 DESC NULLS LAST LIMIT 10", render(plan));
        // A sum of integers has the type FyrQL gives it in every database.
        assertEquals("SELECT CAST(sum(t0.c0) AS HUGEINT) AS c0 FROM (SELECT \"id\" AS c0 FROM csv_main_customers) t0",
                render(new PlanNode.Aggregate(new PlanNode.Scan(TableRef.of("csv", "customers"), customers, List.of(0)),
                        List.of(), List.of(new AggregateCall(AggregateFunction.SUM, 0, false, "s")))));
        PlanNode values = new PlanNode.Values(customers, List.of(new Object[] {1, "x"}, new Object[] {null, null}));
        assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1 FROM (VALUES (CAST(1 AS INTEGER), 'x'), "
                + "(CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR))) t0(c0, c1)", render(values));
        assertEquals("SELECT CAST(NULL AS INTEGER) AS c0, CAST(NULL AS VARCHAR) AS c1 WHERE false",
                render(new PlanNode.Values(customers, List.of())));
        // The operands of a set operation go without parentheses, which JSqlParser fails on for a long first one.
        assertEquals("SELECT * FROM (SELECT \"id\" AS c0, \"name\" AS c1 FROM csv_main_customers EXCEPT ALL "
                + "SELECT t1.c0 AS c0, t1.c1 AS c1 FROM (VALUES (CAST(1 AS INTEGER), 'x'), "
                + "(CAST(NULL AS INTEGER), CAST(NULL AS VARCHAR))) t1(c0, c1)) t0",
                render(new PlanNode.SetOperation(PlanNode.Scan.all(TableRef.of("csv", "customers"), customers),
                        values, PlanNode.SetOperationKind.EXCEPT, true)));
        // A sorted or limited operand goes into a subquery.
        assertEquals("SELECT * FROM (SELECT * FROM (SELECT * FROM (SELECT \"id\" AS c0, \"name\" AS c1 FROM "
                + "csv_main_customers) t1 LIMIT 1) t2 UNION ALL SELECT * FROM (SELECT * FROM (SELECT \"id\" AS c0, "
                + "\"name\" AS c1 FROM csv_main_customers) t3 LIMIT 2) t4) t0",
                render(new PlanNode.Union(List.of(
                        new PlanNode.Limit(PlanNode.Scan.all(TableRef.of("csv", "customers"), customers), 1, 0),
                        new PlanNode.Limit(PlanNode.Scan.all(TableRef.of("csv", "customers"), customers), 2, 0)))));
        assertEquals("(TRY_CAST(t.c1 AS INTEGER) IS DISTINCT FROM 1)",
                renderer.expression(Expressions.isDistinct(Expressions.tryCast(col(customers, 1), DataType.INTEGER),
                        lit(1)), "t").replace("CAST(1 AS INTEGER)", "1"));
        assertEquals("(t.c1 ILIKE 'a%')", renderer.expression(Expressions.ilike(col(customers, 1), lit("a%")), "t"));
        Schema tags = Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("tags", DataType.listOf(DataType.VARCHAR)));
        assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1, unnest(t0.c1) AS c2 FROM (SELECT \"id\" AS c0, \"tags\" AS c1 "
                + "FROM mem_main_tags) t0", render(new PlanNode.Unnest(PlanNode.Scan.all(TableRef.of("mem", "tags"),
                tags), col(tags, 1), "tag")));
        assertEquals("CREATE TABLE x (\"id\" INTEGER, \"amount\" DECIMAL(10,2))", renderer.createTable("x", orders));
        assertEquals("INSERT INTO x VALUES (CAST(1 AS INTEGER), CAST('2.50' AS DECIMAL(10,2))), (CAST(NULL AS INTEGER), "
                + "CAST(NULL AS DECIMAL(10,2)))", renderer.insert("x", orders, List.of(new Object[] {1, new BigDecimal("2.50")},
                new Object[] {null, null})));
    }
}
