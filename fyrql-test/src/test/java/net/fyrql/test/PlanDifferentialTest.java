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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.caseWhen;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.coalesce;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.div;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.ilike;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.intDiv;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNotDistinct;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.mul;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.nullOf;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.rem;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.Expressions.tryCast;
import static net.fyrql.core.expr.Expressions.when;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built plans over generated data with edge values, each compared
 * with DuckDB: every operator, every join type, the arithmetic and
 * comparison semantics, windows, and nested types passing through.
 */
class PlanDifferentialTest {

    /**
     * The tolerance for plans with {@code avg} over {@code BIGINT}: DuckDB
     * divides the 128-bit sum in a {@code long double}, which is a double on
     * Windows, as FyrQL divides, but has 80 bits on x86-64 Linux, where the
     * result may differ in the last place.
     */
    private static final int AVG_ULPS = 1;

    private static final Schema T = Schema.of(
            new Schema.Field("i", DataType.INTEGER), new Schema.Field("l", DataType.BIGINT),
            new Schema.Field("d", DataType.DOUBLE), new Schema.Field("dec", DataType.decimal(10, 2)),
            new Schema.Field("s", DataType.VARCHAR), new Schema.Field("b", DataType.BOOLEAN),
            new Schema.Field("dt", DataType.DATE), new Schema.Field("t", DataType.TINYINT));
    private static final Schema U = Schema.of(
            new Schema.Field("k", DataType.INTEGER), new Schema.Field("v", DataType.VARCHAR),
            new Schema.Field("h", DataType.HUGEINT), new Schema.Field("ts", DataType.TIMESTAMP));
    private static final Schema N = Schema.of(
            new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("st", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                    new DataType.Struct.Field("b", DataType.VARCHAR))),
            new Schema.Field("li", DataType.listOf(DataType.decimal(5, 1))),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));

    private static Differential differential;
    private static PlanNode.Scan t;
    private static PlanNode.Scan u;
    private static PlanNode.Scan n;

    @BeforeAll
    static void setUp() {
        List<Object[]> tRows = DataGenerator.withSeed(5).generate(T, 300);
        List<Object[]> uRows = DataGenerator.withSeed(6).generate(U, 120);
        List<Object[]> nRows = DataGenerator.withSeed(7).generate(N, 60);
        // Join keys within a small range so that joins find matches.
        for (int i = 0; i < uRows.size(); i++) {
            uRows.get(i)[0] = i % 7 == 0 ? null : i % 40;
        }
        for (int i = 0; i < tRows.size(); i++) {
            if (tRows.get(i)[0] != null) {
                tRows.get(i)[0] = (Integer) tRows.get(i)[0] % 50;
            }
        }
        FyrQL engine = FyrQL.builder()
                .register(MemoryConnector.builder("mem").table("t", T, tRows).table("u", U, uRows).table("n", N, nRows)
                        .batchSize(37).build())
                .batchSize(37)
                .build();
        differential = new Differential(engine);
        t = engine.scan(TableRef.of("mem", "t"));
        u = engine.scan(TableRef.of("mem", "u"));
        n = engine.scan(TableRef.of("mem", "n"));
    }

    @AfterAll
    static void tearDown() {
        differential.close();
    }

    private static PlanNode sorted(PlanNode input) {
        List<SortKey> keys = new ArrayList<>();
        for (int i = 0; i < input.schema().size(); i++) {
            keys.add(SortKey.asc(i));
        }
        return new PlanNode.Sort(input, keys);
    }

    private static Expression c(int index) {
        return col(T, index);
    }

    @Test
    void filtersAndExpressions() {
        List<Expression> conditions = List.of(
                gt(c(0), lit(10)),
                and(lt(c(2), lit(0.5)), not(isNull(c(4)))),
                or(eq(c(5), lit(true)), between(c(7), lit((byte) -5), lit((byte) 5))),
                in(c(0), lit(1), lit(2), lit(3), lit(DataType.INTEGER, null)),
                like(c(4), lit("%a%")),
                like(c(4), lit("_")),
                eq(rem(c(0), lit(7)), lit(3)),
                gt(div(c(1), lit(3L)), lit(1.5)),
                lt(intDiv(c(0), lit(4)), lit(2)),
                ne(c(3), lit(new BigDecimal("0.00"))),
                gt(cast(c(0), DataType.DOUBLE), c(2)),
                eq(c(6), lit(DataType.DATE, 0)));
        for (Expression condition : conditions) {
            differential.check(new PlanNode.Filter(t, condition));
        }
    }

    /**
     * Constant parts of mixed types, which the optimizer evaluates before
     * the query runs, constant conditions next to columns, and conjuncts
     * that every branch of an {@code OR} has in common, with {@code NULL}s
     * in both branches.
     */
    @Test
    void foldedConstantsAndFactoredConditions() {
        differential.check(new PlanNode.Project(t, List.of(
                new NamedExpression("a", add(lit((byte) 1), lit(2L))),
                new NamedExpression("b", mul(lit(new BigDecimal("1.5")), lit(2))),
                new NamedExpression("c", add(c(2), cast(lit("12"), DataType.DOUBLE))),
                new NamedExpression("d", sub(lit(DataType.DATE, 100), lit(7))),
                new NamedExpression("e", caseWhen(List.of(when(lit(false), lit(1L)), when(gt(c(0), lit(0)), lit(2))),
                        cast(c(7), DataType.INTEGER))),
                new NamedExpression("f", caseWhen(List.of(when(nullOf(DataType.BOOLEAN), lit("n")),
                        when(lit(true), c(4))), lit("z"))),
                new NamedExpression("g", call("concat", lit("<"), call("upper", lit("x")), c(4))),
                new NamedExpression("h", and(lit(true), gt(c(1), lit(0L)))),
                new NamedExpression("i", or(lit(false), c(5))),
                new NamedExpression("j", and(c(5), nullOf(DataType.BOOLEAN))),
                new NamedExpression("k", not(not(c(5)))))));
        Expression key = eq(c(0), lit(3));
        Expression text = isNull(c(4));
        List<Expression> conditions = List.of(
                or(and(key, gt(c(2), lit(0.5))), and(lt(c(7), lit((byte) 0)), key)),
                or(and(eq(c(7), lit((byte) 1)), text), and(eq(c(7), lit((byte) 1)), not(text))),
                or(or(and(text, c(5)), and(c(5), lt(c(2), lit(0.0)))), and(c(5), text)),
                or(and(in(c(4), lit("a"), lit("b")), c(5)), in(c(4), lit("a"), lit("b"))),
                and(gt(c(0), lit(10)), or(lit(false), lt(lit(1), lit(2)))),
                or(and(gt(c(0), lit(10)), lit(false)), eq(c(0), lit(5))));
        for (Expression condition : conditions) {
            differential.check(new PlanNode.Filter(t, condition));
        }
        // The join condition in each branch, as in TPC-H Q19.
        PlanNode cross = new PlanNode.Join(t, u, List.of(), List.of(), JoinType.CROSS);
        Expression joined = eq(c(0), col(cross.schema(), T.size()));
        Expression v = col(cross.schema(), T.size() + 1);
        differential.check(new PlanNode.Filter(cross, or(or(
                and(and(joined, gt(c(2), lit(0.0))), like(v, lit("%a%"))),
                and(isNull(c(4)), joined)),
                and(joined, and(c(5), joined)))));
    }

    @Test
    void projections() {
        PlanNode plan = new PlanNode.Project(t, List.of(
                new NamedExpression("sum", add(c(0), c(1))),
                new NamedExpression("product", mul(cast(c(7), DataType.INTEGER), lit(1000))),
                new NamedExpression("quotient", div(c(2), c(2))),
                new NamedExpression("decimal", mul(c(3), lit(new BigDecimal("1.5")))),
                new NamedExpression("text", call("concat", c(4), lit("-"), c(0))),
                new NamedExpression("len", call("length", c(4))),
                new NamedExpression("sub", call("substring", c(4), lit(2), lit(3))),
                new NamedExpression("abs", call("abs", c(2))),
                new NamedExpression("round", call("round", c(2), lit(1))),
                new NamedExpression("cased", caseWhen(List.of(when(gt(c(0), lit(0)), lit("pos")),
                        when(lt(c(0), lit(0)), lit("neg"))), lit(DataType.VARCHAR, null))),
                new NamedExpression("co", coalesce(c(0), lit(-1))),
                new NamedExpression("date", cast(c(6), DataType.VARCHAR)),
                new NamedExpression("as_dec", cast(c(0), DataType.decimal(12, 3))),
                new NamedExpression("neg", sub(lit(0L), c(1)))));
        // Overflow of i + l is possible with the edge values, so keep to the rows where it cannot happen.
        PlanNode safe = new PlanNode.Filter(t, and(between(c(0), lit(-1000), lit(1000)),
                between(c(1), lit(-1_000_000L), lit(1_000_000L))));
        differential.check(new PlanNode.Project(safe, ((PlanNode.Project) plan).columns()));
    }

    @Test
    void numericFunctions() {
        PlanNode safe = new PlanNode.Filter(t, and(between(c(0), lit(-1000), lit(1000)),
                between(c(1), lit(-1_000_000L), lit(1_000_000L))));
        Expression d = c(2);
        Expression dec = c(3);
        differential.check(new PlanNode.Project(safe, List.of(
                new NamedExpression("ceil_d", call("ceil", d)),
                new NamedExpression("ceil_i", call("ceiling", c(0))),
                new NamedExpression("floor_dec", call("floor", dec)),
                new NamedExpression("trunc_d", call("trunc", d)),
                new NamedExpression("trunc_dec", call("trunc", dec)),
                new NamedExpression("trunc_t", call("trunc", c(7))),
                new NamedExpression("round_d", call("round", d)),
                new NamedExpression("round_d2", call("round", d, lit(2))),
                new NamedExpression("round_dm", call("round", d, lit(-1))),
                new NamedExpression("round_dec", call("round", dec)),
                new NamedExpression("round_l", call("round", c(1), lit(-3))),
                new NamedExpression("round_i", call("round", c(0), lit(-2))),
                new NamedExpression("sign_d", call("sign", d)),
                new NamedExpression("sign_dec", call("sign", dec)),
                new NamedExpression("sign_l", call("sign", c(1))),
                new NamedExpression("abs_dec", call("abs", dec)),
                new NamedExpression("sqrt", call("sqrt", call("abs", d))),
                new NamedExpression("ln", call("ln", add(call("abs", d), lit(1)))),
                new NamedExpression("log10", call("log10", add(call("abs", dec), lit(1)))),
                new NamedExpression("log2", call("log2", add(call("abs", c(0)), lit(1)))),
                new NamedExpression("logb", call("log", lit(3), add(call("abs", c(1)), lit(2L)))),
                new NamedExpression("exp", call("exp", c(7))),
                new NamedExpression("power", call("power", d, lit(2))),
                new NamedExpression("pow", call("pow", lit(2), c(7))),
                new NamedExpression("greatest", call("greatest", c(0), c(1), c(7))),
                new NamedExpression("least", call("least", d, dec)),
                new NamedExpression("greatest_s", call("greatest", c(4), lit("m"))),
                new NamedExpression("typeof", call("typeof", dec)))), 2);
    }

    @Test
    void stringFunctions() {
        Expression s = c(4);
        differential.check(new PlanNode.Project(t, List.of(
                new NamedExpression("length", call("length", s)),
                new NamedExpression("lower", call("lower", s)),
                new NamedExpression("upper", call("upper", s)),
                new NamedExpression("reverse", call("reverse", s)),
                new NamedExpression("trim", call("trim", s)),
                new NamedExpression("trim_a", call("trim", s, lit("a "))),
                new NamedExpression("ltrim", call("ltrim", s, lit("ab"))),
                new NamedExpression("rtrim", call("rtrim", s)),
                new NamedExpression("replace", call("replace", s, lit("a"), lit("xy"))),
                new NamedExpression("contains", call("contains", s, lit("a"))),
                new NamedExpression("prefix", call("starts_with", s, lit("a"))),
                new NamedExpression("suffix", call("ends_with", s, lit("b"))),
                new NamedExpression("strpos", call("strpos", s, lit("b"))),
                new NamedExpression("left", call("left", s, lit(2))),
                new NamedExpression("left_neg", call("left", s, lit(-1))),
                new NamedExpression("right", call("right", s, lit(3L))),
                new NamedExpression("right_neg", call("right", s, lit((byte) -2))),
                new NamedExpression("repeat", call("repeat", s, lit(2))),
                new NamedExpression("lpad", call("lpad", s, lit(5), lit("xy"))),
                new NamedExpression("rpad", call("rpad", s, lit(6), lit("."))),
                new NamedExpression("split", call("split_part", s, lit("a"), lit(2))),
                new NamedExpression("split_last", call("split_part", s, lit(""), lit(-1))),
                new NamedExpression("concat_ws", call("concat_ws", lit("-"), s, c(0), c(3))),
                new NamedExpression("concat_op", call("||", s, c(7))),
                new NamedExpression("sub_end", call("substring", s, lit(-2), lit(2))),
                new NamedExpression("sub_zero", call("substring", s, lit(0), lit(3))),
                new NamedExpression("sub_back", call("substr", s, lit(2), lit(-1))),
                new NamedExpression("ilike", ilike(s, lit("%A_"))))));
    }

    @Test
    void joins() {
        Schema pair = T.concat(U);
        Expression otherText = ne(col(pair, 4), col(pair, 9));
        Expression below = lt(col(pair, 0), col(pair, 8));
        for (JoinType type : JoinType.values()) {
            if (type == JoinType.MARK || type == JoinType.SINGLE) {
                // DuckDB has no such joins in SQL; see markAndSingleJoinsAreSubqueries.
                continue;
            }
            PlanNode join = type == JoinType.CROSS
                    ? new PlanNode.Join(new PlanNode.Limit(sorted(t), 20), new PlanNode.Limit(sorted(u), 10), List.of(),
                            List.of(), type)
                    : new PlanNode.Join(t, u, List.of(0), List.of(0), type);
            differential.check(join);
            if (type != JoinType.CROSS) {
                differential.check(new PlanNode.Join(t, u, List.of(0), List.of(0), type, otherText));
            }
            if (type == JoinType.SEMI || type == JoinType.ANTI) {
                differential.check(new PlanNode.Join(t, u, List.of(), List.of(), type, below));
            }
        }
    }

    /** Compares the rows of a plan with those DuckDB gives for a query over the copies of its tables. */
    private static void checkAgainst(PlanNode plan, String sql) {
        differential.loadTables(plan);
        String query = sql.replace("$t", DuckDbReference.tableName(t.table()))
                .replace("$u", DuckDbReference.tableName(u.table()));
        ResultComparison.Outcome outcome = ResultComparison.compare(plan.schema(),
                differential.duck().query(query, plan.schema()), differential.engine().rows(plan), false);
        assertTrue(outcome.same(), outcome.report() + Plans.print(plan) + query);
    }

    @Test
    void markAndSingleJoinsAreSubqueries() {
        Schema pair = T.concat(U);
        PlanNode mark = new PlanNode.Join(t, u, List.of(0), List.of(0), JoinType.MARK);
        checkAgainst(mark, "SELECT *, i IN (SELECT k FROM $u) FROM $t");
        PlanNode noNullKeys = new PlanNode.Filter(u, not(isNull(col(U, 0))));
        checkAgainst(new PlanNode.Join(t, noNullKeys, List.of(0), List.of(0), JoinType.MARK),
                "SELECT *, i IN (SELECT k FROM $u WHERE k IS NOT NULL) FROM $t");
        PlanNode empty = new PlanNode.Filter(u, lit(false));
        checkAgainst(new PlanNode.Join(t, empty, List.of(0), List.of(0), JoinType.MARK),
                "SELECT *, i IN (SELECT k FROM $u WHERE false) FROM $t");
        // DuckDB 1.5.6 finds a row for v <> s where s is NULL, so the left rows here have a text.
        PlanNode texts = new PlanNode.Filter(t, not(isNull(col(T, 4))));
        PlanNode exists = new PlanNode.Join(texts, u, List.of(0), List.of(0), JoinType.MARK,
                ne(col(pair, 4), col(pair, 9)));
        checkAgainst(new PlanNode.Project(exists, List.of(new NamedExpression("i", col(T, 0)),
                new NamedExpression("e", coalesce(col(exists.schema(), T.size()), lit(false))))),
                "SELECT i, EXISTS (SELECT 1 FROM $u WHERE k = i AND v <> s) FROM $t WHERE s IS NOT NULL");
        PlanNode unique = new PlanNode.Aggregate(u, List.of(0), List.of(
                new AggregateCall(AggregateFunction.MAX, 1, false, "m")));
        checkAgainst(new PlanNode.Join(t, unique, List.of(0), List.of(0), JoinType.SINGLE),
                "SELECT * FROM $t LEFT JOIN (SELECT k, max(v) AS m FROM $u GROUP BY k) a ON a.k = i");
        checkAgainst(new PlanNode.Join(t, unique, List.of(), List.of(), JoinType.SINGLE,
                eq(col(pair, 0), add(col(pair, 8), lit(1)))),
                "SELECT * FROM $t LEFT JOIN (SELECT k, max(v) AS m FROM $u GROUP BY k) a ON a.k + 1 = i");
        // More than one match fails in both.
        PlanNode twice = new PlanNode.Project(new PlanNode.Join(t, u, List.of(0), List.of(0), JoinType.SINGLE),
                List.of(new NamedExpression("v", col(pair, 9))));
        assertTrue(assertThrows(RuntimeException.class, () -> differential.engine().rows(twice)).getMessage()
                .contains("More than one row returned by a subquery used as an expression"));
        assertThrows(RuntimeException.class, () -> differential.duck().query("SELECT (SELECT v FROM "
                + DuckDbReference.tableName(u.table()) + " WHERE k = i) FROM " + DuckDbReference.tableName(t.table()),
                twice.schema()));
    }

    @Test
    void aggregates() {
        PlanNode plan = new PlanNode.Aggregate(t, List.of(5, 7), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.COUNT, 0, false, "count_i"),
                new AggregateCall(AggregateFunction.COUNT, 4, true, "distinct_s"),
                new AggregateCall(AggregateFunction.SUM, 0, false, "sum_i"),
                new AggregateCall(AggregateFunction.SUM, 3, false, "sum_dec"),
                new AggregateCall(AggregateFunction.MIN, 2, false, "min_d"),
                new AggregateCall(AggregateFunction.MAX, 4, false, "max_s"),
                new AggregateCall(AggregateFunction.MAX, 6, false, "max_dt"),
                new AggregateCall(AggregateFunction.AVG, 1, false, "avg_l")));
        differential.check(plan, AVG_ULPS);
        differential.check(new PlanNode.Aggregate(u, List.of(), List.of(
                new AggregateCall(AggregateFunction.SUM, 0, false, "sum_k"),
                new AggregateCall(AggregateFunction.MIN, 3, false, "min_ts"),
                new AggregateCall(AggregateFunction.MAX, 1, false, "max_v"))));
        differential.check(new PlanNode.Aggregate(new PlanNode.Filter(t, lit(false)), List.of(), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.SUM, 0, false, "s"))));
        // The standard deviations and variances of integers, decimals and doubles, as DuckDB casts them to DOUBLE.
        List<AggregateCall> statistics = List.of(agg(AggregateFunction.STDDEV_SAMP, 0, false),
                agg(AggregateFunction.STDDEV_POP, 1, false), agg(AggregateFunction.VAR_SAMP, 3, false),
                agg(AggregateFunction.VAR_POP, 0, true), agg(AggregateFunction.VAR_SAMP, 2, false));
        differential.check(new PlanNode.Aggregate(t, List.of(5), statistics), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(t, List.of(7), statistics), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(t, List.of(), statistics), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(new PlanNode.Filter(t, lit(false)), List.of(), statistics));
    }

    private static AggregateCall agg(AggregateFunction f, int column, boolean distinct, SortKey... orderBy) {
        return new AggregateCall(f, column, distinct, f.sqlName() + column).withOrderBy(List.of(orderBy));
    }

    /**
     * The aggregates of E2: {@code string_agg}, {@code list}, {@code first},
     * {@code last}, {@code any_value}, {@code bool_and}, {@code bool_or} and
     * {@code DISTINCT} sums and averages. The order-sensitive ones are
     * ordered by their own values, so that equal sort keys mean equal
     * values and the result does not depend on the order of the input.
     */
    @Test
    void stringListFirstAndBooleanAggregates() {
        SortKey nullsFirst = new SortKey(4, true, true);
        List<AggregateCall> calls = List.of(
                agg(AggregateFunction.STRING_AGG, 4, false, SortKey.asc(4)),
                agg(AggregateFunction.STRING_AGG, 4, true, SortKey.desc(4)).withSeparator(" | "),
                agg(AggregateFunction.STRING_AGG, 0, false, SortKey.desc(0)).withSeparator(""),
                agg(AggregateFunction.STRING_AGG, 3, false, SortKey.asc(3)),
                agg(AggregateFunction.STRING_AGG, 6, false, SortKey.asc(6)),
                agg(AggregateFunction.STRING_AGG, 5, false, SortKey.asc(5)),
                agg(AggregateFunction.STRING_AGG, 4, false).withSeparator(null),
                agg(AggregateFunction.LIST, 0, false, SortKey.asc(0)),
                agg(AggregateFunction.LIST, 4, true, nullsFirst),
                agg(AggregateFunction.LIST, 6, false, new SortKey(6, false, true)),
                agg(AggregateFunction.LIST, 3, false, SortKey.desc(3), SortKey.asc(4)),
                agg(AggregateFunction.FIRST, 4, false, nullsFirst),
                agg(AggregateFunction.FIRST, 0, false, SortKey.desc(0)),
                agg(AggregateFunction.LAST, 6, false, SortKey.asc(6)),
                agg(AggregateFunction.LAST, 4, false, nullsFirst),
                agg(AggregateFunction.ANY_VALUE, 4, false, nullsFirst),
                agg(AggregateFunction.ANY_VALUE, 3, false, SortKey.desc(3)),
                agg(AggregateFunction.BOOL_AND, 5, false),
                agg(AggregateFunction.BOOL_OR, 5, false),
                agg(AggregateFunction.BOOL_AND, 5, true),
                agg(AggregateFunction.SUM, 0, true),
                agg(AggregateFunction.SUM, 3, true),
                agg(AggregateFunction.AVG, 1, true),
                agg(AggregateFunction.AVG, 3, true),
                agg(AggregateFunction.COUNT, 2, true),
                agg(AggregateFunction.MIN, 2, true));
        differential.check(new PlanNode.Aggregate(t, List.of(7), calls), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(t, List.of(5), calls), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(t, List.of(), calls), AVG_ULPS);
        differential.check(new PlanNode.Aggregate(new PlanNode.Filter(t, lit(false)), List.of(), calls), AVG_ULPS);
        // Doubles in text form, without the zeros, whose two signs compare equal but print differently.
        differential.check(new PlanNode.Aggregate(new PlanNode.Filter(t, ne(c(2), lit(0.0))), List.of(5), List.of(
                agg(AggregateFunction.STRING_AGG, 2, false, SortKey.asc(2)),
                agg(AggregateFunction.LIST, 2, false, SortKey.desc(2)))));
        // Nested values and timestamps.
        differential.check(new PlanNode.Aggregate(u, List.of(), List.of(
                agg(AggregateFunction.LIST, 3, false, SortKey.asc(3)),
                agg(AggregateFunction.STRING_AGG, 3, false, SortKey.desc(3)),
                agg(AggregateFunction.FIRST, 2, false, SortKey.asc(2)))));
        differential.check(new PlanNode.Aggregate(n, List.of(), List.of(
                agg(AggregateFunction.LIST, 2, false, SortKey.asc(2)),
                agg(AggregateFunction.STRING_AGG, 1, false, SortKey.asc(1)))));
    }

    @Test
    void sortLimitUnion() {
        differential.check(new PlanNode.Sort(t, List.of(new SortKey(4, false, true), SortKey.asc(2), SortKey.desc(3))));
        differential.check(new PlanNode.Limit(sorted(t), 25, 10));
        differential.check(new PlanNode.Union(List.of(t, t)));
        differential.check(new PlanNode.Sort(new PlanNode.Union(List.of(
                new PlanNode.Project(t, List.of(new NamedExpression("a", c(0)))),
                new PlanNode.Project(u, List.of(new NamedExpression("a", col(U, 0)))))), List.of(SortKey.desc(0))));
    }

    @Test
    void valuesAndSetOperations() {
        Schema s = Schema.of(DataType.INTEGER, DataType.VARCHAR, DataType.DOUBLE);
        PlanNode left = new PlanNode.Project(t, List.of(new NamedExpression("i", c(0)), new NamedExpression("s", c(4)),
                new NamedExpression("d", c(2))));
        PlanNode right = new PlanNode.Values(s, List.of(new Object[] {1, "a", 0.0}, new Object[] {1, "a", -0.0},
                new Object[] {null, null, Double.NaN}, new Object[] {2, "b", 1.0}, new Object[] {1, "a", 0.0}));
        differential.check(right);
        differential.check(new PlanNode.Values(s, List.of()));
        PlanNode union = new PlanNode.Union(List.of(left, right, right));
        for (PlanNode.SetOperationKind kind : PlanNode.SetOperationKind.values()) {
            differential.check(new PlanNode.SetOperation(union, right, kind, false));
            differential.check(new PlanNode.SetOperation(union, right, kind, true));
            differential.check(new PlanNode.SetOperation(right, union, kind, true));
        }
        // DISTINCT is an aggregate without aggregates.
        differential.check(new PlanNode.Aggregate(union, List.of(0, 1, 2), List.of()));
    }

    @Test
    void safeCastsIlikeAndIsDistinct() {
        differential.check(new PlanNode.Project(t, List.of(
                new NamedExpression("a", tryCast(c(4), DataType.INTEGER)),
                new NamedExpression("b", tryCast(c(1), DataType.TINYINT)),
                new NamedExpression("c", tryCast(c(2), DataType.decimal(5, 2))),
                new NamedExpression("d", tryCast(c(4), DataType.DATE)),
                new NamedExpression("e", ilike(c(4), lit("%A%"))),
                new NamedExpression("f", isDistinct(c(0), c(7))),
                new NamedExpression("g", isNotDistinct(c(2), lit(0.0))),
                new NamedExpression("h", isDistinct(c(4), nullOf(DataType.VARCHAR))))));
    }

    @Test
    void nestedTypesPassThrough() {
        differential.check(new PlanNode.Filter(n, gt(col(N, 0), lit(0))));
        differential.check(new PlanNode.Sort(n, List.of(SortKey.asc(1), SortKey.desc(2), SortKey.asc(3), SortKey.asc(0))));
        differential.check(new PlanNode.Join(n, n, List.of(0), List.of(0), JoinType.LEFT));
        differential.check(new PlanNode.Aggregate(n, List.of(1), List.of(
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"))));
    }

    /**
     * Unnest of a column with NULL and empty lists, of lists of lists and of
     * structs, twice in a row, of a constant, and below a sort and an
     * aggregation; the rendered SQL of each plan, bound by FyrQL, gives the
     * same rows again.
     */
    @Test
    void unnest() {
        Expression li = col(N, 2);
        PlanNode lists = new PlanNode.Unnest(n, call("list_value", li, nullOf(li.type()), li), "x");
        Schema one = new Schema(List.of());
        List<PlanNode> plans = List.of(
                new PlanNode.Unnest(n, li, "x"),
                new PlanNode.Unnest(new PlanNode.Filter(n, gt(col(N, 0), lit(0))), li, "x"),
                lists,
                new PlanNode.Unnest(lists, new Expression.Column(4, li.type()), "y"),
                new PlanNode.Unnest(n, call("list_value", col(N, 1), col(N, 1)), "x"),
                new PlanNode.Unnest(new PlanNode.Values(one, List.<Object[]>of(new Object[0])),
                        new Expression.Literal(DataType.listOf(DataType.INTEGER), Arrays.asList(1, null, 3)), "x"),
                new PlanNode.Sort(new PlanNode.Unnest(n, li, "x"), List.of(SortKey.desc(4), SortKey.asc(0))),
                new PlanNode.Aggregate(new PlanNode.Unnest(n, li, "x"), List.of(0), List.of(
                        new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                        new AggregateCall(AggregateFunction.SUM, 4, false, "total"))));
        SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE, TableRef::toString);
        for (PlanNode plan : plans) {
            List<Object[]> rows = differential.check(plan);
            String sql = renderer.render(plan);
            ResultComparison.Outcome again = ResultComparison.compare(plan.schema(), rows,
                    differential.engine().rows(sql), Plans.ordered(plan));
            assertTrue(again.same(), () -> sql + "\n" + again.report());
        }
    }

    private static FrameBound bound(BoundKind kind, Expression offset) {
        return FrameBound.of(kind, (Expression.Literal) offset);
    }

    private static Frame frame(FrameUnit unit, FrameBound start, FrameBound end, FrameExclusion exclusion) {
        return new Frame(unit, start, end, exclusion);
    }

    /**
     * Windows over the edge values: every function, frames of rows, of
     * groups and of ranges of numbers, dates and timestamps, exclusions,
     * filters and {@code IGNORE NULLS}, and windows below a filter, an
     * aggregate and a sort. The order within a partition is total, by all
     * other columns, but for the windows whose calls give peers one result.
     * The rendered SQL of each plan, bound by FyrQL, gives the same rows
     * again.
     */
    @Test
    void windows() {
        List<NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < T.size(); i++) {
            columns.add(new NamedExpression(T.name(i), c(i)));
        }
        columns.add(new NamedExpression("two", lit(2L)));
        columns.add(new NamedExpression("three", lit(3)));
        PlanNode counted = new PlanNode.Project(t, columns);
        List<SortKey> total = List.of(SortKey.asc(0), new SortKey(1, false, true), SortKey.asc(2), SortKey.desc(3),
                SortKey.asc(4), SortKey.asc(6), new SortKey(7, true, true));
        FrameBound current = FrameBound.of(BoundKind.CURRENT_ROW);
        FrameBound first = FrameBound.of(BoundKind.UNBOUNDED_PRECEDING);
        FrameBound last = FrameBound.of(BoundKind.UNBOUNDED_FOLLOWING);
        Frame around = frame(FrameUnit.ROWS, bound(BoundKind.PRECEDING, lit(1L)), bound(BoundKind.FOLLOWING, lit(1L)),
                FrameExclusion.NO_OTHERS);
        Frame whole = frame(FrameUnit.ROWS, first, last, FrameExclusion.NO_OTHERS);
        PlanNode functions = new PlanNode.Window(counted, List.of(5), total, List.of(
                WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn"),
                WindowCall.of(WindowFunction.RANK, List.of(), "rk"),
                WindowCall.of(WindowFunction.DENSE_RANK, List.of(), "dr"),
                WindowCall.of(WindowFunction.PERCENT_RANK, List.of(), "pr"),
                WindowCall.of(WindowFunction.CUME_DIST, List.of(), "cd"),
                WindowCall.of(WindowFunction.NTILE, List.of(9), "nt"),
                WindowCall.of(WindowFunction.LAG, List.of(0), "lag"),
                WindowCall.of(WindowFunction.LEAD, List.of(4, 8, 4), "lead"),
                new WindowCall(WindowFunction.LAG, null, List.of(2, 9), true, -1, Frame.DEFAULT, "lag_ignore"),
                new WindowCall(WindowFunction.FIRST_VALUE, null, List.of(3), false, -1, around, "first"),
                new WindowCall(WindowFunction.LAST_VALUE, null, List.of(4), true, -1, Frame.DEFAULT, "last"),
                new WindowCall(WindowFunction.NTH_VALUE, null, List.of(6, 8), false, -1,
                        frame(FrameUnit.GROUPS, first, current, FrameExclusion.NO_OTHERS), "nth")));
        PlanNode aggregates = new PlanNode.Window(counted, List.of(), total, List.of(
                WindowCall.of(agg(AggregateFunction.SUM, 0, false), frame(FrameUnit.ROWS,
                        bound(BoundKind.PRECEDING, lit(2L)), current, FrameExclusion.NO_OTHERS)),
                WindowCall.of(agg(AggregateFunction.AVG, 3, false), frame(FrameUnit.ROWS, current, last,
                        FrameExclusion.CURRENT_ROW)),
                new WindowCall(WindowFunction.AGGREGATE, agg(AggregateFunction.COUNT, 4, false), List.of(), false, 5,
                        Frame.DEFAULT, "count_if"),
                WindowCall.of(agg(AggregateFunction.MIN, 4, false), frame(FrameUnit.GROUPS,
                        bound(BoundKind.PRECEDING, lit(1L)), bound(BoundKind.FOLLOWING, lit(1L)), FrameExclusion.TIES)),
                WindowCall.of(agg(AggregateFunction.MAX, 6, false), around),
                WindowCall.of(agg(AggregateFunction.STRING_AGG, 4, false, SortKey.asc(4)), around),
                WindowCall.of(agg(AggregateFunction.LIST, 7, true, SortKey.desc(7)), whole),
                WindowCall.of(agg(AggregateFunction.BOOL_OR, 5, false), around),
                WindowCall.of(agg(AggregateFunction.STDDEV_SAMP, 0, false), around),
                WindowCall.of(agg(AggregateFunction.VAR_POP, 3, false), frame(FrameUnit.ROWS,
                        bound(BoundKind.PRECEDING, lit(2L)), current, FrameExclusion.NO_OTHERS)),
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"), frame(FrameUnit.ROWS,
                        bound(BoundKind.FOLLOWING, lit(1L)), bound(BoundKind.FOLLOWING, lit(3L)),
                        FrameExclusion.NO_OTHERS)),
                WindowCall.of(agg(AggregateFunction.SUM, 1, true), whole)));
        // Peers of one key: rankings, and aggregates over frames that take whole groups of peers.
        PlanNode peers = new PlanNode.Window(t, List.of(5), List.of(SortKey.desc(0)), List.of(
                WindowCall.of(WindowFunction.RANK, List.of(), "rk"),
                WindowCall.of(WindowFunction.CUME_DIST, List.of(), "cd"),
                WindowCall.of(agg(AggregateFunction.SUM, 1, false), frame(FrameUnit.RANGE,
                        bound(BoundKind.PRECEDING, lit(2)), current, FrameExclusion.NO_OTHERS)),
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"), frame(FrameUnit.RANGE,
                        current, bound(BoundKind.FOLLOWING, lit(5)), FrameExclusion.GROUP)),
                WindowCall.of(agg(AggregateFunction.MIN, 3, false), frame(FrameUnit.GROUPS,
                        bound(BoundKind.PRECEDING, lit(1L)), bound(BoundKind.FOLLOWING, lit(2L)), FrameExclusion.TIES)),
                WindowCall.of(agg(AggregateFunction.MAX, 4, false), Frame.DEFAULT)));
        // Ranges of dates and timestamps, over values that do not leave the range of the types.
        PlanNode dates = new PlanNode.Window(new PlanNode.Filter(t, or(isNull(c(6)), between(c(6),
                lit(DataType.DATE, -25_567), lit(DataType.DATE, 47_482)))), List.of(), List.of(SortKey.desc(6)), List.of(
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 30, PlanNode.IntervalUnit.DAY), current, FrameExclusion.NO_OTHERS)),
                WindowCall.of(agg(AggregateFunction.MAX, 0, false), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 1, PlanNode.IntervalUnit.MONTH),
                        interval(BoundKind.FOLLOWING, 1, PlanNode.IntervalUnit.YEAR), FrameExclusion.NO_OTHERS)),
                WindowCall.of(agg(AggregateFunction.COUNT, 4, false), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 12, PlanNode.IntervalUnit.HOUR),
                        interval(BoundKind.FOLLOWING, 0, PlanNode.IntervalUnit.DAY), FrameExclusion.NO_OTHERS))));
        PlanNode times = new PlanNode.Window(new PlanNode.Filter(u, or(isNull(col(U, 3)), between(col(U, 3),
                lit(DataType.TIMESTAMP, -2_208_988_800_000_000L), lit(DataType.TIMESTAMP, 4_102_444_800_000_000L)))),
                List.of(0), List.of(SortKey.asc(3)), List.of(
                WindowCall.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 1, PlanNode.IntervalUnit.DAY),
                        interval(BoundKind.FOLLOWING, 1, PlanNode.IntervalUnit.HOUR), FrameExclusion.CURRENT_ROW)),
                WindowCall.of(new AggregateCall(AggregateFunction.SUM, 0, false, "sum_k"), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 30, PlanNode.IntervalUnit.MINUTE), current, FrameExclusion.NO_OTHERS)),
                WindowCall.of(new AggregateCall(AggregateFunction.MIN, 1, false, "min_v"), frame(FrameUnit.RANGE,
                        interval(BoundKind.PRECEDING, 2, PlanNode.IntervalUnit.MONTH), current, FrameExclusion.NO_OTHERS))));
        PlanNode ranked = new PlanNode.Window(t, List.of(5), total, List.of(
                WindowCall.of(WindowFunction.ROW_NUMBER, List.of(), "rn")));
        PlanNode ranks = new PlanNode.Window(t, List.of(), List.of(SortKey.asc(7)), List.of(
                WindowCall.of(WindowFunction.DENSE_RANK, List.of(), "dr")));
        List<PlanNode> plans = List.of(functions, aggregates, peers, dates, times,
                new PlanNode.Filter(ranked, and(lt(col(8, DataType.BIGINT), lit(4L)), eq(c(5), lit(true)))),
                new PlanNode.Aggregate(ranks, List.of(8), List.of(
                        new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                        new AggregateCall(AggregateFunction.SUM, 0, false, "s"))),
                new PlanNode.Limit(sorted(functions), 20, 5));
        SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE, TableRef::toString);
        for (PlanNode plan : plans) {
            List<Object[]> rows = differential.check(plan, AVG_ULPS);
            assertFalse(rows.isEmpty(), () -> Plans.print(plan));
            String sql = renderer.render(plan);
            ResultComparison.Outcome again = ResultComparison.compare(plan.schema(), rows,
                    differential.engine().rows(sql), Plans.ordered(plan));
            assertTrue(again.same(), () -> sql + "\n" + again.report());
        }
    }

    /** A bound of a count of a unit, over a date or a timestamp. */
    private static FrameBound interval(BoundKind kind, long count, PlanNode.IntervalUnit unit) {
        return new FrameBound(kind, (Expression.Literal) lit(count), unit);
    }

    static List<List<Object>> asLists(List<Object[]> rows) {
        List<List<Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(Arrays.asList(r));
        }
        return result;
    }

    static Map<String, DataType> noOverrides() {
        return Map.of();
    }
}
