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

package net.fyrql.sql.bind;

import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.sql.ast.OrderItem;
import net.fyrql.sql.ast.SqlExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Walks the expressions of the syntax tree. */
final class SqlTrees {

    private SqlTrees() {
    }

    /**
     * Returns the operands of an expression, including the order of an
     * aggregate. A subquery is no operand: what it contains belongs to the
     * query inside. The operands of a window function are those of its call
     * and its window, not the call itself, so that an aggregate over a
     * window is no aggregate of the query, while one in its arguments is.
     */
    static List<SqlExpr> children(SqlExpr e) {
        return switch (e) {
            case SqlExpr.Literal l -> List.of();
            case SqlExpr.Identifier i -> List.of();
            case SqlExpr.Binary b -> List.of(b.left(), b.right());
            case SqlExpr.Unary u -> List.of(u.operand());
            case SqlExpr.Between b -> List.of(b.value(), b.low(), b.high());
            case SqlExpr.InList i -> {
                List<SqlExpr> result = new ArrayList<>();
                result.add(i.value());
                result.addAll(i.list());
                yield result;
            }
            case SqlExpr.InQuery i -> List.of(i.value());
            case SqlExpr.Exists x -> List.of();
            case SqlExpr.ScalarSubquery s -> List.of();
            case SqlExpr.Like l -> List.of(l.value(), l.pattern());
            case SqlExpr.IsNull n -> List.of(n.operand());
            case SqlExpr.IsDistinct d -> List.of(d.left(), d.right());
            case SqlExpr.Case c -> {
                List<SqlExpr> result = new ArrayList<>();
                if (c.operand() != null) {
                    result.add(c.operand());
                }
                for (SqlExpr.When w : c.whens()) {
                    result.add(w.condition());
                    result.add(w.result());
                }
                if (c.otherwise() != null) {
                    result.add(c.otherwise());
                }
                yield result;
            }
            case SqlExpr.Cast c -> List.of(c.operand());
            case SqlExpr.FunctionCall f -> {
                List<SqlExpr> result = new ArrayList<>(f.arguments());
                for (OrderItem o : f.orderBy()) {
                    result.add(o.expression());
                }
                yield result;
            }
            case SqlExpr.Index x -> List.of(x.base(), x.index());
            case SqlExpr.StructValue s -> s.values();
            case SqlExpr.Interval i -> List.of();
            case SqlExpr.Window w -> w.operands();
        };
    }

    /** Tells whether a function name is that of an aggregate, in any case. */
    static boolean isAggregate(String name) {
        try {
            AggregateFunction.of(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Tells whether an expression is a call of an aggregate. */
    static boolean isAggregate(SqlExpr e) {
        return e instanceof SqlExpr.FunctionCall f && isAggregate(f.name());
    }

    /** Tells whether an expression contains a call of an aggregate. */
    static boolean containsAggregate(SqlExpr e) {
        if (isAggregate(e)) {
            return true;
        }
        for (SqlExpr c : children(e)) {
            if (containsAggregate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether an expression contains a subquery. */
    static boolean containsSubquery(SqlExpr e) {
        if (e instanceof SqlExpr.Exists || e instanceof SqlExpr.InQuery || e instanceof SqlExpr.ScalarSubquery) {
            return true;
        }
        for (SqlExpr c : children(e)) {
            if (containsSubquery(c)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the first window function of an expression, also in the arguments of an aggregate, or {@code null}. */
    static SqlExpr.Window window(SqlExpr e) {
        if (e instanceof SqlExpr.Window w) {
            return w;
        }
        for (SqlExpr c : children(e)) {
            SqlExpr.Window w = window(c);
            if (w != null) {
                return w;
            }
        }
        return null;
    }

    /** Tells whether an expression is a call of {@code unnest}, in any case. */
    static boolean isUnnest(SqlExpr e) {
        return e instanceof SqlExpr.FunctionCall f && key(f.name()).equals("unnest");
    }

    /** Tells whether an expression contains a call of {@code unnest}. */
    static boolean containsUnnest(SqlExpr e) {
        if (isUnnest(e)) {
            return true;
        }
        for (SqlExpr c : children(e)) {
            if (containsUnnest(c)) {
                return true;
            }
        }
        return false;
    }

    /** Tells whether an expression names a column, or an alias, outside the arguments of aggregates. */
    static boolean containsNameOutsideAggregates(SqlExpr e) {
        if (e instanceof SqlExpr.Identifier) {
            return true;
        }
        if (isAggregate(e)) {
            return false;
        }
        for (SqlExpr c : children(e)) {
            if (containsNameOutsideAggregates(c)) {
                return true;
            }
        }
        return false;
    }

    /** Splits a condition at its top-level {@code AND}s. */
    static List<SqlExpr> conjuncts(SqlExpr e) {
        List<SqlExpr> result = new ArrayList<>();
        if (e instanceof SqlExpr.Binary b && b.op() == SqlExpr.BinaryOp.AND) {
            result.addAll(conjuncts(b.left()));
            result.addAll(conjuncts(b.right()));
        } else {
            result.add(e);
        }
        return result;
    }

    /** Returns the position of an integer literal as a 1-based position in a list, or -1 if it is none. */
    static long position(SqlExpr e) {
        if (e instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.NUMBER && !l.text().isEmpty()
                && l.text().chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(l.text());
            } catch (NumberFormatException x) {
                return Long.MAX_VALUE;
            }
        }
        return -1;
    }

    /**
     * Returns the position of an integer literal under any number of minus
     * signs, which DuckDB folds into it: the position under an even number,
     * the negated position under an odd one, and {@link Long#MIN_VALUE} if
     * the expression is no such literal.
     */
    static long signedPosition(SqlExpr e) {
        boolean negated = false;
        SqlExpr x = e;
        while (x instanceof SqlExpr.Unary u && u.op() == SqlExpr.UnaryOp.MINUS) {
            negated = !negated;
            x = u.operand();
        }
        long position = position(x);
        return position < 0 ? Long.MIN_VALUE : negated ? -position : position;
    }

    /** Returns a name in lower case, the key under which names match regardless of case. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
