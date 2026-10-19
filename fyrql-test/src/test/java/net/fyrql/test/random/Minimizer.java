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

package net.fyrql.test.random;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shrinks a query that shows a difference, step by step, as long as the
 * difference stays: each step tries smaller variants in turn and takes the
 * first that still shows it, until none does or the budget of tries is
 * spent.
 */
final class Minimizer {

    private Minimizer() {
    }

    /**
     * Shrinks a query.
     *
     * @param start the query that shows the difference
     * @param candidates the smaller variants of a query, the most promising first
     * @param reproduces whether a variant still shows the difference
     * @param budget the number of variants to try at most
     * @return the smallest query found
     */
    static <T> T minimize(T start, Function<T, List<T>> candidates, Predicate<T> reproduces, int budget) {
        T current = start;
        int tries = 0;
        boolean progress = true;
        while (progress && tries < budget) {
            progress = false;
            for (T candidate : candidates.apply(current)) {
                if (tries++ >= budget) {
                    break;
                }
                boolean shows;
                try {
                    shows = reproduces.test(candidate);
                } catch (RuntimeException e) {
                    shows = false;
                }
                if (shows) {
                    current = candidate;
                    progress = true;
                    break;
                }
            }
        }
        return current;
    }

    /**
     * The smaller variants of a plan: a subtree in place of the whole plan,
     * a node replaced by an input of the same column types, fewer columns
     * or aggregates at the top, smaller expressions, an unnest replaced by
     * its input with {@code NULL} elements, a call of a window or the whole
     * window replaced by {@code NULL}s, a scan replaced by the rows it
     * reads, and fewer rows of {@code VALUES}.
     *
     * @param rows the rows a scan reads
     */
    static List<PlanNode> candidates(PlanNode plan, Function<PlanNode.Scan, List<Object[]>> rows) {
        List<PlanNode> result = new ArrayList<>();
        List<PlanNode> nodes = Plans.nodes(plan);
        for (PlanNode n : nodes) {
            if (n != plan) {
                result.add(n);
            }
        }
        for (PlanNode n : nodes) {
            for (PlanNode child : n.children()) {
                if (child.schema().types().equals(n.schema().types())) {
                    add(result, () -> Plans.replace(plan, n, child));
                }
            }
            if (n instanceof PlanNode.Union u && u.inputs().size() > 2) {
                for (int i = 0; i < u.inputs().size(); i++) {
                    List<PlanNode> inputs = new ArrayList<>(u.inputs());
                    inputs.remove(i);
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Union(inputs)));
                }
            }
        }
        switch (plan) {
            case PlanNode.Project p when p.columns().size() > 1 -> {
                for (int i = 0; i < p.columns().size(); i++) {
                    List<PlanNode.NamedExpression> columns = new ArrayList<>(p.columns());
                    columns.remove(i);
                    result.add(new PlanNode.Project(p.input(), columns));
                }
            }
            case PlanNode.Aggregate a -> {
                for (int i = 0; i < a.aggregates().size(); i++) {
                    List<PlanNode.AggregateCall> calls = new ArrayList<>(a.aggregates());
                    calls.remove(i);
                    if (!calls.isEmpty() || !a.groupColumns().isEmpty()) {
                        result.add(new PlanNode.Aggregate(a.input(), a.groupColumns(), calls));
                    }
                }
                for (int i = 0; i < a.groupColumns().size(); i++) {
                    List<Integer> groups = new ArrayList<>(a.groupColumns());
                    groups.remove(i);
                    if (!groups.isEmpty() || !a.aggregates().isEmpty()) {
                        result.add(new PlanNode.Aggregate(a.input(), groups, a.aggregates()));
                    }
                }
            }
            default -> {
            }
        }
        for (PlanNode n : nodes) {
            if (n instanceof PlanNode.Filter f) {
                for (Expression e : smaller(f.condition())) {
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Filter(f.input(), e)));
                }
            } else if (n instanceof PlanNode.Project p) {
                for (int i = 0; i < p.columns().size(); i++) {
                    PlanNode.NamedExpression column = p.columns().get(i);
                    for (Expression e : smaller(column.expression())) {
                        List<PlanNode.NamedExpression> columns = new ArrayList<>(p.columns());
                        columns.set(i, new PlanNode.NamedExpression(column.name(), e));
                        add(result, () -> Plans.replace(plan, n, new PlanNode.Project(p.input(), columns)));
                    }
                }
            } else if (n instanceof PlanNode.Unnest u) {
                List<PlanNode.NamedExpression> columns = new ArrayList<>();
                for (int i = 0; i < u.input().schema().size(); i++) {
                    columns.add(new PlanNode.NamedExpression(u.input().schema().name(i),
                            new Expression.Column(i, u.input().schema().type(i))));
                }
                columns.add(new PlanNode.NamedExpression(u.name(), Expressions.nullOf(u.elementType())));
                add(result, () -> Plans.replace(plan, n, new PlanNode.Project(u.input(), columns)));
                for (Expression e : smaller(u.list())) {
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Unnest(u.input(), e, u.name())));
                }
            } else if (n instanceof PlanNode.Window w) {
                for (int i = -1; i < w.calls().size(); i++) {
                    int dropped = i;
                    add(result, () -> Plans.replace(plan, n, withoutCall(w, dropped)));
                }
            }
        }
        for (PlanNode n : nodes) {
            if (n instanceof PlanNode.Scan s) {
                add(result, () -> Plans.replace(plan, n, new PlanNode.Values(s.schema(), rows.apply(s))));
            } else if (n instanceof PlanNode.Values v && !v.rows().isEmpty()) {
                int size = v.rows().size();
                if (size > 1) {
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Values(v.schema(), v.rows().subList(0, size / 2))));
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Values(v.schema(), v.rows().subList(size / 2, size))));
                }
                for (int i = 0; i < size; i++) {
                    List<Object[]> fewer = new ArrayList<>(v.rows());
                    fewer.remove(i);
                    add(result, () -> Plans.replace(plan, n, new PlanNode.Values(v.schema(), fewer)));
                }
            }
        }
        return result;
    }

    /**
     * A window with {@code NULL} in place of the column of one call, or of
     * all calls without the window, as columns of the same types.
     *
     * @param dropped the call that goes, or -1 for all
     */
    private static PlanNode withoutCall(PlanNode.Window w, int dropped) {
        Schema in = w.input().schema();
        Schema out = w.schema();
        PlanNode base = w.input();
        if (dropped >= 0) {
            List<PlanNode.WindowCall> calls = new ArrayList<>(w.calls());
            calls.remove(dropped);
            base = new PlanNode.Window(w.input(), w.partition(), w.order(), calls);
        }
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            int call = i - in.size();
            Expression e = call < 0 ? new Expression.Column(i, in.type(i))
                    : dropped < 0 || call == dropped ? Expressions.nullOf(out.type(i))
                    : new Expression.Column(call < dropped ? i : i - 1, out.type(i));
            columns.add(new PlanNode.NamedExpression(out.name(i), e));
        }
        return new PlanNode.Project(base, columns);
    }

    /** The smaller variants of an expression of the same type: an operand in its place, or a scalar {@code NULL}. */
    static List<Expression> smaller(Expression e) {
        List<Expression> result = new ArrayList<>();
        if (!(e instanceof Expression.Literal) && ExpressionGenerator.plain(e.type())) {
            result.add(Expressions.nullOf(e.type()));
        }
        for (Expression child : e.children()) {
            if (child.type().equals(e.type())) {
                result.add(child);
            }
        }
        List<Expression> children = e.children();
        for (int i = 0; i < children.size(); i++) {
            for (Expression variant : smaller(children.get(i))) {
                List<Expression> changed = new ArrayList<>(children);
                changed.set(i, variant);
                try {
                    result.add(Expressions.withChildren(e, changed));
                } catch (IllegalArgumentException ex) {
                    // The variant does not fit its parent.
                }
            }
        }
        return result;
    }

    private static void add(List<PlanNode> result, java.util.function.Supplier<PlanNode> candidate) {
        try {
            result.add(candidate.get());
        } catch (IllegalArgumentException e) {
            // A replacement that does not fit the plan above it.
        }
    }
}
