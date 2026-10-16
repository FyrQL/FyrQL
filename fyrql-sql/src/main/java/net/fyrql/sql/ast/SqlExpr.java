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

package net.fyrql.sql.ast;

import net.fyrql.core.type.DataType;

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
