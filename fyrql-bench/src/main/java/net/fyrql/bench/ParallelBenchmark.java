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

package net.fyrql.bench;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.Splits;
import net.fyrql.exec.TableSource;
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

/**
 * The operators of the executor on one and on several threads, over the
 * rows of {@link CodegenBenchmark} in splits of 32 batches, about 65,000
 * rows each, as a Parquet file has them in row groups, interpreted. The
 * 100,000 customers that {@link #joinProbe} joins are one split, so that
 * one thread builds the hash table and all of them probe it.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar ParallelBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ParallelBenchmark {

    static final int BATCHES_PER_SPLIT = 32;

    @Param({"1", "4", "12"})
    public int threads;

    private Executor executor;
    private PlanNode scanFilter;
    private PlanNode groupByFew;
    private PlanNode groupByMany;
    private PlanNode joinProbe;
    private PlanNode sort;
    private PlanNode window;

    @Setup
    public void setUp() {
        List<Batch> rows = CodegenBenchmark.lineitem();
        List<List<Batch>> splits = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += BATCHES_PER_SPLIT) {
            splits.add(rows.subList(from, Math.min(rows.size(), from + BATCHES_PER_SPLIT)));
        }
        executor = new Executor(new InSplits(Map.of(TableRef.of("mem", "lineitem"), splits,
                TableRef.of("mem", "customer"), List.of(CodegenBenchmark.customers(1)))), MemoryBudget.unlimited(),
                CodegenBenchmark.BATCH, Codegen.off(), threads, true);
        PlanNode.Scan lineitem = PlanNode.Scan.all(TableRef.of("mem", "lineitem"), CodegenBenchmark.LINEITEM);
        PlanNode.Scan customer = PlanNode.Scan.all(TableRef.of("mem", "customer"), CodegenBenchmark.CUSTOMER);
        scanFilter = new PlanNode.Filter(lineitem, CodegenBenchmark.q6());
        groupByFew = new PlanNode.Aggregate(lineitem, List.of(6, 7), List.of(
                new AggregateCall(AggregateFunction.SUM, 1, false, "sum_qty"),
                new AggregateCall(AggregateFunction.SUM, 2, false, "sum_price"),
                new AggregateCall(AggregateFunction.AVG, 3, false, "avg_disc"),
                new AggregateCall(AggregateFunction.SUM, 4, false, "sum_tax"),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_order")));
        groupByMany = new PlanNode.Aggregate(lineitem, List.of(0), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 1, false, "quantity")));
        joinProbe = new PlanNode.Aggregate(new PlanNode.Join(lineitem, customer, List.of(0), List.of(0),
                JoinType.INNER), List.of(9), List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 1, false, "quantity")));
        sort = new PlanNode.Sort(lineitem, List.of(SortKey.desc(2)));
        Frame lastThree = new Frame(FrameUnit.ROWS, FrameBound.of(BoundKind.PRECEDING,
                new Expression.Literal(DataType.BIGINT, 2L)), FrameBound.of(BoundKind.CURRENT_ROW),
                FrameExclusion.NO_OTHERS);
        window = new PlanNode.Window(lineitem, List.of(0), List.of(SortKey.asc(5)), List.of(
                WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn"),
                WindowCall.of(new AggregateCall(AggregateFunction.SUM, 1, false, "quantity"), lastThree)));
    }

    /** The batches of the tables in splits. */
    private record InSplits(Map<TableRef, List<List<Batch>>> tables) implements TableSource {
        @Override
        public BatchReader open(PlanNode.Scan scan) {
            return Splits.sequential(splits(scan, QueryContext.NONE), QueryContext.NONE);
        }

        @Override
        public Splits splits(PlanNode.Scan scan, QueryContext context) {
            return Splits.of(tables.get(scan.table()), Batches::reader);
        }
    }

    /** The filter of TPC-H Q6, whose rows go to the caller in the order of the splits. */
    @Benchmark
    public long scanFilter(Blackhole bh) {
        return run(scanFilter, bh);
    }

    /**
     * The form of TPC-H Q1: sums of an integer, a decimal and a double, an
     * average of a decimal and {@code count(*)} in six groups of two
     * {@code VARCHAR} keys.
     */
    @Benchmark
    public long groupByFew(Blackhole bh) {
        return run(groupByFew, bh);
    }

    /** {@code count(*)} and {@code sum} in 100,000 groups of a {@code BIGINT} key. */
    @Benchmark
    public long groupByMany(Blackhole bh) {
        return run(groupByMany, bh);
    }

    /** A hash join with the 100,000 customers and the count and the units per nation. */
    @Benchmark
    public long joinProbe(Blackhole bh) {
        return run(joinProbe, bh);
    }

    /** All four million rows ordered by the price. */
    @Benchmark
    public long sort(Blackhole bh) {
        return run(sort, bh);
    }

    /**
     * The rows of each of the 100,000 keys numbered by their ship date, with
     * the sum of the quantities of each row and the two before it.
     */
    @Benchmark
    public long window(Blackhole bh) {
        return run(window, bh);
    }

    private long run(PlanNode plan, Blackhole bh) {
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
