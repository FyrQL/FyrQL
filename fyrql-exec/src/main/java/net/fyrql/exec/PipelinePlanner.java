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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.exec.spi.PipelineSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Finds the chains of a plan that run as one compiled pipeline: from a
 * filter, projection or join down through the filters, projections and
 * joins below it, as long as the compiler takes the chain, to the node the
 * chain reads, its source. A join is followed down its left input, the side
 * it probes; its right input, the build side, is opened on its own. A
 * {@code RIGHT} or {@code FULL} join is only the top of a chain. A chain is
 * fused if it holds a join, whose probe loop the operator runs interpreted,
 * or at least two nodes that compute something (a filter or a projection
 * with a column that is not an input column), since a single filter or
 * projection compiles as well as an operator, and if
 * {@link Codegen#fuses(double)} says so for the estimated rows of its
 * source. The executor and {@code explain} ask the same planner, so that
 * {@code explain} shows what runs.
 */
public final class PipelinePlanner {

    /**
     * A chain to fuse.
     *
     * @param spec   the stages
     * @param source the node whose output the first stage reads
     */
    public record Pipeline(PipelineSpec spec, PlanNode source) {
    }

    private PipelinePlanner() {
    }

    /**
     * Returns the pipeline whose last stage is a node, or {@code null} if the
     * node does not start one.
     *
     * @param top      the node whose output the pipeline produces
     * @param codegen  the code generation
     * @param estimate the estimated rows of a node, {@code NaN} if unknown
     */
    public static Pipeline plan(PlanNode top, Codegen codegen, ToDoubleFunction<PlanNode> estimate) {
        if (codegen.compiler() == null || input(top, top) == null) {
            return null;
        }
        List<PlanNode> stages = new ArrayList<>();
        PlanNode node = top;
        for (PlanNode in = input(node, top); in != null; in = input(node, top)) {
            List<PlanNode> candidate = new ArrayList<>();
            candidate.add(node);
            candidate.addAll(stages);
            if (!codegen.compilesPipeline(new PipelineSpec(in.schema(), candidate))) {
                break;
            }
            stages = candidate;
            node = in;
        }
        if (stages.stream().noneMatch(s -> s instanceof PlanNode.Join)
                && stages.stream().filter(PipelinePlanner::computes).count() < 2
                || !codegen.fuses(estimate.applyAsDouble(node))) {
            return null;
        }
        return new Pipeline(new PipelineSpec(node.schema(), stages), node);
    }

    /** Returns the input of a node that can be a stage of a chain with a top, or {@code null}. */
    private static PlanNode input(PlanNode node, PlanNode top) {
        return switch (node) {
            case PlanNode.Filter f -> f.input();
            case PlanNode.Project p -> p.input();
            case PlanNode.Join j -> j.type() != JoinType.RIGHT && j.type() != JoinType.FULL || j == top ? j.left()
                    : null;
            default -> null;
        };
    }

    /** Tells whether a stage computes something: a filter, a join, or a projection of more than input columns. */
    private static boolean computes(PlanNode stage) {
        return !(stage instanceof PlanNode.Project p) || p.columns().stream()
                .anyMatch(c -> !(c.expression() instanceof Expression.Column));
    }
}
