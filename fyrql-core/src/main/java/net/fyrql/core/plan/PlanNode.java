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
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

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
     * deliver groups and aggregates instead of rows. The hints are the
     * conjuncts it did not take over, which the engine evaluates above the
     * scan; the connector sees them again when the scan is opened and may
     * use them to skip data in which no row can satisfy them, but they are
     * not taken over. When the connector takes over all filters, it may
     * leave out of its batches the columns at the end that only the filters
     * read: the batches then carry the first {@code delivered} columns.
     */
    record Pushed(List<Expression> filters, OptionalLong limit, List<SortKey> order,
            Optional<Aggregation> aggregation, List<Expression> hints, OptionalInt delivered) {

        /** Nothing taken over. */
        public static final Pushed NONE = new Pushed(List.of(), OptionalLong.empty(), List.of(), Optional.empty());

        public Pushed {
            filters = List.copyOf(filters);
            Objects.requireNonNull(limit, "limit");
            order = List.copyOf(order);
            Objects.requireNonNull(aggregation, "aggregation");
            hints = List.copyOf(hints);
            Objects.requireNonNull(delivered, "delivered");
            if (delivered.isPresent() && (filters.isEmpty() || aggregation.isPresent() || !hints.isEmpty())) {
                throw new IllegalArgumentException("Columns are left out only of a scan whose filters are all taken "
                        + "over, without an aggregation");
            }
        }

        /** The parts taken over with hints, in batches of all columns. */
        public Pushed(List<Expression> filters, OptionalLong limit, List<SortKey> order,
                Optional<Aggregation> aggregation, List<Expression> hints) {
            this(filters, limit, order, aggregation, hints, OptionalInt.empty());
        }

        /** The parts taken over, without hints. */
        public Pushed(List<Expression> filters, OptionalLong limit, List<SortKey> order,
                Optional<Aggregation> aggregation) {
            this(filters, limit, order, aggregation, List.of());
        }

        /** Tells whether nothing was taken over; the hints do not count. */
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
            for (Expression h : hints) {
                TypeRules.require(DataType.BOOLEAN, h.type(), "A hint of a scan");
                Expressions.check(columns, h);
            }
            for (SortKey k : order) {
                checkColumn(columns, k.column());
                if (delivered.isPresent() && k.column() >= delivered.getAsInt()) {
                    throw new IllegalArgumentException("The order of a scan by column " + k.column()
                            + ", which its batches leave out");
                }
            }
            if (delivered.isPresent() && (delivered.getAsInt() < 0 || delivered.getAsInt() > columns.size())) {
                throw new IllegalArgumentException(delivered.getAsInt() + " delivered of " + columns.size()
                        + " columns");
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

        /**
         * Returns the columns of the batches: the groups and aggregates of the
         * aggregation taken over, or the columns the scan reads, without those
         * at the end that the connector leaves out.
         */
        @Override
        public Schema schema() {
            Schema s = columnSchema();
            if (pushed.delivered().isPresent()) {
                return new Schema(s.fields().subList(0, pushed.delivered().getAsInt()));
            }
            return pushed.aggregation().map(a -> a.output(s)).orElse(s);
        }

        /**
         * Returns the same plan with nothing taken over: the scan of the
         * whole columns and, above it, the fields taken out of them as a
         * projection, and the filter, aggregation, sort and limit the
         * connector carries out, with a projection to the columns of its
         * batches when it leaves some out.
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
            if (pushed.delivered().isPresent()) {
                Schema s = node.schema();
                List<NamedExpression> kept = new ArrayList<>();
                for (int i = 0; i < pushed.delivered().getAsInt(); i++) {
                    kept.add(new NamedExpression(s.name(i), new Expression.Column(i, s.type(i))));
                }
                node = new Project(node, kept);
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
            // The schema once: a scan builds its schema anew each time, for each of thousands of columns.
            Schema in = input.schema();
            for (NamedExpression c : columns) {
                Expressions.check(in, c.expression());
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
     * gives {@code NULL}. {@code stddev_samp}, {@code stddev_pop},
     * {@code var_samp} and {@code var_pop} take numbers and give a
     * {@code DOUBLE}; the sample forms give {@code NULL} over one value, and
     * a result that is not finite is an error.
     */
    enum AggregateFunction {
        COUNT_STAR, COUNT, SUM, AVG, MIN, MAX, STRING_AGG, LIST, FIRST, LAST, ANY_VALUE, BOOL_AND, BOOL_OR,
        STDDEV_SAMP, STDDEV_POP, VAR_SAMP, VAR_POP;

        /** Returns the name as DuckDB spells it. */
        public String sqlName() {
            return this == COUNT_STAR ? "count" : name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Tells whether the function takes {@code NULL}s as values instead of skipping them. */
        public boolean keepsNulls() {
            return this == LIST || this == FIRST || this == LAST;
        }

        /** Tells whether the function is a standard deviation or a variance. */
        public boolean isStatistic() {
            return this == STDDEV_SAMP || this == STDDEV_POP || this == VAR_SAMP || this == VAR_POP;
        }

        /**
         * Returns the function with a name or an alias of DuckDB
         * ({@code array_agg}, {@code listagg}, {@code group_concat},
         * {@code arbitrary}, {@code stddev}, {@code variance}), in any case;
         * {@code count} is the count of values.
         *
         * @throws IllegalArgumentException if there is none
         */
        public static AggregateFunction of(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            return switch (n) {
                case "array_agg" -> LIST;
                case "listagg", "group_concat" -> STRING_AGG;
                case "arbitrary" -> FIRST;
                case "stddev" -> STDDEV_SAMP;
                case "variance" -> VAR_SAMP;
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
     *        {@code DISTINCT} aggregate can only be ordered by its own column,
     *        a {@code string_agg} also by a column whose text its own is, as
     *        the binder orders the text of an instant by the instant
     * @param filter the {@code BOOLEAN} column of the input whose rows the
     *        call takes, those where it is true, or -1 for all rows; as in
     *        DuckDB, {@code count(*)} of no row is 0 and the other functions
     *        give {@code NULL}
     */
    record AggregateCall(AggregateFunction function, int column, boolean distinct, String name, String separator,
            List<SortKey> orderBy, int filter) {
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
                if (distinct && k.column() != column && function != AggregateFunction.STRING_AGG) {
                    throw new IllegalArgumentException(
                            "In a DISTINCT aggregate, ORDER BY expressions must appear in the argument list");
                }
            }
            if (filter < -1) {
                throw new IllegalArgumentException("Filter column " + filter);
            }
        }

        /** An aggregate over all rows. */
        public AggregateCall(AggregateFunction function, int column, boolean distinct, String name, String separator,
                List<SortKey> orderBy) {
            this(function, column, distinct, name, separator, orderBy, -1);
        }

        /** An aggregate in the order of the input; {@code string_agg} separates by a comma. */
        public AggregateCall(AggregateFunction function, int column, boolean distinct, String name) {
            this(function, column, distinct, name, function == AggregateFunction.STRING_AGG ? "," : null, List.of());
        }

        /** Returns the same aggregate with another separator. */
        public AggregateCall withSeparator(String newSeparator) {
            return new AggregateCall(function, column, distinct, name, newSeparator, orderBy, filter);
        }

        /** Returns the same aggregate with the values in another order. */
        public AggregateCall withOrderBy(List<SortKey> keys) {
            return new AggregateCall(function, column, distinct, name, separator, keys, filter);
        }

        /** Returns the same aggregate taking the rows where a column is true, or all rows for -1. */
        public AggregateCall withFilter(int newFilter) {
            return new AggregateCall(function, column, distinct, name, separator, orderBy, newFilter);
        }

        /** Returns the same aggregate over other columns: each column it reads, also in order and filter, mapped. */
        public AggregateCall remap(IntUnaryOperator mapping) {
            List<SortKey> keys = new ArrayList<>();
            for (SortKey k : orderBy) {
                keys.add(new SortKey(mapping.applyAsInt(k.column()), k.ascending(), k.nullsFirst()));
            }
            return new AggregateCall(function, column < 0 ? -1 : mapping.applyAsInt(column), distinct, name,
                    separator, keys, filter < 0 ? -1 : mapping.applyAsInt(filter));
        }

        /** Returns the type of the result, as DuckDB types it. */
        public DataType type(Schema input) {
            if (function == AggregateFunction.COUNT_STAR || function == AggregateFunction.COUNT) {
                return DataType.BIGINT;
            }
            DataType t = input.type(column);
            if (function.isStatistic()) {
                return DataType.DOUBLE;
            }
            return switch (function) {
                case SUM -> t.isIntegral() ? DataType.HUGEINT
                        : t instanceof Decimal d ? DataType.decimal(DataType.MAX_DECIMAL_PRECISION, d.scale())
                        : DataType.DOUBLE;
                case AVG -> t == DataType.TIME || t == DataType.INTERVAL ? t : DataType.DOUBLE;
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
            if ((function == AggregateFunction.SUM || function == AggregateFunction.AVG || function.isStatistic())
                    && !input.type(column).isNumeric() && !(function == AggregateFunction.AVG
                    && (input.type(column) == DataType.TIME || input.type(column) == DataType.INTERVAL))) {
                throw new IllegalArgumentException(function + " of " + input.type(column));
            }
            if ((function == AggregateFunction.BOOL_AND || function == AggregateFunction.BOOL_OR)
                    && input.type(column) != DataType.BOOLEAN) {
                throw new IllegalArgumentException(function + " of " + input.type(column));
            }
            for (SortKey k : orderBy) {
                checkColumn(input, k.column());
            }
            if (filter >= 0) {
                checkColumn(input, filter);
                TypeRules.require(DataType.BOOLEAN, input.type(filter), "The filter of " + function);
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
            Schema in = input.schema();
            for (int c : groupColumns) {
                checkColumn(in, c);
            }
            for (AggregateCall a : aggregates) {
                a.check(in);
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
            Schema in = input.schema();
            for (SortKey k : keys) {
                checkColumn(in, k.column());
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

    /**
     * The functions of a {@link WindowCall}, with the semantics of DuckDB
     * 1.5.6. The ranking functions number the rows of a partition in the
     * order of the window: {@code row_number} one by one, {@code rank} with
     * a gap after peers, {@code dense_rank} without, {@code percent_rank} as
     * {@code (rank - 1) / (rows - 1)}, {@code cume_dist} as the share of the
     * rows up to the last peer, and {@code ntile(n)} as the bucket among n.
     * {@code lag} and {@code lead} take the value of the row a number of
     * rows before or after, or a default. These ignore the frame;
     * {@code first_value}, {@code last_value}, {@code nth_value} and the
     * aggregates take the rows of the frame.
     */
    enum WindowFunction {
        ROW_NUMBER(0, 0), RANK(0, 0), DENSE_RANK(0, 0), PERCENT_RANK(0, 0), CUME_DIST(0, 0), NTILE(1, 1),
        LAG(1, 3), LEAD(1, 3), FIRST_VALUE(1, 1), LAST_VALUE(1, 1), NTH_VALUE(2, 2), AGGREGATE(0, 0);

        private final int minArguments;
        private final int maxArguments;

        WindowFunction(int minArguments, int maxArguments) {
            this.minArguments = minArguments;
            this.maxArguments = maxArguments;
        }

        /** Returns the least number of arguments; none for {@code AGGREGATE}, whose aggregate has them. */
        public int minArguments() {
            return minArguments;
        }

        /** Returns the greatest number of arguments. */
        public int maxArguments() {
            return maxArguments;
        }

        /** Returns the name as DuckDB spells it; a call of {@code AGGREGATE} has the name of its aggregate. */
        public String sqlName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Tells whether the function takes the rows of the frame of a row. */
        public boolean usesFrame() {
            return this == FIRST_VALUE || this == LAST_VALUE || this == NTH_VALUE || this == AGGREGATE;
        }

        /** Tells whether the function takes the value of a row, the only kind that may skip {@code NULL}s. */
        public boolean takesValues() {
            return this == LAG || this == LEAD || this == FIRST_VALUE || this == LAST_VALUE || this == NTH_VALUE;
        }

        /**
         * Returns the function with a name, in any case; not an aggregate.
         *
         * @throws IllegalArgumentException if there is none
         */
        public static WindowFunction of(String name) {
            String n = name.toUpperCase(java.util.Locale.ROOT);
            if (!n.equals(AGGREGATE.name())) {
                for (WindowFunction f : values()) {
                    if (f.name().equals(n)) {
                        return f;
                    }
                }
            }
            throw new IllegalArgumentException("Unknown window function " + name);
        }
    }

    /** The units of a frame: rows, values of the order key, or groups of peers. */
    enum FrameUnit {
        ROWS, RANGE, GROUPS
    }

    /** The kinds of a bound of a frame. */
    enum BoundKind {
        UNBOUNDED_PRECEDING, PRECEDING, CURRENT_ROW, FOLLOWING, UNBOUNDED_FOLLOWING;

        /** Tells whether the bound has an offset. */
        public boolean hasOffset() {
            return this == PRECEDING || this == FOLLOWING;
        }

        /** Returns the keywords, after the offset if there is one. */
        public String keywords() {
            return name().replace('_', ' ');
        }
    }

    /** The rows a frame leaves out of itself. */
    enum FrameExclusion {
        NO_OTHERS, CURRENT_ROW, GROUP, TIES;

        /** Returns the keywords after {@code EXCLUDE}. */
        public String keywords() {
            return name().replace('_', ' ');
        }
    }

    /** The units of an interval by which a {@code RANGE} frame reaches over a date or a timestamp. */
    enum IntervalUnit {
        YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, MILLISECOND, MICROSECOND
    }

    /**
     * A bound of a frame. For {@code ROWS} and {@code GROUPS}, the offset is
     * a {@code BIGINT}, a number of rows or groups; DuckDB takes a negative
     * one as the other direction. For {@code RANGE} it is a distance from
     * the value of the order key: a number over a numeric key, or a count
     * of a unit over a {@code DATE} or {@code TIMESTAMP} key.
     *
     * @param kind the kind
     * @param offset the offset if the kind has one, else {@code null}; never a {@code NULL} value
     * @param unit the unit of an interval, or {@code null} for a plain number
     */
    record FrameBound(BoundKind kind, Expression.Literal offset, IntervalUnit unit) {
        public FrameBound {
            Objects.requireNonNull(kind, "kind");
            if (kind.hasOffset() != (offset != null)) {
                throw new IllegalArgumentException(kind + (offset == null ? " without" : " with") + " an offset");
            }
            if (offset != null && offset.value() == null) {
                throw new IllegalArgumentException("A NULL offset of " + kind);
            }
            if (unit != null && offset.type() != DataType.BIGINT) {
                throw new IllegalArgumentException("A count of " + unit + " of type " + offset.type());
            }
        }

        /** A bound without an offset. */
        public static FrameBound of(BoundKind kind) {
            return new FrameBound(kind, null, null);
        }

        /** A bound with a plain number as its offset. */
        public static FrameBound of(BoundKind kind, Expression.Literal offset) {
            return new FrameBound(kind, offset, null);
        }

        /** Returns the bound as SQL, with the offset written as the function says. */
        public String sql(Function<FrameBound, String> offsets) {
            return kind.hasOffset() ? offsets.apply(this) + " " + kind.keywords() : kind.keywords();
        }
    }

    /**
     * The rows of the partition a {@link WindowCall} takes for a row: from
     * the start bound to the end bound, without the excluded rows. Peers are
     * rows with equal values of the order keys; without order keys, all
     * rows of the partition are peers.
     */
    record Frame(FrameUnit unit, FrameBound start, FrameBound end, FrameExclusion exclusion) {

        /**
         * The frame of a window without one, as in SQL: from the first row
         * to the last peer of the current row, the whole partition if the
         * window has no order.
         */
        public static final Frame DEFAULT = new Frame(FrameUnit.RANGE, FrameBound.of(BoundKind.UNBOUNDED_PRECEDING),
                FrameBound.of(BoundKind.CURRENT_ROW), FrameExclusion.NO_OTHERS);

        public Frame {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(exclusion, "exclusion");
            if (start.kind() == BoundKind.UNBOUNDED_FOLLOWING || end.kind() == BoundKind.UNBOUNDED_PRECEDING
                    || start.kind().ordinal() > end.kind().ordinal()) {
                throw new IllegalArgumentException("A frame from " + start.kind() + " to " + end.kind());
            }
            for (FrameBound b : List.of(start, end)) {
                if (unit != FrameUnit.RANGE && b.offset() != null
                        && (b.unit() != null || b.offset().type() != DataType.BIGINT)) {
                    throw new IllegalArgumentException("A " + unit + " offset of type " + b.offset().type()
                            + (b.unit() == null ? "" : " " + b.unit()));
                }
            }
        }

        /** Tells whether a unit is one of time, from hours down, which shifts an instant without a calendar. */
        public static boolean isTimeUnit(IntervalUnit unit) {
            return unit == IntervalUnit.HOUR || unit == IntervalUnit.MINUTE || unit == IntervalUnit.SECOND
                    || unit == IntervalUnit.MILLISECOND || unit == IntervalUnit.MICROSECOND;
        }

        /** Tells whether a bound has an offset. */
        public boolean hasOffset() {
            return start.offset() != null || end.offset() != null;
        }

        /**
         * Checks the offsets of a {@code RANGE} frame against the order of
         * the window, which must then be one key: a numeric key takes plain
         * numbers, a {@code DATE} or {@code TIMESTAMP} key intervals, a
         * {@code TIMESTAMP WITH TIME ZONE} key intervals of hours and less.
         *
         * @throws IllegalArgumentException if an offset does not fit the order
         */
        public void check(Schema input, List<SortKey> order) {
            if (unit != FrameUnit.RANGE || !hasOffset()) {
                return;
            }
            if (order.size() != 1) {
                throw new IllegalArgumentException("RANGE frames must have only one ORDER BY expression");
            }
            DataType key = input.type(order.get(0).column());
            for (FrameBound b : List.of(start, end)) {
                if (b.offset() == null) {
                    continue;
                }
                boolean fits = b.unit() == null ? key.isNumeric() && b.offset().type().isNumeric()
                        : key == DataType.DATE || key == DataType.TIMESTAMP
                                || key == DataType.TIMESTAMPTZ && isTimeUnit(b.unit());
                if (!fits) {
                    throw new IllegalArgumentException("A RANGE offset of type " + b.offset().type()
                            + (b.unit() == null ? "" : " " + b.unit()) + " for an order key of type " + key);
                }
            }
        }

        /** Returns the frame as SQL, with the offsets written as the function says. */
        public String sql(Function<FrameBound, String> offsets) {
            return unit + " BETWEEN " + start.sql(offsets) + " AND " + end.sql(offsets)
                    + (exclusion == FrameExclusion.NO_OTHERS ? "" : " EXCLUDE " + exclusion.keywords());
        }
    }

    /**
     * One call of a {@link Window}, over the columns of its input. The
     * arguments are columns, so that each row has its own, as in DuckDB; a
     * constant is a column that a projection below computes.
     *
     * @param function the function
     * @param aggregate the aggregate of {@code AGGREGATE}, with its column,
     *        {@code DISTINCT}, separator and order; {@code null} for every
     *        other function
     * @param arguments the arguments of every other function: the count of
     *        {@code ntile}; the value, then the offset and the default of
     *        {@code lag} and {@code lead}; the value of {@code first_value}
     *        and {@code last_value}; the value and the position of
     *        {@code nth_value}. A count, an offset and a position are
     *        integers up to {@code BIGINT}, as DuckDB takes them
     * @param ignoreNulls whether a function that takes values skips the
     *        rows whose value is {@code NULL}
     * @param filter the {@code BOOLEAN} column that tells the rows an
     *        aggregate takes, or -1 for all
     * @param frame the frame, which the functions that do not use one ignore
     * @param name the name of the output column
     */
    record WindowCall(WindowFunction function, AggregateCall aggregate, List<Integer> arguments, boolean ignoreNulls,
            int filter, Frame frame, String name) {
        public WindowCall {
            Objects.requireNonNull(function, "function");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(name, "name");
            if ((function == WindowFunction.AGGREGATE) != (aggregate != null)) {
                throw new IllegalArgumentException(function + (aggregate == null ? " without" : " with")
                        + " an aggregate");
            }
            if (aggregate != null && aggregate.filter() >= 0) {
                throw new IllegalArgumentException("An aggregate over a window filters with the filter of the call");
            }
            if (arguments.size() < function.minArguments || arguments.size() > function.maxArguments) {
                throw new IllegalArgumentException(function + " with " + arguments.size() + " arguments");
            }
            if (ignoreNulls && !function.takesValues()) {
                throw new IllegalArgumentException("IGNORE NULLS for " + function);
            }
            if (filter < -1 || filter >= 0 && function != WindowFunction.AGGREGATE) {
                throw new IllegalArgumentException("A filter " + filter + " for " + function);
            }
        }

        /** A call of a function other than an aggregate, over the default frame. */
        public static WindowCall of(WindowFunction function, List<Integer> arguments, String name) {
            return new WindowCall(function, null, arguments, false, -1, Frame.DEFAULT, name);
        }

        /** A call of an aggregate over all rows of a frame. */
        public static WindowCall of(AggregateCall aggregate, Frame frame) {
            return new WindowCall(WindowFunction.AGGREGATE, aggregate, List.of(), false, -1, frame, aggregate.name());
        }

        /**
         * Checks the call against the input schema and the order of the window.
         *
         * @throws IllegalArgumentException if a column does not exist or does
         *         not fit its argument, or the frame does not fit the order
         */
        public void check(Schema input, List<SortKey> order) {
            if (aggregate != null) {
                aggregate.check(input);
            }
            for (int c : arguments) {
                checkColumn(input, c);
            }
            switch (function) {
                case NTILE -> requireIntegral(input, 0);
                case LAG, LEAD -> {
                    if (arguments.size() > 1) {
                        requireIntegral(input, 1);
                    }
                    if (arguments.size() > 2) {
                        TypeRules.require(input.type(arguments.get(0)), input.type(arguments.get(2)),
                                "The default of " + function);
                    }
                }
                case NTH_VALUE -> requireIntegral(input, 1);
                default -> {
                }
            }
            if (filter >= 0) {
                checkColumn(input, filter);
                TypeRules.require(DataType.BOOLEAN, input.type(filter), "The filter of " + function);
            }
            frame.check(input, order);
        }

        private void requireIntegral(Schema input, int argument) {
            DataType t = input.type(arguments.get(argument));
            if (!t.isIntegral() || t == DataType.HUGEINT) {
                throw new IllegalArgumentException("Argument " + argument + " of " + function + " of type " + t);
            }
        }

        /** Returns the type of the result, as DuckDB types it. */
        public DataType type(Schema input) {
            return switch (function) {
                case ROW_NUMBER, RANK, DENSE_RANK, NTILE -> DataType.BIGINT;
                case PERCENT_RANK, CUME_DIST -> DataType.DOUBLE;
                case LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE -> input.type(arguments.get(0));
                case AGGREGATE -> aggregate.type(input);
            };
        }

        /** Tells whether the result may be {@code NULL}: not for the rankings but {@code ntile}, nor for counts. */
        public boolean nullable() {
            return switch (function) {
                case ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, CUME_DIST -> false;
                case AGGREGATE -> aggregate.function() != AggregateFunction.COUNT_STAR
                        && aggregate.function() != AggregateFunction.COUNT;
                default -> true;
            };
        }
    }

    /**
     * Computes window functions: for every input row, one more column per
     * call after the columns of the input, as {@link Unnest} adds one. The
     * rows are split into partitions by the values of the partition
     * columns, {@code NULL} being a value of its own, and ordered within a
     * partition by the order keys; every call of the node shares the
     * partitions and the order. The number of rows stays; the output has no
     * defined order.
     *
     * @param input the input
     * @param partition the columns of the partitions; none for one partition
     * @param order the order within a partition; none for no order, which
     *        makes all rows of a partition peers
     * @param calls the calls, at least one
     */
    record Window(PlanNode input, List<Integer> partition, List<SortKey> order, List<WindowCall> calls)
            implements PlanNode {
        public Window {
            Objects.requireNonNull(input, "input");
            partition = List.copyOf(partition);
            order = List.copyOf(order);
            calls = List.copyOf(calls);
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("Window without calls");
            }
            Schema in = input.schema();
            for (int c : partition) {
                checkColumn(in, c);
            }
            for (SortKey k : order) {
                checkColumn(in, k.column());
            }
            for (WindowCall c : calls) {
                c.check(in, order);
            }
        }

        @Override
        public Schema schema() {
            Schema in = input.schema();
            List<Schema.Field> fields = new ArrayList<>(in.fields());
            for (WindowCall c : calls) {
                fields.add(new Schema.Field(c.name(), c.type(in), c.nullable()));
            }
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
            Schema l = left.schema();
            Schema r = right.schema();
            if (l.size() != r.size()) {
                throw new IllegalArgumentException(kind + " of different widths");
            }
            for (int i = 0; i < l.size(); i++) {
                TypeRules.require(l.type(i), r.type(i), kind + " column " + i);
            }
        }

        @Override
        public Schema schema() {
            Schema l = left.schema();
            Schema r = right.schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                fields.add(new Schema.Field(l.name(i), l.type(i), l.nullable(i) || r.nullable(i)));
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

    /**
     * A common table expression that runs once: the rows of the definition
     * are kept, and each {@link CommonTableScan} of the same id in the input
     * reads them. The output is that of the input. The definition may read
     * the common tables around this node, not those in its input.
     *
     * @param id the id of the common table, unique in the plan
     * @param name the name of the common table, for showing the plan
     * @param definition the plan of the rows
     * @param input the plan that reads them
     */
    record CommonTable(int id, String name, PlanNode definition, PlanNode input) implements PlanNode {
        public CommonTable {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(input, "input");
        }

        @Override
        public Schema schema() {
            return input.schema();
        }

        @Override
        public List<PlanNode> children() {
            return List.of(definition, input);
        }
    }

    /**
     * Reads columns of the rows of the {@link CommonTable} of the same id
     * above it. The node is a leaf; the definition of the common table is
     * kept for the output and the estimates only.
     *
     * @param id the id of the common table
     * @param name the name of the common table
     * @param columns the columns to read, by index into the output of the definition
     * @param definition the plan of the rows of the common table
     */
    record CommonTableScan(int id, String name, List<Integer> columns, PlanNode definition) implements PlanNode {
        public CommonTableScan {
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
            Objects.requireNonNull(definition, "definition");
            Schema schema = definition.schema();
            for (int c : columns) {
                checkColumn(schema, c);
            }
        }

        /** Reads all columns of a common table. */
        public static CommonTableScan all(int id, String name, PlanNode definition) {
            List<Integer> columns = new ArrayList<>();
            for (int i = 0; i < definition.schema().size(); i++) {
                columns.add(i);
            }
            return new CommonTableScan(id, name, columns, definition);
        }

        @Override
        public Schema schema() {
            return definition.schema().select(columns);
        }

        @Override
        public List<PlanNode> children() {
            return List.of();
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
