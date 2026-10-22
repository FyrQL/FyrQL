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

package net.fyrql.codegen;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.exec.QueryException;
import net.fyrql.exec.Splits;
import net.fyrql.exec.TableSource;
import net.fyrql.exec.compiled.CompiledPipeline;
import net.fyrql.exec.compiled.JoinTable;
import net.fyrql.exec.compiled.PipelineSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static net.fyrql.codegen.JaninoCompilerTest.T;
import static net.fyrql.codegen.JaninoCompilerTest.c;
import static net.fyrql.codegen.JaninoCompilerTest.comparable;
import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.coalesce;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.date;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.le;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compiled pipeline gives the rows of the operators of its nodes, bit for
 * bit, for every flat type, with the stages in either order, columns passed
 * on and computed, in batches of one, seven and 64 rows; an error of exactly
 * one row names the same node with the same message. Its joins give the
 * rows of the hash join for every type of join, with keys of several types
 * or none, with and without a condition, with empty sides, keys that are
 * {@code NULL} on either side and that match more than once, and read the
 * tables in the same order.
 */
class PipelineCodegenTest {

    private static final PlanNode.Scan SCAN = PlanNode.Scan.all(TableRef.of("t", "t"), T);

    /** All columns of a schema as they are, and more after them. */
    private static List<NamedExpression> passAll(Schema schema, NamedExpression... more) {
        List<NamedExpression> columns = new ArrayList<>();
        for (int k = 0; k < schema.size(); k++) {
            columns.add(new NamedExpression(schema.name(k), col(schema, k)));
        }
        columns.addAll(List.of(more));
        return columns;
    }

    /** A filter, and above it a projection of every column and a computed one. */
    private static PlanNode filterBelow(PlanNode source, Expression e, Expression condition) {
        return new PlanNode.Project(new PlanNode.Filter(source, condition),
                passAll(source.schema(), new NamedExpression("x", e)));
    }

    /**
     * A projection of every column and a computed one, a filter that reads
     * it, and a projection of some columns in another order, passed on and
     * computed again from the computed one.
     */
    private static PlanNode filterAbove(PlanNode source, Expression e, Expression condition) {
        PlanNode.Project p = new PlanNode.Project(source, passAll(source.schema(), new NamedExpression("x", e)));
        Schema s = p.schema();
        Expression x = col(s, s.size() - 1);
        PlanNode.Filter f = new PlanNode.Filter(p, or(condition, eq(x, x)));
        return new PlanNode.Project(f, List.of(new NamedExpression("x", x), new NamedExpression("i", col(s, 3)),
                new NamedExpression("xn", isNull(x)), new NamedExpression("xx", coalesce(x, x)),
                new NamedExpression("v", col(s, 10)), new NamedExpression("w", col(s, 9)),
                new NamedExpression("i1", add(col(s, 3), lit(1)))));
    }

    /** Runs a plan; the rows as text, or the error. */
    private static String run(PlanNode plan, List<Batch> data, Codegen codegen, boolean pipeline) {
        return run(new Executor(scan -> Batches.reader(data), MemoryBudget.unlimited(), 64, codegen), plan, pipeline);
    }

    /** Runs a plan over tables by name; the rows as text, or the error, and the tables in the order they were read. */
    private static String run(PlanNode plan, Map<String, List<Batch>> tables, Codegen codegen, boolean pipeline) {
        List<String> read = new ArrayList<>();
        Executor executor = new Executor(scan -> {
            read.add(scan.table().table());
            return Batches.reader(tables.get(scan.table().table()));
        }, MemoryBudget.unlimited(), 64, codegen);
        return run(executor, plan, pipeline) + "read " + read;
    }

    private static String run(Executor executor, PlanNode plan, boolean pipeline) {
        return run(executor, plan, op -> assertEquals(pipeline, op.getClass().getSimpleName()
                .equals("PipelineOperator"), plan::toString));
    }

    /** Runs a plan; the rows as text, or the error, after a check of the top operator. */
    private static String run(Executor executor, PlanNode plan, Consumer<Operator> check) {
        StringBuilder sb = new StringBuilder();
        try (Operator op = executor.open(plan)) {
            check.accept(op);
            for (Batch b = op.next(); b != null; b = op.next()) {
                assertEquals(plan.schema().types(), b.schema().types());
                for (int r = 0; r < b.rowCount(); r++) {
                    for (Vector v : b.columns()) {
                        sb.append(v.isNull(r) ? "NULL" : comparable(v, r)).append('|');
                    }
                    sb.append('\n');
                }
            }
        } catch (QueryException e) {
            return "error";
        }
        return sb.toString();
    }

    private static Codegen always(JaninoCompiler compiler) {
        return Codegen.of(CodegenMode.ALWAYS, 0, compiler);
    }

    @Test
    void chainsAgreeWithTheOperators() {
        JaninoCompiler compiler = new JaninoCompiler();
        List<Expression> all = JaninoCompilerTest.expressions();
        List<Expression> conditions = all.stream().filter(e -> e.type() == DataType.BOOLEAN).toList();
        int runs = 0;
        int rows = 0;
        for (int x = 0; x < all.size(); x++) {
            Expression e = all.get(x);
            Expression condition = conditions.get(x % conditions.size());
            for (PlanNode plan : List.of(filterBelow(SCAN, e, condition), filterAbove(SCAN, e, condition))) {
                for (int batchSize : new int[] {1, 7, 64}) {
                    List<Batch> data = JaninoCompilerTest.batches(x * 31L + batchSize, batchSize);
                    String expected = run(plan, data, Codegen.off(), false);
                    assertEquals(expected, run(plan, data, always(compiler), true), plan::toString);
                    runs++;
                    rows += expected.equals("error") ? 0 : (int) expected.lines().count();
                }
            }
        }
        // Most chains keep rows and do not fail.
        assertTrue(rows > runs * 20, rows + " rows in " + runs + " runs");
        assertEquals(0, compiler.statistics().failures());
        assertEquals(0, compiler.statistics().declined());
    }

    @Test
    void anErrorOfOneRowNamesTheNodeOfItsStage() {
        Schema s = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("v", DataType.VARCHAR));
        List<Object[]> rows = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            rows.add(new Object[] {k, k == 37 ? "x" : String.valueOf(k)});
        }
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "s"), s);
        Expression k = col(s, 0);
        Expression v = col(s, 1);
        PlanNode inProject = new PlanNode.Project(new PlanNode.Filter(scan, gt(k, lit(-1))),
                List.of(new NamedExpression("n", cast(v, DataType.INTEGER))));
        PlanNode.Project computed = new PlanNode.Project(scan, passAll(s, new NamedExpression("k1", add(k, lit(1)))));
        PlanNode inFilter = new PlanNode.Filter(computed, gt(cast(col(computed.schema(), 1), DataType.INTEGER),
                lit(0)));
        PlanNode inUpperProject = new PlanNode.Project(inFilter, List.of(new NamedExpression("n",
                cast(col(computed.schema(), 1), DataType.INTEGER))));
        JaninoCompiler compiler = new JaninoCompiler();
        PlanNode aboveAFilter = new PlanNode.Project(new PlanNode.Filter(computed, gt(col(computed.schema(), 2),
                lit(0))), List.of(new NamedExpression("n", cast(col(computed.schema(), 1), DataType.INTEGER))));
        for (PlanNode plan : List.of(inProject, inFilter, aboveAFilter, inUpperProject)) {
            for (int batchSize : new int[] {1, 7, 64}) {
                List<Batch> data = Batches.split(Batch.ofRows(s, rows), batchSize);
                QueryException expected = failure(plan, data, Codegen.off());
                QueryException actual = failure(plan, data, always(compiler));
                assertEquals(expected.operator(), actual.operator(), plan::toString);
                assertEquals(expected.getMessage(), actual.getMessage());
                assertEquals(expected.getCause().getClass(), actual.getCause().getClass());
            }
        }
        assertEquals("Project#0", failure(aboveAFilter, Batches.split(Batch.ofRows(s, rows), 7), always(compiler))
                .operator());
        // The filter fails first, below the projection that would fail for the same row.
        assertEquals("Filter#1", failure(inUpperProject, Batches.split(Batch.ofRows(s, rows), 7), always(compiler))
                .operator());
    }

    private static QueryException failure(PlanNode plan, List<Batch> data, Codegen codegen) {
        Executor executor = new Executor(scan -> Batches.reader(data), MemoryBudget.unlimited(), 64, codegen);
        try (Operator op = executor.open(plan)) {
            while (op.next() != null) {
                // Reads until the error.
            }
        } catch (QueryException e) {
            return e;
        }
        throw new AssertionError("No error: " + plan);
    }

    @Test
    void longCodeBecomesMethodsOfItsOwn() {
        List<Expression.When> whens = new ArrayList<>();
        Expression condition = lt(c("i"), lit(-1000));
        for (int k = 0; k < 15; k++) {
            whens.add(when(gt(c("i"), lit(k * 100)), add(c("m"), lit(new BigDecimal(k + ".25")))));
            condition = or(condition, and(eq(c("t"), lit((byte) k)), gt(c("d"), lit((double) k))));
        }
        Expression big = caseWhen(whens, c("m"));
        PlanNode.Filter filter = new PlanNode.Filter(SCAN, condition);
        PlanNode.Project project = new PlanNode.Project(filter, passAll(T, new NamedExpression("x", big),
                new NamedExpression("y", add(c("i"), lit(1)))));
        JaninoCompiler compiler = new JaninoCompiler();
        CompiledPipeline compiled = compiler.pipeline(new PipelineSpec(T, List.of(filter, project)));
        assertNotNull(compiled);
        String source = compiled.source();
        assertTrue(source.contains("private boolean s0(int i, "), source);
        assertTrue(source.contains("private void s1_14(int i, "), source);
        // The short column stays in the loop.
        assertFalse(source.contains("s1_15"), source);
        for (int batchSize : new int[] {1, 64}) {
            List<Batch> data = JaninoCompilerTest.batches(batchSize, batchSize);
            String expected = run(project, data, Codegen.off(), false);
            assertEquals(expected, run(project, data, always(compiler), true));
            assertFalse(expected.isEmpty());
        }
    }

    @Test
    void aClassWithAMethodTheJitWouldNotCompileIsDeclined() {
        List<NamedExpression> columns = new ArrayList<>();
        for (int k = 0; k < 300; k++) {
            columns.add(new NamedExpression("c" + k, add(c("i"), lit(k))));
        }
        PlanNode.Filter filter = new PlanNode.Filter(SCAN, gt(c("l"), lit(0L)));
        PlanNode.Project wide = new PlanNode.Project(filter, columns);
        JaninoCompiler compiler = new JaninoCompiler();
        assertTrue(compiler.supportsPipeline(new PipelineSpec(T, List.of(filter, wide))));
        assertNull(compiler.pipeline(new PipelineSpec(T, List.of(filter, wide))));
        assertEquals(1, compiler.statistics().declined());
        assertEquals(0, compiler.statistics().failures());
        // The nodes run as operators.
        List<Batch> data = JaninoCompilerTest.batches(3, 64);
        assertEquals(run(wide, data, Codegen.off(), false), run(wide, data, always(compiler), false));
    }

    @Test
    void theChainOfQ1CompilesBelowTheLimitOfTheJit() {
        DataType money = DataType.decimal(15, 2);
        Schema lineitem = Schema.of(new Schema.Field("l_quantity", money), new Schema.Field("l_extendedprice", money),
                new Schema.Field("l_discount", money), new Schema.Field("l_tax", money),
                new Schema.Field("l_returnflag", DataType.VARCHAR), new Schema.Field("l_linestatus", DataType.VARCHAR),
                new Schema.Field("l_shipdate", DataType.DATE));
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "lineitem"), lineitem);
        PlanNode.Filter filter = new PlanNode.Filter(scan, le(col(lineitem, 6),
                date((int) LocalDate.of(1998, 9, 2).toEpochDay())));
        Expression price = col(lineitem, 1);
        Expression discount = col(lineitem, 2);
        Expression discounted = mul(price, sub(lit(1), discount));
        PlanNode.Project project = new PlanNode.Project(filter, List.of(
                new NamedExpression("#group0", col(lineitem, 4)), new NamedExpression("#group1", col(lineitem, 5)),
                new NamedExpression("#argument0", col(lineitem, 0)), new NamedExpression("#argument1", price),
                new NamedExpression("#argument2", discounted),
                new NamedExpression("#argument3", mul(discounted, add(lit(1), col(lineitem, 3)))),
                new NamedExpression("#argument4", discount)));
        JaninoCompiler compiler = new JaninoCompiler();
        // A class with a method over the limit would be declined.
        assertNotNull(compiler.pipeline(new PipelineSpec(lineitem, List.of(filter, project))));
        SplittableRandom random = new SplittableRandom(1);
        List<Object[]> rows = new ArrayList<>();
        for (int k = 0; k < 500; k++) {
            rows.add(new Object[] {BigDecimal.valueOf(1 + random.nextInt(50), 0).setScale(2),
                BigDecimal.valueOf(90_000 + random.nextInt(10_000_000), 2), BigDecimal.valueOf(random.nextInt(11), 2),
                BigDecimal.valueOf(random.nextInt(9), 2), random.nextBoolean() ? "A" : "R", "F",
                (int) LocalDate.of(1992, 1, 1).toEpochDay() + random.nextInt(2500)});
        }
        List<Batch> data = Batches.split(Batch.ofRows(lineitem, rows), 64);
        assertEquals(run(project, data, Codegen.off(), false), run(project, data, always(compiler), true));
    }

    @Test
    void columnsOfNestedTypesArePassedOn() {
        Schema nested = Schema.of(new Schema.Field("k", DataType.INTEGER),
                new Schema.Field("l", DataType.listOf(DataType.INTEGER)), new Schema.Field("v", DataType.VARCHAR));
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "n"), nested);
        PlanNode plan = new PlanNode.Project(new PlanNode.Filter(scan, gt(col(nested, 0), lit(0))), List.of(
                new NamedExpression("l", col(nested, 1)), new NamedExpression("k1", add(col(nested, 0), lit(1))),
                new NamedExpression("l2", col(nested, 1))));
        JaninoCompiler compiler = new JaninoCompiler();
        for (int batchSize : new int[] {1, 7, 64}) {
            List<Batch> data = Batches.split(Batch.ofRows(nested, DataGenerator.withSeed(batchSize)
                    .generate(nested, 200)), batchSize);
            String expected = run(plan, data, Codegen.off(), false);
            assertEquals(expected, run(plan, data, always(compiler), true));
        }
        // A condition over a nested column ends the chain.
        PlanNode overList = new PlanNode.Project(new PlanNode.Filter(scan, isNull(col(nested, 1))), List.of(
                new NamedExpression("k1", add(col(nested, 0), lit(1)))));
        List<Batch> data = Batches.split(Batch.ofRows(nested, DataGenerator.withSeed(9).generate(nested, 50)), 7);
        assertEquals(run(overList, data, Codegen.off(), false), run(overList, data, always(compiler), false));
    }

    /** The probe side of the joins: keys of four types, a value, and a list that is only passed on. */
    private static final Schema P = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("d", DataType.decimal(9, 2)),
            new Schema.Field("f", DataType.DOUBLE), new Schema.Field("x", DataType.BIGINT),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER)));

    /** The build side of the joins, with the columns of the probe side. */
    private static final Schema B = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("d", DataType.decimal(9, 2)),
            new Schema.Field("f", DataType.DOUBLE), new Schema.Field("y", DataType.BIGINT),
            new Schema.Field("l", DataType.listOf(DataType.INTEGER)));

    private static PlanNode.Scan scan(String name, Schema schema) {
        return PlanNode.Scan.all(TableRef.of("t", name), schema);
    }

    /**
     * Rows for a join: few distinct values, so that rows match, some more
     * than once, with {@code NULL} in every column, {@code -0.0} and
     * {@code NaN}; or, if unique, one row per key.
     */
    private static List<Object[]> joinRows(long seed, int count, boolean unique) {
        SplittableRandom random = new SplittableRandom(seed);
        String[] strings = {"a", "b", "c", "\u00e4"};
        double[] doubles = {0.0, -0.0, 1.5, Double.NaN};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int k = unique ? i : random.nextInt(6);
            rows.add(new Object[] {!unique && random.nextInt(10) == 0 ? null : k,
                random.nextInt(10) == 0 ? null : unique ? "u" + i : strings[random.nextInt(4)],
                random.nextInt(10) == 0 ? null : BigDecimal.valueOf(50L * (1 + random.nextInt(3)), 2),
                random.nextInt(10) == 0 ? null : doubles[random.nextInt(4)],
                random.nextInt(10) == 0 ? null : (long) random.nextInt(10),
                random.nextInt(4) == 0 ? null : List.of(i, k)});
        }
        return rows;
    }

    private static List<Batch> batches(Schema schema, List<Object[]> rows, int batchSize) {
        return rows.isEmpty() ? List.of() : Batches.split(Batch.ofRows(schema, rows), batchSize);
    }

    /** A join of a probe side with the build side {@code b}, with the condition {@code x > y} or none. */
    private static PlanNode.Join join(PlanNode left, List<Integer> leftKeys, List<Integer> rightKeys, JoinType type,
            boolean condition) {
        PlanNode.Scan right = scan("b", B);
        Schema pair = left.schema().concat(B);
        return new PlanNode.Join(left, right, leftKeys, rightKeys, type, condition ? gt(col(pair, 4),
                col(pair, left.schema().size() + 4)) : null);
    }

    /**
     * The plans of the matrix for a type of join: the join above a filter of
     * the probe side; a projection above it that computes from both sides;
     * a second join above it, keyed on the build side of the first where it
     * has one; and the join keyed on a column computed below it.
     */
    private static List<PlanNode> joinPlans(JoinType type, List<Integer> keys, boolean condition) {
        PlanNode.Filter probe = new PlanNode.Filter(scan("p", P), or(isNull(col(P, 4)), ge(col(P, 4), lit(1L))));
        PlanNode.Join join = join(probe, keys, keys, type, condition);
        List<PlanNode> plans = new ArrayList<>();
        plans.add(join);
        if (!keys.isEmpty()) {
            PlanNode.Project computedKey = new PlanNode.Project(probe, passAll(P, new NamedExpression("k1",
                    cast(add(col(P, 0), lit(1)), DataType.INTEGER))));
            plans.add(join(computedKey, List.of(P.size()), List.of(0), type, condition));
        }
        if (type == JoinType.RIGHT || type == JoinType.FULL) {
            return plans;
        }
        Schema s = join.schema();
        int width = P.size();
        NamedExpression computed = switch (type) {
            case SEMI, ANTI -> new NamedExpression("x1", add(col(s, 4), lit(1L)));
            case MARK -> new NamedExpression("unmarked", not(col(s, width)));
            default -> new NamedExpression("xy", add(col(s, 4), col(s, width + 4)));
        };
        List<NamedExpression> columns = passAll(s, computed);
        if (type.keepsRight()) {
            columns.add(new NamedExpression("unmatched", isNull(col(s, width))));
        }
        plans.add(new PlanNode.Project(join, columns));
        int key = type.keepsRight() ? width : 0;
        plans.add(new PlanNode.Join(join, scan("c", B), List.of(key), List.of(0), JoinType.INNER));
        return plans;
    }

    @Test
    void joinsAgreeWithTheHashJoin() {
        JaninoCompiler compiler = new JaninoCompiler();
        List<List<Integer>> keySets = List.of(List.of(0), List.of(1), List.of(2), List.of(3), List.of(0, 1),
                List.of());
        int runs = 0;
        int rows = 0;
        for (JoinType type : JoinType.values()) {
            for (List<Integer> keys : keySets) {
                for (boolean condition : new boolean[] {false, true}) {
                    if (type == JoinType.CROSS ? !keys.isEmpty() || condition : keys.isEmpty() && type.needsKeys()) {
                        continue;
                    }
                    for (PlanNode plan : joinPlans(type, keys, condition)) {
                        for (int data = 0; data < 4; data++) {
                            long seed = runs;
                            List<Object[]> probe = data == 2 ? List.of() : joinRows(seed, 150, false);
                            List<Object[]> build = data == 1 ? List.of() : joinRows(seed + 1, data == 3 ? 6 : 40,
                                    data == 3);
                            List<Object[]> second = joinRows(seed + 2, 30, false);
                            for (int batchSize : new int[] {1, 7, 64}) {
                                Map<String, List<Batch>> tables = new HashMap<>();
                                tables.put("p", batches(P, probe, batchSize));
                                tables.put("b", batches(B, build, batchSize));
                                tables.put("c", batches(B, second, batchSize));
                                String expected = run(plan, tables, Codegen.off(), false);
                                assertEquals(expected, run(plan, tables, always(compiler), true), plan::toString);
                                runs++;
                                rows += (int) expected.lines().count() - 1;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(rows > runs * 10, rows + " rows in " + runs + " runs");
        assertEquals(0, compiler.statistics().failures());
        assertEquals(0, compiler.statistics().declined());
    }

    /**
     * Runs a plan over tables by name on a number of threads, each batch of
     * a table a split of its own; the rows as text, or the error.
     *
     * @param readers notes the threads that read the splits
     */
    private static String parallel(PlanNode plan, Map<String, List<Batch>> tables, Codegen codegen, int threads,
            Set<String> readers) {
        TableSource source = new TableSource() {
            @Override
            public BatchReader open(PlanNode.Scan scan) {
                return Batches.reader(tables.get(scan.table().table()));
            }

            @Override
            public Splits splits(PlanNode.Scan scan, QueryContext context) {
                return Splits.of(tables.get(scan.table().table()), b -> {
                    readers.add(Thread.currentThread().getName());
                    return Batches.reader(List.of(b));
                });
            }
        };
        Executor executor = new Executor(source, MemoryBudget.unlimited(), 64, codegen, threads, true);
        return run(executor, plan, op -> { });
    }

    @Test
    void joinsOnSeveralThreadsAgreeWithOneThread() {
        JaninoCompiler compiler = new JaninoCompiler();
        Set<String> readers = ConcurrentHashMap.newKeySet();
        int runs = 0;
        int rows = 0;
        for (JoinType type : JoinType.values()) {
            for (List<Integer> keys : List.of(List.of(0), List.of(0, 1), List.<Integer>of())) {
                for (boolean condition : new boolean[] {false, true}) {
                    if (type == JoinType.CROSS ? !keys.isEmpty() || condition : keys.isEmpty() && type.needsKeys()) {
                        continue;
                    }
                    for (PlanNode plan : joinPlans(type, keys, condition)) {
                        for (int data = 0; data < 4; data++) {
                            long seed = runs;
                            List<Object[]> probe = data == 2 ? List.of() : joinRows(seed, 150, false);
                            List<Object[]> build = data == 1 ? List.of() : joinRows(seed + 1, data == 3 ? 6 : 40,
                                    data == 3);
                            Map<String, List<Batch>> tables = new HashMap<>();
                            tables.put("p", batches(P, probe, 7));
                            tables.put("b", batches(B, build, 7));
                            tables.put("c", batches(B, joinRows(seed + 2, 30, false), 7));
                            String expected = parallel(plan, tables, Codegen.off(), 1, readers);
                            for (int threads : new int[] {2, 4}) {
                                assertEquals(expected, parallel(plan, tables, always(compiler), threads, readers),
                                        plan::toString);
                                assertEquals(expected, parallel(plan, tables, Codegen.off(), threads, readers),
                                        plan::toString);
                            }
                            runs++;
                            rows += (int) expected.lines().count();
                        }
                    }
                }
            }
        }
        assertTrue(rows > runs * 10, rows + " rows in " + runs + " runs");
        assertTrue(readers.stream().anyMatch(t -> t.startsWith("fyrql-worker-")), readers::toString);
        assertTrue(compiler.statistics().compiled() > 0);
        assertEquals(0, compiler.statistics().failures());
        assertEquals(0, compiler.statistics().declined());
    }

    @Test
    void aJoinWithoutKeysConsumesInStepsOfBoundedPairs() {
        Schema p = Schema.of(new Schema.Field("v", DataType.INTEGER));
        Schema b = Schema.of(new Schema.Field("w", DataType.INTEGER));
        List<Object[]> probe = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            probe.add(new Object[] {i});
        }
        List<Object[]> build = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            build.add(new Object[] {i});
        }
        // One batch of 3,000 rows of the source against 1,000 build rows: three million pairs.
        Map<String, List<Batch>> tables = Map.of("p", batches(p, probe, 3000), "b", batches(b, build, 3000));
        JaninoCompiler compiler = new JaninoCompiler();
        PlanNode.Join cross = new PlanNode.Join(scan("p", p), scan("b", b), List.of(), List.of(), JoinType.CROSS);
        Executor executor = new Executor(scan -> Batches.reader(tables.get(scan.table().table())),
                MemoryBudget.unlimited(), 64, always(compiler));
        long rows = 0;
        int largest = 0;
        try (Operator op = executor.open(cross)) {
            assertEquals("PipelineOperator", op.getClass().getSimpleName());
            for (Batch batch = op.next(); batch != null; batch = op.next()) {
                rows += batch.rowCount();
                largest = Math.max(largest, batch.rowCount());
            }
        }
        assertEquals(3_000_000L, rows);
        assertTrue(largest <= JoinTable.MAX_PAIRS, largest + " rows in a batch");

        Schema pair = p.concat(b);
        for (PlanNode plan : List.of(new PlanNode.Filter(cross, eq(col(pair, 0), col(pair, 1))),
                new PlanNode.Join(scan("p", p), scan("b", b), List.of(), List.of(), JoinType.SINGLE,
                        eq(col(pair, 0), col(pair, 1))),
                new PlanNode.Join(scan("p", p), scan("b", b), List.of(), List.of(), JoinType.MARK,
                        eq(col(pair, 0), col(pair, 1))))) {
            String expected = run(plan, tables, Codegen.off(), false);
            assertEquals(expected, run(plan, tables, always(compiler), true), plan::toString);
        }
    }

    @Test
    void anErrorOfOneRowInAJoinNamesTheJoin() {
        Schema s = Schema.of(new Schema.Field("k", DataType.INTEGER), new Schema.Field("v", DataType.VARCHAR));
        List<Object[]> probe = new ArrayList<>();
        List<Object[]> build = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            probe.add(new Object[] {k, k == 37 ? "x" : String.valueOf(k)});
            build.add(new Object[] {k, String.valueOf(k)});
        }
        List<Object[]> twice = new ArrayList<>(build);
        twice.add(new Object[] {23, "23"});
        Schema pair = s.concat(s);
        PlanNode.Filter left = new PlanNode.Filter(scan("p", s), gt(col(s, 0), lit(-1)));
        PlanNode inCondition = new PlanNode.Project(new PlanNode.Join(left, scan("b", s), List.of(0), List.of(0),
                JoinType.INNER, gt(cast(col(pair, 1), DataType.INTEGER), lit(-1))), passAll(pair));
        PlanNode single = new PlanNode.Project(new PlanNode.Join(left, scan("c", s), List.of(0), List.of(0),
                JoinType.SINGLE), passAll(pair));
        PlanNode.Join leftJoin = new PlanNode.Join(left, scan("b", s), List.of(0), List.of(0), JoinType.LEFT);
        PlanNode above = new PlanNode.Filter(leftJoin, gt(cast(col(pair, 1), DataType.INTEGER), lit(-1)));
        JaninoCompiler compiler = new JaninoCompiler();
        for (PlanNode plan : List.of(inCondition, single, above)) {
            for (int batchSize : new int[] {1, 7, 64}) {
                Map<String, List<Batch>> tables = Map.of("p", batches(s, probe, batchSize), "b",
                        batches(s, build, batchSize), "c", batches(s, twice, batchSize));
                QueryException expected = failure(plan, tables, Codegen.off());
                QueryException actual = failure(plan, tables, always(compiler));
                assertEquals(expected.operator(), actual.operator(), plan::toString);
                assertEquals(expected.getMessage(), actual.getMessage());
                assertEquals(expected.getCause().getClass(), actual.getCause().getClass());
            }
        }
        Map<String, List<Batch>> tables = Map.of("p", batches(s, probe, 7), "b", batches(s, build, 7), "c",
                batches(s, twice, 7));
        assertEquals("Join#1", failure(inCondition, tables, always(compiler)).operator());
        assertTrue(failure(single, tables, always(compiler)).getMessage().startsWith("More than one row"));
        assertEquals("Filter#0", failure(above, tables, always(compiler)).operator());
    }

    private static QueryException failure(PlanNode plan, Map<String, List<Batch>> tables, Codegen codegen) {
        Executor executor = new Executor(scan -> Batches.reader(tables.get(scan.table().table())),
                MemoryBudget.unlimited(), 64, codegen);
        try (Operator op = executor.open(plan)) {
            assertEquals(codegen.compiler() != null, op.getClass().getSimpleName().equals("PipelineOperator"));
            while (op.next() != null) {
                // Reads until the error.
            }
        } catch (QueryException e) {
            return e;
        }
        throw new AssertionError("No error: " + plan);
    }

    @Test
    void aChainOfFiveProbesInTheFormOfQ9CompilesBelowTheLimitOfTheJit() {
        DataType money = DataType.decimal(15, 2);
        Schema lineitem = Schema.of(new Schema.Field("l_orderkey", DataType.INTEGER),
                new Schema.Field("l_partkey", DataType.INTEGER), new Schema.Field("l_suppkey", DataType.INTEGER),
                new Schema.Field("l_quantity", money), new Schema.Field("l_extendedprice", money),
                new Schema.Field("l_discount", money));
        Schema part = Schema.of(new Schema.Field("p_partkey", DataType.INTEGER),
                new Schema.Field("p_name", DataType.VARCHAR));
        Schema partsupp = Schema.of(new Schema.Field("ps_partkey", DataType.INTEGER),
                new Schema.Field("ps_suppkey", DataType.INTEGER), new Schema.Field("ps_supplycost", money));
        Schema supplier = Schema.of(new Schema.Field("s_suppkey", DataType.INTEGER),
                new Schema.Field("s_nationkey", DataType.INTEGER));
        Schema orders = Schema.of(new Schema.Field("o_orderkey", DataType.INTEGER),
                new Schema.Field("o_orderdate", DataType.DATE));
        Schema nation = Schema.of(new Schema.Field("n_nationkey", DataType.INTEGER),
                new Schema.Field("n_name", DataType.VARCHAR));
        PlanNode.Filter green = new PlanNode.Filter(scan("part", part), eq(col(part, 1), lit("green")));
        PlanNode.Join j1 = new PlanNode.Join(scan("lineitem", lineitem), green, List.of(1), List.of(0),
                JoinType.INNER);
        PlanNode.Join j2 = new PlanNode.Join(j1, scan("partsupp", partsupp), List.of(1, 2), List.of(0, 1),
                JoinType.INNER);
        PlanNode.Join j3 = new PlanNode.Join(j2, scan("supplier", supplier), List.of(2), List.of(0),
                JoinType.INNER);
        PlanNode.Join j4 = new PlanNode.Join(j3, scan("orders", orders), List.of(0), List.of(0), JoinType.INNER);
        PlanNode.Join j5 = new PlanNode.Join(j4, scan("nation", nation), List.of(12), List.of(0), JoinType.INNER);
        Schema s = j5.schema();
        PlanNode.Project project = new PlanNode.Project(j5, List.of(new NamedExpression("nation", col(s, 16)),
                new NamedExpression("o_orderdate", col(s, 14)), new NamedExpression("amount",
                        sub(mul(col(s, 4), sub(lit(1), col(s, 5))), mul(col(s, 10), col(s, 3))))));
        JaninoCompiler compiler = new JaninoCompiler();
        // A class with a method over the limit would be declined.
        assertNotNull(compiler.pipeline(new PipelineSpec(lineitem, List.of(j1, j2, j3, j4, j5, project))));

        SplittableRandom random = new SplittableRandom(9);
        Map<String, List<Object[]>> rows = new HashMap<>();
        List<Object[]> items = new ArrayList<>();
        for (int k = 0; k < 400; k++) {
            items.add(new Object[] {random.nextInt(50), random.nextInt(20), random.nextInt(5),
                BigDecimal.valueOf(1 + random.nextInt(50), 0).setScale(2),
                BigDecimal.valueOf(90_000 + random.nextInt(10_000_000), 2), BigDecimal.valueOf(random.nextInt(11), 2)});
        }
        rows.put("lineitem", items);
        List<Object[]> parts = new ArrayList<>();
        List<Object[]> supplies = new ArrayList<>();
        for (int p = 0; p < 20; p++) {
            parts.add(new Object[] {p, p % 3 == 0 ? "green" : "red"});
            for (int x = 0; x < 5; x++) {
                supplies.add(new Object[] {p, x, BigDecimal.valueOf(100 + random.nextInt(100_000), 2)});
            }
        }
        rows.put("part", parts);
        rows.put("partsupp", supplies);
        List<Object[]> suppliers = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            suppliers.add(new Object[] {x, x % 3});
        }
        rows.put("supplier", suppliers);
        List<Object[]> orderRows = new ArrayList<>();
        for (int o = 0; o < 50; o++) {
            orderRows.add(new Object[] {o, (int) LocalDate.of(1992, 1, 1).toEpochDay() + random.nextInt(2500)});
        }
        rows.put("orders", orderRows);
        rows.put("nation", List.of(new Object[] {0, "FRANCE"}, new Object[] {1, "PERU"}, new Object[] {2, "JAPAN"}));
        Map<String, Schema> schemas = Map.of("lineitem", lineitem, "part", part, "partsupp", partsupp, "supplier",
                supplier, "orders", orders, "nation", nation);
        for (int batchSize : new int[] {7, 64}) {
            Map<String, List<Batch>> tables = new HashMap<>();
            for (Map.Entry<String, Schema> e : schemas.entrySet()) {
                tables.put(e.getKey(), batches(e.getValue(), rows.get(e.getKey()), batchSize));
            }
            String expected = run(project, tables, Codegen.off(), false);
            assertEquals(expected, run(project, tables, always(compiler), true));
            assertTrue(expected.lines().count() > 50, expected);
        }
        assertEquals(0, compiler.statistics().declined());
    }
}
