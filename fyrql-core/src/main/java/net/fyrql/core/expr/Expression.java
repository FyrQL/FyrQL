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

package net.fyrql.core.expr;

import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An expression over the columns of a row. Every node checks the types of
 * its operands when it is created and knows its result {@link #type()}; the
 * semantics, after DuckDB, are defined by the {@link Evaluator}.
 * <p>
 * Implicit conversions exist only where noted: the operands of arithmetic,
 * comparisons, {@code BETWEEN} and {@code IN}, and the branches of
 * {@code CASE}, {@code IF} and {@code COALESCE}, are converted to their
 * common type ({@link TypeRules}). Everything else must have the exact
 * type; a {@link Cast} converts explicitly. Columns are referenced by
 * position; a binder turns names into positions.
 */
public sealed interface Expression {

    /** Returns the type of the result. */
    DataType type();

    /** Returns the operands, in the order of evaluation. */
    List<Expression> children();

    /** The arithmetic operators: {@code + - * / // %}. */
    enum ArithmeticOp {
        ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), INT_DIVIDE("//"), REMAINDER("%");

        private final String symbol;

        ArithmeticOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** Returns the type of the result for two operand types. */
        public DataType resultType(DataType left, DataType right) {
            return switch (this) {
                case ADD -> TypeRules.add(left, right);
                case SUBTRACT -> TypeRules.subtract(left, right);
                case MULTIPLY -> TypeRules.multiply(left, right);
                case DIVIDE -> TypeRules.divide(left, right);
                case INT_DIVIDE -> TypeRules.intDivide(left, right);
                case REMAINDER -> TypeRules.remainder(left, right);
            };
        }
    }

    /** The comparison operators. */
    enum ComparisonOp {
        EQ("="), NE("<>"), LT("<"), LE("<="), GT(">"), GE(">=");

        private final String symbol;

        ComparisonOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** Tells whether a comparison result {@code c} (negative, zero or positive) satisfies the operator. */
        public boolean test(int c) {
            return switch (this) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
            };
        }
    }

    /** A constant, possibly {@code NULL}. */
    record Literal(DataType type, Object value) implements Expression {
        public Literal {
            Objects.requireNonNull(type, "type");
            Values.require(type, value);
        }

        @Override
        public List<Expression> children() {
            return List.of();
        }
    }

    /** The value of a column of the input row, by index. */
    record Column(int index, DataType type) implements Expression {
        public Column {
            Objects.requireNonNull(type, "type");
            if (index < 0) {
                throw new IllegalArgumentException("index: " + index);
            }
        }

        @Override
        public List<Expression> children() {
            return List.of();
        }
    }

    /**
     * {@code + - * / // %} on numeric operands; also a number of days added
     * to or subtracted from a {@code DATE}, and the days between two dates
     * ({@link TypeRules#add}, {@link TypeRules#subtract}).
     */
    record Arithmetic(ArithmeticOp op, Expression left, Expression right) implements Expression {
        public Arithmetic {
            Objects.requireNonNull(op, "op");
            op.resultType(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return op.resultType(left.type(), right.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** Unary minus. */
    record Negate(Expression operand) implements Expression {
        public Negate {
            TypeRules.requireNumeric(operand.type());
        }

        @Override
        public DataType type() {
            return operand.type();
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** A comparison in the common type of the operands. */
    record Comparison(ComparisonOp op, Expression left, Expression right) implements Expression {
        public Comparison {
            Objects.requireNonNull(op, "op");
            TypeRules.common(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code AND} with three-valued logic. */
    record And(Expression left, Expression right) implements Expression {
        public And {
            TypeRules.require(DataType.BOOLEAN, left.type(), "An operand of AND");
            TypeRules.require(DataType.BOOLEAN, right.type(), "An operand of AND");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code OR} with three-valued logic. */
    record Or(Expression left, Expression right) implements Expression {
        public Or {
            TypeRules.require(DataType.BOOLEAN, left.type(), "An operand of OR");
            TypeRules.require(DataType.BOOLEAN, right.type(), "An operand of OR");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** {@code NOT} with three-valued logic. */
    record Not(Expression operand) implements Expression {
        public Not {
            TypeRules.require(DataType.BOOLEAN, operand.type(), "The operand of NOT");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code IS NULL}, or {@code IS NOT NULL} if negated; never {@code NULL}. */
    record IsNull(Expression operand, boolean negated) implements Expression {
        public IsNull {
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /** {@code value BETWEEN low AND high}, i.e. {@code value >= low AND value <= high}. */
    record Between(Expression value, Expression low, Expression high) implements Expression {
        public Between {
            TypeRules.common(List.of(value.type(), low.type(), high.type()));
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(value, low, high);
        }
    }

    /** {@code value IN (list)}, each element compared like {@code =}. */
    record In(Expression value, List<Expression> list) implements Expression {
        public In {
            list = List.copyOf(list);
            if (list.isEmpty()) {
                throw new IllegalArgumentException("Empty IN list");
            }
            for (Expression e : list) {
                TypeRules.common(value.type(), e.type());
            }
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            List<Expression> result = new ArrayList<>();
            result.add(value);
            result.addAll(list);
            return result;
        }
    }

    /** One branch of a {@link Case}. */
    record When(Expression condition, Expression result) {
        public When {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "A condition of CASE");
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * {@code CASE WHEN c1 THEN r1 ... ELSE otherwise END}; a {@code NULL}
     * condition counts as false, and a branch is evaluated only for the rows
     * that select it.
     */
    record Case(List<When> whens, Expression otherwise) implements Expression {
        public Case {
            whens = List.copyOf(whens);
            if (whens.isEmpty()) {
                throw new IllegalArgumentException("CASE without WHEN");
            }
            Objects.requireNonNull(otherwise, "otherwise");
            resultTypes(whens, otherwise);
        }

        @Override
        public DataType type() {
            return resultTypes(whens, otherwise);
        }

        private static DataType resultTypes(List<When> whens, Expression otherwise) {
            List<DataType> types = new ArrayList<>();
            for (When w : whens) {
                types.add(w.result().type());
            }
            types.add(otherwise.type());
            return TypeRules.common(types);
        }

        @Override
        public List<Expression> children() {
            List<Expression> result = new ArrayList<>();
            for (When w : whens) {
                result.add(w.condition());
                result.add(w.result());
            }
            result.add(otherwise);
            return result;
        }
    }

    /** {@code if(condition, whenTrue, whenFalse)}; a {@code NULL} condition selects {@code whenFalse}. */
    record If(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression {
        public If {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "The condition of IF");
            TypeRules.common(whenTrue.type(), whenFalse.type());
        }

        @Override
        public DataType type() {
            return TypeRules.common(whenTrue.type(), whenFalse.type());
        }

        @Override
        public List<Expression> children() {
            return List.of(condition, whenTrue, whenFalse);
        }
    }

    /** The first operand that is not {@code NULL}. */
    record Coalesce(List<Expression> operands) implements Expression {
        public Coalesce {
            operands = List.copyOf(operands);
            if (operands.isEmpty()) {
                throw new IllegalArgumentException("COALESCE without operands");
            }
            TypeRules.common(operands.stream().map(Expression::type).toList());
        }

        @Override
        public DataType type() {
            return TypeRules.common(operands.stream().map(Expression::type).toList());
        }

        @Override
        public List<Expression> children() {
            return operands;
        }
    }

    /**
     * {@code CAST(operand AS target)}, for the pairs of types that
     * {@link Casts#allowed} permits; a {@code safe} cast ({@code TRY_CAST})
     * gives {@code NULL} where the value does not convert instead of an
     * error.
     */
    record Cast(Expression operand, DataType target, boolean safe) implements Expression {
        public Cast {
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(target, "target");
            if (!Casts.allowed(operand.type(), target)) {
                throw new IllegalArgumentException("Cannot cast " + operand.type() + " to " + target);
            }
        }

        public Cast(Expression operand, DataType target) {
            this(operand, target, false);
        }

        @Override
        public DataType type() {
            return target;
        }

        @Override
        public List<Expression> children() {
            return List.of(operand);
        }
    }

    /**
     * {@code string LIKE pattern}, see {@code Strings.like}; {@code ILIKE}
     * if case-insensitive, which lower-cases both sides as DuckDB does.
     */
    record Like(Expression string, Expression pattern, boolean caseInsensitive) implements Expression {
        public Like {
            TypeRules.require(DataType.VARCHAR, string.type(), "The operand of LIKE");
            TypeRules.require(DataType.VARCHAR, pattern.type(), "The pattern of LIKE");
        }

        public Like(Expression string, Expression pattern) {
            this(string, pattern, false);
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(string, pattern);
        }
    }

    /**
     * {@code left IS DISTINCT FROM right}, or {@code IS NOT DISTINCT FROM} if
     * negated: an equality in the common type that treats two {@code NULL}s
     * as equal and is never {@code NULL} itself.
     */
    record IsDistinct(Expression left, Expression right, boolean negated) implements Expression {
        public IsDistinct {
            TypeRules.common(left.type(), right.type());
        }

        @Override
        public DataType type() {
            return DataType.BOOLEAN;
        }

        @Override
        public List<Expression> children() {
            return List.of(left, right);
        }
    }

    /** A call of a scalar function of the catalog. */
    record Call(ScalarFunction function, List<Expression> arguments) implements Expression {
        public Call {
            Objects.requireNonNull(function, "function");
            arguments = List.copyOf(arguments);
            if (function.resultType(argumentTypes(arguments)) == null) {
                throw new IllegalArgumentException("Function " + function.name() + " does not accept "
                        + argumentTypes(arguments));
            }
        }

        /** Returns the types of the arguments. */
        public List<DataType> argumentTypes() {
            return argumentTypes(arguments);
        }

        private static List<DataType> argumentTypes(List<Expression> arguments) {
            return arguments.stream().map(Expression::type).toList();
        }

        @Override
        public DataType type() {
            return function.resultType(argumentTypes(arguments));
        }

        @Override
        public List<Expression> children() {
            return arguments;
        }
    }
}
