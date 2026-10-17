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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.CompiledPipeline;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.JoinTable;
import net.fyrql.exec.spi.KeyFunctions;
import net.fyrql.exec.spi.PipelineSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a chain of stages as one compiled pipeline over the batches of its
 * source. Before the source is read, the build sides of the joins become
 * {@link JoinTable}s, from the top join down, as the operators would pull
 * them: the memory of each is reserved under the label of its join, and
 * below an empty build side of an {@code INNER} or {@code SEMI} join
 * nothing is built and the source is not read. Below joins without keys,
 * a batch of the source goes through the pipeline in steps that keep the
 * pairs of the joins within {@link JoinTable#MAX_PAIRS}. A {@code RIGHT} or
 * {@code FULL} join at the top emits its unmatched build rows at the end.
 * An error of a stage names the node of the stage, e.g. {@code Filter#3},
 * as the operator of that node would.
 */
final class PipelineOperator extends AbstractOperator {

    private final PipelineSpec spec;
    private final Operator input;
    private final CompiledPipeline pipeline;
    private final List<String> labels;
    private final List<PlanNode.Join> joins;
    private final List<String> joinLabels = new ArrayList<>();
    private final List<Operator> builds;
    private final MemoryBudget budget;
    private final Codegen codegen;

    private JoinTable[] tables;
    /** The rows of the source that go through the pipeline at once, see {@link JoinTable#stepRows}. */
    private int stepRows = Integer.MAX_VALUE;
    /** The steps of a batch of the source that pairs with too many build rows at once, still to be consumed. */
    private final ArrayDeque<Batch> steps = new ArrayDeque<>();
    private boolean sourceDone;
    private boolean leftoversDone;
    private long reserved;

    /**
     * @param label    the label of the last stage
     * @param spec     the stages
     * @param labels   the label of each stage
     * @param pipeline the compiled stages
     * @param input    the source
     * @param builds   the right input of each join, in the order of {@link PipelineSpec#probes()}
     * @param budget   the budget the build sides are reserved in
     * @param codegen  the code generation, for the key functions that hash the build sides
     */
    PipelineOperator(String label, PipelineSpec spec, List<String> labels, CompiledPipeline pipeline,
            Operator input, List<Operator> builds, MemoryBudget budget, Codegen codegen) {
        super(label, spec.output());
        this.spec = spec;
        this.input = input;
        this.pipeline = pipeline;
        this.labels = List.copyOf(labels);
        this.joins = spec.probes();
        this.builds = List.copyOf(builds);
        this.budget = budget;
        this.codegen = codegen;
        for (int k = 0; k < spec.stages().size(); k++) {
            if (spec.stages().get(k) instanceof PlanNode.Join) {
                joinLabels.add(labels.get(k));
            }
        }
    }

    @Override
    Batch compute() {
        if (tables == null) {
            buildTables();
        }
        while (!sourceDone) {
            Batch b = steps.poll();
            if (b == null) {
                b = input.next();
                if (b == null) {
                    sourceDone = true;
                    break;
                }
                if (b.rowCount() > stepRows) {
                    steps.addAll(Batches.split(b, stepRows));
                    b = steps.poll();
                }
            } else {
                checkCancelled();
            }
            Batch out;
            try {
                out = pipeline.consume(b, tables);
            } catch (QueryException e) {
                throw e;
            } catch (RuntimeException e) {
                int stage = pipeline.stage();
                throw failure(stage >= 0 && stage < labels.size() ? labels.get(stage) : label(), e);
            }
            if (out != null) {
                return out;
            }
        }
        if (!leftoversDone) {
            leftoversDone = true;
            PlanNode top = spec.stages().get(spec.stages().size() - 1);
            if (top instanceof PlanNode.Join j && (j.type() == JoinType.RIGHT || j.type() == JoinType.FULL)) {
                return tables[tables.length - 1].leftovers(j.left().schema(), j.schema());
            }
        }
        return null;
    }

    private void buildTables() {
        tables = new JoinTable[joins.size()];
        for (int p = joins.size() - 1; p >= 0; p--) {
            try {
                tables[p] = build(p);
            } catch (QueryException e) {
                throw e;
            } catch (RuntimeException e) {
                throw failure(joinLabels.get(p), e);
            }
            JoinType type = joins.get(p).type();
            if (tables[p].rowCount() == 0 && (type == JoinType.INNER || type == JoinType.SEMI)) {
                // As the hash join: without build rows these joins have no output, and what they probe is not read.
                sourceDone = true;
                return;
            }
        }
        // A row of the source pairs with every build row of each join without keys it passes.
        long pairs = 1;
        for (int p = 0; p < joins.size(); p++) {
            PlanNode.Join j = joins.get(p);
            if (j.leftKeys().isEmpty() && (j.condition() != null || j.type().keepsRight())) {
                pairs = Math.min(pairs * Math.max(1, tables[p].rowCount()), JoinTable.MAX_PAIRS + 1L);
            }
        }
        stepRows = JoinTable.stepRows(pairs);
    }

    /** Reads the right input of a join into its table; the hashes are those of the interpreter. */
    private JoinTable build(int p) {
        PlanNode.Join j = joins.get(p);
        Operator right = builds.get(p);
        List<Batch> batches = new ArrayList<>();
        for (Batch b = right.next(); b != null; b = right.next()) {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, joinLabels.get(p));
            reserved += bytes;
            batches.add(b);
        }
        int[] keys = j.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        KeyFunctions functions = InterpretedKeys.INSTANCE;
        if (keys.length > 0) {
            List<DataType> types = new ArrayList<>();
            for (int k : j.leftKeys()) {
                types.add(j.left().schema().type(k));
            }
            KeyFunctions compiled = codegen.keys(types);
            if (compiled != null) {
                functions = compiled;
            }
        }
        return new JoinTable(j.right().schema(), batches, keys, functions);
    }

    private static QueryException failure(String label, RuntimeException e) {
        return new QueryException(label, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
    }

    @Override
    public void close() {
        input.close();
        for (Operator build : builds) {
            build.close();
        }
        budget.release(reserved);
        reserved = 0;
    }
}
