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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ColumnPath;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.predicate.Operators;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the filters of a scan into a predicate of parquet-java over the
 * columns of a file that holds wherever the filters hold, so that
 * parquet-java can skip the row groups in which no row can pass, by their
 * statistics, dictionaries and Bloom filters, and the pages, by the column
 * index. The predicate is weaker than the filters, never stronger: the
 * engine still evaluates the filters on the rows read, and a part that has
 * no counterpart is left out.
 * <p>
 * A part has a counterpart when it compares a column, or a field of a
 * {@code STRUCT} column outside of lists, with a literal by
 * {@code = <> < <= > >=}, {@code BETWEEN}, {@code IN} or
 * {@code IS [NOT] DISTINCT FROM}, or is a {@code BOOLEAN} column itself,
 * and the column is
 * <ul>
 * <li>{@code BOOLEAN}, by {@code =} and {@code <>};</li>
 * <li>{@code TINYINT} to {@code BIGINT} with a sign or {@code DECIMAL} on
 * {@code INT32} or {@code INT64}, against an integer or a decimal whose
 * comparison in the common type is exact;</li>
 * <li>{@code DATE}, or {@code TIMESTAMP} in milliseconds or microseconds,
 * against a {@code DATE} or a {@code TIMESTAMP}; {@code TIMESTAMP WITH TIME
 * ZONE} and {@code TIME} in milliseconds or microseconds against their own
 * type;</li>
 * <li>{@code VARCHAR} of {@code STRING}, {@code ENUM} or {@code JSON},
 * against a {@code VARCHAR}, in the order of the unsigned bytes, as in
 * FyrQL.</li>
 * </ul>
 * {@code IS [NOT] NULL} has a counterpart for any primitive column. A value
 * the column cannot hold, such as {@code 1.5} for an integer, turns a range
 * into one of whole values and leaves an equality without counterpart.
 * {@code AND}, {@code OR} and {@code NOT} combine the parts: a part without
 * counterpart drops out of a conjunction and leaves a disjunction without
 * one. {@code NOT} moves inward under three-valued logic, since a filter
 * holds where it is {@code TRUE}; the {@code not} of parquet-java, which
 * would also keep the rows where the filter is {@code NULL}, is never
 * used. A column whose path has a name with a dot has no counterpart,
 * since parquet-java splits its paths at dots.
 */
final class FilterTranslation {

    private static final BigDecimal MICROS_PER_DAY = BigDecimal.valueOf(86_400_000_000L);

    /**
     * The most elements of an {@code IN} that become a disjunction of
     * equalities, which the statistics test one by one; a longer list
     * becomes an {@code in} of parquet-java, whose statistics test takes
     * only the least and the greatest element.
     */
    private static final int SMALL_IN = 32;

    private final MessageType file;
    private final List<ColumnPath> columns;

    private FilterTranslation(MessageType file, List<ColumnPath> columns) {
        this.file = file;
        this.columns = columns;
    }

    /**
     * Returns a predicate that holds wherever all filters hold, or
     * {@code null} if none of them has a counterpart.
     *
     * @param filters conditions over the columns of the scan output
     * @param file the schema of the file
     * @param columns the paths of the output columns in the file
     */
    static FilterPredicate translate(List<Expression> filters, MessageType file, List<ColumnPath> columns) {
        FilterTranslation t = new FilterTranslation(file, columns);
        FilterPredicate result = null;
        for (Expression f : filters) {
            result = and(result, t.when(f, true));
        }
        return result;
    }

    /** Both, or the one that is there. */
    private static FilterPredicate and(FilterPredicate a, FilterPredicate b) {
        return a == null ? b : b == null ? a : FilterApi.and(a, b);
    }

    /** Either, if both are there. */
    private static FilterPredicate or(FilterPredicate a, FilterPredicate b) {
        return a == null || b == null ? null : FilterApi.or(a, b);
    }

    /**
     * Returns a predicate that holds wherever a condition is {@code TRUE},
     * or {@code FALSE} if not {@code truth}; {@code null} if there is none.
     */
    private FilterPredicate when(Expression e, boolean truth) {
        return switch (e) {
            case Expression.And a -> truth ? and(when(a.left(), true), when(a.right(), true))
                    : or(when(a.left(), false), when(a.right(), false));
            case Expression.Or o -> truth ? or(when(o.left(), true), when(o.right(), true))
                    : and(when(o.left(), false), when(o.right(), false));
            case Expression.Not n -> when(n.operand(), !truth);
            case Expression.Comparison c -> comparison(c, truth);
            case Expression.Between b -> between(b, truth);
            case Expression.In i -> in(i, truth);
            case Expression.IsNull n when n.operand() instanceof Expression.Column c -> {
                Leaf leaf = leaf(c);
                yield leaf == null ? null : leaf.isNull(truth != n.negated());
            }
            case Expression.IsDistinct d -> distinct(d, truth);
            case Expression.Column c when c.type() == DataType.BOOLEAN -> {
                Leaf leaf = leaf(c);
                yield leaf == null ? null : leaf.compare(Expression.ComparisonOp.EQ, DataType.BOOLEAN,
                        new Expression.Literal(DataType.BOOLEAN, truth));
            }
            default -> null;
        };
    }

    private FilterPredicate comparison(Expression.Comparison c, boolean truth) {
        Expression.ComparisonOp op = truth ? c.op() : negate(c.op());
        DataType common = TypeRules.common(c.left().type(), c.right().type());
        if (c.left() instanceof Expression.Column column && c.right() instanceof Expression.Literal literal) {
            Leaf leaf = leaf(column);
            return leaf == null ? null : leaf.compare(op, common, literal);
        }
        if (c.left() instanceof Expression.Literal literal && c.right() instanceof Expression.Column column) {
            Leaf leaf = leaf(column);
            return leaf == null ? null : leaf.compare(flip(op), common, literal);
        }
        return null;
    }

    /** {@code BETWEEN} is {@code TRUE} where both bounds hold, {@code FALSE} where either fails. */
    private FilterPredicate between(Expression.Between b, boolean truth) {
        if (!(b.value() instanceof Expression.Column column)) {
            return null;
        }
        Leaf leaf = leaf(column);
        if (leaf == null) {
            return null;
        }
        DataType common = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
        FilterPredicate low = b.low() instanceof Expression.Literal l
                ? leaf.compare(truth ? Expression.ComparisonOp.GE : Expression.ComparisonOp.LT, common, l) : null;
        FilterPredicate high = b.high() instanceof Expression.Literal h
                ? leaf.compare(truth ? Expression.ComparisonOp.LE : Expression.ComparisonOp.GT, common, h) : null;
        return truth ? and(low, high) : or(low, high);
    }

    /**
     * {@code IN} is {@code TRUE} where the value equals an element, and
     * {@code FALSE} where it differs from all of them and none is
     * {@code NULL}.
     */
    private FilterPredicate in(Expression.In in, boolean truth) {
        if (!(in.value() instanceof Expression.Column column)) {
            return null;
        }
        Leaf leaf = leaf(column);
        if (leaf == null) {
            return null;
        }
        Set<Comparable<?>> values = new LinkedHashSet<>();
        for (Expression e : in.list()) {
            if (!(e instanceof Expression.Literal l)) {
                return null;
            }
            if (l.value() == null) {
                if (!truth) {
                    return null;
                }
                continue;
            }
            Equal equal = leaf.value(TypeRules.common(column.type(), l.type()), l);
            if (equal == null) {
                return null;
            }
            if (equal.value() != null) {
                values.add(equal.value());
            }
        }
        if (values.isEmpty()) {
            return truth ? null : leaf.isNull(false);
        }
        if (!truth) {
            FilterPredicate result = null;
            for (Comparable<?> v : values) {
                result = and(result, leaf.predicate(Expression.ComparisonOp.NE, v));
            }
            return result;
        }
        if (values.size() > SMALL_IN) {
            return leaf.in(values);
        }
        List<FilterPredicate> equal = new ArrayList<>();
        for (Comparable<?> v : values) {
            equal.add(leaf.predicate(Expression.ComparisonOp.EQ, v));
        }
        return any(equal, 0, equal.size());
    }

    /** The disjunction of a range of predicates, as a balanced tree. */
    private static FilterPredicate any(List<FilterPredicate> predicates, int from, int to) {
        if (to - from == 1) {
            return predicates.get(from);
        }
        int middle = (from + to) >>> 1;
        return FilterApi.or(any(predicates, from, middle), any(predicates, middle, to));
    }

    /**
     * {@code IS DISTINCT FROM} a literal holds where the value differs from
     * it, {@code NULL} included; {@code IS NOT DISTINCT FROM} where it
     * equals it.
     */
    private FilterPredicate distinct(Expression.IsDistinct d, boolean truth) {
        Expression.Column column;
        Expression.Literal literal;
        if (d.left() instanceof Expression.Column c && d.right() instanceof Expression.Literal l) {
            column = c;
            literal = l;
        } else if (d.left() instanceof Expression.Literal l && d.right() instanceof Expression.Column c) {
            column = c;
            literal = l;
        } else {
            return null;
        }
        Leaf leaf = leaf(column);
        if (leaf == null) {
            return null;
        }
        boolean distinct = truth != d.negated();
        if (literal.value() == null) {
            return leaf.isNull(!distinct);
        }
        DataType common = TypeRules.common(d.left().type(), d.right().type());
        if (!distinct) {
            return leaf.compare(Expression.ComparisonOp.EQ, common, literal);
        }
        Equal equal = leaf.value(common, literal);
        // The notEq of parquet-java keeps the rows where the column is NULL.
        return equal == null ? null : leaf.predicate(Expression.ComparisonOp.NE, equal.value());
    }

    private static Expression.ComparisonOp negate(Expression.ComparisonOp op) {
        return switch (op) {
            case EQ -> Expression.ComparisonOp.NE;
            case NE -> Expression.ComparisonOp.EQ;
            case LT -> Expression.ComparisonOp.GE;
            case LE -> Expression.ComparisonOp.GT;
            case GT -> Expression.ComparisonOp.LE;
            case GE -> Expression.ComparisonOp.LT;
        };
    }

    /** The operator with its operands swapped. */
    private static Expression.ComparisonOp flip(Expression.ComparisonOp op) {
        return switch (op) {
            case EQ, NE -> op;
            case LT -> Expression.ComparisonOp.GT;
            case LE -> Expression.ComparisonOp.GE;
            case GT -> Expression.ComparisonOp.LT;
            case GE -> Expression.ComparisonOp.LE;
        };
    }

    /**
     * The physical value equal to a literal, or {@code null} if no value of
     * the column equals it.
     */
    private record Equal(Comparable<?> value) {
        static final Equal NONE = new Equal(null);
    }

    /** How the values of a primitive column compare with those of FyrQL. */
    private enum Kind {
        /** Only {@code IS [NOT] NULL}. */
        NONE,
        BOOLEAN,
        /** An integer or the unscaled value of a decimal. */
        NUMBER,
        /** Days or a timestamp, compared in microseconds. */
        TIME,
        STRING
    }

    /** Returns the primitive column of the file that a column of the output reads, or {@code null}. */
    private Leaf leaf(Expression.Column c) {
        if (c.index() >= columns.size()) {
            return null;
        }
        ColumnPath p = columns.get(c.index());
        PrimitiveType leaf = ParquetSchemas.leaf(file, p);
        if (leaf == null) {
            return null;
        }
        String[] names = ParquetSchemas.path(file, p);
        for (String name : names) {
            if (name.indexOf('.') >= 0) {
                return null;
            }
        }
        String path = String.join(".", names);
        Operators.Column<?> column = switch (leaf.getPrimitiveTypeName()) {
            case BOOLEAN -> FilterApi.booleanColumn(path);
            case INT32 -> FilterApi.intColumn(path);
            case INT64 -> FilterApi.longColumn(path);
            case FLOAT -> FilterApi.floatColumn(path);
            case DOUBLE -> FilterApi.doubleColumn(path);
            case BINARY, FIXED_LEN_BYTE_ARRAY, INT96 -> FilterApi.binaryColumn(path);
        };
        LogicalTypeAnnotation annotation = leaf.getLogicalTypeAnnotation();
        boolean integral = leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT32
                || leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT64;
        DataType type = c.type();
        Kind kind = Kind.NONE;
        BigDecimal unit = BigDecimal.ONE;
        if (leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.BOOLEAN && type == DataType.BOOLEAN) {
            kind = Kind.BOOLEAN;
        } else if (integral && (annotation == null
                || annotation instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i && i.isSigned())
                && type.isIntegral() && type != DataType.HUGEINT) {
            kind = Kind.NUMBER;
        } else if (integral && annotation instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation d
                && type instanceof DataType.Decimal t && t.scale() == d.getScale()) {
            kind = Kind.NUMBER;
            unit = BigDecimal.ONE.scaleByPowerOfTen(-d.getScale());
        } else if (leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT32
                && annotation instanceof LogicalTypeAnnotation.DateLogicalTypeAnnotation && type == DataType.DATE) {
            kind = Kind.TIME;
            unit = MICROS_PER_DAY;
        } else if (leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT64
                && annotation instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ts
                && ts.getUnit() != LogicalTypeAnnotation.TimeUnit.NANOS
                && (type == DataType.TIMESTAMP || type == DataType.TIMESTAMPTZ)) {
            kind = Kind.TIME;
            unit = ts.getUnit() == LogicalTypeAnnotation.TimeUnit.MILLIS ? BigDecimal.valueOf(1000) : BigDecimal.ONE;
        } else if (integral && annotation instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation t
                && t.getUnit() != LogicalTypeAnnotation.TimeUnit.NANOS && type == DataType.TIME) {
            kind = Kind.TIME;
            unit = t.getUnit() == LogicalTypeAnnotation.TimeUnit.MILLIS ? BigDecimal.valueOf(1000) : BigDecimal.ONE;
        } else if (leaf.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.BINARY
                && (annotation instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation
                        || annotation instanceof LogicalTypeAnnotation.EnumLogicalTypeAnnotation
                        || annotation instanceof LogicalTypeAnnotation.JsonLogicalTypeAnnotation)
                && type == DataType.VARCHAR) {
            kind = Kind.STRING;
        }
        return new Leaf(column, kind, unit, type, leaf.getPrimitiveTypeName());
    }

    /**
     * A primitive column of the file: its column for the predicates, how
     * its values compare, and for a number or a time the value in FyrQL of
     * one physical unit, in the unit of the decimal or in microseconds.
     */
    private record Leaf(Operators.Column<?> column, Kind kind, BigDecimal unit, DataType type,
            PrimitiveType.PrimitiveTypeName physical) {

        /** {@code IS NULL}, or {@code IS NOT NULL} if not {@code isNull}. */
        FilterPredicate isNull(boolean isNull) {
            return switch (column) {
                case Operators.BooleanColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                case Operators.IntColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                case Operators.LongColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                case Operators.FloatColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                case Operators.DoubleColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                case Operators.BinaryColumn c -> isNull ? FilterApi.eq(c, null) : FilterApi.notEq(c, null);
                default -> null;
            };
        }

        /**
         * Returns a predicate that holds wherever the column compares with a
         * literal by an operator in a common type, or {@code null}.
         */
        FilterPredicate compare(Expression.ComparisonOp op, DataType common, Expression.Literal literal) {
            if (literal.value() == null) {
                return null;
            }
            switch (kind) {
                case BOOLEAN, STRING -> {
                    Equal equal = value(common, literal);
                    return equal == null ? null : predicate(op, equal.value());
                }
                case NUMBER, TIME -> {
                    BigDecimal v = exact(common, literal);
                    if (v == null) {
                        return null;
                    }
                    BigDecimal floor = v.divide(unit, 0, RoundingMode.FLOOR);
                    BigDecimal ceiling = v.divide(unit, 0, RoundingMode.CEILING);
                    boolean whole = floor.compareTo(ceiling) == 0;
                    return switch (op) {
                        case EQ -> whole ? predicate(op, physical(floor)) : null;
                        case NE -> whole ? predicate(op, physical(floor)) : null;
                        case LT -> predicate(op, physical(ceiling));
                        case LE -> predicate(op, physical(floor));
                        case GT -> predicate(op, physical(floor));
                        case GE -> predicate(op, physical(ceiling));
                    };
                }
                default -> {
                    return null;
                }
            }
        }

        /**
         * Returns the physical value equal to a literal that is not
         * {@code NULL}, compared in a common type, if the column compares
         * with it; otherwise {@code null}.
         */
        Equal value(DataType common, Expression.Literal literal) {
            return switch (kind) {
                case BOOLEAN -> literal.type() == DataType.BOOLEAN ? new Equal((Boolean) literal.value()) : null;
                case STRING -> literal.type() == DataType.VARCHAR
                        ? new Equal(Binary.fromString((String) literal.value())) : null;
                case NUMBER, TIME -> {
                    BigDecimal v = exact(common, literal);
                    if (v == null) {
                        yield null;
                    }
                    BigDecimal floor = v.divide(unit, 0, RoundingMode.FLOOR);
                    if (floor.multiply(unit).compareTo(v) != 0) {
                        yield Equal.NONE;
                    }
                    Comparable<?> physical = physical(floor);
                    yield physical == null ? Equal.NONE : new Equal(physical);
                }
                default -> null;
            };
        }

        /**
         * Returns the value of a literal as a number, in microseconds for a
         * time, if the column and the literal compare exactly in the common
         * type; otherwise {@code null}.
         */
        private BigDecimal exact(DataType common, Expression.Literal literal) {
            Object v = literal.value();
            if (kind == Kind.TIME) {
                if (literal.type() == DataType.TIMESTAMPTZ || literal.type() == DataType.TIME
                        || type == DataType.TIMESTAMPTZ || type == DataType.TIME) {
                    // An instant or a time of day compares only with its own type.
                    return literal.type() == type ? BigDecimal.valueOf((Long) v) : null;
                }
                if (literal.type() == DataType.DATE) {
                    return BigDecimal.valueOf((Integer) v).multiply(MICROS_PER_DAY);
                }
                return literal.type() == DataType.TIMESTAMP ? BigDecimal.valueOf((Long) v) : null;
            }
            BigDecimal value = switch (v) {
                case Byte b -> BigDecimal.valueOf(b);
                case Short s -> BigDecimal.valueOf(s);
                case Integer i when literal.type().isIntegral() -> BigDecimal.valueOf(i);
                case Long l when literal.type().isIntegral() -> BigDecimal.valueOf(l);
                case BigInteger b -> new BigDecimal(b);
                case BigDecimal d -> d;
                default -> null;
            };
            if (value == null || !(common.isIntegral() || common instanceof DataType.Decimal)) {
                return null;
            }
            if (common instanceof DataType.Decimal c) {
                DataType.Decimal own = TypeRules.asDecimal(type);
                if (own.scale() > c.scale() || own.precision() - own.scale() > c.precision() - c.scale()) {
                    return null;
                }
                BigDecimal scaled = value.stripTrailingZeros();
                if (scaled.scale() > c.scale()
                        || scaled.precision() - scaled.scale() > c.precision() - c.scale()) {
                    return null;
                }
            }
            return value;
        }

        /** Returns a whole number as a value of the physical type, or {@code null} if out of its range. */
        private Comparable<?> physical(BigDecimal whole) {
            BigInteger n = whole.toBigIntegerExact();
            if (physical == PrimitiveType.PrimitiveTypeName.INT32) {
                return n.bitLength() < 32 ? (Comparable<?>) n.intValue() : null;
            }
            return n.bitLength() < 64 ? n.longValue() : null;
        }

        /** Returns the predicate of an operator against a physical value, or {@code null} without one. */
        FilterPredicate predicate(Expression.ComparisonOp op, Comparable<?> value) {
            if (value == null) {
                return null;
            }
            return switch (column) {
                case Operators.BooleanColumn c -> op == Expression.ComparisonOp.EQ ? FilterApi.eq(c, (Boolean) value)
                        : op == Expression.ComparisonOp.NE ? FilterApi.notEq(c, (Boolean) value) : null;
                case Operators.IntColumn c -> ordered(op, c, (Integer) value);
                case Operators.LongColumn c -> ordered(op, c, (Long) value);
                case Operators.BinaryColumn c -> ordered(op, c, (Binary) value);
                default -> null;
            };
        }

        /** Returns the predicate that the column is one of the physical values. */
        @SuppressWarnings("unchecked")
        FilterPredicate in(Set<Comparable<?>> values) {
            return switch (column) {
                case Operators.BooleanColumn c -> FilterApi.in(c, (Set<Boolean>) (Set<?>) values);
                case Operators.IntColumn c -> FilterApi.in(c, (Set<Integer>) (Set<?>) values);
                case Operators.LongColumn c -> FilterApi.in(c, (Set<Long>) (Set<?>) values);
                case Operators.BinaryColumn c -> FilterApi.in(c, (Set<Binary>) (Set<?>) values);
                default -> null;
            };
        }

        private static <T extends Comparable<T>, C extends Operators.Column<T> & Operators.SupportsLtGt>
                FilterPredicate ordered(Expression.ComparisonOp op, C column, T value) {
            return switch (op) {
                case EQ -> FilterApi.eq(column, value);
                case NE -> FilterApi.notEq(column, value);
                case LT -> FilterApi.lt(column, value);
                case LE -> FilterApi.ltEq(column, value);
                case GT -> FilterApi.gt(column, value);
                case GE -> FilterApi.gtEq(column, value);
            };
        }
    }
}
