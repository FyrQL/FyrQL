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

package net.fyrql.core.plan;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Helpers over plan trees: traversal, rebuilding, identities and printing. */
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
            case PlanNode.Values v -> v;
            case PlanNode.SetOperation s -> new PlanNode.SetOperation(children.get(0), children.get(1), s.kind(),
                    s.all());
        };
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
     * sort at the top, possibly below a filter, projection or limit.
     */
    public static boolean ordered(PlanNode plan) {
        return switch (plan) {
            case PlanNode.Sort s -> true;
            case PlanNode.Filter f -> ordered(f.input());
            case PlanNode.Project p -> ordered(p.input());
            case PlanNode.Limit l -> ordered(l.input());
            default -> false;
        };
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
            case PlanNode.Values v -> sb.append("Values ").append(v.rows().size()).append(" rows ")
                    .append(fields(v.schema()));
            case PlanNode.SetOperation s -> sb.append(s.kind()).append(s.all() ? " ALL" : "");
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
