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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.CompiledPipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Turns a logical plan into a tree of operators and runs it. Every node
 * becomes one operator, except the chains of filters, projections and joins
 * that {@link PipelinePlanner} fuses into one compiled pipeline; a
 * {@link QueryException} names the operator by its kind and pre-order id,
 * e.g. {@code Filter#2}, also for a node of a pipeline.
 */
public final class Executor {

    /** The default number of rows per batch. */
    public static final int DEFAULT_BATCH_SIZE = 2048;

    private final TableSource source;
    private final MemoryBudget budget;
    private final int batchSize;
    private final Codegen codegen;

    public Executor(TableSource source, MemoryBudget budget, int batchSize, Codegen codegen) {
        this.source = source;
        this.budget = budget;
        this.batchSize = batchSize;
        this.codegen = codegen;
    }

    /** An executor without code generation. */
    public Executor(TableSource source, MemoryBudget budget, int batchSize) {
        this(source, budget, batchSize, Codegen.off());
    }

    public Executor(TableSource source) {
        this(source, MemoryBudget.unlimited(), DEFAULT_BATCH_SIZE);
    }

    /** Returns the code generation. */
    public Codegen codegen() {
        return codegen;
    }

    /** Returns the batch size. */
    public int batchSize() {
        return batchSize;
    }

    /** Returns the memory budget. */
    public MemoryBudget budget() {
        return budget;
    }

    /**
     * Builds the operator tree of a plan without estimates of its rows, so
     * that only {@link CodegenMode#ALWAYS} fuses pipelines; the caller
     * closes it.
     */
    public Operator open(PlanNode plan) {
        return open(plan, node -> Double.NaN);
    }

    /**
     * Builds the operator tree of a plan, fusing the chains whose sources
     * have enough estimated rows, see {@link Codegen#fuses(double)}; the
     * caller closes it.
     *
     * @param estimate the estimated rows of a node of the plan, {@code NaN} if unknown
     */
    public Operator open(PlanNode plan, ToDoubleFunction<PlanNode> estimate) {
        return open(plan, estimate, QueryContext.NONE);
    }

    /**
     * Builds the operator tree of a plan as {@link #open(PlanNode, ToDoubleFunction)}
     * does, for a query that a context cancels; the caller closes it.
     */
    public Operator open(PlanNode plan, ToDoubleFunction<PlanNode> estimate, QueryContext context) {
        return new Opening(Plans.ids(plan), estimate, context).open(plan);
    }

    /** The opening of one plan: the ids of its nodes, the estimate of their rows and the context of the query. */
    private final class Opening {
        private final Map<PlanNode, Integer> ids;
        private final ToDoubleFunction<PlanNode> estimate;
        private final QueryContext context;

        Opening(Map<PlanNode, Integer> ids, ToDoubleFunction<PlanNode> estimate, QueryContext context) {
            this.ids = ids;
            this.estimate = estimate;
            this.context = context;
        }

        private String label(PlanNode node) {
            return node.getClass().getSimpleName() + "#" + ids.get(node);
        }

        Operator open(PlanNode node) {
            AbstractOperator pipeline = pipeline(node);
            AbstractOperator op = pipeline != null ? pipeline : operator(node);
            op.attach(context);
            return op;
        }

        /** Opens the pipeline that ends at a node, or returns {@code null} if there is none or it does not compile. */
        private AbstractOperator pipeline(PlanNode node) {
            PipelinePlanner.Pipeline p = PipelinePlanner.plan(node, codegen, estimate);
            if (p == null) {
                return null;
            }
            CompiledPipeline compiled = codegen.pipeline(p.spec());
            if (compiled == null) {
                return null;
            }
            List<String> labels = new ArrayList<>();
            for (PlanNode stage : p.spec().stages()) {
                labels.add(label(stage));
            }
            // In the order of the operators: the left input of a join before its right one.
            Operator input = open(p.source());
            List<Operator> builds = new ArrayList<>();
            for (PlanNode.Join j : p.spec().probes()) {
                builds.add(open(j.right()));
            }
            return new PipelineOperator(label(node), p.spec(), labels, compiled, input, builds, budget, codegen);
        }

        private AbstractOperator operator(PlanNode node) {
            String label = label(node);
            return switch (node) {
                case PlanNode.Scan s -> new ScanOperator(label, s, source);
                case PlanNode.Remote r -> new ScanOperator(label, r, source);
                case PlanNode.Filter f -> new FilterOperator(label, f, open(f.input()), codegen);
                case PlanNode.Project p -> new ProjectOperator(label, p, open(p.input()), codegen);
                case PlanNode.Join j -> new HashJoinOperator(label, j, open(j.left()), open(j.right()), budget,
                        codegen);
                case PlanNode.Aggregate a -> new HashAggregateOperator(label, a, open(a.input()), budget, batchSize,
                        codegen);
                case PlanNode.Sort s -> new SortOperator(label, s, open(s.input()), budget, batchSize);
                case PlanNode.Limit l -> new LimitOperator(label, l, open(l.input()));
                case PlanNode.Union u -> {
                    List<Operator> inputs = new ArrayList<>();
                    for (PlanNode in : u.inputs()) {
                        inputs.add(open(in));
                    }
                    yield new UnionOperator(label, u, inputs);
                }
                case PlanNode.Unnest u -> new UnnestOperator(label, u, open(u.input()), batchSize);
                case PlanNode.Values v -> new ValuesOperator(label, v, batchSize);
                case PlanNode.SetOperation s -> new SetOperationOperator(label, s, open(s.left()),
                        open(s.right()), budget, batchSize);
            };
        }
    }

    /** Runs a plan and collects all batches. */
    public List<Batch> execute(PlanNode plan) {
        List<Batch> batches = new ArrayList<>();
        try (Operator op = open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                batches.add(b);
            }
        }
        return batches;
    }

    /** Runs a plan and collects all rows as Java values. */
    public List<Object[]> rows(PlanNode plan) {
        List<Object[]> rows = new ArrayList<>();
        for (Batch b : execute(plan)) {
            rows.addAll(b.rows());
        }
        return rows;
    }
}
