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

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Narrows every scan to the columns the nodes above it use, and drops the
 * columns of projections, the aggregates and the window calls that nothing
 * reads, and a window without a call in use. The pass goes down the plan
 * with what a node must keep of its output columns, and every node keeps
 * that and whatever its own keys and expressions need; on the way up,
 * column references are renumbered. The root keeps all its
 * columns, so the output of the plan is unchanged; the inputs of
 * {@code EXCEPT} and {@code INTERSECT} keep all of theirs, since the rows
 * are compared whole. A node keeps at least one column, so that no batch
 * is without columns. The definition of a common table keeps the columns
 * that any of its scans reads, whole.
 * <p>
 * Where the nodes above use a {@code STRUCT} column only to take fields
 * out of it by constant names, the pass may also narrow the column to
 * those fields: a scan then reads each field as a column of its own, if
 * its connector reads fields, and the expressions above refer to those
 * columns instead of taking the fields out. Of two fields of which one
 * contains the other, the outer one is read. Join, sort and group keys,
 * aggregates and the inputs of set operations use their columns whole; a
 * projection passes the fields used of one of its columns on to the column
 * it picks or takes a field out of, and then has a column per field.
 * <p>
 * A scan of which the connector has taken over parts is left as it is, and
 * so is a part of the plan that a source runs; the pass runs before both
 * are negotiated.
 */
final class ColumnPruning {

    private ColumnPruning() {
    }

    /** Prunes whole columns only. */
    static PlanNode prune(PlanNode plan) {
        return prune(plan, null);
    }

    /**
     * Prunes the columns of a plan.
     *
     * @param connectors the connectors to ask whether they read fields
     *        inside columns, or {@code null} to read whole columns only
     */
    static PlanNode prune(PlanNode plan, Function<String, Connector> connectors) {
        return new Pass(connectors).prune(plan, Need.all(plan.schema().size())).node();
    }

    /**
     * What a node must keep of its output columns: each column whole, or
     * only fields inside it, by their paths of field names.
     */
    private static final class Need {
        final BitSet whole = new BitSet();
        final Map<Integer, Set<List<String>>> fields = new TreeMap<>();

        static Need all(int width) {
            Need need = new Need();
            need.whole.set(0, width);
            return need;
        }

        static Need whole(BitSet columns) {
            Need need = new Need();
            need.whole.or(columns);
            return need;
        }

        void whole(int column) {
            whole.set(column);
            fields.remove(column);
        }

        /** Adds the field at a path inside a column; the whole column for an empty path. */
        void field(int column, List<String> path) {
            if (path.isEmpty()) {
                whole(column);
            } else if (!whole.get(column)) {
                fields.computeIfAbsent(column, c -> new LinkedHashSet<>()).add(List.copyOf(path));
            }
        }

        /** Adds what an expression uses: the fields it takes out of columns, and the other columns whole. */
        void add(Expression e) {
            Expressions.FieldAccess access = Expressions.fieldAccess(e);
            if (access.base() instanceof Expression.Column c) {
                field(c.index(), access.path());
                return;
            }
            for (Expression child : access.base().children()) {
                add(child);
            }
        }

        BitSet used() {
            BitSet used = (BitSet) whole.clone();
            for (int c : fields.keySet()) {
                used.set(c);
            }
            return used;
        }

        /** Tells whether only fields of a column are used, not the whole of it. */
        boolean onlyFields(int column) {
            return !whole.get(column) && fields.containsKey(column);
        }

        /** Adds what another need keeps. */
        void merge(Need other) {
            for (int c = other.whole.nextSetBit(0); c >= 0; c = other.whole.nextSetBit(c + 1)) {
                whole(c);
            }
            other.fields.forEach((c, paths) -> paths.forEach(path -> field(c, path)));
        }

        Need copy() {
            return slice(0, Integer.MAX_VALUE);
        }

        /** The need of the columns from {@code from} on, below {@code to}, numbered from 0. */
        Need slice(int from, int to) {
            Need part = new Need();
            part.whole.or(whole.get(from, to));
            for (Map.Entry<Integer, Set<List<String>>> e : fields.entrySet()) {
                if (e.getKey() >= from && e.getKey() < to) {
                    part.fields.put(e.getKey() - from, new LinkedHashSet<>(e.getValue()));
                }
            }
            return part;
        }
    }

    /**
     * A pruned node and where the old output columns are in its new output.
     *
     * @param mapping for each old output column, its new index, or -1 if
     *        it is gone or only fields of it are kept
     * @param fields for an old output column of which fields are kept as
     *        columns of their own, the new index of each, by its path
     */
    private record Pruned(PlanNode node, int[] mapping, Map<Integer, Map<List<String>, Integer>> fields) {

        Pruned(PlanNode node, int[] mapping) {
            this(node, mapping, Map.of());
        }

        /**
         * The field at a path inside an old output column, or the whole
         * column for an empty path, in the new output: the column of the
         * longest part of the path that is kept as a column, with the rest
         * of the fields taken out of it.
         */
        Expression reach(int column, List<String> path) {
            Map<List<String>, Integer> kept = fields.getOrDefault(column, Map.of());
            for (int length = path.size(); length > 0; length--) {
                Integer at = kept.get(path.subList(0, length));
                if (at != null) {
                    return Expressions.structField(column(at), path.subList(length, path.size()));
                }
            }
            if (mapping[column] < 0) {
                throw new IllegalStateException("The column " + column + " is in use, but was pruned");
            }
            return Expressions.structField(column(mapping[column]), path);
        }

        private Expression column(int index) {
            return new Expression.Column(index, node.schema().type(index));
        }

        /** Rewrites an expression over the old output into one over the new. */
        Expression rewrite(Expression e) {
            Expressions.FieldAccess access = Expressions.fieldAccess(e);
            if (access.base() instanceof Expression.Column c) {
                return reach(c.index(), access.path());
            }
            Expression base = access.base();
            List<Expression> children = new ArrayList<>();
            for (Expression child : base.children()) {
                children.add(rewrite(child));
            }
            return Expressions.structField(Expressions.withChildren(base, children), access.path());
        }
    }

    private static final class Pass {
        private final Function<String, Connector> connectors;
        /** The columns of the definitions of common tables that their scans read so far, by id. */
        private final Map<Integer, BitSet> commonColumns = new HashMap<>();

        Pass(Function<String, Connector> connectors) {
            this.connectors = connectors;
        }

        Pruned prune(PlanNode node, Need required) {
            return switch (node) {
                case PlanNode.Scan s -> scan(s, required);
                case PlanNode.CommonTable t -> commonTable(t, required);
                case PlanNode.CommonTableScan s -> commonTableScan(s, required);
                case PlanNode.Filter f -> {
                    Need need = required.copy();
                    need.add(f.condition());
                    Pruned in = prune(f.input(), need);
                    yield new Pruned(new PlanNode.Filter(in.node(), in.rewrite(f.condition())), in.mapping(),
                            in.fields());
                }
                case PlanNode.Project p -> project(p, required);
                case PlanNode.Aggregate a -> {
                    int groups = a.groupColumns().size();
                    int width = groups + a.aggregates().size();
                    BitSet used = required.used();
                    BitSet kept = new BitSet();
                    kept.set(0, groups);
                    for (int i = used.nextSetBit(groups); i >= 0 && i < width; i = used.nextSetBit(i + 1)) {
                        kept.set(i);
                    }
                    if (kept.isEmpty() && width > 0) {
                        // Grouping by nothing, and no aggregate in use: keep one, so that the row count is right.
                        kept.set(groups);
                    }
                    BitSet need = new BitSet();
                    for (int g : a.groupColumns()) {
                        need.set(g);
                    }
                    for (int i = kept.nextSetBit(groups); i >= 0; i = kept.nextSetBit(i + 1)) {
                        AggregateCall call = a.aggregates().get(i - groups);
                        if (call.column() >= 0) {
                            need.set(call.column());
                        }
                        for (SortKey k : call.orderBy()) {
                            need.set(k.column());
                        }
                    }
                    Pruned in = prune(a.input(), Need.whole(need));
                    List<AggregateCall> calls = new ArrayList<>();
                    for (int i = kept.nextSetBit(groups); i >= 0; i = kept.nextSetBit(i + 1)) {
                        calls.add(remap(a.aggregates().get(i - groups), in.mapping()));
                    }
                    if (width == 0) {
                        // Neither groups nor calls, as for a HAVING without either: a count, so that the one row
                        // has a column.
                        calls.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "count", null, List.of()));
                    }
                    yield new Pruned(new PlanNode.Aggregate(in.node(), remap(a.groupColumns(), in.mapping()), calls),
                            mapping(kept, width));
                }
                case PlanNode.Join j -> join(j, required);
                case PlanNode.Sort s -> {
                    Need need = required.copy();
                    for (SortKey k : s.keys()) {
                        need.whole(k.column());
                    }
                    Pruned in = prune(s.input(), need);
                    yield new Pruned(new PlanNode.Sort(in.node(), remapKeys(s.keys(), in.mapping())), in.mapping(),
                            in.fields());
                }
                case PlanNode.Limit l -> {
                    Pruned in = prune(l.input(), required);
                    yield new Pruned(new PlanNode.Limit(in.node(), l.count(), l.offset()), in.mapping(), in.fields());
                }
                case PlanNode.Union u -> {
                    BitSet kept = keep(required.used(), u.schema().size());
                    List<PlanNode> inputs = new ArrayList<>();
                    for (PlanNode in : u.inputs()) {
                        inputs.add(exactly(prune(in, Need.whole(kept)), kept, in.schema()));
                    }
                    yield new Pruned(new PlanNode.Union(inputs), mapping(kept, u.schema().size()));
                }
                case PlanNode.SetOperation s -> {
                    // EXCEPT and INTERSECT compare whole rows: the inputs keep every column.
                    BitSet all = new BitSet();
                    all.set(0, s.schema().size());
                    PlanNode left = exactly(prune(s.left(), Need.whole(all)), all, s.left().schema());
                    PlanNode right = exactly(prune(s.right(), Need.whole(all)), all, s.right().schema());
                    yield new Pruned(new PlanNode.SetOperation(left, right, s.kind(), s.all()),
                            identity(s.schema().size()));
                }
                case PlanNode.Unnest u -> {
                    // The elements stay, as their list decides the number of rows.
                    int width = u.input().schema().size();
                    Need need = required.slice(0, width);
                    need.add(u.list());
                    Pruned in = prune(u.input(), need);
                    int[] mapping = Arrays.copyOf(in.mapping(), width + 1);
                    mapping[width] = in.node().schema().size();
                    yield new Pruned(new PlanNode.Unnest(in.node(), in.rewrite(u.list()), u.name()), mapping,
                            in.fields());
                }
                case PlanNode.Window w -> window(w, required);
                case PlanNode.Values v -> new Pruned(v, identity(v.schema().size()));
                case PlanNode.Remote r -> new Pruned(r, identity(r.schema().size()));
            };
        }

        /**
         * A common table prunes its input first, whose scans of it note the
         * columns they read; then the definition keeps what all of them read,
         * and the scans read those columns where the pruned definition has
         * them.
         */
        private Pruned commonTable(PlanNode.CommonTable t, Need required) {
            commonColumns.put(t.id(), new BitSet());
            Pruned in = prune(t.input(), required);
            BitSet read = commonColumns.remove(t.id());
            Pruned definition = prune(t.definition(), Need.whole(read));
            PlanNode input = rescan(in.node(), t.id(), definition);
            return new Pruned(new PlanNode.CommonTable(t.id(), t.name(), definition.node(), input), in.mapping(),
                    in.fields());
        }

        /** A scan of a common table reads the columns required of it, whole, and at least one. */
        private Pruned commonTableScan(PlanNode.CommonTableScan s, Need required) {
            BitSet kept = keep(required.used(), s.columns().size());
            List<Integer> columns = new ArrayList<>();
            BitSet read = commonColumns.get(s.id());
            for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
                columns.add(s.columns().get(i));
                if (read != null) {
                    read.set(s.columns().get(i));
                }
            }
            return new Pruned(new PlanNode.CommonTableScan(s.id(), s.name(), columns, s.definition()),
                    mapping(kept, s.columns().size()));
        }

        /** Points the scans of a common table in a plan at the columns of its pruned definition. */
        private static PlanNode rescan(PlanNode node, int id, Pruned definition) {
            if (node instanceof PlanNode.CommonTableScan s && s.id() == id) {
                return new PlanNode.CommonTableScan(id, s.name(), remap(s.columns(), definition.mapping()),
                        definition.node());
            }
            List<PlanNode> children = new ArrayList<>();
            boolean changed = false;
            for (PlanNode child : node.children()) {
                PlanNode c = rescan(child, id, definition);
                changed |= c != child;
                children.add(c);
            }
            return changed ? Plans.withChildren(node, children) : node;
        }

        /**
         * A window keeps the calls in use, and of its input what is required
         * and what those calls read. Without a call in use, the window goes
         * away, as it neither adds nor drops rows.
         */
        private Pruned window(PlanNode.Window w, Need required) {
            int width = w.input().schema().size();
            Need need = required.slice(0, width);
            BitSet used = required.used();
            int[] mapping = new int[w.schema().size()];
            Arrays.fill(mapping, -1);
            List<WindowCall> kept = new ArrayList<>();
            for (int i = 0; i < w.calls().size(); i++) {
                if (used.get(width + i)) {
                    mapping[width + i] = kept.size();
                    kept.add(w.calls().get(i));
                }
            }
            if (kept.isEmpty()) {
                Pruned in = prune(w.input(), need);
                System.arraycopy(in.mapping(), 0, mapping, 0, width);
                return new Pruned(in.node(), mapping, in.fields());
            }
            for (int c : w.partition()) {
                need.whole(c);
            }
            for (SortKey k : w.order()) {
                need.whole(k.column());
            }
            for (WindowCall call : kept) {
                for (int c : call.arguments()) {
                    need.whole(c);
                }
                if (call.filter() >= 0) {
                    need.whole(call.filter());
                }
                AggregateCall a = call.aggregate();
                if (a != null && a.column() >= 0) {
                    need.whole(a.column());
                }
                for (SortKey k : a == null ? List.<SortKey>of() : a.orderBy()) {
                    need.whole(k.column());
                }
            }
            Pruned in = prune(w.input(), need);
            int[] m = in.mapping();
            List<WindowCall> calls = new ArrayList<>();
            for (WindowCall call : kept) {
                calls.add(new WindowCall(call.function(), call.aggregate() == null ? null : remap(call.aggregate(), m),
                        remap(call.arguments(), m), call.ignoreNulls(), call.filter() < 0 ? -1 : m[call.filter()],
                        call.frame(), call.name()));
            }
            System.arraycopy(m, 0, mapping, 0, width);
            for (int i = width; i < mapping.length; i++) {
                if (mapping[i] >= 0) {
                    mapping[i] += in.node().schema().size();
                }
            }
            return new Pruned(new PlanNode.Window(in.node(), remap(w.partition(), m), remapKeys(w.order(), m), calls),
                    mapping, in.fields());
        }

        private Pruned join(PlanNode.Join j, Need required) {
            int leftWidth = j.left().schema().size();
            Need needLeft = required.slice(0, leftWidth);
            Need needRight = j.type().keepsRight() ? required.slice(leftWidth, j.schema().size()) : new Need();
            for (int k : j.leftKeys()) {
                needLeft.whole(k);
            }
            for (int k : j.rightKeys()) {
                needRight.whole(k);
            }
            if (j.condition() != null) {
                Need need = new Need();
                need.add(j.condition());
                needLeft.merge(need.slice(0, leftWidth));
                needRight.merge(need.slice(leftWidth, Integer.MAX_VALUE));
            }
            Pruned left = prune(j.left(), needLeft);
            Pruned right = prune(j.right(), needRight);
            int newLeftWidth = left.node().schema().size();
            // Where the columns of both inputs are, as the condition reads them.
            int[] pairs = new int[leftWidth + j.right().schema().size()];
            for (int i = 0; i < pairs.length; i++) {
                if (i < leftWidth) {
                    pairs[i] = left.mapping()[i];
                } else {
                    int r = right.mapping()[i - leftWidth];
                    pairs[i] = r < 0 ? -1 : newLeftWidth + r;
                }
            }
            Map<Integer, Map<List<String>, Integer>> fields = new HashMap<>(left.fields());
            for (Map.Entry<Integer, Map<List<String>, Integer>> e : right.fields().entrySet()) {
                Map<List<String>, Integer> shifted = new HashMap<>();
                e.getValue().forEach((path, at) -> shifted.put(path, newLeftWidth + at));
                fields.put(leftWidth + e.getKey(), shifted);
            }
            Expression condition = null;
            if (j.condition() != null) {
                PlanNode both = new PlanNode.Join(left.node(), right.node(), List.of(), List.of(),
                        PlanNode.JoinType.CROSS);
                condition = new Pruned(both, pairs, fields).rewrite(j.condition());
            }
            PlanNode.Join join = new PlanNode.Join(left.node(), right.node(), remap(j.leftKeys(), left.mapping()),
                    remap(j.rightKeys(), right.mapping()), j.type(), condition);
            if (j.type().keepsRight()) {
                return new Pruned(join, pairs, fields);
            }
            int[] mapping = Arrays.copyOf(left.mapping(), j.schema().size());
            if (j.type() == PlanNode.JoinType.MARK) {
                mapping[leftWidth] = newLeftWidth;
            }
            return new Pruned(join, mapping, left.fields());
        }

        /**
         * A projection keeps the columns in use. Of a column of which only
         * fields are used and that picks a column or takes a field out of
         * one, it keeps a column per field instead, and passes the fields
         * on to its input.
         */
        private Pruned project(PlanNode.Project p, Need required) {
            int width = p.columns().size();
            BitSet kept = keep(required.used(), width);
            Need need = new Need();
            for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
                Expression e = p.columns().get(i).expression();
                Expressions.FieldAccess access = Expressions.fieldAccess(e);
                if (required.onlyFields(i) && access.base() instanceof Expression.Column c) {
                    for (List<String> path : required.fields.get(i)) {
                        need.field(c.index(), concat(access.path(), path));
                    }
                } else {
                    need.add(e);
                }
            }
            Pruned in = prune(p.input(), need);
            List<NamedExpression> columns = new ArrayList<>();
            int[] mapping = new int[width];
            Arrays.fill(mapping, -1);
            Map<Integer, Map<List<String>, Integer>> fields = new HashMap<>();
            for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
                NamedExpression c = p.columns().get(i);
                if (required.onlyFields(i)
                        && Expressions.fieldAccess(c.expression()).base() instanceof Expression.Column) {
                    Map<List<String>, Integer> at = new HashMap<>();
                    for (List<String> path : required.fields.get(i)) {
                        at.put(path, columns.size());
                        columns.add(new NamedExpression(c.name() + "." + String.join(".", path),
                                in.rewrite(Expressions.structField(c.expression(), path))));
                    }
                    fields.put(i, at);
                } else {
                    mapping[i] = columns.size();
                    columns.add(new NamedExpression(c.name(), in.rewrite(c.expression())));
                }
            }
            return new Pruned(new PlanNode.Project(in.node(), columns), mapping, fields);
        }

        /**
         * A scan reads the columns in use. Where only fields of a column are
         * used, it reads those fields if its connector reads fields, and the
         * whole column otherwise; a column that is a field already stays as
         * it is.
         */
        private Pruned scan(PlanNode.Scan s, Need required) {
            int width = s.columns().size();
            if (!s.pushed().isEmpty()) {
                return new Pruned(s, identity(width));
            }
            BitSet kept = keep(required.used(), width);
            // The paths to read as they are, and the fields that give way to an outer field read anyway.
            List<ColumnPath> exact = new ArrayList<>();
            List<ColumnPath> wanted = new ArrayList<>();
            for (int k = kept.nextSetBit(0); k >= 0; k = kept.nextSetBit(k + 1)) {
                ColumnPath path = new ColumnPath(s.columns().get(k), s.fields().get(k));
                if (connectors != null && required.onlyFields(k) && path.isWhole()) {
                    for (List<String> field : required.fields.get(k)) {
                        add(wanted, new ColumnPath(path.column(), field));
                    }
                } else {
                    add(exact, path);
                }
            }
            List<ColumnPath> read = new ArrayList<>(exact);
            for (ColumnPath p : wanted) {
                if (exact.stream().noneMatch(q -> contains(q, p))
                        && wanted.stream().noneMatch(q -> !q.equals(p) && contains(q, p))) {
                    add(read, p);
                }
            }
            if (!read.equals(exact)) {
                read = negotiate(s, read, exact);
            }
            List<Integer> columns = new ArrayList<>();
            List<List<String>> fields = new ArrayList<>();
            for (ColumnPath p : read) {
                columns.add(p.column());
                fields.add(p.fields());
            }
            int[] mapping = new int[width];
            Arrays.fill(mapping, -1);
            Map<Integer, Map<List<String>, Integer>> reached = new HashMap<>();
            for (int k = 0; k < width; k++) {
                List<String> prefix = s.fields().get(k);
                for (int j = 0; j < read.size(); j++) {
                    ColumnPath p = read.get(j);
                    if (p.column() != s.columns().get(k) || !startsWith(p.fields(), prefix)) {
                        continue;
                    }
                    if (p.fields().size() == prefix.size()) {
                        mapping[k] = j;
                    } else {
                        reached.computeIfAbsent(k, c -> new HashMap<>())
                                .put(p.fields().subList(prefix.size(), p.fields().size()), j);
                    }
                }
            }
            return new Pruned(new PlanNode.Scan(s.table(), s.tableSchema(), columns, fields, PlanNode.Pushed.NONE),
                    mapping, reached);
        }

        /**
         * Asks the connector of a scan to read fields: the paths, if it
         * reads them, or else the whole columns of the paths.
         *
         * @throws IllegalStateException if it answers with other columns, or
         *         no longer reads a field it read before
         */
        private List<ColumnPath> negotiate(PlanNode.Scan s, List<ColumnPath> read, List<ColumnPath> exact) {
            Connector connector = connectors.apply(s.table().source());
            ScanRequest request = new ScanRequest(read, List.of(), OptionalLong.empty(), List.of(), Optional.empty());
            ScanPlan plan = connector.plan(s.table(), request);
            if (plan.columns().equals(read)) {
                return read;
            }
            List<ColumnPath> whole = request.withWholeColumns().columns();
            if (!plan.columns().equals(whole) || exact.stream().anyMatch(p -> !p.isWhole())) {
                throw new IllegalStateException("The connector " + connector.name() + " breaks the rules of the "
                        + "scan negotiation for " + s.table() + ": it reads " + plan.columns() + " instead of "
                        + read);
            }
            return whole;
        }
    }

    private static void add(List<ColumnPath> paths, ColumnPath path) {
        if (!paths.contains(path)) {
            paths.add(path);
        }
    }

    /** Tells whether the value at one path contains the value at another, or is it. */
    private static boolean contains(ColumnPath outer, ColumnPath inner) {
        return outer.column() == inner.column() && startsWith(inner.fields(), outer.fields());
    }

    private static boolean startsWith(List<String> path, List<String> prefix) {
        return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> path = new ArrayList<>(first);
        path.addAll(second);
        return path;
    }

    /**
     * The input of a set operation, with exactly the kept columns: a
     * projection drops what the input kept beyond them.
     */
    private static PlanNode exactly(Pruned in, BitSet kept, Schema before) {
        if (Arrays.equals(in.mapping(), mapping(kept, before.size()))) {
            return in.node();
        }
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
            columns.add(new NamedExpression(before.name(i),
                    new Expression.Column(in.mapping()[i], before.type(i))));
        }
        return new PlanNode.Project(in.node(), columns);
    }

    /** The required columns within the width, or the first column if none is required. */
    private static BitSet keep(BitSet required, int width) {
        BitSet kept = required.get(0, width);
        if (kept.isEmpty() && width > 0) {
            kept.set(0);
        }
        return kept;
    }

    private static int[] mapping(BitSet kept, int width) {
        int[] mapping = new int[width];
        Arrays.fill(mapping, -1);
        int next = 0;
        for (int i = kept.nextSetBit(0); i >= 0; i = kept.nextSetBit(i + 1)) {
            mapping[i] = next++;
        }
        return mapping;
    }

    private static int[] identity(int width) {
        int[] mapping = new int[width];
        for (int i = 0; i < width; i++) {
            mapping[i] = i;
        }
        return mapping;
    }

    private static List<Integer> remap(List<Integer> columns, int[] mapping) {
        List<Integer> result = new ArrayList<>();
        for (int c : columns) {
            result.add(mapping[c]);
        }
        return result;
    }

    private static AggregateCall remap(AggregateCall call, int[] mapping) {
        return new AggregateCall(call.function(), call.column() < 0 ? -1 : mapping[call.column()], call.distinct(),
                call.name(), call.separator(), remapKeys(call.orderBy(), mapping));
    }

    private static List<SortKey> remapKeys(List<SortKey> keys, int[] mapping) {
        List<SortKey> result = new ArrayList<>();
        for (SortKey k : keys) {
            result.add(new SortKey(mapping[k.column()], k.ascending(), k.nullsFirst()));
        }
        return result;
    }
}
