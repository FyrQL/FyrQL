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

package net.fyrql.connector;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of the negotiation, as {@link Capabilities#plan} applies them, and the paths of a request. */
class CapabilitiesTest {

    private static final Schema TABLE = Schema.of(new Schema.Field("a", DataType.INTEGER),
            new Schema.Field("s", DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
                    new DataType.Struct.Field("y", DataType.VARCHAR))),
            new Schema.Field("b", DataType.BIGINT));
    private static final TableRef T = TableRef.of("src", "t");

    /** Over the output (a, b): accepted by the predicate, which takes only the first output column. */
    private static final Expression ON_A = gt(col(0, DataType.INTEGER), lit(1));
    private static final Expression ON_B = gt(col(1, DataType.BIGINT), lit(5L));
    private static final java.util.function.Predicate<Expression> FIRST_COLUMN_ONLY = e -> Expressions.allMatch(e,
            n -> !(n instanceof Expression.Column c) || c.index() == 0);

    private static ScanRequest request(List<Expression> filters, OptionalLong limit, List<SortKey> order,
            Optional<PlanNode.Aggregation> aggregation) {
        return new ScanRequest(ColumnPath.whole(List.of(0, 2)), filters, limit, order, aggregation);
    }

    @Test
    void pathsNameColumnsAndFieldsInsideThem() {
        assertEquals(TABLE.fields().get(2), ColumnPath.whole(2).field(TABLE));
        assertEquals(new Schema.Field("s.y", DataType.VARCHAR, true), new ColumnPath(1, List.of("y")).field(TABLE));
        assertThrows(IllegalArgumentException.class, () -> new ColumnPath(1, List.of("z")).field(TABLE));
        assertThrows(IllegalArgumentException.class, () -> new ColumnPath(0, List.of("x")).field(TABLE));
        assertThrows(IllegalArgumentException.class, () -> ColumnPath.whole(3).field(TABLE));
        assertEquals(List.of(DataType.BIGINT, DataType.INTEGER),
                ScanRequest.columns(List.of(2, 0)).output(TABLE).types());
        assertEquals(List.of(2, 0), ScanRequest.columns(List.of(2, 0)).wholeColumns());
        ScanRequest nested = new ScanRequest(List.of(new ColumnPath(1, List.of("x"))), List.of(),
                OptionalLong.empty(), List.of(), Optional.empty());
        assertEquals("s.x INTEGER", nested.output(TABLE).fields().get(0).toString());
        assertThrows(IllegalArgumentException.class, nested::wholeColumns);
        assertEquals("#1.x", nested.columns().get(0).toString());
    }

    @Test
    void filtersAreAcceptedOneByOneAndTheLimitOnlyWithAllOfThem() {
        ScanPlan plan = Capabilities.filtersAndLimit(FIRST_COLUMN_ONLY).plan(T, TABLE,
                request(List.of(ON_A, ON_B), OptionalLong.of(10), List.of(), Optional.empty()), "state");
        assertEquals(List.of(ON_A), plan.acceptedFilters());
        assertFalse(plan.limitAccepted(), "a filter stays with the engine");
        assertEquals("state", plan.state());
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT), plan.output().types());

        plan = Capabilities.filtersAndLimit(FIRST_COLUMN_ONLY).plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.of(10), List.of(), Optional.empty()), null);
        assertEquals(List.of(ON_A), plan.acceptedFilters());
        assertTrue(plan.limitAccepted());

        plan = Capabilities.filtersOnly(FIRST_COLUMN_ONLY).plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.of(10), List.of(), Optional.empty()), null);
        assertFalse(plan.limitAccepted(), "no limit without the capability");

        plan = Capabilities.NONE.plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.of(10), List.of(), Optional.empty()), null);
        assertEquals(List.of(), plan.acceptedFilters());
        assertFalse(plan.limitAccepted());
    }

    @Test
    void theOrderGatesTheLimit() {
        List<SortKey> order = List.of(SortKey.asc(1));
        ScanPlan plan = new Capabilities(e -> true, true, o -> true, a -> false).plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.of(3), order, Optional.empty()), null);
        assertTrue(plan.orderAccepted());
        assertTrue(plan.limitAccepted());

        plan = new Capabilities(e -> true, true, o -> false, a -> false).plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.of(3), order, Optional.empty()), null);
        assertFalse(plan.orderAccepted());
        assertFalse(plan.limitAccepted(), "the engine sorts, so it must limit");

        plan = new Capabilities(e -> true, true, o -> true, a -> false).plan(T, TABLE,
                request(List.of(), OptionalLong.empty(), List.of(), Optional.empty()), null);
        assertFalse(plan.orderAccepted(), "nothing to accept");
        assertFalse(plan.limitAccepted());
    }

    @Test
    void anAggregationChangesTheOutput() {
        PlanNode.Aggregation aggregation = new PlanNode.Aggregation(List.of(0),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n", null, List.of())));
        Capabilities all = new Capabilities(e -> true, true, o -> false, a -> true);
        ScanPlan plan = all.plan(T, TABLE,
                request(List.of(ON_A), OptionalLong.empty(), List.of(), Optional.of(aggregation)), null);
        assertTrue(plan.aggregationAccepted());
        assertEquals("[a INTEGER, n BIGINT NOT NULL]", plan.output().toString());

        plan = all.plan(T, TABLE, request(List.of(ON_A), OptionalLong.of(5), List.of(), Optional.of(aggregation)),
                null);
        assertFalse(plan.aggregationAccepted(), "not with a limit");
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT), plan.output().types());

        plan = new Capabilities(FIRST_COLUMN_ONLY, true, o -> false, a -> true).plan(T, TABLE,
                request(List.of(ON_B), OptionalLong.empty(), List.of(), Optional.of(aggregation)), null);
        assertFalse(plan.aggregationAccepted(), "not with a filter left to the engine");
    }

    @Test
    void fieldsAreReadOnlyByAConnectorThatReadsThem() {
        // Over the output (s.y, a, s.x).
        Expression onA = gt(col(1, DataType.INTEGER), lit(1));
        ScanRequest fields = new ScanRequest(List.of(new ColumnPath(1, List.of("y")), ColumnPath.whole(0),
                new ColumnPath(1, List.of("x"))), List.of(onA), OptionalLong.of(3), List.of(), Optional.empty());
        assertTrue(fields.readsFields());
        assertEquals(ScanRequest.columns(List.of(1, 0)), fields.withWholeColumns());

        ScanPlan plan = Capabilities.filtersAndLimit(e -> true).plan(T, TABLE, fields, null);
        assertEquals(ColumnPath.whole(List.of(1, 0)), plan.columns(), "the whole columns, each once");
        assertEquals(List.of(TABLE.type(1), DataType.INTEGER), plan.output().types());
        assertTrue(plan.acceptedFilters().isEmpty(), "the filter is over the paths, not the columns");
        assertFalse(plan.limitAccepted());
        assertEquals(plan.columns(), ScanPlan.columnsOnly(T, TABLE, fields, null).columns());

        plan = Capabilities.filtersAndLimit(e -> true).withFields(true).plan(T, TABLE, fields, null);
        assertEquals(fields.columns(), plan.columns());
        assertEquals(List.of(DataType.VARCHAR, DataType.INTEGER, DataType.INTEGER), plan.output().types());
        assertEquals(List.of(onA), plan.acceptedFilters());
        assertTrue(plan.limitAccepted());
    }
}
