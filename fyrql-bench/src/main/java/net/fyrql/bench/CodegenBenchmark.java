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

import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.exec.spi.CompiledFilter;
import net.fyrql.exec.spi.CompiledProjection;
import net.fyrql.exec.spi.ExpressionCompiler;
import net.fyrql.exec.spi.KeyFunctions;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.date;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.iff;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.sub;

/**
 * The operators of the executor over batches in memory, interpreted
 * ({@code OFF}) and with compiled code from the first row on
 * ({@code ALWAYS}), where a chain with a join, such as the one of
 * {@link #join}, or with two nodes that compute runs as one pipeline. The
 * data follows {@code lineitem} of TPC-H: a key, a
 * quantity, a price and a discount as decimals, a tax as a double, a ship
 * date, a return flag and a line status, four million rows in batches of
 * 2048. The batches are built once, so that the benchmarks measure the
 * operators, not a connector; {@link #scan} is the control, which only
 * reads them. {@link #filterProject} runs a chain that {@code ALWAYS} fuses
 * into one pipeline, {@link #filterProjectOperators} the same chain as two
 * operators with compiled expressions; {@link #pipeline} and
 * {@link #pipelineOperators} do the same for a chain with a join.
 * <p>
 * Run with {@code java -jar fyrql-bench/target/benchmarks.jar}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms4g", "-Xmx4g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class CodegenBenchmark {

    static final int ROWS = 4_000_000;
    static final int KEYS = 100_000;
    static final int BATCH = 2048;

    private static final String[] FLAGS = {"A", "N", "R"};
    private static final String[] STATUSES = {"F", "O"};

    static final Schema LINEITEM = Schema.of(new Schema.Field("key", DataType.BIGINT),
            new Schema.Field("quantity", DataType.INTEGER), new Schema.Field("price", DataType.decimal(12, 2)),
            new Schema.Field("discount", DataType.decimal(4, 2)), new Schema.Field("tax", DataType.DOUBLE),
            new Schema.Field("shipdate", DataType.DATE), new Schema.Field("returnflag", DataType.VARCHAR),
            new Schema.Field("linestatus", DataType.VARCHAR));
    static final Schema CUSTOMER = Schema.of(new Schema.Field("key", DataType.BIGINT),
            new Schema.Field("nation", DataType.INTEGER));

    @Param({"OFF", "ALWAYS"})
    public CodegenMode mode;

    private Map<TableRef, List<Batch>> tables;
    private Executor executor;
    private Executor operators;
    private PlanNode scan;
    private PlanNode filter;
    private PlanNode project;
    private PlanNode groupBy;
    private PlanNode join;
    private PlanNode aggregateDecimals;
    private PlanNode leftJoin;
    private PlanNode pipeline;
    private PlanNode filterProject;

    @Setup
    public void setUp() {
        tables = Map.of(TableRef.of("mem", "lineitem"), lineitem(), TableRef.of("mem", "customer"), customers(1),
                TableRef.of("mem", "supplier"), customers(2));
        executor = new Executor(s -> Batches.reader(tables.get(s.table())), MemoryBudget.unlimited(), BATCH,
                mode == CodegenMode.OFF ? Codegen.off() : Codegen.of(mode, 0, Codegen.loadCompiler()));
        operators = new Executor(s -> Batches.reader(tables.get(s.table())), MemoryBudget.unlimited(), BATCH,
                mode == CodegenMode.OFF ? Codegen.off() : Codegen.of(mode, 0, new WithoutPipelines(
                        Codegen.loadCompiler())));
        PlanNode.Scan lineitem = PlanNode.Scan.all(TableRef.of("mem", "lineitem"), LINEITEM);
        scan = lineitem;
        filter = new PlanNode.Filter(lineitem, q6());
        project = new PlanNode.Project(lineitem, projection());
        groupBy = new PlanNode.Aggregate(lineitem, List.of(0), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 1, false, "quantity")));
        PlanNode.Scan customer = PlanNode.Scan.all(TableRef.of("mem", "customer"), CUSTOMER);
        join = new PlanNode.Join(lineitem, customer, List.of(0), List.of(0), JoinType.INNER);
        aggregateDecimals = new PlanNode.Aggregate(lineitem, List.of(6, 7), List.of(
                new AggregateCall(AggregateFunction.SUM, 1, false, "sum_qty"),
                new AggregateCall(AggregateFunction.SUM, 2, false, "sum_price"),
                new AggregateCall(AggregateFunction.SUM, 3, false, "sum_disc"),
                new AggregateCall(AggregateFunction.AVG, 1, false, "avg_qty"),
                new AggregateCall(AggregateFunction.AVG, 2, false, "avg_price"),
                new AggregateCall(AggregateFunction.AVG, 3, false, "avg_disc"),
                new AggregateCall(AggregateFunction.SUM, 4, false, "sum_tax"),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count_order")));
        leftJoin = new PlanNode.Join(lineitem, PlanNode.Scan.all(TableRef.of("mem", "supplier"), CUSTOMER),
                List.of(0), List.of(0), JoinType.LEFT);
        pipeline = new PlanNode.Aggregate(revenue(lineitem, customer), List.of(3),
                List.of(new AggregateCall(AggregateFunction.SUM, 1, false, "revenue")));
        filterProject = new PlanNode.Project(new PlanNode.Filter(lineitem, wide()), projection());
    }

    /**
     * The chain below the aggregate of {@link #pipeline}: a filter that keeps
     * about half of the rows, a projection of the key and a decimal revenue,
     * and a join with the customers.
     */
    static PlanNode.Join revenue(PlanNode lineitem, PlanNode customer) {
        PlanNode.Project revenue = new PlanNode.Project(new PlanNode.Filter(lineitem, wide()), List.of(
                new NamedExpression("key", col(LINEITEM, 0)),
                new NamedExpression("revenue", mul(col(LINEITEM, 2), sub(lit(new BigDecimal("1.00")),
                        col(LINEITEM, 3))))));
        return new PlanNode.Join(revenue, customer, List.of(0), List.of(0), JoinType.INNER);
    }

    /** A compiler that compiles expressions and keys, but no pipelines. */
    private record WithoutPipelines(ExpressionCompiler compiler) implements ExpressionCompiler {
        @Override
        public String name() {
            return compiler.name();
        }

        @Override
        public CompiledProjection projection(Expression expression, Schema input) {
            return compiler.projection(expression, input);
        }

        @Override
        public CompiledFilter filter(Expression condition, Schema input) {
            return compiler.filter(condition, input);
        }

        @Override
        public KeyFunctions keys(List<DataType> types) {
            return compiler.keys(types);
        }

        @Override
        public boolean supports(Expression expression) {
            return compiler.supports(expression);
        }

        @Override
        public boolean supportsKeys(List<DataType> types) {
            return compiler.supportsKeys(types);
        }

        @Override
        public CodegenStatistics statistics() {
            return compiler.statistics();
        }
    }

    /** A condition that keeps about half of the rows: shipped from 1994 on, fewer than 40 units. */
    static Expression wide() {
        int from = (int) LocalDate.of(1994, 1, 1).toEpochDay();
        return and(ge(col(LINEITEM, 5), date(from)), lt(col(LINEITEM, 1), lit(40)));
    }

    /** The condition of TPC-H Q6. */
    static Expression q6() {
        int from = (int) LocalDate.of(1994, 1, 1).toEpochDay();
        int to = (int) LocalDate.of(1995, 1, 1).toEpochDay();
        return and(and(and(ge(col(LINEITEM, 5), date(from)), lt(col(LINEITEM, 5), date(to))),
                between(col(LINEITEM, 3), lit(new BigDecimal("0.05")), lit(new BigDecimal("0.07")))),
                lt(col(LINEITEM, 1), lit(24)));
    }

    /** Decimal, integer and double arithmetic and a condition. */
    static List<NamedExpression> projection() {
        return List.of(
                new NamedExpression("revenue", mul(col(LINEITEM, 2), sub(lit(new BigDecimal("1.00")),
                        col(LINEITEM, 3)))),
                new NamedExpression("units", add(mul(col(LINEITEM, 1), lit(2)), lit(1))),
                new NamedExpression("charge", mul(col(LINEITEM, 4), lit(100.0))),
                new NamedExpression("bulk", iff(gt(col(LINEITEM, 1), lit(25)), col(LINEITEM, 1), lit(0))));
    }

    static List<Batch> lineitem() {
        SplittableRandom random = new SplittableRandom(20261014);
        int first = (int) LocalDate.of(1992, 1, 1).toEpochDay();
        List<Batch> batches = new ArrayList<>();
        for (int from = 0; from < ROWS; from += BATCH) {
            int n = Math.min(BATCH, ROWS - from);
            long[] key = new long[n];
            int[] quantity = new int[n];
            long[] price = new long[n];
            long[] discount = new long[n];
            double[] tax = new double[n];
            int[] shipdate = new int[n];
            BytesVector.Builder returnflag = new BytesVector.Builder(DataType.VARCHAR, n);
            BytesVector.Builder linestatus = new BytesVector.Builder(DataType.VARCHAR, n);
            for (int i = 0; i < n; i++) {
                key[i] = random.nextInt(KEYS);
                quantity[i] = 1 + random.nextInt(50);
                price[i] = 90_000 + random.nextLong(10_410_000);
                discount[i] = random.nextInt(11);
                tax[i] = random.nextInt(9) / 100.0;
                shipdate[i] = first + random.nextInt(7 * 365);
                returnflag.appendString(FLAGS[random.nextInt(FLAGS.length)]);
                linestatus.appendString(STATUSES[random.nextInt(STATUSES.length)]);
            }
            List<Vector> columns = List.of(BigIntVector.wrap(DataType.BIGINT, key, null),
                    IntVector.wrap(DataType.INTEGER, quantity, null),
                    DecimalVector.wrap(DataType.decimal(12, 2), price, null),
                    DecimalVector.wrap(DataType.decimal(4, 2), discount, null), DoubleVector.wrap(tax, null),
                    IntVector.wrap(DataType.DATE, shipdate, null), returnflag.build(), linestatus.build());
            batches.add(new Batch(LINEITEM, n, columns));
        }
        return batches;
    }

    /** The rows with every {@code step}-th key below {@link #KEYS}, and a nation of 25. */
    static List<Batch> customers(int step) {
        int count = KEYS / step;
        List<Batch> batches = new ArrayList<>();
        for (int from = 0; from < count; from += BATCH) {
            int n = Math.min(BATCH, count - from);
            long[] key = new long[n];
            int[] nation = new int[n];
            for (int i = 0; i < n; i++) {
                key[i] = (long) (from + i) * step;
                nation[i] = (from + i) % 25;
            }
            batches.add(new Batch(CUSTOMER, n, List.of(BigIntVector.wrap(DataType.BIGINT, key, null),
                    IntVector.wrap(DataType.INTEGER, nation, null))));
        }
        return batches;
    }

    private long run(PlanNode plan, Blackhole bh) {
        return run(executor, plan, bh);
    }

    private static long run(Executor executor, PlanNode plan, Blackhole bh) {
        long rows = 0;
        try (Operator op = executor.open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                rows += b.rowCount();
                bh.consume(b);
            }
        }
        return rows;
    }

    /** The control: only reads the batches. */
    @Benchmark
    public long scan(Blackhole bh) {
        return run(scan, bh);
    }

    /** The filter of TPC-H Q6. */
    @Benchmark
    public long filter(Blackhole bh) {
        return run(filter, bh);
    }

    /** Four computed columns over all rows. */
    @Benchmark
    public long project(Blackhole bh) {
        return run(project, bh);
    }

    /** {@code count(*)} and {@code sum} in 100,000 groups of a {@code BIGINT} key. */
    @Benchmark
    public long groupBy(Blackhole bh) {
        return run(groupBy, bh);
    }

    /** A hash join of the four million rows with 100,000 customers on a {@code BIGINT} key. */
    @Benchmark
    public long join(Blackhole bh) {
        return run(join, bh);
    }

    /**
     * The form of TPC-H Q1: sums and averages of an integer and two decimal
     * columns, the sum of a double and {@code count(*)} in six groups of two
     * {@code VARCHAR} keys.
     */
    @Benchmark
    public long aggregateDecimals(Blackhole bh) {
        return run(aggregateDecimals, bh);
    }

    /** A left join of the four million rows with 50,000 suppliers, which match half of them. */
    @Benchmark
    public long leftJoin(Blackhole bh) {
        return run(leftJoin, bh);
    }

    /**
     * A filter that keeps about half of the rows, a projection of the key and
     * a decimal revenue, a join with the customers and the sum of the revenue
     * per nation: the chain a pipeline fuses, with an aggregate on top.
     */
    @Benchmark
    public long pipeline(Blackhole bh) {
        return run(pipeline, bh);
    }

    /** The plan of {@link #pipeline} with a filter, a projection and a join, each with compiled code. */
    @Benchmark
    public long pipelineOperators(Blackhole bh) {
        return run(operators, pipeline, bh);
    }

    /** The filter that keeps about half of the rows and the four computed columns above it, as one pipeline. */
    @Benchmark
    public long filterProject(Blackhole bh) {
        return run(filterProject, bh);
    }

    /** The chain of {@link #filterProject} as a filter and a projection, each with compiled code. */
    @Benchmark
    public long filterProjectOperators(Blackhole bh) {
        return run(operators, filterProject, bh);
    }
}
