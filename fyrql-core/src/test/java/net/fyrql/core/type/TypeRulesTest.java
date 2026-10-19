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

package net.fyrql.core.type;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeRulesTest {

    @Test
    void integralTypesKeepTheWiderOne() {
        assertEquals(DataType.TINYINT, TypeRules.numeric(DataType.TINYINT, DataType.TINYINT));
        assertEquals(DataType.INTEGER, TypeRules.numeric(DataType.TINYINT, DataType.INTEGER));
        assertEquals(DataType.HUGEINT, TypeRules.numeric(DataType.BIGINT, DataType.HUGEINT));
    }

    @Test
    void floatingPointAbsorbs() {
        assertEquals(DataType.FLOAT, TypeRules.numeric(DataType.FLOAT, DataType.BIGINT));
        assertEquals(DataType.DOUBLE, TypeRules.numeric(DataType.FLOAT, DataType.DOUBLE));
        assertEquals(DataType.FLOAT, TypeRules.numeric(DataType.FLOAT, DataType.decimal(10, 2)));
        assertEquals(DataType.DOUBLE, TypeRules.divide(DataType.TINYINT, DataType.TINYINT));
        assertEquals(DataType.FLOAT, TypeRules.divide(DataType.TINYINT, DataType.FLOAT));
        assertEquals(DataType.DOUBLE, TypeRules.divide(DataType.decimal(10, 2), DataType.decimal(10, 2)));
        assertEquals(DataType.DOUBLE, TypeRules.intDivide(DataType.decimal(10, 2), DataType.TINYINT));
        assertEquals(DataType.INTEGER, TypeRules.intDivide(DataType.INTEGER, DataType.TINYINT));
    }

    @Test
    void decimals() {
        assertEquals(DataType.decimal(11, 2), TypeRules.numeric(DataType.decimal(10, 2), DataType.decimal(10, 2)));
        assertEquals(DataType.decimal(12, 3), TypeRules.numeric(DataType.decimal(10, 2), DataType.decimal(5, 3)));
        assertEquals(DataType.decimal(13, 2), TypeRules.numeric(DataType.decimal(10, 2), DataType.INTEGER));
        // The carry digit does not push a 64-bit decimal into 128 bits.
        assertEquals(DataType.decimal(18, 6), TypeRules.numeric(DataType.decimal(18, 6), DataType.decimal(18, 6)));
        assertEquals(DataType.decimal(26, 6), TypeRules.numeric(DataType.BIGINT, DataType.decimal(18, 6)));
        assertEquals(DataType.decimal(38, 2), TypeRules.numeric(DataType.decimal(38, 2), DataType.decimal(38, 2)));
        // Operands of at most 18 digits give at most 18, whatever the sum of their digits.
        assertEquals(DataType.decimal(18, 10), TypeRules.numeric(DataType.decimal(12, 10), DataType.INTEGER));
        assertEquals(DataType.decimal(29, 18), TypeRules.numeric(DataType.decimal(19, 18), DataType.INTEGER));
        assertEquals(DataType.decimal(18, 4), TypeRules.multiply(DataType.decimal(10, 2), DataType.decimal(10, 2)));
        assertEquals(DataType.decimal(29, 2), TypeRules.multiply(DataType.BIGINT, DataType.decimal(10, 2)));
        assertEquals(DataType.decimal(38, 20), TypeRules.multiply(DataType.decimal(38, 10), DataType.decimal(38, 10)));
        assertEquals(DataType.decimal(12, 2), TypeRules.remainder(DataType.INTEGER, DataType.decimal(10, 2)));
        assertEquals(DataType.DOUBLE, TypeRules.remainder(DataType.HUGEINT, DataType.decimal(10, 2)));
        // A product with a scale of 18 or more is not held to 18 digits.
        assertEquals(DataType.decimal(36, 24), TypeRules.multiply(DataType.decimal(18, 12), DataType.decimal(18, 12)));
        assertEquals(DataType.decimal(23, 20), TypeRules.multiply(DataType.decimal(11, 10), DataType.decimal(12, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeRules.multiply(DataType.decimal(38, 20), DataType.decimal(38, 20)));
    }

    @Test
    void commonTypes() {
        assertEquals(DataType.TIMESTAMP, TypeRules.common(DataType.DATE, DataType.TIMESTAMP));
        assertEquals(DataType.VARCHAR, TypeRules.common(List.of(DataType.VARCHAR, DataType.VARCHAR)));
        assertEquals(DataType.listOf(DataType.INTEGER),
                TypeRules.common(DataType.listOf(DataType.INTEGER), DataType.listOf(DataType.INTEGER)));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.common(DataType.VARCHAR, DataType.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.numeric(DataType.DATE, DataType.INTEGER));
    }

    @Test
    void typeNamesAndValues() {
        DataType.Struct s = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("l", DataType.listOf(DataType.VARCHAR)));
        assertEquals("STRUCT(a INTEGER, l VARCHAR[])", s.sqlName());
        assertEquals("MAP(VARCHAR, DECIMAL(10,2))", DataType.mapOf(DataType.VARCHAR, DataType.decimal(10, 2)).sqlName());
        assertTrue(s.accepts(List.of(1, List.of("x"))));
        assertFalse(s.accepts(List.of(1L, List.of("x"))));
        assertTrue(DataType.decimal(10, 2).accepts(new BigDecimal("1.50")));
        assertFalse(DataType.decimal(10, 2).accepts(new BigDecimal("1.5")));
        assertFalse(DataType.decimal(3, 2).accepts(new BigDecimal("12.50")));
        assertTrue(DataType.HUGEINT.accepts(Values.HUGEINT_MAX));
        assertFalse(DataType.HUGEINT.accepts(Values.HUGEINT_MAX.add(java.math.BigInteger.ONE)));
        assertEquals(DataType.decimal(19, 0), TypeRules.asDecimal(DataType.BIGINT));
    }

    @Test
    void nestedTypesUniteByElementAndStructsByName() {
        DataType.Struct a = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER));
        DataType.Struct b = DataType.struct(new DataType.Struct.Field("b", DataType.INTEGER));
        assertEquals(DataType.listOf(DataType.decimal(11, 1)),
                TypeRules.common(DataType.listOf(DataType.INTEGER), DataType.listOf(DataType.decimal(2, 1))));
        assertEquals(DataType.mapOf(DataType.VARCHAR, DataType.DOUBLE),
                TypeRules.common(DataType.mapOf(DataType.VARCHAR, DataType.INTEGER),
                        DataType.mapOf(DataType.VARCHAR, DataType.DOUBLE)));
        assertEquals("STRUCT(a INTEGER, b INTEGER)", TypeRules.common(a, b).sqlName());
        assertEquals("STRUCT(b INTEGER, a INTEGER)", TypeRules.common(b, a).sqlName());
        assertEquals("STRUCT(a DOUBLE)", TypeRules.common(a,
                DataType.struct(new DataType.Struct.Field("A", DataType.DOUBLE))).sqlName());
        assertEquals("STRUCT(a INTEGER, b INTEGER)[]",
                TypeRules.common(DataType.listOf(a), DataType.listOf(b)).sqlName());
        assertThrows(IllegalArgumentException.class,
                () -> TypeRules.common(DataType.listOf(DataType.INTEGER), DataType.listOf(DataType.VARCHAR)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeRules.common(DataType.listOf(DataType.INTEGER), DataType.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.common(a, DataType.listOf(DataType.INTEGER)));
    }
}
