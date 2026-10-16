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

package net.fyrql.optimizer;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RemotePlan;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.Filter;
import net.fyrql.core.plan.PlanNode.Join;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.NamedExpression;
import net.fyrql.core.plan.PlanNode.Scan;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.conjunction;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.isNotNull;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The order of inner joins, chosen by the estimated rows of a star of
 * memory tables with known row counts: a fact table {@code f} of 200 rows
 * and the dimensions {@code d1} (20 rows), {@code d2} (5 rows) and
 * {@code d3} (3 rows). The tables {@code l} (400 rows), {@code ps} (200),
 * {@code p} (100) and {@code s} (50) have the shape of the tables of TPC-H
 * Q9: {@code l} refers to {@code ps} by part and supplier, {@code ps} to
 * {@code p} and {@code s}.
 */
class JoinOrderingTest {

    private static final Schema F = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("d1", DataType.INTEGER), new Schema.Field("d2", DataType.INTEGER),
            new Schema.Field("v", DataType.INTEGER));
    private static final Schema D1 = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("name", DataType.VARCHAR));
    private static final Schema D2 = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("g", DataType.INTEGER));
    private static final Schema D3 = Schema.of(new Schema.Field("g", DataType.INTEGER),
            new Schema.Field("label", DataType.VARCHAR));
    private static final Schema L = Schema.of(new Schema.Field("part", DataType.INTEGER),
            new Schema.Field("supplier", DataType.INTEGER), new Schema.Field("quantity", DataType.INTEGER));
    private static final Schema PS = Schema.of(new Schema.Field("part", DataType.INTEGER),
            new Schema.Field("supplier", DataType.INTEGER), new Schema.Field("cost", DataType.INTEGER));
    private static final Schema P = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("name", DataType.VARCHAR));
    private static final Schema S = Schema.of(new Schema.Field("id", DataType.INTEGER));
    private static final MemoryConnector MEMORY = star();
    private static final Executor EXECUTOR = Tables.executor(MEMORY);

    private static MemoryConnector star() {
        List<Object[]> f = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            f.add(new Object[] {i, i % 20, i % 7 == 0 ? null : i % 5, i % 7});
        }
        List<Object[]> d1 = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            d1.add(new Object[] {i, i % 10 == 0 ? "x" : "y" + i});
        }
        List<Object[]> d2 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            d2.add(new Object[] {i, i % 2});
        }
        List<Object[]> d3 = List.of(new Object[] {0, "even"}, new Object[] {1, "odd"}, new Object[] {2, "none"});
        // Each part has two suppliers, and each row of l one of the two.
        List<Object[]> l = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            l.add(new Object[] {i % 100, (i % 100 + i / 100 % 2 * 25) % 50, i});
        }
        List<Object[]> ps = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ps.add(new Object[] {i % 100, (i % 100 + i / 100 * 25) % 50, i});
        }
        List<Object[]> p = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            p.add(new Object[] {i, (i % 10 == 0 ? "green " : "red ") + i});
        }
        List<Object[]> s = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            s.add(new Object[] {i});
        }
        return MemoryConnector.builder("mem").batchSize(16).table("f", F, f).table("d1", D1, d1).table("d2", D2, d2)
                .table("d3", D3, d3).table("l", L, l).table("ps", PS, ps).table("p", P, p).table("s", S, s).build();
    }

    private static Scan scan(String table, Schema schema) {
        return Scan.all(TableRef.of("mem", table), schema);
    }

    private static Scan f() {
        return scan("f", F);
    }

    private static Scan d1() {
        return scan("d1", D1);
    }

    private static Scan d2() {
        return scan("d2", D2);
    }

    private static Scan d3() {
        return scan("d3", D3);
    }

    private static Statistics statistics() {
        return new Statistics(name -> MEMORY);
    }

    /** Orders a plan and checks that the types, the rows and a second pass stay the same. */
    private static PlanNode ordered(PlanNode plan) {
        PlanNode result = JoinOrdering.order(plan, statistics());
        assertEquals(plan.schema().types(), result.schema().types());
        assertEquals(names(plan.schema()), names(result.schema()));
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, result)),
                Plans.print(result));
        assertEquals(result, JoinOrdering.order(result, statistics()), "a second pass changes nothing");
        return result;
    }

    private static List<String> names(Schema schema) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            names.add(schema.name(i));
        }
        return names;
    }

    /** The joins of a plan, with the tables as leaves, the filters and projections left out. */
    private static String shape(PlanNode node) {
        return switch (node) {
            case Scan s -> s.table().table();
            case Join j -> "(" + shape(j.left()) + (j.type() == JoinType.CROSS ? " x " : " " + j.type() + " ")
                    + shape(j.right()) + ")";
            default -> node.children().stream().map(JoinOrderingTest::shape).collect(Collectors.joining(", "));
        };
    }

    private static Join join(PlanNode left, PlanNode right, int leftKey, int rightKey) {
        return new Join(left, right, List.of(leftKey), List.of(rightKey), JoinType.INNER);
    }

    private static Join cross(PlanNode left, PlanNode right) {
        return new Join(left, right, List.of(), List.of(), JoinType.CROSS);
    }

    @Test
    void theSmallerInputIsBuilt() {
        // d1 JOIN f ON d1.id = f.d1: f has more rows, so it becomes the left input, and a projection restores the columns.
        PlanNode plan = join(d1(), f(), 0, 1);
        Join swapped = join(f(), d1(), 1, 0);
        List<NamedExpression> columns = new ArrayList<>();
        int[] positions = {4, 5, 0, 1, 2, 3};
        for (int i = 0; i < positions.length; i++) {
            columns.add(new NamedExpression(plan.schema().name(i), col(positions[i], plan.schema().type(i))));
        }
        assertEquals(new PlanNode.Project(swapped, columns), ordered(plan));

        // In the right order already, the plan stays as it is.
        assertSame(swapped, JoinOrdering.order(swapped, statistics()));
    }

    @Test
    void aFilteredDimensionIsJoinedFirst() {
        // d2 is the smallest table, but the filter leaves fewer rows of d1: f joins d1 first, then d2.
        Filter d1 = new Filter(d1(), eq(col(D1, 1), lit("x")));
        PlanNode plan = new Filter(cross(cross(d2(), d1), f()),
                and(eq(col(0, DataType.INTEGER), col(6, DataType.INTEGER)),
                        eq(col(2, DataType.INTEGER), col(5, DataType.INTEGER))));
        assertEquals("((f INNER d1) INNER d2)", shape(ordered(plan)));

        // Without the filter, both orders cost the same, and the first one found stays.
        PlanNode unfiltered = new Filter(cross(cross(d2(), d1()), f()),
                and(eq(col(0, DataType.INTEGER), col(6, DataType.INTEGER)),
                        eq(col(2, DataType.INTEGER), col(5, DataType.INTEGER))));
        assertEquals("((f INNER d2) INNER d1)", shape(ordered(unfiltered)));
    }

    @Test
    void aConditionOverTwoTablesStandsAboveTheLowestJoinWithBoth() {
        // f.v < d1.id needs f and d1, d1.id + d2.id < 10 needs d1 and d2, which meet only at the top.
        Expression fd1 = lt(col(3, DataType.INTEGER), col(4, DataType.INTEGER));
        Expression d1d2 = lt(add(col(4, DataType.INTEGER), col(6, DataType.INTEGER)), lit(10));
        PlanNode plan = new Filter(cross(cross(f(), new Filter(d1(), eq(col(D1, 1), lit("x")))), d2()),
                conjunction(List.of(eq(col(1, DataType.INTEGER), col(4, DataType.INTEGER)),
                        eq(col(2, DataType.INTEGER), col(6, DataType.INTEGER)), d1d2, fd1, gt(col(7,
                                DataType.INTEGER), lit(0)))));
        PlanNode result = ordered(plan);
        Filter top = (Filter) result;
        assertEquals(lt(add(col(4, DataType.INTEGER), col(6, DataType.INTEGER)), lit(10)), top.condition());
        Join outer = (Join) top.input();
        Filter below = (Filter) outer.left();
        assertEquals(fd1, below.condition());
        // The condition over d2 alone went to d2.
        assertEquals(new Filter(d2(), gt(col(1, DataType.INTEGER), lit(0))), outer.right());
    }

    @Test
    void tablesWithoutAnEqualityAreJoinedLastAsACrossProduct() {
        // d3 shares no equality with the others; d1 and d2 none with each other, so they meet only through f.
        Filter d1 = new Filter(d1(), eq(col(D1, 1), lit("x")));
        Filter d2 = new Filter(d2(), eq(col(D2, 1), lit(1)));
        PlanNode plan = new Filter(cross(cross(cross(d3(), d1), d2), f()),
                and(eq(col(2, DataType.INTEGER), col(7, DataType.INTEGER)),
                        eq(col(4, DataType.INTEGER), col(8, DataType.INTEGER))));
        assertEquals("(((f INNER d1) INNER d2) x d3)", shape(ordered(plan)));
    }

    @Test
    void anEqualityThatFollowsFromTwoOthersIsAKeyToo() {
        // f.d1 = a.id and f.d1 = b.id, with d1 twice: a.id = b.id follows, and the two filtered scans of d1 meet first.
        Filter a = new Filter(d1(), eq(col(D1, 1), lit("x")));
        Filter b = new Filter(d1(), eq(col(D1, 1), lit("x")));
        PlanNode plan = new Filter(cross(cross(f(), a), b),
                and(eq(col(1, DataType.INTEGER), col(4, DataType.INTEGER)),
                        eq(col(1, DataType.INTEGER), col(6, DataType.INTEGER))));
        PlanNode result = ordered(plan);
        assertEquals("(f INNER (d1 INNER d1))", shape(result));
        assertEquals(join(f(), join(a, b, 0, 0), 1, 0), result);
    }

    @Test
    void twoTablesJoinedOnTwoKeysKeepTheRowsOfTheLargerOne() {
        // l JOIN ps ON part and supplier, ps JOIN p (filtered) ON part, ps JOIN s ON supplier: l has the columns
        // 0-2, ps 3-5, p 6-7 and s 8. Taken as unique on its own, each key would leave 400 * 200 / (100 * 50) = 16
        // rows of l joined with ps, fewer than the filtered p, and their join would be built: (s INNER (p INNER
        // (l INNER ps))). As a composite key of ps, the two keep the 400 rows of l, and l probes the smaller tables.
        Filter p = new Filter(scan("p", P), like(col(P, 1), lit("%green%")));
        PlanNode plan = new Filter(cross(cross(cross(scan("l", L), scan("ps", PS)), p), scan("s", S)),
                conjunction(List.of(eq(col(0, DataType.INTEGER), col(3, DataType.INTEGER)),
                        eq(col(1, DataType.INTEGER), col(4, DataType.INTEGER)),
                        eq(col(3, DataType.INTEGER), col(6, DataType.INTEGER)),
                        eq(col(4, DataType.INTEGER), col(8, DataType.INTEGER)))));
        assertEquals("(l INNER ((ps INNER p) INNER s))", shape(ordered(plan)));
    }

    @Test
    void theInputsOfOtherJoinsAreOrderedOnTheirOwn() {
        // The left join keeps its place; the inner join below it builds the smaller input.
        PlanNode inner = join(d2(), f(), 0, 2);
        PlanNode plan = new Join(inner, d3(), List.of(1), List.of(0), JoinType.LEFT);
        PlanNode result = ordered(plan);
        assertEquals("((f INNER d2) LEFT d3)", shape(result));
        assertEquals(JoinType.LEFT, ((Join) result).type());
    }

    @Test
    void aSemiJoinThatReadsOneRelationIsMovedOntoIt() {
        // (d2 JOIN f ON d2.id = f.d2) JOIN d1 ON f.d1 = d1.id: d2 has the columns 0-1, f 2-5 and d1 6-7.
        PlanNode component = join(join(d2(), f(), 0, 2), d1(), 3, 0);
        // A semi join on d1.id = d3.g AND d1.name <> d3.label reads only d1 on the left; an anti join on
        // d2.g = d3.g AND f.v < 3 reads two relations and stays above the component.
        Join semi = new Join(component, d3(), List.of(6), List.of(0), JoinType.SEMI,
                ne(col(7, DataType.VARCHAR), col(9, DataType.VARCHAR)));
        Join anti = new Join(semi, d3(), List.of(1), List.of(0), JoinType.ANTI, lt(col(5, DataType.INTEGER), lit(3)));
        PlanNode result = ordered(anti);
        assertEquals("(((f INNER (d1 SEMI d3)) INNER d2) ANTI d3)", shape(result));
        Join moved = (Join) Plans.nodes(result).stream().filter(n -> n instanceof Join j && j.type() == JoinType.SEMI)
                .findFirst().orElseThrow();
        assertEquals(List.of(0), moved.leftKeys());
        assertEquals(ne(col(1, DataType.VARCHAR), col(3, DataType.VARCHAR)), moved.condition());
    }

    @Test
    void largeComponentsAreJoinedGreedily() {
        // f and twelve scans of d2 on its id: more than the dynamic programming takes. The first join builds f;
        // the greedy order joins the scans of d2 with each other first and builds them.
        PlanNode plan = join(d2(), f(), 0, 2);
        for (int i = 1; i < 12; i++) {
            plan = join(plan, d2(), 0, 0);
        }
        PlanNode result = ordered(plan);
        assertEquals("(f INNER " + "(".repeat(11) + "d2 INNER d2)" + " INNER d2)".repeat(10) + ")", shape(result));
        assertFalse(shape(result).contains(" x "), shape(result));
        assertEquals(12, Plans.nodes(result).stream().filter(n -> n instanceof Join j && j.type() == JoinType.INNER)
                .count());
    }

    @Test
    void theOptimizerOrdersAfterItPushesAndATreeThatWasOrderedStays() {
        Filter d1 = new Filter(d1(), eq(col(D1, 1), lit("x")));
        PlanNode plan = new PlanNode.Project(new Filter(cross(cross(d2(), d1), f()),
                and(eq(col(0, DataType.INTEGER), col(6, DataType.INTEGER)),
                        eq(col(2, DataType.INTEGER), col(5, DataType.INTEGER)))),
                List.of(new NamedExpression("g", col(1, DataType.INTEGER)), new NamedExpression("v",
                        col(7, DataType.INTEGER))));
        Optimizer optimizer = new Optimizer(name -> MEMORY, Optimizer.Options.DEFAULT);
        PlanNode once = optimizer.optimize(plan);
        assertEquals(once, optimizer.optimize(once));
        assertEquals("((f INNER d1) INNER d2)", shape(once));
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, once)));
        Optimizer without = new Optimizer(name -> MEMORY,
                new Optimizer.Options(true, true, true, true, false, true, true));
        assertEquals("((d2 x d1) INNER f)", shape(without.optimize(plan)));
    }

    @Test
    void theTablesOfASourceThatRunsTheirJoinAreJoinedFirst() {
        // f and d1 in a database that runs joins, the filtered d2 in memory. Joined by the engine, f meets d2 first;
        // f JOIN d1 in the database brings only its 200 rows into the engine instead of 220 rows and a hash table.
        Scan f = Scan.all(TableRef.of("db", "f"), F);
        Scan d1 = Scan.all(TableRef.of("db", "d1"), D1);
        PlanNode plan = new Filter(cross(cross(f, d1), new Filter(d2(), eq(col(D2, 1), lit(1)))),
                and(eq(col(1, DataType.INTEGER), col(4, DataType.INTEGER)),
                        eq(col(2, DataType.INTEGER), col(6, DataType.INTEGER))));
        List<PlanNode> offered = new ArrayList<>();
        Connector db = database(offered, true);
        Function<String, Connector> connectors = name -> name.equals("db") ? db : MEMORY;
        Statistics statistics = new Statistics(connectors);
        PlanNode result = JoinOrdering.order(plan, statistics, connectors);
        assertEquals("((f INNER d1) INNER d2)", shape(result));
        assertEquals(Tables.sorted(Tables.rows(EXECUTOR, plan)), Tables.sorted(Tables.rows(EXECUTOR, result)));
        // The database was asked once, for the join of its two tables.
        assertEquals(1, offered.size());
        assertEquals(List.of("d1", "f"), Plans.scans(offered.get(0)).stream().map(s -> s.table().table()).sorted()
                .toList());
        offered.clear();
        assertEquals(result, JoinOrdering.order(result, statistics, connectors), "a second pass changes nothing");

        // A database that declines, or no regard to the sources: the order of the engine.
        Connector declines = database(new ArrayList<>(), false);
        assertEquals("((f INNER d2) INNER d1)", shape(JoinOrdering.order(plan, statistics,
                name -> name.equals("db") ? declines : MEMORY)));
        assertEquals("((f INNER d2) INNER d1)", shape(JoinOrdering.order(plan, statistics)));
    }

    @Test
    void theEstimatesComeFromTheCatalogAndTheConditions() {
        Statistics statistics = statistics();
        assertEquals(200, statistics.rows(f()));
        assertEquals(20, statistics.rows(new Filter(f(), eq(col(F, 3), lit(1)))), 1e-9);
        assertEquals(200, statistics.base(new Filter(f(), eq(col(F, 3), lit(1)))));
        // f JOIN d1 on the key of d1: as many rows as f.
        assertEquals(200, statistics.rows(join(f(), d1(), 1, 0)), 1e-9);
        assertEquals(4000, statistics.rows(cross(f(), d1())), 1e-9);
        assertEquals(1, statistics.rows(new PlanNode.Aggregate(f(), List.of(),
                List.of(new PlanNode.AggregateCall(PlanNode.AggregateFunction.COUNT_STAR, -1, false, "n")))));
        assertEquals(3, statistics.rows(new PlanNode.Limit(f(), 3)));

        Expression v = col(F, 3);
        assertEquals(0.9, Statistics.selectivity(ne(v, lit(1))), 1e-9);
        assertEquals(0.3, Statistics.selectivity(gt(v, lit(1))), 1e-9);
        assertEquals(0.25, Statistics.selectivity(between(v, lit(1), lit(3))), 1e-9);
        assertEquals(0.3, Statistics.selectivity(in(v, lit(1), lit(2), lit(3))), 1e-9);
        assertEquals(0.5, Statistics.selectivity(in(v, lit(1), lit(2), lit(3), lit(4), lit(5), lit(6))), 1e-9);
        Expression name = col(D1, 1);
        assertEquals(0.1, Statistics.selectivity(like(name, lit("x%"))), 1e-9);
        assertEquals(0.5, Statistics.selectivity(like(name, lit("%x"))), 1e-9);
        assertEquals(0.05, Statistics.selectivity(isNull(v)), 1e-9);
        assertEquals(0.95, Statistics.selectivity(isNotNull(v)), 1e-9);
        assertEquals(0.7, Statistics.selectivity(not(gt(v, lit(1)))), 1e-9);
        assertEquals(0.03, Statistics.selectivity(and(eq(v, lit(1)), gt(v, lit(1)))), 1e-9);
        assertEquals(0.37, Statistics.selectivity(or(eq(v, lit(1)), gt(v, lit(1)))), 1e-9);
        assertEquals(0, Statistics.selectivity(lit(false)));

        // A source that does not know its rows: the default.
        Statistics unknown = new Statistics(source -> withoutRowCounts());
        assertEquals(Statistics.UNKNOWN_ROWS, unknown.rows(f()));
    }

    /**
     * The memory source under the name {@code db}, which keeps the parts of
     * plans offered to it and runs them, or declines them all.
     */
    private static Connector database(List<PlanNode> offered, boolean accepts) {
        return new Connector() {
            @Override
            public String name() {
                return "db";
            }

            @Override
            public Catalog catalog() {
                return MEMORY.catalog();
            }

            @Override
            public ScanPlan plan(TableRef table, ScanRequest request) {
                return MEMORY.plan(table, request);
            }

            @Override
            public List<Split> splits(ScanPlan plan) {
                return MEMORY.splits(plan);
            }

            @Override
            public BatchReader open(Split split) {
                return MEMORY.open(split);
            }

            @Override
            public Optional<RemotePlan> planRemote(PlanNode plan) {
                offered.add(plan);
                return accepts ? Optional.of(new RemotePlan(plan.schema(), "run", plan)) : Optional.empty();
            }
        };
    }

    /** The memory source with a catalog that does not tell the number of rows. */
    private static Connector withoutRowCounts() {
        Catalog catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return MEMORY.catalog().schemas();
            }

            @Override
            public List<String> tables(String schema) {
                return MEMORY.catalog().tables(schema);
            }

            @Override
            public Schema schema(TableRef table) {
                return MEMORY.catalog().schema(table);
            }
        };
        return new Connector() {
            @Override
            public String name() {
                return MEMORY.name();
            }

            @Override
            public Catalog catalog() {
                return catalog;
            }

            @Override
            public ScanPlan plan(TableRef table, ScanRequest request) {
                return MEMORY.plan(table, request);
            }

            @Override
            public List<Split> splits(ScanPlan plan) {
                return MEMORY.splits(plan);
            }

            @Override
            public BatchReader open(Split split) {
                return MEMORY.open(split);
            }
        };
    }
}
