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
import net.fyrql.core.expr.Expression.ArithmeticOp;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.function.DateFormats;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.function.StructExtract;
import net.fyrql.core.function.StructPack;
import net.fyrql.core.function.ZonedOperators;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Dates;
import net.fyrql.core.value.Interval;
import net.fyrql.core.value.ZonedDates;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.SqlExpr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns the expressions of the syntax tree into {@link Expression}s over the
 * columns of a {@link Scope}, with the typing rules of DuckDB 1.5.6 that
 * {@link Expression} itself does not know:
 * <ul>
 * <li>A bare {@code NULL} takes the type its context gives it, and is an
 * {@code INTEGER} where nothing does.</li>
 * <li>A string literal compared with, or united with, a value of another
 * type is converted to that type when the query is bound. A {@code VARCHAR}
 * value is converted to the type of the other side of {@code =},
 * {@code <>} and {@code IN}, but not of an ordering comparison.</li>
 * <li>An integer literal next to a narrower integral type in arithmetic,
 * {@code CASE}, {@code COALESCE} and similar takes that type if its value
 * fits, so {@code tinyint_column + 1} stays a {@code TINYINT}.</li>
 * <li>A condition that is not {@code BOOLEAN} is cast to it.</li>
 * <li>A parameter {@code ?} takes the type its context gives a bare
 * {@code NULL}, and is a value of that type from then on, see
 * {@link ParameterTypes}.</li>
 * </ul>
 * A name that is no column of the scope may be the alias of an item of the
 * select list, whose expression then stands in for it.
 */
final class ExpressionBinder {

    /** What kind of literal an expression is, which decides how its type adapts to its context. */
    enum Kind {
        /** Not a literal whose type adapts. */
        TYPED,
        /** A bare {@code NULL}, typed {@code INTEGER} until the context decides. */
        NULL,
        /** A string literal. */
        STRING,
        /** An integer literal. */
        INTEGER,
        /** A parameter, typed {@code INTEGER} like a bare {@code NULL} until the context decides. */
        PARAMETER
    }

    /**
     * A bound expression with its {@link Kind}.
     *
     * @param parameter the parameter of the kind {@link Kind#PARAMETER}, else {@code null}
     */
    record Bound(Expression expression, Kind kind, SqlExpr.Parameter parameter) {
        Bound {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.PARAMETER) != (parameter != null)) {
                throw new IllegalArgumentException("a parameter is of the kind PARAMETER, and nothing else is");
            }
        }

        Bound(Expression expression, Kind kind) {
            this(expression, kind, null);
        }

        static Bound typed(Expression e) {
            return new Bound(e, Kind.TYPED);
        }

        static Bound nullLiteral() {
            return new Bound(new Expression.Literal(DataType.INTEGER, null), Kind.NULL);
        }

        static Bound parameter(SqlExpr.Parameter p) {
            return new Bound(new Expression.Literal(DataType.INTEGER, null), Kind.PARAMETER, p);
        }

        DataType type() {
            return expression.type();
        }

        /** Tells whether this is a bare {@code NULL} or an untyped parameter, whose type the context decides. */
        boolean isNull() {
            return kind == Kind.NULL || kind == Kind.PARAMETER;
        }

        Expression.Literal literal() {
            return (Expression.Literal) expression;
        }
    }

    /**
     * Replaces the binding of a node of the syntax tree, for the expressions
     * above a {@code GROUP BY}, whose aggregates and grouped expressions are
     * columns of the aggregation.
     */
    interface Hook {
        /** Returns the expression for a node, or {@code null} to bind it as usual. */
        Expression intercept(SqlExpr e);
    }

    /**
     * Binds the subqueries of an expression, each to a column that a join
     * adds to the rows the expression reads.
     */
    interface Subqueries {
        /** The value of {@code [NOT] EXISTS}. */
        Expression exists(SqlExpr.Exists e);

        /** The value of {@code [NOT] IN} with a subquery, given its bound left side. */
        Expression in(SqlExpr.InQuery e, Bound value, ExpressionBinder binder);

        /** The value of a scalar subquery. */
        Expression scalar(SqlExpr.ScalarSubquery e);
    }

    private final BindContext context;
    private final Scope scope;
    private final Map<String, SqlExpr> aliases;
    private final String aggregateError;
    private final Hook hook;
    private final Subqueries subqueries;
    private final Set<String> expanding = new HashSet<>();

    /**
     * @param scope the columns
     * @param aliases the aliases of the select list that may stand for a
     *        name, by {@link SqlTrees#key}
     * @param aggregateError the message for an aggregate in this place;
     *        {@code null} where the hook takes the aggregates
     * @param hook the hook, or {@code null}
     * @param subqueries the binding of subqueries, or {@code null} where
     *        they are not supported
     */
    ExpressionBinder(BindContext context, Scope scope, Map<String, SqlExpr> aliases, String aggregateError,
            Hook hook, Subqueries subqueries) {
        this.context = context;
        this.scope = scope;
        this.aliases = aliases;
        this.aggregateError = aggregateError;
        this.hook = hook;
        this.subqueries = subqueries;
    }

    /** A binder without subqueries. */
    ExpressionBinder(BindContext context, Scope scope, Map<String, SqlExpr> aliases, String aggregateError,
            Hook hook) {
        this(context, scope, aliases, aggregateError, hook, null);
    }

    Scope scope() {
        return scope;
    }

    /** Returns the time zone of the session. */
    java.time.ZoneId zone() {
        return context.zone();
    }

    /** Binds an expression; a bare {@code NULL} becomes an {@code INTEGER}. */
    Expression bind(SqlExpr e) {
        return bound(e).expression();
    }

    /** Binds a condition, cast to {@code BOOLEAN} if it is not one. */
    Expression condition(SqlExpr e) {
        return toBoolean(bound(e), e.position());
    }

    /** Binds an expression and tells what kind of literal it is. */
    Bound bound(SqlExpr e) {
        if (hook != null) {
            Expression x = hook.intercept(e);
            if (x != null) {
                return Bound.typed(x);
            }
        }
        return switch (e) {
            case SqlExpr.Literal l -> literal(l);
            case SqlExpr.Parameter p -> {
                DataType type = context.parameters().check(p);
                yield type != null ? Bound.typed(ParameterTypes.placeholder(p, type)) : Bound.parameter(p);
            }
            case SqlExpr.Identifier i -> identifier(i);
            case SqlExpr.Binary b -> binary(b);
            case SqlExpr.Unary u -> unary(u);
            case SqlExpr.Between b -> between(b);
            case SqlExpr.InList i -> in(i);
            case SqlExpr.InQuery i -> Bound.typed(subqueries(i).in(i, bound(i.value()), this));
            case SqlExpr.Exists x -> Bound.typed(subqueries(x).exists(x));
            case SqlExpr.ScalarSubquery s -> Bound.typed(subqueries(s).scalar(s));
            case SqlExpr.Like l -> like(l);
            case SqlExpr.IsNull n -> Bound.typed(new Expression.IsNull(bind(n.operand()), n.negated()));
            case SqlExpr.IsDistinct d -> {
                Expression[] operands = equality(bound(d.left()), bound(d.right()), d.position());
                yield Bound.typed(new Expression.IsDistinct(operands[0], operands[1], d.negated()));
            }
            case SqlExpr.Case c -> caseWhen(c);
            case SqlExpr.Cast c -> cast(c);
            case SqlExpr.FunctionCall f -> call(f);
            case SqlExpr.Index x -> index(bound(x.base()), x.index(), x.position());
            case SqlExpr.StructValue s -> structValue(s);
            case SqlExpr.Interval i -> Bound.typed(interval(i));
            case SqlExpr.Window w -> throw new SqlException(windowError(), w.position());
        };
    }

    /**
     * The message for a window function in this place, worded as DuckDB
     * words it for the clauses whose message for an aggregate is known.
     */
    private String windowError() {
        if (aggregateError == null) {
            return "Not supported: window functions";
        }
        if (aggregateError.equals("aggregate function calls cannot be nested")) {
            return "aggregate function calls cannot contain window function calls";
        }
        // DuckDB checks the condition of a join as a WHERE clause.
        if (aggregateError.startsWith("JOIN clause ")) {
            return "WHERE clause cannot contain window functions!";
        }
        if (aggregateError.endsWith(" cannot contain aggregates!")) {
            return aggregateError.replace(" cannot contain aggregates!", " cannot contain window functions!");
        }
        if (aggregateError.endsWith(" cannot contain aggregates")) {
            return aggregateError.replace(" cannot contain aggregates", " cannot contain window functions");
        }
        return "Not supported: window functions";
    }

    private Subqueries subqueries(SqlExpr e) {
        if (subqueries == null) {
            throw new SqlException("Not supported: a subquery here", e.position());
        }
        return subqueries;
    }

    /** {@code INTERVAL '...'}, read as DuckDB reads it. */
    private static Expression.Literal interval(SqlExpr.Interval i) {
        try {
            return new Expression.Literal(DataType.INTERVAL, Interval.parse(i.text()));
        } catch (ConversionException | ArithmeticException e) {
            throw new SqlException(e.getMessage(), i.position());
        }
    }

    /** Converts a literal as {@link Literals#lazily} does, in the time zone of the session. */
    private Expression lazily(Expression.Literal literal, DataType target, Position position) {
        return Literals.lazily(literal, target, position, context.zone());
    }

    private Bound literal(SqlExpr.Literal l) {
        return switch (l.kind()) {
            case NUMBER -> Literals.number(l.text(), false, l.position());
            case STRING -> new Bound(new Expression.Literal(DataType.VARCHAR, l.text()), Kind.STRING);
            case BOOLEAN -> Bound.typed(new Expression.Literal(DataType.BOOLEAN, Boolean.parseBoolean(l.text())));
            case NULL -> Bound.nullLiteral();
            case DATE -> Bound.typed(lazily(new Expression.Literal(DataType.VARCHAR, l.text()), DataType.DATE,
                    l.position()));
            case TIMESTAMP -> Bound.typed(lazily(new Expression.Literal(DataType.VARCHAR, l.text()),
                    DataType.TIMESTAMP, l.position()));
            case TIME -> Bound.typed(lazily(new Expression.Literal(DataType.VARCHAR, l.text()), DataType.TIME,
                    l.position()));
            case TIMESTAMPTZ -> Bound.typed(lazily(new Expression.Literal(DataType.VARCHAR, l.text()),
                    DataType.TIMESTAMPTZ, l.position()));
        };
    }

    private Bound identifier(SqlExpr.Identifier i) {
        Scope.Resolved r = scope.resolve(i.parts(), i.position());
        if (r != null) {
            Scope.Column c = r.column();
            // A bare NULL of a subquery keeps its open type, as in DuckDB.
            return fields(new Bound(c.expression(), c.untypedNull() ? Kind.NULL : Kind.TYPED), c.name(), r.fields(),
                    i.position());
        }
        if (i.parts().size() == 1) {
            String key = SqlTrees.key(i.name());
            SqlExpr aliased = aliases.get(key);
            if (aliased != null && expanding.add(key)) {
                try {
                    return bound(aliased);
                } finally {
                    expanding.remove(key);
                }
            }
            if (key.equals("localtime") || key.equals("localtimestamp")) {
                // Keywords of SQL that the parser reads as names.
                return call(new SqlExpr.FunctionCall(key, List.of(), i.position()));
            }
        }
        throw notFound(i);
    }

    /**
     * The fields that the parts of a name after the name of a column select,
     * one inside the other, over the value of the column.
     */
    Bound fields(Bound column, String name, List<String> fields, Position position) {
        Bound b = column;
        String text = name;
        for (String field : fields) {
            b = field(b, field, text, position);
            text = "struct_extract(" + text + ", '" + field + "')";
        }
        return b;
    }

    /**
     * The field of a value that a part of a name after a column names: of a
     * struct, by its name in any case, or of a map, the value of the key.
     *
     * @param text the base as DuckDB writes it in the message for a value that has no fields
     */
    private Bound field(Bound base, String name, String text, Position position) {
        if (base.type() instanceof DataType.Struct s && !base.isNull()) {
            return structField(base, s, name, position);
        }
        if (base.type() instanceof DataType.MapType && !base.isNull()) {
            return mapValue("map_extract_value", base, new Bound(new Expression.Literal(DataType.VARCHAR, name),
                    Kind.STRING), position);
        }
        throw new SqlException("Cannot extract field '" + name + "' from expression \"" + text
                + "\" because it is not a struct, union, map, or json", position);
    }

    /** The field of a struct that a name selects, without regard to case. */
    private static Bound structField(Bound base, DataType.Struct s, String name, Position position) {
        int index = s.find(name);
        if (index < 0) {
            List<String> names = new ArrayList<>();
            for (DataType.Struct.Field f : s.fields()) {
                names.add("\"" + f.name() + "\"");
            }
            throw new SqlException("Could not find key \"" + name + "\" in struct; candidate entries: "
                    + String.join(", ", names), position);
        }
        String field = s.fields().get(index).name();
        return foldNull(new Expression.Call(new StructExtract(index, field),
                List.of(base.expression(), new Expression.Literal(DataType.VARCHAR, field))));
    }

    /**
     * {@code base[index]}: the element of a list, the character of a string,
     * the value of a key of a map, or the field of a struct whose name is a
     * constant string. A string literal as the position of a list is the
     * number it spells.
     */
    private Bound index(Bound base, SqlExpr indexExpr, Position position) {
        Bound index = bound(indexExpr);
        DataType type = base.type();
        if (type instanceof DataType.Struct s && !base.isNull()) {
            if (index.isNull()) {
                return Bound.nullLiteral();
            }
            if (index.type().isIntegral()) {
                throw new SqlException("struct_extract with an integer key can only be used on unnamed structs,"
                        + " use a string key instead", position);
            }
            if (index.type() != DataType.VARCHAR || !Expressions.isConstant(index.expression())) {
                throw new SqlException("Key name for struct_extract needs to be a constant string", position);
            }
            String name = (String) check(() -> Expressions.constantValue(index.expression()), position, null);
            return name == null ? Bound.nullLiteral() : structField(base, s, name, position);
        }
        if (type instanceof DataType.MapType && !base.isNull()) {
            return mapValue("map_extract_value", base, index, position);
        }
        return foldNull(call("list_extract", templateArguments("list_extract", List.of(base, index), position),
                position).expression());
    }

    /** A lookup in a map, whose key takes the key type of the map if it is a literal. */
    private Bound mapValue(String name, Bound map, Bound key, Position position) {
        return foldNull(call(name, templateArguments(name, List.of(map, key), position), position).expression());
    }

    /**
     * Converts the literal arguments of the functions over lists and maps as
     * DuckDB deduces their types: a value looked up in a list or a map takes
     * the type of the elements or keys if it is a string literal or a bare
     * {@code NULL}, and a position given as a string literal is a
     * {@code BIGINT}.
     */
    private List<Bound> templateArguments(String name, List<Bound> args, Position position) {
        if (args.size() != 2) {
            return args;
        }
        DataType target = switch (name) {
            case "list_contains", "list_has", "array_contains", "array_has" ->
                    args.get(0).type() instanceof DataType.ListType l && !args.get(0).isNull() ? l.element() : null;
            case "map_extract", "element_at", "map_extract_value" ->
                    args.get(0).type() instanceof DataType.MapType m && !args.get(0).isNull() ? m.key() : null;
            case "list_extract", "list_element", "array_extract" -> DataType.BIGINT;
            default -> null;
        };
        Bound value = args.get(1);
        if (target == null || !value.isNull() && value.kind() != Kind.STRING) {
            return args;
        }
        Expression converted = value.isNull() ? typed(value, target).expression()
                : target == DataType.VARCHAR ? value.expression() : lazily(value.literal(), target, position);
        return List.of(args.get(0), Bound.typed(converted));
    }

    /** {@code {'a': x}}: a struct of the values, whose bare {@code NULL}s are {@code INTEGER}s. */
    private Bound structValue(SqlExpr.StructValue s) {
        List<Expression> values = new ArrayList<>();
        for (SqlExpr v : s.values()) {
            values.add(bind(v));
        }
        StructPack pack = check(() -> new StructPack(s.names()), s.position(), null);
        return Bound.typed(new Expression.Call(pack, values));
    }

    /** The error for a name that is no column. */
    SqlException notFound(SqlExpr.Identifier i) {
        String name = String.join(".", i.parts());
        if (!scope.hasFrom()) {
            return new SqlException("Referenced column \"" + name
                    + "\" was not found because the FROM clause is missing", i.position());
        }
        return new SqlException("Referenced column \"" + name + "\" not found in FROM clause!", i.position());
    }

    private Bound binary(SqlExpr.Binary b) {
        Position p = b.position();
        return switch (b.op()) {
            case AND -> Bound.typed(new Expression.And(condition(b.left()), condition(b.right())));
            case OR -> Bound.typed(new Expression.Or(condition(b.left()), condition(b.right())));
            case ADD -> arithmetic(ArithmeticOp.ADD, b);
            case SUBTRACT -> arithmetic(ArithmeticOp.SUBTRACT, b);
            case MULTIPLY -> arithmetic(ArithmeticOp.MULTIPLY, b);
            case DIVIDE -> arithmetic(ArithmeticOp.DIVIDE, b);
            case INT_DIVIDE -> arithmetic(ArithmeticOp.INT_DIVIDE, b);
            case MODULO -> arithmetic(ArithmeticOp.REMAINDER, b);
            case CONCAT -> {
                Bound l = bound(b.left());
                Bound r = bound(b.right());
                // A parameter takes the type of text or bytes on the other side, and is text otherwise.
                if (l.kind() == Kind.PARAMETER) {
                    l = typed(l, concatenated(r));
                }
                if (r.kind() == Kind.PARAMETER) {
                    r = typed(r, concatenated(l));
                }
                // DuckDB gives NULL's own type to a concatenation with a constant NULL.
                yield constantNull(l.expression()) || constantNull(r.expression()) ? Bound.nullLiteral()
                        : call("||", List.of(l, r), p);
            }
            case EQUAL -> comparison(ComparisonOp.EQ, b);
            case NOT_EQUAL -> comparison(ComparisonOp.NE, b);
            case LESS -> comparison(ComparisonOp.LT, b);
            case LESS_EQUAL -> comparison(ComparisonOp.LE, b);
            case GREATER -> comparison(ComparisonOp.GT, b);
            case GREATER_EQUAL -> comparison(ComparisonOp.GE, b);
        };
    }

    /** The type of a parameter concatenated with a value: that of text or bytes, else text. */
    private static DataType concatenated(Bound other) {
        return !other.isNull() && other.type() == DataType.BLOB ? DataType.BLOB : DataType.VARCHAR;
    }

    private Bound arithmetic(ArithmeticOp op, SqlExpr.Binary b) {
        Bound l = bound(b.left());
        Bound r = bound(b.right());
        Bound shifted = constantDateShift(op, l, r, b);
        if (shifted != null) {
            return shifted;
        }
        if (l.isNull() && r.isNull()) {
            l = typed(l, DataType.BIGINT);
            r = typed(r, DataType.BIGINT);
        } else if (l.isNull()) {
            l = typed(l, nullOperand(op, r.type(), true, b.position()));
        } else if (r.isNull()) {
            r = typed(r, nullOperand(op, l.type(), false, b.position()));
        }
        l = adaptInteger(l, r.type());
        r = adaptInteger(r, l.type());
        DataType lt = l.type();
        DataType rt = r.type();
        if (lt == DataType.TIMESTAMPTZ || rt == DataType.TIMESTAMPTZ) {
            return zonedArithmetic(op, l, r, b.position());
        }
        Expression left = l.expression();
        Expression right = r.expression();
        return foldNull(check(() -> new Expression.Arithmetic(op, left, right), b.position(),
                "No operator " + op.symbol() + " for " + lt + " and " + rt));
    }

    /**
     * Arithmetic with a {@code TIMESTAMP WITH TIME ZONE}, which depends on
     * the time zone and is therefore a call of a function that holds it:
     * {@code add} for an instant plus an interval, {@code subtract} for an
     * instant minus an interval or minus another instant, a local date or
     * timestamp first becoming an instant in the time zone.
     */
    private Bound zonedArithmetic(ArithmeticOp op, Bound l, Bound r, Position position) {
        DataType lt = l.type();
        DataType rt = r.type();
        Expression left = l.expression();
        Expression right = r.expression();
        if (op == ArithmeticOp.SUBTRACT && isDated(lt) && isDated(rt)) {
            left = as(l, DataType.TIMESTAMPTZ);
            right = as(r, DataType.TIMESTAMPTZ);
        }
        ScalarFunction function = op == ArithmeticOp.ADD ? ZonedOperators.add(lt, rt)
                : op == ArithmeticOp.SUBTRACT ? ZonedOperators.subtract(left.type(), right.type()) : null;
        if (function == null) {
            throw new SqlException("No operator " + op.symbol() + " for " + lt + " and " + rt, position);
        }
        return foldNull(new Expression.Call(function, List.of(left, right)));
    }

    private static boolean isDated(DataType type) {
        return type == DataType.DATE || type == DataType.TIMESTAMP || type == DataType.TIMESTAMPTZ;
    }

    /**
     * {@code date + INTERVAL '...'} and {@code date - INTERVAL '...'} over a
     * constant {@code DATE} and an interval literal without a time of day,
     * computed here: the result stays a {@code DATE}, where DuckDB gives a
     * {@code TIMESTAMP}, so that a filter of a date column against it can
     * go to a source as it is. The end of a month is kept within the month,
     * as in {@code 2024-01-31 + 1 MONTH} is {@code 2024-02-29}. Every other
     * interval arithmetic is an operator, see {@link TypeRules#add}.
     *
     * @return the date, or {@code null} for any other arithmetic
     */
    private Bound constantDateShift(ArithmeticOp op, Bound l, Bound r, SqlExpr.Binary b) {
        Bound date;
        SqlExpr.Interval interval;
        if (b.right() instanceof SqlExpr.Interval i && !(b.left() instanceof SqlExpr.Interval)
                && (op == ArithmeticOp.ADD || op == ArithmeticOp.SUBTRACT)) {
            date = l;
            interval = i;
        } else if (b.left() instanceof SqlExpr.Interval i && !(b.right() instanceof SqlExpr.Interval)
                && op == ArithmeticOp.ADD) {
            date = r;
            interval = i;
        } else {
            return null;
        }
        Interval shift = (Interval) (date == l ? r : l).literal().value();
        if (date.isNull() || date.type() != DataType.DATE || !Expressions.isConstant(date.expression())
                || shift.micros() != 0) {
            return null;
        }
        Object value = Literals.evaluate(date.expression(), b.position());
        if (value == null) {
            return nullOf(DataType.DATE);
        }
        try {
            Interval signed = op == ArithmeticOp.SUBTRACT ? shift.negate() : shift;
            LocalDate d = LocalDate.ofEpochDay((Integer) value).plusMonths(signed.months()).plusDays(signed.days());
            return Bound.typed(new Expression.Literal(DataType.DATE, Dates.checkDays(d.toEpochDay())));
        } catch (RuntimeException e) {
            throw new SqlException("The date is out of range after INTERVAL '" + interval.text() + "'",
                    interval.position());
        }
    }

    /**
     * Folds an operator or a function that gives {@code NULL} for a
     * {@code NULL} argument, as DuckDB's binder does: with an argument that
     * is a constant {@code NULL}, the call is a {@code NULL} itself, and its
     * other arguments are never evaluated. The {@code NULL} keeps the type of
     * the call, except a decimal, which DuckDB types like a bare
     * {@code NULL}.
     */
    private static Bound foldNull(Expression call) {
        if (call instanceof Expression.Call c && !c.function().nullOnNullInput()) {
            return Bound.typed(call);
        }
        for (Expression argument : call.children()) {
            if (constantNull(argument)) {
                return call.type() instanceof DataType.Decimal ? Bound.nullLiteral() : nullOf(call.type());
            }
        }
        return Bound.typed(call);
    }

    /** Tells whether an expression is a constant that evaluates to {@code NULL}. */
    private static boolean constantNull(Expression e) {
        if (e instanceof Expression.Literal l) {
            return l.value() == null;
        }
        if (!Expressions.isConstant(e)) {
            return false;
        }
        try {
            return Expressions.constantValue(e) == null;
        } catch (RuntimeException x) {
            // DuckDB tries the evaluation and keeps the call if it fails.
            return false;
        }
    }

    /** The type of a bare {@code NULL} next to an operand of a type: that type, if the operator takes it twice. */
    private static DataType nullOperand(ArithmeticOp op, DataType other, boolean left, Position position) {
        boolean add = op == ArithmeticOp.ADD;
        boolean subtract = op == ArithmeticOp.SUBTRACT;
        if (add || subtract) {
            // As DuckDB: a date takes days, a timestamp and an instant an interval, but a NULL before a minus is
            // of their own type; a time of day and an interval take an interval after a minus only.
            if (other == DataType.DATE) {
                return subtract && left ? DataType.DATE : DataType.INTEGER;
            }
            if (other == DataType.TIMESTAMP || other == DataType.TIMESTAMPTZ) {
                return subtract && left ? other : DataType.INTERVAL;
            }
            if (other == DataType.TIME || other == DataType.INTERVAL) {
                if (subtract && !left) {
                    return DataType.INTERVAL;
                }
                throw new SqlException("Could not choose a best candidate for the operator " + op.symbol()
                        + " of NULL and " + other.sqlName() + "; add an explicit cast", position);
            }
        }
        try {
            op.resultType(other, other);
            return other;
        } catch (IllegalArgumentException e) {
            // A date takes an integral number of days.
            return DataType.INTEGER;
        }
    }

    /** An integer literal next to an integral type that holds its value takes that type. */
    private static Bound adaptInteger(Bound b, DataType other) {
        if (b.kind() == Kind.INTEGER && other.isIntegral() && !other.equals(b.type())
                && Literals.fits(b.literal(), other)) {
            return new Bound(Literals.convert(b.literal(), other, Position.UNKNOWN), Kind.INTEGER);
        }
        return b;
    }

    private static Bound nullOf(DataType type) {
        return Bound.typed(new Expression.Literal(type, null));
    }

    /**
     * A bare {@code NULL} or a parameter as a value of a type: a
     * {@code NULL} of the type, or the parameter, which takes the type.
     */
    Bound typed(Bound b, DataType type) {
        return b.kind() == Kind.PARAMETER ? Bound.typed(context.parameters().typed(b.parameter(), type))
                : nullOf(type);
    }

    private Bound comparison(ComparisonOp op, SqlExpr.Binary b) {
        Bound l = bound(b.left());
        Bound r = bound(b.right());
        Expression[] operands = comparable(l, r, op == ComparisonOp.EQ || op == ComparisonOp.NE, b.position());
        return Bound.typed(new Expression.Comparison(op, operands[0], operands[1]));
    }

    /** Makes two operands comparable for equality. */
    Expression[] equality(Bound l, Bound r, Position position) {
        return comparable(l, r, true, position);
    }

    /**
     * Makes two operands comparable: a bare {@code NULL} and a string
     * literal take the type of the other side; a {@code VARCHAR} value is
     * cast to the type of the other side for equality.
     */
    private Expression[] comparable(Bound l, Bound r, boolean equality, Position position) {
        if (l.isNull() && !r.isNull()) {
            l = typed(l, r.type());
        } else if (r.isNull() && !l.isNull()) {
            r = typed(r, l.type());
        }
        if (l.kind() == Kind.STRING && r.kind() != Kind.STRING && r.type() != DataType.VARCHAR) {
            l = Bound.typed(lazily(l.literal(), r.type(), position));
        } else if (r.kind() == Kind.STRING && l.kind() != Kind.STRING && l.type() != DataType.VARCHAR) {
            r = Bound.typed(lazily(r.literal(), l.type(), position));
        }
        DataType lt = l.type();
        DataType rt = r.type();
        if (lt == DataType.VARCHAR && rt != DataType.VARCHAR || rt == DataType.VARCHAR && lt != DataType.VARCHAR) {
            DataType other = lt == DataType.VARCHAR ? rt : lt;
            if (!equality || other.isNested()) {
                throw new SqlException("Cannot compare values of type " + lt + " and type " + rt
                        + " - an explicit cast is required", position);
            }
            if (lt == DataType.VARCHAR) {
                l = Bound.typed(new Expression.Cast(l.expression(), other));
            } else {
                r = Bound.typed(new Expression.Cast(r.expression(), other));
            }
        }
        if (equality) {
            DataType number = fractionalWithBoolean(l.type(), r.type());
            if (number != null) {
                l = Bound.typed(to(l, number, position));
                r = Bound.typed(to(r, number, position));
            }
        }
        DataType a = l.type();
        DataType c = r.type();
        check(() -> TypeRules.common(a, c), position,
                "Cannot compare values of type " + a + " and type " + c + " - an explicit cast is required");
        return new Expression[] {l.expression(), r.expression()};
    }

    /** The expression of a bound value, cast to a type unless it has it. */
    private static Expression as(Bound b, DataType type) {
        return b.type().equals(type) ? b.expression() : new Expression.Cast(b.expression(), type);
    }

    /**
     * For a {@code BOOLEAN} and a floating-point or decimal type, the latter,
     * to which DuckDB casts the boolean for an equality; {@code null} for
     * other pairs. A boolean and an integral type have a common type anyway.
     */
    static DataType fractionalWithBoolean(DataType a, DataType b) {
        if (a == DataType.BOOLEAN && b.isNumeric() && !b.isIntegral()) {
            return b;
        }
        if (b == DataType.BOOLEAN && a.isNumeric() && !a.isIntegral()) {
            return a;
        }
        return null;
    }

    private Bound unary(SqlExpr.Unary u) {
        return switch (u.op()) {
            case NOT -> Bound.typed(new Expression.Not(condition(u.operand())));
            case MINUS -> {
                if (u.operand() instanceof SqlExpr.Literal l && l.kind() == SqlExpr.LiteralKind.NUMBER) {
                    yield Literals.number(l.text(), true, l.position());
                }
                Bound b = bound(u.operand());
                if (b.kind() == Kind.PARAMETER) {
                    yield Bound.typed(new Expression.Negate(typed(b, DataType.BIGINT).expression()));
                }
                if (b.isNull()) {
                    yield nullOf(DataType.BIGINT);
                }
                if (b.kind() == Kind.INTEGER) {
                    // DuckDB folds the minus into the literal, which keeps adapting to its context.
                    String digits = b.literal().value().toString();
                    yield digits.startsWith("-") ? Literals.number(digits.substring(1), false, u.position())
                            : Literals.number(digits, true, u.position());
                }
                yield foldNull(check(() -> new Expression.Negate(b.expression()), u.position(),
                        "No operator - for " + b.type()));
            }
            case PLUS -> {
                Bound b = bound(u.operand());
                if (!b.isNull() && !b.type().isNumeric()) {
                    throw new SqlException("No operator + for " + b.type(), u.position());
                }
                yield b;
            }
        };
    }

    private Bound between(SqlExpr.Between b) {
        Bound value = bound(b.value());
        Bound low = bound(b.low());
        Bound high = bound(b.high());
        List<Bound> operands = unifyInOrder(List.of(value, low, high), "BETWEEN clause", b.position());
        DataType type = check(() -> TypeRules.common(operands.stream().map(Bound::type).toList()), b.position(),
                "Cannot mix values of the types of a BETWEEN clause - an explicit cast is required");
        if (value.kind() != Kind.PARAMETER && constantNull(value.expression())) {
            // As in DuckDB, a constant NULL value is NULL without evaluating the bounds, whose types still unite.
            return nullOf(DataType.BOOLEAN);
        }
        Expression between = new Expression.Between(as(operands.get(0), type), as(operands.get(1), type),
                as(operands.get(2), type));
        return Bound.typed(b.negated() ? new Expression.Not(between) : between);
    }

    private Bound in(SqlExpr.InList in) {
        List<Bound> operands = new ArrayList<>();
        operands.add(bound(in.value()));
        for (SqlExpr e : in.list()) {
            operands.add(bound(e));
        }
        // The common type of the typed operands other than VARCHAR, which are cast to it as for '='.
        DataType target = null;
        boolean varchar = false;
        for (Bound b : operands) {
            if (b.kind() == Kind.STRING || b.isNull()) {
                continue;
            }
            if (b.type() == DataType.VARCHAR) {
                varchar = true;
                continue;
            }
            DataType t = target;
            DataType number = t == null ? null : fractionalWithBoolean(t, b.type());
            target = t == null ? b.type() : number != null ? number : check(() -> TypeRules.common(t, b.type()),
                    in.position(), "Cannot compare values of type " + t + " and type " + b.type()
                            + " - an explicit cast is required");
        }
        if (target == null) {
            target = varchar || operands.stream().anyMatch(o -> o.kind() == Kind.STRING) ? DataType.VARCHAR
                    : DataType.INTEGER;
        }
        List<Expression> list = new ArrayList<>();
        for (Bound b : operands) {
            list.add(to(b, target, in.position()));
        }
        Expression result = new Expression.In(list.get(0), list.subList(1, list.size()));
        return Bound.typed(in.negated() ? new Expression.Not(result) : result);
    }

    /**
     * Converts a literal, or casts a {@code VARCHAR} value or a boolean
     * compared with a fractional number, to a type; other values keep theirs.
     */
    private Expression to(Bound b, DataType target, Position position) {
        if (b.isNull()) {
            return typed(b, target).expression();
        }
        if (b.kind() == Kind.STRING) {
            return lazily(b.literal(), target, position);
        }
        if (b.type() == DataType.VARCHAR && target != DataType.VARCHAR) {
            if (target.isNested()) {
                throw new SqlException("Cannot compare values of type VARCHAR and type " + target, position);
            }
            return new Expression.Cast(b.expression(), target);
        }
        if (b.type() == DataType.BOOLEAN && fractionalWithBoolean(b.type(), target) != null) {
            return new Expression.Cast(b.expression(), target);
        }
        return b.expression();
    }

    private Bound like(SqlExpr.Like l) {
        Bound value = bound(l.value());
        Bound pattern = bound(l.pattern());
        Expression v = value.isNull() ? typed(value, DataType.VARCHAR).expression() : value.expression();
        Expression p = pattern.isNull() ? typed(pattern, DataType.VARCHAR).expression() : pattern.expression();
        if (v.type() != DataType.VARCHAR || p.type() != DataType.VARCHAR) {
            throw new SqlException((l.caseInsensitive() ? "ILIKE" : "LIKE") + " needs VARCHAR operands, not "
                    + v.type() + " and " + p.type(), l.position());
        }
        Expression like = new Expression.Like(v, p, l.caseInsensitive());
        return Bound.typed(l.negated() ? new Expression.Not(like) : like);
    }

    private Bound caseWhen(SqlExpr.Case c) {
        Bound operand = c.operand() != null ? bound(c.operand()) : null;
        List<Expression> conditions = new ArrayList<>();
        List<Bound> results = new ArrayList<>();
        for (SqlExpr.When w : c.whens()) {
            if (operand != null) {
                Expression[] operands = equality(operand, bound(w.condition()), w.condition().position());
                conditions.add(new Expression.Comparison(ComparisonOp.EQ, operands[0], operands[1]));
            } else {
                conditions.add(condition(w.condition()));
            }
            results.add(bound(w.result()));
        }
        results.add(c.otherwise() != null ? bound(c.otherwise()) : Bound.nullLiteral());
        results = unifyCase(results, "CASE expression", c.position());
        List<Expression.When> whens = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            whens.add(new Expression.When(conditions.get(i), results.get(i).expression()));
        }
        return openIfNull(new Expression.Case(whens, results.get(results.size() - 1).expression()), results);
    }

    /**
     * Gives the results of a {@code CASE}, the {@code ELSE} last, one type
     * as DuckDB does: from left to right as {@link #unifyInOrder}, but
     * starting with the {@code ELSE}; and each typed result must unite with
     * that type, so that a boolean, which unites with an integer, does not
     * with a decimal that comes later.
     */
    private List<Bound> unifyCase(List<Bound> results, String what, Position position) {
        List<Bound> ordered = new ArrayList<>();
        ordered.add(results.get(results.size() - 1));
        ordered.addAll(results.subList(0, results.size() - 1));
        List<Bound> united = unifyInOrder(ordered, what, position);
        if (!united.isEmpty() && !united.stream().allMatch(Bound::isNull)) {
            DataType type = united.stream().filter(b -> !b.isNull()).map(Bound::type).reduce((a, b) ->
                    mix(a, b, what, position)).orElseThrow();
            for (Bound b : ordered) {
                if (b.kind() == Kind.TYPED) {
                    mix(type, b.type(), what, position);
                }
            }
            if (type.isNested()) {
                // The union of structs depends on the order, which starts with the ELSE.
                List<Bound> cast = new ArrayList<>();
                for (Bound b : united) {
                    cast.add(b.isNull() ? b : Bound.typed(as(b, type)));
                }
                united = cast;
            }
        }
        List<Bound> result = new ArrayList<>(united.subList(1, united.size()));
        result.add(united.get(0));
        return result;
    }

    /** A value of results that are all bare {@code NULL}s is one too, whose type stays open, as in DuckDB. */
    private static Bound openIfNull(Expression e, List<Bound> results) {
        return new Bound(e, results.stream().allMatch(Bound::isNull) ? Kind.NULL : Kind.TYPED);
    }

    /**
     * Gives values that are united into one, like the branches of
     * {@code CASE}, one type: the common type of the typed values; integer
     * literals that fit an integral one take it; string literals and bare
     * {@code NULL}s are converted to it. Without a typed value integer
     * literals decide, then string literals ({@code VARCHAR}); bare
     * {@code NULL}s alone stay as they are.
     */
    List<Bound> unify(List<Bound> values, String what, Position position) {
        DataType target = null;
        for (Bound b : values) {
            if (b.kind() == Kind.TYPED) {
                target = target == null ? b.type() : mix(target, b.type(), what, position);
            }
        }
        List<Bound> integers = values.stream().filter(b -> b.kind() == Kind.INTEGER).toList();
        if (target == null) {
            if (!integers.isEmpty()) {
                for (Bound b : integers) {
                    target = target == null ? b.type() : mix(target, b.type(), what, position);
                }
            } else if (values.stream().anyMatch(b -> b.kind() == Kind.STRING)) {
                target = DataType.VARCHAR;
            } else {
                return values;
            }
        } else {
            for (Bound b : integers) {
                if (!target.isIntegral() || !Literals.fits(b.literal(), target)) {
                    target = mix(target, b.type(), what, position);
                }
            }
        }
        List<Bound> result = new ArrayList<>();
        for (Bound b : values) {
            result.add(switch (b.kind()) {
                case NULL, PARAMETER -> typed(b, target);
                case STRING -> target == DataType.VARCHAR ? b : Bound.typed(lazily(b.literal(), target,
                        position));
                case INTEGER -> target.isIntegral() ? new Bound(Literals.convert(b.literal(), target, position),
                        Kind.INTEGER) : b;
                case TYPED -> b;
            });
        }
        return result;
    }

    /**
     * Gives values one type from left to right, as DuckDB does for
     * {@code COALESCE}, {@code BETWEEN}, {@code greatest} and {@code least}: two literals of a kind unite to
     * the type of neither kind, so that {@code coalesce(1, 2, tinyint)} is an
     * {@code INTEGER} and two string literals are {@code VARCHAR}, which then
     * unites with nothing but text; otherwise a literal takes the type of the
     * other side. An integer literal that an integral type takes keeps that
     * type when a later value widens it to another kind, so that
     * {@code coalesce(smallint, 0, 1.5)} is a {@code DECIMAL(6,1)} as in
     * DuckDB.
     */
    List<Bound> unifyInOrder(List<Bound> values, String what, Position position) {
        DataType type = null;
        Kind kind = null;
        boolean nullFirst = false;
        int first = -1;
        Map<Integer, DataType> taken = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            Bound b = values.get(i);
            if (b.isNull()) {
                nullFirst |= type == null;
                if (kind == Kind.INTEGER || kind == Kind.STRING) {
                    // DuckDB unites a literal and a bare NULL to the literal's own type.
                    kind = Kind.TYPED;
                }
                continue;
            }
            if (type == null) {
                // A literal after a bare NULL is typed: DuckDB unites the two to the literal's own type.
                type = b.type();
                kind = nullFirst ? Kind.TYPED : b.kind();
                first = i;
            } else if (kind == Kind.STRING && b.kind() == Kind.STRING) {
                kind = Kind.TYPED;
            } else if (kind == Kind.STRING) {
                type = b.type();
                kind = b.kind();
            } else if (b.kind() == Kind.STRING) {
                continue;
            } else if (kind == Kind.INTEGER && b.kind() == Kind.INTEGER) {
                type = mix(type, b.type(), what, position);
                kind = Kind.TYPED;
            } else if (kind == Kind.INTEGER) {
                if (b.type().isIntegral() && Literals.fits(values.get(first).literal(), b.type())) {
                    type = b.type();
                    taken.put(first, type);
                } else {
                    type = mix(type, b.type(), what, position);
                }
                kind = Kind.TYPED;
            } else if (b.kind() == Kind.INTEGER && type.isIntegral() && Literals.fits(b.literal(), type)) {
                taken.put(i, type);
            } else {
                type = mix(type, b.type(), what, position);
            }
        }
        if (type == null) {
            return values;
        }
        List<Bound> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Bound b = values.get(i);
            if (b.isNull()) {
                result.add(typed(b, type));
            } else if (b.kind() == Kind.STRING && type != DataType.VARCHAR) {
                result.add(Bound.typed(lazily(b.literal(), type, position)));
            } else if (b.kind() == Kind.INTEGER && type.isIntegral() && !b.type().equals(type)
                    && Literals.fits(b.literal(), type)) {
                result.add(Bound.typed(Literals.convert(b.literal(), type, position)));
            } else if (b.kind() == Kind.INTEGER && taken.containsKey(i) && !type.isIntegral()) {
                result.add(Bound.typed(Literals.convert(b.literal(), taken.get(i), position)));
            } else {
                result.add(b);
            }
        }
        return result;
    }

    /**
     * Gives the values of a column of {@code VALUES} one type as DuckDB
     * does, row by row: the first value that is no bare {@code NULL} sets the
     * type, a string literal as {@code VARCHAR} and an integer literal as its
     * own type; a later string literal takes the type, a later integer
     * literal keeps an integral type that holds it, and other values unite
     * with it, except that nothing but text unites with {@code VARCHAR}.
     */
    List<Bound> unifyRows(List<Bound> values, Position position) {
        DataType target = null;
        for (Bound b : values) {
            if (b.isNull()) {
                continue;
            }
            if (target == null) {
                target = b.type();
            } else if (b.kind() == Kind.STRING) {
                continue;
            } else if (target == DataType.VARCHAR && b.type() != DataType.VARCHAR) {
                throw new SqlException("Cannot combine types VARCHAR and "
                        + (b.kind() == Kind.INTEGER ? "INTEGER_LITERAL" : b.type().sqlName())
                        + " - an explicit cast is required", position);
            } else if (b.kind() != Kind.INTEGER || !target.isIntegral() || !Literals.fits(b.literal(), target)) {
                target = mix(target, b.type(), "VALUES list", position);
            }
        }
        if (target == null) {
            return values;
        }
        List<Bound> result = new ArrayList<>();
        for (Bound b : values) {
            if (b.isNull()) {
                result.add(typed(b, target));
            } else if (b.kind() == Kind.STRING && target != DataType.VARCHAR
                    || b.kind() == Kind.INTEGER && target.isIntegral() && !b.type().equals(target)) {
                result.add(Bound.typed(Literals.convert(b.literal(), target, position, context.zone())));
            } else {
                result.add(b);
            }
        }
        return result;
    }

    private static DataType mix(DataType a, DataType b, String what, Position position) {
        return check(() -> TypeRules.common(a, b), position,
                "Cannot mix values of type " + a + " and " + b + " in " + what + " - an explicit cast is required");
    }

    /**
     * {@code CAST(x AS T)}. The empty list {@code []} casts to every list
     * type, as in DuckDB, though on its own it is an {@code INTEGER[]}.
     */
    private Bound cast(SqlExpr.Cast c) {
        if (c.type() instanceof DataType.ListType && c.operand() instanceof SqlExpr.FunctionCall f
                && SqlTrees.key(f.name()).equals("list_value") && f.arguments().isEmpty()) {
            return Bound.typed(new Expression.Literal(c.type(), List.of()));
        }
        Bound operand = bound(c.operand());
        if (operand.isNull()) {
            return typed(operand, c.type());
        }
        if (!Casts.allowed(operand.type(), c.type())) {
            throw new SqlException("Cannot cast " + operand.type() + " to " + c.type(), c.position());
        }
        return Bound.typed(new Expression.Cast(operand.expression(), c.type(), c.safe()));
    }

    /** Converts a value to {@code BOOLEAN} for a condition. */
    private Expression toBoolean(Bound b, Position position) {
        if (b.type() == DataType.BOOLEAN && !b.isNull()) {
            return b.expression();
        }
        if (b.isNull()) {
            return typed(b, DataType.BOOLEAN).expression();
        }
        if (b.kind() == Kind.STRING) {
            return lazily(b.literal(), DataType.BOOLEAN, position);
        }
        if (!Casts.allowed(b.type(), DataType.BOOLEAN)) {
            throw new SqlException("Cannot use a value of type " + b.type() + " as a condition", position);
        }
        return new Expression.Cast(b.expression(), DataType.BOOLEAN);
    }

    private Bound call(SqlExpr.FunctionCall f) {
        String name = SqlTrees.key(f.name());
        Position p = f.position();
        if (SqlTrees.isAggregate(name)) {
            throw new SqlException(aggregateError != null ? aggregateError
                    : "Aggregate " + name + " is not allowed here", p);
        }
        if (name.equals("unnest")) {
            // The select list unnests through its hook; anywhere else is no place for it.
            throw new SqlException("UNNEST not supported here", p);
        }
        if (f.distinct() || f.star() || !f.orderBy().isEmpty()) {
            throw new SqlException("Not supported: " + (f.star() ? "*" : f.distinct() ? "DISTINCT" : "ORDER BY")
                    + " in a call of the scalar function " + name, p);
        }
        List<SqlExpr> args = f.arguments();
        switch (name) {
            case "coalesce", "ifnull" -> {
                if (args.isEmpty() || name.equals("ifnull") && args.size() != 2) {
                    throw arity(name, p);
                }
                List<Bound> operands = unifyInOrder(boundAll(args), "COALESCE operator", p);
                return openIfNull(new Expression.Coalesce(operands.stream().map(Bound::expression).toList()),
                        operands);
            }
            case "if" -> {
                if (args.size() != 3) {
                    throw arity(name, p);
                }
                Expression condition = condition(args.get(0));
                List<Bound> branches = unifyCase(boundAll(args.subList(1, 3)), "IF function", p);
                return openIfNull(new Expression.If(condition, branches.get(0).expression(),
                        branches.get(1).expression()), branches);
            }
            case "nullif" -> {
                if (args.size() != 2) {
                    throw arity(name, p);
                }
                List<Bound> operands = unify(boundAll(args), "NULLIF function", p);
                Expression value = operands.get(0).expression();
                Expression[] compared = equality(operands.get(0), operands.get(1), p);
                return Bound.typed(new Expression.Case(List.of(new Expression.When(
                        new Expression.Comparison(ComparisonOp.EQ, compared[0], compared[1]),
                        new Expression.Literal(value.type(), null))), value));
            }
            case "typeof" -> {
                if (args.size() != 1) {
                    throw arity(name, p);
                }
                Bound b = bound(args.get(0));
                return Bound.typed(new Expression.Literal(DataType.VARCHAR,
                        b.isNull() ? "\"NULL\"" : b.type().sqlName()));
            }
            case "current_date", "today" -> {
                if (!args.isEmpty()) {
                    throw arity(name, p);
                }
                return Bound.typed(new Expression.Literal(DataType.DATE, Dates.dayOf(localNow())));
            }
            case "now", "current_timestamp", "get_current_timestamp", "transaction_timestamp" -> {
                if (!args.isEmpty()) {
                    throw arity(name, p);
                }
                return Bound.typed(new Expression.Literal(DataType.TIMESTAMPTZ, nowMicros()));
            }
            case "current_time", "current_localtime", "localtime" -> {
                // DuckDB gives current_time a TIME WITH TIME ZONE, which FyrQL does not have.
                if (!args.isEmpty()) {
                    throw arity(name, p);
                }
                return Bound.typed(new Expression.Literal(DataType.TIME, Dates.timeOf(localNow())));
            }
            case "current_localtimestamp", "localtimestamp" -> {
                if (!args.isEmpty()) {
                    throw arity(name, p);
                }
                return Bound.typed(new Expression.Literal(DataType.TIMESTAMP, localNow()));
            }
            case "current_setting" -> {
                if (args.size() != 1 || !(args.get(0) instanceof SqlExpr.Literal l)
                        || l.kind() != SqlExpr.LiteralKind.STRING) {
                    throw new SqlException("current_setting requires a constant name", p);
                }
                String setting = Settings.name(l.text());
                if (setting == null) {
                    throw new SqlException("unrecognized configuration parameter \"" + l.text() + "\"", p);
                }
                return Bound.typed(new Expression.Literal(DataType.VARCHAR, context.setting(setting)));
            }
            case "age" -> {
                if (args.size() == 1) {
                    // The age at midnight of the current date.
                    Bound x = bound(args.get(0));
                    long midnight = Dates.timestamp(Dates.dayOf(localNow()), 0);
                    Bound start = x.type() == DataType.TIMESTAMPTZ && !x.isNull()
                            ? Bound.typed(new Expression.Literal(DataType.TIMESTAMPTZ,
                                    ZonedDates.toInstant(midnight, context.zone())))
                            : Bound.typed(new Expression.Literal(DataType.TIMESTAMP, midnight));
                    return foldNull(call("age", List.of(start, x), p).expression());
                }
            }
            case "greatest", "least" -> {
                return foldNull(call(name, unifyInOrder(boundAll(args), name + " function", p), p).expression());
            }
            case "date_part", "datepart" -> {
                // A constant 'epoch' gives the seconds with their fraction, a DOUBLE.
                if (args.size() == 2 && args.get(0) instanceof SqlExpr.Literal l
                        && l.kind() == SqlExpr.LiteralKind.STRING && l.text().equalsIgnoreCase("epoch")) {
                    return foldNull(call("epoch", boundAll(args.subList(1, 2)), p).expression());
                }
            }
            case "list_value" -> {
                return listValue(boundAll(args), p);
            }
            case "struct_extract" -> {
                if (args.size() == 2) {
                    Bound base = bound(args.get(0));
                    if (base.type() instanceof DataType.Struct && !base.isNull()) {
                        return index(base, args.get(1), p);
                    }
                }
                throw new SqlException("No function matches struct_extract with these arguments", p);
            }
            default -> {
            }
        }
        Bound result = call(name, templateArguments(name, boundAll(args), p), p);
        checkFormat(name, args, (Expression.Call) result.expression(), p);
        return foldNull(result.expression());
    }

    /**
     * {@code [a, b, ...]}: a list whose elements take one type as DuckDB
     * deduces it, see {@link #unify}. The elements of a list of bare
     * {@code NULL}s, and of the empty list, are {@code INTEGER}s; DuckDB
     * gives them a type of its own for {@code NULL}.
     */
    private Bound listValue(List<Bound> elements, Position position) {
        List<Bound> united = unify(elements, "list_value", position);
        DataType type = united.stream().map(Bound::type).reduce((a, b) -> mix(a, b, "list_value", position))
                .orElse(DataType.INTEGER);
        List<Expression> arguments = new ArrayList<>();
        for (Bound b : united) {
            arguments.add(as(b, type));
        }
        return Bound.typed(new Expression.Call(context.functions().resolve("list_value",
                arguments.stream().map(Expression::type).toList()), arguments));
    }

    private static SqlException arity(String name, Position position) {
        return new SqlException("Wrong number of arguments for the function " + name, position);
    }

    private List<Bound> boundAll(List<SqlExpr> args) {
        List<Bound> result = new ArrayList<>();
        for (SqlExpr a : args) {
            result.add(bound(a));
        }
        return result;
    }

    /**
     * The types a bare {@code NULL} argument is tried with, in the order of
     * DuckDB's costs of casting a {@code NULL} to them: {@code BIGINT}, then
     * {@code DOUBLE}, {@code INTEGER} and decimals, most other types, then
     * {@code HUGEINT} and {@code TIMESTAMP}, and {@code VARCHAR} last.
     */
    private static final List<DataType> NULL_ARGUMENT_TYPES = List.of(DataType.BIGINT, DataType.DOUBLE,
            DataType.INTEGER, DataType.decimal(18, 3), DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.FLOAT, DataType.DATE, DataType.HUGEINT, DataType.TIMESTAMP, DataType.VARCHAR);

    /**
     * Calls a scalar function of the catalog; bare {@code NULL} arguments
     * and parameters take the first type that fits.
     */
    private Bound call(String name, List<Bound> args, Position position) {
        if (!context.functionNames().contains(name)) {
            throw new SqlException("Function with name " + name + " does not exist", position);
        }
        List<DataType> candidates = new ArrayList<>();
        if (args.stream().anyMatch(Bound::isNull)) {
            candidates.addAll(NULL_ARGUMENT_TYPES);
        } else {
            candidates.add(null);
        }
        for (DataType candidate : candidates) {
            List<Expression> arguments = new ArrayList<>();
            for (Bound a : args) {
                arguments.add(a.kind() == Kind.PARAMETER ? ParameterTypes.placeholder(a.parameter(), candidate)
                        : a.isNull() ? new Expression.Literal(candidate, null) : a.expression());
            }
            List<DataType> types = arguments.stream().map(Expression::type).toList();
            ScalarFunction function;
            try {
                function = context.functions().resolve(name, types);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ScalarFunction resolved = function;
            Expression call = new Expression.Call(check(() -> resolved.specialize(arguments), position, null),
                    arguments);
            for (Bound a : args) {
                if (a.kind() == Kind.PARAMETER) {
                    typed(a, candidate);
                }
            }
            return Bound.typed(call);
        }
        List<String> types = new ArrayList<>();
        for (Bound a : args) {
            types.add(a.kind() == Kind.PARAMETER ? "?" : a.isNull() ? "NULL" : a.type().sqlName());
        }
        throw new SqlException("No function matches " + name + "(" + String.join(", ", types) + ")", position);
    }

    /** The time of the query as microseconds since 1970-01-01 00:00:00 UTC. */
    private long nowMicros() {
        return Math.addExact(Math.multiplyExact(context.now().getEpochSecond(), 1_000_000L),
                context.now().getNano() / 1_000);
    }

    /** The time of the query as the local timestamp in the time zone of the session. */
    private long localNow() {
        return ZonedDates.toLocal(nowMicros(), context.zone());
    }

    /** Checks that the format of strftime or strptime is a constant with known specifiers. */
    private static void checkFormat(String name, List<SqlExpr> args, Expression.Call call, Position position) {
        int index;
        switch (name) {
            case "strftime" -> index = call.argumentTypes().get(0) == DataType.VARCHAR ? 0 : 1;
            case "strptime", "try_strptime" -> index = 1;
            default -> {
                return;
            }
        }
        if (!(args.get(index) instanceof SqlExpr.Literal l) || l.kind() != SqlExpr.LiteralKind.STRING) {
            throw new SqlException(name + " format must be a constant", args.get(index).position());
        }
        check(() -> {
            DateFormats.check(l.text());
            return l;
        }, position, null);
    }

    /**
     * Runs a step that may reject its operands with an
     * {@link IllegalArgumentException}, as the constructors of
     * {@link Expression} do, and reports that as a {@link SqlException}.
     *
     * @param message the message, or {@code null} for that of the exception
     */
    static <T> T check(Supplier<T> step, Position position, String message) {
        try {
            return step.get();
        } catch (IllegalArgumentException e) {
            throw new SqlException(message != null ? message : e.getMessage(), position);
        }
    }
}
