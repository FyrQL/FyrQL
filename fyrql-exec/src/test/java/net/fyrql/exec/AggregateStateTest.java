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

package net.fyrql.exec;

import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.EdgeValues;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The aggregates without boxing give the results and the errors of the
 * accumulator objects: on random values mixed with the edge values of each
 * type, so that sums overflow a {@code long}, exceed 2^53 and the range of
 * their result, with NaN, {@code -0.0} and {@code NULL}s, with zero, one and
 * two group keys, and over batches of every size.
 */
class AggregateStateTest {

    private static final List<DataType> VALUE_TYPES = List.of(DataType.BOOLEAN, DataType.TINYINT, DataType.SMALLINT,
            DataType.INTEGER, DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE,
            DataType.decimal(15, 2), DataType.decimal(18, 18), DataType.decimal(4, 0), DataType.decimal(30, 6),
            DataType.decimal(38, 0), DataType.DATE, DataType.TIMESTAMP, DataType.VARCHAR);

    private static final List<AggregateFunction> FUNCTIONS = List.of(AggregateFunction.COUNT, AggregateFunction.SUM,
            AggregateFunction.AVG, AggregateFunction.MIN, AggregateFunction.MAX, AggregateFunction.STDDEV_SAMP,
            AggregateFunction.STDDEV_POP, AggregateFunction.VAR_SAMP, AggregateFunction.VAR_POP);

    private static final List<AggregateFunction> BINARY = List.of(AggregateFunction.CORR,
            AggregateFunction.COVAR_POP, AggregateFunction.COVAR_SAMP, AggregateFunction.REGR_COUNT,
            AggregateFunction.REGR_SLOPE, AggregateFunction.REGR_INTERCEPT, AggregateFunction.REGR_R2,
            AggregateFunction.REGR_SXY, AggregateFunction.ARG_MIN, AggregateFunction.ARG_MAX);

    private static final Schema SCHEMA = schema();

    private static Schema schema() {
        List<Schema.Field> fields = new ArrayList<>();
        fields.add(new Schema.Field("g1", DataType.INTEGER));
        fields.add(new Schema.Field("g2", DataType.VARCHAR));
        for (int i = 0; i < VALUE_TYPES.size(); i++) {
            fields.add(new Schema.Field("v" + i, VALUE_TYPES.get(i)));
        }
        return new Schema(fields);
    }

    @Test
    void unboxedStatesGiveTheResultsOfTheAccumulators() {
        SplittableRandom random = new SplittableRandom(20261016);
        int failures = 0;
        for (int round = 0; round < 300; round++) {
            List<Object[]> rows = rows(random, random.nextInt(4) == 0 ? random.nextInt(3) : random.nextInt(400));
            List<Integer> keys = switch (random.nextInt(3)) {
                case 0 -> List.of();
                case 1 -> List.of(random.nextInt(2));
                default -> List.of(0, 1);
            };
            List<AggregateCall> calls = new ArrayList<>();
            calls.add(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"));
            for (int k = 0; k < 6; k++) {
                int column = 2 + random.nextInt(VALUE_TYPES.size());
                AggregateFunction function = FUNCTIONS.get(random.nextInt(FUNCTIONS.size()));
                AggregateCall call = new AggregateCall(function, column, random.nextInt(10) == 0, "a" + k);
                int orderBy = 2 + random.nextInt(VALUE_TYPES.size());
                try {
                    if (random.nextInt(10) == 0) {
                        call = call.withOrderBy(List.of(SortKey.asc(orderBy)));
                    }
                    call.check(SCHEMA);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                calls.add(call);
            }
            for (int k = 0; k < 2; k++) {
                // Functions of two columns: the covariances over the doubles mostly, arg_min and arg_max over any.
                AggregateFunction function = BINARY.get(random.nextInt(BINARY.size()));
                int column = 2 + random.nextInt(VALUE_TYPES.size());
                int second = 2 + random.nextInt(VALUE_TYPES.size());
                if (function.isCovariance() && random.nextBoolean()) {
                    column = 2 + VALUE_TYPES.indexOf(random.nextBoolean() ? DataType.DOUBLE : DataType.FLOAT);
                    second = 2 + VALUE_TYPES.indexOf(DataType.DOUBLE);
                }
                AggregateCall call = AggregateCall.of(function, column, second, random.nextInt(10) == 0, "b" + k);
                try {
                    call.check(SCHEMA);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                calls.add(call);
            }
            int batchSize = 1 + random.nextInt(64);
            String what = "round " + round + " keys " + keys + " calls " + calls;
            Object unboxed = run(rows, keys, calls, batchSize, true);
            Object boxed = run(rows, keys, calls, batchSize, false);
            if (boxed instanceof RuntimeException e) {
                failures++;
                if (!(unboxed instanceof RuntimeException u)) {
                    fail(what + ": the accumulators fail with " + e + ", the states give " + unboxed);
                    return;
                }
                assertEquals(e.getClass(), u.getClass(), what);
                assertEquals(e.getMessage(), u.getMessage(), what);
            } else {
                assertEquals(boxed, unboxed, what);
            }
        }
        // Some sums of HUGEINTs and of DECIMAL(38,0)s overflow; the test must see such failures.
        assertTrue(failures > 0, "no round failed");
    }

    /** Runs an aggregation and returns its rows, or the error it fails with. */
    private static Object run(List<Object[]> rows, List<Integer> keys, List<AggregateCall> calls, int batchSize,
            boolean unboxed) {
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "data"), SCHEMA);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, keys, calls);
        List<Batch> batches = rows.isEmpty() ? List.of() : Batches.split(Batch.ofRows(SCHEMA, rows), batchSize);
        Operator input = new ScanOperator("Scan#1", scan, s -> Batches.reader(batches));
        List<List<Object>> result = new ArrayList<>();
        try (Operator op = new HashAggregateOperator("Aggregate#0", aggregate, input, MemoryBudget.unlimited(), 7,
                Codegen.off(), unboxed)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                for (Object[] row : b.rows()) {
                    result.add(Arrays.asList(row));
                }
            }
        } catch (RuntimeException e) {
            return e;
        }
        return result;
    }

    /** Rows with few distinct keys and values that are random, edge values or {@code NULL}. */
    private static List<Object[]> rows(SplittableRandom random, int n) {
        List<Object[]> rows = new ArrayList<>();
        // Rounds with mostly edge values let sums overflow; the others keep them small enough to succeed.
        boolean edgy = random.nextBoolean();
        for (int r = 0; r < n; r++) {
            Object[] row = new Object[SCHEMA.size()];
            row[0] = random.nextInt(8) == 0 ? null : random.nextInt(5);
            row[1] = random.nextInt(8) == 0 ? null : List.of("a", "b", "c").get(random.nextInt(3));
            for (int c = 2; c < row.length; c++) {
                DataType type = SCHEMA.type(c);
                int pick = random.nextInt(10);
                if (pick == 0) {
                    row[c] = null;
                } else if (type.isFloatingPoint() && pick == 2) {
                    // Equal values that differ, of which min and max keep the first.
                    boolean negative = random.nextBoolean();
                    row[c] = type == DataType.FLOAT ? (Object) (negative ? -0.0f : 0.0f) : (Object) (negative ? -0.0 : 0.0);
                } else if (edgy && pick < 6 || pick == 1) {
                    List<Object> edges = EdgeValues.of(type);
                    row[c] = edges.get(random.nextInt(edges.size()));
                } else {
                    row[c] = DataGenerator.randomValue(type, random, 0);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    @Test
    void minAndMaxKeepTheFirstOfEqualValues() {
        Schema s = Schema.of(DataType.INTEGER, DataType.DOUBLE, DataType.FLOAT);
        List<Object[]> rows = List.of(new Object[] {0, -0.0, -0.0f}, new Object[] {0, 0.0, 0.0f},
                new Object[] {1, 0.0, 0.0f}, new Object[] {1, -0.0, -0.0f}, new Object[] {2, Double.NaN, Float.NaN},
                new Object[] {2, 1.0, 1.0f});
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "zeros"), s);
        List<AggregateCall> calls = new ArrayList<>();
        for (AggregateFunction f : List.of(AggregateFunction.MIN, AggregateFunction.MAX)) {
            calls.add(new AggregateCall(f, 1, false, f + "d"));
            calls.add(new AggregateCall(f, 2, false, f + "f"));
        }
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(0), calls);
        for (boolean unboxed : new boolean[] {false, true}) {
            Operator input = new ScanOperator("Scan#1", scan, x -> Batches.reader(Batches.split(Batch.ofRows(s, rows),
                    1)));
            try (Operator op = new HashAggregateOperator("Aggregate#0", aggregate, input, MemoryBudget.unlimited(), 7,
                    Codegen.off(), unboxed)) {
                List<List<Object>> result = op.next().rows().stream().map(Arrays::asList).toList();
                assertEquals(List.of(List.of(0, -0.0, -0.0f, -0.0, -0.0f), List.of(1, 0.0, 0.0f, 0.0, 0.0f),
                        List.of(2, 1.0, 1.0f, Double.NaN, Float.NaN)), result, "unboxed " + unboxed);
            }
        }
    }

    @Test
    void theStatesCoverTheCommonAggregates() {
        Schema s = SCHEMA;
        int bigint = 2 + VALUE_TYPES.indexOf(DataType.BIGINT);
        int decimal = 2 + VALUE_TYPES.indexOf(DataType.decimal(30, 6));
        int huge = 2 + VALUE_TYPES.indexOf(DataType.HUGEINT);
        int varchar = 2 + VALUE_TYPES.indexOf(DataType.VARCHAR);
        int date = 2 + VALUE_TYPES.indexOf(DataType.DATE);
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "x"), s));
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.SUM, bigint, false, "x"), s));
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.AVG, decimal, false, "x"), s));
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.MIN, date, false, "x"), s));
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.COUNT, varchar, false, "x"), s));
        int dbl = 2 + VALUE_TYPES.indexOf(DataType.DOUBLE);
        int flt = 2 + VALUE_TYPES.indexOf(DataType.FLOAT);
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.VAR_POP, dbl, false, "x"), s));
        assertNotNull(AggregateState.of(new AggregateCall(AggregateFunction.STDDEV_SAMP, flt, false, "x"), s));
        assertNotNull(AggregateState.of(AggregateCall.of(AggregateFunction.CORR, flt, dbl, false, "x"), s));
        assertNotNull(AggregateState.of(AggregateCall.of(AggregateFunction.ARG_MIN, varchar, date, false, "x"), s));
        assertNull(AggregateState.of(AggregateCall.of(AggregateFunction.REGR_SLOPE, dbl, bigint, false, "x"), s));
        // The binder casts the other numbers to DOUBLE; a plan without it takes the accumulators.
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.VAR_SAMP, bigint, false, "x"), s));
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.SUM, huge, false, "x"), s));
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.MAX, decimal, false, "x"), s));
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.MIN, varchar, false, "x"), s));
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.COUNT, bigint, true, "x"), s));
        assertNull(AggregateState.of(new AggregateCall(AggregateFunction.SUM, bigint, false, "x")
                .withOrderBy(List.of(SortKey.asc(0))), s));
    }

    @Test
    void aLongBecomesTheDoubleOfItsBigInteger() {
        SplittableRandom random = new SplittableRandom(7);
        List<Long> values = new ArrayList<>(List.of(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, (1L << 53) + 1,
                -(1L << 53) - 1, -(1L << 53) - 3, -(1L << 62) - 1));
        for (int i = 0; i < 100_000; i++) {
            values.add(random.nextLong() >> random.nextInt(64));
        }
        for (long x : values) {
            assertEquals(Double.doubleToRawLongBits(HashAggregateOperator.toDouble(BigInteger.valueOf(x))),
                    Double.doubleToRawLongBits(AggregateState.toDouble(x)), Long.toString(x));
        }
    }

    @Test
    void sumsGrowBeyondALongAndAveragesKeepTheirRounding() {
        Schema s = Schema.of(DataType.BIGINT, DataType.decimal(18, 2));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new Object[] {Long.MAX_VALUE - i, new BigDecimal("9999999999999999.99")});
        }
        rows.add(new Object[] {-(1L << 53) - 3, new BigDecimal("-0.01")});
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "big"), s);
        PlanNode.Aggregate aggregate = new PlanNode.Aggregate(scan, List.of(), List.of(
                new AggregateCall(AggregateFunction.SUM, 0, false, "s"),
                new AggregateCall(AggregateFunction.AVG, 0, false, "a"),
                new AggregateCall(AggregateFunction.SUM, 1, false, "d"),
                new AggregateCall(AggregateFunction.AVG, 1, false, "e")));
        List<Object> results = new ArrayList<>();
        for (boolean unboxed : new boolean[] {false, true}) {
            Operator input = new ScanOperator("Scan#1", scan, x -> Batches.reader(Batches.split(Batch.ofRows(s, rows),
                    2)));
            try (Operator op = new HashAggregateOperator("Aggregate#0", aggregate, input, MemoryBudget.unlimited(), 7,
                    Codegen.off(), unboxed)) {
                results.add(Arrays.asList(op.next().row(0)));
            }
        }
        assertEquals(results.get(0), results.get(1));
        BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)
                .subtract(BigInteger.valueOf(45)).subtract(BigInteger.ONE.shiftLeft(53)).subtract(BigInteger.valueOf(3));
        assertEquals(expected, ((List<?>) results.get(1)).get(0));
        assertEquals(new BigDecimal("99999999999999999.89"), ((List<?>) results.get(1)).get(2));
    }
}
