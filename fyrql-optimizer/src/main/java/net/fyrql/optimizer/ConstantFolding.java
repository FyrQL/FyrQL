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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the constant parts of the expressions of a plan once, before
 * the query runs, and simplifies the logic around them:
 * <ul>
 * <li>a subexpression without columns and without {@code random} becomes
 * a literal of its type; if evaluating it fails, it stays as it is and
 * fails when it runs, for the rows that reach it;</li>
 * <li>{@code AND} drops a {@code TRUE} operand and is {@code FALSE} with a
 * {@code FALSE} one, {@code OR} the other way round, and {@code NOT NOT x}
 * is {@code x};</li>
 * <li>{@code CASE} and {@code if} drop the branches whose condition is a
 * constant {@code FALSE} or {@code NULL} and end at a constant
 * {@code TRUE};</li>
 * <li>a conjunct that every branch of an {@code OR} has in common is taken
 * out in front of it, {@code (c AND a) OR (c AND b)} becomes
 * {@code c AND (a OR b)}, which holds in three-valued logic; so a join
 * condition that each branch repeats becomes a join key. Only conjuncts
 * that cannot fail are taken out, since they are then evaluated for more
 * rows;</li>
 * <li>a filter whose condition is {@code TRUE} disappears.</li>
 * </ul>
 * The rows are the same as without the rewrite.
 */
final class ConstantFolding {

    private static final Expression TRUE = Expressions.lit(true);
    private static final Expression FALSE = Expressions.lit(false);

    private ConstantFolding() {
    }

    /** Folds the expressions of every node of a plan. */
    static PlanNode fold(PlanNode plan) {
        List<PlanNode> children = new ArrayList<>();
        boolean changed = false;
        for (PlanNode child : plan.children()) {
            PlanNode folded = fold(child);
            changed |= folded != child;
            children.add(folded);
        }
        PlanNode node = changed ? Plans.withChildren(plan, children) : plan;
        if (Plans.expressions(node).isEmpty()) {
            return node;
        }
        PlanNode result = Plans.mapExpressions(node, ConstantFolding::fold);
        if (result instanceof PlanNode.Filter f && f.condition().equals(TRUE)) {
            return f.input();
        }
        return result.equals(node) ? node : result;
    }

    /** Folds an expression, bottom-up. */
    static Expression fold(Expression e) {
        if (e instanceof Expression.Or) {
            return or(e);
        }
        List<Expression> children = e.children();
        List<Expression> folded = new ArrayList<>(children.size());
        boolean changed = false;
        for (Expression child : children) {
            Expression f = fold(child);
            changed |= f != child;
            folded.add(f);
        }
        Expression node = changed ? Expressions.withChildren(e, folded) : e;
        if (!(node instanceof Expression.Literal) && Expressions.isConstant(node)) {
            return constant(node);
        }
        return switch (node) {
            case Expression.And a -> and(a);
            case Expression.Not n when n.operand() instanceof Expression.Not inner -> inner.operand();
            case Expression.Case c -> caseWhen(c);
            case Expression.If i -> iff(i);
            default -> node;
        };
    }

    private static Expression and(Expression.And a) {
        if (a.left().equals(FALSE) || a.right().equals(FALSE)) {
            return FALSE;
        }
        if (a.left().equals(TRUE)) {
            return a.right();
        }
        if (a.right().equals(TRUE)) {
            return a.left();
        }
        return a;
    }

    private static Expression constant(Expression e) {
        try {
            return new Expression.Literal(e.type(), Expressions.constantValue(e));
        } catch (RuntimeException x) {
            // Fails when it runs, and only if a row reaches it.
            return e;
        }
    }

    /**
     * Folds the branches of nested {@code OR}s as one list, so that the
     * conjuncts all of them have in common are found at once.
     */
    private static Expression or(Expression e) {
        List<Expression> all = new ArrayList<>();
        disjuncts(e, all);
        List<Expression> branches = new ArrayList<>();
        for (Expression b : all) {
            Expression f = fold(b);
            if (f.equals(TRUE)) {
                return TRUE;
            }
            if (!f.equals(FALSE)) {
                branches.add(f);
            }
        }
        if (branches.isEmpty()) {
            return FALSE;
        }
        Expression result = disjunction(branches);
        if (Expressions.isConstant(result)) {
            return result instanceof Expression.Literal ? result : constant(result);
        }
        return branches.size() == 1 ? result : factor(branches, result);
    }

    private static Expression disjunction(List<Expression> branches) {
        Expression result = branches.get(0);
        for (int i = 1; i < branches.size(); i++) {
            result = new Expression.Or(result, branches.get(i));
        }
        return result;
    }

    /** Takes the conjuncts that all branches of an {@code OR} have in common out in front of it. */
    private static Expression factor(List<Expression> branches, Expression or) {
        List<List<Expression>> conjuncts = new ArrayList<>();
        for (Expression b : branches) {
            conjuncts.add(new ArrayList<>(Expressions.conjuncts(b)));
        }
        List<Expression> common = new ArrayList<>();
        for (Expression c : conjuncts.get(0)) {
            if (!common.contains(c) && cannotFail(c) && conjuncts.stream().allMatch(list -> list.contains(c))) {
                common.add(c);
            }
        }
        if (common.isEmpty()) {
            return or;
        }
        List<Expression> rest = new ArrayList<>();
        for (List<Expression> list : conjuncts) {
            list.removeAll(common);
            if (list.isEmpty()) {
                // One branch is the common part alone, so the OR is just that.
                rest.clear();
                break;
            }
            rest.add(Expressions.conjunction(list));
        }
        List<Expression> result = new ArrayList<>(common);
        if (!rest.isEmpty()) {
            result.add(disjunction(rest));
        }
        return Expressions.conjunction(result);
    }

    private static void disjuncts(Expression e, List<Expression> into) {
        if (e instanceof Expression.Or o) {
            disjuncts(o.left(), into);
            disjuncts(o.right(), into);
        } else {
            into.add(e);
        }
    }

    /**
     * Tells whether an expression never fails: comparisons of columns and
     * literals of one type, with {@code AND}, {@code OR}, {@code NOT} and
     * {@code IS NULL}, but no conversion, arithmetic or function.
     */
    static boolean cannotFail(Expression e) {
        return Expressions.allMatch(e, x -> switch (x) {
            case Expression.Column c -> true;
            case Expression.Literal l -> true;
            case Expression.And a -> true;
            case Expression.Or or -> true;
            case Expression.Not n -> true;
            case Expression.IsNull n -> true;
            case Expression.Comparison c -> c.left().type().equals(c.right().type());
            case Expression.IsDistinct d -> d.left().type().equals(d.right().type());
            case Expression.Between b -> b.value().type().equals(b.low().type())
                    && b.value().type().equals(b.high().type());
            case Expression.In in -> in.list().stream().allMatch(v -> v.type().equals(in.value().type()));
            default -> false;
        });
    }

    private static Expression caseWhen(Expression.Case c) {
        List<Expression.When> whens = new ArrayList<>();
        Expression otherwise = c.otherwise();
        boolean changed = false;
        for (Expression.When w : c.whens()) {
            if (w.condition().equals(TRUE)) {
                otherwise = w.result();
                changed = true;
                break;
            }
            if (w.condition() instanceof Expression.Literal) {
                // FALSE or NULL: the branch is never taken.
                changed = true;
            } else {
                whens.add(w);
            }
        }
        if (!changed) {
            return c;
        }
        Expression otherwiseAs = as(otherwise, c.type());
        // The branches left keep their types; if a dropped one was the widest, the cast restores the type.
        return whens.isEmpty() ? otherwiseAs : as(new Expression.Case(whens, otherwiseAs), c.type());
    }

    private static Expression iff(Expression.If i) {
        if (i.condition().equals(TRUE)) {
            return as(i.whenTrue(), i.type());
        }
        if (i.condition() instanceof Expression.Literal) {
            return as(i.whenFalse(), i.type());
        }
        return i;
    }

    /** A branch in the type of the whole {@code CASE}, which may be wider than the branch's own. */
    private static Expression as(Expression e, DataType type) {
        if (e.type().equals(type)) {
            return e;
        }
        Expression cast = new Expression.Cast(e, type);
        return Expressions.isConstant(cast) ? fold(cast) : cast;
    }
}
