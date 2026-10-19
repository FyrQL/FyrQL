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

package net.fyrql.optimizer;

import net.fyrql.connector.Connector;
import net.fyrql.connector.RemotePlan;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.Plans;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Offers the parts of a plan that join tables of one source only to that
 * source, which may run each as a whole, see {@link Connector#planRemote}.
 * From the top, the pass looks for the largest part made of joins,
 * filters, projections, aggregations, sorts and limits over scans of one
 * source, with at least one join; if the source declines, the parts below
 * are offered. An accepted part becomes a {@link PlanNode.Remote}. The pass
 * runs before the scans are negotiated, so the scans of a part carry
 * nothing taken over yet.
 * <p>
 * A {@code SEMI} or {@code ANTI} join keeps or drops a row by its left
 * columns, as a filter does, so the conditions of a filter right below it
 * on the left may as well stand above it. When the source declines such a
 * join for conditions it cannot evaluate, they are moved above the join
 * and the join is offered again: the rows the source then sends are never
 * more than the scan of the left input would send, since the source could
 * not have filtered them either, and the right input stays in the source.
 */
final class JoinPushdown {

    private JoinPushdown() {
    }

    static PlanNode push(PlanNode plan, Function<String, Connector> connectors) {
        String source = source(plan);
        if (source != null && joins(plan)) {
            Connector connector = connectors.apply(source);
            Optional<RemotePlan> remote = connector.planRemote(plan);
            if (remote.isPresent()) {
                return remote(connector, source, plan, remote.get());
            }
            if (plan instanceof PlanNode.Join j && (j.type() == JoinType.SEMI || j.type() == JoinType.ANTI)
                    && j.left() instanceof PlanNode.Filter f && Expressions.isDeterministic(f.condition())) {
                PlanNode lifted = lift(j, f, connector, source);
                if (lifted != null) {
                    return lifted;
                }
            }
        }
        if (plan.children().isEmpty()) {
            return plan;
        }
        List<PlanNode> children = new ArrayList<>();
        for (PlanNode child : plan.children()) {
            children.add(push(child, connectors));
        }
        return Plans.withChildren(plan, children);
    }

    private static PlanNode remote(Connector connector, String source, PlanNode plan, RemotePlan remote) {
        if (!remote.output().types().equals(plan.schema().types())) {
            throw new IllegalStateException("The connector " + connector.name() + " plans the columns "
                    + remote.output() + " for a part of a plan of " + plan.schema());
        }
        return new PlanNode.Remote(source, plan, remote.description());
    }

    /**
     * Offers a declined semi or anti join again with the conditions of its
     * left filter that the source declines above it, or returns
     * {@code null} if there are none or the source declines the join anyway.
     */
    private static PlanNode lift(PlanNode.Join j, PlanNode.Filter f, Connector connector, String source) {
        List<Expression> below = new ArrayList<>();
        List<Expression> above = new ArrayList<>();
        for (Expression c : Expressions.conjuncts(f.condition())) {
            boolean accepted = connector.planRemote(j.withLeft(new PlanNode.Filter(f.input(), c))).isPresent();
            (accepted ? below : above).add(c);
        }
        if (above.isEmpty()) {
            return null;
        }
        PlanNode.Join join = j.withLeft(below.isEmpty() ? f.input()
                : new PlanNode.Filter(f.input(), Expressions.conjunction(below)));
        Optional<RemotePlan> remote = connector.planRemote(join);
        return remote.isEmpty() ? null : new PlanNode.Filter(remote(connector, source, join, remote.get()),
                Expressions.conjunction(above));
    }

    /**
     * The source of all scans of a part a source may run, or {@code null}
     * if the part has another kind of node, a scan with something taken
     * over or of fields inside columns, or scans of two sources.
     */
    static String source(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> s.pushed().isEmpty() && !s.readsFields() ? s.table().source() : null;
            case PlanNode.Join j -> {
                String left = source(j.left());
                yield left != null && left.equals(source(j.right())) ? left : null;
            }
            case PlanNode.Filter f -> source(f.input());
            case PlanNode.Project p -> source(p.input());
            case PlanNode.Aggregate a -> source(a.input());
            case PlanNode.Sort s -> source(s.input());
            case PlanNode.Limit l -> source(l.input());
            default -> null;
        };
    }

    private static boolean joins(PlanNode node) {
        return node instanceof PlanNode.Join || node.children().stream().anyMatch(JoinPushdown::joins);
    }
}
