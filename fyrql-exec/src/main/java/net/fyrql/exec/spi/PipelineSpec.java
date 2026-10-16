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

package net.fyrql.exec.spi;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A chain of plan nodes that a compiler may run as one loop over the rows
 * of each batch of a source: filters, projections and joins, each reading
 * the output of the one before it. A join reads it as its left input, the
 * side it probes; its right input, the build side, is a {@link JoinTable}
 * built before the source is read. A {@code RIGHT} or {@code FULL} join is
 * only the last stage, since its unmatched build rows come after the
 * source.
 *
 * @param input  the schema of the batches of the source
 * @param stages the nodes, from the one that reads the source to the one
 *               whose output the chain produces
 */
public record PipelineSpec(Schema input, List<PlanNode> stages) {

    public PipelineSpec {
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("A pipeline without stages");
        }
        for (int k = 0; k < stages.size(); k++) {
            PlanNode stage = stages.get(k);
            PlanNode in = switch (stage) {
                case PlanNode.Filter f -> f.input();
                case PlanNode.Project p -> p.input();
                case PlanNode.Join j -> {
                    if ((j.type() == JoinType.RIGHT || j.type() == JoinType.FULL) && k < stages.size() - 1) {
                        throw new IllegalArgumentException("A " + j.type() + " join below the last stage");
                    }
                    yield j.left();
                }
                default -> throw new IllegalArgumentException("Not a stage of a pipeline: " + stage.getClass()
                        .getSimpleName());
            };
            if (k > 0 && in != stages.get(k - 1)) {
                throw new IllegalArgumentException("Stage " + k + " does not read stage " + (k - 1));
            }
            if (k == 0 && !in.schema().types().equals(input.types())) {
                throw new IllegalArgumentException("The first stage reads " + in.schema() + ", the source is "
                        + input);
            }
        }
    }

    /** Returns the schema of the batches the chain produces. */
    public Schema output() {
        return stages.get(stages.size() - 1).schema();
    }

    /** Returns the joins of the chain, in the order of the stages: the order of the tables of a batch. */
    public List<PlanNode.Join> probes() {
        List<PlanNode.Join> joins = new ArrayList<>();
        for (PlanNode stage : stages) {
            if (stage instanceof PlanNode.Join j) {
                joins.add(j);
            }
        }
        return joins;
    }
}
