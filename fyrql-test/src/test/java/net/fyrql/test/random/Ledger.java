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

package net.fyrql.test.random;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The known differences between FyrQL and DuckDB that FyrQL keeps on
 * purpose: a random query whose outcome an entry explains is counted under
 * the entry and named in the report, but is no failure. Each entry applies
 * to the versions of DuckDB from {@code since} on and, if DuckDB fixes the
 * difference, before {@code fixed}.
 */
final class Ledger {

    /**
     * A known difference.
     *
     * @param id a short name
     * @param description what differs, and why FyrQL keeps it
     * @param since the first version of DuckDB the entry applies to
     * @param fixed the first version that no longer differs, {@code null} if none does yet
     * @param matches whether an outcome is this difference
     */
    record Entry(String id, String description, String since, String fixed, Predicate<Outcome> matches) {
    }

    /**
     * Tells whether the rows of a difference agree once every value that
     * reads as an interval is normalized, as both engines compare intervals.
     */
    private static boolean sameUpToEqualIntervals(Outcome o) {
        return sameRows(o, Ledger::normalized);
    }

    /**
     * Tells whether the rows of a difference of a query with
     * {@code string_agg} agree once the parts of each string are normalized
     * as intervals, and each string has the same parts in both: equal
     * intervals that the aggregate joined in another order.
     */
    private static boolean sameUpToJoinedTies(Outcome o) {
        return STRING_AGG.matcher(o.sql()).matches() && sameRows(o, Ledger::joinedParts);
    }

    /**
     * Tells whether the rows of a difference of a query with
     * {@code string_agg(DISTINCT ...)} agree once the parts of each string
     * are taken as a set and the intervals are normalized: equal intervals
     * of different forms that one engine kept apart.
     */
    private static boolean sameUpToDistinctParts(Outcome o) {
        return DISTINCT_STRING_AGG.matcher(o.sql()).matches() && sameRows(o, Ledger::distinctParts);
    }

    /** Tells whether the rows of a difference agree once each row is replaced by a key. */
    private static boolean sameRows(Outcome o, UnaryOperator<String> key) {
        if (o.kind() != Outcome.Kind.ROWS_DIFFER) {
            return false;
        }
        List<String> expected = new java.util.ArrayList<>();
        List<String> actual = new java.util.ArrayList<>();
        List<String> firstExpected = new java.util.ArrayList<>();
        List<String> firstActual = new java.util.ArrayList<>();
        for (String line : o.report().split("\n")) {
            String l = line.strip();
            int e = l.indexOf("expected [");
            int a = l.indexOf("], actual [");
            if (l.startsWith("row ") && e >= 0 && a > e) {
                firstExpected.add(key.apply(l.substring(e + 9, a + 1)));
                firstActual.add(key.apply(l.substring(a + 10)));
            } else if (l.startsWith("missing:")) {
                expected.add(key.apply(l.substring(8).strip()));
            } else if (l.startsWith("extra:")) {
                actual.add(key.apply(l.substring(6).strip()));
            }
        }
        if (expected.isEmpty() && actual.isEmpty()) {
            // Only the first row that differs is known.
            expected = firstExpected;
            actual = firstActual;
        }
        java.util.Collections.sort(expected);
        java.util.Collections.sort(actual);
        return !expected.isEmpty() && expected.equals(actual);
    }

    /** A row of a report with each value that reads as an interval in its normalized form. */
    private static String normalized(String row) {
        StringBuilder out = new StringBuilder();
        for (String token : row.split("((?<=[\\[\\],])|(?=[\\[\\],]))")) {
            String t = token.strip();
            if (t.isEmpty() || t.startsWith("'") || !t.matches(".*(year|mon|day|:).*")) {
                out.append(token);
                continue;
            }
            try {
                net.fyrql.core.value.Interval i = net.fyrql.core.value.Interval.parse(t);
                long micros = i.micros();
                long days = i.days() + micros / 86_400_000_000L;
                micros %= 86_400_000_000L;
                long months = i.months() + days / 30;
                days %= 30;
                out.append("iv(").append(months).append(',').append(days).append(',').append(micros).append(')');
            } catch (RuntimeException e) {
                out.append(token);
            }
        }
        return out.toString();
    }

    /**
     * A row of a report with each string as the parts that {@code string_agg}
     * joined with {@code " | "} or {@code ","}, normalized as intervals in
     * their order, and the parts as they are written, sorted.
     */
    private static String joinedParts(String row) {
        Matcher m = QUOTED.matcher(row);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String text = m.group(1);
            List<String> parts = List.of(text.split(text.contains(" | ") ? " \\| " : ","));
            List<String> sorted = new java.util.ArrayList<>(parts);
            java.util.Collections.sort(sorted);
            StringBuilder key = new StringBuilder();
            for (String part : parts) {
                key.append(normalized(part)).append('|');
            }
            key.append('#').append(String.join("|", sorted));
            m.appendReplacement(out, Matcher.quoteReplacement(key.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * A row of a report with each string as the set of the parts that
     * {@code string_agg} joined, normalized as intervals, and the other
     * values that read as intervals normalized.
     */
    private static String distinctParts(String row) {
        Matcher m = QUOTED.matcher(row);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String text = m.group(1);
            java.util.TreeSet<String> parts = new java.util.TreeSet<>();
            for (String part : text.split(text.contains(" | ") ? " \\| " : ",")) {
                parts.add(normalized(part));
            }
            m.appendReplacement(out, Matcher.quoteReplacement("{" + String.join("|", parts) + "}"));
        }
        m.appendTail(out);
        return normalized(out.toString());
    }

    static final List<Entry> ENTRIES = List.of(
            new Entry("duckdb-optimizer-errors", "An expression fails in one engine only, and DuckDB without its"
                    + " optimizer agrees with FyrQL: DuckDB folds constant expressions while planning and fails where"
                    + " no row reaches them, or prunes and pushes down expressions so that the rows which fail in"
                    + " FyrQL never reach them. FyrQL evaluates every expression of a node for every row of its"
                    + " input.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.unoptimized() == Outcome.Kind.SAME
                            || o.kind() == Outcome.Kind.FYRQL_FAILS && o.unoptimized() == Outcome.Kind.BOTH_FAIL),
            new Entry("plan-null-folding", "DuckDB's binder folds an operator or a function with a constant NULL"
                    + " argument to NULL without evaluating the other arguments, and types it like a bare NULL if it"
                    + " is a decimal or a concatenation. FyrQL's binder does the same, but a plan built in Java runs"
                    + " as it is: it evaluates every argument, and keeps the decimal type. Explained only where FyrQL"
                    + " agrees with DuckDB on the same SQL.", "1.5.6", null,
                    o -> (o.kind() == Outcome.Kind.FYRQL_FAILS || o.kind() == Outcome.Kind.TYPES_DIFFER)
                            && o.bound() == Outcome.Kind.SAME
                            && (o.sql().contains("CAST(NULL AS ") || o.sql().contains("TRY_CAST("))),
            new Entry("hugeint-sum-order", "DuckDB checks every addition of a HUGEINT sum, for sum and avg, so"
                    + " that it fails when a partial sum leaves the range, depending on the order of the values,"
                    + " which for DISTINCT is that of a hash table; FyrQL checks the final sum only.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.duckError().contains("Overflow in HUGEINT addition")),
            new Entry("round-overflow", "DuckDB wraps around when round with negative places takes an integer"
                    + " beyond its maximum, as in round(9223372036854775807, -1) = -9223372036854775800; FyrQL reports"
                    + " the overflow.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.sql().contains("round(")
                            && o.fyrqlError().matches(".*Out of range for (TINYINT|SMALLINT|INTEGER|BIGINT|HUGEINT).*")
                            || o.kind() == Outcome.Kind.ROWS_DIFFER && o.sql().contains("round(")
                            && o.report().contains("170141183460469231731687303715884105")),
            new Entry("join-key-cast", "FyrQL casts a join key to the type of the other side in a projection"
                    + " below the join, for every row; DuckDB casts it inside the join and not at all where the other"
                    + " side is empty. A value that does not convert fails in FyrQL only.", "1.5.6", null,
                    Ledger::failsInJoinKeyCast),
            new Entry("comparison-without-common-type", "DuckDB binds a comparison of two types without a common"
                    + " type, such as DECIMAL and DATE, and fails only when a row reaches the cast; FyrQL rejects it"
                    + " while binding.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && (o.fyrqlError().matches(".*Cannot compare values"
                            + " of type (?!VARCHAR ).+ and type (?!VARCHAR ).+ - an explicit cast.*")
                            || o.fyrqlError().matches(".*Cannot compare values of type (VARCHAR and type (MAP|STRUCT)"
                                    + "|(MAP|STRUCT).* and type VARCHAR ).*"))),
            new Entry("cast-without-conversion", "DuckDB binds a cast between two scalar types without a"
                    + " conversion, such as BOOLEAN to TIMESTAMP, and fails only when a value that is not NULL reaches"
                    + " it, while TRY_CAST gives NULL; FyrQL rejects it while binding. DuckDB has no conversion for"
                    + " any pair of scalar types that FyrQL rejects but BLOB and UUID, which the entry leaves out.",
                    "1.5.6", null, Ledger::castsWithoutConversion),
            new Entry("set-operation-without-common-type", "DuckDB unites two columns of types without a common"
                    + " type, such as DATE and DECIMAL, or a list and a VARCHAR, with a cast that fails only when a"
                    + " row reaches it; FyrQL rejects them while binding.", "1.5.6", null,
                    Ledger::unitesWithoutCommonType),
            new Entry("using-duplicate-alias", "DuckDB cannot resolve the merged column of a USING join of two tables"
                    + " with the same name, as in q JOIN q USING (k); FyrQL resolves it.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.duckError().contains("(duplicate alias")),
            new Entry("null-argument-overload", "DuckDB binds ceil and floor of a NULL as a DOUBLE and repeat as a"
                    + " BLOB, from its own overloads; FyrQL takes the cheapest of its overloads, a BIGINT or a VARCHAR.",
                    "1.5.6", null,
                    o -> (o.kind() == Outcome.Kind.TYPES_DIFFER || o.kind() == Outcome.Kind.DUCKDB_FAILS)
                            && o.sql().matches("(?s).*\\b(ceil|ceiling|floor|repeat)\\(NULL\\b.*")),
            new Entry("sum-distinct-hugeint-wraps", "DuckDB wraps around when sum(DISTINCT) of HUGEINTs leaves the"
                    + " range, while sum without DISTINCT fails; FyrQL fails in both.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains("Out of range for HUGEINT")
                            && o.sql().contains("sum(DISTINCT")),
            new Entry("nan-sign", "DuckDB writes the NaN of an invalid operation such as 0 / 0, whose sign bit the"
                    + " processor sets, as -nan; FyrQL writes every NaN as nan.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.report().contains("-nan")),
            new Entry("join-condition-cast", "DuckDB casts a whole column for a join condition, also for rows that"
                    + " no pair of the join reaches; FyrQL evaluates the condition on the pairs only.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.duckError().contains("when casting from source column")
                            && o.sql().contains(" JOIN ")),
            new Entry("condition-pushed-down", "FyrQL moves a condition above a join, an aggregate, a set"
                    + " operation or another condition, or one in the ON clause of a join, down to an input and"
                    + " evaluates it for every row of that input, also for rows that the node it passed drops;"
                    + " DuckDB without its optimizer evaluates it on the output or on the pairs, so a value that"
                    + " fails there fails in FyrQL only.", "1.5.6", null, Ledger::failsBelowANode),
            new Entry("condition-before-projection", "FyrQL moves a condition above a projection down below it,"
                    + " and one over the partition columns of a window, and computes the projection or the window"
                    + " only for the rows that pass; DuckDB without its optimizer computes it for every row first,"
                    + " so a value that fails there fails in DuckDB only.",
                    "1.5.6", null, Ledger::failsAboveACondition),
            new Entry("duckdb-short-circuit", "DuckDB evaluates the condition of a WHERE or a CASE as a selection,"
                    + " which evaluates the upper bound of BETWEEN and the right side of AND only for the rows that"
                    + " the lower bound and the left side keep; FyrQL evaluates both for every row, so a value that"
                    + " fails there fails in FyrQL only.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.unoptimized() == Outcome.Kind.FYRQL_FAILS
                            && o.sql().contains("CASE WHEN ")
                            && (o.sql().contains(" BETWEEN ") || o.sql().contains(" AND "))),
            new Entry("join-condition-side", "DuckDB evaluates the parts of a join condition that read one input"
                    + " for every row of that input, also for rows that no pair reaches, as when the other input"
                    + " is empty; FyrQL evaluates the condition on the pairs only.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.unoptimized() == Outcome.Kind.DUCKDB_FAILS
                            && o.plan() != null && Plans.nodes(o.plan()).stream().anyMatch(n ->
                                    n instanceof PlanNode.Join j && j.condition() != null)),
            new Entry("substring-constant-string", "DuckDB computes substring of a constant string with a start or"
                    + " length from a column by an older rule, which cuts the start to the string first; FyrQL uses"
                    + " the rule DuckDB has for columns and constants alike.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.sql().matches("(?s).*substr(ing)?\\('.*")),
            new Entry("parser-if-after-division", "JSqlParser does not read a call of if right after //, which FyrQL"
                    + " writes as DIV.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains("Syntax error at or near \"if\"")),
            new Entry("null-argument-ambiguity", "DuckDB reports a call with a NULL argument as ambiguous when"
                    + " overloads of different types fit it, as in date_trunc('day', NULL); FyrQL takes the first that"
                    + " fits.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS
                            && o.duckError().contains("Could not choose a best candidate function")),
            new Entry("distinct-order-by", "FyrQL orders a SELECT DISTINCT only by its output columns, as"
                    + " PostgreSQL does; DuckDB also takes other columns.", "1.5.6", null,
                    fyrqlFails("For SELECT DISTINCT, ORDER BY expressions must appear in select list")),
            new Entry("string-literal-argument", "DuckDB converts a string literal to the parameter type of a"
                    + " function with a single candidate, as in sqrt('4'); FyrQL does not.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains("No function matches")
                            && o.fyrqlError().contains("VARCHAR")),
            new Entry("avg-temporal", "DuckDB averages dates and timestamps; FyrQL does not.", "1.5.6", null,
                    fyrqlFails("No function matches avg(DATE)").or(fyrqlFails("No function matches avg(TIMESTAMP)"))),
            new Entry("duckdb-constant-folding", "DuckDB evaluates an expression without columns while binding,"
                    + " also without its optimizer, and fails where FyrQL never evaluates it: in a column no one"
                    + " reads, where no row reaches it, or in the bounds of a constant NULL BETWEEN, which FyrQL's"
                    + " binder drops as DuckDB's does.", "1.5.6", null, Ledger::failsAsAConstant),
            new Entry("plan-without-zone", "A plan built in Java has no time zone of a session, which only the"
                    + " binder gives the casts and functions of instants: it writes and reads an instant as text and"
                    + " takes its local date and time in UTC, while its rendering runs in DuckDB in the time zone of"
                    + " the session. Explained only where FyrQL agrees with DuckDB on the same SQL.", "1.5.6", null,
                    o -> ((o.kind() == Outcome.Kind.ROWS_DIFFER || o.kind() == Outcome.Kind.DUCKDB_FAILS
                            || o.kind() == Outcome.Kind.FYRQL_FAILS) && o.bound() == Outcome.Kind.SAME
                            || o.kind() == Outcome.Kind.DUCKDB_FAILS && o.bound() == Outcome.Kind.BOTH_FAIL)
                            && !RandomTables.ZONE.equals(java.time.ZoneOffset.UTC)
                            && !RandomTables.ZONE.getId().equals("UTC")),
            new Entry("interval-ties", "Intervals that both engines compare as equal but write differently, as 30"
                    + " days, 720:00:00 and 1 month, keep the order of their input among equal sort keys, which"
                    + " differs between the engines, also where string_agg joins them in the order of an ORDER BY,"
                    + " and min, max and first may return either of them. Explained where the rows agree with the"
                    + " intervals normalized, or the strings of string_agg have the same parts and agree with them"
                    + " normalized.", "1.5.6", null,
                    o -> sameUpToEqualIntervals(o) || sameUpToJoinedTies(o)),
            new Entry("distinct-aggregate-interval-forms", "DuckDB keeps intervals that it compares as equal but"
                    + " writes differently, as 30 days, 720:00:00 and 1 month, apart in string_agg(DISTINCT ...),"
                    + " while it takes them as one value in GROUP BY and SELECT DISTINCT; FyrQL takes them as one"
                    + " value in all three. Explained where the rows agree with the intervals normalized and the"
                    + " parts of each string as a set.", "1.5.6", null,
                    Ledger::sameUpToDistinctParts),
            new Entry("round-decimal-negative", "DuckDB rounds a decimal to negative places to 0 where the rounded"
                    + " value needs one integral digit more than the decimal has, as in round(9.450, -1) = 0 while"
                    + " round(19.450, -1) = 20; FyrQL rounds to 10.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER
                            && o.sql().matches("(?s).*round\\(.*, \\(?-\\d+\\)?\\).*")),
            new Entry("power-last-bit", "The math libraries of the two engines differ in the last bit of a large"
                    + " power, as of power(-10, 23), which a cast to a decimal or to text shows.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.sql().matches("(?s).*\\bpow(er)?\\(.*")),
            new Entry("window-value-filter", "DuckDB's parser takes first and last over a window as first_value and"
                    + " last_value and rejects a FILTER on them; FyrQL takes them as aggregates with a filter.",
                    "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS
                            && o.duckError().contains("FILTER is not implemented for non-aggregate window functions")),
            new Entry("avg-hugeint-range", "DuckDB averages HUGEINTs whose sum leaves the range of HUGEINT; FyrQL"
                    + " sums them exactly and fails.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains("Out of range for HUGEINT")
                            && o.sql().contains("avg(")),
            new Entry("lead-lag-instant-text", "DuckDB writes an instant that lead or lag takes as the default of a"
                    + " text in UTC; FyrQL writes it in the time zone of the session, as a cast does in both.",
                    "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.sql().matches("(?s).*\\b(lead|lag)\\(.*")
                            && o.report().contains("+00'") && !RandomTables.ZONE.getId().equals("UTC")),
            new Entry("duckdb-part-errors", "DuckDB fails on a part that a time of day or an instant does not have,"
                    + " as date_part('year', a TIME) or date_diff('timezone', ...), also where no row reaches it, as"
                    + " in a filter after false AND or for a NULL; FyrQL fails only where a row reaches it.",
                    "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS
                            && (o.duckError().contains("Specifier type not implemented")
                                    || o.duckError().contains("unsuccessful or closed pending query result")
                                    && o.sql().matches("(?s).*date_part\\('[a-z_]+', .*"))),
            new Entry("constant-in-list-cast", "DuckDB folds an IN list whose operands are all constants while"
                    + " binding, also with the optimizer disabled, and casts every constant of the list to the common"
                    + " type there even where the left operand is NULL; a HUGEINT beyond DECIMAL(38,10) fails. FyrQL"
                    + " folds NULL IN (...) to NULL without casting the list.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS
                            && o.duckError().matches("(?s).*Could not cast value -?\\d+ to DECIMAL.*")
                            && o.sql().matches("(?s).*CAST\\(NULL AS [A-Z]+\\) IN \\(.*")),
            new Entry("subquery-aggregate-before-outer", "FyrQL computes the aggregate of a subquery that it"
                    + " turns into a join for every group before it reads the rows of the outer query, and fails"
                    + " where the aggregate fails also when the outer query has no row that needs it; DuckDB computes"
                    + " it for the rows of the outer query only.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains(" in Aggregate#")
                            && o.plan() != null && Plans.nodes(o.plan()).stream().anyMatch(n ->
                                    n instanceof PlanNode.Join j && j.type() == PlanNode.JoinType.SINGLE
                                            && Plans.nodes(j.right()).stream()
                                                    .anyMatch(r -> r instanceof PlanNode.Aggregate))),
            new Entry("join-empty-build", "FyrQL reads the right input of a join first and does not read the left"
                    + " input of an inner join or a semi join whose right input has no rows; DuckDB evaluates the"
                    + " expressions and windows of the left input, also without its optimizer, and may fail there,"
                    + " as on a count of ntile that is not positive.", "1.5.6",
                    null, Ledger::skipsTheLeftInput),
            new Entry("duckdb-join-skips-input", "DuckDB does not evaluate one input of an inner join or a"
                    + " semi join when its hash table, built from the other input, holds no key that is not NULL;"
                    + " which input it builds from depends on its plan, also without its optimizer. FyrQL reads both"
                    + " inputs where the right one has rows, and fails in the left one where DuckDB gives no rows.",
                    "1.5.6", null, Ledger::skippedByDuckDb),
            new Entry("subquery-null-column", "DuckDB keeps a bare NULL that a subquery selects untyped, so that"
                    + " an operator over it picks its overload as for a bare NULL, such as a BIGINT for %; FyrQL"
                    + " types the column INTEGER.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.TYPES_DIFFER && o.sql().contains("NULL AS ")),
            new Entry("duckdb-correlated-null", "DuckDB finds a row for a correlated EXISTS or IN in an"
                    + " expression where a value of the outer query that the subquery compares other than by"
                    + " equality is NULL, as in a OR EXISTS (SELECT * FROM u WHERE u.k = t.k AND u.d <> t.d) with"
                    + " t.d NULL, unlike the same EXISTS as a condition of WHERE; FyrQL finds none.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.plan() != null
                            && Plans.nodes(o.plan()).stream().anyMatch(n -> n instanceof PlanNode.Join j
                                    && j.type() == PlanNode.JoinType.MARK && j.condition() != null)),
            new Entry("window-call-pruned", "FyrQL drops the call of a window whose column no one reads; DuckDB"
                    + " computes it, also without its optimizer, and fails where it fails, as on a negative offset"
                    + " of a RANGE frame.", "1.5.6", null, Ledger::prunesAWindowCall),
            new Entry("window-key-zero-sign", "DuckDB passes the partition and order columns of a window through"
                    + " its sort keys, which turn -0.0 into 0.0; FyrQL keeps -0.0, so that a value computed from it"
                    + " above the window, such as -10 / x, may differ in its sign.", "1.5.6", null,
                    Ledger::differsInTheSignOfAZero),
            new Entry("lead-lag-offset-overflow", "DuckDB adds the offset of lag or lead to the number of the row"
                    + " and fails when the sum leaves BIGINT; FyrQL takes the row it would reach as one outside the"
                    + " partition and returns the default, as for any other offset that leaves it.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS
                            && o.duckError().matches("(?s).*Overflow in (addition|subtraction) of INT64.*")
                            && o.plan() != null && Plans.nodes(o.plan()).stream().anyMatch(n ->
                                    n instanceof PlanNode.Window w && w.calls().stream().anyMatch(c ->
                                            (c.function() == PlanNode.WindowFunction.LAG
                                                    || c.function() == PlanNode.WindowFunction.LEAD)
                                                    && c.arguments().size() > 1))),
            new Entry("duckdb-empty-frame-exclusion", "DuckDB takes rows into a frame whose start lies after its end,"
                    + " which is empty, when the frame leaves out rows, as in GROUPS BETWEEN 0 PRECEDING AND 3"
                    + " PRECEDING EXCLUDE TIES, where it takes earlier groups; FyrQL keeps the frame empty.", "1.5.6",
                    null, Ledger::excludesFromAnEmptyFrame),
            new Entry("window-argument-without-cast", "DuckDB binds an argument of ntile, nth_value, lag or lead"
                    + " of a type that has no cast to the type the function needs, such as a DATE for the count of"
                    + " ntile or a BIGINT for the default of lag of a DATE, and fails only when a row takes a value"
                    + " that is not NULL; FyrQL rejects it while binding.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().matches(
                            "(?s).*No function matches (lag|lead|ntile|nth_value) with an argument of type.*")),
            new Entry("plan-without-sql", "A plan built in Java may combine types that a query cannot, such as"
                    + " BOOLEAN and DOUBLE results of a CASE; DuckDB rejects its rendering while binding, and so"
                    + " does FyrQL's binder.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.DUCKDB_FAILS && o.bound() == Outcome.Kind.BOTH_FAIL
                            && o.duckError().contains("Binder Error")),
            new Entry("duckdb-unstable-rows", "DuckDB gives other rows for the same query over the same tables"
                    + " on another run, rarely, as for a LIMIT in a subquery below a join; the rows of a second run"
                    + " agree with FyrQL.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.report().startsWith(TwoEngines.UNSTABLE)),
            new Entry("duckdb-optimizer-rows", "DuckDB gives other rows than FyrQL on two runs, and DuckDB without"
                    + " its optimizer agrees with FyrQL, as when a LIMIT below a join keeps rows that it would not"
                    + " keep: the rows lie between the least and the greatest key of the build side, as if DuckDB"
                    + " had moved the filter on these keys that the join passes to its probe side below the LIMIT."
                    + " Seen rarely, in the build on Linux.", "1.5.6", null,
                    o -> o.kind() == Outcome.Kind.ROWS_DIFFER && o.unoptimized() == Outcome.Kind.SAME));

    /** A string as a report writes it, in single quotes. */
    private static final Pattern QUOTED = Pattern.compile("'((?:[^']|'')*)'");
    /** A query with an aggregate that joins strings. */
    private static final Pattern STRING_AGG = Pattern.compile("(?s).*\\b(string_agg|listagg)\\(.*");
    /** A query with an aggregate that joins distinct strings. */
    private static final Pattern DISTINCT_STRING_AGG = Pattern.compile("(?s).*\\b(string_agg|listagg)\\(DISTINCT .*");

    /** The name of a scalar type in a message of FyrQL. */
    private static final String SCALAR = "TIMESTAMP WITH TIME ZONE|DECIMAL\\(\\d+,\\d+\\)|[A-Z]+";

    /** The two types of a set operation that FyrQL does not unite. */
    private static final Pattern SET_OPERATION_TYPES = Pattern.compile(
            "(?s).*Cannot combine types (.+) and (.+) in a set operation.*");

    /** The two scalar types of a cast that FyrQL rejects, each name whole. */
    private static final Pattern CAST_TYPES = Pattern.compile("(?s).*Cannot cast (" + SCALAR + ") to (" + SCALAR
            + ")(?![A-Za-z(\\[]| WITH ).*");

    /** The node a QueryException names: its label, the type and the number of the node in the plan. */
    private static final Pattern FAILED_NODE = Pattern.compile(".* in Project#(\\d+)$");
    /** The values of the first row that differs, as {@link net.fyrql.test.ResultComparison} reports them. */
    private static final Pattern DIFFERING_ROW = Pattern.compile("(?s).*?expected \\[(.*?)\\], actual \\[(.*?)\\]\n.*");
    private static final Pattern FAILED_FILTER = Pattern.compile(".* in Filter#\\d+$");
    /** A string that DuckDB fails to convert, and the type. */
    private static final Pattern FAILED_STRING_CAST = Pattern.compile("Could not convert string [\"'](.*?)[\"'] to (\\S+)");

    private final String version;
    private final Map<String, Integer> hits = new LinkedHashMap<>();

    /** A ledger for a version of DuckDB, such as {@code 1.5.6}. */
    Ledger(String version) {
        this.version = version.startsWith("v") ? version.substring(1) : version;
    }

    /** Tells whether FyrQL failed in a projection that casts a join key, which the binder names {@code #key}. */
    private static boolean failsInJoinKeyCast(Outcome o) {
        if (o.kind() != Outcome.Kind.FYRQL_FAILS || o.optimized() == null) {
            return false;
        }
        Matcher m = FAILED_NODE.matcher(o.fyrqlError());
        if (!m.matches()) {
            return false;
        }
        // The error numbers the nodes of the plan that ran, the optimized one.
        int id = Integer.parseInt(m.group(1));
        for (Map.Entry<PlanNode, Integer> node : Plans.ids(o.optimized()).entrySet()) {
            if (node.getValue() == id && node.getKey() instanceof PlanNode.Project p) {
                return p.columns().stream().anyMatch(c -> c.name().startsWith("#key")
                        && c.expression() instanceof Expression.Cast);
            }
        }
        return false;
    }

    /**
     * Tells whether FyrQL failed in a filter where DuckDB without its
     * optimizer does not, and the plan has a condition that predicate
     * pushdown moves below a node that drops rows: one above a join, an
     * aggregate, a set operation or another filter, or one of a join.
     */
    private static boolean failsBelowANode(Outcome o) {
        if (o.kind() != Outcome.Kind.FYRQL_FAILS || o.unoptimized() != Outcome.Kind.FYRQL_FAILS || o.plan() == null
                || !FAILED_FILTER.matcher(o.fyrqlError()).matches()) {
            return false;
        }
        for (PlanNode node : Plans.nodes(o.plan())) {
            if (node instanceof PlanNode.Join j && j.condition() != null || node instanceof PlanNode.Filter f
                    && Plans.nodes(f.input()).stream().anyMatch(n -> n instanceof PlanNode.Join
                            || n instanceof PlanNode.Aggregate || n instanceof PlanNode.SetOperation
                            || n instanceof PlanNode.Filter)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether DuckDB failed, also without its optimizer, and the plan
     * has a filter above a projection that computes a value or above a
     * window.
     */
    private static boolean failsAboveACondition(Outcome o) {
        if (o.kind() != Outcome.Kind.DUCKDB_FAILS || o.unoptimized() != Outcome.Kind.DUCKDB_FAILS || o.plan() == null) {
            return false;
        }
        for (PlanNode node : Plans.nodes(o.plan())) {
            if (node instanceof PlanNode.Filter f && Plans.nodes(f.input()).stream().anyMatch(n ->
                    n instanceof PlanNode.Window
                            || n instanceof PlanNode.Project p && p.columns().stream().anyMatch(c ->
                                    !(c.expression() instanceof Expression.Column)
                                            && !(c.expression() instanceof Expression.Literal)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether DuckDB failed, and differs from FyrQL also without its
     * optimizer, and the plan FyrQL bound has an expression without columns
     * that fails, or the query casts the string that DuckDB fails to convert.
     */
    private static boolean failsAsAConstant(Outcome o) {
        if (o.kind() != Outcome.Kind.DUCKDB_FAILS || o.unoptimized() == Outcome.Kind.SAME || o.plan() == null) {
            return false;
        }
        Matcher cast = FAILED_STRING_CAST.matcher(o.duckError());
        if (cast.find() && o.sql().contains("CAST('" + cast.group(1) + "' AS " + cast.group(2) + ")")) {
            return true;
        }
        for (PlanNode node : Plans.nodes(o.plan())) {
            for (Expression e : Plans.expressions(node)) {
                if (!Expressions.allMatch(e, x -> !Expressions.isConstant(x) || !fails(x))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean fails(Expression constant) {
        try {
            Expressions.constantValue(constant);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Tells whether FyrQL failed, also against DuckDB without its optimizer,
     * where DuckDB gives no rows, and the plan FyrQL ran has an inner or a
     * semi join that computes expressions in its left input.
     */
    private static boolean skippedByDuckDb(Outcome o) {
        if (o.kind() != Outcome.Kind.FYRQL_FAILS || o.unoptimized() != Outcome.Kind.FYRQL_FAILS
                || !o.report().equals(TwoEngines.DUCKDB_ROWS + "0\n") || o.optimized() == null) {
            return false;
        }
        for (PlanNode node : Plans.nodes(o.optimized())) {
            if (node instanceof PlanNode.Join j && (j.type() == PlanNode.JoinType.INNER
                    || j.type() == PlanNode.JoinType.SEMI) && Plans.nodes(j.left()).stream().anyMatch(n ->
                    Plans.expressions(n).stream().anyMatch(e -> !(e instanceof Expression.Column)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether DuckDB failed, also without its optimizer, and the plan
     * FyrQL ran has an inner or a semi join that computes expressions or
     * windows in its left input.
     */
    private static boolean skipsTheLeftInput(Outcome o) {
        if (o.kind() != Outcome.Kind.DUCKDB_FAILS || o.unoptimized() != Outcome.Kind.DUCKDB_FAILS
                || o.optimized() == null) {
            return false;
        }
        for (PlanNode node : Plans.nodes(o.optimized())) {
            if (node instanceof PlanNode.Join j && (j.type() == PlanNode.JoinType.INNER
                    || j.type() == PlanNode.JoinType.SEMI) && Plans.nodes(j.left()).stream().anyMatch(n ->
                    n instanceof PlanNode.Window
                            || Plans.expressions(n).stream().anyMatch(e -> !(e instanceof Expression.Column)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether DuckDB failed, also without its optimizer, and the plan
     * FyrQL ran has fewer calls of windows than the plan it was given.
     */
    private static boolean prunesAWindowCall(Outcome o) {
        return o.kind() == Outcome.Kind.DUCKDB_FAILS && o.unoptimized() == Outcome.Kind.DUCKDB_FAILS
                && o.plan() != null && o.optimized() != null && windowCalls(o.optimized()) < windowCalls(o.plan());
    }

    /**
     * Tells whether the first rows that differ differ only in the signs of
     * floating-point values, as computed from a zero of the other sign, and
     * the plan has a window.
     */
    private static boolean differsInTheSignOfAZero(Outcome o) {
        if (o.kind() != Outcome.Kind.ROWS_DIFFER || o.plan() == null
                || Plans.nodes(o.plan()).stream().noneMatch(n -> n instanceof PlanNode.Window)) {
            return false;
        }
        Matcher m = DIFFERING_ROW.matcher(o.report());
        if (!m.matches()) {
            return false;
        }
        String[] expected = m.group(1).split(", ");
        String[] actual = m.group(2).split(", ");
        if (expected.length != actual.length) {
            return false;
        }
        boolean differs = false;
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(actual[i])) {
                if (!inSignOnly(expected[i], actual[i])) {
                    return false;
                }
                differs = true;
            }
        }
        return differs;
    }

    /** Tells whether two values, a float or a double as the report writes them, differ in their sign bits only. */
    private static boolean inSignOnly(String x, String y) {
        try {
            if (x.startsWith("d") && y.startsWith("d")) {
                return (Long.parseUnsignedLong(x.substring(1), 16) ^ Long.parseUnsignedLong(y.substring(1), 16))
                        == Long.MIN_VALUE;
            }
            if (x.startsWith("f") && y.startsWith("f")) {
                return (Integer.parseUnsignedInt(x.substring(1), 16) ^ Integer.parseUnsignedInt(y.substring(1), 16))
                        == Integer.MIN_VALUE;
            }
        } catch (NumberFormatException e) {
            // Not a number as the report writes one.
        }
        return false;
    }

    /**
     * Tells whether the rows differ and the plan has a call of a window
     * over a frame of rows or groups that leaves out rows and whose start
     * lies after its end.
     */
    private static boolean excludesFromAnEmptyFrame(Outcome o) {
        if (o.kind() != Outcome.Kind.ROWS_DIFFER || o.plan() == null) {
            return false;
        }
        for (PlanNode node : Plans.nodes(o.plan())) {
            if (node instanceof PlanNode.Window w) {
                for (PlanNode.WindowCall c : w.calls()) {
                    PlanNode.Frame f = c.frame();
                    if (c.function().usesFrame() && f.exclusion() != PlanNode.FrameExclusion.NO_OTHERS
                            && f.unit() != PlanNode.FrameUnit.RANGE && reach(f.start()) > reach(f.end())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The distance of a bound of rows or groups from the current one, negative before it. */
    private static long reach(PlanNode.FrameBound b) {
        return switch (b.kind()) {
            case UNBOUNDED_PRECEDING -> Long.MIN_VALUE;
            case PRECEDING -> -((Number) b.offset().value()).longValue();
            case CURRENT_ROW -> 0;
            case FOLLOWING -> ((Number) b.offset().value()).longValue();
            case UNBOUNDED_FOLLOWING -> Long.MAX_VALUE;
        };
    }

    private static int windowCalls(PlanNode plan) {
        int calls = 0;
        for (PlanNode node : Plans.nodes(plan)) {
            if (node instanceof PlanNode.Window w) {
                calls += w.calls().size();
            }
        }
        return calls;
    }

    /**
     * Tells whether FyrQL rejected a set operation of two types without a
     * common type: a VARCHAR, which FyrQL unites with every scalar type,
     * only with a nested type.
     */
    private static boolean unitesWithoutCommonType(Outcome o) {
        if (o.kind() != Outcome.Kind.FYRQL_FAILS) {
            return false;
        }
        Matcher m = SET_OPERATION_TYPES.matcher(o.fyrqlError());
        if (!m.matches()) {
            return false;
        }
        String left = m.group(1);
        String right = m.group(2);
        return !left.equals("VARCHAR") && !right.equals("VARCHAR") || nested(left) || nested(right);
    }

    /**
     * Tells whether FyrQL rejected a cast between two scalar types of which
     * DuckDB has no conversion either: any but between BLOB and UUID.
     */
    private static boolean castsWithoutConversion(Outcome o) {
        if (o.kind() != Outcome.Kind.FYRQL_FAILS) {
            return false;
        }
        Matcher m = CAST_TYPES.matcher(o.fyrqlError());
        return m.matches() && !Set.of(m.group(1), m.group(2)).equals(Set.of("BLOB", "UUID"));
    }

    /** Tells whether a type name is one of a list, a struct or a map. */
    private static boolean nested(String type) {
        return type.endsWith("]") || type.startsWith("STRUCT(") || type.startsWith("MAP(");
    }

    private static Predicate<Outcome> fyrqlFails(String message) {
        return o -> o.kind() == Outcome.Kind.FYRQL_FAILS && o.fyrqlError().contains(message);
    }

    /** Returns the entry that explains a differing outcome, or {@code null} if none does. */
    Entry explain(Outcome outcome) {
        if (!outcome.kind().differs()) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (applies(e) && e.matches().test(outcome)) {
                return e;
            }
        }
        return null;
    }

    /** Counts a hit of an entry. */
    void count(Entry entry) {
        hits.merge(entry.id(), 1, Integer::sum);
    }

    /** Returns the hits per entry, for the report. */
    Map<String, Integer> hits() {
        return hits;
    }

    private boolean applies(Entry e) {
        return compare(version, e.since()) >= 0 && (e.fixed() == null || compare(version, e.fixed()) < 0);
    }

    /** Compares two versions of dotted numbers. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int number(String part) {
        String digits = part.replaceAll("\\D.*", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }
}
