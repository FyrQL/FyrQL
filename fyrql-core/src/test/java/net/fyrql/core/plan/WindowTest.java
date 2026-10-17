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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.IntervalUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.Window;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A window: its output, its frames and the checks of its calls. */
class WindowTest {

    private static final Schema INPUT = Schema.of(new Schema.Field("p", DataType.INTEGER),
            new Schema.Field("o", DataType.BIGINT), new Schema.Field("v", DataType.VARCHAR),
            new Schema.Field("d", DataType.DATE), new Schema.Field("ts", DataType.TIMESTAMP),
            new Schema.Field("x", DataType.DOUBLE), new Schema.Field("f", DataType.BOOLEAN),
            new Schema.Field("n", DataType.INTEGER), new Schema.Field("m", DataType.decimal(10, 2)));

    private static final PlanNode VALUES = new PlanNode.Values(INPUT, List.of());

    private static Expression.Literal bigint(long value) {
        return new Expression.Literal(DataType.BIGINT, value);
    }

    private static Frame rows(long preceding) {
        return new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.PRECEDING, bigint(preceding)),
                FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS);
    }

    private static Frame range(FrameBound start) {
        return new Frame(FrameUnit.RANGE, start, FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS);
    }

    private static WindowCall call(WindowFunction function, Integer... arguments) {
        return WindowCall.of(function, List.of(arguments), function.sqlName());
    }

    private static WindowCall aggregate(AggregateFunction function, int column, int filter) {
        return new WindowCall(WindowFunction.AGGREGATE, new AggregateCall(function, column, false, function.sqlName()),
                List.of(), false, filter, Frame.DEFAULT, function.sqlName());
    }

    private static Window window(List<SortKey> order, WindowCall... calls) {
        return new Window(VALUES, List.of(0), order, List.of(calls));
    }

    @Test
    void aWindowAddsOneColumnPerCallAfterThoseOfItsInput() {
        WindowCall lag = new WindowCall(WindowFunction.LAG, null, List.of(2, 7, 2), true, -1, Frame.DEFAULT, "lag");
        WindowCall sum = new WindowCall(WindowFunction.AGGREGATE,
                new AggregateCall(AggregateFunction.SUM, 1, false, "s"), List.of(), false, 6, rows(2), "s");
        Window w = window(List.of(SortKey.asc(1)), call(WindowFunction.ROW_NUMBER), lag, sum,
                call(WindowFunction.PERCENT_RANK), call(WindowFunction.NTILE, 7),
                aggregate(AggregateFunction.COUNT_STAR, -1, -1), call(WindowFunction.NTH_VALUE, 8, 1));
        assertEquals("[p INTEGER, o BIGINT, v VARCHAR, d DATE, ts TIMESTAMP, x DOUBLE, f BOOLEAN, n INTEGER, "
                + "m DECIMAL(10,2), row_number BIGINT NOT NULL, lag VARCHAR, s HUGEINT, percent_rank DOUBLE NOT NULL, "
                + "ntile BIGINT, count BIGINT NOT NULL, nth_value DECIMAL(10,2)]", w.schema().toString());
        assertEquals(w, Plans.withChildren(w, List.of(VALUES)));
        assertTrue(Plans.print(w).startsWith("Window partition [0] order [SortKey[column=1"), Plans.print(w));
        // Without partition and order, a window is one partition of peers.
        assertEquals(10, new Window(VALUES, List.of(), List.of(), List.of(call(WindowFunction.RANK))).schema().size());
    }

    @Test
    void framesAreWrittenAsSql() {
        assertEquals("RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW", Frame.DEFAULT.sql(b -> "?"));
        Frame frame = new Frame(FrameUnit.GROUPS, FrameBound.of(BoundKind.PRECEDING, bigint(1)),
                FrameBound.of(BoundKind.FOLLOWING, bigint(2)), FrameExclusion.CURRENT_ROW);
        assertEquals("GROUPS BETWEEN 1 PRECEDING AND 2 FOLLOWING EXCLUDE CURRENT ROW",
                frame.sql(b -> b.offset().value().toString()));
        assertEquals("ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING EXCLUDE TIES", new Frame(FrameUnit.ROWS,
                FrameBound.of(BoundKind.CURRENT_ROW), FrameBound.of(BoundKind.UNBOUNDED_FOLLOWING),
                FrameExclusion.TIES).sql(b -> "?"));
        assertEquals(WindowFunction.ROW_NUMBER, WindowFunction.of("Row_Number"));
        assertEquals("cume_dist", WindowFunction.CUME_DIST.sqlName());
        assertThrows(IllegalArgumentException.class, () -> WindowFunction.of("aggregate"));
        assertThrows(IllegalArgumentException.class, () -> WindowFunction.of("sum"));
    }

    @Test
    void rangeOffsetsFitTheirOneOrderKey() {
        FrameBound days = new FrameBound(BoundKind.PRECEDING, bigint(3), IntervalUnit.DAY);
        window(List.of(SortKey.asc(3)), WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(days)));
        window(List.of(SortKey.desc(4)), WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(new FrameBound(BoundKind.PRECEDING, bigint(5), IntervalUnit.MICROSECOND))));
        window(List.of(SortKey.asc(5)), WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(FrameBound.of(BoundKind.PRECEDING, new Expression.Literal(DataType.DOUBLE, 1.5)))));
        window(List.of(SortKey.asc(8)), WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(FrameBound.of(BoundKind.PRECEDING, new Expression.Literal(DataType.decimal(2, 1),
                        new BigDecimal("1.5"))))));
        // Without an offset, a RANGE frame takes any order.
        window(List.of(SortKey.asc(2), SortKey.asc(1)), aggregate(AggregateFunction.COUNT_STAR, -1, -1));

        WindowCall overDays = WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(days));
        WindowCall overNumbers = WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                range(FrameBound.of(BoundKind.PRECEDING, bigint(1))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> window(List.of(SortKey.asc(1), SortKey.asc(3)), overNumbers));
        assertEquals("RANGE frames must have only one ORDER BY expression", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> window(List.of(), overNumbers));
        assertThrows(IllegalArgumentException.class, () -> window(List.of(SortKey.asc(3)), overNumbers));
        assertThrows(IllegalArgumentException.class, () -> window(List.of(SortKey.asc(2)), overNumbers));
        assertThrows(IllegalArgumentException.class, () -> window(List.of(SortKey.asc(1)), overDays));
    }

    @Test
    void wrongCallsFramesAndBoundsAreRefused() {
        List<Executable> wrong = List.of(
                () -> new Window(VALUES, List.of(), List.of(), List.of()),
                () -> new Window(VALUES, List.of(9), List.of(), List.of(call(WindowFunction.RANK))),
                () -> new Window(VALUES, List.of(), List.of(SortKey.asc(9)), List.of(call(WindowFunction.RANK))),
                () -> new WindowCall(WindowFunction.AGGREGATE, null, List.of(), false, -1, Frame.DEFAULT, "a"),
                () -> new WindowCall(WindowFunction.RANK, new AggregateCall(AggregateFunction.COUNT_STAR, -1, false,
                        "n"), List.of(), false, -1, Frame.DEFAULT, "r"),
                () -> call(WindowFunction.ROW_NUMBER, 1),
                () -> call(WindowFunction.LAG),
                () -> call(WindowFunction.LEAD, 1, 1, 1, 1),
                () -> call(WindowFunction.NTH_VALUE, 1),
                () -> new WindowCall(WindowFunction.RANK, null, List.of(), true, -1, Frame.DEFAULT, "r"),
                () -> new WindowCall(WindowFunction.AGGREGATE, new AggregateCall(AggregateFunction.MIN, 1, false, "m"),
                        List.of(), true, -1, Frame.DEFAULT, "m"),
                () -> new WindowCall(WindowFunction.LAG, null, List.of(1), false, 6, Frame.DEFAULT, "l"),
                () -> aggregate(AggregateFunction.SUM, 1, -2),
                () -> window(List.of(), aggregate(AggregateFunction.SUM, 1, 2)),
                () -> window(List.of(), aggregate(AggregateFunction.SUM, 2, -1)),
                () -> window(List.of(), call(WindowFunction.NTILE, 5)),
                () -> window(List.of(), call(WindowFunction.LAG, 2, 2)),
                () -> window(List.of(), call(WindowFunction.LAG, 2, 7, 1)),
                () -> window(List.of(), call(WindowFunction.NTH_VALUE, 2, 5)),
                () -> window(List.of(), call(WindowFunction.FIRST_VALUE, 9)),
                () -> new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.UNBOUNDED_FOLLOWING),
                        FrameBound.of(BoundKind.UNBOUNDED_FOLLOWING), FrameExclusion.NO_OTHERS),
                () -> new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.UNBOUNDED_PRECEDING),
                        FrameBound.of(BoundKind.UNBOUNDED_PRECEDING), FrameExclusion.NO_OTHERS),
                () -> new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.CURRENT_ROW),
                        FrameBound.of(BoundKind.PRECEDING, bigint(1)), FrameExclusion.NO_OTHERS),
                () -> new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.PRECEDING,
                        new Expression.Literal(DataType.INTEGER, 1)), FrameBound.of(BoundKind.CURRENT_ROW),
                        FrameExclusion.NO_OTHERS),
                () -> new Frame(FrameUnit.GROUPS, new FrameBound(BoundKind.PRECEDING, bigint(1), IntervalUnit.DAY),
                        FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS),
                () -> FrameBound.of(BoundKind.PRECEDING),
                () -> FrameBound.of(BoundKind.CURRENT_ROW, bigint(1)),
                () -> FrameBound.of(BoundKind.FOLLOWING, new Expression.Literal(DataType.BIGINT, null)),
                () -> new FrameBound(BoundKind.FOLLOWING, new Expression.Literal(DataType.INTEGER, 1),
                        IntervalUnit.DAY));
        for (int i = 0; i < wrong.size(); i++) {
            assertThrows(IllegalArgumentException.class, wrong.get(i), "case " + i);
        }
    }
}
