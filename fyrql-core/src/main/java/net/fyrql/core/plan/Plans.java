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

package net.fyrql.core.plan;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * Helpers over plan trees: traversal, rebuilding, identities and printing.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class Plans {

    private Plans() {
    }

    /** Returns all nodes of a plan in pre-order; the part of a {@link PlanNode.Remote} is not among them. */
    public static List<PlanNode> nodes(PlanNode plan) {
        List<PlanNode> result = new ArrayList<>();
        collect(plan, result);
        return result;
    }

    private static void collect(PlanNode node, List<PlanNode> result) {
        result.add(node);
        for (PlanNode child : node.children()) {
            collect(child, result);
        }
    }

    /** Returns the scans of a plan, in pre-order, also those of the parts a source runs. */
    public static List<PlanNode.Scan> scans(PlanNode plan) {
        List<PlanNode.Scan> result = new ArrayList<>();
        for (PlanNode n : nodes(plan)) {
            if (n instanceof PlanNode.Scan s) {
                result.add(s);
            } else if (n instanceof PlanNode.Remote r) {
                result.addAll(scans(r.logical()));
            }
        }
        return result;
    }

    /** Rebuilds a node with new inputs. */
    public static PlanNode withChildren(PlanNode node, List<PlanNode> children) {
        return switch (node) {
            case PlanNode.Scan s -> s;
            case PlanNode.Remote r -> r;
            case PlanNode.Filter f -> new PlanNode.Filter(children.get(0), f.condition());
            case PlanNode.Project p -> new PlanNode.Project(children.get(0), p.columns());
            case PlanNode.Aggregate a -> new PlanNode.Aggregate(children.get(0), a.groupColumns(), a.aggregates());
            case PlanNode.Join j -> new PlanNode.Join(children.get(0), children.get(1), j.leftKeys(), j.rightKeys(),
                    j.type(), j.condition());
            case PlanNode.Sort s -> new PlanNode.Sort(children.get(0), s.keys());
            case PlanNode.Limit l -> new PlanNode.Limit(children.get(0), l.count(), l.offset());
            case PlanNode.Union u -> new PlanNode.Union(children);
            case PlanNode.Unnest u -> new PlanNode.Unnest(children.get(0), u.list(), u.name());
            case PlanNode.Window w -> new PlanNode.Window(children.get(0), w.partition(), w.order(), w.calls());
            case PlanNode.Values v -> v;
            case PlanNode.SetOperation s -> new PlanNode.SetOperation(children.get(0), children.get(1), s.kind(),
                    s.all());
            case PlanNode.CommonTable t -> new PlanNode.CommonTable(t.id(), t.name(), children.get(0),
                    children.get(1));
            case PlanNode.CommonTableScan s -> s;
            case PlanNode.RecursiveUnion r -> new PlanNode.RecursiveUnion(r.id(), r.name(), children.get(0),
                    children.get(1), r.all());
            case PlanNode.WorkingTableScan w -> w;
            case PlanNode.LateralJoin l -> new PlanNode.LateralJoin(l.id(), children.get(0), children.get(1), l.type());
        };
    }

    /**
     * Applies a mapping to the expressions of every node of a plan, see
     * {@link #mapExpressions}, from the leaves up; a plan the mapping does
     * not change comes back as it is.
     *
     * @param rebuilt takes each node of the plan and the node that takes its place, which may be the same
     */
    public static PlanNode mapAllExpressions(PlanNode node, UnaryOperator<Expression> mapping,
            BiConsumer<PlanNode, PlanNode> rebuilt) {
        List<PlanNode> children = new ArrayList<>();
        boolean changed = false;
        for (PlanNode child : node.children()) {
            PlanNode c = mapAllExpressions(child, mapping, rebuilt);
            changed |= c != child;
            children.add(c);
        }
        PlanNode result = changed ? withChildren(node, children) : node;
        List<Expression> before = expressions(result);
        PlanNode mapped = mapExpressions(result, mapping);
        if (!changed && expressions(mapped).equals(before)) {
            mapped = node;
        }
        rebuilt.accept(node, mapped);
        return mapped;
    }

    /** Tells whether a plan reads a value of the left row of a lateral join, which a source cannot. */
    public static boolean readsOuter(PlanNode plan) {
        for (PlanNode n : nodes(plan)) {
            for (Expression e : expressions(n)) {
                if (!Expressions.allMatch(e, x -> !(x instanceof Expression.Outer))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the columns of the left input of a lateral join that the {@link Expression.Outer}s of its id read. */
    public static BitSet outerSlots(PlanNode right, int id) {
        BitSet slots = new BitSet();
        for (PlanNode n : nodes(right)) {
            for (Expression e : expressions(n)) {
                Expressions.allMatch(e, x -> {
                    if (x instanceof Expression.Outer o && o.id() == id) {
                        slots.set(o.slot());
                    }
                    return true;
                });
            }
        }
        return slots;
    }

    /**
     * Gives each scan of a common table the definition of its
     * {@link PlanNode.CommonTable}, as it is after a rewrite of the plan;
     * a plan whose scans have them already comes back as it is.
     */
    public static PlanNode linkCommonTables(PlanNode plan) {
        return link(plan, new HashMap<>());
    }

    private static PlanNode link(PlanNode node, Map<Integer, PlanNode> definitions) {
        if (node instanceof PlanNode.CommonTableScan s) {
            PlanNode definition = definitions.get(s.id());
            return definition == null || definition == s.definition() ? s
                    : new PlanNode.CommonTableScan(s.id(), s.name(), s.columns(), definition);
        }
        if (node instanceof PlanNode.CommonTable t) {
            PlanNode definition = link(t.definition(), definitions);
            PlanNode outer = definitions.put(t.id(), definition);
            PlanNode input = link(t.input(), definitions);
            if (outer == null) {
                definitions.remove(t.id());
            } else {
                definitions.put(t.id(), outer);
            }
            return definition == t.definition() && input == t.input() ? t
                    : new PlanNode.CommonTable(t.id(), t.name(), definition, input);
        }
        List<PlanNode> children = new ArrayList<>();
        boolean changed = false;
        for (PlanNode child : node.children()) {
            PlanNode linked = link(child, definitions);
            changed |= linked != child;
            children.add(linked);
        }
        return changed ? withChildren(node, children) : node;
    }

    /**
     * Adds computed columns after the columns of a plan, through a
     * projection, named {@code #key0}, {@code #key1} and so on: the keys of
     * a join that are no plain columns. Without extras, the plan itself.
     */
    public static PlanNode withKeyColumns(PlanNode plan, List<Expression> extras) {
        if (extras.isEmpty()) {
            return plan;
        }
        Schema schema = plan.schema();
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new PlanNode.NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
        }
        for (int i = 0; i < extras.size(); i++) {
            columns.add(new PlanNode.NamedExpression("#key" + i, extras.get(i)));
        }
        return new PlanNode.Project(plan, columns);
    }

    /** Replaces a node, found by identity, and rebuilds the nodes above it. */
    public static PlanNode replace(PlanNode plan, PlanNode target, PlanNode replacement) {
        PlanNode result = replaceOrNull(plan, target, replacement);
        if (result == null) {
            throw new IllegalArgumentException("Not a node of the plan: " + target);
        }
        return result;
    }

    private static PlanNode replaceOrNull(PlanNode node, PlanNode target, PlanNode replacement) {
        if (node == target) {
            return replacement;
        }
        List<PlanNode> children = new ArrayList<>(node.children());
        for (int i = 0; i < children.size(); i++) {
            PlanNode c = replaceOrNull(children.get(i), target, replacement);
            if (c != null) {
                children.set(i, c);
                return withChildren(node, children);
            }
        }
        return null;
    }

    /** Applies a mapping to the expressions of a filter, projection, unnest or the condition of a join. */
    public static PlanNode mapExpressions(PlanNode node, UnaryOperator<Expression> mapping) {
        return switch (node) {
            case PlanNode.Filter f -> new PlanNode.Filter(f.input(), mapping.apply(f.condition()));
            case PlanNode.Unnest u -> new PlanNode.Unnest(u.input(), mapping.apply(u.list()), u.name());
            case PlanNode.Join j when j.condition() != null -> j.withCondition(mapping.apply(j.condition()));
            case PlanNode.Project p -> {
                List<PlanNode.NamedExpression> columns = new ArrayList<>();
                for (PlanNode.NamedExpression c : p.columns()) {
                    columns.add(new PlanNode.NamedExpression(c.name(), mapping.apply(c.expression())));
                }
                yield new PlanNode.Project(p.input(), columns);
            }
            default -> node;
        };
    }

    /**
     * Returns the expressions of a node: the filter condition, the projected
     * columns, the unnested list or the condition of a join.
     */
    public static List<Expression> expressions(PlanNode node) {
        return switch (node) {
            case PlanNode.Filter f -> List.of(f.condition());
            case PlanNode.Unnest u -> List.of(u.list());
            case PlanNode.Join j when j.condition() != null -> List.of(j.condition());
            case PlanNode.Project p -> p.columns().stream().map(PlanNode.NamedExpression::expression).toList();
            default -> List.of();
        };
    }

    /**
     * Tells whether the output of a plan has a defined order: only with a
     * sort at the top, possibly below a filter, projection, limit or
     * common table.
     */
    public static boolean ordered(PlanNode plan) {
        return switch (plan) {
            case PlanNode.Sort s -> true;
            case PlanNode.Filter f -> ordered(f.input());
            case PlanNode.Project p -> ordered(p.input());
            case PlanNode.Limit l -> ordered(l.input());
            case PlanNode.CommonTable t -> ordered(t.input());
            default -> false;
        };
    }

    /** A column of a table that an output column of a plan shows as it is. */
    public record Origin(TableRef table, String column) {
    }

    /**
     * Returns for each output column of a plan the column of a table it
     * shows as it is: through projections of columns, filters, sorts and
     * limits down to a scan of whole columns; {@code null} for a column
     * computed or read otherwise.
     */
    public static List<Origin> origins(PlanNode plan) {
        int width = plan.schema().size();
        List<Origin> result = new ArrayList<>();
        switch (plan) {
            case PlanNode.Scan s -> {
                for (int i = 0; i < s.columns().size(); i++) {
                    result.add(s.fields().get(i).isEmpty()
                            ? new Origin(s.table(), s.tableSchema().name(s.columns().get(i))) : null);
                }
            }
            case PlanNode.Project p -> {
                List<Origin> input = origins(p.input());
                for (PlanNode.NamedExpression e : p.columns()) {
                    result.add(e.expression() instanceof Expression.Column c ? input.get(c.index()) : null);
                }
            }
            case PlanNode.Filter f -> result.addAll(origins(f.input()));
            case PlanNode.Sort s -> result.addAll(origins(s.input()));
            case PlanNode.Limit l -> result.addAll(origins(l.input()));
            default -> {
                for (int i = 0; i < width; i++) {
                    result.add(null);
                }
            }
        }
        return result;
    }

    /** Assigns pre-order ids to the nodes of a plan, by identity. */
    public static Map<PlanNode, Integer> ids(PlanNode plan) {
        Map<PlanNode, Integer> ids = new IdentityHashMap<>();
        for (PlanNode n : nodes(plan)) {
            ids.put(n, ids.size());
        }
        return ids;
    }

    /** Prints a plan as an indented tree. */
    public static String print(PlanNode node) {
        return print(node, 0);
    }

    private static String print(PlanNode node, int depth) {
        StringBuilder sb = new StringBuilder("  ".repeat(depth));
        switch (node) {
            case PlanNode.Scan s -> {
                sb.append("Scan ").append(s.table()).append(' ').append(s.columns()).append(' ')
                        .append(fields(s.tableSchema()));
                if (s.readsFields()) {
                    sb.append(" fields ").append(s.fields());
                }
                if (!s.pushed().isEmpty()) {
                    sb.append(" pushed ").append(s.pushed());
                }
            }
            case PlanNode.Remote r -> {
                sb.append("Remote ").append(r.source()).append(' ').append(r.description()).append('\n');
                sb.append(print(r.logical(), depth + 2));
                return sb.toString();
            }
            case PlanNode.Filter f -> sb.append("Filter ").append(f.condition());
            case PlanNode.Project p -> {
                sb.append("Project");
                for (PlanNode.NamedExpression c : p.columns()) {
                    sb.append('\n').append("  ".repeat(depth + 2)).append(c.name()).append(" = ")
                            .append(c.expression());
                }
            }
            case PlanNode.Aggregate a -> sb.append("Aggregate groups ").append(a.groupColumns()).append(' ')
                    .append(a.aggregates());
            case PlanNode.Join j -> sb.append("Join ").append(j.type()).append(' ').append(j.leftKeys())
                    .append(" = ").append(j.rightKeys())
                    .append(j.condition() == null ? "" : " ON " + j.condition());
            case PlanNode.Sort s -> sb.append("Sort ").append(s.keys());
            case PlanNode.Limit l -> sb.append("Limit ").append(l.count()).append(" offset ").append(l.offset());
            case PlanNode.Union u -> sb.append("Union");
            case PlanNode.Unnest u -> sb.append("Unnest ").append(u.list()).append(" AS ").append(u.name());
            case PlanNode.Window w -> sb.append("Window partition ").append(w.partition()).append(" order ")
                    .append(w.order()).append(' ').append(w.calls());
            case PlanNode.Values v -> sb.append("Values ").append(v.rows().size()).append(" rows ")
                    .append(fields(v.schema()));
            case PlanNode.SetOperation s -> sb.append(s.kind()).append(s.all() ? " ALL" : "");
            case PlanNode.CommonTable t -> sb.append("CommonTable ").append(t.name()).append(" #").append(t.id());
            case PlanNode.CommonTableScan s -> sb.append("CommonTableScan ").append(s.name()).append(" #")
                    .append(s.id()).append(' ').append(s.columns()).append(' ').append(fields(s.schema()));
            case PlanNode.RecursiveUnion r -> sb.append("RecursiveUnion ").append(r.name()).append(" #")
                    .append(r.id()).append(r.all() ? " ALL" : "");
            case PlanNode.WorkingTableScan w -> sb.append("WorkingTableScan ").append(w.name()).append(" #")
                    .append(w.id()).append(' ').append(fields(w.schema()));
            case PlanNode.LateralJoin l -> sb.append("LateralJoin ").append(l.type()).append(" #").append(l.id());
        }
        sb.append('\n');
        for (PlanNode child : node.children()) {
            sb.append(print(child, depth + 1));
        }
        return sb.toString();
    }

    private static String fields(Schema schema) {
        List<String> result = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            result.add(f.toString());
        }
        return "(" + String.join(", ", result) + ")";
    }
}
