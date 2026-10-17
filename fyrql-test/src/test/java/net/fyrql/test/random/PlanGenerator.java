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

package net.fyrql.test.random;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameExclusion;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.IntervalUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates plans over the {@linkplain RandomTables random tables} with
 * every kind of node: scans of some columns, {@code VALUES}, filters,
 * projections with {@linkplain ExpressionGenerator generated expressions},
 * joins on columns of one type, aggregations, windows, sorts, limits,
 * unions and {@code EXCEPT}/{@code INTERSECT}.
 * <p>
 * The results must not depend on the order in which an engine processes
 * rows: every sort orders by all columns, a limit applies to a sort only,
 * and the aggregates whose result depends on the order of their values
 * ({@code string_agg}, {@code list}, {@code first}, {@code last},
 * {@code any_value}) are ordered by their own column and leave out
 * floating-point columns, whose zeros compare equal but differ in sign.
 * Sums and averages leave out floating-point columns as well, since
 * their rounding depends on the order of addition. A window either orders
 * the rows of a partition by all other columns, or its calls give the same
 * result to peers. {@code SEMI} and
 * {@code ANTI} joins are left out, as the SQL front end does not read them.
 */
final class PlanGenerator {

    private final SplittableRandom random;
    private int joins;
    private int names;

    PlanGenerator(SplittableRandom random) {
        this.random = random;
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    private boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    /** Generates a plan of at most the given depth of nodes above the leaves. */
    PlanNode plan(int depth) {
        joins = 0;
        names = 0;
        PlanNode plan = node(depth);
        if (chance(30)) {
            plan = sorted(plan);
        }
        return plan;
    }

    private PlanNode node(int depth) {
        if (depth <= 0 || chance(15)) {
            return chance(85) ? scan() : values();
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return nodeOnce(depth - 1);
            } catch (IllegalArgumentException e) {
                // The node does not fit its input; draw again.
            }
        }
        return scan();
    }

    private PlanNode nodeOnce(int depth) {
        return switch (random.nextInt(12)) {
            case 0, 1 -> {
                PlanNode input = node(depth);
                yield new PlanNode.Filter(input, generator(input).condition(3));
            }
            case 2, 3 -> project(node(depth));
            case 4, 5 -> joins < 2 ? join(node(depth), node(depth)) : project(node(depth));
            case 6, 7 -> aggregate(node(depth));
            case 8 -> {
                PlanNode input = node(depth);
                yield new PlanNode.Limit(sorted(input), random.nextInt(0, 16), chance(50) ? 0 : random.nextInt(0, 6));
            }
            case 9 -> {
                PlanNode input = node(depth);
                yield new PlanNode.Union(List.of(input, sibling(input)));
            }
            case 10 -> window(node(depth));
            default -> {
                PlanNode input = node(depth);
                PlanNode right = sibling(input);
                boolean swap = chance(30);
                yield new PlanNode.SetOperation(swap ? right : input, swap ? input : right,
                        pick(List.of(PlanNode.SetOperationKind.values())), chance(50));
            }
        };
    }

    private ExpressionGenerator generator(PlanNode input) {
        return new ExpressionGenerator(random, input.schema().types());
    }

    private String name() {
        return "x" + names++;
    }

    private PlanNode.Scan scan() {
        List<String> tables = new ArrayList<>(RandomTables.SCHEMAS.keySet());
        String table = pick(tables);
        Schema schema = RandomTables.SCHEMAS.get(table);
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            if (chance(50)) {
                columns.add(i);
            }
        }
        if (columns.isEmpty()) {
            columns.add(0);
        }
        if (chance(30)) {
            Collections.shuffle(columns, new java.util.Random(random.nextLong()));
        }
        return new PlanNode.Scan(TableRef.of(RandomTables.SOURCE, table), schema, columns);
    }

    private PlanNode values() {
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = random.nextInt(1, 4); i > 0; i--) {
            fields.add(new Schema.Field(name(), pick(ExpressionGenerator.TYPES)));
        }
        Schema schema = new Schema(fields);
        return new PlanNode.Values(schema, rows(schema, random.nextInt(0, 6)));
    }

    private List<Object[]> rows(Schema schema, int count) {
        ExpressionGenerator literals = new ExpressionGenerator(random, List.of());
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            Object[] row = new Object[schema.size()];
            for (int c = 0; c < row.length; c++) {
                row[c] = ((Expression.Literal) literals.literal(schema.type(c))).value();
            }
            rows.add(row);
        }
        return rows;
    }

    /** A plan of the same column types as the input: a filter of it, the input itself, or rows of literals. */
    private PlanNode sibling(PlanNode input) {
        int choice = random.nextInt(10);
        if (choice < 5) {
            return new PlanNode.Filter(input, generator(input).condition(2));
        }
        if (choice < 7 && input.schema().types().stream().allMatch(ExpressionGenerator.TYPES::contains)) {
            return new PlanNode.Values(input.schema(), rows(input.schema(), random.nextInt(0, 5)));
        }
        return input;
    }

    private PlanNode project(PlanNode input) {
        ExpressionGenerator g = generator(input);
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = random.nextInt(1, 6); i > 0; i--) {
            Expression e = chance(30) ? g.leaf() : g.any(3);
            columns.add(new PlanNode.NamedExpression(name(), e));
        }
        return new PlanNode.Project(input, columns);
    }

    private PlanNode join(PlanNode left, PlanNode right) {
        joins++;
        List<Integer> leftKeys = new ArrayList<>();
        List<Integer> rightKeys = new ArrayList<>();
        Schema l = left.schema();
        Schema r = right.schema();
        for (int i = 0; i < l.size() && leftKeys.size() < 2; i++) {
            for (int j = 0; j < r.size(); j++) {
                if (l.type(i).equals(r.type(j)) && chance(40)) {
                    leftKeys.add(i);
                    rightKeys.add(j);
                    break;
                }
            }
        }
        if (leftKeys.isEmpty()) {
            // Without keys, a cross join of a few rows of the right side.
            return new PlanNode.Join(left, new PlanNode.Limit(sorted(right), random.nextInt(0, 4), 0), List.of(),
                    List.of(), PlanNode.JoinType.CROSS);
        }
        PlanNode.JoinType type = pick(List.of(PlanNode.JoinType.INNER, PlanNode.JoinType.LEFT, PlanNode.JoinType.RIGHT,
                PlanNode.JoinType.FULL));
        // Now and then a condition besides the keys, over the columns of both inputs.
        Expression condition = chance(25)
                ? new ExpressionGenerator(random, l.concat(r).types()).condition(2) : null;
        return new PlanNode.Join(left, right, leftKeys, rightKeys, type, condition);
    }

    private PlanNode aggregate(PlanNode input) {
        Schema schema = input.schema();
        List<Integer> groups = new ArrayList<>();
        for (int i = 0; i < schema.size() && groups.size() < 3; i++) {
            if (chance(25)) {
                groups.add(i);
            }
        }
        List<AggregateCall> calls = new ArrayList<>();
        for (int n = random.nextInt(groups.isEmpty() ? 1 : 0, 5); n > 0; n--) {
            AggregateCall call = call(schema);
            if (call != null) {
                calls.add(call);
            }
        }
        if (groups.isEmpty() && calls.isEmpty()) {
            calls.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, name()));
        }
        PlanNode aggregate = new PlanNode.Aggregate(input, groups, calls);
        if (chance(30) && !calls.isEmpty()) {
            aggregate = new PlanNode.Filter(aggregate, generator(aggregate).condition(2));
        }
        return aggregate;
    }

    private AggregateCall call(Schema schema) {
        int column = random.nextInt(schema.size());
        DataType type = schema.type(column);
        boolean floating = type == DataType.FLOAT || type == DataType.DOUBLE;
        boolean scalar = type instanceof DataType.Scalar || type instanceof DataType.Decimal;
        boolean distinct = chance(25);
        AggregateFunction f = pick(List.of(AggregateFunction.values()));
        String name = name();
        return switch (f) {
            case COUNT_STAR -> new AggregateCall(f, -1, false, name);
            case COUNT -> new AggregateCall(f, column, distinct, name);
            case SUM, AVG -> type.isIntegral() || type instanceof DataType.Decimal
                    ? new AggregateCall(f, column, distinct, name) : null;
            case MIN, MAX -> scalar ? new AggregateCall(f, column, distinct, name) : null;
            case BOOL_AND, BOOL_OR -> type == DataType.BOOLEAN ? new AggregateCall(f, column, distinct, name) : null;
            case STRING_AGG, LIST, FIRST, LAST, ANY_VALUE -> {
                if (floating || !scalar) {
                    yield null;
                }
                SortKey order = new SortKey(column, chance(50), chance(30));
                boolean d = distinct && (f == AggregateFunction.STRING_AGG || f == AggregateFunction.LIST);
                AggregateCall call = new AggregateCall(f, column, d, name).withOrderBy(List.of(order));
                if (f == AggregateFunction.STRING_AGG) {
                    call = call.withSeparator(pick(java.util.Arrays.asList(",", "", " | ", "\u00DF", null)));
                }
                yield call;
            }
        };
    }

    /**
     * A window of one to three calls over the input, sometimes with columns
     * of small integers added for the counts and offsets of the calls. Its
     * order is either total, by all columns but those of the partition, so
     * that every function and frame has one result, or one key at most,
     * whose peers the calls take together: rankings with peers, and
     * aggregates over {@code RANGE} or {@code GROUPS} frames, which take or
     * leave out whole groups of peers.
     */
    private PlanNode window(PlanNode input) {
        if (chance(40)) {
            input = withCounts(input);
        }
        Schema schema = input.schema();
        List<Integer> partition = new ArrayList<>();
        for (int i = 0; i < schema.size() && partition.size() < 2; i++) {
            if (chance(20)) {
                partition.add(i);
            }
        }
        boolean total = chance(60);
        List<SortKey> order = new ArrayList<>();
        if (total) {
            for (int i = 0; i < schema.size(); i++) {
                if (!partition.contains(i)) {
                    order.add(new SortKey(i, chance(60), chance(30)));
                }
            }
            Collections.shuffle(order, new java.util.Random(random.nextLong()));
        } else if (chance(85)) {
            order.add(new SortKey(random.nextInt(schema.size()), chance(60), chance(30)));
        }
        List<WindowCall> calls = new ArrayList<>();
        for (int n = random.nextInt(1, 4); n > 0; n--) {
            WindowCall call = windowCall(schema, order, total);
            if (call != null) {
                calls.add(call);
            }
        }
        if (calls.isEmpty()) {
            calls.add(WindowCall.of(WindowFunction.RANK, List.of(), name()));
        }
        PlanNode window = new PlanNode.Window(input, partition, order, calls);
        if (chance(25)) {
            window = new PlanNode.Filter(window, generator(window).condition(2));
        }
        return window;
    }

    /** The input with one or two more columns of small integers, mostly positive, sometimes {@code NULL}. */
    private PlanNode withCounts(PlanNode input) {
        Schema schema = input.schema();
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            columns.add(new PlanNode.NamedExpression(schema.name(i), new Expression.Column(i, schema.type(i))));
        }
        for (int i = random.nextInt(1, 3); i > 0; i--) {
            DataType type = chance(50) ? DataType.INTEGER : DataType.BIGINT;
            Expression count = chance(5) ? Expressions.nullOf(type)
                    : new Expression.Literal(type, type == DataType.INTEGER ? (Object) random.nextInt(-1, 4)
                            : (Object) random.nextLong(-1, 4));
            columns.add(new PlanNode.NamedExpression(name(), count));
        }
        return new PlanNode.Project(input, columns);
    }

    private WindowCall windowCall(Schema schema, List<SortKey> order, boolean total) {
        if (!total) {
            return chance(30) ? WindowCall.of(pick(List.of(WindowFunction.RANK, WindowFunction.DENSE_RANK,
                    WindowFunction.PERCENT_RANK, WindowFunction.CUME_DIST)), List.of(), name())
                    : windowAggregate(schema, order, false);
        }
        String name = name();
        int column = random.nextInt(schema.size());
        DataType type = schema.type(column);
        List<Integer> integral = new ArrayList<>();
        List<Integer> sameType = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            if (schema.type(i).isIntegral() && schema.type(i) != DataType.HUGEINT) {
                integral.add(i);
            }
            if (schema.type(i).equals(type)) {
                sameType.add(i);
            }
        }
        WindowFunction f = pick(List.of(WindowFunction.values()));
        boolean ignoreNulls = chance(20);
        return switch (f) {
            case ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST -> WindowCall.of(f, List.of(), name);
            case NTILE -> integral.isEmpty() ? null : WindowCall.of(f, List.of(pick(integral)), name);
            case LAG, LEAD -> {
                List<Integer> arguments = new ArrayList<>(List.of(column));
                if (!integral.isEmpty() && chance(70)) {
                    arguments.add(pick(integral));
                    if (chance(50)) {
                        arguments.add(pick(sameType));
                    }
                }
                yield new WindowCall(f, null, arguments, ignoreNulls, -1, Frame.DEFAULT, name);
            }
            case FIRST_VALUE, LAST_VALUE -> new WindowCall(f, null, List.of(column), ignoreNulls, -1,
                    frame(schema, order, true), name);
            case NTH_VALUE -> integral.isEmpty() ? null : new WindowCall(f, null, List.of(column, pick(integral)),
                    ignoreNulls, -1, frame(schema, order, true), name);
            case AGGREGATE -> windowAggregate(schema, order, true);
        };
    }

    /**
     * An aggregate over a frame, sometimes with a filter. The aggregates
     * whose result depends on the order of their values are ordered by
     * them, but for {@code first} and {@code last} over a total order,
     * which DuckDB takes as {@code first_value} and {@code last_value}.
     */
    private WindowCall windowAggregate(Schema schema, List<SortKey> order, boolean total) {
        int column = random.nextInt(schema.size());
        DataType type = schema.type(column);
        boolean floating = type == DataType.FLOAT || type == DataType.DOUBLE;
        boolean scalar = ExpressionGenerator.plain(type);
        boolean distinct = chance(20);
        AggregateFunction f = pick(List.of(AggregateFunction.values()));
        String name = name();
        AggregateCall call = switch (f) {
            case COUNT_STAR -> new AggregateCall(f, -1, false, name);
            case COUNT -> new AggregateCall(f, column, distinct, name);
            case SUM, AVG -> type.isIntegral() || type instanceof DataType.Decimal
                    ? new AggregateCall(f, column, distinct, name) : null;
            case MIN, MAX -> scalar ? new AggregateCall(f, column, distinct, name) : null;
            case BOOL_AND, BOOL_OR -> type == DataType.BOOLEAN ? new AggregateCall(f, column, false, name) : null;
            case FIRST, LAST -> total && scalar ? new AggregateCall(f, column, false, name) : null;
            case STRING_AGG, LIST, ANY_VALUE -> {
                if (floating || !scalar) {
                    yield null;
                }
                boolean d = distinct && f != AggregateFunction.ANY_VALUE;
                AggregateCall c = new AggregateCall(f, column, d, name)
                        .withOrderBy(List.of(new SortKey(column, chance(50), chance(30))));
                yield f == AggregateFunction.STRING_AGG ? c.withSeparator(pick(List.of(",", "", "-"))) : c;
            }
        };
        if (call == null) {
            return null;
        }
        List<Integer> booleans = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            if (schema.type(i) == DataType.BOOLEAN) {
                booleans.add(i);
            }
        }
        int filter = !booleans.isEmpty() && chance(20) ? pick(booleans) : -1;
        return new WindowCall(WindowFunction.AGGREGATE, call, List.of(), false, filter, frame(schema, order, total),
                name);
    }

    /**
     * A frame: the default, or one of rows, groups of peers or a range of
     * values of the only order key, with offsets mostly small and positive,
     * and sometimes rows left out. Without a total order, there are no
     * frames of rows.
     */
    private Frame frame(Schema schema, List<SortKey> order, boolean total) {
        if (chance(20)) {
            return Frame.DEFAULT;
        }
        FrameUnit unit = total ? pick(List.of(FrameUnit.values())) : pick(List.of(FrameUnit.RANGE, FrameUnit.GROUPS));
        DataType key = order.size() == 1 ? schema.type(order.get(0).column()) : null;
        boolean offsets = unit != FrameUnit.RANGE
                || key != null && (key.isNumeric() || key == DataType.DATE || key == DataType.TIMESTAMP);
        for (int attempt = 0; attempt < 10; attempt++) {
            BoundKind start = pick(List.of(BoundKind.UNBOUNDED_PRECEDING, BoundKind.PRECEDING, BoundKind.CURRENT_ROW,
                    BoundKind.FOLLOWING));
            BoundKind end = pick(List.of(BoundKind.PRECEDING, BoundKind.CURRENT_ROW, BoundKind.FOLLOWING,
                    BoundKind.UNBOUNDED_FOLLOWING));
            if (start.ordinal() > end.ordinal() || !offsets && (start.hasOffset() || end.hasOffset())) {
                continue;
            }
            FrameExclusion exclusion = chance(70) ? FrameExclusion.NO_OTHERS : pick(List.of(FrameExclusion.values()));
            return new Frame(unit, bound(start, unit, key), bound(end, unit, key), exclusion);
        }
        return Frame.DEFAULT;
    }

    /** A bound with an offset where its kind has one: of the type of the order key for a range of numbers. */
    private FrameBound bound(BoundKind kind, FrameUnit unit, DataType key) {
        if (!kind.hasOffset()) {
            return FrameBound.of(kind);
        }
        long n = chance(5) ? -1 : random.nextInt(0, 4);
        if (unit != FrameUnit.RANGE) {
            return FrameBound.of(kind, new Expression.Literal(DataType.BIGINT, n));
        }
        if (key == DataType.DATE || key == DataType.TIMESTAMP) {
            IntervalUnit u = key == DataType.DATE
                    ? pick(List.of(IntervalUnit.DAY, IntervalUnit.MONTH, IntervalUnit.YEAR, IntervalUnit.HOUR))
                    : pick(List.of(IntervalUnit.values()));
            long count = switch (u) {
                case YEAR, MONTH -> n;
                case DAY, HOUR -> n * random.nextInt(1, 40);
                default -> n * random.nextLong(1, 100_000);
            };
            return new FrameBound(kind, new Expression.Literal(DataType.BIGINT, count), u);
        }
        Object value;
        if (key instanceof DataType.Decimal d) {
            value = new BigDecimal(BigInteger.valueOf(n * 50 + (n > 0 ? random.nextInt(0, 50) : 0)), 2)
                    .setScale(d.scale());
        } else {
            value = switch ((DataType.Scalar) key) {
                case TINYINT -> (byte) n;
                case SMALLINT -> (short) n;
                case INTEGER -> (int) n;
                case BIGINT -> n;
                case HUGEINT -> BigInteger.valueOf(n);
                case FLOAT -> n * 1.25f;
                case DOUBLE -> n * 0.75;
                default -> throw new IllegalArgumentException("No RANGE offset for " + key);
            };
        }
        return FrameBound.of(kind, new Expression.Literal(key, value));
    }

    /** Sorts by all columns, in a random order of keys and directions, which leaves no ties between different rows. */
    PlanNode sorted(PlanNode input) {
        List<SortKey> keys = new ArrayList<>();
        for (int i = 0; i < input.schema().size(); i++) {
            keys.add(new SortKey(i, chance(60), chance(30)));
        }
        Collections.shuffle(keys, new java.util.Random(random.nextLong()));
        return new PlanNode.Sort(input, keys);
    }
}
