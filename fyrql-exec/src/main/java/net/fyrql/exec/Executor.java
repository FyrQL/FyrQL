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

package net.fyrql.exec;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.compiled.CompiledPipeline;
import net.fyrql.exec.compiled.JoinTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private final SpillDirectory spill;

    /**
     * @param threads       the most threads on which one query runs at a time
     * @param preserveOrder whether rows that no sort orders come in the order of the parts of their scans, as
     *                      on one thread
     * @param spill         the directory to which operators spill when the budget is exhausted, or
     *                      {@code null} if they fail instead
     */
    public Executor(TableSource source, MemoryBudget budget, int batchSize, Codegen codegen, int threads,
            boolean preserveOrder, SpillDirectory spill) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads: " + threads);
        }
        this.source = source;
        this.budget = budget;
        this.batchSize = batchSize;
        this.codegen = codegen;
        this.threads = threads;
        this.preserveOrder = preserveOrder;
        this.spill = spill;
    }

    /**
     * Returns an executor that reads the tables from another source, with
     * the budget, the threads and the directory to spill to of this one.
     */
    public Executor withSource(TableSource source) {
        return new Executor(source, budget, batchSize, codegen, threads, preserveOrder, spill);
    }

    /**
     * An executor whose operators do not spill.
     *
     * @param threads       the most threads on which one query runs at a time
     * @param preserveOrder whether rows that no sort orders come in the order of the parts of their scans, as
     *                      on one thread
     */
    public Executor(TableSource source, MemoryBudget budget, int batchSize, Codegen codegen, int threads,
            boolean preserveOrder) {
        this(source, budget, batchSize, codegen, threads, preserveOrder, null);
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

    /** Returns the directory to which operators spill, or {@code null} if they do not spill. */
    public SpillDirectory spillDirectory() {
        return spill;
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
     * Returns the label of a node of a plan under which its operator reports
     * failures and counts what it does, such as {@code Filter#2}: the name
     * of its type and its id, see {@link Plans#ids}.
     *
     * @param ids the ids of the nodes of the plan
     */
    public static String label(PlanNode node, Map<PlanNode, Integer> ids) {
        return node.getClass().getSimpleName() + "#" + ids.get(node);
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
        if (context != QueryContext.NONE) {
            context.budget(budget);
        }
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
        /** The build sides of the joins of the copy of a chain, or of a pipeline, that is being built. */
        private Map<PlanNode.Join, JoinSide> tables;
        /** Whether the joins over these build sides emit their unmatched build rows themselves. */
        private boolean ownLeftovers;
        /** The operators opened already for nodes, which opening a node returns. */
        private final Map<PlanNode, Operator> substitutes = new IdentityHashMap<>();
        /** The buffers of the common tables around the node that is being opened, by their ids. */
        private final Map<Integer, CommonTableBuffer> commonTables = new HashMap<>();

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
                case PlanNode.CommonTable t -> {
                    unordered(t.input());
                    // The order of the rows matters as long as one scan of them keeps it.
                    if (Plans.nodes(t.input()).stream().allMatch(n -> !(n instanceof PlanNode.CommonTableScan s)
                            || s.id() != t.id() || unordered.contains(s))) {
                        unordered(t.definition());
                    }
                }
                default -> {
                }
            }
        }

        private String label(PlanNode node) {
            return Executor.label(node, ids);
        }

        Operator open(PlanNode node) {
            Operator substitute = substitutes.get(node);
            if (substitute != null) {
                return substitute;
            }
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
            return new ParallelSegment(label(top), top.schema(), chains, source, threads, ordered, budget, codegen,
                    spill);
        }

        /** Builds a copy of a chain over the scan of a worker and the build sides of the joins of the chain. */
        private Operator chain(PlanNode top, MorselScan scan, Map<PlanNode.Join, JoinSide> sides) {
            morselScan = scan;
            tables = sides;
            try {
                return open(top);
            } finally {
                morselScan = null;
                tables = null;
            }
        }

        /**
         * Opens the join of a copy of a chain, or of the stages of a
         * pipeline, over its build side read already; a join whose build side
         * went to disk emits its unmatched build rows itself.
         */
        private AbstractOperator join(String label, PlanNode.Join j) {
            JoinSide side = tables.get(j);
            boolean leftovers = ownLeftovers || side instanceof JoinSide.Spilled;
            return new HashJoinOperator(label, j, open(j.left()), side, leftovers, budget, codegen, batchSize,
                    !unordered.contains(j), spill);
        }

        /**
         * Builds the operators of a pipeline that does not run compiled since
         * a build side went to disk: over the source the pipeline opened
         * already and the build sides it read, whose joins emit their
         * unmatched build rows themselves.
         */
        private Operator interpreted(PlanNode top, PlanNode sourceNode, Operator input,
                Map<PlanNode.Join, JoinSide> sides) {
            Map<PlanNode.Join, JoinSide> outer = tables;
            boolean outerLeftovers = ownLeftovers;
            substitutes.put(sourceNode, input);
            tables = sides;
            ownLeftovers = true;
            try {
                AbstractOperator op = operator(top);
                op.attach(context);
                return op;
            } finally {
                substitutes.remove(sourceNode);
                tables = outer;
                ownLeftovers = outerLeftovers;
            }
        }

        /** Opens the pipeline that ends at a node, or returns {@code null} if there is none or it does not compile. */
        private AbstractOperator pipeline(PlanNode node) {
            PipelinePlanner.Pipeline p = PipelinePlanner.plan(node, codegen, estimate);
            if (p == null) {
                return null;
            }
            CompiledPipeline compiled = codegen.pipeline(p.spec(), context);
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
                    if (!(tables.get(p.spec().probes().get(k)) instanceof JoinSide.Table t)) {
                        // The compiled stages probe tables in memory only.
                        return null;
                    }
                    shared[k] = t.table();
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
            PlanNode sourceNode = p.source();
            return new PipelineOperator(label(node), p.spec(), labels, compiled, input, builds, budget, codegen,
                    spill, (in, sides) -> interpreted(node, sourceNode, in, sides));
        }

        private AbstractOperator operator(PlanNode node) {
            String label = label(node);
            return switch (node) {
                case PlanNode.Scan s -> morselScan != null ? morselScan : new ScanOperator(label, s, source);
                case PlanNode.Remote r -> new ScanOperator(label, r, source);
                case PlanNode.Filter f -> new FilterOperator(label, f, open(f.input()), codegen);
                case PlanNode.Project p -> new ProjectOperator(label, p, open(p.input()), codegen);
                case PlanNode.Join j -> tables != null ? join(label, j)
                        : new HashJoinOperator(label, j, open(j.left()), open(j.right()), budget, codegen,
                                batchSize, !unordered.contains(j), spill);
                case PlanNode.Aggregate a -> new HashAggregateOperator(label, a, open(a.input()), budget, batchSize,
                        codegen, threads, !unordered.contains(a), spill);
                case PlanNode.Sort s -> new SortOperator(label, s, open(s.input()), budget, batchSize,
                        threads, spill);
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
                        batchSize, threads, spill);
                case PlanNode.Values v -> new ValuesOperator(label, v, batchSize);
                case PlanNode.SetOperation s -> new SetOperationOperator(label, s, open(s.left()),
                        open(s.right()), budget, batchSize);
                case PlanNode.CommonTable t -> commonTable(label, t);
                case PlanNode.CommonTableScan s -> new CommonTableScanOperator(label, s, commonTables.get(s.id()));
            };
        }

        /**
         * Opens the definition of a common table, and then the input, whose
         * scans of the common table find its buffer.
         */
        private AbstractOperator commonTable(String label, PlanNode.CommonTable t) {
            Operator definition = open(t.definition());
            CommonTableBuffer buffer = new CommonTableBuffer(label, t.definition().schema(), budget, spill, context);
            // The ids are unique in the plan; the copies of chains that workers build later find the buffer too.
            commonTables.put(t.id(), buffer);
            Operator input;
            try {
                input = open(t.input());
            } catch (RuntimeException e) {
                definition.close();
                throw e;
            }
            return new CommonTableOperator(label, t, definition, buffer, input);
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
