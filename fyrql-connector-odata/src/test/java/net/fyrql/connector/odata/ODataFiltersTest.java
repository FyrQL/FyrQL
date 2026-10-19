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

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@code $filter} and {@code $orderby} of the filters and orders of
 * scans: cases with the conditions they give in both versions, and random
 * filters over random rows, whose hints must hold wherever FyrQL selects a
 * row, also for a service that compares strings without case or ignores
 * spaces at their end, and whose exact conditions must select exactly the
 * rows of FyrQL, both as the fake service evaluates them.
 */
class ODataFiltersTest {

    /** The columns of the cases, by position: their types in FyrQL and in OData. */
    private static final Schema CASES = new Schema(List.of(
            new Schema.Field("I", DataType.INTEGER, true),
            new Schema.Field("N", DataType.INTEGER, false),
            new Schema.Field("L", DataType.BIGINT, true),
            new Schema.Field("T", DataType.TINYINT, true),
            new Schema.Field("D", DataType.decimal(4, 2), true),
            new Schema.Field("B", DataType.BOOLEAN, true),
            new Schema.Field("U", DataType.UUID, true),
            new Schema.Field("S", DataType.VARCHAR, true),
            new Schema.Field("Day", DataType.DATE, true),
            new Schema.Field("Ts", DataType.TIMESTAMP, true),
            new Schema.Field("Exact", DataType.TIMESTAMP, false),
            new Schema.Field("Address/City", DataType.VARCHAR, true),
            new Schema.Field("F", DataType.DOUBLE, true),
            new Schema.Field("Hidden", DataType.INTEGER, true),
            new Schema.Field("Tso", DataType.TIMESTAMP, true)));

    private static final List<String> EDM = List.of("Edm.Int32", "Edm.Int32", "Edm.Int64", "Edm.SByte",
            "Edm.Decimal", "Edm.Boolean", "Edm.Guid", "Edm.String", "Edm.Date", "Edm.DateTimeOffset",
            "Edm.DateTimeOffset", "Edm.String", "Edm.Double", "Edm.Int32", "Edm.DateTimeOffset");

    private static final UUID GUID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    private static ODataFilters filters(ODataVersion version) {
        List<ODataFilters.Target> targets = new ArrayList<>();
        for (int c = 0; c < CASES.size(); c++) {
            String edm = EDM.get(c);
            if (version == ODataVersion.V2 && c == 9) {
                edm = "Edm.DateTime";
            }
            String name = CASES.name(c);
            targets.add(new ODataFilters.Target(name, edm, name.equals("Exact") ? "3" : null, CASES.type(c),
                    CASES.nullable(c), !name.equals("Hidden"), true, !name.equals("N")));
        }
        return new ODataFilters(version, targets);
    }

    private static final ODataFilters V2 = filters(ODataVersion.V2);
    private static final ODataFilters V4 = filters(ODataVersion.V4);

    private static Expression.Column col(String name) {
        int c = CASES.indexOf(name);
        return new Expression.Column(c, CASES.type(c));
    }

    private static Expression.Literal lit(DataType type, Object value) {
        return new Expression.Literal(type, value);
    }

    private static Expression.Literal i(int v) {
        return lit(DataType.INTEGER, v);
    }

    private static Expression.Literal dec(String v) {
        BigDecimal d = new BigDecimal(v);
        return lit(DataType.decimal(Math.max(d.precision(), d.scale() + 1), d.scale()), d);
    }

    private static Expression.Literal ts(String v) {
        return lit(DataType.TIMESTAMP, Conversions.parseTimestamp(v));
    }

    private static Expression.Literal date(String v) {
        return lit(DataType.DATE, Conversions.parseDate(v));
    }

    private static Expression.Literal str(String v) {
        return lit(DataType.VARCHAR, v);
    }

    private static Expression cmp(ComparisonOp op, Expression a, Expression b) {
        return new Expression.Comparison(op, a, b);
    }

    private static Expression not(Expression e) {
        return new Expression.Not(e);
    }

    @Test
    void comparisonsOfNumbers() {
        assertEquals("I gt 5", V4.hint(cmp(ComparisonOp.GT, col("I"), i(5))));
        assertEquals("I gt 5", V4.hint(cmp(ComparisonOp.LT, i(5), col("I"))));
        assertEquals("I le 5", V4.hint(not(cmp(ComparisonOp.GT, col("I"), i(5)))));
        assertEquals("I gt 2", V4.exact(cmp(ComparisonOp.GT, col("I"), dec("2.5"))));
        assertEquals("I ge 3", V4.exact(cmp(ComparisonOp.GE, col("I"), dec("2.5"))));
        assertEquals("I lt 3", V4.exact(cmp(ComparisonOp.LT, col("I"), dec("2.5"))));
        assertEquals("I le 2", V4.exact(cmp(ComparisonOp.LE, col("I"), dec("2.5"))));
        assertNull(V4.hint(cmp(ComparisonOp.EQ, col("I"), dec("2.5"))));
        assertEquals("I eq 2", V4.exact(cmp(ComparisonOp.EQ, col("I"), dec("2.0"))));
        assertEquals("I ne null", V4.exact(cmp(ComparisonOp.NE, col("I"), dec("2.5"))));
        assertEquals("I ne 5", V4.hint(cmp(ComparisonOp.NE, col("I"), i(5))));
        // In OData null differs from 5, in FyrQL the comparison is unknown.
        assertEquals("I ne 5 and I ne null", V4.exact(cmp(ComparisonOp.NE, col("I"), i(5))));
        assertEquals("L eq 5L", V2.exact(cmp(ComparisonOp.EQ, col("L"), i(5))));
        assertEquals("L eq -9223372036854775808", V4.exact(cmp(ComparisonOp.EQ, col("L"),
                lit(DataType.BIGINT, Long.MIN_VALUE))));
        assertEquals("D gt 1.50M", V2.exact(cmp(ComparisonOp.GT, col("D"), dec("1.5"))));
        assertEquals("D gt 1.50", V4.exact(cmp(ComparisonOp.GT, col("D"), dec("1.5"))));
        assertEquals("D gt 1.55", V4.exact(cmp(ComparisonOp.GT, col("D"), dec("1.555"))));
        assertEquals("D ge 1.56", V4.exact(cmp(ComparisonOp.GE, col("D"), dec("1.555"))));
        assertEquals("D lt 10", V4.exact(cmp(ComparisonOp.LT, col("D"), i(10))).replace(".00", ""));
        // The column holds no such value, and the service might refuse the literal.
        assertNull(V4.hint(cmp(ComparisonOp.LT, col("T"), i(1000))));
        assertNull(V4.hint(cmp(ComparisonOp.LT, col("D"), i(1000))));
        assertEquals("T ge -128", V4.exact(cmp(ComparisonOp.GE, col("T"), i(-128))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, col("F"), lit(DataType.DOUBLE, 0.5))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, col("I"), col("L"))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, col("Hidden"), i(1))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, col("I"), lit(DataType.INTEGER, null))));
        // A cast to a type that holds every value of the column compares the values themselves.
        assertEquals("D ge 5.00 and D le 30.00", V4.exact(new Expression.Between(new Expression.Cast(col("D"),
                DataType.decimal(12, 2), false), i(5), i(30))));
        assertEquals("T lt 100", V4.exact(cmp(ComparisonOp.LT, new Expression.Cast(col("T"), DataType.BIGINT,
                false), lit(DataType.BIGINT, 100L))));
        assertNull(V4.hint(cmp(ComparisonOp.LT, new Expression.Cast(col("T"), DataType.BIGINT, false),
                lit(DataType.BIGINT, 1000L))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, new Expression.Cast(col("D"), DataType.decimal(4, 1), false),
                dec("1.5"))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, new Expression.Cast(col("L"), DataType.INTEGER, false), i(5))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, new Expression.Cast(col("I"), DataType.DOUBLE, false),
                lit(DataType.DOUBLE, 5.0))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, new Expression.Cast(col("S"), DataType.INTEGER, false), i(5))));
    }

    @Test
    void otherFormsOfConditions() {
        assertEquals("I eq null", V4.exact(new Expression.IsNull(col("I"), false)));
        assertEquals("I ne null", V4.exact(not(new Expression.IsNull(col("I"), false))));
        assertEquals("S ne null", V2.exact(new Expression.IsNull(col("S"), true)));
        assertEquals("F eq null", V4.exact(new Expression.IsNull(col("F"), false)));
        assertNull(V4.exact(new Expression.IsNull(col("Hidden"), false)));
        assertEquals("I ge 1 and I le 10", V4.exact(new Expression.Between(col("I"), i(1), i(10))));
        assertEquals("(I lt 1 or I gt 10)", V4.exact(not(new Expression.Between(col("I"), i(1), i(10)))));
        assertEquals("I ge 1", V4.hint(new Expression.Between(col("I"), i(1), lit(DataType.INTEGER, null))));
        assertNull(V4.exact(new Expression.Between(col("I"), i(1), lit(DataType.INTEGER, null))));
        assertEquals("(I eq 1 or I eq 2)", V4.exact(new Expression.In(col("I"), List.of(i(1), i(2),
                lit(DataType.INTEGER, null)))));
        assertEquals("I eq 1", V4.exact(new Expression.In(col("I"), List.of(i(1)))));
        assertEquals("I ne 1 and I ne 2", V4.hint(not(new Expression.In(col("I"), List.of(i(1), i(2))))));
        assertEquals("I ne 1 and I ne null and I ne 2 and I ne null", V4.exact(not(new Expression.In(col("I"),
                List.of(i(1), i(2))))));
        // A NOT IN with NULL is never TRUE.
        assertEquals("I ne 1", V4.hint(not(new Expression.In(col("I"), List.of(i(1), lit(DataType.INTEGER,
                null))))));
        assertNull(V4.exact(not(new Expression.In(col("I"), List.of(i(1), lit(DataType.INTEGER, null))))));
        List<Expression> many = new ArrayList<>();
        for (int k = 0; k <= ODataFilters.MAX_IN; k++) {
            many.add(i(k));
        }
        assertNull(V4.hint(new Expression.In(col("I"), many)));
        assertEquals("(I ne 5 or I eq null)", V4.hint(new Expression.IsDistinct(col("I"), i(5), false)));
        assertEquals("I eq 5", V4.exact(new Expression.IsDistinct(i(5), col("I"), true)));
        assertEquals("I eq null", V4.exact(new Expression.IsDistinct(col("I"), lit(DataType.INTEGER, null), true)));
        assertEquals("B eq true", V4.exact(col("B")));
        assertEquals("B eq false", V4.exact(not(col("B"))));
        assertEquals("B eq false", V2.exact(cmp(ComparisonOp.NE, col("B"), lit(DataType.BOOLEAN, true))));
        assertNull(V4.exact(cmp(ComparisonOp.LT, col("B"), lit(DataType.BOOLEAN, true))));
        assertEquals("U eq guid'01234567-89ab-cdef-0123-456789abcdef'", V2.exact(cmp(ComparisonOp.EQ, col("U"),
                lit(DataType.UUID, GUID))));
        assertEquals("U ne 01234567-89ab-cdef-0123-456789abcdef and U ne null", V4.exact(cmp(ComparisonOp.NE,
                col("U"), lit(DataType.UUID, GUID))));
        assertEquals("U ne 01234567-89ab-cdef-0123-456789abcdef", V4.hint(cmp(ComparisonOp.NE, col("U"),
                lit(DataType.UUID, GUID))));
    }

    @Test
    void stringsAreOnlyHints() {
        Expression eq = cmp(ComparisonOp.EQ, col("S"), str("O'Neil"));
        assertEquals("S eq 'O''Neil'", V4.hint(eq));
        assertNull(V4.exact(eq));
        assertNull(V4.hint(cmp(ComparisonOp.NE, col("S"), str("x"))));
        assertNull(V4.hint(cmp(ComparisonOp.GT, col("S"), str("x"))));
        assertEquals("Address/City eq 'Bonn'", V4.hint(cmp(ComparisonOp.EQ, col("Address/City"), str("Bonn"))));
        assertEquals("(S eq 'a' or S eq 'b')", V4.hint(new Expression.In(col("S"), List.of(str("a"), str("b")))));
        assertEquals("startswith(S,'ab')", V4.hint(new Expression.Like(col("S"), str("ab%"))));
        assertEquals("startswith(S,'ab') eq true", V2.hint(new Expression.Like(col("S"), str("ab%"))));
        assertEquals("endswith(S,'ab')", V4.hint(new Expression.Like(col("S"), str("%ab"))));
        assertEquals("contains(S,'a') and contains(S,'b')", V4.hint(new Expression.Like(col("S"), str("%a%b%"))));
        assertEquals("substringof('a',S) eq true", V2.hint(new Expression.Like(col("S"), str("%a%"))));
        assertEquals("startswith(S,'a') and contains(S,'b') and endswith(S,'c')", V4.hint(new Expression.Like(
                col("S"), str("a_b%c"))));
        assertEquals("S eq 'abc'", V4.hint(new Expression.Like(col("S"), str("abc"))));
        assertNull(V4.hint(new Expression.Like(col("S"), str("%_%"))));
        assertNull(V4.exact(new Expression.Like(col("S"), str("ab%"))));
        assertNull(V4.hint(not(new Expression.Like(col("S"), str("ab%")))));
        assertNull(V4.hint(new Expression.Like(col("S"), str("ab%"), true)));
    }

    @Test
    void datesAndTimestamps() {
        assertEquals("Day gt 2000-01-01", V4.exact(cmp(ComparisonOp.GT, col("Day"), date("2000-01-01"))));
        assertEquals("Day ge 2000-06-02", V4.exact(cmp(ComparisonOp.GE, col("Day"), ts("2000-06-01 12:00:00"))));
        assertEquals("Day le 2000-06-01", V4.exact(cmp(ComparisonOp.LE, col("Day"), ts("2000-06-01 12:00:00"))));
        assertNull(V4.exact(cmp(ComparisonOp.EQ, col("Day"), ts("2000-06-01 12:00:00"))));
        // Version 4 sends microseconds, of a service that may hold finer values.
        assertEquals("Ts lt 1999-12-31T23:59:59.5Z", V4.exact(cmp(ComparisonOp.LT, col("Ts"),
                ts("1999-12-31 23:59:59.5"))));
        assertEquals("Ts lt 1999-12-31T23:59:59.500001Z", V4.exact(cmp(ComparisonOp.LE, col("Ts"),
                ts("1999-12-31 23:59:59.5"))));
        assertEquals("Ts ge 1999-12-31T23:59:59.500001Z", V4.exact(cmp(ComparisonOp.GT, col("Ts"),
                ts("1999-12-31 23:59:59.5"))));
        assertEquals("Ts ge 2000-01-01T00:00:00Z and Ts lt 2000-01-01T00:00:00.000001Z", V4.exact(
                cmp(ComparisonOp.EQ, col("Ts"), date("2000-01-01"))));
        assertEquals("(Ts lt 2000-01-01T00:00:00Z or Ts ge 2000-01-01T00:00:00.000001Z)", V4.exact(
                cmp(ComparisonOp.NE, col("Ts"), date("2000-01-01"))));
        // A facet Precision of 3 says that there is nothing finer.
        assertEquals("Exact eq 1999-12-31T23:59:59.5Z", V4.exact(cmp(ComparisonOp.EQ, col("Exact"),
                ts("1999-12-31 23:59:59.5"))));
        assertEquals("Exact le 1999-12-31T23:59:59.5Z", V4.exact(cmp(ComparisonOp.LE, col("Exact"),
                ts("1999-12-31 23:59:59.5"))));
        // Version 2 sends milliseconds.
        assertEquals("Ts lt datetime'2000-01-01T00:00:00.001'", V2.exact(cmp(ComparisonOp.LE, col("Ts"),
                ts("2000-01-01 00:00:00"))));
        assertEquals("Ts ge datetime'2000-01-01T00:00:00.001'", V2.exact(cmp(ComparisonOp.GT, col("Ts"),
                ts("2000-01-01 00:00:00.0005"))));
        assertEquals("Ts ge datetime'2000-01-01T00:00:00.001'", V2.exact(cmp(ComparisonOp.GE, col("Ts"),
                ts("2000-01-01 00:00:00.0005"))));
        assertNull(V2.hint(cmp(ComparisonOp.EQ, col("Ts"), ts("2000-01-01 00:00:00.0005"))));
        assertEquals("Ts ne null", V2.exact(cmp(ComparisonOp.NE, col("Ts"), ts("2000-01-01 00:00:00.0005"))));
        assertEquals("Exact eq datetimeoffset'2000-01-01T00:00:00.001Z'", V2.exact(cmp(ComparisonOp.EQ,
                col("Exact"), ts("2000-01-01 00:00:00.001"))));
        assertEquals("Tso lt datetimeoffset'0001-01-01T00:00:00.001Z'", V2.exact(cmp(ComparisonOp.LE, col("Tso"),
                ts("0001-01-01 00:00:00"))));
        assertNull(V2.exact(cmp(ComparisonOp.GT, col("Tso"), ts("9999-12-31 23:59:59.9995"))));
    }

    @Test
    void conjunctionsDisjunctionsAndTheFilterOfAScan() {
        Expression known = cmp(ComparisonOp.GT, col("I"), i(5));
        Expression unknown = cmp(ComparisonOp.GT, col("F"), lit(DataType.DOUBLE, 0.5));
        assertEquals("I gt 5", V4.hint(new Expression.And(known, unknown)));
        assertNull(V4.exact(new Expression.And(known, unknown)));
        assertNull(V4.hint(new Expression.Or(known, unknown)));
        assertEquals("(I le 5 or F eq null)", V4.hint(not(new Expression.And(known, new Expression.IsNull(col("F"),
                true)))));
        assertEquals("I le 5", V4.hint(not(new Expression.Or(known, unknown))));
        assertEquals("(I gt 5 or I eq null) and L eq 1", V4.exact(new Expression.And(new Expression.Or(known,
                new Expression.IsNull(col("I"), false)), cmp(ComparisonOp.EQ, col("L"), i(1)))));
        Expression string = cmp(ComparisonOp.EQ, col("S"), str("x"));
        assertEquals("I gt 5 and S eq 'x'", V4.filter(List.of(known, unknown, string), List.of(known)));
        assertNull(V4.filter(List.of(unknown), List.of()));
        assertThrows(IllegalStateException.class, () -> V4.filter(List.of(string), List.of(string)));
    }

    @Test
    void orders() {
        int n = CASES.indexOf("N");
        int nullable = CASES.indexOf("I");
        assertEquals("N asc", V4.orderBy(List.of(PlanNode.SortKey.asc(n))));
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(n, false, false))), "N sorts only ascending");
        assertNull(V4.orderBy(List.of(PlanNode.SortKey.asc(nullable))));
        assertEquals("I asc", V4.orderBy(List.of(new PlanNode.SortKey(nullable, true, true))));
        assertEquals("I desc", V4.orderBy(List.of(new PlanNode.SortKey(nullable, false, false))));
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(nullable, false, true))));
        assertEquals("D desc,Day desc,Exact asc", V4.orderBy(List.of(new PlanNode.SortKey(CASES.indexOf("D"),
                false, false), new PlanNode.SortKey(CASES.indexOf("Day"), false, false),
                PlanNode.SortKey.asc(CASES.indexOf("Exact")))));
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(CASES.indexOf("S"), false, false))));
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(CASES.indexOf("B"), false, false))));
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(CASES.indexOf("F"), false, false))));
        int fine = CASES.indexOf("Ts");
        assertEquals("N asc,Ts desc", V4.orderBy(List.of(PlanNode.SortKey.asc(n), new PlanNode.SortKey(fine,
                false, false))));
        // The service breaks ties of the timestamps of FyrQL by the finer values it holds.
        assertNull(V4.orderBy(List.of(new PlanNode.SortKey(fine, false, false), PlanNode.SortKey.asc(n))));
        assertNull(V4.orderBy(List.of()));
    }

    /** The columns of the random rows, of every type that has a counterpart, with their types in OData. */
    private static final Schema RANDOM = new Schema(List.of(
            new Schema.Field("id", DataType.INTEGER, false),
            new Schema.Field("b", DataType.BOOLEAN, true),
            new Schema.Field("t", DataType.TINYINT, true),
            new Schema.Field("s", DataType.SMALLINT, true),
            new Schema.Field("by", DataType.SMALLINT, true),
            new Schema.Field("i", DataType.INTEGER, true),
            new Schema.Field("l", DataType.BIGINT, true),
            new Schema.Field("d", DataType.decimal(9, 2), true),
            new Schema.Field("day", DataType.DATE, true),
            new Schema.Field("ts", DataType.TIMESTAMP, true),
            new Schema.Field("tso", DataType.TIMESTAMP, true),
            new Schema.Field("v", DataType.VARCHAR, true),
            new Schema.Field("u", DataType.UUID, true),
            new Schema.Field("f", DataType.DOUBLE, true)));

    private static String edm(int column, ODataVersion version) {
        return switch (RANDOM.name(column)) {
            case "id", "i" -> "Edm.Int32";
            case "b" -> "Edm.Boolean";
            case "t" -> "Edm.SByte";
            case "s" -> "Edm.Int16";
            case "by" -> "Edm.Byte";
            case "l" -> "Edm.Int64";
            case "d" -> "Edm.Decimal";
            case "day" -> version == ODataVersion.V2 ? "Edm.DateTime" : "Edm.Date";
            case "ts" -> version == ODataVersion.V2 ? "Edm.DateTime" : "Edm.DateTimeOffset";
            case "tso" -> "Edm.DateTimeOffset";
            case "v" -> "Edm.String";
            case "u" -> "Edm.Guid";
            default -> "Edm.Double";
        };
    }

    @Test
    void hintsHoldWhereverFyrqlSelectsARowAndExactConditionsExactlyThere() {
        List<String> failures = new ArrayList<>();
        int hints = 0;
        int exact = 0;
        int all = 0;
        for (ODataVersion version : ODataVersion.values()) {
            for (long seed : List.of(20261018L, 7L, 8L)) {
                List<Object[]> service = new DataGenerator(seed, 0.1, 0.1).generate(RANDOM, 300);
                for (int r = 0; r < service.size(); r++) {
                    Object[] row = service.get(r);
                    row[0] = r;
                    if (row[4] != null) {
                        row[4] = (short) ((Short) row[4] & 0xFF);
                    }
                    // A date of version 2 is a timestamp at midnight.
                    if (version == ODataVersion.V2) {
                        row[8] = null;
                    }
                    // Strings with spaces at the end and in other cases, for the quirks of services.
                    if (row[11] instanceof String v && r % 7 == 0) {
                        row[11] = r % 2 == 0 ? v + " " : v.toUpperCase(Locale.ROOT);
                    }
                }
                // FyrQL reads the timestamps of version 2 to the millisecond.
                List<Object[]> read = new ArrayList<>();
                for (Object[] row : service) {
                    Object[] copy = row.clone();
                    if (version == ODataVersion.V2) {
                        for (int c : new int[] {9, 10}) {
                            if (copy[c] != null) {
                                copy[c] = Math.floorDiv((Long) copy[c], 1000L) * 1000L;
                            }
                        }
                    }
                    read.add(copy);
                }
                Batch batch = Batch.ofRows(RANDOM, read);
                List<ODataFilters.Target> targets = new ArrayList<>();
                for (int c = 0; c < RANDOM.size(); c++) {
                    DataType type = RANDOM.type(c);
                    if (version == ODataVersion.V2 && c == 8) {
                        // Without Edm.Date, the column is no column of version 2 here.
                        targets.add(new ODataFilters.Target(RANDOM.name(c), null, null, type, true, false, false,
                                false));
                        continue;
                    }
                    targets.add(new ODataFilters.Target(RANDOM.name(c), edm(c, version), null, type,
                            RANDOM.nullable(c), true, true, true));
                }
                ODataFilters filters = new ODataFilters(version, targets);
                FakeFilter.Resolver resolver = path -> {
                    int c = RANDOM.indexOf(path);
                    return new FakeFilter.Column(RANDOM.type(c), edm(c, version), row -> row[c]);
                };
                SplittableRandom random = new SplittableRandom(seed);
                for (int k = 0; k < 400; k++) {
                    Expression e = condition(random, service, version, 0);
                    all++;
                    Set<Integer> selected = new HashSet<>();
                    for (int r : Evaluator.select(e, batch)) {
                        selected.add(r);
                    }
                    String hint = filters.hint(e);
                    if (hint != null) {
                        hints++;
                        for (FakeFilter.Quirks quirks : List.of(FakeFilter.Quirks.NONE, new FakeFilter.Quirks(true,
                                false), new FakeFilter.Quirks(false, true), new FakeFilter.Quirks(true, true))) {
                            Set<Integer> passing = passing(hint, version, resolver, quirks, service, failures, e);
                            if (passing != null && !passing.containsAll(selected)) {
                                Set<Integer> missing = new HashSet<>(selected);
                                missing.removeAll(passing);
                                failures.add(version + " " + e + "\n  hint " + hint + " with " + quirks
                                        + " misses the rows " + rows(missing, read));
                            }
                        }
                    }
                    String condition = filters.exact(e);
                    if (condition != null) {
                        exact++;
                        Set<Integer> passing = passing(condition, version, resolver, FakeFilter.Quirks.NONE,
                                service, failures, e);
                        if (passing != null && !passing.equals(selected)) {
                            Set<Integer> more = new HashSet<>(passing);
                            more.removeAll(selected);
                            Set<Integer> fewer = new HashSet<>(selected);
                            fewer.removeAll(passing);
                            failures.add(version + " " + e + "\n  exact " + condition + " selects also "
                                    + rows(more, read) + " and not " + rows(fewer, read));
                        }
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " filters differ:\n\n" + String.join("\n\n", failures.subList(0,
                    Math.min(20, failures.size()))));
        }
        assertTrue(hints > all / 2, hints + " hints of " + all + " filters");
        assertTrue(exact > all / 4, exact + " exact conditions of " + all + " filters");
    }

    private static Set<Integer> passing(String condition, ODataVersion version, FakeFilter.Resolver resolver,
            FakeFilter.Quirks quirks, List<Object[]> rows, List<String> failures, Expression e) {
        Predicate<Object[]> filter;
        try {
            filter = FakeFilter.parse(condition, version, resolver, quirks);
        } catch (IllegalArgumentException x) {
            failures.add(version + " " + e + "\n  " + condition + " is refused: " + x.getMessage());
            return null;
        }
        Set<Integer> passing = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            if (filter.test(rows.get(r))) {
                passing.add(r);
            }
        }
        return passing;
    }

    private static String rows(Set<Integer> indexes, List<Object[]> rows) {
        List<String> shown = new ArrayList<>();
        for (int r : indexes) {
            if (shown.size() == 3) {
                shown.add("...");
                break;
            }
            shown.add(Arrays.toString(rows.get(r)));
        }
        return shown.toString();
    }

    private static final ComparisonOp[] OPS = ComparisonOp.values();

    /** Returns a random condition over the random columns, with literals from the rows and near them. */
    private static Expression condition(SplittableRandom random, List<Object[]> rows, ODataVersion version,
            int depth) {
        int kind = random.nextInt(depth < 2 ? 12 : 9);
        if (kind >= 9) {
            Expression a = condition(random, rows, version, depth + 1);
            return switch (kind) {
                case 9 -> new Expression.And(a, condition(random, rows, version, depth + 1));
                case 10 -> new Expression.Or(a, condition(random, rows, version, depth + 1));
                default -> new Expression.Not(a);
            };
        }
        int c = 1 + random.nextInt(RANDOM.size() - 1);
        if (version == ODataVersion.V2 && c == 8) {
            c = 9;
        }
        DataType type = RANDOM.type(c);
        Expression.Column column = new Expression.Column(c, type);
        switch (kind) {
            case 0, 1, 2 -> {
                Expression.Literal l = literal(random, rows, c);
                ComparisonOp op = OPS[random.nextInt(OPS.length)];
                Expression value = widened(random, column);
                return random.nextBoolean() ? new Expression.Comparison(op, value, l)
                        : new Expression.Comparison(op, l, value);
            }
            case 3 -> {
                return new Expression.IsNull(column, random.nextBoolean());
            }
            case 4 -> {
                return new Expression.Between(widened(random, column), literal(random, rows, c),
                        literal(random, rows, c));
            }
            case 5 -> {
                List<Expression> list = new ArrayList<>();
                int n = 1 + random.nextInt(4);
                for (int k = 0; k < n; k++) {
                    list.add(literal(random, rows, c));
                }
                return new Expression.In(column, list);
            }
            case 6 -> {
                return new Expression.IsDistinct(column, literal(random, rows, c), random.nextBoolean());
            }
            case 7 -> {
                Expression.Column v = new Expression.Column(11, DataType.VARCHAR);
                Object value = rows.get(random.nextInt(rows.size()))[11];
                String s = value == null ? "a" : (String) value;
                int from = s.isEmpty() ? 0 : random.nextInt(s.length());
                int to = from + random.nextInt(s.length() - from + 1);
                String part = s.substring(from, to).replace("%", "").replace("_", "");
                String pattern = switch (random.nextInt(5)) {
                    case 0 -> part + "%";
                    case 1 -> "%" + part;
                    case 2 -> "%" + part + "%";
                    case 3 -> part.isEmpty() ? "%" : part.charAt(0) + "_%" + part.substring(1);
                    default -> part;
                };
                return new Expression.Like(v, new Expression.Literal(DataType.VARCHAR, pattern));
            }
            default -> {
                return new Expression.Column(1, DataType.BOOLEAN);
            }
        }
    }

    /** Returns a column of a number type, sometimes in a cast to a type that holds all its values. */
    private static Expression widened(SplittableRandom random, Expression.Column column) {
        DataType type = column.type();
        if (!(type.isIntegral() || type instanceof DataType.Decimal) || random.nextInt(4) != 0) {
            return column;
        }
        DataType.Decimal d = TypeRules.asDecimal(type);
        if (type.isIntegral() && random.nextBoolean()) {
            return new Expression.Cast(column, DataType.BIGINT, false);
        }
        int precision = d.precision() + 2;
        return precision > DataType.MAX_DECIMAL_PRECISION ? column
                : new Expression.Cast(column, DataType.decimal(precision, d.scale() + 1), false);
    }

    /**
     * Returns a literal for a column: a value of a random row, often null
     * free, or a value next to it, for numbers also between two of its
     * values, for timestamps also one of another unit.
     */
    private static Expression.Literal literal(SplittableRandom random, List<Object[]> rows, int c) {
        DataType type = RANDOM.type(c);
        Object v = null;
        for (int tries = 0; tries < 3 && v == null; tries++) {
            v = rows.get(random.nextInt(rows.size()))[c];
        }
        if (v == null || random.nextInt(20) == 0) {
            return new Expression.Literal(type, null);
        }
        int shift = random.nextInt(3) - 1;
        try {
            return near(random, type, v, shift);
        } catch (IllegalArgumentException e) {
            // A value next to an edge value may be out of the range of its type.
            return new Expression.Literal(type, v);
        }
    }

    private static Expression.Literal near(SplittableRandom random, DataType type, Object v, int shift) {
        return switch (v) {
            case Integer i when type == DataType.DATE -> random.nextBoolean() ? new Expression.Literal(type, i + shift)
                    : new Expression.Literal(DataType.TIMESTAMP, (i + shift) * 86_400_000_000L
                            + random.nextLong(86_400_000_000L));
            case Integer i -> random.nextInt(4) == 0 ? decimal(BigDecimal.valueOf(i).add(new BigDecimal("0.5")))
                    : new Expression.Literal(type, i + shift);
            case Short s -> new Expression.Literal(DataType.INTEGER, s + shift);
            case Byte b -> new Expression.Literal(DataType.INTEGER, b + shift);
            case Long l when type == DataType.TIMESTAMP -> switch (random.nextInt(4)) {
                case 0 -> new Expression.Literal(type, l + shift);
                case 1 -> new Expression.Literal(type, Math.floorDiv(l, 1000L) * 1000L + shift * 1000L);
                case 2 -> new Expression.Literal(DataType.DATE, (int) Math.floorDiv(l, 86_400_000_000L));
                default -> new Expression.Literal(type, l);
            };
            case Long l -> random.nextInt(4) == 0 ? decimal(BigDecimal.valueOf(l).subtract(new BigDecimal("0.5")))
                    : new Expression.Literal(type, l);
            case BigDecimal d -> random.nextBoolean() ? new Expression.Literal(type, d)
                    : decimal(d.add(new BigDecimal("0.005").multiply(BigDecimal.valueOf(shift))));
            case Double d -> new Expression.Literal(type, d);
            default -> new Expression.Literal(type, v);
        };
    }

    private static Expression.Literal decimal(BigDecimal d) {
        int scale = Math.max(d.scale(), 0);
        BigDecimal value = d.setScale(scale);
        return new Expression.Literal(DataType.decimal(Math.min(38, Math.max(value.precision(), scale + 1)), scale),
                value);
    }
}
