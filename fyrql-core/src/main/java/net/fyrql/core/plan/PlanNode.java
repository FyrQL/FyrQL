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

package net.fyrql.core.plan;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A node of the logical plan. Every node knows its output {@link #schema()}
 * and checks its expressions and column references against the schemas of
 * its inputs when it is created. Grouping, join and sort keys are column
 * indexes; a {@link Project} below computes anything else.
 */
public sealed interface PlanNode {

    /** Returns the columns of the output. */
    Schema schema();

    /** Returns the inputs. */
    List<PlanNode> children();

    /**
     * A grouping with aggregate calls over an input, as a connector takes
     * it over for a scan; the same rules as an {@link Aggregate}.
     *
     * @param groupColumns the columns to group by, by index into the input
     * @param calls the aggregates
     */
    record Aggregation(List<Integer> groupColumns, List<AggregateCall> calls) {
        public Aggregation {
            groupColumns = List.copyOf(groupColumns);
            calls = List.copyOf(calls);
        }

        /**
         * Checks the columns and calls against the input.
         *
         * @throws IllegalArgumentException if a column does not exist or a
         *         call does not fit its column
         */
        public void check(Schema input) {
            for (int c : groupColumns) {
                checkColumn(input, c);
            }
            for (AggregateCall a : calls) {
                a.check(input);
            }
        }

        /** Returns the output: the group columns, then one column per call. */
        public Schema output(Schema input) {
            List<Schema.Field> fields = new ArrayList<>();
            for (int c : groupColumns) {
                fields.add(input.fields().get(c));
            }
            for (AggregateCall a : calls) {
                boolean nullable = a.function() != AggregateFunction.COUNT_STAR
                        && a.function() != AggregateFunction.COUNT;
                fields.add(new Schema.Field(a.name(), a.type(input), nullable));
            }
            return new Schema(fields);
        }
    }

    /**
     * The parts of a scan that its connector has taken over, all over the
     * columns the scan reads: the filters are conjuncts that the connector
     * evaluates, the limit is the number of rows after which it stops, the
     * order is the order of its batches, and the aggregation makes it
     * deliver groups and aggregates instead of rows.
     */
    record Pushed(List<Expression> filters, OptionalLong limit, List<SortKey> order,
            Optional<Aggregation> aggregation) {

        /** Nothing taken over. */
        public static final Pushed NONE = new Pushed(List.of(), OptionalLong.empty(), List.of(), Optional.empty());

        public Pushed {
            filters = List.copyOf(filters);
            Objects.requireNonNull(limit, "limit");
            order = List.copyOf(order);
            Objects.requireNonNull(aggregation, "aggregation");
        }

        /** Tells whether nothing was taken over. */
        public boolean isEmpty() {
            return filters.isEmpty() && limit.isEmpty() && order.isEmpty() && aggregation.isEmpty();
        }

        /**
         * Returns the filters as one condition.
         *
         * @throws IllegalStateException if there are no filters
         */
        public Expression condition() {
            if (filters.isEmpty()) {
                throw new IllegalStateException("No filters");
            }
            return Expressions.conjunction(filters);
        }

        void check(Schema columns) {
            for (Expression f : filters) {
                TypeRules.require(DataType.BOOLEAN, f.type(), "A filter of a scan");
                Expressions.check(columns, f);
            }
            for (SortKey k : order) {
                checkColumn(columns, k.column());
            }
            aggregation.ifPresent(a -> a.check(columns));
        }
    }

    /**
     * Reads a table of a source. A column of the output is a column of the
     * table or a field inside a {@code STRUCT} column, which the connector
     * reads instead of the whole column. The connector may have taken over
     * parts of the plan above the scan, see {@link Pushed}; the output then
     * has the columns of the aggregation, if one was taken over.
     *
     * @param table the table
     * @param tableSchema all columns of the table
     * @param columns the columns to read, by index into the table schema,
     *        in the order of the output
     * @param fields for each of the columns, the names of the fields to
     *        follow into it, outermost first; empty for the whole column
     * @param pushed what the connector has taken over, over the columns
     */
    record Scan(TableRef table, Schema tableSchema, List<Integer> columns, List<List<String>> fields, Pushed pushed)
            implements PlanNode {
        public Scan {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(tableSchema, "tableSchema");
            columns = List.copyOf(columns);
            List<List<String>> copy = new ArrayList<>();
            for (List<String> path : fields) {
                copy.add(List.copyOf(path));
            }
            fields = List.copyOf(copy);
            if (fields.size() != columns.size()) {
                throw new IllegalArgumentException(fields.size() + " field paths for " + columns.size() + " columns");
            }
            for (int i = 0; i < columns.size(); i++) {
                field(tableSchema, columns.get(i), fields.get(i));
            }
            Objects.requireNonNull(pushed, "pushed");
            pushed.check(scanColumns(tableSchema, columns, fields));
        }

        /** A scan of whole columns. */
        public Scan(TableRef table, Schema tableSchema, List<Integer> columns, Pushed pushed) {
            this(table, tableSchema, columns, wholeColumns(columns.size()), pushed);
        }

        /** A scan of whole columns of which the connector has taken over nothing. */
        public Scan(TableRef table, Schema tableSchema, List<Integer> columns) {
            this(table, tableSchema, columns, Pushed.NONE);
        }

        /** Reads all columns of a table. */
        public static Scan all(TableRef table, Schema schema) {
            List<Integer> columns = new ArrayList<>();
            for (int i = 0; i < schema.size(); i++) {
                columns.add(i);
            }
            return new Scan(table, schema, columns);
        }

        /**
         * Returns the column a path yields: the column of the table, or a
         * nullable field named by the column and the fields, joined by dots.
         *
         * @param column the index of the column in the table schema
         * @param path the names of the fields to follow, outermost first
         * @throws IllegalArgumentException if the column or a field does not
         *         exist, or a field is taken of something that is not a struct
         */
        public static Schema.Field field(Schema table, int column, List<String> path) {
            checkColumn(table, column);
            Schema.Field f = table.fields().get(column);
            if (path.isEmpty()) {
                return f;
            }
            DataType type = f.type();
            StringBuilder name = new StringBuilder(f.name());
            for (String step : path) {
                if (!(type instanceof DataType.Struct s)) {
                    throw new IllegalArgumentException(name + " is a " + type.sqlName() + ", which has no field "
                            + step);
                }
                int i = s.indexOf(step);
                if (i < 0) {
                    throw new IllegalArgumentException("No field " + step + " in " + name + " " + type.sqlName());
                }
                type = s.fields().get(i).type();
                name.append('.').append(s.fields().get(i).name());
            }
            return new Schema.Field(name.toString(), type, true);
        }

        /** Tells whether the scan reads a field inside a column instead of a whole column. */
        public boolean readsFields() {
            for (List<String> path : fields) {
                if (!path.isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        /** Returns the columns the scan reads, before any aggregation. */
        public Schema columnSchema() {
            return scanColumns(tableSchema, columns, fields);
        }

        @Override
        public Schema schema() {
            Schema s = columnSchema();
            return pushed.aggregation().map(a -> a.output(s)).orElse(s);
        }

        /**
         * Returns the same plan with nothing taken over: the scan of the
         * whole columns and, above it, the fields taken out of them as a
         * projection, and the filter, aggregation, sort and limit the
         * connector carries out.
         */
        public PlanNode logical() {
            PlanNode node;
            if (readsFields()) {
                List<Integer> whole = new ArrayList<>();
                List<NamedExpression> picked = new ArrayList<>();
                Schema s = columnSchema();
                for (int i = 0; i < columns.size(); i++) {
                    int at = whole.indexOf(columns.get(i));
                    if (at < 0) {
                        at = whole.size();
                        whole.add(columns.get(i));
                    }
                    Expression e = new Expression.Column(at, tableSchema.type(columns.get(i)));
                    picked.add(new NamedExpression(s.name(i), Expressions.structField(e, fields.get(i))));
                }
                node = new Project(new Scan(table, tableSchema, whole), picked);
            } else {
                node = new Scan(table, tableSchema, columns);
            }
            if (!pushed.filters().isEmpty()) {
                node = new Filter(node, pushed.condition());
            }
            if (pushed.aggregation().isPresent()) {
                Aggregation a = pushed.aggregation().get();
                node = new Aggregate(node, a.groupColumns(), a.calls());
            }
            if (!pushed.order().isEmpty()) {
                node = new Sort(node, pushed.order());
            }
            if (pushed.limit().isPresent()) {
                node = new Limit(node, pushed.limit().getAsLong(), 0);
            }
            return node;
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
        }
    }

    /**
     * A part of the plan that a source runs as a whole, e.g. a join of two
     * tables of a database as one statement. The node is a leaf of the
     * plan the engine runs; the part itself is kept for the plan's output,
     * for the estimates and for showing the plan.
     *
     * @param source the source that runs the part
     * @param logical the part, over scans of tables of the source
     * @param description what the source runs, e.g. the statement
     */
    record Remote(String source, PlanNode logical, String description) implements PlanNode {
        public Remote {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(logical, "logical");
            Objects.requireNonNull(description, "description");
        }

        @Override
        public Schema schema() {
            return logical.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
        }
    }

    /** Keeps the rows whose condition is {@code true}; {@code NULL} counts as false. */
    record Filter(PlanNode input, Expression condition) implements PlanNode {
        public Filter {
            TypeRules.require(DataType.BOOLEAN, condition.type(), "The condition of a filter");
            Expressions.check(input.schema(), condition);
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /** A named output column of a {@link Project}. */
    record NamedExpression(String name, Expression expression) {
        public NamedExpression {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /** Computes output columns from the input row; every output column is nullable. */
    record Project(PlanNode input, List<NamedExpression> columns) implements PlanNode {
        public Project {
            columns = List.copyOf(columns);
            for (NamedExpression c : columns) {
                Expressions.check(input.schema(), c.expression());
            }
        }

        @Override
        public Schema schema() {
            List<Schema.Field> fields = new ArrayList<>();
            for (NamedExpression c : columns) {
                fields.add(new Schema.Field(c.name(), c.expression().type()));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /**
     * The kinds of join. Besides the joins of SQL there are two that
     * subqueries become: {@code MARK} keeps every left row once and adds a
     * column {@value Join#MARK_COLUMN} that tells whether a right row
     * matches, with the three values of {@code x IN (SELECT ...)}: true if
     * one matches, else false if the right input is empty, else
     * {@code NULL} if a left key is {@code NULL} or a right row has a
     * {@code NULL} key, else false. {@code SINGLE} is a {@code LEFT} join
     * that fails if a left row matches more than one right row, as a scalar
     * subquery does.
     */
    enum JoinType {
        INNER, LEFT, RIGHT, FULL, SEMI, ANTI, CROSS, MARK, SINGLE;

        /** Tells whether the output has the columns of the right input. */
        public boolean keepsRight() {
            return this != SEMI && this != ANTI && this != MARK;
        }

        /** Tells whether the join needs keys: the joins of SQL except {@code CROSS}. */
        public boolean needsKeys() {
            return this == INNER || this == LEFT || this == RIGHT || this == FULL;
        }

        /** Tells whether every left row is in the output, matched or not. */
        public boolean keepsAllLeft() {
            return this == LEFT || this == FULL || this == MARK || this == SINGLE;
        }
    }

    /**
     * Joins two inputs: a right row matches a left row if their keys are
     * equal and the condition, if any, is true; a key that contains
     * {@code NULL} matches nothing. The condition reads the columns of the
     * left input followed by those of the right. The output has the columns
     * of the left input followed by those of the right, except for
     * {@code SEMI} and {@code ANTI}, which keep only the left, and
     * {@code MARK}, which keeps the left and adds the mark. A
     * {@code CROSS} join has no keys and no condition; {@code SEMI},
     * {@code ANTI}, {@code MARK} and {@code SINGLE} may do without keys,
     * every right row is then a candidate.
     *
     * @param condition the condition besides the keys, or {@code null}
     */
    record Join(PlanNode left, PlanNode right, List<Integer> leftKeys, List<Integer> rightKeys, JoinType type,
            Expression condition) implements PlanNode {

        /** The name of the column a {@code MARK} join adds. */
        public static final String MARK_COLUMN = "#mark";

        public Join {
            leftKeys = List.copyOf(leftKeys);
            rightKeys = List.copyOf(rightKeys);
            Objects.requireNonNull(type, "type");
            if (leftKeys.size() != rightKeys.size()) {
                throw new IllegalArgumentException("Join keys " + leftKeys + " / " + rightKeys);
            }
            if (type == JoinType.CROSS ? !leftKeys.isEmpty() || condition != null
                    : leftKeys.isEmpty() && type.needsKeys()) {
                throw new IllegalArgumentException(type + " join with the keys " + leftKeys
                        + (condition == null ? "" : " and a condition"));
            }
            for (int i = 0; i < leftKeys.size(); i++) {
                checkColumn(left.schema(), leftKeys.get(i));
                checkColumn(right.schema(), rightKeys.get(i));
                TypeRules.require(left.schema().type(leftKeys.get(i)), right.schema().type(rightKeys.get(i)),
                        "Join key " + i);
            }
            if (condition != null) {
                TypeRules.require(DataType.BOOLEAN, condition.type(), "The condition of a join");
                Expressions.check(left.schema().concat(right.schema()), condition);
            }
        }

        /** A join without a condition besides the keys. */
        public Join(PlanNode left, PlanNode right, List<Integer> leftKeys, List<Integer> rightKeys, JoinType type) {
            this(left, right, leftKeys, rightKeys, type, null);
        }

        /** Returns the same join of another left input with the same columns. */
        public Join withLeft(PlanNode newLeft) {
            return new Join(newLeft, right, leftKeys, rightKeys, type, condition);
        }

        /** Returns the same join with another condition, or none for {@code null}. */
        public Join withCondition(Expression newCondition) {
            return new Join(left, right, leftKeys, rightKeys, type, newCondition);
        }

        /** Returns the columns the condition reads: those of the left input followed by those of the right. */
        public Schema pairSchema() {
            return left.schema().concat(right.schema());
        }

        @Override
        public Schema schema() {
            boolean leftNullable = type == JoinType.RIGHT || type == JoinType.FULL;
            boolean rightNullable = type == JoinType.LEFT || type == JoinType.FULL || type == JoinType.SINGLE;
            List<Schema.Field> fields = new ArrayList<>();
            for (Schema.Field f : left.schema().fields()) {
                fields.add(leftNullable ? new Schema.Field(f.name(), f.type(), true) : f);
            }
            if (type.keepsRight()) {
                for (Schema.Field f : right.schema().fields()) {
                    fields.add(rightNullable ? new Schema.Field(f.name(), f.type(), true) : f);
                }
            } else if (type == JoinType.MARK) {
                fields.add(new Schema.Field(MARK_COLUMN, DataType.BOOLEAN, true));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(left, right);
        }
    }

    /**
     * The aggregate functions, with the semantics of DuckDB 1.5.6: every
     * function except {@code count(*)} skips {@code NULL}s, except
     * {@code list}, {@code first} and {@code last}, which keep them.
     * {@code string_agg} joins the text forms of its values with a
     * separator; {@code list} collects the values; {@code first} and
     * {@code last} take the first and the last value, {@code any_value} the
     * first that is not {@code NULL}; {@code bool_and} and {@code bool_or}
     * combine booleans. Over no values every function except the counts
     * gives {@code NULL}.
     */
    enum AggregateFunction {
        COUNT_STAR, COUNT, SUM, AVG, MIN, MAX, STRING_AGG, LIST, FIRST, LAST, ANY_VALUE, BOOL_AND, BOOL_OR;

        /** Returns the name as DuckDB spells it. */
        public String sqlName() {
            return this == COUNT_STAR ? "count" : name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Tells whether the function takes {@code NULL}s as values instead of skipping them. */
        public boolean keepsNulls() {
            return this == LIST || this == FIRST || this == LAST;
        }

        /**
         * Returns the function with a name or an alias of DuckDB
         * ({@code array_agg}, {@code listagg}, {@code group_concat},
         * {@code arbitrary}), in any case; {@code count} is the count of
         * values.
         *
         * @throws IllegalArgumentException if there is none
         */
        public static AggregateFunction of(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return switch (n) {
                case "array_agg" -> LIST;
                case "listagg", "group_concat" -> STRING_AGG;
                case "arbitrary" -> FIRST;
                case "count_star" -> throw new IllegalArgumentException("Unknown aggregate " + name);
                default -> {
                    try {
                        yield valueOf(n.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown aggregate " + name, e);
                    }
                }
            };
        }
    }

    /**
     * One aggregate of an {@link Aggregate}.
     *
     * @param function the function
     * @param column the column it aggregates, or -1 for {@code count(*)}
     * @param distinct whether only distinct values count
     * @param name the name of the output column
     * @param separator the separator of {@code string_agg}, {@code null} for
     *        a {@code NULL} separator, which makes the result {@code NULL};
     *        {@code null} for every other function
     * @param orderBy the order in which the values reach the function, over
     *        the columns of the input; empty for the order of the input. A
     *        {@code DISTINCT} aggregate can only be ordered by its own column.
     */
    record AggregateCall(AggregateFunction function, int column, boolean distinct, String name, String separator,
            List<SortKey> orderBy) {
        public AggregateCall {
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(name, "name");
            orderBy = List.copyOf(orderBy);
            if ((function == AggregateFunction.COUNT_STAR) != (column < 0)) {
                throw new IllegalArgumentException(function + " with column " + column);
            }
            if (function == AggregateFunction.COUNT_STAR && (distinct || !orderBy.isEmpty())) {
                throw new IllegalArgumentException("count(*) with DISTINCT or ORDER BY");
            }
            if (separator != null && function != AggregateFunction.STRING_AGG) {
                throw new IllegalArgumentException("A separator for " + function);
            }
            for (SortKey k : orderBy) {
                if (distinct && k.column() != column) {
                    throw new IllegalArgumentException(
                            "In a DISTINCT aggregate, ORDER BY expressions must appear in the argument list");
                }
            }
        }

        /** An aggregate in the order of the input; {@code string_agg} separates by a comma. */
        public AggregateCall(AggregateFunction function, int column, boolean distinct, String name) {
            this(function, column, distinct, name, function == AggregateFunction.STRING_AGG ? "," : null, List.of());
        }

        /** Returns the same aggregate with another separator. */
        public AggregateCall withSeparator(String newSeparator) {
            return new AggregateCall(function, column, distinct, name, newSeparator, orderBy);
        }

        /** Returns the same aggregate with the values in another order. */
        public AggregateCall withOrderBy(List<SortKey> keys) {
            return new AggregateCall(function, column, distinct, name, separator, keys);
        }

        /** Returns the type of the result, as DuckDB types it. */
        public DataType type(Schema input) {
            if (function == AggregateFunction.COUNT_STAR || function == AggregateFunction.COUNT) {
                return DataType.BIGINT;
            }
            DataType t = input.type(column);
            return switch (function) {
                case SUM -> t.isIntegral() ? DataType.HUGEINT
                        : t instanceof Decimal d ? DataType.decimal(DataType.MAX_DECIMAL_PRECISION, d.scale())
                        : DataType.DOUBLE;
                case AVG -> DataType.DOUBLE;
                case STRING_AGG -> DataType.VARCHAR;
                case LIST -> DataType.listOf(t);
                case BOOL_AND, BOOL_OR -> DataType.BOOLEAN;
                default -> t;
            };
        }

        /**
         * Checks the call against the input schema.
         *
         * @throws IllegalArgumentException if a column does not exist or the function does not apply to it
         */
        public void check(Schema input) {
            if (column >= input.size()) {
                throw new IllegalArgumentException("No column " + column + " for " + function);
            }
            if ((function == AggregateFunction.SUM || function == AggregateFunction.AVG)
                    && !input.type(column).isNumeric()) {
                throw new IllegalArgumentException(function + " of " + input.type(column));
            }
            if ((function == AggregateFunction.BOOL_AND || function == AggregateFunction.BOOL_OR)
                    && input.type(column) != DataType.BOOLEAN) {
                throw new IllegalArgumentException(function + " of " + input.type(column));
            }
            for (SortKey k : orderBy) {
                checkColumn(input, k.column());
            }
        }
    }

    /**
     * Groups the input by columns and computes aggregates per group. The
     * output has the group columns first, then the aggregates; {@code NULL}
     * is a group of its own; without group columns an empty input gives one
     * row.
     */
    record Aggregate(PlanNode input, List<Integer> groupColumns, List<AggregateCall> aggregates)
            implements PlanNode {
        public Aggregate {
            groupColumns = List.copyOf(groupColumns);
            aggregates = List.copyOf(aggregates);
            for (int c : groupColumns) {
                checkColumn(input.schema(), c);
            }
            for (AggregateCall a : aggregates) {
                a.check(input.schema());
            }
        }

        @Override
        public Schema schema() {
            return new Aggregation(groupColumns, aggregates).output(input.schema());
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /** A sort key; DuckDB's default is ascending with {@code NULL} last. */
    record SortKey(int column, boolean ascending, boolean nullsFirst) {

        /** Ascending, {@code NULL} last. */
        public static SortKey asc(int column) {
            return new SortKey(column, true, false);
        }

        /** Descending, {@code NULL} last. */
        public static SortKey desc(int column) {
            return new SortKey(column, false, false);
        }
    }

    /** Sorts the input; the sort is stable. */
    record Sort(PlanNode input, List<SortKey> keys) implements PlanNode {
        public Sort {
            keys = List.copyOf(keys);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("Sort without keys");
            }
            for (SortKey k : keys) {
                checkColumn(input.schema(), k.column());
            }
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /** Skips {@code offset} rows, then passes at most {@code count}. */
    record Limit(PlanNode input, long count, long offset) implements PlanNode {
        public Limit {
            Objects.requireNonNull(input, "input");
            if (count < 0 || offset < 0) {
                throw new IllegalArgumentException("count " + count + ", offset " + offset);
            }
        }

        public Limit(PlanNode input, long count) {
            this(input, count, 0);
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /**
     * Repeats each input row once per element of a list, with the element
     * as one more column after those of the input, in the order of the
     * list. A row whose list is empty or {@code NULL} gives no row, as
     * {@code unnest} in DuckDB.
     *
     * @param input the input
     * @param list the list, an expression over the input
     * @param name the name of the column of the elements
     */
    record Unnest(PlanNode input, Expression list, String name) implements PlanNode {
        public Unnest {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(name, "name");
            if (!(list.type() instanceof DataType.ListType)) {
                throw new IllegalArgumentException("Unnest of a " + list.type() + ", not a list");
            }
            Expressions.check(input.schema(), list);
        }

        /** Returns the type of the elements. */
        public DataType elementType() {
            return ((DataType.ListType) list.type()).element();
        }

        @Override
        public Schema schema() {
            List<Schema.Field> fields = new ArrayList<>(input.schema().fields());
            fields.add(new Schema.Field(name, elementType(), true));
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(input);
        }
    }

    /** Constant rows, e.g. from {@code VALUES} or a {@code SELECT} without {@code FROM}. */
    record Values(Schema schema, List<Object[]> rows) implements PlanNode {
        public Values {
            Objects.requireNonNull(schema, "schema");
            rows = List.copyOf(rows);
            for (Object[] row : rows) {
                if (row.length != schema.size()) {
                    throw new IllegalArgumentException("A row of " + row.length + " values for " + schema);
                }
                for (int i = 0; i < row.length; i++) {
                    net.fyrql.core.type.Values.require(schema.type(i), row[i]);
                }
            }
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
        }
    }

    /** The set operations besides {@code UNION}. */
    enum SetOperationKind {
        EXCEPT, INTERSECT
    }

    /**
     * {@code EXCEPT} or {@code INTERSECT} of two inputs with the same column
     * types, as sets, or as multisets with {@code ALL}; {@code NULL} equals
     * {@code NULL}, as in {@code GROUP BY}. The output has the column names
     * of the left input and no defined order.
     */
    record SetOperation(PlanNode left, PlanNode right, SetOperationKind kind, boolean all) implements PlanNode {
        public SetOperation {
            Objects.requireNonNull(kind, "kind");
            if (left.schema().size() != right.schema().size()) {
                throw new IllegalArgumentException(kind + " of different widths");
            }
            for (int i = 0; i < left.schema().size(); i++) {
                TypeRules.require(left.schema().type(i), right.schema().type(i), kind + " column " + i);
            }
        }

        @Override
        public Schema schema() {
            Schema l = left.schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                fields.add(new Schema.Field(l.name(i), l.type(i), l.nullable(i) || right.schema().nullable(i)));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return List.of(left, right);
        }
    }

    /** {@code UNION ALL} of inputs with the same column types. */
    record Union(List<PlanNode> inputs) implements PlanNode {
        public Union {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("Union without inputs");
            }
            Schema first = inputs.get(0).schema();
            for (PlanNode in : inputs) {
                if (in.schema().size() != first.size()) {
                    throw new IllegalArgumentException("Union of different widths");
                }
                for (int i = 0; i < first.size(); i++) {
                    TypeRules.require(first.type(i), in.schema().type(i), "Union column " + i);
                }
            }
        }

        @Override
        public Schema schema() {
            Schema first = inputs.get(0).schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int i = 0; i < first.size(); i++) {
                boolean nullable = false;
                for (PlanNode in : inputs) {
                    nullable |= in.schema().nullable(i);
                }
                fields.add(new Schema.Field(first.fields().get(i).name(), first.type(i), nullable));
            }
            return new Schema(fields);
        }

        @Override
        public List<PlanNode> children() {
            return inputs;
        }
    }

    /** The columns of a scan: a column or a field per path. */
    private static Schema scanColumns(Schema table, List<Integer> columns, List<List<String>> fields) {
        List<Schema.Field> result = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            result.add(Scan.field(table, columns.get(i), fields.get(i)));
        }
        return new Schema(result);
    }

    /** The paths of whole columns. */
    private static List<List<String>> wholeColumns(int count) {
        List<List<String>> fields = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            fields.add(List.of());
        }
        return fields;
    }

    private static void checkColumn(Schema schema, int column) {
        if (column < 0 || column >= schema.size()) {
            throw new IllegalArgumentException("No column " + column + " in " + schema);
        }
    }
}
