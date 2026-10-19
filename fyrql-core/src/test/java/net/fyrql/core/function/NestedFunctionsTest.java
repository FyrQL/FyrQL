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

package net.fyrql.core.function;

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.nullOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The functions over lists, maps and structs, measured against DuckDB 1.5.6. */
class NestedFunctionsTest {

    private static final DataType INTS = DataType.listOf(DataType.INTEGER);
    private static final DataType STRING_TO_INT = DataType.mapOf(DataType.VARCHAR, DataType.INTEGER);

    private static final Batch ONE_ROW = Batch.ofRows(new Schema(List.of()), List.<Object[]>of(new Object[0]));

    private static Object eval(Expression e) {
        return Evaluator.eval(e, ONE_ROW).get(0);
    }

    private static Expression list(Object... values) {
        return new Expression.Literal(INTS, Arrays.asList(values));
    }

    private static Expression map(Object... keysAndValues) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new Expression.Literal(STRING_TO_INT, map);
    }

    @Test
    void positionsStartAtOneAndCountFromTheEnd() {
        Expression l = list(10, 20, 30);
        assertEquals(10, eval(call("list_extract", l, lit(1))));
        assertEquals(30, eval(call("list_element", l, lit(3L))));
        assertEquals(30, eval(call("array_extract", l, lit(-1))));
        assertEquals(10, eval(call("list_extract", l, lit(-3))));
        assertNull(eval(call("list_extract", l, lit(0))));
        assertNull(eval(call("list_extract", l, lit(4))));
        assertNull(eval(call("list_extract", l, lit(-4))));
        assertNull(eval(call("list_extract", l, lit(Long.MIN_VALUE))));
        assertNull(eval(call("list_extract", list(1, null), lit(2))));
        assertEquals("b", eval(call("list_extract", lit("abc"), lit(2))));
        assertEquals("c", eval(call("list_extract", lit("abc"), lit(-1))));
        assertEquals("", eval(call("list_extract", lit("abc"), lit(0))));
        assertEquals("", eval(call("list_extract", lit("abc"), lit(5))));
        assertEquals("", eval(call("list_extract", lit("abc"), lit(-4))));
    }

    @Test
    void aListHasTheCommonTypeOfItsElements() {
        Expression mixed = call("list_value", lit(1), lit(new BigDecimal("2.5")));
        assertEquals(DataType.listOf(DataType.decimal(11, 1)), mixed.type());
        assertEquals(List.of(new BigDecimal("1.0"), new BigDecimal("2.5")), eval(mixed));
        assertEquals(Arrays.asList(1, null), eval(call("list_value", lit(1), nullOf(DataType.INTEGER))));
        assertEquals(INTS, call("list_value").type());
        assertEquals(List.of(), eval(call("list_value")));
        assertThrows(IllegalArgumentException.class, () -> call("list_value", lit(1), lit("a")));
        assertEquals(3L, eval(call("len", list(1, null, 3))));
        assertEquals(0L, eval(call("length", list())));
        assertEquals(2L, eval(call("array_length", list(1, 2), lit(1))));
        assertThrows(IllegalArgumentException.class, () -> eval(call("array_length", list(1, 2), lit(2))));
    }

    @Test
    void containsComparesInTheCommonTypeAndNullsEqualNothing() {
        assertEquals(true, eval(call("list_contains", list(1, 2), lit(2))));
        assertEquals(false, eval(call("list_has", list(1, null), lit(3))));
        assertEquals(false, eval(call("array_contains", list(1, 2), lit(new BigDecimal("1.5")))));
        assertEquals(true, eval(call("array_has", list(1, 2), lit(new BigDecimal("2.0")))));
        assertNull(eval(call("list_contains", list(1, null), nullOf(DataType.INTEGER))));
        Expression doubles = new Expression.Literal(DataType.listOf(DataType.DOUBLE), List.of(Double.NaN, 0.0));
        assertEquals(true, eval(call("list_contains", doubles, lit(Double.NaN))));
        assertEquals(true, eval(call("list_contains", doubles, lit(-0.0))));
        assertThrows(IllegalArgumentException.class, () -> call("list_contains", list(1), lit("1")));
    }

    @Test
    void sortingIsStableWithNullsLastUnlessAsked() {
        Expression l = list(3, null, 1, 2);
        assertEquals(Arrays.asList(1, 2, 3, null), eval(call("list_sort", l)));
        assertEquals(Arrays.asList(3, 2, 1, null), eval(call("list_sort", l, lit("DESC"))));
        assertEquals(Arrays.asList(null, 1, 2, 3), eval(call("array_sort", l, lit("asc"), lit("nulls first"))));
        assertEquals(Arrays.asList(3, 2, 1, null), eval(call("list_sort", l, lit("DESCENDING"), lit("NULLS_LAST"))));
        assertEquals(Arrays.asList(null, 3, 2, 1), eval(call("list_reverse_sort", l, lit("NULLS FIRST"))));
        assertEquals(Arrays.asList(1, 2, 3, null), eval(call("list_sort", l, lit("DEFAULT"), lit("DEFAULT"))));
        assertNull(eval(call("list_sort", l, nullOf(DataType.VARCHAR))));
        Expression doubles = new Expression.Literal(DataType.listOf(DataType.DOUBLE),
                Arrays.asList(null, 1.0, Double.NaN, -0.0, 0.0));
        assertEquals(Arrays.asList(-0.0, 0.0, 1.0, Double.NaN, null), eval(call("list_sort", doubles)));
        assertEquals("Unrecognized sort order \"UP\"",
                assertThrows(IllegalArgumentException.class, () -> call("list_sort", l, lit("UP"))).getMessage());
        assertEquals("Unrecognized order of NULLs \"FIRST\"", assertThrows(IllegalArgumentException.class,
                () -> call("list_sort", l, lit("ASC"), lit("FIRST"))).getMessage());
        Expression column = new Expression.Column(0, DataType.VARCHAR);
        assertEquals("Sorting order must be a constant", assertThrows(IllegalArgumentException.class,
                () -> call("list_sort", l, column)).getMessage());
    }

    @Test
    void mapsHaveUniqueKeysThatAreNeverNull() {
        Expression keys = new Expression.Literal(DataType.listOf(DataType.VARCHAR), List.of("a", "b"));
        Map<Object, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1);
        expected.put("b", 2);
        assertEquals(expected, eval(call("map", keys, list(1, 2))));
        assertEquals(DataType.mapOf(DataType.INTEGER, DataType.INTEGER), call("map").type());
        assertEquals(Map.of(), eval(call("map")));
        assertEquals("The map key list does not align with the map value list.", assertThrows(
                ConversionException.class, () -> eval(call("map", keys, list(1)))).getMessage());
        Expression twice = new Expression.Literal(DataType.listOf(DataType.VARCHAR), List.of("a", "a"));
        assertEquals("Map keys must be unique.", assertThrows(ConversionException.class,
                () -> eval(call("map", twice, list(1, 2)))).getMessage());
        Expression withNull = new Expression.Literal(DataType.listOf(DataType.VARCHAR), Arrays.asList("a", null));
        assertEquals("Map keys can not be NULL.", assertThrows(ConversionException.class,
                () -> eval(call("map", withNull, list(1, 2)))).getMessage());
    }

    @Test
    void mapExtractGivesAListAndTheIndexTheValue() {
        Expression m = map("a", 1, "b", null);
        assertEquals(List.of(1), eval(call("map_extract", m, lit("a"))));
        assertEquals(Arrays.asList((Object) null), eval(call("element_at", m, lit("b"))));
        assertEquals(List.of(), eval(call("map_extract", m, lit("z"))));
        assertEquals(List.of(), eval(call("map_extract", m, nullOf(DataType.VARCHAR))));
        assertNull(eval(call("map_extract", nullOf(STRING_TO_INT), lit("a"))));
        assertEquals(1, eval(call("map_extract_value", m, lit("a"))));
        assertNull(eval(call("map_extract_value", m, lit("A"))), "keys are compared exactly");
        Expression numbers = new Expression.Literal(DataType.mapOf(DataType.INTEGER, DataType.VARCHAR),
                new LinkedHashMap<>(Map.of(1, "x")));
        assertEquals("x", eval(call("map_extract_value", numbers, lit(1L))));
        assertEquals("x", eval(call("map_extract_value", numbers, lit(new BigDecimal("1.0")))));
        assertThrows(IllegalArgumentException.class, () -> call("map_extract", numbers, lit("1")));
    }

    @Test
    void structsPackAndExtractNamedFields() {
        StructPack pack = new StructPack(List.of("a", "B"));
        Expression s = new Expression.Call(pack, List.of(lit(1), nullOf(DataType.VARCHAR)));
        assertEquals("STRUCT(a INTEGER, B VARCHAR)", s.type().sqlName());
        assertEquals(Arrays.asList(1, null), eval(s));
        Expression field = new Expression.Call(new StructExtract(1, "B"), List.of(s, lit("B")));
        assertEquals(DataType.VARCHAR, field.type());
        assertNull(eval(field));
        assertEquals(1, eval(new Expression.Call(new StructExtract(0, "a"), List.of(s, lit("a")))));
        assertThrows(IllegalArgumentException.class,
                () -> new Expression.Call(new StructExtract(1, "b"), List.of(s, lit("b"))));
        assertEquals("Duplicate struct entry name \"A\"", assertThrows(IllegalArgumentException.class,
                () -> new StructPack(List.of("a", "A"))).getMessage());
        assertEquals("Can't pack nothing into a struct", assertThrows(IllegalArgumentException.class,
                () -> new StructPack(new ArrayList<>())).getMessage());
    }
}
