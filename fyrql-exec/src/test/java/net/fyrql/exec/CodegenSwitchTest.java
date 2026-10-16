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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.spi.CodegenStatistics;
import net.fyrql.exec.spi.CompiledFilter;
import net.fyrql.exec.spi.CompiledPipeline;
import net.fyrql.exec.spi.CompiledProjection;
import net.fyrql.exec.spi.ExpressionCompiler;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.JoinTable;
import net.fyrql.exec.spi.KeyFunctions;
import net.fyrql.exec.spi.PipelineSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Operators switch from the interpreter to compiled code when
 * {@link Codegen#due} says so, and only then; chains run as one pipeline
 * when {@link PipelinePlanner} finds them and the compiler compiles them.
 */
class CodegenSwitchTest {

    private static final Schema T = Schema.of(new Schema.Field("a", DataType.INTEGER),
            new Schema.Field("b", DataType.INTEGER));

    /** A compiler that compiles nothing but delegates to the interpreter and counts what it is asked and called for. */
    private static final class CountingCompiler implements ExpressionCompiler {
        int asked;
        int calls;
        int keyCalls;
        boolean decline;
        boolean fail;
        boolean pipelines;
        boolean declinePipelines;
        boolean failInPipeline;
        int pipelinesAsked;
        int pipelineCalls;

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public CompiledProjection projection(Expression e, Schema input) {
            if (answer()) {
                return null;
            }
            return new CompiledProjection() {
                @Override
                public Vector evaluate(Batch in) {
                    calls++;
                    return Evaluator.eval(e, in);
                }

                @Override
                public String source() {
                    return "";
                }
            };
        }

        @Override
        public CompiledFilter filter(Expression condition, Schema input) {
            if (answer()) {
                return null;
            }
            return new CompiledFilter() {
                @Override
                public int[] select(Batch in) {
                    calls++;
                    return Evaluator.select(condition, in);
                }

                @Override
                public String source() {
                    return "";
                }
            };
        }

        @Override
        public KeyFunctions keys(List<DataType> types) {
            if (answer()) {
                return null;
            }
            return new KeyFunctions() {
                @Override
                public void hash(Batch b, int[] columns, int[] out) {
                    keyCalls++;
                    InterpretedKeys.INSTANCE.hash(b, columns, out);
                }

                @Override
                public boolean equal(Batch a, int rowA, int[] columnsA, Batch b, int rowB, int[] columnsB) {
                    return InterpretedKeys.INSTANCE.equal(a, rowA, columnsA, b, rowB, columnsB);
                }
            };
        }

        private boolean answer() {
            asked++;
            if (fail) {
                throw new IllegalStateException("broken");
            }
            return decline;
        }

        @Override
        public boolean supports(Expression expression) {
            return true;
        }

        @Override
        public boolean supportsKeys(List<DataType> types) {
            return true;
        }

        @Override
        public boolean supportsPipeline(PipelineSpec spec) {
            // The interpreted pipeline below has no joins.
            return pipelines && spec.probes().isEmpty();
        }

        @Override
        public CompiledPipeline pipeline(PipelineSpec spec) {
            pipelinesAsked++;
            return declinePipelines ? null : new CompiledPipeline() {
                private int stage;

                @Override
                public Batch consume(Batch in, JoinTable[] tables) {
                    pipelineCalls++;
                    Batch b = in;
                    for (stage = 0; stage < spec.stages().size(); stage++) {
                        if (failInPipeline) {
                            throw new IllegalStateException("broken");
                        }
                        if (spec.stages().get(stage) instanceof PlanNode.Filter f) {
                            int[] rows = Evaluator.select(f.condition(), b);
                            if (rows.length == 0) {
                                return null;
                            }
                            b = b.select(rows);
                        } else {
                            PlanNode.Project p = (PlanNode.Project) spec.stages().get(stage);
                            List<Vector> columns = new ArrayList<>();
                            for (NamedExpression c : p.columns()) {
                                columns.add(Evaluator.eval(c.expression(), b));
                            }
                            b = new Batch(p.schema(), b.rowCount(), columns);
                        }
                    }
                    return b;
                }

                @Override
                public int stage() {
                    return stage;
                }

                @Override
                public String source() {
                    return "";
                }
            };
        }

        @Override
        public CodegenStatistics statistics() {
            return CodegenStatistics.NONE;
        }
    }

    private static PlanNode.Scan table(String name) {
        return PlanNode.Scan.all(TableRef.of("t", name), T);
    }

    private static List<Object[]> rows(int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {i, i % 3 == 0 ? null : i % 4});
        }
        return rows;
    }

    private static Executor executor(Codegen codegen) {
        return new Executor(scan -> Batches.reader(Batches.split(Batch.ofRows(T, rows(10)), 3)),
                MemoryBudget.unlimited(), 3, codegen);
    }

    private static String run(Executor executor, PlanNode plan) {
        StringBuilder sb = new StringBuilder();
        for (Object[] row : executor.rows(plan)) {
            sb.append(java.util.Arrays.toString(row));
        }
        return sb.toString();
    }

    /** Reads an operator to its end and closes it; the rows as text. */
    private static String drain(Operator op) {
        StringBuilder sb = new StringBuilder();
        try (op) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                for (Object[] row : b.rows()) {
                    sb.append(java.util.Arrays.toString(row));
                }
            }
        }
        return sb.toString();
    }

    /** A filter and a projection with a computed column above it: a chain of two stages that compute. */
    private static PlanNode chain() {
        return new PlanNode.Project(new PlanNode.Filter(table("t"), gt(col(T, 0), lit(0))),
                List.of(new NamedExpression("s", add(col(T, 0), col(T, 1))), new NamedExpression("b", col(T, 1))));
    }

    @Test
    void aChainRunsAsAPipelineFromTheEstimatedRowsOfItsSourceOn() {
        PlanNode plan = chain();
        String expected = run(executor(Codegen.off()), plan);
        CountingCompiler compiler = new CountingCompiler();
        compiler.pipelines = true;
        Executor auto = executor(Codegen.of(CodegenMode.AUTO, 100, compiler));
        List<PlanNode> estimated = new ArrayList<>();
        assertEquals(expected, drain(auto.open(plan, node -> {
            estimated.add(node);
            return 99;
        })));
        // Only the source is estimated; below the threshold the operators run.
        assertEquals(List.of(table("t")), estimated);
        assertEquals(0, compiler.pipelinesAsked);

        CountingCompiler fused = new CountingCompiler();
        fused.pipelines = true;
        assertEquals(expected, drain(executor(Codegen.of(CodegenMode.AUTO, 100, fused)).open(plan, node -> 100)));
        assertEquals(1, fused.pipelinesAsked);
        // Four batches, all with rows that pass, and no operator asked for code of its own.
        assertEquals(4, fused.pipelineCalls);
        assertEquals(0, fused.asked);

        // Without estimates, only ALWAYS fuses.
        CountingCompiler unknown = new CountingCompiler();
        unknown.pipelines = true;
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.AUTO, 100, unknown)), plan));
        assertEquals(0, unknown.pipelinesAsked);
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 1000, unknown)), plan));
        assertEquals(1, unknown.pipelinesAsked);
        assertEquals(0, unknown.asked);
    }

    @Test
    void aDeclinedPipelineRunsAsOperators() {
        PlanNode plan = chain();
        String expected = run(executor(Codegen.off()), plan);
        CountingCompiler compiler = new CountingCompiler();
        compiler.pipelines = true;
        compiler.declinePipelines = true;
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 0, compiler)), plan));
        assertEquals(1, compiler.pipelinesAsked);
        // The filter and the computed column, each compiled by its operator.
        assertEquals(2, compiler.asked);
    }

    @Test
    void aChainWithOneStageThatComputesIsNoPipeline() {
        CountingCompiler compiler = new CountingCompiler();
        compiler.pipelines = true;
        Executor always = executor(Codegen.of(CodegenMode.ALWAYS, 0, compiler));
        PlanNode filter = new PlanNode.Filter(table("t"), gt(col(T, 0), lit(0)));
        PlanNode renamed = new PlanNode.Project(filter, List.of(new NamedExpression("x", col(T, 1))));
        PlanNode computed = new PlanNode.Project(table("t"), List.of(new NamedExpression("s", add(col(T, 0),
                col(T, 1)))));
        for (PlanNode plan : List.of(filter, renamed, computed)) {
            assertEquals(run(executor(Codegen.off()), plan), run(always, plan));
        }
        assertEquals(0, compiler.pipelinesAsked);
        assertNull(PipelinePlanner.plan(renamed, Codegen.of(CodegenMode.ALWAYS, 0, compiler), node -> 1e9));
        PipelinePlanner.Pipeline p = PipelinePlanner.plan(chain(), Codegen.of(CodegenMode.ALWAYS, 0, compiler),
                node -> Double.NaN);
        assertEquals(table("t"), p.source());
        assertEquals(2, p.spec().stages().size());
    }

    @Test
    void anErrorInAPipelineNamesTheNodeOfItsStage() {
        CountingCompiler compiler = new CountingCompiler();
        compiler.pipelines = true;
        compiler.failInPipeline = true;
        QueryException e = assertThrows(QueryException.class, () -> run(executor(Codegen.of(CodegenMode.ALWAYS, 0,
                compiler)), chain()));
        // The filter is the first stage; the projection on top is #0.
        assertEquals("Filter#1", e.operator());
        assertEquals("broken in Filter#1", e.getMessage());
    }

    @Test
    void filterAndProjectionCompileFromTheThreshold() {
        PlanNode plan = new PlanNode.Project(new PlanNode.Filter(table("t"), gt(col(T, 0), lit(0))),
                List.of(new NamedExpression("s", add(col(T, 0), col(T, 1))), new NamedExpression("b", col(T, 1))));
        String expected = run(executor(Codegen.off()), plan);

        CountingCompiler always = new CountingCompiler();
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 1000, always)), plan));
        // The filter and the computed column are compiled once each; the plain column is not. All four batches
        // keep rows.
        assertEquals(2, always.asked);
        assertEquals(4 + 4, always.calls);

        CountingCompiler auto = new CountingCompiler();
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.AUTO, 4, auto)), plan));
        // The filter has seen 6 rows before its third batch, so it compiles for the last two; so does the
        // projection, which has seen 2 + 3 rows by then.
        assertEquals(2, auto.asked);
        assertEquals(2 + 2, auto.calls);

        CountingCompiler high = new CountingCompiler();
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.AUTO, 1000, high)), plan));
        assertEquals(0, high.asked);
    }

    @Test
    void declinedOrFailedCompilationKeepsInterpreting() {
        PlanNode plan = new PlanNode.Filter(table("t"), gt(col(T, 1), lit(1)));
        String expected = run(executor(Codegen.off()), plan);
        CountingCompiler declining = new CountingCompiler();
        declining.decline = true;
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 0, declining)), plan));
        assertEquals(1, declining.asked);
        CountingCompiler failing = new CountingCompiler();
        failing.fail = true;
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 0, failing)), plan));
        assertEquals(1, failing.asked);
        assertEquals(0, failing.calls);
    }

    @Test
    void joinSwitchesKeysWhileProbing() {
        PlanNode plan = new PlanNode.Join(table("l"), table("r"), List.of(1), List.of(1), JoinType.FULL);
        String expected = run(executor(Codegen.off()), plan);
        CountingCompiler auto = new CountingCompiler();
        // The build side has 10 rows: hashed by the interpreter; from the second probe batch on, with the
        // compiled functions, which must find the same matches.
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.AUTO, 12, auto)), plan));
        assertEquals(1, auto.asked);
        assertEquals(3, auto.keyCalls);
        CountingCompiler always = new CountingCompiler();
        assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 0, always)), plan));
        assertEquals(1 + 4, always.keyCalls);
    }

    @Test
    void joinConditionCompilesFromTheThreshold() {
        Schema pair = T.concat(T);
        for (JoinType type : List.of(JoinType.INNER, JoinType.LEFT, JoinType.SEMI, JoinType.ANTI, JoinType.MARK)) {
            PlanNode plan = new PlanNode.Join(table("l"), table("r"), List.of(1), List.of(1), type,
                    gt(col(pair, 0), col(pair, 2)));
            String expected = run(executor(Codegen.off()), plan);
            CountingCompiler always = new CountingCompiler();
            assertEquals(expected, run(executor(Codegen.of(CodegenMode.ALWAYS, 0, always)), plan), type.name());
            // The keys and the condition are compiled once each; the condition runs for the probe batches with
            // candidates.
            assertEquals(2, always.asked, type.name());
            assertTrue(always.calls > 0, type.name());
        }
    }

    @Test
    void alwaysNeedsACompiler() {
        assertTrue(assertThrows(IllegalStateException.class, () -> Codegen.of(CodegenMode.ALWAYS, 0, null))
                .getMessage().contains("fyrql-codegen"));
        assertNull(Codegen.of(CodegenMode.OFF, 0, new CountingCompiler()).compiler());
        assertEquals(CodegenStatistics.NONE, Codegen.off().statistics());
    }
}
