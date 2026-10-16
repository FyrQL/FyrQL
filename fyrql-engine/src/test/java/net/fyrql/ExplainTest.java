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

package net.fyrql;

import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The lines that {@link FyrQL#explain} prints for the kinds of nodes. */
class ExplainTest {

    @Test
    void anUnnestNamesItsListAndItsColumn() {
        try (FyrQL engine = FyrQL.builder().build()) {
            String sql = "SELECT id, x FROM (VALUES (1, [10, 20])) v(id, l), unnest(v.l) AS u(x)";
            assertEquals("""
                    Project #0 AS id, #2 AS x
                      Unnest #1 AS x
                        Values 1 row (col0 INTEGER, col1 INTEGER[])
                    """, engine.explain(sql));
            assertEquals(List.of(List.of(1, 10), List.of(1, 20)),
                    engine.rows(sql).stream().map(List::of).toList());
        }
    }

    @Test
    void constantsAreFoldedAndTheConditionAllBranchesShareBecomesTheJoinKey() {
        try (FyrQL engine = FyrQL.builder().build()) {
            String sql = """
                    SELECT count(*) FROM (VALUES (1, 'a'), (2, 'b')) p(k, brand), (VALUES (1, 3), (2, 25)) l(k, q)
                    WHERE (p.k = l.k AND p.brand = 'a' AND l.q <= 1 + 10)
                       OR (l.k = 7 - 5 AND p.k = l.k AND p.brand = 'b' AND l.q >= 2 * 10)""";
            assertEquals("""
                    Project #0 AS "count_star()"
                      Aggregate count(*) AS "count_star()"
                        Filter (((#1 = 'a') AND (#3 <= CAST(11 AS INTEGER))) OR (((#2 = CAST(2 AS INTEGER)) \
                    AND (#1 = 'b')) AND (#3 >= CAST(20 AS INTEGER))))
                          Join INNER ON l#0 = r#0
                            Values 2 rows (col0 INTEGER, col1 VARCHAR)
                            Values 2 rows (col0 INTEGER, col1 INTEGER)
                    """, engine.explain(sql));
            assertEquals(List.of(List.of(2L)), engine.rows(sql).stream().map(List::of).toList());
        }
    }

    @Test
    void aJoinShowsItsConditionAfterItsKeys() {
        try (FyrQL engine = FyrQL.builder().build()) {
            Schema p = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("brand", DataType.VARCHAR));
            Schema l = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("q", DataType.INTEGER));
            PlanNode plan = new PlanNode.Join(
                    new PlanNode.Values(p, List.of(new Object[] {1, "a"}, new Object[] {2, "b"})),
                    new PlanNode.Values(l, List.of(new Object[] {1, 0}, new Object[] {2, 25})),
                    List.of(0), List.of(0), PlanNode.JoinType.SEMI,
                    Expressions.lt(Expressions.col(0, DataType.INTEGER), Expressions.col(3, DataType.INTEGER)));
            assertEquals("""
                    Join SEMI ON l#0 = r#0 AND (l#0 < r#1)
                      Values 2 rows (k INTEGER, brand VARCHAR)
                      Values 2 rows (k INTEGER, q INTEGER)
                    """, engine.explain(plan));
            assertEquals(List.of(List.of(2, "b")), engine.rows(plan).stream().map(List::of).toList());
        }
    }
}
