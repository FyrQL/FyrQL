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

package net.fyrql.bench;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.lit;

/**
 * One window function at a time over the four million rows of
 * {@link CodegenBenchmark}, in the 100,000 partitions of their keys and
 * ordered by the ship date, interpreted. {@code sort} orders the rows as
 * the window does, without a function, so that the time of a function is
 * its difference to it. The aggregates take the frame of each row and the
 * two before it ({@code last3}) or all rows up to it ({@code running});
 * the other functions ignore the frame.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar WindowBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class WindowBenchmark {

    @Param({"1", "12"})
    public int threads;

    @Param({"sort", "rowNumber", "sumInteger", "sumDecimal", "sumDouble", "avgDecimal", "count", "min",
            "lagDefault", "ntile"})
    public String function;

    @Param({"last3", "running"})
    public String frame;

    private Executor executor;
    private PlanNode plan;

    @Setup
    public void setUp() {
        List<Batch> rows = CodegenBenchmark.lineitem();
        List<List<Batch>> splits = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += ParallelBenchmark.BATCHES_PER_SPLIT) {
            splits.add(rows.subList(from, Math.min(rows.size(), from + ParallelBenchmark.BATCHES_PER_SPLIT)));
        }
        executor = new Executor(new ParallelBenchmark.InSplits(Map.of(TableRef.of("mem", "lineitem"), splits)),
                MemoryBudget.unlimited(), CodegenBenchmark.BATCH, Codegen.off(), threads, true);
        Schema s = CodegenBenchmark.LINEITEM;
        // key, quantity, price, tax, shipdate, and the constants 1, 0 and 4.
        PlanNode input = new PlanNode.Project(PlanNode.Scan.all(TableRef.of("mem", "lineitem"), s), List.of(
                new NamedExpression("key", col(s, 0)), new NamedExpression("quantity", col(s, 1)),
                new NamedExpression("price", col(s, 2)), new NamedExpression("tax", col(s, 4)),
                new NamedExpression("shipdate", col(s, 5)), new NamedExpression("one", lit(1L)),
                new NamedExpression("zero", lit(0)), new NamedExpression("four", lit(4L))));
        List<SortKey> order = List.of(SortKey.asc(4));
        if (function.equals("sort")) {
            plan = new PlanNode.Sort(input, List.of(SortKey.asc(0), SortKey.asc(4)));
            return;
        }
        FrameBound start = frame.equals("last3")
                ? FrameBound.of(BoundKind.PRECEDING, new Expression.Literal(DataType.BIGINT, 2L))
                : FrameBound.of(BoundKind.UNBOUNDED_PRECEDING);
        Frame f = new Frame(FrameUnit.ROWS, start, FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS);
        WindowCall call = switch (function) {
            case "rowNumber" -> WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "f");
            case "sumInteger" -> aggregate(AggregateFunction.SUM, 1, f);
            case "sumDecimal" -> aggregate(AggregateFunction.SUM, 2, f);
            case "sumDouble" -> aggregate(AggregateFunction.SUM, 3, f);
            case "avgDecimal" -> aggregate(AggregateFunction.AVG, 2, f);
            case "count" -> aggregate(AggregateFunction.COUNT, 1, f);
            case "min" -> aggregate(AggregateFunction.MIN, 1, f);
            case "lagDefault" -> WindowCall.of(WindowFunction.LAG, List.of(1, 5, 6), "f");
            case "ntile" -> WindowCall.of(WindowFunction.NTILE, List.of(7), "f");
            default -> throw new IllegalArgumentException("function: " + function);
        };
        plan = new PlanNode.Window(input, List.of(0), order, List.of(call));
    }

    private static WindowCall aggregate(AggregateFunction function, int column, Frame frame) {
        return WindowCall.of(new AggregateCall(function, column, false, "f"), frame);
    }

    /** The function of {@link #function} over the rows, or the rows sorted for {@code sort}. */
    @Benchmark
    public long window(Blackhole bh) {
        long rows = 0;
        try (Operator op = executor.open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                bh.consume(b);
                rows += b.rowCount();
            }
        }
        return rows;
    }
}
