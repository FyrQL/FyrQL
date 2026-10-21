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

import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.SpillDirectory;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.rem;

/**
 * A sort, an aggregation of about a million groups and a window over the
 * rows of {@link CodegenBenchmark}, in the splits of
 * {@link ParallelBenchmark}, interpreted: without a limit of memory, and
 * with a budget of a quarter of the bytes of the rows, so that they spill
 * to the temporary directory of the JVM.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar SpillBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
public class SpillBenchmark {

    @Param({"1", "12"})
    public int threads;

    /** The budget: {@code all} for no limit, {@code quarter} for a quarter of the bytes of the rows. */
    @Param({"all", "quarter"})
    public String budget;

    private Executor executor;
    private Path directory;
    private SpillDirectory spill;
    private PlanNode sort;
    private PlanNode groupByMany;
    private PlanNode window;

    @Setup
    public void setUp() throws IOException {
        List<Batch> rows = CodegenBenchmark.lineitem();
        long bytes = 0;
        for (Batch b : rows) {
            bytes += Batches.estimateBytes(b);
        }
        List<List<Batch>> splits = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += ParallelBenchmark.BATCHES_PER_SPLIT) {
            splits.add(rows.subList(from, Math.min(rows.size(), from + ParallelBenchmark.BATCHES_PER_SPLIT)));
        }
        MemoryBudget limit = budget.equals("all") ? MemoryBudget.unlimited() : new MemoryBudget(bytes / 4);
        directory = Files.createTempDirectory("fyrql-bench-");
        spill = new SpillDirectory(directory);
        executor = new Executor(new ParallelBenchmark.InSplits(Map.of(TableRef.of("mem", "lineitem"), splits)), limit,
                CodegenBenchmark.BATCH, Codegen.off(), threads, true, spill);
        PlanNode.Scan lineitem = PlanNode.Scan.all(TableRef.of("mem", "lineitem"), CodegenBenchmark.LINEITEM);
        sort = new PlanNode.Sort(lineitem, List.of(SortKey.desc(2)));
        Expression key = add(mul(col(CodegenBenchmark.LINEITEM, 0), lit(10L)),
                rem(col(CodegenBenchmark.LINEITEM, 1), lit(10)));
        groupByMany = new PlanNode.Aggregate(new PlanNode.Project(lineitem, List.of(new NamedExpression("key", key),
                new NamedExpression("quantity", col(CodegenBenchmark.LINEITEM, 1)))), List.of(0), List.of(
                        new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                        new AggregateCall(AggregateFunction.SUM, 1, false, "quantity")));
        window = new PlanNode.Window(lineitem, List.of(0), List.of(SortKey.asc(5)), List.of(
                WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn")));
    }

    @TearDown
    public void tearDown() throws IOException {
        System.out.println();
        System.out.println("spilled: " + spill.statistics());
        spill.close();
        Files.deleteIfExists(directory);
    }

    /** All four million rows ordered by the price. */
    @Benchmark
    public long sort(Blackhole bh) {
        return run(sort, bh);
    }

    /** {@code count(*)} and {@code sum} in about 980,000 groups of a {@code BIGINT} key. */
    @Benchmark
    public long groupByMany(Blackhole bh) {
        return run(groupByMany, bh);
    }

    /** The number of each row in its order among the rows of its key, about 100,000 partitions. */
    @Benchmark
    public long window(Blackhole bh) {
        return run(window, bh);
    }

    private long run(PlanNode plan, Blackhole bh) {
        long rows = 0;
        try (Operator op = executor.open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                rows += b.rowCount();
                bh.consume(b);
            }
        }
        return rows;
    }
}
