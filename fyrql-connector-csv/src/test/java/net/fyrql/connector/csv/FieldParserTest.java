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

package net.fyrql.connector.csv;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Compares the fields read from characters with the same fields read from strings. */
class FieldParserTest {

    private static final List<DataType> TYPES = List.of(DataType.TINYINT, DataType.SMALLINT, DataType.INTEGER,
            DataType.BIGINT, DataType.decimal(15, 2), DataType.decimal(18, 0), DataType.decimal(18, 18),
            DataType.decimal(4, 4), DataType.decimal(1, 0), DataType.decimal(38, 10), DataType.DATE,
            DataType.BOOLEAN, DataType.VARCHAR, DataType.DOUBLE, DataType.FLOAT, DataType.TIMESTAMP,
            DataType.HUGEINT, DataType.BLOB, DataType.UUID);

    private static final List<String> NULL_STRINGS = List.of("", "NA", "NULL");

    private static final List<String> EDGES = List.of("", " ", "0", "-0", "00", "+1", "+01", " 1", "1 ", "\t1",
            "1e3", "1E-2", "-1e2", "0x1F", "0X1f", "0b101", "1_000", "1__0", "1.5", "1.", ".5", "-.5", ".", "-", "--1",
            "-+1", "1-2-3", "epoch", "EPOCH", " epoch ", "0001-01-01 (BC)", "1994-01-01", "1994-1-1", "1994/01/01",
            "1994 01 01", "1994-02-29", "1996-02-29", "2000-02-29", "1900-02-29", "0000-01-01", "0000-02-29",
            "9999-12-31", "10000-01-01", "-1994-01-01", "+1994-01-01", "1994-13-01", "1994-00-10", "1994-01-32",
            "1994-01-00", "1994-04-31", "1994-01-01x", "1994-01-01 ", " 1994-01-01", "1994-01-01 12:00:00",
            "1994-01-0a", "199a-01-01", "1994x01x01", "99999999999999999999", "9223372036854775807",
            "-9223372036854775808", "9223372036854775808", "-9223372036854775809", "127", "128", "-128", "-129",
            "255", "32767", "32768", "-32768", "-32769", "2147483647", "2147483648", "-2147483648", "-2147483649",
            "999999999999999999", "-999999999999999999", "1000000000000000000", "0000000000000000000001",
            "000000000000000001", "1234567890123.45", "-1234567890123.45", "12345678901234.56", "1234567890123.456",
            "9999999999999.99", "99999999999999.99", "0.001", "0.005", "-0.00", "00012.30", "0.123456789012345678",
            "0.1234567890123456789", "1.0", "0.9999", "9.9", "9", "10", "-9", "true", "TRUE", "True", "tRuE", "t",
            "T", "yes", "YES", "Yes", "y", "Y", "no", "NO", "n", "N", "false", "FALSE", "f", "F", "1", "0", "2", "tru",
            "truee", "on", "off", " true", "true ", "\u00E9", "caf\u00E9", "\uD83D\uDE00", "\ud800", "x\udc00y",
            "NULL", "null", "NA", "\"q\"", "a,b", "nan", "NaN", "inf", "-Infinity", "1.7976931348623157E308",
            "4.9e-324", "2026-01-02 03:04:05", "2026-01-02T03:04:05.123456789", "\\x41\\x42", "\u0661",
            "\uFF11", "1994-01-\u0661\u0661", "\u0131", "\u0130", "\u212A", "12345678-1234-1234-1234-123456789012",
            "~", "\u007f", "\u0080");

    @Test
    void edgeFormsGiveTheValuesAndErrorsOfStrings() {
        for (DataType type : TYPES) {
            for (String nullString : NULL_STRINGS) {
                for (String field : EDGES) {
                    same(type, field, nullString);
                }
            }
        }
    }

    @Test
    void randomValuesGiveTheValuesAndErrorsOfStrings() {
        SplittableRandom random = new SplittableRandom(20261016);
        for (DataType type : TYPES) {
            for (int i = 0; i < 3000; i++) {
                DataType from = random.nextInt(4) == 0 ? TYPES.get(random.nextInt(TYPES.size())) : type;
                String field = Casts.format(DataGenerator.randomValue(from, random, 0), from);
                same(type, field, "");
            }
        }
    }

    @Test
    void randomStringsGiveTheValuesAndErrorsOfStrings() {
        SplittableRandom random = new SplittableRandom(1);
        String alphabet = "0123456789000111999--..+eE _xbTtFfNnYy:/";
        for (DataType type : TYPES) {
            for (int i = 0; i < 5000; i++) {
                StringBuilder field = new StringBuilder();
                int length = random.nextInt(23);
                for (int k = 0; k < length; k++) {
                    field.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                same(type, field.toString(), "");
            }
        }
    }

    @Test
    void thePlainFormsTakeTheFastPath() {
        assertFast(DataType.INTEGER, "123", "-45", "0", "2147483647");
        assertFast(DataType.BIGINT, "123456789012345678", "-1");
        assertFast(DataType.TINYINT, "-128", "127");
        assertFast(DataType.SMALLINT, "-32768", "32767");
        assertFast(DataType.decimal(15, 2), "1234.56", "-0.5", "17", "9999999999999.99", ".5", "3.");
        assertFast(DataType.DATE, "1994-01-01", "0000-01-01", "9999-12-31", "2000-02-29");
        assertFast(DataType.BOOLEAN, "true", "FALSE", "y", "0");
        assertFast(DataType.VARCHAR, "", "abc", "Smith, John");
        assertSlow(DataType.INTEGER, "+1", " 1", "1.5", "0x1F", "1_000", "1234567890123456789");
        assertSlow(DataType.decimal(15, 2), "1.234", "1e2", "+1", "99999999999999.99");
        assertSlow(DataType.DATE, "1994-1-1", "epoch", "1994-02-30", "1994-01-01 00:00:00");
        assertSlow(DataType.VARCHAR, "caf\u00E9");
        assertSlow(DataType.DOUBLE, "1.5");
    }

    private static void assertFast(DataType type, String... fields) {
        for (String field : fields) {
            assertTrue(FieldParser.of(type, "NA", 1).fast(field.toCharArray(), 0, field.length()), type + " " + field);
        }
    }

    private static void assertSlow(DataType type, String... fields) {
        for (String field : fields) {
            assertFalse(FieldParser.of(type, "NA", 1).fast(field.toCharArray(), 0, field.length()), type + " " + field);
        }
    }

    /** Reads a field, inside other characters, as the string would be read; both fail alike, or give one value. */
    private static void same(DataType type, String field, String nullString) {
        Object expected = null;
        RuntimeException expectedError = null;
        try {
            expected = CsvConnector.parse(field, type, nullString);
        } catch (RuntimeException e) {
            expectedError = e;
        }
        FieldParser parser = FieldParser.of(type, nullString, 1);
        char[] buf = ("1-" + field + "-1").toCharArray();
        String what = type + " '" + field + "' with the null string '" + nullString + "'";
        try {
            parser.parse(buf, 2, field.length());
        } catch (RuntimeException e) {
            assertNotNull(expectedError, () -> what + " failed with " + e + ", the string gives a value");
            assertEquals(expectedError.getClass(), e.getClass(), what);
            assertEquals(expectedError.getMessage(), e.getMessage(), what);
            return;
        }
        if (expectedError != null) {
            fail(what + " gave a value, the string fails with " + expectedError);
        }
        Vector v = parser.finish();
        assertEquals(1, v.size(), what);
        Object actual = v.get(0);
        // As the string is appended, e.g. with a lone surrogate encoded as '?'.
        Vector.Builder b = Vectors.builder(type, 1);
        b.append(expected);
        expected = b.build().get(0);
        if (expected == null) {
            assertNull(actual, what);
        } else if (expected instanceof byte[] bytes) {
            assertArrayEquals(bytes, (byte[]) actual, what);
        } else {
            assertEquals(expected, actual, what);
        }
    }

    @Test
    void theParserStartsANewVectorAfterEachOne() {
        FieldParser parser = FieldParser.of(DataType.decimal(15, 2), "", 2);
        List<Object> values = new ArrayList<>();
        for (String field : List.of("1.5", "", "-2", "1e1", "3.25")) {
            parser.parse(field.toCharArray(), 0, field.length());
        }
        Vector first = parser.finish();
        parser.parse("7".toCharArray(), 0, 1);
        Vector second = parser.finish();
        for (int i = 0; i < first.size(); i++) {
            values.add(first.get(i));
        }
        assertEquals(java.util.Arrays.asList(new java.math.BigDecimal("1.50"), null, new java.math.BigDecimal("-2.00"),
                new java.math.BigDecimal("10.00"), new java.math.BigDecimal("3.25")), values);
        assertEquals(1, second.size());
        assertEquals(new java.math.BigDecimal("7.00"), second.get(0));
    }
}
