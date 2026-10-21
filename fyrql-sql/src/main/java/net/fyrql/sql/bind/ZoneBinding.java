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
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixes the time zone of the session in a bound plan, as the last step of
 * the binding: every cast that depends on the time zone converts in it,
 * every function computes in it ({@link ScalarFunction#inZone}), and where
 * a {@code DATE} or {@code TIMESTAMP} meets a {@code TIMESTAMP WITH TIME
 * ZONE} in a comparison or in the branches of a {@code CASE}, a
 * {@code COALESCE} or an {@code IF}, it becomes an instant in that zone
 * through an explicit cast. So the interpreter and generated code never
 * convert between local and zoned values on their own.
 */
final class ZoneBinding {

    private ZoneBinding() {
    }

    /** Fixes the time zone in every expression of a plan. */
    static PlanNode plan(PlanNode plan, ZoneId zone) {
        List<PlanNode> children = new ArrayList<>();
        boolean changed = false;
        for (PlanNode child : plan.children()) {
            PlanNode fixed = plan(child, zone);
            changed |= fixed != child;
            children.add(fixed);
        }
        PlanNode node = changed ? Plans.withChildren(plan, children) : plan;
        return Plans.mapExpressions(node, e -> expression(e, zone));
    }

    /** Fixes the time zone in the plans of a statement. */
    static BoundStatement statement(BoundStatement statement, ZoneId zone) {
        return switch (statement) {
            case BoundStatement.Query q -> new BoundStatement.Query(plan(q.plan(), zone));
            case BoundStatement.Copy c -> new BoundStatement.Copy(plan(c.input(), zone), c.path(), c.format(),
                    c.options());
            case BoundStatement.CopyFrom c -> new BoundStatement.CopyFrom(c.table(), plan(c.input(), zone), c.path(),
                    c.format(), c.options());
            case BoundStatement.Create c -> new BoundStatement.Create(c.table(), c.schema(),
                    c.input() == null ? null : plan(c.input(), zone), c.mode());
            case BoundStatement.Insert i -> new BoundStatement.Insert(i.table(), plan(i.input(), zone));
            case BoundStatement.Drop d -> d;
            case BoundStatement.Set s -> s;
        };
    }

    /** Fixes the time zone in an expression. */
    static Expression expression(Expression e, ZoneId zone) {
        return Expressions.transform(e, x -> node(x, zone));
    }

    private static Expression node(Expression e, ZoneId zone) {
        return switch (e) {
            case Expression.Cast c when c.zone() == null && Casts.needsZone(c.operand().type(), c.target()) ->
                    new Expression.Cast(c.operand(), c.target(), c.safe(), zone);
            case Expression.Call c -> {
                ScalarFunction f = c.function().inZone(zone);
                // An argument that the function casts to a type that depends on the time zone is cast here.
                List<DataType> coerced = f.readsArguments() ? f.coerce(c.argumentTypes()) : c.argumentTypes();
                List<Expression> arguments = new ArrayList<>(c.arguments());
                boolean cast = false;
                for (int i = 0; i < arguments.size(); i++) {
                    DataType from = arguments.get(i).type();
                    if (!from.equals(coerced.get(i)) && Casts.needsZone(from, coerced.get(i))) {
                        arguments.set(i, new Expression.Cast(arguments.get(i), coerced.get(i), false, zone));
                        cast = true;
                    }
                }
                yield f == c.function() && !cast ? c : new Expression.Call(f, arguments);
            }
            case Expression.Comparison c when mixed(c.left(), c.right()) ->
                    new Expression.Comparison(c.op(), instant(c.left(), zone), instant(c.right(), zone));
            case Expression.IsDistinct d when mixed(d.left(), d.right()) ->
                    new Expression.IsDistinct(instant(d.left(), zone), instant(d.right(), zone), d.negated());
            case Expression.Between b when zoned(List.of(b.value(), b.low(), b.high())) ->
                    new Expression.Between(instant(b.value(), zone), instant(b.low(), zone), instant(b.high(), zone));
            case Expression.In in when zoned(e.children()) -> new Expression.In(instant(in.value(), zone),
                    in.list().stream().map(x -> instant(x, zone)).toList());
            case Expression.Case c when c.type() == DataType.TIMESTAMPTZ -> new Expression.Case(
                    c.whens().stream().map(w -> new Expression.When(w.condition(), instant(w.result(), zone))).toList(),
                    instant(c.otherwise(), zone));
            case Expression.Coalesce c when c.type() == DataType.TIMESTAMPTZ ->
                    new Expression.Coalesce(c.operands().stream().map(x -> instant(x, zone)).toList());
            case Expression.If i when i.type() == DataType.TIMESTAMPTZ -> new Expression.If(i.condition(),
                    instant(i.whenTrue(), zone), instant(i.whenFalse(), zone));
            default -> e;
        };
    }

    private static boolean isLocal(DataType type) {
        return type == DataType.DATE || type == DataType.TIMESTAMP;
    }

    /** Tells whether one of two operands is an instant and the other a local date or timestamp. */
    private static boolean mixed(Expression a, Expression b) {
        return a.type() == DataType.TIMESTAMPTZ && isLocal(b.type())
                || b.type() == DataType.TIMESTAMPTZ && isLocal(a.type());
    }

    /** Tells whether operands mix instants with local dates or timestamps. */
    private static boolean zoned(List<Expression> operands) {
        boolean instant = false;
        boolean local = false;
        for (Expression x : operands) {
            instant |= x.type() == DataType.TIMESTAMPTZ;
            local |= isLocal(x.type());
        }
        return instant && local;
    }

    /** A local date or timestamp as the instant it names in the time zone; anything else as it is. */
    private static Expression instant(Expression e, ZoneId zone) {
        return isLocal(e.type()) ? new Expression.Cast(e, DataType.TIMESTAMPTZ, false, zone) : e;
    }
}
