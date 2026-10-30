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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.SqlExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The references of a subquery to the query around it. In the
 * {@code WHERE} clause of the subquery, a name that its own columns do not
 * reach is bound by the binder of the clause around the subquery, and
 * stands for a placeholder column; the join that adds the subquery to the
 * rows of that clause reads the bound values in their place. The binding
 * of the subquery tells here how to join it: by which keys, under which
 * condition, and with which value.
 */
final class Correlation implements Scope.Outer {

    /** What the subquery stands for. */
    enum Kind {
        SCALAR, EXISTS, IN
    }

    /**
     * How to join the subquery to the rows of the clause around it.
     *
     * @param innerKeys the key columns of the subquery
     * @param outerKeys the keys they must equal, over the placeholders
     * @param condition a condition over the columns of the subquery and the
     *        placeholders, or {@code null} for none
     * @param value the value of a scalar subquery over its columns, to be
     *        computed after the join, or {@code null} for its first column
     * @param columns the number of columns the subquery has in its select list
     */
    record Join(List<Integer> innerKeys, List<Expression> outerKeys, Expression condition, Expression value,
            int columns) {
        Join {
            innerKeys = List.copyOf(innerKeys);
            outerKeys = List.copyOf(outerKeys);
        }
    }

    /** The index of the first placeholder column, beyond any real one and below those of {@link SubqueryJoiner}. */
    static final int PLACEHOLDER = 1 << 29;
    private static final int END = 1 << 30;

    private final ExpressionBinder outer;
    private final Kind kind;
    private final List<Expression> values = new ArrayList<>();
    private Join join;

    /** @param outer binds the names of the clause around the subquery */
    Correlation(ExpressionBinder outer, Kind kind) {
        this.outer = Objects.requireNonNull(outer, "outer");
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }

    /** The values the placeholders stand for, over the rows of the clause around the subquery. */
    List<Expression> values() {
        return values;
    }

    /** Tells whether the subquery references the query around it. */
    boolean isCorrelated() {
        return !values.isEmpty();
    }

    /** How to join the subquery, or {@code null} if its binding did not tell. */
    Join join() {
        return join;
    }

    void join(Join j) {
        this.join = j;
    }

    /** Tells whether an expression reads a placeholder. */
    static boolean reads(Expression e) {
        return !Expressions.allMatch(e, x -> !(x instanceof Expression.Column c && c.index() >= PLACEHOLDER
                && c.index() < END));
    }

    /** Replaces the placeholders of an expression by the values they stand for. */
    Expression outer(Expression e) {
        return Expressions.transform(e, x -> x instanceof Expression.Column c && c.index() >= PLACEHOLDER
                && c.index() < END ? values.get(c.index() - PLACEHOLDER) : x);
    }

    @Override
    public Scope.Resolved resolve(List<String> name, Position position) {
        if (outer.scope().lookup(name, position) == null) {
            return null;
        }
        Expression value = outer.bind(new SqlExpr.Identifier(name, position));
        if (reads(value)) {
            throw new SqlException("Not supported: a reference to a query more than one level out of a subquery",
                    position);
        }
        int i = values.indexOf(value);
        if (i < 0) {
            values.add(value);
            i = values.size() - 1;
        }
        Expression placeholder = new Expression.Column(PLACEHOLDER + i, value.type());
        return new Scope.Resolved(new Scope.Column(null, name.get(name.size() - 1), placeholder, false), List.of());
    }

    /**
     * Resolves the names of the clauses of the subquery other than
     * {@code WHERE}, which may not reference the query around it; a name
     * beyond it, of the left row of a lateral join around it, is a constant
     * of the subquery and resolves as there.
     */
    Scope.Outer outsideWhere() {
        return (name, position) -> {
            Scope.Resolved beyond = outer.scope().lookupLocal(name, position) == null
                    ? outer.scope().lookup(name, position) : null;
            if (beyond != null && Expressions.allMatch(beyond.column().expression(),
                    x -> !(x instanceof Expression.Column))) {
                return beyond;
            }
            if (beyond != null || outer.scope().lookupLocal(name, position) != null) {
                throw new SqlException("Not supported: a reference to the outer query outside the WHERE clause of a"
                        + " subquery", position);
            }
            return null;
        };
    }
}
