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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shapes of the plans the binder builds; their results are compared with DuckDB in {@code fyrql-test}. */
class BinderTest {

    /** Two tables t(a INTEGER, b VARCHAR, e TINYINT) and u(a BIGINT, f VARCHAR) of a source s. */
    private static final CatalogView CATALOG = new CatalogView() {
        @Override
        public TableRef resolve(List<String> name) {
            String table = name.get(name.size() - 1).toLowerCase(java.util.Locale.ROOT);
            if (!table.equals("t") && !table.equals("u")) {
                throw new SqlException("Table with name " + String.join(".", name) + " does not exist",
                        Position.UNKNOWN);
            }
            return TableRef.of("s", table);
        }

        @Override
        public Schema schema(TableRef table) {
            return table.table().equals("t")
                    ? Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.VARCHAR),
                            new Schema.Field("e", DataType.TINYINT))
                    : Schema.of(new Schema.Field("a", DataType.BIGINT), new Schema.Field("f", DataType.VARCHAR));
        }

        @Override
        public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
            return TableRef.of("s", name + arguments + namedArguments);
        }
    };

    private static final Binder BINDER = new Binder(CATALOG, FunctionRegistry.standard());

    private static PlanNode bind(String sql) {
        return BINDER.bind(SqlParser.parse(sql));
    }

    private static SqlException error(String sql) {
        return assertThrows(SqlException.class, () -> bind(sql));
    }

    @Test
    void equalitiesBetweenTheSidesAreJoinKeysOfOneType() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT t.a FROM t JOIN u ON t.a = u.a AND t.b < u.f"));
        PlanNode.Filter filter = assertInstanceOf(PlanNode.Filter.class, top.input());
        PlanNode.Join join = assertInstanceOf(PlanNode.Join.class, filter.input());
        assertEquals(PlanNode.JoinType.INNER, join.type());
        // t.a is cast to BIGINT in an extra column of the left input, after its three columns.
        assertEquals(List.of(3), join.leftKeys());
        assertEquals(List.of(0), join.rightKeys());
        assertEquals(DataType.BIGINT, join.left().schema().type(3));
        // The filter reads u.f behind the extra column.
        assertEquals("Comparison[op=LT, left=Column[index=1, type=VARCHAR], right=Column[index=5, type=VARCHAR]]",
                filter.condition().toString());
    }

    @Test
    void anOuterJoinFiltersTheOtherSideAndKeepsTheRestOfItsConditionInTheJoin() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT t.a, u.f FROM t LEFT JOIN u ON u.f = 'x' AND t.e > 1"));
        PlanNode.Join join = assertInstanceOf(PlanNode.Join.class, top.input());
        assertEquals(PlanNode.JoinType.LEFT, join.type());
        // The condition on u filters the right input; the one on t stays in the join, as a row of t is kept either
        // way, and the join without an equality has the key TRUE on both sides.
        assertEquals(1, join.leftKeys().size());
        assertEquals("Comparison[op=GT, left=Column[index=2, type=TINYINT], right=Literal[type=INTEGER, value=1]]",
                join.condition().toString());
        assertInstanceOf(PlanNode.Filter.class, assertInstanceOf(PlanNode.Project.class, join.right()).input());
        assertInstanceOf(PlanNode.Scan.class, assertInstanceOf(PlanNode.Project.class, join.left()).input());
        PlanNode.Join right = assertInstanceOf(PlanNode.Join.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT t.a, u.f FROM t RIGHT JOIN u ON t.a = u.a AND t.e > 1 AND u.f = 'x'")).input());
        assertEquals(1, right.leftKeys().size());
        assertInstanceOf(PlanNode.Filter.class, assertInstanceOf(PlanNode.Project.class, right.left()).input());
        // The condition on u, the preserved side, needs no extra column.
        assertInstanceOf(PlanNode.Scan.class, right.right());
        assertEquals("Comparison[op=EQ, left=Column[index=5, type=VARCHAR], right=Literal[type=VARCHAR, value=x]]",
                right.condition().toString());
        // Without an equality, a join without conditions still has a key: TRUE on both sides.
        PlanNode.Join always = assertInstanceOf(PlanNode.Join.class,
                assertInstanceOf(PlanNode.Project.class, bind("SELECT * FROM t FULL JOIN u ON true")).input());
        assertEquals(1, always.leftKeys().size());
        // A condition between the two sides reads the right input behind the extra key column of the left one.
        PlanNode.Join between = assertInstanceOf(PlanNode.Join.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT * FROM t RIGHT JOIN u ON t.a = u.a AND t.b < u.f")).input());
        assertEquals(PlanNode.JoinType.RIGHT, between.type());
        assertEquals("Comparison[op=LT, left=Column[index=1, type=VARCHAR], right=Column[index=5, type=VARCHAR]]",
                between.condition().toString());
    }

    @Test
    void conditionsOfWhereThatAreSubqueriesAreSemiAndAntiJoins() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a FROM t WHERE a IN (SELECT a FROM u) AND NOT EXISTS (SELECT 1 FROM u) AND e > 1"));
        PlanNode.Filter filter = assertInstanceOf(PlanNode.Filter.class, top.input());
        assertEquals("Comparison[op=GT, left=Column[index=2, type=TINYINT], right=Literal[type=INTEGER, value=1]]",
                filter.condition().toString());
        PlanNode.Join anti = assertInstanceOf(PlanNode.Join.class, filter.input());
        assertEquals(PlanNode.JoinType.ANTI, anti.type());
        assertEquals(List.of(), anti.leftKeys());
        // EXISTS needs one row of the subquery at most.
        assertEquals(1, assertInstanceOf(PlanNode.Limit.class, anti.right()).count());
        PlanNode.Join semi = assertInstanceOf(PlanNode.Join.class, anti.left());
        assertEquals(PlanNode.JoinType.SEMI, semi.type());
        // t.a is cast to the BIGINT of u.a in an extra column of the left input.
        assertEquals(List.of(3), semi.leftKeys());
        assertEquals(List.of(0), semi.rightKeys());
        assertEquals(DataType.BIGINT, semi.left().schema().type(3));
    }

    @Test
    void otherSubqueriesAreMarkAndSingleJoinsThatAddTheirValueAsAColumn() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a FROM t WHERE e NOT IN (SELECT a FROM u) OR a = (SELECT max(a) FROM u)"));
        PlanNode.Filter filter = assertInstanceOf(PlanNode.Filter.class, top.input());
        PlanNode.Join single = assertInstanceOf(PlanNode.Join.class, filter.input());
        assertEquals(PlanNode.JoinType.SINGLE, single.type());
        assertEquals(List.of(), single.leftKeys());
        PlanNode.Join mark = assertInstanceOf(PlanNode.Join.class, single.left());
        assertEquals(PlanNode.JoinType.MARK, mark.type());
        assertEquals(List.of(3), mark.leftKeys());
        // The mark follows the key column of e, the maximum the mark.
        assertEquals("Or[left=Not[operand=Column[index=4, type=BOOLEAN]], right=Comparison[op=EQ, left=Column[index=0, "
                + "type=INTEGER], right=Column[index=5, type=BIGINT]]]",
                filter.condition().toString());
        PlanNode.Project items = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a, EXISTS (SELECT 1 FROM u), (SELECT f FROM u LIMIT 1) FROM t"));
        assertEquals("[Column[index=0, type=INTEGER], Coalesce[operands=[Column[index=3, type=BOOLEAN], Literal["
                + "type=BOOLEAN, value=false]]], Column[index=4, type=VARCHAR]]",
                items.columns().stream().map(PlanNode.NamedExpression::expression).toList().toString());
    }

    @Test
    void theSubqueriesOfAnAggregationAreJoinedToTheGroups() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT b, (SELECT count(*) FROM u) FROM t GROUP BY b HAVING count(*) > (SELECT min(a) FROM u)"));
        PlanNode.Filter having = assertInstanceOf(PlanNode.Filter.class, top.input());
        PlanNode.Join second = assertInstanceOf(PlanNode.Join.class, having.input());
        PlanNode.Join first = assertInstanceOf(PlanNode.Join.class, second.left());
        assertEquals(PlanNode.JoinType.SINGLE, first.type());
        assertInstanceOf(PlanNode.Aggregate.class, first.left());
        // The groups are b and count(*); the two subqueries follow.
        assertEquals("Column[index=2, type=BIGINT]", top.columns().get(1).expression().toString());
        assertEquals("Comparison[op=GT, left=Column[index=1, type=BIGINT], right=Column[index=3, type=BIGINT]]",
                having.condition().toString());
    }

    @Test
    void subqueriesGoInWhereHavingAndTheSelectListOnly() {
        String here = "Not supported: a subquery here at line 1, column ";
        assertEquals(here + "26", error("SELECT a FROM t ORDER BY (SELECT 1)").getMessage());
        // EXISTS has the position of its subquery.
        assertEquals(here + "50", error("SELECT t.a FROM t JOIN u ON t.a = u.a AND EXISTS (SELECT 1)").getMessage());
        assertEquals(here + "26", error("SELECT a FROM t GROUP BY (SELECT 1)").getMessage());
        assertEquals(here + "12", error("SELECT sum((SELECT 1)) FROM t").getMessage());
        assertEquals("Subquery returns 2 columns - expected 1 at line 1, column 23",
                error("SELECT a FROM t WHERE a IN (SELECT a, f FROM u)").getMessage());
        assertEquals("Subquery returns 2 columns - expected 1 at line 1, column 8",
                error("SELECT (SELECT * FROM u)").getMessage());
    }

    @Test
    void aCorrelatedSubqueryIsJoinedByTheConditionsOfItsWhereThatReadTheOuterQuery() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a AND u.f <> t.b AND u.f > 'x')"));
        PlanNode.Join semi = assertInstanceOf(PlanNode.Join.class, top.input());
        assertEquals(PlanNode.JoinType.SEMI, semi.type());
        // The equality is a key: t.a cast to BIGINT in an extra column, u.a after the select list of the subquery.
        assertEquals(List.of(3), semi.leftKeys());
        assertEquals(List.of(1), semi.rightKeys());
        assertEquals(DataType.BIGINT, semi.left().schema().type(3));
        // The other condition reads u.f, the second extra column of the subquery, after the 4 columns on the left.
        assertEquals("Comparison[op=NE, left=Column[index=6, type=VARCHAR], right=Column[index=1, type=VARCHAR]]",
                semi.condition().toString());
        // The subquery is joined as a whole, filtered by its own condition.
        PlanNode.Project subquery = assertInstanceOf(PlanNode.Project.class, semi.right());
        assertEquals(List.of("1", "#corr0", "#corr1"), subquery.schema().fields().stream()
                .map(net.fyrql.core.type.Schema.Field::name).toList());
        assertInstanceOf(PlanNode.Filter.class, subquery.input());
    }

    @Test
    void anAggregatingCorrelatedSubqueryGroupsByItsKeys() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a FROM t WHERE e < (SELECT count(*) + 1 FROM u WHERE t.a = u.a)"));
        PlanNode.Filter filter = assertInstanceOf(PlanNode.Filter.class, top.input());
        PlanNode.Join single = assertInstanceOf(PlanNode.Join.class, filter.input());
        assertEquals(PlanNode.JoinType.SINGLE, single.type());
        assertEquals(List.of(0), single.rightKeys());
        PlanNode.Aggregate groups = assertInstanceOf(PlanNode.Aggregate.class, single.right());
        assertEquals(List.of(0), groups.groupColumns());
        // Without GROUP BY, the value is computed after the join, where a count without a group is 0.
        assertEquals("Comparison[op=LT, left=Column[index=2, type=TINYINT], right=Arithmetic[op=ADD, left=Coalesce["
                + "operands=[Column[index=5, type=BIGINT], Literal[type=BIGINT, value=0]]], right=Literal[type=BIGINT, "
                + "value=1]]]", filter.condition().toString());
    }

    @Test
    void aCorrelatedSubqueryReferencesTheOuterQueryInItsWhereClauseOnly() {
        String at = " at line 1, column ";
        assertEquals("Not supported: a reference to the outer query outside the WHERE clause of a subquery" + at + "16",
                error("SELECT (SELECT t.b FROM u) FROM t").getMessage());
        assertEquals("Not supported: a reference to a query more than one level out of a subquery" + at + "91",
                error("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE EXISTS (SELECT 1 FROM u x WHERE x.a = t.a))")
                        .getMessage());
        assertEquals("Not supported: a correlated condition of an aggregating subquery that is not an equality" + at
                + "55", error("SELECT a FROM t WHERE e < (SELECT max(a) FROM u WHERE u.a > t.a)").getMessage());
        assertEquals("Not supported: HAVING in a correlated subquery without GROUP BY" + at + "72",
                error("SELECT a FROM t WHERE e < (SELECT max(a) FROM u WHERE u.a = t.a HAVING count(*) > 1)")
                        .getMessage());
        assertEquals("Not supported: LIMIT or OFFSET in a correlated subquery" + at + "69",
                error("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a LIMIT 1)").getMessage());
        assertEquals("Not supported: EXISTS or IN with a correlated subquery that aggregates without GROUP BY" + at
                + "31", error("SELECT a FROM t WHERE EXISTS (SELECT max(a) FROM u WHERE u.a = t.a)").getMessage());
    }

    @Test
    void anIntervalOfDaysAddedToAConstantDateIsFoldedWhenBinding() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT DATE '1998-12-01' - INTERVAL"
                + " '90' DAY, DATE '2024-01-31' + INTERVAL 1 MONTH, CAST('2024-02-28' AS DATE) + INTERVAL '2' DAYS,"
                + " CAST(NULL AS DATE) + INTERVAL '1' DAY, INTERVAL '1' WEEK + DATE '2024-01-01',"
                + " DATE '2024-01-31' + INTERVAL '1 month 1 day'"));
        List<Object> values = new ArrayList<>();
        for (PlanNode.NamedExpression c : top.columns()) {
            Expression.Literal l = assertInstanceOf(Expression.Literal.class, c.expression());
            values.add(l.value() == null ? null : LocalDate.ofEpochDay((Integer) l.value()).toString());
        }
        assertEquals(Arrays.asList("1998-09-02", "2024-02-29", "2024-03-01", null, "2024-01-08", "2024-03-01"),
                values);
        assertEquals("[DATE, DATE, DATE, DATE, DATE, DATE]", top.schema().types().toString());
        // With a time of day, a column or a timestamp, the operator of DuckDB, which gives a TIMESTAMP.
        assertEquals("[TIMESTAMP, TIMESTAMP, TIMESTAMP, INTERVAL, INTERVAL]", bind("SELECT DATE '2024-01-01'"
                + " + INTERVAL '36' HOUR, TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '1' MONTHS,"
                + " DATE '2024-01-01' + INTERVAL '1.5' DAY, INTERVAL '1' DAY, 2 * INTERVAL '1 day'")
                .schema().types().toString());
        assertEquals("No operator - for INTERVAL and DATE at line 1, column 8",
                error("SELECT INTERVAL '1' DAY - DATE '2024-01-01'").getMessage());
        assertEquals("Could not convert string '1 2:00' to INTERVAL at line 1, column 8",
                error("SELECT INTERVAL '1 2:00'").getMessage());
    }

    @Test
    void usingMergesThePairOfColumns() {
        PlanNode plan = bind("SELECT * FROM t FULL JOIN u USING (a)");
        assertEquals("[a BIGINT, b VARCHAR, e TINYINT, f VARCHAR]", plan.schema().fields().toString());
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, plan);
        assertInstanceOf(Expression.Coalesce.class, top.columns().get(0).expression());
        assertEquals("[a INTEGER, a BIGINT]", bind("SELECT t.a, u.a FROM t JOIN u USING (a)").schema().fields()
                .toString());
    }

    @Test
    void anAggregationComputesEachCallOnce() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT b, sum(a), sum(a) + 1, count(*) FROM t GROUP BY ALL HAVING sum(a) > 2"));
        PlanNode.Filter having = assertInstanceOf(PlanNode.Filter.class, top.input());
        PlanNode.Aggregate aggregate = assertInstanceOf(PlanNode.Aggregate.class, having.input());
        assertEquals(List.of(0), aggregate.groupColumns());
        assertEquals(2, aggregate.aggregates().size());
        assertEquals("[b VARCHAR, sum(a) HUGEINT, (sum(a) + 1) HUGEINT, count_star() BIGINT]",
                top.schema().fields().toString());
    }

    @Test
    void theFilterOfAnAggregateIsAColumnOfItsInput() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT b, count(*) FILTER (WHERE a > 1),"
                + " sum(a) FILTER (WHERE a > 1), sum(a), sum(a) FILTER (WHERE e) FROM t GROUP BY b"));
        PlanNode.Aggregate aggregate = assertInstanceOf(PlanNode.Aggregate.class, top.input());
        PlanNode.Project below = assertInstanceOf(PlanNode.Project.class, aggregate.input());
        // The filter is read once by both calls that take it, and differs from the call without it.
        List<PlanNode.AggregateCall> calls = aggregate.aggregates();
        assertEquals(4, calls.size());
        assertEquals(1, calls.get(0).filter());
        assertEquals(1, calls.get(1).filter());
        assertEquals(-1, calls.get(2).filter());
        assertEquals(DataType.BOOLEAN, below.schema().type(calls.get(3).filter()));
        assertEquals("[b VARCHAR, count_star() FILTER (WHERE (a > 1)) BIGINT, sum(a) FILTER (WHERE (a > 1)) HUGEINT,"
                + " sum(a) HUGEINT, sum(a) FILTER (WHERE e) HUGEINT]", top.schema().fields().toString());
        assertEquals("aggregate function calls cannot be nested",
                error("SELECT sum(a) FILTER (WHERE sum(a) > 1) FROM t").reason());
        assertEquals("Function \"abs\" is a Scalar Function. \"DISTINCT\", \"FILTER\", and \"ORDER BY\" are only"
                + " applicable to aggregate functions.", error("SELECT abs(a) FILTER (WHERE a > 1) FROM t").reason());
    }

    @Test
    void orderByReadsColumnsThatAreNotSelected() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT a FROM t ORDER BY e DESC, 1"));
        PlanNode.Sort sort = assertInstanceOf(PlanNode.Sort.class, top.input());
        assertEquals(List.of(PlanNode.SortKey.desc(1), PlanNode.SortKey.asc(0)), sort.keys());
        assertEquals(1, top.schema().size());
        assertEquals(2, sort.schema().size());
    }

    @Test
    void limitsAreConstants() {
        assertInstanceOf(PlanNode.Project.class, bind("SELECT a FROM t LIMIT NULL"));
        assertEquals(new PlanNode.Limit(bind("SELECT a FROM t"), 2, 1), bind("SELECT a FROM t LIMIT 1 + 1 OFFSET '1'"));
        assertEquals("LIMIT/OFFSET cannot be negative", error("SELECT a FROM t LIMIT -1").reason());
    }

    @Test
    void currentDateIsTheTimeOfTheBindingInTheTimeZone() {
        Instant now = Instant.parse("2024-02-29T23:30:00Z");
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, BINDER.bind(SqlParser.parse(
                "SELECT current_date, now(), current_time, localtimestamp, current_setting('TimeZone')"), now,
                ZoneId.of("Europe/Berlin")));
        assertEquals(new Expression.Literal(DataType.DATE, (int) LocalDate.of(2024, 3, 1).toEpochDay()),
                p.columns().get(0).expression());
        assertEquals(new Expression.Literal(DataType.TIMESTAMPTZ, now.getEpochSecond() * 1_000_000L),
                p.columns().get(1).expression());
        assertEquals(new Expression.Literal(DataType.TIME, 1_800_000_000L), p.columns().get(2).expression());
        assertEquals(DataType.TIMESTAMP, p.columns().get(3).expression().type());
        assertEquals(new Expression.Literal(DataType.VARCHAR, "Europe/Berlin"), p.columns().get(4).expression());
    }

    @Test
    void errorsNameTheirPosition() {
        assertEquals("Referenced column \"x\" not found in FROM clause! at line 1, column 14",
                error("SELECT a, b, x FROM t").getMessage());
        assertEquals(new Position(1, 23), error("SELECT a FROM t WHERE count(*) > 1").position());
        assertEquals(new Position(2, 6), error("SELECT a\nFROM nosuch").position());
    }

    @Test
    void windowFunctionsAreRejectedWhereTheyCannotGo() {
        SqlException e = error("SELECT a FROM t WHERE 1 + rank() OVER () > 1");
        assertEquals("WHERE clause cannot contain window functions!", e.reason());
        assertEquals(new Position(1, 27), e.position());
        assertEquals("WHERE clause cannot contain window functions!",
                error("SELECT t.a FROM t JOIN u ON t.a = row_number() OVER ()").reason());
        assertEquals("GROUP BY clause cannot contain window functions!",
                error("SELECT count(*) FROM t GROUP BY rank() OVER ()").reason());
        assertEquals("aggregate function calls cannot contain window function calls",
                error("SELECT sum(rank() OVER ()) FROM t").reason());
        assertEquals("VALUES cannot contain window functions", error("VALUES (rank() OVER ())").reason());
        assertEquals("HAVING clause cannot contain window functions!",
                error("SELECT b FROM t GROUP BY b HAVING rank() OVER () > 1").reason());
        e = error("SELECT lag(1 + rank() OVER ()) OVER () FROM t");
        assertEquals("window function calls cannot be nested", e.reason());
        assertEquals(new Position(1, 16), e.position());
        assertEquals("window functions are not allowed in window definitions",
                error("SELECT rank() OVER (ORDER BY rank() OVER ()) FROM t").reason());
        assertEquals("Cannot group on a window clause", error("SELECT b, a + rank() OVER () FROM t GROUP BY ALL")
                .reason());
        assertEquals("at least one window function must appear in the SELECT column or QUALIFY clause",
                error("SELECT a FROM t QUALIFY a > 1").reason());
        assertEquals("window \"w\" does not exist", error("SELECT rank() OVER w FROM t WINDOW v AS ()").reason());
        assertEquals("RANGE frames must have only one ORDER BY expression",
                error("SELECT sum(a) OVER (ORDER BY a, b RANGE 1 PRECEDING) FROM t").reason());
        assertEquals("FILTER is not implemented for non-aggregate window functions!",
                error("SELECT rank() FILTER (WHERE a > 1) OVER () FROM t").reason());
        assertEquals("NTH_VALUE needs 2 parameters, got 1", error("SELECT nth_value(a) OVER () FROM t").reason());
        assertEquals("upper is not an aggregate function", error("SELECT upper(b) OVER () FROM t").reason());
        assertEquals("Not supported: a frame offset that is not a constant",
                error("SELECT sum(a) OVER (ROWS e PRECEDING) FROM t").reason());
        e = error("SELECT a FROM t WHERE EXISTS (SELECT rank() OVER () FROM u WHERE u.a = t.a)");
        assertEquals("Not supported: a window function in a correlated subquery", e.reason());
        assertEquals(new Position(1, 38), e.position());
    }

    @Test
    void theCallsOfAWindowShareANodeOverAProjectionOfWhatTheyRead() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT a, row_number() OVER (PARTITION"
                + " BY b ORDER BY a), sum(e + 1) OVER (PARTITION BY b ORDER BY a), rank() OVER (ORDER BY e DESC)"
                + " FROM t"));
        PlanNode.Window second = assertInstanceOf(PlanNode.Window.class, top.input());
        PlanNode.Window first = assertInstanceOf(PlanNode.Window.class, second.input());
        PlanNode.Project below = assertInstanceOf(PlanNode.Project.class, first.input());
        assertInstanceOf(PlanNode.Scan.class, below.input());
        // The projection passes a, b and e on and computes e + 1 after them.
        assertEquals(4, below.columns().size());
        assertEquals(List.of(1), first.partition());
        assertEquals(List.of(PlanNode.SortKey.asc(0)), first.order());
        assertEquals(List.of(PlanNode.WindowFunction.ROW_NUMBER, PlanNode.WindowFunction.AGGREGATE),
                first.calls().stream().map(PlanNode.WindowCall::function).toList());
        assertEquals(3, first.calls().get(1).aggregate().column());
        assertEquals(List.of(), second.partition());
        assertEquals(List.of(PlanNode.SortKey.desc(2)), second.order());
        assertEquals(List.of(new Expression.Column(0, DataType.INTEGER), new Expression.Column(4, DataType.BIGINT),
                new Expression.Column(5, DataType.HUGEINT), new Expression.Column(6, DataType.BIGINT)),
                top.columns().stream().map(PlanNode.NamedExpression::expression).toList());
    }

    @Test
    void aCallIsComputedOnceForTheSelectListQualifyAndTheOrder() {
        PlanNode.Sort sort = assertInstanceOf(PlanNode.Sort.class, bind("SELECT a, row_number() OVER w AS rn FROM t"
                + " WINDOW w AS (ORDER BY a) QUALIFY rn > 1 ORDER BY row_number() OVER w DESC"));
        assertEquals(List.of(PlanNode.SortKey.desc(1)), sort.keys());
        PlanNode.Project project = assertInstanceOf(PlanNode.Project.class, sort.input());
        PlanNode.Filter qualify = assertInstanceOf(PlanNode.Filter.class, project.input());
        assertEquals("Comparison[op=GT, left=Column[index=3, type=BIGINT], right=Literal[type=INTEGER, value=1]]",
                qualify.condition().toString());
        PlanNode.Window window = assertInstanceOf(PlanNode.Window.class, qualify.input());
        assertEquals(1, window.calls().size());
        assertInstanceOf(PlanNode.Scan.class, window.input());
    }

    @Test
    void aWindowOverAnAggregationReadsItsGroupsAndAggregates() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT b, sum(sum(e)) OVER (ORDER BY b), rank() OVER (ORDER BY count(*) DESC) FROM t"
                        + " GROUP BY b"));
        PlanNode.Window second = assertInstanceOf(PlanNode.Window.class, top.input());
        PlanNode.Window first = assertInstanceOf(PlanNode.Window.class, second.input());
        PlanNode.Aggregate aggregate = assertInstanceOf(PlanNode.Aggregate.class, first.input());
        assertEquals(2, aggregate.aggregates().size());
        assertEquals(List.of(PlanNode.SortKey.asc(0)), first.order());
        assertEquals(1, first.calls().get(0).aggregate().column());
        assertEquals(List.of(PlanNode.SortKey.desc(2)), second.order());
    }

    @Test
    void frameOffsetsAreConstantsOfTheTypesDuckDbComputesWith() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT sum(a) OVER (ORDER BY e RANGE"
                + " BETWEEN 1 PRECEDING AND 300 FOLLOWING), count(*) OVER (ORDER BY a ROWS 2.5 PRECEDING),"
                + " count(*) OVER (ORDER BY DATE '2024-01-01' + a RANGE BETWEEN 2 PRECEDING AND INTERVAL 1 MONTH"
                + " FOLLOWING), lag(a, 1.5, '7') OVER (ORDER BY a), rank() OVER (ORDER BY a ROWS 1 PRECEDING) FROM t"));
        // The windows by e, by a and by the date, with the calls by a together: count, lag and rank.
        List<PlanNode.WindowCall> calls = new ArrayList<>();
        for (PlanNode n = top.input(); n instanceof PlanNode.Window w; n = w.input()) {
            calls.addAll(0, w.calls());
        }
        // An integer takes the type of the key if it fits, as key - offset does.
        PlanNode.Frame range = calls.get(0).frame();
        assertEquals(new Expression.Literal(DataType.TINYINT, (byte) 1), range.start().offset());
        assertEquals(new Expression.Literal(DataType.INTEGER, 300), range.end().offset());
        // Rows are counted in whole numbers, rounded.
        assertEquals(new Expression.Literal(DataType.BIGINT, 3L), calls.get(1).frame().start().offset());
        // A number of days, or an interval, over a date.
        PlanNode.Frame dates = calls.get(4).frame();
        assertEquals(new PlanNode.FrameBound(PlanNode.BoundKind.PRECEDING, new Expression.Literal(DataType.BIGINT, 2L),
                PlanNode.IntervalUnit.DAY), dates.start());
        assertEquals(new PlanNode.FrameBound(PlanNode.BoundKind.FOLLOWING, new Expression.Literal(DataType.BIGINT, 1L),
                PlanNode.IntervalUnit.MONTH), dates.end());
        // The offset of lag is an integer, and its default takes the type of the value.
        Schema in = assertInstanceOf(PlanNode.Window.class, top.input()).input().schema();
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT, DataType.INTEGER),
                calls.get(2).arguments().stream().map(in::type).toList());
        // A function that takes no frame ignores the one it is given.
        assertEquals(PlanNode.Frame.DEFAULT, calls.get(3).frame());
    }

    @Test
    void tableFunctionsTakeConstants() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT * FROM READ_X('f' || '.csv', k = 1 + 1, Q = true) AS r WHERE r.a IS NULL"));
        PlanNode.Scan scan = assertInstanceOf(PlanNode.Scan.class,
                assertInstanceOf(PlanNode.Filter.class, top.input()).input());
        assertEquals(TableRef.of("s", "read_x[f.csv]{k=2, q=true}"), scan.table());
        assertTrue(error("SELECT * FROM read_x(a)").reason().startsWith("Referenced column \"a\" was not found"));
    }

    @Test
    void theLongestPrefixThatNamesAColumnWinsAndTheRestAreFields() {
        PlanNode.Project field = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT s.a FROM (SELECT {'a': 1} AS s) s"));
        assertTrue(field.columns().get(0).expression().toString().contains("StructExtract[index=0, field=a]"),
                "the table s has no column a, so a is a field of the column s");
        PlanNode.Project column = assertInstanceOf(PlanNode.Project.class,
                bind("SELECT t.a FROM (SELECT {'a': 1} AS t, 5 AS a) t"));
        assertEquals(DataType.INTEGER, column.schema().type(0));
        assertTrue(column.columns().get(0).expression() instanceof Expression.Column,
                "t.a is the column a of the table t");
        PlanNode deep = bind("SELECT x.B.c, t.x.b['C'] FROM (SELECT {'b': {'c': 2.5}} AS x) t");
        assertEquals(List.of(DataType.decimal(2, 1), DataType.decimal(2, 1)), deep.schema().types());
        assertEquals(List.of("c", "t.x.b['C']"), deep.schema().fields().stream().map(Schema.Field::name).toList());
        assertEquals("Cannot extract field 'b' from expression \"a\" because it is not a struct, union, map, or json",
                error("SELECT a.b.c FROM t").reason());
        assertEquals("Referenced table \"nope\" not found! Candidate tables: \"t\"",
                error("SELECT nope.a.b FROM t").reason());
        assertEquals("Could not find key \"z\" in struct; candidate entries: \"a\"",
                error("SELECT s.z FROM (SELECT {'a': 1} AS s)").reason());
    }

    @Test
    void aFieldOfAGroupedColumnIsGrouped() {
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT),
                bind("SELECT s.a, count(*) FROM (SELECT {'a': 1} AS s) GROUP BY s").schema().types());
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT),
                bind("SELECT s.a, count(*) FROM (SELECT {'a': 1} AS s) GROUP BY s.a").schema().types());
        assertTrue(error("SELECT s.b, count(*) FROM (SELECT {'a': 1, 'b': 2} AS s) GROUP BY s.a").reason()
                .startsWith("column \"s\" must appear in the GROUP BY clause"));
    }

    @Test
    void unnestAddsTheElementsAfterTheRowsThatReachTheSelectList() {
        PlanNode.Project select = assertInstanceOf(PlanNode.Project.class, bind("SELECT a, unnest([a, e]) + 1 FROM t"));
        PlanNode.Unnest unnest = assertInstanceOf(PlanNode.Unnest.class, select.input());
        assertInstanceOf(PlanNode.Scan.class, unnest.input());
        assertEquals(DataType.listOf(DataType.INTEGER), unnest.list().type());
        assertTrue(select.columns().get(1).expression().toString().contains("Column[index=3"),
                select.columns().get(1).toString());

        // The ORDER BY adds an aggregate after the select list is bound; the elements follow it.
        PlanNode grouped = bind("SELECT a, count(*), unnest([1, 2]) FROM t GROUP BY a ORDER BY max(e)");
        PlanNode.Unnest after = (PlanNode.Unnest) net.fyrql.core.plan.Plans.nodes(grouped).stream()
                .filter(n -> n instanceof PlanNode.Unnest).findFirst().orElseThrow();
        assertInstanceOf(PlanNode.Aggregate.class, after.input());
        assertEquals(3, after.input().schema().size());
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT, DataType.INTEGER), grouped.schema().types());

        PlanNode.Unnest lateral = assertInstanceOf(PlanNode.Unnest.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT x FROM t, unnest([t.a, t.a * 2]) AS u(x)")).input());
        assertInstanceOf(PlanNode.Scan.class, lateral.input());
        assertEquals("x", lateral.name());
        PlanNode.Unnest alone = assertInstanceOf(PlanNode.Unnest.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT * FROM unnest([1, 2])")).input());
        assertInstanceOf(PlanNode.Values.class, alone.input());
        assertEquals(List.of("p", "q"), bind("SELECT unnest({'p': 1, 'q': 'z'}) AS ignored FROM t").schema().fields()
                .stream().map(Schema.Field::name).toList());
        assertEquals("Not supported: UNNEST of more than one list in a select list",
                error("SELECT unnest([a]), unnest([e]) FROM t").reason());
        assertEquals("UNNEST not supported here", error("SELECT a FROM t WHERE unnest([a]) = 1").reason());
    }

    @Test
    void aSeriesInFromUnnestsTheListOfItsFunction() {
        PlanNode.Unnest alone = assertInstanceOf(PlanNode.Unnest.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT range FROM range(1, 4)")).input());
        assertInstanceOf(PlanNode.Values.class, alone.input());
        assertEquals("range", alone.name());
        assertEquals(DataType.BIGINT, alone.elementType());
        assertEquals(List.of("i"), bind("SELECT * FROM generate_series(1, 3) g(i) WHERE g.i > 1").schema().fields()
                .stream().map(Schema.Field::name).toList());
        PlanNode.Unnest lateral = assertInstanceOf(PlanNode.Unnest.class, assertInstanceOf(PlanNode.Project.class,
                bind("SELECT a, generate_series FROM t, LATERAL generate_series(1, t.e)")).input());
        assertInstanceOf(PlanNode.Scan.class, lateral.input());
        assertEquals(DataType.listOf(DataType.TIMESTAMP), assertInstanceOf(PlanNode.Unnest.class, bind(
                "SELECT * FROM range(DATE '2024-01-01', DATE '2024-02-01', INTERVAL 1 WEEK)").children().get(0))
                .list().type());
        assertEquals("interval cannot be 0!", error("SELECT * FROM range(1, 5, 0)").reason());
    }

    @Test
    void groupingSetsAreAUnionOfAnAggregationPerSet() {
        PlanNode plan = bind("SELECT b, e, count(*), grouping(b, e) FROM t GROUP BY ROLLUP (b, e)");
        PlanNode.Union union = nodes(plan, PlanNode.Union.class).get(0);
        assertEquals(3, union.inputs().size());
        List<List<Integer>> groups = new ArrayList<>();
        List<Expression> bits = new ArrayList<>();
        for (PlanNode input : union.inputs()) {
            PlanNode.Project p = assertInstanceOf(PlanNode.Project.class, input);
            groups.add(assertInstanceOf(PlanNode.Aggregate.class, p.input()).groupColumns());
            bits.add(p.columns().get(3).expression());
            // Each set reads the rows of the table anew.
            assertInstanceOf(PlanNode.Scan.class, assertInstanceOf(PlanNode.Project.class,
                    ((PlanNode.Aggregate) p.input()).input()).input());
        }
        assertEquals(List.of(List.of(0, 1), List.of(0), List.of()), groups);
        assertEquals(List.of(new Expression.Literal(DataType.BIGINT, 0L), new Expression.Literal(DataType.BIGINT, 1L),
                new Expression.Literal(DataType.BIGINT, 3L)), bits);
        assertEquals(new Expression.Literal(DataType.TINYINT, null),
                ((PlanNode.Project) union.inputs().get(1)).columns().get(1).expression());
        assertEquals("[b VARCHAR, e TINYINT, count_star() BIGINT, GROUPING(b, e) BIGINT]",
                plan.schema().fields().toString());
        // Rows that compute something run once, as a common table of the query.
        PlanNode joined = bind("SELECT t.b, count(*) FROM t JOIN u ON t.a = u.a GROUP BY CUBE (t.b)");
        assertEquals(1, commonTables(joined).size());
        assertEquals(2, nodes(joined, PlanNode.CommonTableScan.class).size());
        assertEquals("GROUPING child \"a\" must be a grouping column",
                error("SELECT b, grouping(a) FROM t GROUP BY ROLLUP (b)").reason());
    }

    /** The nodes of a plan of a type, from the top down. */
    private static <T extends PlanNode> List<T> nodes(PlanNode plan, Class<T> type) {
        return net.fyrql.core.plan.Plans.nodes(plan).stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void distinctOnNumbersTheRowsOfEachKeyInTheOrder() {
        PlanNode plan = bind("SELECT DISTINCT ON (b) a, b FROM t ORDER BY b, e DESC");
        assertEquals(2, plan.schema().size());
        PlanNode.Window window = nodes(plan, PlanNode.Window.class).get(0);
        assertEquals(List.of(1), window.partition());
        assertEquals(List.of(PlanNode.SortKey.asc(1), PlanNode.SortKey.desc(2)), window.order());
        assertEquals(PlanNode.WindowFunction.ROW_NUMBER, window.calls().get(0).function());
        assertInstanceOf(PlanNode.Filter.class, nodes(plan, PlanNode.Sort.class).get(0).input());
        assertEquals("Not supported: DISTINCT ON in a correlated subquery",
                error("SELECT a FROM t WHERE EXISTS (SELECT DISTINCT ON (f) f FROM u WHERE u.a = t.a)").reason());
    }

    @Test
    void anyAndAllAreJoinsAndRowsCompareByAllTheirValues() {
        // An uncorrelated ordered comparison reads the counts and extremes of the subquery from one row.
        PlanNode.Join single = nodes(bind("SELECT a > ANY (SELECT a FROM u) FROM t"), PlanNode.Join.class).get(0);
        assertEquals(PlanNode.JoinType.SINGLE, single.type());
        assertEquals(4, assertInstanceOf(PlanNode.Aggregate.class, single.right()).aggregates().size());
        // = ANY is IN, <> ALL is NOT IN; a correlated comparison takes two MARK joins.
        assertEquals(PlanNode.JoinType.SEMI, nodes(bind("SELECT a FROM t WHERE a = ANY (SELECT a FROM u)"),
                PlanNode.Join.class).get(0).type());
        List<PlanNode.Join> marks = nodes(bind("SELECT e < ALL (SELECT a FROM u WHERE u.f = t.b) FROM t"),
                PlanNode.Join.class);
        assertEquals(2, marks.size());
        assertTrue(marks.stream().allMatch(j -> j.type() == PlanNode.JoinType.MARK && j.leftKeys().size() == 1
                && j.condition() != null));
        assertEquals(PlanNode.JoinType.ANTI, nodes(bind("SELECT a FROM t WHERE e < ALL (SELECT a FROM u"
                + " WHERE u.f = t.b)"), PlanNode.Join.class).get(0).type());
        // A row is a join on all its values.
        PlanNode.Join semi = nodes(bind("SELECT a FROM t WHERE (a, b) IN (SELECT a, f FROM u)"),
                PlanNode.Join.class).get(0);
        assertEquals(PlanNode.JoinType.SEMI, semi.type());
        assertEquals(2, semi.leftKeys().size());
        PlanNode.Join mark = nodes(bind("SELECT (e, b) NOT IN (SELECT a, f FROM u) FROM t"), PlanNode.Join.class)
                .get(0);
        assertEquals(PlanNode.JoinType.MARK, mark.type());
        assertEquals(List.of(0, 1), mark.rightKeys());
        assertEquals("Subquery returns 1 columns - expected 2",
                error("SELECT a FROM t WHERE (a, b) IN (SELECT a FROM u)").reason());
        assertEquals("Correlated IN/ANY/ALL with multiple columns not yet supported",
                error("SELECT a FROM t WHERE (a, b) IN (SELECT a, f FROM u WHERE u.a = t.a)").reason());
        assertEquals("Not supported: row values", error("SELECT (a, b) FROM t").reason());
    }

    /** The common tables of a plan, from the outermost one on. */
    private static List<PlanNode.CommonTable> commonTables(PlanNode plan) {
        return net.fyrql.core.plan.Plans.nodes(plan).stream().filter(n -> n instanceof PlanNode.CommonTable)
                .map(n -> (PlanNode.CommonTable) n).toList();
    }

    /** The scans of common tables of a plan, without those of the definitions that are scans themselves. */
    private static long commonTableScans(PlanNode plan) {
        return net.fyrql.core.plan.Plans.nodes(plan).stream().filter(n -> n instanceof PlanNode.CommonTableScan)
                .count();
    }

    private static long scans(PlanNode plan) {
        return net.fyrql.core.plan.Plans.nodes(plan).stream().filter(n -> n instanceof PlanNode.Scan).count();
    }

    @Test
    void aCommonTableThatComputesAndIsReadTwiceRunsOnce() {
        PlanNode plan = bind("WITH c AS (SELECT a, count(*) AS n FROM t GROUP BY a) "
                + "SELECT * FROM c WHERE n = (SELECT max(n) FROM c)");
        PlanNode.CommonTable table = assertInstanceOf(PlanNode.CommonTable.class, plan);
        assertEquals("c", table.name());
        assertTrue(net.fyrql.core.plan.Plans.nodes(table.definition()).stream()
                .anyMatch(n -> n instanceof PlanNode.Aggregate));
        assertEquals(1, scans(plan));
        assertEquals(2, commonTableScans(plan));
        assertEquals(List.of(DataType.INTEGER, DataType.BIGINT), plan.schema().types());

        // A join, a sort with a limit, a window and a set operation compute too.
        for (String query : List.of("SELECT t.a FROM t JOIN u ON t.a = u.a", "SELECT a FROM t ORDER BY a LIMIT 2",
                "SELECT a, rank() OVER (ORDER BY e) FROM t", "SELECT a FROM t UNION SELECT a FROM t",
                "SELECT a FROM t EXCEPT SELECT a FROM t", "SELECT DISTINCT a FROM t")) {
            PlanNode twice = bind("WITH c AS (" + query + ") SELECT * FROM c, c AS d");
            assertEquals(1, commonTables(twice).size(), query);
        }
    }

    @Test
    void aCommonTableThatOnlyReadsOrIsReadOnceTakesThePlaceOfItsScans() {
        // Each scan keeps its filters in the source.
        PlanNode filtered = bind("WITH c AS (SELECT a, b FROM t WHERE e > 1) SELECT * FROM c, c AS d WHERE c.a = 1");
        assertEquals(List.of(), commonTables(filtered));
        assertEquals(0, commonTableScans(filtered));
        assertEquals(2, scans(filtered));
        PlanNode once = bind("WITH c AS (SELECT a, count(*) FROM t GROUP BY a) SELECT * FROM c");
        assertEquals(List.of(), commonTables(once));
        assertEquals(1, scans(once));
        PlanNode unread = bind("WITH c AS (SELECT a, count(*) FROM t GROUP BY a) SELECT 1");
        assertEquals(0, scans(unread));
        assertEquals(0, commonTableScans(unread));
    }

    @Test
    void materializedAndNotMaterializedDecide() {
        PlanNode always = bind("WITH c AS MATERIALIZED (SELECT a FROM t) SELECT * FROM c");
        assertEquals(1, scans(assertInstanceOf(PlanNode.CommonTable.class, always).definition()));
        assertEquals(1, commonTableScans(always));
        PlanNode never = bind("WITH c AS NOT MATERIALIZED (SELECT a, count(*) FROM t GROUP BY a) "
                + "SELECT * FROM c, c AS d");
        assertEquals(List.of(), commonTables(never));
        assertEquals(2, scans(never));
    }

    @Test
    void aLaterCommonTableReadsAnEarlierOne() {
        // b runs once and reads a, which runs once too, around it; b's column list renames.
        PlanNode plan = bind("WITH a AS (SELECT a, count(*) AS n FROM t GROUP BY a), "
                + "b(x, y) AS (SELECT a.a, a.n FROM a JOIN a AS a2 ON a.a = a2.a) "
                + "SELECT * FROM b, b AS b2");
        List<PlanNode.CommonTable> tables = commonTables(plan);
        assertEquals(List.of("a", "b"), tables.stream().map(PlanNode.CommonTable::name).toList());
        assertEquals(tables.get(1), tables.get(0).input());
        assertEquals(1, scans(plan));
        assertEquals(4, commonTableScans(plan));
        assertEquals(List.of("x", "y", "x", "y"), plan.schema().fields().stream().map(Schema.Field::name).toList());

        // b is read once and goes in place of its scan; a is then read twice and runs once.
        PlanNode inlined = bind("WITH a AS (SELECT a, count(*) AS n FROM t GROUP BY a), "
                + "b AS (SELECT a.a FROM a JOIN a AS a2 ON a.a = a2.a) SELECT * FROM b");
        assertEquals(List.of("a"), commonTables(inlined).stream().map(PlanNode.CommonTable::name).toList());
        assertEquals(2, commonTableScans(inlined));

        // b reads a once and is read twice: a is read twice in the end, once in each plan of b.
        PlanNode twice = bind("WITH a AS (SELECT a, count(*) AS n FROM t GROUP BY a), b AS (SELECT a FROM a) "
                + "SELECT * FROM b, b AS b2");
        assertEquals(List.of("a"), commonTables(twice).stream().map(PlanNode.CommonTable::name).toList());
        assertEquals(2, commonTableScans(twice));
    }

    @Test
    void aCommonTableOfASubqueryInAnExpressionTakesThePlaceOfItsScans() {
        PlanNode plan = bind("SELECT a FROM t WHERE a IN (WITH c AS (SELECT a, count(*) AS n FROM u GROUP BY a) "
                + "SELECT c.a FROM c JOIN c AS d ON c.a = d.a)");
        assertEquals(List.of(), commonTables(plan));
        assertEquals(3, scans(plan));
        // One of the query around it is read from the subquery as well.
        PlanNode outer = bind("WITH c AS (SELECT a, count(*) AS n FROM t GROUP BY a) "
                + "SELECT * FROM c WHERE EXISTS (SELECT 1 FROM c AS d WHERE d.a = c.a AND d.n > 1)");
        assertEquals(1, commonTables(outer).size());
        assertEquals(2, commonTableScans(outer));
    }

    @Test
    void theScansOfACommonTableKeepItsBareNulls() {
        // The NULL of the common table takes the type of the other side of the union, as it does without one.
        PlanNode plan = bind("WITH c AS (SELECT DISTINCT NULL AS x FROM t) SELECT x FROM c UNION ALL "
                + "SELECT 'a' FROM c");
        assertEquals(List.of(DataType.VARCHAR), plan.schema().types());
        assertEquals(1, commonTables(plan).size());
    }
}
