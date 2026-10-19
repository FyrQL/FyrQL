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

package net.fyrql.sql.ast;

import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * An expression as written in the query, before names, functions and types
 * are resolved. Names keep their spelling; identifiers in double quotes come
 * without the quotes. Every node knows its {@link #position()} in the query
 * text, which takes no part in the semantics of the tree.
 */
public sealed interface SqlExpr {

    /** Returns where the expression starts in the query text. */
    Position position();

    /** The kinds of literals. */
    enum LiteralKind {
        /** A number as written: an integer, a decimal such as {@code 1.50}, or one with an exponent. */
        NUMBER,
        /** A string; the text is its value, without quotes and escapes. */
        STRING,
        /** {@code TRUE} or {@code FALSE}; the text is {@code true} or {@code false}. */
        BOOLEAN,
        /** {@code NULL}; the text is {@code NULL}. */
        NULL,
        /** {@code DATE '...'}; the text is the string. */
        DATE,
        /** {@code TIMESTAMP '...'}; the text is the string. */
        TIMESTAMP
    }

    /** The binary operators. */
    enum BinaryOp {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), INT_DIVIDE("//"), MODULO("%"), CONCAT("||"),
        EQUAL("="), NOT_EQUAL("<>"), LESS("<"), LESS_EQUAL("<="), GREATER(">"), GREATER_EQUAL(">="),
        AND("AND"), OR("OR");

        private final String symbol;

        BinaryOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** The unary operators. */
    enum UnaryOp {
        NOT("NOT "), MINUS("-"), PLUS("+");

        private final String symbol;

        UnaryOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** A constant; see {@link LiteralKind} for its text. */
    record Literal(LiteralKind kind, String text, Position position) implements SqlExpr {
        public Literal {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * A parameter {@code ?} of a prepared query, numbered from 1 in the
     * order of the text. Its type comes from its context when the query is
     * bound, as the type of a bare {@code NULL} does.
     */
    record Parameter(int index, Position position) implements SqlExpr {
        public Parameter {
            if (index < 1) {
                throw new IllegalArgumentException("parameters are numbered from 1, not " + index);
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /** A name of one or more parts, such as {@code a}, {@code t.a} or {@code s.t.a}. */
    record Identifier(List<String> parts, Position position) implements SqlExpr {
        public Identifier {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("an identifier needs a part");
            }
            Objects.requireNonNull(position, "position");
        }

        /** Returns the last part, the name of the column. */
        public String name() {
            return parts.get(parts.size() - 1);
        }
    }

    /** An operator between two operands. */
    record Binary(BinaryOp op, SqlExpr left, SqlExpr right, Position position) implements SqlExpr {
        public Binary {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(position, "position");
        }
    }

    /** An operator before one operand. */
    record Unary(UnaryOp op, SqlExpr operand, Position position) implements SqlExpr {
        public Unary {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code value [NOT] BETWEEN low AND high}. */
    record Between(SqlExpr value, SqlExpr low, SqlExpr high, boolean negated, Position position) implements SqlExpr {
        public Between {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(low, "low");
            Objects.requireNonNull(high, "high");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code value [NOT] IN (list)}. */
    record InList(SqlExpr value, List<SqlExpr> list, boolean negated, Position position) implements SqlExpr {
        public InList {
            Objects.requireNonNull(value, "value");
            list = List.copyOf(list);
            if (list.isEmpty()) {
                throw new IllegalArgumentException("IN needs at least one value");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code value [NOT] IN (query)}, where the query returns one column. */
    record InQuery(SqlExpr value, Query query, boolean negated, Position position) implements SqlExpr {
        public InQuery {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code [NOT] EXISTS (query)}. */
    record Exists(Query query, boolean negated, Position position) implements SqlExpr {
        public Exists {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code (query)} as a value: the one column of the at most one row the query returns. */
    record ScalarSubquery(Query query, Position position) implements SqlExpr {
        public ScalarSubquery {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code value [NOT] LIKE pattern}, or {@code ILIKE} when case-insensitive. */
    record Like(SqlExpr value, SqlExpr pattern, boolean caseInsensitive, boolean negated, Position position)
            implements SqlExpr {
        public Like {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code operand IS [NOT] NULL}. */
    record IsNull(SqlExpr operand, boolean negated, Position position) implements SqlExpr {
        public IsNull {
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * {@code left IS DISTINCT FROM right}, or {@code IS NOT DISTINCT FROM}
     * when negated; {@code x IS [NOT] TRUE} and {@code FALSE} are written
     * this way.
     */
    record IsDistinct(SqlExpr left, SqlExpr right, boolean negated, Position position) implements SqlExpr {
        public IsDistinct {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(position, "position");
        }
    }

    /** One {@code WHEN condition THEN result} of a {@link Case}. */
    record When(SqlExpr condition, SqlExpr result) {
        public When {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * {@code CASE [operand] WHEN ... THEN ... [ELSE otherwise] END}; with an
     * operand, each condition is a value compared to it. The operand and
     * {@code otherwise} may be {@code null}.
     */
    record Case(SqlExpr operand, List<When> whens, SqlExpr otherwise, Position position) implements SqlExpr {
        public Case {
            whens = List.copyOf(whens);
            if (whens.isEmpty()) {
                throw new IllegalArgumentException("CASE needs at least one WHEN");
            }
            Objects.requireNonNull(position, "position");
        }
    }

    /** {@code CAST(operand AS type)}, {@code operand::type}, or {@code TRY_CAST} when safe. */
    record Cast(SqlExpr operand, DataType type, boolean safe, Position position) implements SqlExpr {
        public Cast {
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * A call of a scalar or aggregate function by name: {@code f(args)},
     * {@code f(DISTINCT args ORDER BY ...)}, or {@code f(*)} when star.
     * The special forms {@code EXTRACT}, {@code TRIM}, {@code SUBSTRING},
     * {@code POSITION}, {@code CURRENT_DATE} and so on are calls of the
     * functions of the same meaning.
     */
    record FunctionCall(String name, List<SqlExpr> arguments, boolean distinct, boolean star,
            List<OrderItem> orderBy, Position position) implements SqlExpr {
        public FunctionCall {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
            orderBy = List.copyOf(orderBy);
            if (star && !arguments.isEmpty()) {
                throw new IllegalArgumentException("f(*) takes no arguments");
            }
            for (OrderItem item : orderBy) {
                if (item.isAll()) {
                    throw new IllegalArgumentException("ORDER BY ALL inside a function");
                }
            }
            Objects.requireNonNull(position, "position");
        }

        /** A call without {@code DISTINCT}, {@code *} and {@code ORDER BY}. */
        public FunctionCall(String name, List<SqlExpr> arguments, Position position) {
            this(name, arguments, false, false, List.of(), position);
        }
    }

    /**
     * A call of a window function or of an aggregate over a window:
     * {@code f(args [IGNORE NULLS]) [FILTER (WHERE filter)] OVER window}.
     * The filter may be {@code null}.
     */
    record Window(FunctionCall call, WindowSpec over, boolean ignoreNulls, SqlExpr filter, Position position)
            implements SqlExpr {
        public Window {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(over, "over");
            Objects.requireNonNull(position, "position");
        }

        /**
         * Returns the expressions the window function reads, in this order:
         * the arguments and the order of the call, the expressions of
         * {@code PARTITION BY} and {@code ORDER BY}, the offsets of the frame
         * and the filter. The call itself is no operand.
         */
        public List<SqlExpr> operands() {
            List<SqlExpr> result = new ArrayList<>(call.arguments());
            for (OrderItem o : call.orderBy()) {
                result.add(o.expression());
            }
            result.addAll(over.partitionBy());
            for (OrderItem o : over.orderBy()) {
                result.add(o.expression());
            }
            if (over.frame() != null) {
                if (over.frame().start().offset() != null) {
                    result.add(over.frame().start().offset());
                }
                if (over.frame().end().offset() != null) {
                    result.add(over.frame().end().offset());
                }
            }
            if (filter != null) {
                result.add(filter);
            }
            return result;
        }

        /** Returns the same window function with other operands, in the order of {@link #operands()}. */
        public Window withOperands(List<SqlExpr> operands) {
            Iterator<SqlExpr> it = operands.iterator();
            List<SqlExpr> arguments = new ArrayList<>();
            for (int i = 0; i < call.arguments().size(); i++) {
                arguments.add(it.next());
            }
            List<OrderItem> callOrder = new ArrayList<>();
            for (OrderItem o : call.orderBy()) {
                callOrder.add(new OrderItem(it.next(), o.ascending(), o.nullsFirst()));
            }
            List<SqlExpr> partitionBy = new ArrayList<>();
            for (int i = 0; i < over.partitionBy().size(); i++) {
                partitionBy.add(it.next());
            }
            List<OrderItem> orderBy = new ArrayList<>();
            for (OrderItem o : over.orderBy()) {
                orderBy.add(new OrderItem(it.next(), o.ascending(), o.nullsFirst()));
            }
            WindowSpec.Frame frame = over.frame();
            if (frame != null) {
                WindowSpec.Bound start = frame.start().offset() == null ? frame.start()
                        : new WindowSpec.Bound(frame.start().kind(), it.next());
                WindowSpec.Bound end = frame.end().offset() == null ? frame.end()
                        : new WindowSpec.Bound(frame.end().kind(), it.next());
                frame = new WindowSpec.Frame(frame.unit(), start, end, frame.exclusion());
            }
            SqlExpr newFilter = filter == null ? null : it.next();
            if (it.hasNext()) {
                throw new IllegalArgumentException("too many operands");
            }
            FunctionCall newCall = new FunctionCall(call.name(), arguments, call.distinct(), call.star(), callOrder,
                    call.position());
            return new Window(newCall, new WindowSpec(over.base(), partitionBy, orderBy, frame), ignoreNulls,
                    newFilter, position);
        }
    }

    /**
     * {@code INTERVAL 'n' unit}, also {@code INTERVAL n unit} and
     * {@code INTERVAL 'n unit'}: a whole number of years, months, days,
     * hours, minutes or seconds. The unit is in upper case, as written,
     * singular or plural. The binder takes an interval only as a constant
     * added to or subtracted from a constant date or timestamp.
     */
    record Interval(String value, String unit, Position position) implements SqlExpr {
        public Interval {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * {@code base[index]}: an element of a list, the value of a key of a map,
     * a field of a struct by a constant name, or a character of a string.
     * The access {@code base.name} to a field of an expression that is no
     * name is the index with the name as a string literal.
     */
    record Index(SqlExpr base, SqlExpr index, Position position) implements SqlExpr {
        public Index {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * {@code {'a': x, 'b': y}} or {@code struct_pack(a := x, b := y)}: a
     * struct of named values, at least one.
     */
    record StructValue(List<String> names, List<SqlExpr> values, Position position) implements SqlExpr {
        public StructValue {
            names = List.copyOf(names);
            values = List.copyOf(values);
            if (names.isEmpty() || names.size() != values.size()) {
                throw new IllegalArgumentException("a struct needs a name for each of at least one value");
            }
            Objects.requireNonNull(position, "position");
        }
    }
}
