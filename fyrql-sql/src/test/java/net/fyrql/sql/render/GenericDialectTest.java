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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.GenericDialect.LimitStyle;
import net.fyrql.sql.render.GenericDialect.Settings;
import net.fyrql.sql.render.GenericDialect.Trust;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.ge;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.ilike;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.le;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic dialect: it quotes and names types as the settings say, takes
 * over only filters on integers and dates and what it is trusted with,
 * hands conditions on strings over as hints, binds its literals and keeps
 * the first rows as the settings say.
 */
class GenericDialectTest {

    private static final Schema S = FullPlan.ORDERS;
    private static final String TABLE = FullPlan.tableName(FullPlan.ORDERS_REF);

    private static GenericDialect with(Set<Trust> trusted) {
        return GenericDialect.of(new Settings("\"", LimitStyle.MAX_ROWS, trusted, true));
    }

    private static GenericDialect with(String quote, LimitStyle limit) {
        return GenericDialect.of(new Settings(quote, limit, Set.of(), true));
    }

    @Test
    void itQuotesAsTheSettingsSay() {
        assertEquals("\"a\"\"b\"", GenericDialect.DEFAULT.quoteIdentifier("a\"b"));
        assertEquals("`a``b`", with("`", LimitStyle.MAX_ROWS).quoteIdentifier("a`b"));
        assertEquals("[a]]b]", with("[", LimitStyle.MAX_ROWS).quoteIdentifier("a]b"));
        assertEquals("a b", with("", LimitStyle.MAX_ROWS).quoteIdentifier("a b"));
        assertThrows(IllegalArgumentException.class, () -> with("'", LimitStyle.MAX_ROWS));
        assertSame(GenericDialect.DEFAULT, GenericDialect.of(new Settings("\"", LimitStyle.MAX_ROWS, Set.of(), true)));
        assertEquals("generic", GenericDialect.DEFAULT.name());
    }

    @Test
    void itNamesTheTypesOfSql92() {
        GenericDialect d = GenericDialect.DEFAULT;
        assertEquals("SMALLINT", d.typeName(DataType.TINYINT));
        assertEquals("INTEGER", d.typeName(DataType.INTEGER));
        assertEquals("BIGINT", d.typeName(DataType.BIGINT));
        assertEquals("DECIMAL(10,2)", d.typeName(DataType.decimal(10, 2)));
        assertEquals("REAL", d.typeName(DataType.FLOAT));
        assertEquals("DOUBLE PRECISION", d.typeName(DataType.DOUBLE));
        assertEquals("VARCHAR(4000)", d.typeName(DataType.VARCHAR));
        assertEquals("BLOB", d.typeName(DataType.BLOB));
        assertEquals("TIMESTAMP", d.typeName(DataType.TIMESTAMP));
        assertEquals("CHAR(36)", d.typeName(DataType.UUID));
        assertThrows(IllegalArgumentException.class, () -> d.typeName(DataType.HUGEINT));

        assertEquals("5", d.literal(5, DataType.INTEGER));
        assertEquals("1.50", d.literal(new BigDecimal("1.50"), DataType.decimal(10, 2)));
        assertEquals("'it''s'", d.literal("it's", DataType.VARCHAR));
        assertEquals("DATE '1970-01-02'", d.literal(1, DataType.DATE));
        assertEquals("'00000000-0000-0001-0000-000000000002'", d.literal(new UUID(1, 2), DataType.UUID));
        assertEquals("CAST(NULL AS INTEGER)", d.literal(null, DataType.INTEGER));
    }

    /** What a source may evaluate: integers and dates, the rest only where trusted. */
    @Test
    void itTakesOverFiltersOnIntegersAndDates() {
        GenericDialect d = GenericDialect.DEFAULT;
        Expression integers = and(gt(col(S, 0), lit(1)), or(not(isNull(col(S, 7))), lt(col(S, 9), lit(5L))));
        Expression forms = and(between(col(S, 8), lit((short) 1), lit((short) 2)), in(col(S, 0), lit(1), lit(2)));
        Expression date = and(gt(col(S, 4), lit(DataType.DATE, 1)), le(col(S, 4), lit(DataType.DATE, 9)));
        assertTrue(d.supports(integers));
        assertTrue(d.supports(forms));
        assertTrue(d.supports(date));
        assertTrue(d.supports(in(col(S, 0), lit(1), nullOf(DataType.INTEGER))));

        Expression decimal = gt(col(S, 1), lit(new BigDecimal("1.50")));
        Expression timestamp = lt(col(S, 5), lit(DataType.TIMESTAMP, 1L));
        Expression floating = and(gt(col(S, 6), lit(1.5)), eq(col(S, 10), lit(1.5f)));
        Expression bool = eq(col(S, 3), lit(false));
        for (Expression e : List.of(decimal, timestamp, floating, bool)) {
            assertFalse(d.supports(e), e.toString());
        }
        assertTrue(with(Set.of(Trust.DECIMAL)).supports(decimal));
        assertTrue(with(Set.of(Trust.TIMESTAMP)).supports(timestamp));
        assertTrue(with(Set.of(Trust.DOUBLE)).supports(floating));
        assertTrue(with(Set.of(Trust.BOOLEAN)).supports(bool));
        assertFalse(with(Set.of(Trust.DECIMAL)).supports(timestamp));

        GenericDialect all = with(Set.of(Trust.values()));
        List<Expression> never = List.of(eq(col(S, 2), lit("a")), lt(col(S, 2), lit("m")),
                isNull(col(S, 12)), eq(col(S, 11), lit(DataType.BLOB, new byte[] {1})),
                isDistinct(col(S, 0), lit(3)), col(S, 3), not(col(S, 3)), and(col(S, 3), gt(col(S, 0), lit(1))),
                lit(true), eq(lit(1), lit(2)), between(lit(1), col(S, 0), col(S, 9)),
                eq(eq(col(S, 0), lit(1)), lit(true)), gt(add(col(S, 0), lit(1)), lit(2)),
                eq(cast(col(S, 0), DataType.BIGINT), lit(1L)), like(col(S, 2), lit("a%")),
                eq(call("upper", col(S, 2)), lit("A")), in(col(S, 0), lit(1), col(S, 7)));
        for (Expression e : never) {
            assertFalse(all.supports(e), e.toString());
        }
        assertFalse(GenericDialect.of(new Settings("\"", LimitStyle.MAX_ROWS, Set.of(), false)).supports(integers));
    }

    @Test
    void itTakesOverNoOrderAggregationJoinOrWindow() {
        GenericDialect d = with(Set.of(Trust.values()));
        assertFalse(d.supportsOrder(List.of(SortKey.asc(0)), S));
        assertFalse(d.supportsAggregation(new PlanNode.Aggregation(List.of(0),
                List.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"))), S));
        for (JoinType t : JoinType.values()) {
            assertFalse(d.supportsJoin(t), t.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> d.isDistinct("a", "b", false));
        assertThrows(IllegalArgumentException.class, () -> d.like("a", "b", true));
        assertEquals("(a LIKE b)", d.like("a", "b", false));
        assertThrows(IllegalArgumentException.class, () -> d.aggregate(AggregateFunction.COUNT_STAR, false, "*",
                null, List.of()));
    }

    /** A hint holds wherever the filter holds, in every database. */
    @Test
    void conditionsOnStringsAreHints() {
        GenericDialect d = GenericDialect.DEFAULT;
        Expression note = col(S, 2);
        List<Expression> hints = List.of(eq(note, lit("ab")), eq(lit("ab"), note), in(note, lit("a"), lit("b")),
                like(note, lit("a%_")), isNull(note), isNull(col(S, 6)), gt(col(S, 0), lit(1)),
                or(eq(note, lit("x")), gt(col(S, 0), lit(1))), and(gt(col(S, 0), lit(1)), eq(note, lit("x"))));
        for (Expression e : hints) {
            assertEquals(Optional.of(e), d.hint(e), e.toString());
        }
        List<Expression> none = List.of(eq(note, lit("")), in(note, lit("a"), lit("")),
                in(note, lit("a"), nullOf(DataType.VARCHAR)), ne(note, lit("ab")), lt(note, lit("m")),
                between(note, lit("a"), lit("b")), like(note, lit("a\\%")), like(note, lit("[a]%")),
                like(note, lit("")), ilike(note, lit("a%")), isNotNull(note), not(eq(note, lit("x"))),
                or(ne(note, lit("x")), gt(col(S, 0), lit(1))), eq(call("upper", note), lit("A")),
                eq(note, col(S, 2)), gt(col(S, 6), lit(1.5)));
        for (Expression e : none) {
            assertEquals(Optional.empty(), d.hint(e), e.toString());
        }
        assertEquals(Optional.of(gt(col(S, 0), lit(1))), d.hint(and(ne(note, lit("x")), gt(col(S, 0), lit(1)))));
        assertEquals(Optional.of(eq(note, lit("x"))), d.hint(and(eq(note, lit("x")), lt(note, lit("y")))));
        assertEquals(Optional.empty(), GenericDialect.of(new Settings("\"", LimitStyle.MAX_ROWS, Set.of(), false))
                .hint(eq(note, lit("ab"))));

        // Any other dialect hints what it supports.
        assertEquals(Optional.of(eq(note, lit("ab"))), H2Dialect.INSTANCE.hint(eq(note, lit("ab"))));
        assertEquals(Optional.empty(), H2Dialect.INSTANCE.hint(lt(note, lit("m"))));
    }

    private static PlanNode.Scan scan(OptionalLong limit, List<Expression> hints) {
        Schema s = new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of(2, 0, 4)).schema();
        PlanNode.Pushed pushed = new PlanNode.Pushed(List.of(gt(col(s, 1), lit(5)), isNull(col(s, 2))), limit,
                List.of(), Optional.empty(), hints);
        return new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of(2, 0, 4), pushed);
    }

    @Test
    void aScanBindsItsLiteralsAndCarriesItsLimit() {
        Schema s = new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of(2, 0, 4)).schema();
        PlanNode.Scan scan = scan(OptionalLong.of(10), List.of(eq(col(s, 0), lit("ab"))));
        SqlRenderer r = new SqlRenderer(GenericDialect.DEFAULT, FullPlan::tableName);
        String select = "SELECT t.\"note\", t.\"id\", t.\"day\" FROM " + TABLE + " t WHERE (((t.\"id\" > ?) AND "
                + "(t.\"day\" IS NULL)) AND (t.\"note\" = ?))";
        BoundSql bound = r.renderScanBound(scan, null);
        assertEquals(select, bound.sql());
        assertEquals(List.of(new BoundSql.Parameter(DataType.INTEGER, 5), new BoundSql.Parameter(DataType.VARCHAR,
                "ab")), bound.parameters());
        assertEquals(OptionalLong.of(10), bound.maxRows());
        assertEquals(select + " -- parameters: 5, 'ab'; max rows: 10", bound.toString());

        // The parameters are those of each call, also with a range of keys over the columns of the table.
        BoundSql part = r.renderScanBound(scan, and(ge(col(S, 0), lit(100)), lt(col(S, 0), lit(200))));
        assertEquals(select + " AND ((t.\"id\" >= ?) AND (t.\"id\" < ?))", part.sql());
        assertEquals(List.of(5, "ab", 100, 200), part.parameters().stream().map(BoundSql.Parameter::value).toList());
        assertEquals(2, r.renderScanBound(scan, null).parameters().size());

        BoundSql nulls = r.renderScanBound(new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of(0),
                new PlanNode.Pushed(List.of(in(col(S, 0), lit(1), nullOf(DataType.INTEGER))), OptionalLong.empty(),
                        List.of(), Optional.empty())), null);
        assertEquals("SELECT t.\"id\" FROM " + TABLE + " t WHERE (t.\"id\" IN (?, CAST(NULL AS INTEGER)))",
                nulls.sql());
        assertEquals(1, nulls.parameters().size());
        assertTrue(r.renderScanBound(new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of()), null).isPlain());

        // Outside a scan nothing is bound.
        assertEquals("(t0.c0 > 5)", r.expression(gt(col(S, 0), lit(5)), "t0"));
        assertThrows(IllegalArgumentException.class, () -> r.renderScan(scan));
        assertThrows(IllegalArgumentException.class, () -> r.render(scan));
    }

    @Test
    void theFirstRowsAreKeptAsTheSettingsSay() {
        PlanNode.Scan scan = scan(OptionalLong.of(10), List.of());
        String where = " FROM " + TABLE + " t WHERE ((t.\"id\" > ?) AND (t.\"day\" IS NULL))";
        String columns = "t.\"note\", t.\"id\", t.\"day\"";

        BoundSql limit = new SqlRenderer(with("\"", LimitStyle.LIMIT), FullPlan::tableName).renderScanBound(scan, null);
        assertEquals("SELECT " + columns + where + " LIMIT 10", limit.sql());
        assertEquals(OptionalLong.empty(), limit.maxRows());
        assertEquals("SELECT " + columns + where + " FETCH FIRST 10 ROWS ONLY", new SqlRenderer(
                with("\"", LimitStyle.FETCH_FIRST), FullPlan::tableName).renderScanBound(scan, null).sql());
        BoundSql top = new SqlRenderer(with("[", LimitStyle.TOP), FullPlan::tableName).renderScanBound(scan, null);
        assertEquals("SELECT TOP 10 t.[note], t.[id], t.[day] FROM " + TABLE
                + " t WHERE ((t.[id] > ?) AND (t.[day] IS NULL))", top.sql());
        assertEquals(OptionalLong.empty(), top.maxRows());
        assertEquals(OptionalLong.of(0), new SqlRenderer(GenericDialect.DEFAULT, FullPlan::tableName)
                .renderScanBound(scan(OptionalLong.of(0), List.of()), null).maxRows());

        assertThrows(IllegalArgumentException.class, () -> GenericDialect.DEFAULT.limit(5, 0));
        assertThrows(IllegalArgumentException.class, () -> with("\"", LimitStyle.TOP).limit(5, 0));
        assertThrows(IllegalArgumentException.class, () -> with("\"", LimitStyle.LIMIT).limit(5, 1));
    }

    /** A dialect that writes its literals renders the scan as before, as a plain statement. */
    @Test
    void anyOtherDialectRendersAPlainStatement() {
        Schema s = new PlanNode.Scan(FullPlan.ORDERS_REF, S, List.of(2, 0, 4)).schema();
        PlanNode.Scan scan = scan(OptionalLong.of(10), List.of(eq(col(s, 0), lit("ab"))));
        SqlRenderer r = new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName);
        BoundSql bound = r.renderScanBound(scan, null);
        assertTrue(bound.isPlain());
        assertEquals("SELECT t.\"note\", t.\"id\", t.\"day\" FROM " + TABLE + " t WHERE (((t.\"id\" > "
                + "CAST(5 AS INTEGER)) AND (t.\"day\" IS NULL)) AND (t.\"note\" = 'ab')) LIMIT 10", bound.sql());
        assertEquals(bound.sql(), r.renderScan(scan));
        assertEquals(bound.sql(), bound.toString());

        assertEquals("SELECT 1", BoundSql.of("SELECT 1").toString());
        assertThrows(IllegalArgumentException.class, () -> new BoundSql("SELECT 1", List.of(), OptionalLong.of(-1)));
        assertThrows(NullPointerException.class, () -> new BoundSql.Parameter(DataType.INTEGER, null));
    }
}
