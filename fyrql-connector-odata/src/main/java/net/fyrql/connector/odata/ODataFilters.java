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

package net.fyrql.connector.odata;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns the filters of a scan into a {@code $filter} of OData, and its
 * order into an {@code $orderby}, over the columns of the scan output as
 * the service knows them.
 * <p>
 * A filter becomes either a <em>hint</em>, a condition that holds wherever
 * the filter holds, so that the service sends no fewer rows than FyrQL
 * selects and FyrQL still evaluates the filter, or an <em>exact</em>
 * condition, which holds exactly where the filter holds, for the filters
 * the source trusts the service with. A part without counterpart drops
 * out of a conjunction of a hint and leaves a disjunction without one; an
 * exact condition has no part without counterpart. {@code NOT} moves
 * inward under the three-valued logic of FyrQL and is never sent, since a
 * comparison with {@code null} is false in OData rather than unknown, so
 * that {@code not (Price gt 5)} would hold for a {@code null} price.
 * <p>
 * A part has a counterpart when it compares a column with a literal by
 * {@code = <> < <= > >=}, {@code BETWEEN}, {@code IN} or
 * {@code IS [NOT] DISTINCT FROM}, is {@code IS [NOT] NULL}, a
 * {@code BOOLEAN} column itself, or a {@code LIKE} of a hint, and the
 * service may filter by the column:
 * <ul>
 * <li>{@code Edm.SByte} to {@code Edm.Int64} and {@code Edm.Decimal} with
 * its facets, {@code Edm.Date}, {@code Edm.DateTime},
 * {@code Edm.DateTimeOffset}, {@code Edm.Time} and {@code Edm.TimeOfDay},
 * by every comparison, exactly; a literal the
 * column cannot hold, such as {@code 1.5} for an integer, turns a range
 * into one of the values the column holds;</li>
 * <li>{@code Edm.Boolean} and {@code Edm.Guid} by {@code =} and
 * {@code <>}, exactly;</li>
 * <li>{@code Edm.String} by {@code =}, {@code IN} and a {@code LIKE}
 * whose pattern has text between its wildcards, which becomes
 * {@code startswith}, {@code endswith} and {@code contains}
 * ({@code substringof} of version 2), only as a hint: a service may
 * compare without case or ignore spaces at the end, which gives more rows
 * but never fewer, while {@code <>} and ranges could give fewer and have
 * no counterpart;</li>
 * <li>{@code IS [NOT] NULL} for every column of a primitive type.</li>
 * </ul>
 * A {@code TIMESTAMP} holds the value a service sends, to the millisecond
 * of {@code /Date(...)/} in version 2 and to the microsecond in version 4,
 * while the service may compare a finer one, unless the facet
 * {@code Precision} says that there is none; such a column compares by
 * ranges of the unit it is sent in, so that {@code ts <= x} becomes
 * {@code ts lt x + unit}. {@code FLOAT}, {@code DOUBLE}, binary values and
 * collections have no counterpart beyond {@code IS [NOT] NULL}, nor has
 * {@code Edm.Duration}, since FyrQL compares a month as 30 days. A
 * {@code TIME} compares like a {@code TIMESTAMP}.
 * <p>
 * Literals are written as the version wants them: in version 2
 * {@code datetime'2024-01-01T00:00:00'}, {@code datetimeoffset'...Z'},
 * {@code time'PT13H20M'}, {@code guid'...'}, {@code 12L} for
 * {@code Edm.Int64} and {@code 1.5M} for {@code Edm.Decimal}; in version 4
 * without prefixes and suffixes, a time of day as {@code 13:20:00};
 * quotes in strings doubled.
 */
final class ODataFilters {

    /** The most elements of an {@code IN} that become a disjunction, which keeps URLs short. */
    static final int MAX_IN = 100;

    private static final BigDecimal MICROS_PER_DAY = BigDecimal.valueOf(86_400_000_000L);

    /** The first and the last day of the years 1 to 9999, which literals of dates can name. */
    private static final long MIN_DAY = LocalDate.of(1, 1, 1).toEpochDay();
    private static final long MAX_DAY = LocalDate.of(9999, 12, 31).toEpochDay();

    /**
     * A column of the output of a scan as the service knows it.
     *
     * @param path the path of its property, such as {@code Price} or
     *        {@code Address/City}
     * @param edmType the primitive type of the property, its type
     *        definitions resolved, or {@code null} if it is complex or a
     *        collection
     * @param precision the facet {@code Precision}, or {@code null}
     * @param type the type in FyrQL
     * @param nullable whether a value may be {@code null}
     * @param filterable whether the service filters by it
     * @param ascending whether the service sorts by it ascending
     * @param descending whether the service sorts by it descending
     */
    record Target(String path, String edmType, String precision, DataType type, boolean nullable,
            boolean filterable, boolean ascending, boolean descending) {

        Target {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(type, "type");
        }
    }

    private final ODataVersion version;
    private final List<Target> columns;

    /**
     * @param columns the columns of the scan output, by position
     */
    ODataFilters(ODataVersion version, List<Target> columns) {
        this.version = Objects.requireNonNull(version, "version");
        this.columns = List.copyOf(columns);
    }

    /** Returns a condition that holds wherever a filter holds, or {@code null} if there is none. */
    String hint(Expression filter) {
        return when(filter, true, false);
    }

    /** Returns a condition that holds exactly where a filter holds, or {@code null} if there is none. */
    String exact(Expression filter) {
        return when(filter, true, true);
    }

    /**
     * Returns the {@code $filter} of the filters of a scan: the exact
     * condition of each accepted one and the hint of each other one, or
     * {@code null} if there is no condition.
     *
     * @param accepted the filters that the scan takes over, which have an exact condition
     */
    String filter(List<Expression> filters, List<Expression> accepted) {
        String result = null;
        for (Expression f : filters) {
            String condition = accepted.contains(f) ? exact(f) : hint(f);
            if (accepted.contains(f) && condition == null) {
                throw new IllegalStateException("The accepted filter " + f + " has no exact condition");
            }
            result = and(result, condition, false);
        }
        return result;
    }

    /**
     * Returns the {@code $orderby} of an order, or {@code null} if the
     * service does not sort exactly so: every key must be a number, a
     * {@code DATE} or a {@code TIMESTAMP} the service sorts by in its
     * direction, with {@code NULL}s where OData puts them, first ascending
     * and last descending, unless the column has none. A {@code TIMESTAMP}
     * that the service may hold finer than it sends can only be the last
     * key, since the service breaks its ties by the finer values.
     */
    String orderBy(List<PlanNode.SortKey> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            PlanNode.SortKey key = keys.get(k);
            Target t = target(key.column());
            Unit unit = t == null ? null : unit(t);
            if (unit == null || unit.number() == Number.NONE) {
                return null;
            }
            if (key.ascending() ? !t.ascending() || t.nullable() && !key.nullsFirst()
                    : !t.descending() || t.nullable() && key.nullsFirst()) {
                return null;
            }
            if (unit.fine() && k < keys.size() - 1) {
                return null;
            }
            parts.add(t.path() + (key.ascending() ? " asc" : " desc"));
        }
        return String.join(",", parts);
    }

    /** Returns the column of the output at an index, if the service filters or sorts by it, or {@code null}. */
    private Target target(int index) {
        return index < columns.size() ? columns.get(index) : null;
    }

    /**
     * Returns the column a filter may name, also inside a cast to a number
     * type that holds all its values, or {@code null}.
     */
    private Target filterable(Expression e) {
        Expression operand = e instanceof Expression.Cast cast && widens(cast.operand().type(), cast.target())
                ? cast.operand() : e;
        if (!(operand instanceof Expression.Column c)) {
            return null;
        }
        Target t = target(c.index());
        return t != null && t.filterable() && t.edmType() != null ? t : null;
    }

    /**
     * Tells whether a cast from an integer or decimal type to another holds
     * every value, such as that of a {@code DECIMAL(9,2)} column that
     * {@code BETWEEN 5 AND 30} compares as {@code DECIMAL(12,2)}, so that
     * it compares as the values themselves.
     */
    private static boolean widens(DataType from, DataType to) {
        if (!(from.isIntegral() || from instanceof DataType.Decimal) || !(to.isIntegral()
                || to instanceof DataType.Decimal)) {
            return false;
        }
        DataType.Decimal f = TypeRules.asDecimal(from);
        DataType.Decimal t = TypeRules.asDecimal(to);
        return t.scale() >= f.scale() && t.precision() - t.scale() >= f.precision() - f.scale();
    }

    /** Both, or the one there is; in an exact condition both or none. */
    private static String and(String a, String b, boolean exact) {
        if (a == null || b == null) {
            return exact ? null : a == null ? b : a;
        }
        return a + " and " + b;
    }

    /** Either, if both are there. */
    private static String or(String a, String b) {
        return a == null || b == null ? null : "(" + a + " or " + b + ")";
    }

    /**
     * Returns a condition that holds wherever a filter is {@code TRUE}, or
     * {@code FALSE} if not {@code truth}, or exactly there if
     * {@code exact}; {@code null} if there is none.
     */
    private String when(Expression e, boolean truth, boolean exact) {
        return switch (e) {
            case Expression.And a -> truth ? and(when(a.left(), true, exact), when(a.right(), true, exact), exact)
                    : or(when(a.left(), false, exact), when(a.right(), false, exact));
            case Expression.Or o -> truth ? or(when(o.left(), true, exact), when(o.right(), true, exact))
                    : and(when(o.left(), false, exact), when(o.right(), false, exact), exact);
            case Expression.Not n -> when(n.operand(), !truth, exact);
            case Expression.Comparison c -> comparison(c, truth, exact);
            case Expression.Between b -> between(b, truth, exact);
            case Expression.In i -> in(i, truth, exact);
            case Expression.IsNull n -> {
                Target t = filterable(n.operand());
                yield t == null ? null : t.path() + (truth != n.negated() ? " eq null" : " ne null");
            }
            case Expression.IsDistinct d -> distinct(d, truth, exact);
            case Expression.Column c when c.type() == DataType.BOOLEAN -> {
                Target t = filterable(c);
                yield t == null || !"Edm.Boolean".equals(t.edmType()) ? null : t.path() + " eq " + truth;
            }
            case Expression.Like l -> truth && !exact && !l.caseInsensitive() ? like(l) : null;
            default -> null;
        };
    }

    private String comparison(Expression.Comparison c, boolean truth, boolean exact) {
        Expression.ComparisonOp op = truth ? c.op() : negate(c.op());
        DataType common = TypeRules.common(c.left().type(), c.right().type());
        if (c.right() instanceof Expression.Literal literal) {
            return compare(filterable(c.left()), op, common, literal, exact);
        }
        if (c.left() instanceof Expression.Literal literal) {
            return compare(filterable(c.right()), flip(op), common, literal, exact);
        }
        return null;
    }

    /** {@code BETWEEN} is {@code TRUE} where both bounds hold, {@code FALSE} where either fails. */
    private String between(Expression.Between b, boolean truth, boolean exact) {
        Target t = filterable(b.value());
        if (t == null) {
            return null;
        }
        DataType common = TypeRules.common(List.of(b.value().type(), b.low().type(), b.high().type()));
        String low = b.low() instanceof Expression.Literal l
                ? compare(t, truth ? Expression.ComparisonOp.GE : Expression.ComparisonOp.LT, common, l, exact) : null;
        String high = b.high() instanceof Expression.Literal h
                ? compare(t, truth ? Expression.ComparisonOp.LE : Expression.ComparisonOp.GT, common, h, exact)
                : null;
        return truth ? and(low, high, exact) : or(low, high);
    }

    /**
     * {@code IN} is {@code TRUE} where the value equals an element, which a
     * {@code NULL} never does, and {@code FALSE} where it differs from all
     * of them and none is {@code NULL}.
     */
    private String in(Expression.In in, boolean truth, boolean exact) {
        Target t = filterable(in.value());
        if (t == null || in.list().size() > MAX_IN) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Expression e : in.list()) {
            if (!(e instanceof Expression.Literal l)) {
                return null;
            }
            if (truth && l.value() == null) {
                continue;
            }
            DataType common = TypeRules.common(in.value().type(), l.type());
            String part = compare(t, truth ? Expression.ComparisonOp.EQ : Expression.ComparisonOp.NE, common, l,
                    exact);
            if (part != null) {
                parts.add(part);
            } else if (truth || exact) {
                return null;
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (!truth) {
            return String.join(" and ", parts);
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" or ", parts) + ")";
    }

    /**
     * {@code IS DISTINCT FROM} a literal holds where the value differs from
     * it, {@code NULL} included; {@code IS NOT DISTINCT FROM} where it
     * equals it, or is {@code NULL} for {@code NULL}.
     */
    private String distinct(Expression.IsDistinct d, boolean truth, boolean exact) {
        Expression.Literal literal;
        Target t;
        if (d.right() instanceof Expression.Literal l) {
            literal = l;
            t = filterable(d.left());
        } else if (d.left() instanceof Expression.Literal l) {
            literal = l;
            t = filterable(d.right());
        } else {
            return null;
        }
        if (t == null) {
            return null;
        }
        boolean distinct = truth != d.negated();
        if (literal.value() == null) {
            return t.path() + (distinct ? " ne null" : " eq null");
        }
        DataType common = TypeRules.common(d.left().type(), d.right().type());
        if (!distinct) {
            return compare(t, Expression.ComparisonOp.EQ, common, literal, exact);
        }
        String differs = compare(t, Expression.ComparisonOp.NE, common, literal, exact);
        return differs == null ? null : "(" + differs + " or " + t.path() + " eq null)";
    }

    /**
     * A {@code LIKE} holds only where the string starts with the text
     * before the first wildcard, ends with the text after the last one and
     * contains the text between them; a pattern without wildcards is an
     * equality.
     */
    private String like(Expression.Like l) {
        Target t = filterable(l.string());
        if (t == null || !"Edm.String".equals(t.edmType()) || !(l.pattern() instanceof Expression.Literal p)
                || p.value() == null) {
            return null;
        }
        String pattern = (String) p.value();
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%' || ch == '_') {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(ch);
            }
        }
        parts.add(part.toString());
        if (parts.size() == 1) {
            return t.path() + " eq " + string(pattern);
        }
        String result = null;
        for (int i = 0; i < parts.size(); i++) {
            String text = parts.get(i);
            if (text.isEmpty()) {
                continue;
            }
            String condition;
            if (i == 0) {
                condition = "startswith(" + t.path() + "," + string(text) + ")";
            } else if (i == parts.size() - 1) {
                condition = "endswith(" + t.path() + "," + string(text) + ")";
            } else if (version == ODataVersion.V2) {
                condition = "substringof(" + string(text) + "," + t.path() + ")";
            } else {
                condition = "contains(" + t.path() + "," + string(text) + ")";
            }
            // Version 2 writes the functions as comparisons, as the examples of its conventions do.
            result = and(result, version == ODataVersion.V2 ? condition + " eq true" : condition, false);
        }
        return result;
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

    /** How the values of a column compare. */
    private enum Number {
        /** Not as numbers. */
        NONE,
        /** An integer or a decimal. */
        NUMBER,
        /** Days or a timestamp, compared in microseconds. */
        TIME
    }

    /**
     * How a column compares: as numbers in the unit of its values, in
     * microseconds for a time, and whether the service may hold values
     * finer than the unit, which FyrQL reads cut to it.
     */
    private record Unit(Number number, BigDecimal unit, boolean fine) {
    }

    /** Returns how a column compares, or {@code null} if it has no counterpart for comparisons. */
    private Unit unit(Target t) {
        String edm = t.edmType();
        if (edm == null) {
            return null;
        }
        DataType type = t.type();
        return switch (edm) {
            case "Edm.SByte", "Edm.Byte", "Edm.Int16", "Edm.Int32", "Edm.Int64" -> type.isIntegral()
                    ? new Unit(Number.NUMBER, BigDecimal.ONE, false) : null;
            case "Edm.Decimal" -> type instanceof DataType.Decimal d
                    ? new Unit(Number.NUMBER, BigDecimal.ONE.scaleByPowerOfTen(-d.scale()), false) : null;
            case "Edm.Date" -> type == DataType.DATE ? new Unit(Number.TIME, MICROS_PER_DAY, false) : null;
            case "Edm.DateTime", "Edm.DateTimeOffset", "Edm.Time", "Edm.TimeOfDay" -> {
                boolean time = edm.equals("Edm.Time") || edm.equals("Edm.TimeOfDay");
                if (time ? type != DataType.TIME : type != DataType.TIMESTAMP && type != DataType.TIMESTAMPTZ) {
                    yield null;
                }
                int digits = version == ODataVersion.V2 ? 3 : 6;
                yield new Unit(Number.TIME, BigDecimal.TEN.pow(6 - digits), !coarse(t.precision(), digits));
            }
            case "Edm.Boolean", "Edm.Guid", "Edm.String" -> new Unit(Number.NONE, BigDecimal.ONE, false);
            default -> null;
        };
    }

    /** Tells whether a facet {@code Precision} says that values have at most so many digits of a second. */
    private static boolean coarse(String precision, int digits) {
        if (precision == null) {
            return false;
        }
        try {
            return Integer.parseInt(precision.strip()) <= digits;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Returns the condition of a column compared with a literal by an
     * operator in a common type, or {@code null}.
     */
    private String compare(Target t, Expression.ComparisonOp op, DataType common, Expression.Literal literal,
            boolean exact) {
        if (t == null || literal.value() == null) {
            return null;
        }
        Unit unit = unit(t);
        if (unit == null) {
            return null;
        }
        String path = t.path();
        if (unit.number() == Number.NONE) {
            return switch (t.edmType()) {
                case "Edm.Boolean" -> {
                    if (literal.type() != DataType.BOOLEAN) {
                        yield null;
                    }
                    boolean v = (Boolean) literal.value();
                    yield op == Expression.ComparisonOp.EQ ? path + " eq " + v
                            : op == Expression.ComparisonOp.NE ? path + " eq " + !v : null;
                }
                case "Edm.Guid" -> {
                    if (literal.type() != DataType.UUID) {
                        yield null;
                    }
                    String v = guid((UUID) literal.value());
                    yield op == Expression.ComparisonOp.EQ ? path + " eq " + v
                            : op != Expression.ComparisonOp.NE ? null
                            : exact ? path + " ne " + v + " and " + path + " ne null" : path + " ne " + v;
                }
                default -> literal.type() == DataType.VARCHAR && op == Expression.ComparisonOp.EQ && !exact
                        ? path + " eq " + string((String) literal.value()) : null;
            };
        }
        BigDecimal v = value(t, unit, common, literal);
        if (v == null) {
            return null;
        }
        BigDecimal u = unit.unit();
        BigDecimal floor = v.divide(u, 0, RoundingMode.FLOOR).multiply(u);
        BigDecimal ceiling = v.divide(u, 0, RoundingMode.CEILING).multiply(u);
        boolean whole = floor.compareTo(ceiling) == 0;
        if (unit.fine()) {
            BigDecimal next = floor.add(u);
            return switch (op) {
                case EQ -> whole ? and(compare(t, "ge", v), compare(t, "lt", next), true) : null;
                case NE -> whole ? or(compare(t, "lt", v), compare(t, "ge", next))
                        : exact ? path + " ne null" : null;
                case LT -> compare(t, "lt", ceiling);
                case LE -> compare(t, "lt", next);
                case GT -> compare(t, "ge", next);
                case GE -> compare(t, "ge", ceiling);
            };
        }
        return switch (op) {
            case EQ -> whole ? compare(t, "eq", v) : null;
            case NE -> {
                if (!whole) {
                    yield exact ? path + " ne null" : null;
                }
                String ne = compare(t, "ne", v);
                yield ne == null || !exact ? ne : ne + " and " + path + " ne null";
            }
            case LT -> compare(t, "lt", ceiling);
            case LE -> compare(t, "le", floor);
            case GT -> compare(t, "gt", floor);
            case GE -> compare(t, "ge", ceiling);
        };
    }

    /** Returns {@code path op literal} for a value the column can hold, or {@code null} if it cannot. */
    private String compare(Target t, String op, BigDecimal value) {
        String literal = literal(t, value);
        return literal == null ? null : t.path() + " " + op + " " + literal;
    }

    /**
     * Returns the value of a literal as a number, in microseconds for a
     * time, if the column and the literal compare exactly in the common
     * type; otherwise {@code null}.
     */
    private static BigDecimal value(Target t, Unit unit, DataType common, Expression.Literal literal) {
        Object v = literal.value();
        if (unit.number() == Number.TIME) {
            // An instant and a time of day compare only with their own type, a date and a timestamp with either.
            if (t.type() == DataType.TIMESTAMPTZ || t.type() == DataType.TIME) {
                return literal.type() == t.type() ? BigDecimal.valueOf((Long) v) : null;
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
            DataType.Decimal own = TypeRules.asDecimal(t.type());
            if (own.scale() > c.scale() || own.precision() - own.scale() > c.precision() - c.scale()) {
                return null;
            }
            BigDecimal scaled = value.stripTrailingZeros();
            if (scaled.scale() > c.scale() || scaled.precision() - scaled.scale() > c.precision() - c.scale()) {
                return null;
            }
        }
        return value;
    }

    /** Returns the literal of a value of a column, a multiple of its unit, or {@code null} if it cannot hold it. */
    private String literal(Target t, BigDecimal value) {
        boolean v2 = version == ODataVersion.V2;
        switch (t.edmType()) {
            case "Edm.SByte", "Edm.Byte", "Edm.Int16", "Edm.Int32", "Edm.Int64" -> {
                BigInteger n = value.toBigIntegerExact();
                long min = switch (t.edmType()) {
                    case "Edm.SByte" -> Byte.MIN_VALUE;
                    case "Edm.Byte" -> 0;
                    case "Edm.Int16" -> Short.MIN_VALUE;
                    case "Edm.Int32" -> Integer.MIN_VALUE;
                    default -> Long.MIN_VALUE;
                };
                long max = switch (t.edmType()) {
                    case "Edm.SByte" -> Byte.MAX_VALUE;
                    case "Edm.Byte" -> 255;
                    case "Edm.Int16" -> Short.MAX_VALUE;
                    case "Edm.Int32" -> Integer.MAX_VALUE;
                    default -> Long.MAX_VALUE;
                };
                if (n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0) {
                    return null;
                }
                return n + (v2 && t.edmType().equals("Edm.Int64") ? "L" : "");
            }
            case "Edm.Decimal" -> {
                DataType.Decimal d = (DataType.Decimal) t.type();
                BigDecimal plain = value.setScale(d.scale(), RoundingMode.UNNECESSARY);
                if (plain.precision() - plain.scale() > d.precision() - d.scale()) {
                    return null;
                }
                return plain.toPlainString() + (v2 ? "M" : "");
            }
            case "Edm.Date" -> {
                long days = value.divide(MICROS_PER_DAY, 0, RoundingMode.UNNECESSARY).longValueExact();
                if (days < MIN_DAY || days > MAX_DAY) {
                    return null;
                }
                return Conversions.formatDate((int) days);
            }
            case "Edm.Time", "Edm.TimeOfDay" -> {
                long micros;
                try {
                    micros = value.longValueExact();
                } catch (ArithmeticException e) {
                    return null;
                }
                if (micros < 0 || micros >= MICROS_PER_DAY.longValueExact()) {
                    return null;
                }
                return v2 ? "time'" + duration(micros) + "'" : timeOfDay(micros);
            }
            default -> {
                long micros;
                try {
                    micros = value.longValueExact();
                } catch (ArithmeticException e) {
                    return null;
                }
                String text = timestamp(micros);
                if (text == null) {
                    return null;
                }
                if (!v2) {
                    return text + "Z";
                }
                return t.edmType().equals("Edm.DateTime") ? "datetime'" + text + "'"
                        : "datetimeoffset'" + text + "Z'";
            }
        }
    }

    /**
     * Returns a timestamp as {@code yyyy-mm-ddThh:mm:ss} with as many
     * digits of a second as it needs, or {@code null} outside the years 1
     * to 9999.
     */
    private static String timestamp(long micros) {
        long days = Math.floorDiv(micros, 86_400_000_000L);
        if (days < MIN_DAY || days > MAX_DAY) {
            return null;
        }
        LocalDateTime t = Conversions.toLocalDateTime(micros);
        String text = String.format("%04d-%02d-%02dT%02d:%02d:%02d", t.getYear(), t.getMonthValue(),
                t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond());
        int fraction = t.getNano() / 1000;
        if (fraction == 0) {
            return text;
        }
        String digits = String.format("%06d", fraction);
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') {
            end--;
        }
        return text + "." + digits.substring(0, end);
    }

    /** Returns a time of day as {@code hh:mm:ss} with as many digits of a second as it needs. */
    private static String timeOfDay(long micros) {
        String text = String.format("%02d:%02d:%02d", micros / 3_600_000_000L, micros / 60_000_000L % 60,
                micros / 1_000_000L % 60);
        return text + fraction(micros % 1_000_000L);
    }

    /** Returns a time of day as a duration of ISO 8601 such as {@code PT13H20M5.5S}. */
    private static String duration(long micros) {
        StringBuilder text = new StringBuilder("PT");
        long hours = micros / 3_600_000_000L;
        long minutes = micros / 60_000_000L % 60;
        long seconds = micros / 1_000_000L % 60;
        long fraction = micros % 1_000_000L;
        if (hours > 0) {
            text.append(hours).append('H');
        }
        if (minutes > 0) {
            text.append(minutes).append('M');
        }
        if (seconds > 0 || fraction > 0 || hours == 0 && minutes == 0) {
            text.append(seconds).append(fraction(fraction)).append('S');
        }
        return text.toString();
    }

    /** Returns the microseconds of a second as a fraction without trailing zeros, empty for none. */
    private static String fraction(long micros) {
        if (micros == 0) {
            return "";
        }
        String digits = String.format("%06d", micros);
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') {
            end--;
        }
        return "." + digits.substring(0, end);
    }

    /** Returns the literal of a GUID. */
    private String guid(UUID uuid) {
        return version == ODataVersion.V2 ? "guid'" + uuid + "'" : uuid.toString();
    }

    /** Returns the literal of a string, its quotes doubled. */
    static String string(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
