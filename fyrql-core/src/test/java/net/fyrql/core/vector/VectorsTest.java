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

package net.fyrql.core.vector;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.value.DataGeneratorTest;
import net.fyrql.core.value.Keys;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorsTest {

    @Test
    void everyTypeRoundTripsThroughABatch() {
        Schema schema = new Schema(DataGeneratorTest.ALL_TYPES.stream().map(t -> new Schema.Field(t.sqlName(), t))
                .toList());
        List<Object[]> rows = DataGenerator.withSeed(3).generate(schema, 300);
        Batch batch = Batch.ofRows(schema, rows);
        assertEquals(300, batch.rowCount());
        List<Object[]> back = batch.rows();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < schema.size(); c++) {
                Object expected = Keys.canonical(rows.get(r)[c]);
                Object actual = Keys.canonical(back.get(r)[c]);
                assertTrue(Keys.equalObjects(expected, actual), schema.type(c) + " row " + r + ": " + expected
                        + " became " + actual);
            }
        }
        // Selecting rows keeps values and NULLs; the order follows the selection.
        int[] selection = {299, 0, 7, 7, 150};
        Batch selected = batch.select(selection);
        for (int i = 0; i < selection.length; i++) {
            for (int c = 0; c < schema.size(); c++) {
                assertTrue(Keys.equalObjects(Keys.canonical(rows.get(selection[i])[c]),
                        Keys.canonical(selected.column(c).get(i))), schema.type(c) + " selected row " + i);
            }
        }
    }

    @Test
    void orderEqualityAndHashAgree() {
        Schema schema = new Schema(DataGeneratorTest.ALL_TYPES.stream().map(t -> new Schema.Field(t.sqlName(), t))
                .toList());
        List<Object[]> rows = DataGenerator.withSeed(11).generate(schema, 120);
        Batch batch = Batch.ofRows(schema, rows);
        for (int c = 0; c < schema.size(); c++) {
            Vector v = batch.column(c);
            for (int i = 0; i < v.size(); i++) {
                for (int j = 0; j < v.size(); j++) {
                    int cij = Vectors.compareNullable(v, i, v, j);
                    int cji = Vectors.compareNullable(v, j, v, i);
                    assertEquals(Integer.signum(cij), -Integer.signum(cji), schema.type(c) + " antisymmetric " + i + "," + j);
                    if (cij == 0) {
                        assertEquals(Vectors.hash(v, i), Vectors.hash(v, j), schema.type(c) + " hash of equal values "
                                + v.get(i) + " and " + v.get(j));
                        assertTrue(Vectors.equal(v, i, v, j));
                    } else {
                        assertFalse(Vectors.equal(v, i, v, j));
                    }
                }
            }
        }
    }

    @Test
    void stringsCompareByCodePoint() {
        // U+FF5C is one code point below U+1F600 in code point order but above it in UTF-16 code units.
        BytesVector v = BytesVector.ofStrings("\uD83D\uDE00", "\uFF5C", "a", "B", "", "ab");
        assertTrue(Vectors.compare(v, 1, v, 0) < 0, "U+FF5C < U+1F600");
        assertTrue("\uFF5C".compareTo("\uD83D\uDE00") > 0, "Java would say the opposite");
        assertTrue(Vectors.compare(v, 3, v, 2) < 0, "'B' < 'a'");
        assertTrue(Vectors.compare(v, 4, v, 2) < 0, "'' < 'a'");
        assertTrue(Vectors.compare(v, 2, v, 5) < 0, "'a' < 'ab'");
        assertEquals("ab", v.getString(5));
        assertEquals(2, v.length(5));
    }

    @Test
    void floatingPointOrderAndKeys() {
        DoubleVector v = DoubleVector.of(-0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, -1.5,
                Double.longBitsToDouble(0x7ff8000000000001L));
        assertEquals(0, Vectors.compare(v, 0, v, 1), "-0.0 = 0.0");
        assertEquals(Vectors.hash(v, 0), Vectors.hash(v, 1));
        assertTrue(Vectors.compare(v, 3, v, 2) < 0, "infinity < NaN");
        assertEquals(0, Vectors.compare(v, 2, v, 5), "every NaN is one NaN");
        assertEquals(Vectors.hash(v, 2), Vectors.hash(v, 5));
        assertTrue(Vectors.compare(v, 4, v, 0) < 0);
    }

    @Test
    void nullsAndNested() {
        DataType.Struct s = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("l", DataType.listOf(DataType.VARCHAR)));
        Vector v = Vectors.of(s, List.of(1, List.of("x", "y")), null, Arrays.asList(null, List.of()),
                List.of(1, Arrays.asList("x", null)));
        assertEquals(4, v.size());
        assertTrue(v.isNull(1));
        assertNull(v.get(1));
        assertEquals(List.of(1, List.of("x", "y")), v.get(0));
        assertEquals(Arrays.asList(null, List.of()), v.get(2));
        assertTrue(Vectors.compare(v, 0, v, 3) < 0, "'y' < NULL inside a list");
        assertTrue(Vectors.compare(v, 0, v, 2) < 0, "1 < NULL in a field");
        StructVector sv = (StructVector) v.select(new int[] {3, 1});
        assertEquals(List.of(1, Arrays.asList("x", null)), sv.get(0));
        assertTrue(sv.isNull(1));
        assertTrue(sv.child(0).isNull(1));

        DecimalVector big = (DecimalVector) Vectors.of(DataType.decimal(30, 5), new BigDecimal("1.50000"), null);
        assertFalse(big.isSmall());
        assertEquals(new BigDecimal("1.50000"), big.get(0));
        DecimalVector small = (DecimalVector) Vectors.of(DataType.decimal(10, 2), new BigDecimal("1.50"));
        assertEquals(Vectors.hash(big, 0), Vectors.hash(small, 0), "1.50000 and 1.50 hash alike");
        assertEquals(0, Vectors.compare(big, 0, small, 0));
    }

    @Test
    void buildersGrow() {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            values.add(i % 7 == 0 ? null : "s" + i);
        }
        BytesVector v = (BytesVector) Vectors.of(DataType.VARCHAR, values);
        assertEquals(5000, v.size());
        assertEquals("s4999", v.getString(4999));
        assertTrue(v.isNull(4998));
        ListVector l = (ListVector) Vectors.of(DataType.listOf(DataType.BIGINT),
                java.util.Collections.nCopies(3000, List.of(1L, 2L, 3L)));
        assertEquals(9000, l.child().size());
        assertEquals(List.of(1L, 2L, 3L), l.get(2999));
    }

    @Test
    void wrapTakesArraysWithoutCopying() {
        IntVector dates = IntVector.wrap(DataType.DATE, new int[] {1, 2, 3}, new boolean[] {false, true, false});
        assertEquals(DataType.DATE, dates.type());
        assertTrue(dates.isNull(1));
        assertEquals(3, dates.getInt(2));
        // A mask without a NULL is dropped.
        assertFalse(BigIntVector.wrap(DataType.TIMESTAMP, new long[] {5, 6}, new boolean[2]).hasNulls());
        assertFalse(DoubleVector.wrap(new double[] {0.5}, null).hasNulls());
        assertEquals(new BigDecimal("-1.25"),
                DecimalVector.wrap(DataType.decimal(5, 2), new long[] {-125}, null).getBigDecimal(0));
        assertEquals(java.math.BigInteger.ONE.shiftLeft(64),
                HugeIntVector.wrap(new long[] {1}, new long[] {0}, null).getBigInteger(0));
        assertThrows(IllegalArgumentException.class, () -> BooleanVector.wrap(new boolean[2], new boolean[3]));
        assertThrows(IllegalArgumentException.class,
                () -> DecimalVector.wrap(DataType.decimal(20, 2), new long[1], null));
        assertThrows(IllegalArgumentException.class, () -> IntVector.wrap(DataType.BIGINT, new int[1], null));
    }

    @Test
    void decimalHashWithoutBigIntegerAgrees() {
        java.util.SplittableRandom random = new java.util.SplittableRandom(7);
        List<Long> values = new ArrayList<>(List.of(0L, 1L, -1L, 10L, -10L, 100L, 999_999_999_999_999_999L,
                -999_999_999_999_999_999L, 4_294_967_296L, -4_294_967_296L, 4_294_967_295L, 2_147_483_648L, 1_000_000L));
        for (int i = 0; i < 2000; i++) {
            long v = random.nextLong(-999_999_999_999_999_999L, 999_999_999_999_999_999L);
            values.add(i % 3 == 0 ? v / 1000 * 1000 : i % 3 == 1 ? v % 100_000 : v);
        }
        for (long v : values) {
            for (int scale = 0; scale <= 18; scale += 3) {
                java.math.BigInteger unscaled = java.math.BigInteger.valueOf(v);
                int s = scale;
                while (s > 0 && unscaled.signum() != 0 && unscaled.mod(java.math.BigInteger.TEN).signum() == 0) {
                    unscaled = unscaled.divide(java.math.BigInteger.TEN);
                    s--;
                }
                int expected = unscaled.hashCode() * 31 + (unscaled.signum() == 0 ? 0 : s);
                assertEquals(expected, Vectors.hashDecimal(v, scale), v + " scale " + scale);
            }
        }
    }
    @Test
    void concatAndGatherKeepValuesAndNulls() {
        Schema schema = new Schema(DataGeneratorTest.ALL_TYPES.stream().map(t -> new Schema.Field(t.sqlName(), t))
                .toList());
        java.util.SplittableRandom random = new java.util.SplittableRandom(20261016);
        for (int round = 0; round < 20; round++) {
            List<Object[]> rows = DataGenerator.withSeed(round).generate(schema, 1 + random.nextInt(200));
            Batch batch = Batch.ofRows(schema, rows);
            // Parts of random sizes, some empty, some selected out of order.
            List<Batch> parts = new ArrayList<>();
            List<Object[]> expected = new ArrayList<>();
            for (int from = 0; from < rows.size(); ) {
                int n = Math.min(random.nextInt(40), rows.size() - from);
                int[] selection = new int[n];
                for (int i = 0; i < n; i++) {
                    selection[i] = from + (random.nextBoolean() ? i : n - 1 - i);
                    expected.add(rows.get(selection[i]));
                }
                parts.add(batch.select(selection));
                from += n;
            }
            for (int c = 0; c < schema.size(); c++) {
                DataType type = schema.type(c);
                List<Vector> columns = new ArrayList<>();
                for (Batch p : parts) {
                    columns.add(p.column(c));
                }
                Vector all = Vectors.concat(type, columns);
                assertEquals(type, all.type());
                assertEquals(expected.size(), all.size(), type + " concat");
                for (int r = 0; r < expected.size(); r++) {
                    assertSame(expected.get(r)[c], all, r, type + " concat row " + r);
                }
                Vector v = batch.column(c);
                int[] gathered = new int[random.nextInt(60)];
                for (int i = 0; i < gathered.length; i++) {
                    gathered[i] = random.nextInt(4) == 0 ? -1 : random.nextInt(v.size());
                }
                Vector g = Vectors.gather(v, gathered);
                assertEquals(type, g.type());
                assertEquals(gathered.length, g.size(), type + " gather");
                for (int i = 0; i < gathered.length; i++) {
                    assertSame(gathered[i] < 0 ? null : rows.get(gathered[i])[c], g, i, type + " gather row " + i);
                    assertEquals(gathered[i] < 0 ? 0 : Vectors.hash(v, gathered[i]), Vectors.hash(g, i),
                            type + " hash of gathered row " + i);
                }
            }
        }
        assertEquals(0, Vectors.concat(DataType.VARCHAR, List.of()).size());
        assertThrows(IllegalArgumentException.class, () -> Vectors.concat(DataType.INTEGER,
                List.of(IntVector.of(1), BigIntVector.of(2L))));
        Vector none = Vectors.gather(Vectors.of(DataType.listOf(DataType.INTEGER)), new int[] {-1, -1});
        assertTrue(none.isNull(0) && none.isNull(1));
    }

    private static void assertSame(Object expected, Vector v, int row, String what) {
        assertEquals(expected == null, v.isNull(row), what);
        assertTrue(Keys.equalObjects(Keys.canonical(expected), Keys.canonical(v.get(row))), what + ": " + expected
                + " became " + v.get(row));
    }
}
