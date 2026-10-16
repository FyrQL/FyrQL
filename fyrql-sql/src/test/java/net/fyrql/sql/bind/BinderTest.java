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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    void anIntervalAddedToAConstantDateIsFoldedWhenBinding() {
        PlanNode.Project top = assertInstanceOf(PlanNode.Project.class, bind("SELECT DATE '1998-12-01' - INTERVAL"
                + " '90' DAY, DATE '2024-01-31' + INTERVAL 1 MONTH, DATE '2024-01-01' + INTERVAL '36' HOUR,"
                + " TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '1' MONTHS, CAST('2024-02-28' AS DATE) + INTERVAL '2' DAYS,"
                + " CAST(NULL AS DATE) + INTERVAL '1' DAY"));
        List<Object> values = new ArrayList<>();
        for (PlanNode.NamedExpression c : top.columns()) {
            Expression.Literal l = assertInstanceOf(Expression.Literal.class, c.expression());
            values.add(l.value() == null ? null : l.type() == DataType.DATE
                    ? LocalDate.ofEpochDay((Integer) l.value()).toString() : Conversions.toLocalDateTime((Long) l.value())
                    .toString());
        }
        assertEquals(Arrays.asList("1998-09-02", "2024-02-29", "2024-01-02T12:00", "2024-02-29T10:00", "2024-03-01", null),
                values);
        assertEquals("[DATE, DATE, TIMESTAMP, TIMESTAMP, DATE, DATE]", top.schema().types().toString());
        String notSupported = "Not supported: INTERVAL except as a constant added to a date at line 1, column ";
        assertEquals(notSupported + "12", error("SELECT a + INTERVAL '1' DAY FROM t").getMessage());
        assertEquals(notSupported + "8", error("SELECT INTERVAL '1' DAY").getMessage());
        assertEquals("[DATE]", bind("SELECT INTERVAL '1' DAY + DATE '2024-01-01'").schema().types().toString());
        assertEquals(notSupported + "8", error("SELECT INTERVAL '1' DAY - DATE '2024-01-01'").getMessage());
        assertEquals(notSupported + "12", error("SELECT 1 + INTERVAL '1' DAY").getMessage());
        assertEquals("Not supported: the interval unit WEEK at line 1, column 28",
                error("SELECT DATE '2024-01-01' + INTERVAL '1' WEEK").getMessage());
        assertEquals("The interval '1.5' is not a whole number at line 1, column 28",
                error("SELECT DATE '2024-01-01' + INTERVAL '1.5' DAY").getMessage());
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
    void currentDateIsTheTimeOfTheBinding() {
        LocalDateTime now = LocalDateTime.of(2024, 2, 29, 13, 14, 15);
        PlanNode.Project p = assertInstanceOf(PlanNode.Project.class,
                BINDER.bind(SqlParser.parse("SELECT current_date, now()"), now));
        assertEquals(new Expression.Literal(DataType.DATE, (int) LocalDate.of(2024, 2, 29).toEpochDay()),
                p.columns().get(0).expression());
        assertEquals(DataType.TIMESTAMP, p.columns().get(1).expression().type());
    }

    @Test
    void errorsNameTheirPosition() {
        assertEquals("Referenced column \"x\" not found in FROM clause! at line 1, column 14",
                error("SELECT a, b, x FROM t").getMessage());
        assertEquals(new Position(1, 23), error("SELECT a FROM t WHERE count(*) > 1").position());
        assertEquals(new Position(2, 6), error("SELECT a\nFROM nosuch").position());
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
}
