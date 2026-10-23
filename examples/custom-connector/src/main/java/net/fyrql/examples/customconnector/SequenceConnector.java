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

package net.fyrql.examples.customconnector;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expression.Between;
import net.fyrql.core.expr.Expression.Column;
import net.fyrql.core.expr.Expression.Comparison;
import net.fyrql.core.expr.Expression.ComparisonOp;
import net.fyrql.core.expr.Expression.Literal;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A source with one table, {@code main.numbers}, whose rows it computes: the numbers {@code n} from 1 to a maximum,
 * their squares and whether they are even. It takes over the filters that narrow the range of {@code n}, so that a
 * query such as {@code WHERE n BETWEEN 10 AND 19} computes ten rows instead of all, and reads the range in parts on
 * several threads.
 */
public final class SequenceConnector implements Connector {

    static final String TABLE = "numbers";

    static final Schema SCHEMA = Schema.of(
            new Schema.Field("n", DataType.BIGINT, false),
            new Schema.Field("square", DataType.BIGINT, false),
            new Schema.Field("even", DataType.BOOLEAN, false));

    /** The largest maximum whose square still fits a BIGINT. */
    static final long LARGEST_MAX = 3_037_000_499L;

    /** The position of n in the table. */
    private static final int N = 0;

    /** The fewest rows a part of a scan has, so that a small range is not split. */
    private static final long ROWS_PER_SPLIT = 10_000;

    private final String name;
    private final long max;
    private final int batchSize;
    private final AtomicLong computed = new AtomicLong();

    public SequenceConnector(String name, long max, int batchSize) {
        if (max < 1 || max > LARGEST_MAX) {
            throw new IllegalArgumentException("The maximum must be between 1 and " + LARGEST_MAX + ", not " + max);
        }
        this.name = name;
        this.max = max;
        this.batchSize = batchSize;
    }

    /** Returns how many rows the scans of this source have computed so far. */
    public long computedRows() {
        return computed.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Catalog catalog() {
        return new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of("main");
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals("main") ? List.of(TABLE) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                check(table);
                return SCHEMA;
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                // The optimizer uses it to order joins.
                return OptionalLong.of(max);
            }
        };
    }

    /**
     * Takes over the filters on {@code n} that narrow its range and declines all others, which the engine then
     * evaluates itself. The range goes into the plan, for {@link #splits} and {@link #open}.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        check(table);
        // The filters refer to the columns of the request by their position; where is n?
        int n = -1;
        for (int i = 0; i < request.columns().size(); i++) {
            ColumnPath path = request.columns().get(i);
            if (path.column() == N && path.isWhole()) {
                n = i;
            }
        }
        int position = n;
        Range all = new Range(1, max);
        // Capabilities applies the rules of the request, such as which filters may be taken over.
        Capabilities capabilities = Capabilities.filtersOnly(filter -> narrow(filter, position, all) != null);
        ScanPlan plan = capabilities.plan(table, SCHEMA, request, null);
        Range range = all;
        for (Expression filter : plan.acceptedFilters()) {
            range = narrow(filter, position, range);
        }
        return new ScanPlan(plan.table(), plan.output(), plan.columns(), plan.acceptedFilters(),
                plan.limitAccepted(), plan.orderAccepted(), plan.aggregationAccepted(), range);
    }

    /**
     * Narrows a range by a filter, or returns {@code null} if the filter does not narrow the range of {@code n}
     * exactly as the engine would evaluate it.
     */
    static Range narrow(Expression filter, int n, Range range) {
        return switch (filter) {
            case Comparison(ComparisonOp op, Column column, Literal literal) when column.index() == n ->
                    range.compare(op, wholeNumber(literal));
            case Comparison(ComparisonOp op, Literal literal, Column column) when column.index() == n ->
                    range.compare(flip(op), wholeNumber(literal));
            case Between(Column column, Literal low, Literal high) when column.index() == n ->
                    range.between(wholeNumber(low), wholeNumber(high));
            // Every other filter, also of a kind that a later version of FyrQL adds to Expression, stays with
            // the engine. Without this branch, a connector might take over what it does not understand.
            default -> null;
        };
    }

    /** Returns the value of an integer literal, or {@code null} for NULL and any other type. */
    private static Long wholeNumber(Literal literal) {
        return switch (literal.value()) {
            case Long value -> value;
            case Integer value -> value.longValue();
            case Short value -> value.longValue();
            case Byte value -> value.longValue();
            case null, default -> null;
        };
    }

    /** Turns {@code 10 < n} into {@code n > 10}. */
    private static ComparisonOp flip(ComparisonOp op) {
        return switch (op) {
            case LT -> ComparisonOp.GT;
            case LE -> ComparisonOp.GE;
            case GT -> ComparisonOp.LT;
            case GE -> ComparisonOp.LE;
            default -> op;
        };
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        return splits(plan, 1);
    }

    /** Divides the range into one part per thread, of at least {@link #ROWS_PER_SPLIT} rows each. */
    @Override
    public List<Split> splits(ScanPlan plan, int threads) {
        Range range = (Range) plan.state();
        long parts = Math.max(1, Math.min(threads, range.size() / ROWS_PER_SPLIT));
        List<Split> splits = new ArrayList<>();
        long from = range.low();
        for (int i = 0; i < parts; i++) {
            long rows = range.size() / parts + (i < range.size() % parts ? 1 : 0);
            splits.add(new Split(plan, i, new Range(from, from + rows - 1)));
            from += rows;
        }
        return splits;
    }

    @Override
    public BatchReader open(Split split) {
        Range range = (Range) split.state();
        // The columns of the table to deliver, in the order of the output.
        List<Integer> columns = split.plan().wholeColumns();
        Schema output = split.plan().output();
        return new BatchReader() {
            private long next = range.low();

            @Override
            public Batch next() {
                if (next > range.high()) {
                    return null;
                }
                int rows = (int) Math.min(batchSize, range.high() - next + 1);
                List<Vector> vectors = new ArrayList<>();
                for (int column : columns) {
                    vectors.add(switch (column) {
                        case 0 -> numbers(rows, false);
                        case 1 -> numbers(rows, true);
                        default -> evens(rows);
                    });
                }
                next += rows;
                computed.addAndGet(rows);
                return new Batch(output, rows, vectors);
            }

            private Vector numbers(int rows, boolean squared) {
                long[] values = new long[rows];
                for (int i = 0; i < rows; i++) {
                    long n = next + i;
                    values[i] = squared ? n * n : n;
                }
                return BigIntVector.of(values);
            }

            private Vector evens(int rows) {
                boolean[] values = new boolean[rows];
                for (int i = 0; i < rows; i++) {
                    values[i] = (next + i) % 2 == 0;
                }
                return BooleanVector.of(values);
            }
        };
    }

    private void check(TableRef table) {
        if (!table.schema().equals("main") || !table.table().equals(TABLE)) {
            throw new IllegalArgumentException("The sequence source " + name + " has no table " + table.schema() + "."
                    + table.table());
        }
    }

    /**
     * The numbers from {@code low} to {@code high}, both included; empty if {@code low} is greater. The values
     * that narrow it are clamped to just outside the numbers of the source, so that no arithmetic overflows.
     */
    record Range(long low, long high) {

        long size() {
            return Math.max(0, high - low + 1);
        }

        Range compare(ComparisonOp op, Long value) {
            if (value == null || op == ComparisonOp.NE) {
                // n <> v is no range, and a comparison with NULL holds for no row: the engine evaluates both.
                return null;
            }
            if (size() == 0) {
                return this;
            }
            long v = Math.clamp(value, low - 1, high + 1);
            return switch (op) {
                case EQ -> new Range(Math.max(low, v), Math.min(high, v));
                case LT -> new Range(low, Math.min(high, v - 1));
                case LE -> new Range(low, Math.min(high, v));
                case GT -> new Range(Math.max(low, v + 1), high);
                case GE -> new Range(Math.max(low, v), high);
                default -> null;
            };
        }

        Range between(Long from, Long to) {
            Range lower = compare(ComparisonOp.GE, from);
            return lower == null ? null : lower.compare(ComparisonOp.LE, to);
        }
    }
}
