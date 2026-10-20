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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The dialect of a database FyrQL knows nothing about but that it speaks
 * SQL through JDBC. Databases differ in quoting, in the clause that keeps
 * the first rows, in how they compare strings (with or without regard to
 * the case and to trailing spaces, Oracle even takes {@code ''} for
 * {@code NULL}), in arithmetic and in the place of {@code NULL}s in an
 * order, so the dialect takes over only what every one of them evaluates
 * as FyrQL does:
 * <ul>
 * <li>comparisons, {@code BETWEEN}, {@code IN} over literals and
 * {@code IS [NOT] NULL} of a column, over integers and dates, combined
 * with {@code AND}, {@code OR} and {@code NOT}; decimals, floating-point
 * values, timestamps and booleans only where the settings trust the
 * database with them;</li>
 * <li>no aggregation, join, order, function or window.</li>
 * </ul>
 * Literals are bound as parameters, so that no database has to read a
 * literal of FyrQL; a statement is rendered with
 * {@link SqlRenderer#renderScanBound}. The first rows are kept by the
 * driver ({@code Statement.setMaxRows}) unless the settings name a
 * clause.
 * <p>
 * Strings are never compared exactly, but a few conditions on them are
 * {@linkplain #hint hints}: the database returns at least the rows that
 * satisfy them, so it may return fewer rows, and FyrQL filters again.
 */
public final class GenericDialect extends StandardDialect {

    /** How the first rows of a statement are kept. */
    public enum LimitStyle {
        /** By the driver, with {@code Statement.setMaxRows}; every database has it. */
        MAX_ROWS,
        /** {@code FETCH FIRST n ROWS ONLY}, as in SQL:2008, Oracle 12c, Db2 and Derby. */
        FETCH_FIRST,
        /** {@code LIMIT n}. */
        LIMIT,
        /** {@code SELECT TOP n}, as in SQL Server and Sybase. */
        TOP
    }

    /** A type whose values the database is trusted to store, compare and order as FyrQL does. */
    public enum Trust {
        DECIMAL, DOUBLE, TIMESTAMP, BOOLEAN
    }

    /**
     * How the dialect writes and what it takes over.
     *
     * @param quote the quote of identifiers: {@code "}, {@code `},
     *        {@code [} for {@code [name]}, or empty for none
     * @param limit how the first rows are kept
     * @param trusted the types the database is trusted with besides
     *        integers and dates
     * @param filters whether the database evaluates any filter at all
     */
    public record Settings(String quote, LimitStyle limit, Set<Trust> trusted, boolean filters) {

        private static final Set<String> QUOTES = Set.of("", "\"", "`", "[");

        /** Double quotes, {@code setMaxRows}, nothing trusted, filters on integers and dates. */
        public static final Settings DEFAULT = new Settings("\"", LimitStyle.MAX_ROWS, Set.of(), true);

        public Settings {
            Objects.requireNonNull(quote, "quote");
            if (!QUOTES.contains(quote)) {
                throw new IllegalArgumentException("The quote of identifiers is one of \", `, [ or none, not " + quote);
            }
            Objects.requireNonNull(limit, "limit");
            trusted = Set.copyOf(trusted);
        }
    }

    /** The dialect with the {@linkplain Settings#DEFAULT default settings}. */
    public static final GenericDialect DEFAULT = new GenericDialect(Settings.DEFAULT);

    private final Settings settings;

    private GenericDialect(Settings settings) {
        this.settings = settings;
    }

    /** Returns the dialect with the given settings. */
    public static GenericDialect of(Settings settings) {
        return settings.equals(Settings.DEFAULT) ? DEFAULT : new GenericDialect(settings);
    }

    /** Returns the settings. */
    public Settings settings() {
        return settings;
    }

    @Override
    public String name() {
        return "generic";
    }

    @Override
    public String toString() {
        return name() + " " + settings;
    }

    @Override
    public String quoteIdentifier(String name) {
        String q = settings.quote();
        return switch (q) {
            case "" -> name;
            case "[" -> "[" + name.replace("]", "]]") + "]";
            default -> q + name.replace(q, q + q) + q;
        };
    }

    /** The names of SQL-92 where it has one; {@code HUGEINT} has no portable type. */
    @Override
    String scalarTypeName(Scalar type) {
        return switch (type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT, SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case HUGEINT -> throw new IllegalArgumentException(name() + " has no portable type for HUGEINT");
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE PRECISION";
            case VARCHAR -> "VARCHAR(4000)";
            case BLOB -> "BLOB";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case UUID -> "CHAR(36)";
        };
    }

    @Override
    String integerLiteral(Object value, Scalar type) {
        return String.valueOf(value);
    }

    @Override
    String hugeIntLiteral(Object value) {
        return String.valueOf(value);
    }

    @Override
    String decimalLiteral(BigDecimal value, Decimal type) {
        return value.toPlainString();
    }

    @Override
    String floatingLiteral(String digits, Scalar type) {
        return digits;
    }

    @Override
    String uuidLiteral(java.util.UUID value) {
        return string(value.toString());
    }

    /** Yes: a value is a parameter of the statement. */
    @Override
    public boolean bindsLiterals() {
        return true;
    }

    @Override
    public LimitForm limitForm() {
        return switch (settings.limit()) {
            case MAX_ROWS -> LimitForm.MAX_ROWS;
            case TOP -> LimitForm.TOP;
            case FETCH_FIRST, LIMIT -> LimitForm.CLAUSE;
        };
    }

    /**
     * Renders {@code LIMIT n} or {@code FETCH FIRST n ROWS ONLY}, as the
     * settings say.
     *
     * @throws IllegalArgumentException for an offset, or if the settings keep the first rows otherwise
     */
    @Override
    public String limit(long count, long offset) {
        if (offset > 0) {
            throw new IllegalArgumentException(name() + " skips no rows");
        }
        return switch (settings.limit()) {
            case LIMIT -> "LIMIT " + count;
            case FETCH_FIRST -> "FETCH FIRST " + count + " ROWS ONLY";
            case MAX_ROWS, TOP -> throw new IllegalArgumentException(name() + " keeps the first rows with "
                    + settings.limit() + ", not with a clause");
        };
    }

    @Override
    public boolean supportsJoin(PlanNode.JoinType type) {
        return false;
    }

    @Override
    public String like(String string, String pattern, boolean caseInsensitive) {
        if (caseInsensitive) {
            throw new IllegalArgumentException(name() + " has no ILIKE");
        }
        return super.like(string, pattern, false);
    }

    @Override
    public String isDistinct(String left, String right, boolean negated) {
        throw new IllegalArgumentException(name() + " has no IS DISTINCT FROM");
    }

    @Override
    public String aggregate(AggregateFunction function, boolean distinct, String argument, String separator,
            List<String> orderKeys) {
        throw new IllegalArgumentException(name() + " renders no aggregate");
    }

    @Override
    boolean storesExactly(DataType type) {
        if (integer(type) || type == Scalar.DATE) {
            return true;
        }
        Trust trust = type instanceof Decimal ? Trust.DECIMAL
                : type == Scalar.FLOAT || type == Scalar.DOUBLE ? Trust.DOUBLE
                : type == Scalar.TIMESTAMP ? Trust.TIMESTAMP
                : type == Scalar.BOOLEAN ? Trust.BOOLEAN : null;
        return trust != null && settings.trusted().contains(trust);
    }

    @Override
    boolean ordersExactly(DataType type) {
        return storesExactly(type);
    }

    /**
     * Tells whether the database evaluates a filter as FyrQL does: a
     * predicate over a column and literals, as the class says; never a
     * bare column or literal as a condition, which not every database
     * reads, and never {@code IS DISTINCT FROM}.
     */
    @Override
    public boolean supports(Expression expression) {
        return settings.filters() && predicate(expression) && super.supports(expression);
    }

    /** Tells whether a condition is made of the predicates the class lists, over columns and literals. */
    private static boolean predicate(Expression e) {
        return switch (e) {
            case Expression.And a -> predicate(a.left()) && predicate(a.right());
            case Expression.Or o -> predicate(o.left()) && predicate(o.right());
            case Expression.Not n -> predicate(n.operand());
            case Expression.Comparison c -> operand(c.left()) && operand(c.right())
                    && (c.left() instanceof Expression.Column || c.right() instanceof Expression.Column);
            case Expression.Between b -> b.value() instanceof Expression.Column && operand(b.low())
                    && operand(b.high());
            case Expression.In in -> in.value() instanceof Expression.Column;
            case Expression.IsNull n -> n.operand() instanceof Expression.Column;
            default -> false;
        };
    }

    private static boolean operand(Expression e) {
        return e instanceof Expression.Column || e instanceof Expression.Literal;
    }

    /**
     * Returns the exact filter if the dialect {@linkplain #supports
     * supports} it, else a weaker condition on strings that every database
     * evaluates at least as widely: {@code =} or {@code IN} against
     * strings that are not empty, {@code LIKE} with a pattern that is not
     * empty and has no {@code \} or {@code [}, which some databases read as
     * an escape or a class of characters, and {@code IS NULL}; an
     * {@code AND} keeps the hints of its sides, an {@code OR} needs one on
     * both. A string is compared with or without regard to the case and to
     * trailing spaces, so the database may return more rows, never fewer.
     */
    @Override
    public Optional<Expression> hint(Expression filter) {
        if (!settings.filters()) {
            return Optional.empty();
        }
        if (supports(filter)) {
            return Optional.of(filter);
        }
        return switch (filter) {
            case Expression.And a -> {
                Optional<Expression> left = hint(a.left());
                Optional<Expression> right = hint(a.right());
                yield left.isPresent() && right.isPresent()
                        ? Optional.of(new Expression.And(left.get(), right.get())) : left.or(() -> right);
            }
            case Expression.Or o -> {
                Optional<Expression> left = hint(o.left());
                Optional<Expression> right = hint(o.right());
                yield left.isPresent() && right.isPresent()
                        ? Optional.of(new Expression.Or(left.get(), right.get())) : Optional.empty();
            }
            case Expression.Comparison c when c.op() == Expression.ComparisonOp.EQ
                    && (string(c.left()) && text(c.right()) || text(c.left()) && string(c.right())) -> Optional.of(filter);
            case Expression.In in when string(in.value()) && !in.list().isEmpty()
                    && in.list().stream().allMatch(GenericDialect::text) -> Optional.of(filter);
            case Expression.Like l when !l.caseInsensitive() && string(l.string()) && pattern(l.pattern()) ->
                    Optional.of(filter);
            case Expression.IsNull n when !n.negated() && n.operand() instanceof Expression.Column -> Optional.of(filter);
            default -> Optional.empty();
        };
    }

    /** Tells whether an expression is a column of strings. */
    private static boolean string(Expression e) {
        return e instanceof Expression.Column c && c.type() == DataType.VARCHAR;
    }

    /** Tells whether an expression is a string literal that is neither {@code NULL} nor empty. */
    private static boolean text(Expression e) {
        return e instanceof Expression.Literal l && l.value() instanceof String s && !s.isEmpty();
    }

    /** Tells whether an expression is a pattern of {@code LIKE} that every database reads as FyrQL does. */
    private static boolean pattern(Expression e) {
        return text(e) && ((Expression.Literal) e).value() instanceof String s && s.indexOf('\\') < 0
                && s.indexOf('[') < 0;
    }

    /** No: the databases differ in the place of {@code NULL}s and in the order of strings. */
    @Override
    public boolean supportsOrder(List<SortKey> keys, Schema schema) {
        return false;
    }

    /** No: FyrQL groups and aggregates itself. */
    @Override
    public boolean supportsAggregation(PlanNode.Aggregation aggregation, Schema schema) {
        return false;
    }
}
