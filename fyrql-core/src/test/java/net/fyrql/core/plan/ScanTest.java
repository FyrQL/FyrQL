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

package net.fyrql.core.plan;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.Pushed;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A scan with parts taken over by its connector. */
class ScanTest {

    private static final Schema TABLE = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("v", DataType.VARCHAR), new Schema.Field("n", DataType.DOUBLE));
    private static final TableRef T = TableRef.of("src", "t");

    @Test
    void pushedPartsShapeTheSchemaAndTheLogicalPlan() {
        // The scan reads (n, k); the pushed parts refer to these positions.
        Expression filter = gt(col(1, DataType.INTEGER), lit(1));
        Pushed pushed = new Pushed(List.of(filter), OptionalLong.of(5), List.of(SortKey.asc(0)), Optional.empty());
        Scan scan = new Scan(T, TABLE, List.of(2, 0), pushed);
        assertEquals(List.of(DataType.DOUBLE, DataType.INTEGER), scan.schema().types());
        assertFalse(pushed.isEmpty());
        assertTrue(Pushed.NONE.isEmpty());

        PlanNode logical = scan.logical();
        PlanNode.Limit limit = assertInstanceOf(PlanNode.Limit.class, logical);
        assertEquals(5, limit.count());
        PlanNode.Sort sort = assertInstanceOf(PlanNode.Sort.class, limit.input());
        PlanNode.Filter f = assertInstanceOf(PlanNode.Filter.class, sort.input());
        assertEquals(filter, f.condition());
        Scan plain = assertInstanceOf(Scan.class, f.input());
        assertEquals(new Scan(T, TABLE, List.of(2, 0)), plain);
        assertEquals(Pushed.NONE, plain.pushed());
        assertEquals(scan.schema(), logical.schema());
        assertTrue(Plans.print(scan).contains("pushed"));
    }

    @Test
    void anAggregationTakenOverMakesTheScanDeliverGroups() {
        PlanNode.Aggregation aggregation = new PlanNode.Aggregation(List.of(1),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n", null, List.of())));
        Scan scan = new Scan(T, TABLE, List.of(2, 0),
                new Pushed(List.of(), OptionalLong.empty(), List.of(), Optional.of(aggregation)));
        assertEquals("[k INTEGER, n BIGINT NOT NULL]", scan.schema().toString());
        assertEquals(List.of(DataType.DOUBLE, DataType.INTEGER), scan.columnSchema().types());
        PlanNode.Aggregate a = assertInstanceOf(PlanNode.Aggregate.class, scan.logical());
        assertEquals(scan.schema(), a.schema());
    }

    @Test
    void pushedPartsAreCheckedAgainstTheColumnsRead() {
        Expression beyond = gt(col(2, DataType.DOUBLE), lit(1.0));
        assertThrows(IllegalArgumentException.class, () -> new Scan(T, TABLE, List.of(2, 0),
                new Pushed(List.of(beyond), OptionalLong.empty(), List.of(), Optional.empty())));
        Expression notBoolean = col(0, DataType.DOUBLE);
        assertThrows(IllegalArgumentException.class, () -> new Scan(T, TABLE, List.of(2, 0),
                new Pushed(List.of(notBoolean), OptionalLong.empty(), List.of(), Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> new Scan(T, TABLE, List.of(2, 0),
                new Pushed(List.of(), OptionalLong.empty(), List.of(SortKey.asc(2)), Optional.empty())));
        assertThrows(IllegalStateException.class, Pushed.NONE::condition);
    }

    @Test
    void aScanOfFieldsReadsThemAsColumnsAndTakesThemOutInItsLogicalPlan() {
        Schema table = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("s", DataType.struct(
                new DataType.Struct.Field("a", DataType.VARCHAR), new DataType.Struct.Field("b", DataType.struct(
                        new DataType.Struct.Field("c", DataType.DOUBLE))))));
        Expression filter = gt(col(2, DataType.DOUBLE), lit(1.0));
        Scan scan = new Scan(T, table, List.of(1, 0, 1), List.of(List.of("a"), List.of(), List.of("b", "c")),
                new Pushed(List.of(filter), OptionalLong.empty(), List.of(), Optional.empty()));
        assertTrue(scan.readsFields());
        assertFalse(new Scan(T, table, List.of(1)).readsFields());
        assertEquals("[s.a VARCHAR, k INTEGER, s.b.c DOUBLE]", scan.schema().toString());

        PlanNode.Filter f = assertInstanceOf(PlanNode.Filter.class, scan.logical());
        assertEquals(filter, f.condition());
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, f.input());
        assertEquals(new Scan(T, table, List.of(1, 0)), p.input());
        Expression s = col(0, table.type(1));
        assertEquals(List.of(new PlanNode.NamedExpression("s.a", Expressions.structField(s, List.of("a"))),
                new PlanNode.NamedExpression("k", col(1, DataType.INTEGER)),
                new PlanNode.NamedExpression("s.b.c", Expressions.structField(s, List.of("b", "c")))), p.columns());
        assertEquals(new Expressions.FieldAccess(s, List.of("b", "c")),
                Expressions.fieldAccess(Expressions.structField(s, List.of("b", "c"))));
        assertTrue(Plans.print(scan).contains("fields [[a], [], [b, c]]"), Plans.print(scan));

        assertThrows(IllegalArgumentException.class, () -> new Scan(T, table, List.of(1), List.of(List.of("x")),
                Pushed.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Scan(T, table, List.of(0), List.of(List.of("a")),
                Pushed.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Scan(T, table, List.of(0, 1), List.of(List.of()),
                Pushed.NONE));
    }

    @Test
    void conjunctionAndAllMatch() {
        Expression a = gt(col(0, DataType.INTEGER), lit(1));
        Expression b = lt(col(0, DataType.INTEGER), lit(9));
        assertEquals(a, Expressions.conjunction(List.of(a)));
        assertEquals(new Expression.And(a, b), Expressions.conjunction(List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () -> Expressions.conjunction(List.of()));
        assertTrue(Expressions.allMatch(new Expression.And(a, b), e -> !(e instanceof Expression.Call)));
        assertFalse(Expressions.allMatch(new Expression.And(a, b), e -> !(e instanceof Expression.Literal)));
    }
}
