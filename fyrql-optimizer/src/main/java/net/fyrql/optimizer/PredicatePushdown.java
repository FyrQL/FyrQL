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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.expr.Expressions.Side;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Moves the conditions of filters down the plan, as close to the scans as
 * their columns allow, and turns an equality between the two inputs of an
 * inner or cross join into a key of the join. The pass goes down the plan
 * with the conjuncts that hold above a node, over its output, and each node
 * passes on what its inputs can take:
 * <ul>
 * <li>a filter puts its own conjuncts in front of those from above, so that
 * they are still evaluated first;</li>
 * <li>a projection passes a conjunct with its expressions put in for the
 * columns;</li>
 * <li>an inner or cross join passes a conjunct over one input to that input
 * and makes an equality between the inputs a key, in the common type of
 * both sides; a key that is no plain column of its input is computed by a
 * projection below the join, and a cross join with a key becomes an inner
 * join. A left join passes the conjuncts over its left input, a right join
 * those over its right input, a semi or anti join all of them, a full join
 * none;</li>
 * <li>an aggregation with group columns passes the conjuncts over them, and
 * a window the conjuncts over its partition columns, which keep or drop
 * whole partitions;</li>
 * <li>a sort passes everything, a union copies everything into each input,
 * {@code EXCEPT} and {@code INTERSECT} pass everything to their left input,
 * whose rows they keep, and an unnest passes the conjuncts that do not read
 * its elements;</li>
 * <li>a limit passes nothing.</li>
 * </ul>
 * What a node does not pass stays in a filter right above it. A conjunct
 * that calls {@code random} stays where it is, as does an equality of
 * nested values, which a join does not take as a key. The output of the
 * plan keeps its columns and types, and the rows are the same as without
 * the rewrite.
 */
final class PredicatePushdown {

    private static final Expression TRUE = Expressions.lit(true);
    private static final Expression FALSE = Expressions.lit(false);

    private PredicatePushdown() {
    }

    static PlanNode push(PlanNode plan) {
        return push(plan, List.of());
    }

    /** Pushes conjuncts over the output of a node into it; the result has the output of the node. */
    private static PlanNode push(PlanNode node, List<Expression> conjuncts) {
        return switch (node) {
            case PlanNode.Filter f -> {
                List<Expression> down = new ArrayList<>();
                List<Expression> stay = new ArrayList<>();
                for (Expression c : Expressions.conjuncts(f.condition())) {
                    (Expressions.isDeterministic(c) ? down : stay).add(c);
                }
                down.addAll(conjuncts);
                yield filter(push(f.input(), down), stay);
            }
            case PlanNode.Project p -> {
                List<Expression> down = new ArrayList<>();
                List<Expression> stay = new ArrayList<>();
                for (Expression c : conjuncts) {
                    if (Expressions.columns(c).stream()
                            .allMatch(i -> Expressions.isDeterministic(p.columns().get(i).expression()))) {
                        down.add(ConstantFolding.fold(Expressions.transform(c, x -> x instanceof Expression.Column col
                                ? p.columns().get(col.index()).expression() : x)));
                    } else {
                        stay.add(c);
                    }
                }
                yield filter(new PlanNode.Project(push(p.input(), down), p.columns()), stay);
            }
            case PlanNode.Join j -> join(j, conjuncts);
            case PlanNode.Aggregate a -> {
                List<Integer> groups = a.groupColumns();
                List<Expression> down = new ArrayList<>();
                List<Expression> stay = new ArrayList<>();
                for (Expression c : conjuncts) {
                    if (!groups.isEmpty() && Expressions.columns(c).nextSetBit(groups.size()) < 0) {
                        down.add(Expressions.transform(c, x -> x instanceof Expression.Column col
                                ? new Expression.Column(groups.get(col.index()), col.type()) : x));
                    } else {
                        stay.add(c);
                    }
                }
                yield filter(new PlanNode.Aggregate(push(a.input(), down), groups, a.aggregates()), stay);
            }
            case PlanNode.Sort s -> new PlanNode.Sort(push(s.input(), conjuncts), s.keys());
            case PlanNode.Limit l -> filter(new PlanNode.Limit(push(l.input(), List.of()), l.count(), l.offset()),
                    conjuncts);
            case PlanNode.Union u -> {
                List<PlanNode> inputs = new ArrayList<>();
                for (PlanNode in : u.inputs()) {
                    inputs.add(push(in, conjuncts));
                }
                yield new PlanNode.Union(inputs);
            }
            case PlanNode.SetOperation s -> new PlanNode.SetOperation(push(s.left(), conjuncts),
                    push(s.right(), List.of()), s.kind(), s.all());
            case PlanNode.Unnest u -> {
                int width = u.input().schema().size();
                List<Expression> down = new ArrayList<>();
                List<Expression> stay = new ArrayList<>();
                for (Expression c : conjuncts) {
                    (Expressions.columns(c).get(width) ? stay : down).add(c);
                }
                yield filter(new PlanNode.Unnest(push(u.input(), down), u.list(), u.name()), stay);
            }
            case PlanNode.Window w -> {
                // A conjunct over the partition columns keeps or drops whole partitions, which the calls compute
                // apart; any other conjunct would change the rows the calls of a partition see.
                BitSet partition = new BitSet();
                w.partition().forEach(partition::set);
                List<Expression> down = new ArrayList<>();
                List<Expression> stay = new ArrayList<>();
                for (Expression c : conjuncts) {
                    BitSet columns = Expressions.columns(c);
                    columns.andNot(partition);
                    (columns.isEmpty() ? down : stay).add(c);
                }
                yield filter(new PlanNode.Window(push(w.input(), down), w.partition(), w.order(), w.calls()), stay);
            }
            case PlanNode.Scan s -> filter(s, conjuncts);
            case PlanNode.Remote r -> filter(r, conjuncts);
            case PlanNode.Values v -> filter(v, conjuncts);
        };
    }

    private static PlanNode join(PlanNode.Join j, List<Expression> conjuncts) {
        int leftWidth = j.left().schema().size();
        int rightWidth = j.right().schema().size();
        JoinType type = j.type();
        boolean inner = type == JoinType.INNER || type == JoinType.CROSS;
        boolean toLeft = inner || type == JoinType.LEFT || type == JoinType.SEMI || type == JoinType.ANTI
                || type == JoinType.MARK || type == JoinType.SINGLE;
        boolean toRight = inner || type == JoinType.RIGHT;
        List<Expression> left = new ArrayList<>();
        List<Expression> right = new ArrayList<>();
        List<Expression> stay = new ArrayList<>();
        List<Expression> condition = new ArrayList<>();
        List<Expression> leftExtras = new ArrayList<>();
        List<Expression> rightExtras = new ArrayList<>();
        List<Integer> leftKeys = new ArrayList<>(j.leftKeys());
        List<Integer> rightKeys = new ArrayList<>(j.rightKeys());
        for (Expression c : conjuncts) {
            Side side = Expressions.side(c, leftWidth);
            Expression[] key = inner && side == Side.BOTH ? key(c, leftWidth) : null;
            if (side == Side.LEFT && toLeft) {
                left.add(c);
            } else if (side == Side.RIGHT && toRight) {
                right.add(Expressions.shift(c, 0, -leftWidth));
            } else if (key != null) {
                addKey(key, leftWidth, rightWidth, leftExtras, rightExtras, leftKeys, rightKeys);
            } else {
                stay.add(c);
            }
        }
        if (j.condition() != null) {
            // A conjunct of the condition over one input filters that input if the rows it drops come out of the
            // join in no other way. A MARK join keeps its conjuncts, which as filters or keys would change when
            // its mark is NULL.
            boolean leftMatched = inner || type == JoinType.SEMI || type == JoinType.RIGHT;
            boolean rightMatched = type != JoinType.RIGHT && type != JoinType.FULL && type != JoinType.MARK;
            for (Expression c : Expressions.conjuncts(j.condition())) {
                Side side = Expressions.side(c, leftWidth);
                Expression[] key = side == Side.BOTH && type != JoinType.MARK && Expressions.isDeterministic(c)
                        ? key(c, leftWidth) : null;
                if (!Expressions.isDeterministic(c)) {
                    condition.add(c);
                } else if (side == Side.LEFT && leftMatched) {
                    left.add(c);
                } else if (side == Side.RIGHT && rightMatched) {
                    right.add(Expressions.shift(c, 0, -leftWidth));
                } else if (key != null) {
                    addKey(key, leftWidth, rightWidth, leftExtras, rightExtras, leftKeys, rightKeys);
                } else if (type == JoinType.INNER) {
                    stay.add(c);
                } else {
                    condition.add(c);
                }
            }
        }
        PlanNode l = Plans.withKeyColumns(push(j.left(), left), leftExtras);
        PlanNode r = Plans.withKeyColumns(push(j.right(), right), rightExtras);
        // The computed keys sit between the columns of the two inputs and after those of the right one.
        int extra = leftExtras.size();
        List<Expression> shiftedCondition = new ArrayList<>();
        for (Expression c : condition) {
            shiftedCondition.add(Expressions.shift(c, leftWidth, extra));
        }
        PlanNode join = new PlanNode.Join(l, r, leftKeys, rightKeys, leftKeys.isEmpty() ? type
                : type == JoinType.CROSS ? JoinType.INNER : type,
                shiftedCondition.isEmpty() ? null : Expressions.conjunction(shiftedCondition));
        if (leftExtras.isEmpty() && rightExtras.isEmpty()) {
            return filter(join, stay);
        }
        List<Expression> shifted = new ArrayList<>();
        for (Expression c : stay) {
            shifted.add(Expressions.shift(c, leftWidth, extra));
        }
        Schema schema = j.schema();
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new NamedExpression(schema.name(i),
                    new Expression.Column(i < leftWidth ? i : i + extra, schema.type(i))));
        }
        return new PlanNode.Project(filter(join, shifted), columns);
    }

    private static void addKey(Expression[] key, int leftWidth, int rightWidth, List<Expression> leftExtras,
            List<Expression> rightExtras, List<Integer> leftKeys, List<Integer> rightKeys) {
        int l = column(key[0], leftWidth, leftExtras);
        int r = column(key[1], rightWidth, rightExtras);
        if (!keyOf(leftKeys, rightKeys, l, r)) {
            leftKeys.add(l);
            rightKeys.add(r);
        }
    }

    /**
     * The two sides of an equality between the inputs of a join, the right
     * one over the right input, both in their common type; {@code null} if
     * the conjunct is no such equality.
     */
    private static Expression[] key(Expression c, int leftWidth) {
        if (!(c instanceof Expression.Comparison cmp) || cmp.op() != Expression.ComparisonOp.EQ) {
            return null;
        }
        Side ls = Expressions.side(cmp.left(), leftWidth);
        Side rs = Expressions.side(cmp.right(), leftWidth);
        Expression l;
        Expression r;
        if (ls == Side.LEFT && rs == Side.RIGHT) {
            l = cmp.left();
            r = cmp.right();
        } else if (ls == Side.RIGHT && rs == Side.LEFT) {
            l = cmp.right();
            r = cmp.left();
        } else {
            return null;
        }
        DataType common = TypeRules.common(l.type(), r.type());
        if (common.isNested()) {
            return null;
        }
        return new Expression[] {as(l, common), Expressions.shift(as(r, common), 0, -leftWidth)};
    }

    private static Expression as(Expression e, DataType type) {
        return e.type().equals(type) ? e : new Expression.Cast(e, type);
    }

    /** The column of a key in its input: a plain column, or one computed after the columns of the input. */
    private static int column(Expression e, int width, List<Expression> extras) {
        if (e instanceof Expression.Column c) {
            return c.index();
        }
        int i = extras.indexOf(e);
        if (i < 0) {
            extras.add(e);
            i = extras.size() - 1;
        }
        return width + i;
    }

    private static boolean keyOf(List<Integer> leftKeys, List<Integer> rightKeys, int l, int r) {
        for (int i = 0; i < leftKeys.size(); i++) {
            if (leftKeys.get(i) == l && rightKeys.get(i) == r) {
                return true;
            }
        }
        return false;
    }

    /** A node filtered by conjuncts, or the node itself without any; {@code TRUE} drops out, {@code FALSE} decides. */
    private static PlanNode filter(PlanNode node, List<Expression> conjuncts) {
        List<Expression> kept = new ArrayList<>();
        for (Expression c : conjuncts) {
            if (c.equals(FALSE)) {
                return new PlanNode.Filter(node, FALSE);
            }
            if (!c.equals(TRUE)) {
                kept.add(c);
            }
        }
        return kept.isEmpty() ? node : new PlanNode.Filter(node, Expressions.conjunction(kept));
    }
}
