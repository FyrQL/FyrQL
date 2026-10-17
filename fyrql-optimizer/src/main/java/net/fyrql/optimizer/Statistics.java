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

package net.fyrql.optimizer;

import net.fyrql.connector.Connector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Estimates the number of rows of a plan from the row counts that the
 * catalogs know and fixed selectivities of the conditions. A table whose
 * catalog does not know its rows counts as {@value #UNKNOWN_ROWS} rows. A
 * condition keeps the share of the rows that {@link #selectivity} gives:
 * <ul>
 * <li>{@code =} 0.1, {@code <>} 0.9, {@code <}, {@code <=}, {@code >} and
 * {@code >=} 0.3, {@code BETWEEN} 0.25;</li>
 * <li>{@code IN} 0.1 per value, at most 0.5;</li>
 * <li>{@code LIKE} 0.1 with a pattern that starts with a fixed prefix,
 * otherwise 0.5;</li>
 * <li>{@code IS NULL} 0.05, {@code IS NOT NULL} 0.95;</li>
 * <li>{@code NOT} the rest of its operand, {@code AND} the product,
 * {@code OR} the share of either;</li>
 * <li>anything else 0.5.</li>
 * </ul>
 * An aggregation with groups keeps a tenth of its input, an unnest gives
 * {@value #UNNEST_FACTOR} rows per input row. The estimates are only
 * meant to compare plans with each other; the join order is chosen with
 * them.
 */
final class Statistics {

    /** The rows of a table whose catalog does not know them. */
    static final long UNKNOWN_ROWS = 1_000;

    /** The rows an unnest gives per input row. */
    static final int UNNEST_FACTOR = 4;

    private final Function<String, Connector> connectors;
    private final Map<TableRef, Long> tableRows = new HashMap<>();

    /** @param connectors the connector of a source, by its name, whose catalog knows the row counts */
    Statistics(Function<String, Connector> connectors) {
        this.connectors = connectors;
    }

    /** Returns the estimated number of rows of a plan. */
    double rows(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> s.pushed().isEmpty() ? tableRows(s.table()) : rows(s.logical());
            case PlanNode.Remote r -> rows(r.logical());
            case PlanNode.Filter f -> rows(f.input()) * selectivity(f.condition());
            case PlanNode.Project p -> rows(p.input());
            case PlanNode.Sort s -> rows(s.input());
            case PlanNode.Limit l -> Math.min(l.count(), Math.max(0, rows(l.input()) - l.offset()));
            case PlanNode.Aggregate a -> a.groupColumns().isEmpty() ? 1 : Math.max(1, rows(a.input()) / 10);
            case PlanNode.Join j -> join(j);
            case PlanNode.Union u -> u.inputs().stream().mapToDouble(this::rows).sum();
            case PlanNode.SetOperation s -> s.kind() == PlanNode.SetOperationKind.EXCEPT ? rows(s.left())
                    : Math.min(rows(s.left()), rows(s.right()));
            case PlanNode.Unnest u -> rows(u.input()) * UNNEST_FACTOR;
            case PlanNode.Window w -> rows(w.input());
            case PlanNode.Values v -> v.rows().size();
        };
    }

    /**
     * Returns the rows of a plan before its filters: the rows of the table
     * below filters, projections, sorts and the joins that keep each left
     * row at most once, otherwise the estimate of the plan. A join key that refers to the smaller of two such tables is
     * taken to be its primary key, see {@link JoinOrdering}.
     */
    double base(PlanNode node) {
        return switch (node) {
            case PlanNode.Scan s -> s.pushed().aggregation().isEmpty() && s.pushed().limit().isEmpty()
                    ? tableRows(s.table()) : rows(s);
            case PlanNode.Filter f -> base(f.input());
            case PlanNode.Project p -> base(p.input());
            case PlanNode.Sort s -> base(s.input());
            case PlanNode.Join j when j.type() == JoinType.SEMI || j.type() == JoinType.ANTI
                    || j.type() == JoinType.MARK || j.type() == JoinType.SINGLE -> base(j.left());
            default -> rows(node);
        };
    }

    private double join(PlanNode.Join j) {
        double left = rows(j.left());
        double right = rows(j.right());
        if (j.type() == JoinType.CROSS) {
            return left * right;
        }
        double inner = left * right / Math.max(1, Math.min(base(j.left()), base(j.right())))
                * (j.condition() == null ? 1 : selectivity(j.condition()));
        return switch (j.type()) {
            case LEFT -> Math.max(left, inner);
            case RIGHT -> Math.max(right, inner);
            case FULL -> Math.max(Math.max(left, right), inner);
            case SEMI, ANTI -> left / 2;
            case MARK, SINGLE -> left;
            default -> inner;
        };
    }

    private long tableRows(TableRef table) {
        return tableRows.computeIfAbsent(table, t -> {
            OptionalLong count = connectors.apply(t.source()).catalog().rowCount(t);
            return count.isPresent() ? count.getAsLong() : UNKNOWN_ROWS;
        });
    }

    /** Returns the share of the rows for which a condition holds, between 0 and 1. */
    static double selectivity(Expression condition) {
        double s = switch (condition) {
            case Expression.Literal l -> Boolean.TRUE.equals(l.value()) ? 1 : 0;
            case Expression.And a -> selectivity(a.left()) * selectivity(a.right());
            case Expression.Or o -> {
                double l = selectivity(o.left());
                double r = selectivity(o.right());
                yield l + r - l * r;
            }
            case Expression.Not n -> 1 - selectivity(n.operand());
            case Expression.Comparison c -> switch (c.op()) {
                case EQ -> 0.1;
                case NE -> 0.9;
                default -> 0.3;
            };
            case Expression.Between b -> 0.25;
            case Expression.In in -> Math.min(0.5, 0.1 * in.list().size());
            case Expression.Like l -> prefixed(l.pattern()) ? 0.1 : 0.5;
            case Expression.IsNull n -> n.negated() ? 0.95 : 0.05;
            case Expression.IsDistinct d -> d.negated() ? 0.1 : 0.9;
            default -> 0.5;
        };
        return Math.max(0, Math.min(1, s));
    }

    /** Tells whether a pattern is a constant that starts with a character that is no wildcard. */
    private static boolean prefixed(Expression pattern) {
        return pattern instanceof Expression.Literal l && l.type() == DataType.VARCHAR && l.value() instanceof String p
                && !p.isEmpty() && p.charAt(0) != '%' && p.charAt(0) != '_';
    }
}
