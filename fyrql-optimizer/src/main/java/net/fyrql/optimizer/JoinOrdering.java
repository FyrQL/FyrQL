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
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Chooses the order of inner and cross joins. A component is a tree of
 * such joins, with the filters and the projections of plain columns between
 * them; its relations are the nodes below it, which are ordered on their
 * own. The pass collects the equalities of the component, from the join
 * keys and from the filters, into classes of columns that are equal in the
 * result, and estimates the rows of every set of relations with
 * {@link Statistics}: the product of the rows of the relations, divided,
 * for every class with {@code m} columns in the set, {@code m - 1} times by
 * the rows of the smallest table in the class (whose key is taken to be its
 * primary key, so that a key of a larger table referring to it has as many
 * values), times the selectivities of the other conditions over the set.
 * Two relations of the set in two or more of the same classes are joined
 * on a composite key: instead of the product of the rows of those classes,
 * their join divides by the rows of the smaller of the two tables, whose
 * key the composite key is taken to be, but by no fewer than the rows of
 * any one of the classes. So {@code lineitem} joined with {@code partsupp}
 * on part and supplier keeps the rows of {@code lineitem}, rather than a
 * share as small as if each key were unique on its own.
 * <p>
 * The cost of a tree is the sum of the rows its relations give and, over
 * its joins, of the rows of the result and of the rows of the right input,
 * which the hash join builds. With the connectors at hand, a connected set
 * of two or more relations of one source whose join that source runs as a
 * whole, see {@link Connector#planRemote}, costs only the rows of its
 * result, which is all that leaves the source; so the tables of a database
 * are joined with each other before the rest wherever that does not cost
 * more than it saves. The source is asked with the tree of the set as the
 * pass builds it, before {@link JoinPushdown} offers the final parts.
 * Dynamic programming over the connected sets of relations finds the
 * cheapest tree for up to {@value #EXHAUSTIVE} relations; larger
 * components are joined greedily, the pair with the smallest result first,
 * without regard to the sources. A join of two sets
 * builds the smaller one, which becomes the right input; sets without an
 * equality between them are joined only when nothing else is left, as cross
 * products, the largest first. Each join gets one key per class that it
 * connects, and every other condition stands right above the lowest join
 * that has all its relations; a projection restores the columns of the
 * component.
 * <p>
 * A component keeps its tree when every join already has a key for each
 * class it connects, its filters hold only conditions over two or more
 * relations that no key can take, and no tree is cheaper; so a plan that
 * was ordered before comes back unchanged.
 * <p>
 * A {@code SEMI} or {@code ANTI} join right above a component keeps or
 * drops a row by its left columns alone, as a filter does; when all the
 * left columns it reads come from one relation, it is moved onto that
 * relation before the component is ordered, so that it reduces the rows
 * before they are joined with the others.
 */
final class JoinOrdering {

    /** The most relations that are ordered by dynamic programming; more are joined greedily. */
    static final int EXHAUSTIVE = 10;

    /** The share by which another tree must be cheaper to replace the tree of a component. */
    private static final double TOLERANCE = 1e-9;

    private final Statistics statistics;
    private final Function<String, Connector> connectors;

    private JoinOrdering(Statistics statistics, Function<String, Connector> connectors) {
        this.statistics = statistics;
        this.connectors = connectors;
    }

    /** Orders the joins of a plan without regard to the sources of the tables. */
    static PlanNode order(PlanNode plan, Statistics statistics) {
        return order(plan, statistics, null);
    }

    /**
     * Orders the joins of a plan.
     *
     * @param connectors the connector of a source, by its name, which is asked whether it runs a join of its
     *        tables as a whole, or {@code null} to leave the sources out
     */
    static PlanNode order(PlanNode plan, Statistics statistics, Function<String, Connector> connectors) {
        return new JoinOrdering(statistics, connectors).order(plan);
    }

    private PlanNode order(PlanNode node) {
        if (inComponent(node)) {
            return new Component(node, List.of()).order();
        }
        PlanNode below = node;
        while (below instanceof PlanNode.Join j && (j.type() == JoinType.SEMI || j.type() == JoinType.ANTI)) {
            below = j.left();
        }
        if (below != node && inComponent(below)) {
            List<PlanNode.Join> filters = new ArrayList<>();
            for (PlanNode n = node; n != below; n = ((PlanNode.Join) n).left()) {
                PlanNode.Join j = (PlanNode.Join) n;
                filters.add(0, new PlanNode.Join(j.left(), order(j.right()), j.leftKeys(), j.rightKeys(), j.type(),
                        j.condition()));
            }
            return new Component(below, filters).order();
        }
        List<PlanNode> children = new ArrayList<>();
        for (PlanNode child : node.children()) {
            children.add(order(child));
        }
        return children.equals(node.children()) ? node : Plans.withChildren(node, children);
    }

    /** An inner or cross join; one with a condition, which predicate pushdown turns into a filter, is left alone. */
    private static boolean isJoin(PlanNode node) {
        return node instanceof PlanNode.Join j && (j.type() == JoinType.INNER || j.type() == JoinType.CROSS)
                && j.condition() == null;
    }

    /** Tells whether a node belongs to the component of the joins below it. */
    private static boolean inComponent(PlanNode node) {
        return switch (node) {
            case PlanNode.Join j -> isJoin(j);
            case PlanNode.Filter f -> Expressions.isDeterministic(f.condition()) && inComponent(f.input());
            case PlanNode.Project p -> p.columns().stream().allMatch(c -> c.expression() instanceof Expression.Column)
                    && inComponent(p.input());
            default -> false;
        };
    }

    /** A join tree over the relations of a component, by their index; the right input of a join is built. */
    private sealed interface Tree {
        long mask();
    }

    private record Leaf(int relation) implements Tree {
        @Override
        public long mask() {
            return 1L << relation;
        }
    }

    private record Node(Tree left, Tree right) implements Tree {
        @Override
        public long mask() {
            return left.mask() | right.mask();
        }
    }

    /** The output columns of a part of a component, as columns of the component, and its tree. */
    private record Flat(List<Integer> columns, Tree tree) {
    }

    /** A join of the component as it is: its inputs and its keys, as pairs of columns of the component. */
    private record ExistingJoin(long left, long right, List<int[]> keys) {
    }

    /** A condition over two or more relations that no key takes. */
    private record Residual(Expression condition, long mask, double selectivity) {
    }

    /** A built part of a tree: its plan, its columns as columns of the component, and the equal columns per class. */
    private record Built(PlanNode node, List<Integer> columns, TreeMap<Integer, List<List<Integer>>> groups) {
    }

    private final class Component {

        private final PlanNode root;
        /** The {@code SEMI} and {@code ANTI} joins above the root, the lowest first, their right inputs ordered. */
        private final List<PlanNode.Join> filters;
        private final List<PlanNode> relations = new ArrayList<>();
        private final List<Integer> offsets = new ArrayList<>();
        /** Per column of the component: its type and its relation. */
        private final List<DataType> types = new ArrayList<>();
        private final List<Integer> relationOf = new ArrayList<>();
        private final List<Expression> conditions = new ArrayList<>();
        private final List<ExistingJoin> joins = new ArrayList<>();
        private final Set<Long> edges = new HashSet<>();
        private final Map<Long, Double> cards = new HashMap<>();

        private int[] classOf;
        private final List<List<Integer>> classes = new ArrayList<>();
        private double[] classRows;
        private long[] adjacent;
        private double[] base;
        private double[] rows;
        private final List<List<Expression>> local = new ArrayList<>();
        private final List<Expression> constants = new ArrayList<>();
        private final List<Residual> residuals = new ArrayList<>();
        /** Per relation: the source that may run joins of it, or {@code null}. */
        private String[] sources;
        /** Per set of relations: whether its source runs its join as a whole. */
        private final Map<Long, Boolean> runRemotely = new HashMap<>();
        private boolean placed = true;

        Component(PlanNode root, List<PlanNode.Join> filters) {
            this.root = root;
            this.filters = filters;
        }

        PlanNode order() {
            Flat flat = flatten(root);
            List<PlanNode.Join> above = attach(flat);
            int n = relations.size();
            if (n >= Long.SIZE) {
                return above(rebuild(root, new int[1]), above);
            }
            classify();
            Tree best = best();
            if (keepsItsTree() && cost(flat.tree()) <= cost(best) * (1 + TOLERANCE)) {
                return above(rebuild(root, new int[1]), above);
            }
            Built built = build(best, Collections.newSetFromMap(new IdentityHashMap<>()));
            PlanNode node = filter(built.node(), constants);
            return above(restore(node, built.columns(), flat.columns()), above);
        }

        /**
         * Moves each {@code SEMI} or {@code ANTI} join above the root onto
         * the relation that has all the left columns it reads, and returns
         * the joins that stay above, the lowest first. Such joins only drop
         * left rows, so their order does not matter.
         */
        private List<PlanNode.Join> attach(Flat flat) {
            List<PlanNode.Join> above = new ArrayList<>();
            int width = flat.columns().size();
            for (PlanNode.Join j : filters) {
                BitSet read = new BitSet();
                j.leftKeys().forEach(read::set);
                if (j.condition() != null) {
                    read.or(Expressions.columns(j.condition()).get(0, width));
                }
                int relation = -1;
                for (int i = read.nextSetBit(0); i >= 0 && relation != -2; i = read.nextSetBit(i + 1)) {
                    int r = relationOf.get(flat.columns().get(i));
                    relation = relation == -1 || relation == r ? r : -2;
                }
                if (relation < 0) {
                    above.add(j);
                    continue;
                }
                PlanNode target = relations.get(relation);
                int offset = offsets.get(relation);
                int local = target.schema().size();
                List<Integer> keys = new ArrayList<>();
                for (int k : j.leftKeys()) {
                    keys.add(flat.columns().get(k) - offset);
                }
                Expression condition = j.condition() == null ? null : Expressions.transform(j.condition(),
                        x -> x instanceof Expression.Column c ? new Expression.Column(c.index() < width
                                ? flat.columns().get(c.index()) - offset : local + c.index() - width, c.type()) : x);
                relations.set(relation, new PlanNode.Join(target, j.right(), keys, j.rightKeys(), j.type(), condition));
            }
            return above;
        }

        private static PlanNode above(PlanNode node, List<PlanNode.Join> joins) {
            PlanNode result = node;
            for (PlanNode.Join j : joins) {
                result = new PlanNode.Join(result, j.right(), j.leftKeys(), j.rightKeys(), j.type(), j.condition());
            }
            return result;
        }

        private Flat flatten(PlanNode node) {
            if (!inComponent(node)) {
                int relation = relations.size();
                PlanNode ordered = JoinOrdering.this.order(node);
                relations.add(ordered);
                offsets.add(types.size());
                List<Integer> columns = new ArrayList<>();
                for (DataType t : ordered.schema().types()) {
                    columns.add(types.size());
                    types.add(t);
                    relationOf.add(relation);
                }
                return new Flat(columns, new Leaf(relation));
            }
            return switch (node) {
                case PlanNode.Join j -> {
                    Flat l = flatten(j.left());
                    Flat r = flatten(j.right());
                    List<int[]> keys = new ArrayList<>();
                    for (int i = 0; i < j.leftKeys().size(); i++) {
                        int a = l.columns().get(j.leftKeys().get(i));
                        int b = r.columns().get(j.rightKeys().get(i));
                        keys.add(new int[] {a, b});
                        edges.add(edge(a, b));
                    }
                    joins.add(new ExistingJoin(l.tree().mask(), r.tree().mask(), keys));
                    List<Integer> columns = new ArrayList<>(l.columns());
                    columns.addAll(r.columns());
                    yield new Flat(columns, new Node(l.tree(), r.tree()));
                }
                case PlanNode.Filter f -> {
                    Flat in = flatten(f.input());
                    for (Expression c : Expressions.conjuncts(f.condition())) {
                        conditions.add(Expressions.transform(c, x -> x instanceof Expression.Column col
                                ? new Expression.Column(in.columns().get(col.index()), col.type()) : x));
                    }
                    yield in;
                }
                case PlanNode.Project p -> {
                    Flat in = flatten(p.input());
                    List<Integer> columns = new ArrayList<>();
                    for (NamedExpression c : p.columns()) {
                        columns.add(in.columns().get(((Expression.Column) c.expression()).index()));
                    }
                    yield new Flat(columns, in.tree());
                }
                default -> throw new IllegalStateException("Not part of a join component: " + node);
            };
        }

        /** The component as it was, with its relations ordered. */
        private PlanNode rebuild(PlanNode node, int[] next) {
            if (!inComponent(node)) {
                return relations.get(next[0]++);
            }
            List<PlanNode> children = new ArrayList<>();
            for (PlanNode child : node.children()) {
                children.add(rebuild(child, next));
            }
            return children.equals(node.children()) ? node : Plans.withChildren(node, children);
        }

        /** Builds the classes of equal columns and sorts the conditions of the filters. */
        private void classify() {
            int width = types.size();
            int[] parent = new int[width];
            for (int i = 0; i < width; i++) {
                parent[i] = i;
            }
            boolean[] keyed = new boolean[width];
            List<int[]> pairs = new ArrayList<>();
            for (ExistingJoin j : joins) {
                pairs.addAll(j.keys());
            }
            for (Expression c : conditions) {
                int[] pair = equality(c);
                if (pair != null) {
                    pairs.add(pair);
                    edges.add(edge(pair[0], pair[1]));
                }
            }
            for (int[] p : pairs) {
                parent[find(parent, p[0])] = find(parent, p[1]);
                keyed[p[0]] = true;
                keyed[p[1]] = true;
            }
            classOf = new int[width];
            Map<Integer, Integer> ids = new HashMap<>();
            for (int i = 0; i < width; i++) {
                if (!keyed[i]) {
                    classOf[i] = -1;
                    continue;
                }
                int id = ids.computeIfAbsent(find(parent, i), r -> {
                    classes.add(new ArrayList<>());
                    return classes.size() - 1;
                });
                classOf[i] = id;
                classes.get(id).add(i);
            }

            int n = relations.size();
            base = new double[n];
            rows = new double[n];
            adjacent = new long[n];
            sources = new String[n];
            for (int r = 0; r < n; r++) {
                base[r] = statistics.base(relations.get(r));
                rows[r] = statistics.rows(relations.get(r));
                local.add(new ArrayList<>());
                sources[r] = connectors == null ? null : JoinPushdown.source(relations.get(r));
            }
            classRows = new double[classes.size()];
            for (int k = 0; k < classes.size(); k++) {
                double min = Double.MAX_VALUE;
                long mask = 0;
                for (int c : classes.get(k)) {
                    min = Math.min(min, base[relationOf.get(c)]);
                    mask |= 1L << relationOf.get(c);
                }
                classRows[k] = Math.max(1, min);
                for (int c : classes.get(k)) {
                    adjacent[relationOf.get(c)] |= mask & ~(1L << relationOf.get(c));
                }
            }
            for (Expression c : conditions) {
                if (equality(c) != null) {
                    placed = false;
                    continue;
                }
                long mask = mask(c);
                if (mask == 0) {
                    constants.add(c);
                } else if (Long.bitCount(mask) == 1) {
                    int r = Long.numberOfTrailingZeros(mask);
                    local.get(r).add(c);
                    rows[r] *= Statistics.selectivity(c);
                    placed = false;
                } else {
                    residuals.add(new Residual(c, mask, Statistics.selectivity(c)));
                }
            }
            for (int r = 0; r < n; r++) {
                rows[r] = Math.max(1, rows[r]);
            }
        }

        /** The two columns of an equality between two relations that a key can take, or {@code null}. */
        private int[] equality(Expression c) {
            if (c instanceof Expression.Comparison cmp && cmp.op() == Expression.ComparisonOp.EQ
                    && cmp.left() instanceof Expression.Column a && cmp.right() instanceof Expression.Column b
                    && !relationOf.get(a.index()).equals(relationOf.get(b.index())) && a.type().equals(b.type())
                    && !a.type().isNested()) {
                return new int[] {a.index(), b.index()};
            }
            return null;
        }

        private long mask(Expression c) {
            long mask = 0;
            BitSet columns = Expressions.columns(c);
            for (int i = columns.nextSetBit(0); i >= 0; i = columns.nextSetBit(i + 1)) {
                mask |= 1L << relationOf.get(i);
            }
            return mask;
        }

        /**
         * Tells whether the tree of the component can stay: every join has a
         * key for each class it connects, and the filters hold no condition
         * that belongs to a relation or into a key.
         */
        private boolean keepsItsTree() {
            if (!placed) {
                return false;
            }
            for (ExistingJoin j : joins) {
                Set<Integer> keyed = new HashSet<>();
                for (int[] k : j.keys()) {
                    keyed.add(classOf[k[0]]);
                }
                for (int k = 0; k < classes.size(); k++) {
                    long mask = classMask(k);
                    if ((mask & j.left()) != 0 && (mask & j.right()) != 0 && !keyed.contains(k)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private long classMask(int k) {
            long mask = 0;
            for (int c : classes.get(k)) {
                mask |= 1L << relationOf.get(c);
            }
            return mask;
        }

        /** The estimated rows of a set of relations. */
        private double card(long mask) {
            Double known = cards.get(mask);
            if (known != null) {
                return known;
            }
            double result = 1;
            for (long m = mask; m != 0; m &= m - 1) {
                result *= rows[Long.numberOfTrailingZeros(m)];
            }
            long[] within = new long[classes.size()];
            for (int k = 0; k < classes.size(); k++) {
                int members = 0;
                for (int c : classes.get(k)) {
                    if ((mask & (1L << relationOf.get(c))) != 0) {
                        members++;
                        within[k] |= 1L << relationOf.get(c);
                    }
                }
                for (int i = 1; i < members; i++) {
                    result /= classRows[k];
                }
            }
            for (long a = mask; a != 0; a &= a - 1) {
                for (long b = a & (a - 1); b != 0; b &= b - 1) {
                    long pair = Long.lowestOneBit(a) | Long.lowestOneBit(b);
                    int shared = 0;
                    double product = 1;
                    double largest = 1;
                    for (int k = 0; k < classes.size(); k++) {
                        if ((within[k] & pair) == pair) {
                            shared++;
                            product *= classRows[k];
                            largest = Math.max(largest, classRows[k]);
                        }
                    }
                    if (shared > 1) {
                        double composite = Math.max(largest, Math.min(base[Long.numberOfTrailingZeros(a)],
                                base[Long.numberOfTrailingZeros(b)]));
                        result *= product / Math.min(product, composite);
                    }
                }
            }
            for (Residual r : residuals) {
                if ((r.mask() & ~mask) == 0) {
                    result *= r.selectivity();
                }
            }
            cards.put(mask, result);
            return result;
        }

        private double cost(Tree tree) {
            return switch (tree) {
                case Leaf l -> rows[l.relation()];
                case Node n -> runsRemotely(n) ? card(n.mask()) : cost(n, cost(n.left()), cost(n.right()));
            };
        }

        /**
         * Tells whether the source of all relations of a join runs it as a
         * whole; the answer for one tree of a set holds for the set.
         */
        private boolean runsRemotely(Node node) {
            long mask = node.mask();
            Boolean known = runRemotely.get(mask);
            if (known == null) {
                String source = sources[Long.numberOfTrailingZeros(mask)];
                boolean same = source != null;
                for (long m = mask; same && m != 0; m &= m - 1) {
                    same = source.equals(sources[Long.numberOfTrailingZeros(m)]);
                }
                known = same && connectors.apply(source)
                        .planRemote(build(node, Collections.newSetFromMap(new IdentityHashMap<>())).node())
                        .isPresent();
                runRemotely.put(mask, known);
            }
            return known;
        }

        /** The cost of a join from the costs of its inputs: the rows of the result and of the built input. */
        private double cost(Node node, double left, double right) {
            return left + right + card(node.mask()) + card(node.right().mask());
        }

        private boolean connected(long a, long b) {
            for (long m = a; m != 0; m &= m - 1) {
                if ((adjacent[Long.numberOfTrailingZeros(m)] & b) != 0) {
                    return true;
                }
            }
            return false;
        }

        /** A join of two trees that builds the smaller one. */
        private Node join(Tree a, Tree b) {
            return card(a.mask()) >= card(b.mask()) ? new Node(a, b) : new Node(b, a);
        }

        /** The cheapest tree: one per connected part, then cross products of the parts, the largest first. */
        private Tree best() {
            List<Tree> parts = new ArrayList<>();
            long rest = (1L << relations.size()) - 1;
            while (rest != 0) {
                long part = Long.lowestOneBit(rest);
                long before;
                do {
                    before = part;
                    for (long m = before; m != 0; m &= m - 1) {
                        part |= adjacent[Long.numberOfTrailingZeros(m)];
                    }
                } while (part != before);
                parts.add(Long.bitCount(part) <= EXHAUSTIVE ? exhaustive(part) : greedy(part));
                rest &= ~part;
            }
            parts.sort(Comparator.comparingDouble((Tree t) -> card(t.mask())).reversed());
            Tree result = parts.get(0);
            for (Tree t : parts.subList(1, parts.size())) {
                result = join(result, t);
            }
            return result;
        }

        /** The cheapest tree of a connected set, by dynamic programming over its connected subsets. */
        private Tree exhaustive(long part) {
            List<Long> subsets = new ArrayList<>();
            for (long s = part; s != 0; s = (s - 1) & part) {
                subsets.add(s);
            }
            subsets.sort(Comparator.comparingInt(Long::bitCount).thenComparingLong(s -> s));
            Map<Long, Tree> best = new HashMap<>();
            Map<Long, Double> costs = new HashMap<>();
            for (long s : subsets) {
                if (Long.bitCount(s) == 1) {
                    best.put(s, new Leaf(Long.numberOfTrailingZeros(s)));
                    costs.put(s, rows[Long.numberOfTrailingZeros(s)]);
                    continue;
                }
                long low = Long.lowestOneBit(s);
                Node chosen = null;
                double cheapest = 0;
                for (long a = (s - 1) & s; a != 0; a = (a - 1) & s) {
                    long b = s ^ a;
                    if ((a & low) == 0 || !best.containsKey(a) || !best.containsKey(b) || !connected(a, b)) {
                        continue;
                    }
                    Node node = join(best.get(a), best.get(b));
                    double c = cost(node, costs.get(node.left().mask()), costs.get(node.right().mask()));
                    if (chosen == null || c < cheapest) {
                        chosen = node;
                        cheapest = c;
                    }
                }
                if (chosen != null) {
                    best.put(s, chosen);
                    costs.put(s, runsRemotely(chosen) ? card(s) : cheapest);
                }
            }
            return best.get(part);
        }

        /** A tree of a connected set that joins the connected pair with the smallest result first. */
        private Tree greedy(long part) {
            List<Tree> trees = new ArrayList<>();
            for (long m = part; m != 0; m &= m - 1) {
                trees.add(new Leaf(Long.numberOfTrailingZeros(m)));
            }
            while (trees.size() > 1) {
                int x = -1;
                int y = -1;
                double smallest = 0;
                for (int i = 0; i < trees.size(); i++) {
                    for (int j = i + 1; j < trees.size(); j++) {
                        long a = trees.get(i).mask();
                        long b = trees.get(j).mask();
                        if (connected(a, b) && (x < 0 || card(a | b) < smallest)) {
                            x = i;
                            y = j;
                            smallest = card(a | b);
                        }
                    }
                }
                trees.set(x, join(trees.get(x), trees.get(y)));
                trees.remove(y);
            }
            return trees.get(0);
        }

        /** Builds the plan of a tree; {@code done} holds the residual conditions that already have their place. */
        private Built build(Tree tree, Set<Residual> done) {
            if (tree instanceof Leaf leaf) {
                int r = leaf.relation();
                PlanNode relation = relations.get(r);
                int offset = offsets.get(r);
                List<Integer> columns = new ArrayList<>();
                TreeMap<Integer, List<List<Integer>>> groups = new TreeMap<>();
                for (int i = 0; i < relation.schema().size(); i++) {
                    int c = offset + i;
                    columns.add(c);
                    if (classOf[c] >= 0) {
                        groups.computeIfAbsent(classOf[c], k -> new ArrayList<>()).add(List.of(c));
                    }
                }
                return new Built(filter(relation, localize(local.get(r), columns)), columns, groups);
            }
            Node node = (Node) tree;
            Built l = build(node.left(), done);
            Built r = build(node.right(), done);
            List<Integer> leftKeys = new ArrayList<>();
            List<Integer> rightKeys = new ArrayList<>();
            TreeMap<Integer, List<List<Integer>>> groups = new TreeMap<>(l.groups());
            for (Map.Entry<Integer, List<List<Integer>>> e : r.groups().entrySet()) {
                List<List<Integer>> left = l.groups().get(e.getKey());
                List<List<Integer>> right = e.getValue();
                if (left == null) {
                    groups.put(e.getKey(), right);
                    continue;
                }
                // Every group of one side becomes equal to the first group of the other.
                List<Integer> merged = new ArrayList<>();
                for (List<Integer> g : left) {
                    key(g, right.get(0), l, r, leftKeys, rightKeys);
                    merged.addAll(g);
                }
                for (int i = 0; i < right.size(); i++) {
                    if (i > 0) {
                        key(left.get(0), right.get(i), l, r, leftKeys, rightKeys);
                    }
                    merged.addAll(right.get(i));
                }
                groups.put(e.getKey(), List.of(merged));
            }
            List<Integer> columns = new ArrayList<>(l.columns());
            columns.addAll(r.columns());
            PlanNode join = new PlanNode.Join(l.node(), r.node(), leftKeys, rightKeys,
                    leftKeys.isEmpty() ? JoinType.CROSS : JoinType.INNER);
            List<Expression> here = new ArrayList<>();
            for (Residual res : residuals) {
                if ((res.mask() & ~node.mask()) == 0 && done.add(res)) {
                    here.add(res.condition());
                }
            }
            return new Built(filter(join, localize(here, columns)), columns, groups);
        }

        /** Adds the key that makes two groups of equal columns equal, an equality of the query if there is one. */
        private void key(List<Integer> left, List<Integer> right, Built l, Built r, List<Integer> leftKeys,
                List<Integer> rightKeys) {
            int a = left.get(0);
            int b = right.get(0);
            search:
            for (int x : left) {
                for (int y : right) {
                    if (edges.contains(edge(x, y))) {
                        a = x;
                        b = y;
                        break search;
                    }
                }
            }
            leftKeys.add(l.columns().indexOf(a));
            rightKeys.add(r.columns().indexOf(b));
        }

        /** Conditions over columns of the component, over the columns of a part instead. */
        private List<Expression> localize(List<Expression> conditions, List<Integer> columns) {
            Map<Integer, Integer> position = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                position.put(columns.get(i), i);
            }
            List<Expression> result = new ArrayList<>();
            for (Expression c : conditions) {
                result.add(Expressions.transform(c, x -> x instanceof Expression.Column col
                        ? new Expression.Column(position.get(col.index()), col.type()) : x));
            }
            return result;
        }

        /** The output of the component over a built plan: a projection, unless the plan has it already. */
        private PlanNode restore(PlanNode node, List<Integer> columns, List<Integer> output) {
            Schema schema = root.schema();
            Schema built = node.schema();
            List<NamedExpression> projection = new ArrayList<>();
            boolean same = output.size() == built.size();
            for (int i = 0; i < output.size(); i++) {
                int position = columns.indexOf(output.get(i));
                same &= position == i && schema.name(i).equals(built.name(i));
                projection.add(new NamedExpression(schema.name(i), new Expression.Column(position, schema.type(i))));
            }
            return same ? node : new PlanNode.Project(node, projection);
        }
    }

    private static long edge(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static PlanNode filter(PlanNode node, List<Expression> conditions) {
        return conditions.isEmpty() ? node : new PlanNode.Filter(node, Expressions.conjunction(conditions));
    }
}
