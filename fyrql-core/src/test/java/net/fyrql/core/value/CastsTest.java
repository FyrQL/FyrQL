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

package net.fyrql.core.value;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Values;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cast semantics measured against DuckDB 1.5.6. */
class CastsTest {

    @Test
    void floatingPointToIntegralRoundsHalfToEven() {
        assertEquals(2, Casts.cast(1.7, DataType.DOUBLE, DataType.INTEGER));
        assertEquals(2, Casts.cast(2.5, DataType.DOUBLE, DataType.INTEGER));
        assertEquals(-2, Casts.cast(-2.5, DataType.DOUBLE, DataType.INTEGER));
        assertEquals(4, Casts.cast(3.5, DataType.DOUBLE, DataType.INTEGER));
        assertEquals((byte) 0, Casts.cast(0.49999999999999994, DataType.DOUBLE, DataType.TINYINT));
        assertEquals(2L, Casts.cast(1.5f, DataType.FLOAT, DataType.BIGINT));
        assertEquals(BigInteger.valueOf(130), Casts.cast(130.5, DataType.DOUBLE, DataType.HUGEINT));
        assertEquals(BigInteger.valueOf(132), Casts.cast(131.5, DataType.DOUBLE, DataType.HUGEINT));
        assertThrows(ArithmeticException.class, () -> Casts.cast(Double.NaN, DataType.DOUBLE, DataType.INTEGER));
        assertThrows(ArithmeticException.class, () -> Casts.cast(1e10, DataType.DOUBLE, DataType.INTEGER));
    }

    @Test
    void outOfRangeIsAnError() {
        assertThrows(ArithmeticException.class, () -> Casts.cast(300, DataType.INTEGER, DataType.TINYINT));
        assertThrows(ArithmeticException.class,
                () -> Casts.cast(Values.HUGEINT_MAX, DataType.HUGEINT, DataType.BIGINT));
        assertThrows(ArithmeticException.class,
                () -> Casts.cast(new BigDecimal("123.45"), DataType.decimal(5, 2), DataType.decimal(4, 2)));
        assertEquals((byte) 127, Casts.cast(127L, DataType.BIGINT, DataType.TINYINT));
    }

    @Test
    void decimalsRescaleHalfUp() {
        assertEquals(new BigDecimal("1.01"),
                Casts.cast(new BigDecimal("1.005"), DataType.decimal(10, 3), DataType.decimal(10, 2)));
        assertEquals(new BigDecimal("-1.01"),
                Casts.cast(new BigDecimal("-1.005"), DataType.decimal(10, 3), DataType.decimal(10, 2)));
        assertEquals(new BigDecimal("1.500"), Casts.cast(1.5, DataType.DOUBLE, DataType.decimal(10, 3)));
        assertEquals(2, Casts.cast(new BigDecimal("1.50"), DataType.decimal(10, 2), DataType.INTEGER));
        assertEquals(new BigDecimal("42.00"), Casts.cast(42, DataType.INTEGER, DataType.decimal(10, 2)));
        assertEquals(1.5, Casts.cast(new BigDecimal("1.50"), DataType.decimal(10, 2), DataType.DOUBLE));
    }

    @Test
    void strings() {
        assertEquals(42, Casts.cast(" 42 ", DataType.VARCHAR, DataType.INTEGER));
        assertThrows(ConversionException.class, () -> Casts.cast("x", DataType.VARCHAR, DataType.INTEGER));
        assertThrows(ArithmeticException.class, () -> Casts.cast("300", DataType.VARCHAR, DataType.TINYINT));
        assertEquals(true, Casts.cast("TRUE", DataType.VARCHAR, DataType.BOOLEAN));
        assertEquals(new BigDecimal("1.50"), Casts.cast("1.5", DataType.VARCHAR, DataType.decimal(10, 2)));
        assertEquals("1.50", Casts.cast(new BigDecimal("1.50"), DataType.decimal(10, 2), DataType.VARCHAR));
        assertEquals("true", Casts.cast(true, DataType.BOOLEAN, DataType.VARCHAR));
        assertEquals(Values.HUGEINT_MAX,
                Casts.cast("170141183460469231731687303715884105727", DataType.VARCHAR, DataType.HUGEINT));
        assertThrows(ArithmeticException.class,
                () -> Casts.cast("170141183460469231731687303715884105728", DataType.VARCHAR, DataType.HUGEINT));
        assertNull(Casts.cast(null, DataType.VARCHAR, DataType.INTEGER));
    }

    @Test
    void stringsToNumbersFollowTheGrammarOfDuckDb() {
        assertEquals(2, Casts.cast("1.5", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(-2, Casts.cast("-1.5", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(100, Casts.cast("1e2", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(0, Casts.cast("1e-1", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(1, Casts.cast(".5", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(10, Casts.cast("1.e1", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(16, Casts.cast("0x1_0", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(5, Casts.cast("0b101", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(1000, Casts.cast("1_000", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(11, Casts.cast("1_0.5", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(12, Casts.cast("\t00012\n", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(2147483647, Casts.cast("2147483647.4", DataType.VARCHAR, DataType.INTEGER));
        assertThrows(ArithmeticException.class, () -> Casts.cast("2147483647.5", DataType.VARCHAR, DataType.INTEGER));
        assertThrows(ArithmeticException.class, () -> Casts.cast("1e100", DataType.VARCHAR, DataType.INTEGER));
        assertEquals(new BigInteger("100000000000000000000000000000000000000"),
                Casts.cast("1e38", DataType.VARCHAR, DataType.HUGEINT));
        for (String bad : List.of("", " ", ".", "1e", "e2", "1__0", "_1", "1_", "0x", "-0x10", "1e1_0", "1 2", "1,5",
                "12abc", "- 1", "0x7FFFFFFFF", "1.5.5")) {
            assertThrows(RuntimeException.class, () -> Casts.cast(bad, DataType.VARCHAR, DataType.INTEGER), bad);
        }
        assertThrows(ConversionException.class, () -> Casts.cast("0x10", DataType.VARCHAR, DataType.HUGEINT));
        assertEquals(Double.NaN, Casts.cast("NAN", DataType.VARCHAR, DataType.DOUBLE));
        assertEquals(Double.NEGATIVE_INFINITY, Casts.cast("-Infinity", DataType.VARCHAR, DataType.DOUBLE));
        assertEquals(Double.POSITIVE_INFINITY, Casts.cast("+inf", DataType.VARCHAR, DataType.DOUBLE));
        assertEquals(1000.0, Casts.cast("1_000", DataType.VARCHAR, DataType.DOUBLE));
        assertEquals(Double.POSITIVE_INFINITY, Casts.cast("1e400", DataType.VARCHAR, DataType.DOUBLE));
        for (String bad : List.of("0x10", "1d", "1f", "0x1p3", ".", "1e")) {
            assertThrows(ConversionException.class, () -> Casts.cast(bad, DataType.VARCHAR, DataType.DOUBLE), bad);
        }
        assertEquals(new BigDecimal("127.56"), Casts.cast("127.555", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertEquals(new BigDecimal("-0.01"), Casts.cast("-0.005", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertEquals(new BigDecimal("150.00"), Casts.cast("1.5e2", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertEquals(new BigDecimal("0.00"), Casts.cast("1e-3", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertThrows(ArithmeticException.class, () -> Casts.cast("999.999", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertThrows(ConversionException.class, () -> Casts.cast("1_000", DataType.VARCHAR, DataType.decimal(5, 2)));
        assertEquals(true, Casts.cast("yEs", DataType.VARCHAR, DataType.BOOLEAN));
        assertEquals(false, Casts.cast("n", DataType.VARCHAR, DataType.BOOLEAN));
        assertThrows(ConversionException.class, () -> Casts.cast(" true ", DataType.VARCHAR, DataType.BOOLEAN));
    }

    @Test
    void stringsToDatesFollowTheGrammarOfDuckDb() {
        int day = Conversions.parseDate("2026-01-02");
        assertEquals(day, Conversions.parseDate("2026-1-2"));
        assertEquals(day, Conversions.parseDate("2026/01/02"));
        assertEquals(day, Conversions.parseDate("2026 01 02"));
        assertEquals(day, Conversions.parseDate(" 2026-01-02 03:04:05+02 "));
        assertEquals(day, Conversions.parseDate("2026-01-02x"));
        assertEquals(day, Conversions.parseDate("02026-01-02"));
        assertEquals(0, Conversions.parseDate("epoch"));
        assertEquals((int) java.time.LocalDate.of(-2025, 1, 2).toEpochDay(), Conversions.parseDate("2026-01-02 (bc)"));
        assertEquals((int) java.time.LocalDate.of(-1, 1, 1).toEpochDay(), Conversions.parseDate("-0001-01-01"));
        assertEquals("10000-01-01", Conversions.formatDate(Conversions.parseDate("10000-01-01")));
        for (String bad : List.of("20260102", "2026-01", "2026.01.02", "2026-001-02", "2026-13-01", "2026-02-30", "")) {
            assertThrows(ConversionException.class, () -> Conversions.parseDate(bad), bad);
        }
        long midnight = Conversions.parseTimestamp("2026-01-02");
        assertEquals(midnight, Conversions.parseTimestamp("2026/1/2"));
        assertEquals(midnight + 3_600_000_000L * 3 + 60_000_000L * 4 + 5_000_000L,
                Conversions.parseTimestamp("2026-01-02 3:4:5"));
        assertEquals(midnight + 3_600_000_000L * 3 + 60_000_000L * 4, Conversions.parseTimestamp("2026-01-02T03:04"));
        assertEquals(midnight + 123456, Conversions.parseTimestamp("2026-01-02 00:00:00.123456789"));
        assertEquals(midnight + 100000, Conversions.parseTimestamp("2026-01-02 00:00:00.1"));
        assertEquals(midnight, Conversions.parseTimestamp("2026-01-02 00:00:00."));
        assertEquals(midnight, Conversions.parseTimestamp("2026-01-02 00:00:00+02"));
        assertEquals(midnight, Conversions.parseTimestamp("2026-01-02 00:00:00Z"));
        assertEquals(midnight, Conversions.parseTimestamp("2026-01-02 00:00:00-05:30"));
        assertEquals(midnight, Conversions.parseTimestamp("2026-01-02 00:00:00 UTC"));
        assertEquals(midnight + 86_400_000_000L, Conversions.parseTimestamp("2026-01-02 24:00:00"));
        assertEquals(0L, Conversions.parseTimestamp("epoch"));
        for (String bad : List.of("2026-01-02T", "2026-01-02 x", "2026-01-02 03", "2026-01-02 23:60:00",
                "2026-01-02 23:59:60", "2026-01-02 24:00:01")) {
            assertThrows(ConversionException.class, () -> Conversions.parseTimestamp(bad), bad);
        }
    }

    @Test
    void doublesFormatLikeDuckDb() {
        assertEquals("1.0", Casts.format(1.0, DataType.DOUBLE));
        assertEquals("0.1", Casts.format(0.1, DataType.DOUBLE));
        assertEquals("-100.25", Casts.format(-100.25, DataType.DOUBLE));
        assertEquals("1e+19", Casts.format(1e19, DataType.DOUBLE));
        assertEquals("1e+16", Casts.format(1e16, DataType.DOUBLE));
        assertEquals("-7206616547838947.0", Casts.format(-7206616547838947.0, DataType.DOUBLE));
        assertEquals("1234568000000000.0", Casts.format(1234567890123456.0f, DataType.FLOAT));
        assertEquals("0.0001", Casts.format(0.0001, DataType.DOUBLE));
        assertEquals("5e-324", Casts.format(Double.MIN_VALUE, DataType.DOUBLE));
        assertEquals("1e-45", Casts.format(Float.MIN_VALUE, DataType.FLOAT));
        assertEquals("1.5e-05", Casts.format(1.5e-5, DataType.DOUBLE));
        assertEquals("nan", Casts.format(Double.NaN, DataType.DOUBLE));
        assertEquals("-inf", Casts.format(Double.NEGATIVE_INFINITY, DataType.DOUBLE));
        assertEquals("-0.0", Casts.format(-0.0, DataType.DOUBLE));
        assertEquals("1.5", Casts.format(1.5f, DataType.FLOAT));
    }

    @Test
    void datesAndTimestamps() {
        assertEquals(0, Casts.cast("1970-01-01", DataType.VARCHAR, DataType.DATE));
        assertEquals("2026-10-12", Casts.cast(Conversions.parseDate("2026-10-12"), DataType.DATE, DataType.VARCHAR));
        long micros = Conversions.parseTimestamp("2026-01-02 03:04:05.123456");
        assertEquals("2026-01-02 03:04:05.123456", Casts.format(micros, DataType.TIMESTAMP));
        assertEquals("2026-01-02 03:04:05.1", Casts.format(Conversions.parseTimestamp("2026-01-02T03:04:05.100"),
                DataType.TIMESTAMP));
        assertEquals("2026-01-02 03:04:05", Casts.format(Conversions.parseTimestamp("2026-01-02 03:04:05"),
                DataType.TIMESTAMP));
        assertEquals(Conversions.parseDate("2026-01-02"), Casts.cast(micros, DataType.TIMESTAMP, DataType.DATE));
        assertEquals(Conversions.parseTimestamp("2026-01-02 00:00:00"),
                Casts.cast(Conversions.parseDate("2026-01-02"), DataType.DATE, DataType.TIMESTAMP));
        assertEquals("1969-12-31 23:59:59.999999", Casts.format(-1L, DataType.TIMESTAMP));
        assertThrows(ConversionException.class, () -> Casts.cast("2026-13-01", DataType.VARCHAR, DataType.DATE));
        assertThrows(ConversionException.class, () -> Casts.cast(1, DataType.DATE, DataType.INTEGER));
    }

    @Test
    void blobsAndNested() {
        byte[] bytes = {(byte) 0xAA, 0, 'a', '\\'};
        String formatted = Casts.format(bytes, DataType.BLOB);
        assertEquals("\\xAA\\x00a\\x5C", formatted);
        assertArrayEquals(bytes, (byte[]) Casts.cast(formatted, DataType.VARCHAR, DataType.BLOB));
        DataType.Struct s = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("b", DataType.VARCHAR));
        assertEquals("{'a': 1, 'b': x}", Casts.format(List.of(1, "x"), s));
        assertEquals("{'a': 1, 'b': 'x y '}", Casts.format(List.of(1, "x y "), s));
        // DuckDB quotes the text of an element only where it could be misread.
        assertEquals("['', 'null', 'a\\'b', 'a,b', a b, a\\b, '2020-01-01 10:00:00']",
                Casts.format(List.of("", "null", "a'b", "a,b", "a b", "a\\b", "2020-01-01 10:00:00"),
                        DataType.listOf(DataType.VARCHAR)));
        assertEquals("{'a=b'=NULL}", Casts.format(java.util.Collections.singletonMap("a=b", null),
                DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));
        assertEquals("[1, NULL]", Casts.format(java.util.Arrays.asList(1, null), DataType.listOf(DataType.INTEGER)));
        assertEquals("{k=1}", Casts.format(Map.of("k", 1), DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)));
        assertThrows(ConversionException.class, () -> Casts.cast(List.of(1), DataType.listOf(DataType.INTEGER),
                DataType.INTEGER));
    }

    @Test
    void nestedValuesConvertElementByElementAndStructsByName() {
        DataType ints = DataType.listOf(DataType.INTEGER);
        DataType tinyints = DataType.listOf(DataType.TINYINT);
        assertEquals(Arrays.asList((byte) 1, null), Casts.cast(Arrays.asList(1, null), ints, tinyints));
        assertThrows(ArithmeticException.class, () -> Casts.cast(List.of(1, 300), ints, tinyints));
        assertEquals(Arrays.asList((byte) 1, null), Casts.tryCast(List.of(1, 300), ints, tinyints));
        assertEquals(List.of(2), Casts.cast(List.of(new BigDecimal("1.5")), DataType.listOf(DataType.decimal(2, 1)),
                ints));
        DataType.Struct ab = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                new DataType.Struct.Field("b", DataType.VARCHAR));
        DataType.Struct bc = DataType.struct(new DataType.Struct.Field("B", DataType.VARCHAR),
                new DataType.Struct.Field("c", DataType.BIGINT));
        assertTrue(Casts.allowed(ab, bc));
        assertEquals(Arrays.asList("x", null), Casts.cast(Arrays.asList(1, "x"), ab, bc));
        assertFalse(Casts.allowed(ab, DataType.struct(new DataType.Struct.Field("z", DataType.INTEGER))));
        assertFalse(Casts.allowed(ab, DataType.struct(new DataType.Struct.Field("b", DataType.DATE),
                new DataType.Struct.Field("a", DataType.listOf(DataType.INTEGER)))));
        DataType strings = DataType.mapOf(DataType.VARCHAR, DataType.INTEGER);
        DataType numbers = DataType.mapOf(DataType.INTEGER, DataType.BIGINT);
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("1", 1);
        map.put("2", null);
        Map<Object, Object> converted = new LinkedHashMap<>();
        converted.put(1, 1L);
        converted.put(2, null);
        assertEquals(converted, Casts.cast(map, strings, numbers));
        map.put("01", 3);
        assertThrows(ConversionException.class, () -> Casts.cast(map, strings, numbers), "two keys become 1");
        assertNull(Casts.tryCast(map, strings, numbers));
        assertNull(Casts.tryCast(Map.of("x", 1), strings, numbers));
        assertFalse(Casts.allowed(DataType.VARCHAR, ints), "a string does not convert to a nested type");
        assertFalse(Casts.allowed(ints, strings));
        assertTrue(Casts.allowed(ints, DataType.VARCHAR));
    }
}
