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
import net.fyrql.exec.spi.JoinTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Turns a logical plan into a tree of operators and runs it. Every node
 * becomes one operator, except the chains of filters, projections and joins
 * that {@link PipelinePlanner} fuses into one compiled pipeline; a
 * {@link QueryException} names the operator by its kind and pre-order id,
 * e.g. {@code Filter#2}, also for a node of a pipeline.
 * <p>
 * With more than one thread, each segment of the plan, see
 * {@link SegmentPlanner}, runs on workers over the splits of its scans,
 * each worker with its own copy of the operators of the segment, which
 * share the tables of the joins of the segment. The
 * batches of a segment come in the order of the splits, as on one thread,
 * unless the order does not matter: above the segment, up to the result or
 * the input of a node that orders or groups its rows, there are only
 * limits, unions, the left inputs of joins and nodes that work on each row
 * by itself, and the executor does not preserve the order. A scan whose
 * source sorts its rows always keeps its order. An aggregation over a
 * segment groups the rows on the workers and merges their groups, see
 * {@link HashAggregateOperator}; its groups come in the order of their first
 * rows under the same condition. A sort and a window sort on several
 * threads, and a window computes its partitions on them, see
 * {@link SortOperator} and {@link WindowOperator}.
 */
public final class Executor {

    /** The default number of rows per batch. */
    public static final int DEFAULT_BATCH_SIZE = 2048;

    /** The system property that sets the default number of threads of a query, a number from 1 on. */
    public static final String THREADS_PROPERTY = "fyrql.threads";

    private final TableSource source;
    private final MemoryBudget budget;
    private final int batchSize;
    private final Codegen codegen;
    private final int threads;
    private final boolean preserveOrder;

    /**
     * @param threads       the most threads on which one query runs at a time
     * @param preserveOrder whether rows that no sort orders come in the order of the parts of their scans, as
     *                      on one thread
     */
    public Executor(TableSource source, MemoryBudget budget, int batchSize, Codegen codegen, int threads,
            boolean preserveOrder) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads: " + threads);
        }
        this.source = source;
        this.budget = budget;
        this.batchSize = batchSize;
        this.codegen = codegen;
        this.threads = threads;
        this.preserveOrder = preserveOrder;
    }

    /** An executor that runs a query on one thread. */
    public Executor(TableSource source, MemoryBudget budget, int batchSize, Codegen codegen) {
        this(source, budget, batchSize, codegen, 1, true);
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

    /** Returns the most threads on which one query runs at a time. */
    public int threads() {
        return threads;
    }

    /** Tells whether rows that no sort orders come in the order of the parts of their scans. */
    public boolean preserveOrder() {
        return preserveOrder;
    }

    /**
     * Returns the number of threads of the system property
     * {@value #THREADS_PROPERTY}, or the number of processors if it is not set.
     *
     * @throws IllegalArgumentException if the property is no number from 1 on
     */
    public static int threadsFromSystemProperty() {
        String value = System.getProperty(THREADS_PROPERTY);
        if (value == null || value.isBlank()) {
            return Runtime.getRuntime().availableProcessors();
        }
        int threads;
        try {
            threads = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            threads = 0;
        }
        if (threads < 1) {
            throw new IllegalArgumentException("The system property " + THREADS_PROPERTY
                    + " must be a number from 1 on: " + value);
        }
        return threads;
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
        return new Opening(plan, estimate, context).open(plan);
    }

    /**
     * The opening of one plan: the ids of its nodes, the estimate of their
     * rows, the context of the query, the tops of the segments and the nodes
     * whose order does not matter.
     */
    private final class Opening {
        private final Map<PlanNode, Integer> ids;
        private final ToDoubleFunction<PlanNode> estimate;
        private final QueryContext context;
        private final Set<PlanNode> segments;
        private final Set<PlanNode> unordered = Collections.newSetFromMap(new IdentityHashMap<>());
        /** The scan of the copy of a chain that is being built, {@code null} outside of one. */
        private MorselScan morselScan;
        /** The tables of the joins of the copy of a chain that is being built. */
        private Map<PlanNode.Join, JoinTable> tables;

        Opening(PlanNode plan, ToDoubleFunction<PlanNode> estimate, QueryContext context) {
            this.ids = Plans.ids(plan);
            this.estimate = estimate;
            this.context = context;
            this.segments = threads > 1 ? SegmentPlanner.tops(plan) : Set.of();
            if (threads > 1 && !preserveOrder) {
                unordered(plan);
            }
        }

        /** Notes a node whose order does not matter, and the inputs whose order its own one is. */
        private void unordered(PlanNode node) {
            unordered.add(node);
            switch (node) {
                case PlanNode.Filter f -> unordered(f.input());
                case PlanNode.Project p -> unordered(p.input());
                case PlanNode.Unnest u -> unordered(u.input());
                case PlanNode.Limit l -> unordered(l.input());
                case PlanNode.Join j -> unordered(j.left());
                case PlanNode.Union u -> u.inputs().forEach(this::unordered);
                default -> {
                }
            }
        }

        private String label(PlanNode node) {
            return node.getClass().getSimpleName() + "#" + ids.get(node);
        }

        Operator open(PlanNode node) {
            AbstractOperator op;
            if (morselScan == null && segments.contains(node)) {
                op = segment(node);
            } else {
                AbstractOperator pipeline = pipeline(node);
                op = pipeline != null ? pipeline : operator(node);
            }
            op.attach(context);
            return op;
        }

        /**
         * Opens a segment and the build sides of the joins of its chains;
         * its workers build their copies of the chains when they are needed.
         */
        private AbstractOperator segment(PlanNode top) {
            List<PlanNode> inputs = top instanceof PlanNode.Union u ? u.inputs() : List.of(top);
            List<ParallelSegment.Chain> chains = new ArrayList<>();
            boolean ordered = !unordered.contains(top);
            for (PlanNode in : inputs) {
                PlanNode.Scan scan = SegmentPlanner.scan(in);
                List<ParallelSegment.Build> builds = new ArrayList<>();
                for (PlanNode.Join j : SegmentPlanner.joins(in)) {
                    builds.add(new ParallelSegment.Build(j, label(j), open(j.right())));
                }
                chains.add(new ParallelSegment.Chain(scan, label(scan), builds, (s, t) -> chain(in, s, t)));
                ordered |= !scan.pushed().order().isEmpty();
            }
            return new ParallelSegment(label(top), top.schema(), chains, source, threads, ordered, budget, codegen);
        }

        /** Builds a copy of a chain over the scan of a worker and the tables of the joins of the chain. */
        private Operator chain(PlanNode top, MorselScan scan, Map<PlanNode.Join, JoinTable> joinTables) {
            morselScan = scan;
            tables = joinTables;
            try {
                return open(top);
            } finally {
                morselScan = null;
                tables = null;
            }
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
            if (tables != null) {
                JoinTable[] shared = new JoinTable[p.spec().probes().size()];
                for (int k = 0; k < shared.length; k++) {
                    shared[k] = tables.get(p.spec().probes().get(k));
                }
                return new PipelineOperator(label(node), p.spec(), labels, compiled, open(p.source()), shared,
                        codegen);
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
                case PlanNode.Scan s -> morselScan != null ? morselScan : new ScanOperator(label, s, source);
                case PlanNode.Remote r -> new ScanOperator(label, r, source);
                case PlanNode.Filter f -> new FilterOperator(label, f, open(f.input()), codegen);
                case PlanNode.Project p -> new ProjectOperator(label, p, open(p.input()), codegen);
                case PlanNode.Join j -> tables != null
                        ? new HashJoinOperator(label, j, open(j.left()), tables.get(j), codegen)
                        : new HashJoinOperator(label, j, open(j.left()), open(j.right()), budget, codegen);
                case PlanNode.Aggregate a -> new HashAggregateOperator(label, a, open(a.input()), budget, batchSize,
                        codegen, threads, !unordered.contains(a));
                case PlanNode.Sort s -> new SortOperator(label, s, open(s.input()), budget, batchSize,
                        threads);
                case PlanNode.Limit l -> new LimitOperator(label, l, open(l.input()));
                case PlanNode.Union u -> {
                    List<Operator> inputs = new ArrayList<>();
                    for (PlanNode in : u.inputs()) {
                        inputs.add(open(in));
                    }
                    yield new UnionOperator(label, u, inputs);
                }
                case PlanNode.Unnest u -> new UnnestOperator(label, u, open(u.input()), batchSize);
                case PlanNode.Window w -> new WindowOperator(label, w, open(w.input()), budget,
                        batchSize, threads);
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
