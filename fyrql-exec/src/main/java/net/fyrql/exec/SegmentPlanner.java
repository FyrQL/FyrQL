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
import net.fyrql.core.plan.PlanNode.JoinType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Finds the segments of a plan, the parts that run on several threads: a
 * chain of nodes that work on each row by itself ({@code Filter},
 * {@code Project}, {@code Unnest}) and of joins, followed down their left
 * inputs, the sides they probe, over a scan, up to the first node that is
 * not one, or a {@code Union} of such chains without joins. A
 * {@code RIGHT} or {@code FULL} join is only the top of a chain, as in a
 * compiled pipeline, see {@link PipelinePlanner}. The workers of a segment
 * each run a copy of its chains over the splits of their scans, see
 * {@link TableSource#splits}, and share the build sides of its joins, which
 * are read once before; a scan with one split runs on the thread of the
 * query. A compiled pipeline is thus always inside one chain.
 */
public final class SegmentPlanner {

    private SegmentPlanner() {
    }

    /** Returns the top nodes of the segments of a plan, by identity. */
    public static Set<PlanNode> tops(PlanNode plan) {
        Set<PlanNode> tops = Collections.newSetFromMap(new IdentityHashMap<>());
        visit(plan, false, tops);
        return tops;
    }

    private static void visit(PlanNode node, boolean inChain, Set<PlanNode> tops) {
        boolean chain = inChain;
        if (!inChain && scan(node) != null) {
            tops.add(node);
            chain = true;
        }
        if (!inChain && node instanceof PlanNode.Union u
                && u.inputs().stream().allMatch(i -> scan(i, true, false) != null)) {
            tops.add(u);
            chain = true;
        }
        if (node instanceof PlanNode.Join j) {
            visit(j.left(), chain, tops);
            visit(j.right(), false, tops);
        } else {
            for (PlanNode child : node.children()) {
                visit(child, chain, tops);
            }
        }
    }

    /** Returns the scan at the bottom of a chain whose top is a node, or {@code null} if the node starts none. */
    static PlanNode.Scan scan(PlanNode node) {
        return scan(node, true, true);
    }

    /**
     * Returns the scan at the bottom of a chain through a node, or
     * {@code null}.
     *
     * @param top   whether the node is the top of the chain
     * @param joins whether the chain may hold joins
     */
    private static PlanNode.Scan scan(PlanNode node, boolean top, boolean joins) {
        return switch (node) {
            case PlanNode.Scan s -> s;
            case PlanNode.Filter f -> scan(f.input(), false, joins);
            case PlanNode.Project p -> scan(p.input(), false, joins);
            case PlanNode.Unnest u -> scan(u.input(), false, joins);
            case PlanNode.Join j -> joins && (top || !keepsUnmatchedRight(j)) ? scan(j.left(), false, true) : null;
            default -> null;
        };
    }

    /** Returns the joins of the chain whose top is a node, from the top down. */
    static List<PlanNode.Join> joins(PlanNode top) {
        List<PlanNode.Join> joins = new ArrayList<>();
        for (PlanNode node = top; !(node instanceof PlanNode.Scan); ) {
            node = switch (node) {
                case PlanNode.Filter f -> f.input();
                case PlanNode.Project p -> p.input();
                case PlanNode.Unnest u -> u.input();
                case PlanNode.Join j -> {
                    joins.add(j);
                    yield j.left();
                }
                default -> throw new IllegalArgumentException("No chain: " + node);
            };
        }
        return joins;
    }

    /** Tells whether a join emits the rows of its build side that never matched, at its end. */
    static boolean keepsUnmatchedRight(PlanNode.Join join) {
        return join.type() == JoinType.RIGHT || join.type() == JoinType.FULL;
    }
}
