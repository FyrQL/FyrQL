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

package net.fyrql.connector.odata;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The strings in the JSON of OData as values of their types. */
class ODataValuesTest {

    private static final ODataValues VALUES = ODataValues.INSTANCE;

    private static long micros(String timestamp) {
        return Conversions.parseTimestamp(timestamp);
    }

    private static String error(String text, DataType type) {
        return assertThrows(IllegalArgumentException.class, () -> VALUES.parse(text, type)).getMessage();
    }

    @Test
    void timestampsOfVersion2() {
        assertEquals(micros("2023-11-14 22:13:20"), VALUES.parse("/Date(1700000000000)/", DataType.TIMESTAMP));
        assertEquals(micros("2023-11-14 22:13:20.123"), VALUES.parse(" /Date(1700000000123)/ ", DataType.TIMESTAMP));
        assertEquals(-1_000L, VALUES.parse("/Date(-1)/", DataType.TIMESTAMP));
        // The milliseconds of an Edm.DateTimeOffset are in UTC; the offset after them does not change them.
        assertEquals(micros("2023-11-14 22:13:20"), VALUES.parse("/Date(1700000000000+0120)/", DataType.TIMESTAMP));
        assertEquals(micros("2023-11-14 22:13:20"), VALUES.parse("/Date(1700000000000-0300)/", DataType.TIMESTAMP));
    }

    @Test
    void timestampsInIso() {
        assertEquals(micros("2024-01-02 03:04:05"), VALUES.parse("2024-01-02T03:04:05Z", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-02 03:04:05"), VALUES.parse("2024-01-02T03:04:05", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-02 01:04:05.123456"),
                VALUES.parse("2024-01-02T03:04:05.1234567+02:00", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-02 03:04:05.5"), VALUES.parse("2024-01-02T03:04:05.5z", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-02 04:34:00"), VALUES.parse("2024-01-02T03:04-01:30", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-01 23:00:00"), VALUES.parse("2024-01-02T00:00:00+0100", DataType.TIMESTAMP));
        assertEquals(micros("2024-01-02 03:04:05"), VALUES.parse("2024-01-02 03:04:05", DataType.TIMESTAMP));
    }

    @Test
    void timestampsThatAreNone() {
        assertEquals("expected /Date(milliseconds)/ or an ISO 8601 date and time", error("yesterday",
                DataType.TIMESTAMP));
        assertEquals("expected /Date(milliseconds)/ or an ISO 8601 date and time",
                error("/Date(99999999999999999999)/", DataType.TIMESTAMP));
        assertEquals("the milliseconds of /Date(9999999999999999999)/ are out of range",
                error("/Date(9999999999999999999)/", DataType.TIMESTAMP));
        assertEquals("the offset of 2024-01-02T03:04:05+24:00 is out of range",
                error("2024-01-02T03:04:05+24:00", DataType.TIMESTAMP));
        assertEquals("Invalid value for MonthOfYear (valid values 1 - 12): 13",
                error("2024-13-02T03:04:05Z", DataType.TIMESTAMP));
        assertThrows(ArithmeticException.class, () -> VALUES.parse("/Date(9223372036854776)/", DataType.TIMESTAMP));
    }

    @Test
    void timesOfDayAndDurations() {
        assertEquals(Conversions.parseTime("13:20:00"), VALUES.parse("PT13H20M", DataType.TIME));
        assertEquals(Conversions.parseTime("00:00:01.123456"), VALUES.parse("PT1.1234567S", DataType.TIME));
        assertEquals(Conversions.parseTime("24:00:00"), VALUES.parse("P1D", DataType.TIME));
        assertEquals(Conversions.parseTime("13:20:00.5"), VALUES.parse("13:20:00.5000000", DataType.TIME));
        assertEquals(Conversions.parseTime("13:20:00"), VALUES.parse("13:20", DataType.TIME));
        assertEquals("expected a time of day: PT25H", error("PT25H", DataType.TIME));
        assertEquals("expected a time of day: -PT1H", error("-PT1H", DataType.TIME));
        assertEquals(Interval.parse("1 day 02:30:00"), VALUES.parse("P1DT2H30M", DataType.INTERVAL));
        assertEquals(Interval.parse("-1 year -2 months -3 days -00:00:00.5"),
                VALUES.parse("-P1Y2M3DT0.5S", DataType.INTERVAL));
        assertEquals(Interval.parse("00:00:00"), VALUES.parse("PT0S", DataType.INTERVAL));
        assertEquals("expected a duration of ISO 8601 such as P1DT2H30M: P", error("P", DataType.INTERVAL));
        assertEquals("expected a duration of ISO 8601 such as P1DT2H30M: P1DT", error("P1DT", DataType.INTERVAL));
        // An instant as a timestamp, in UTC.
        assertEquals(micros("2024-01-02 01:04:05"), VALUES.parse("2024-01-02T03:04:05+02:00",
                DataType.TIMESTAMPTZ));
    }

    @Test
    void dates() {
        assertEquals(Conversions.parseDate("2024-01-02"), VALUES.parse("2024-01-02", DataType.DATE));
        assertEquals(Conversions.parseDate("2024-01-02"), VALUES.parse("/Date(1704153600000)/", DataType.DATE));
        assertEquals(Conversions.parseDate("2024-01-02"), VALUES.parse("/Date(1704239999999)/", DataType.DATE));
        assertEquals(-1, VALUES.parse("/Date(-1)/", DataType.DATE));
    }

    @Test
    void binaryValuesInBothFormsOfBase64() {
        byte[] bytes = {0, 1, 2, -1, -2, 62};
        assertArrayEquals(bytes, (byte[]) VALUES.parse("AAEC//4+", DataType.BLOB));
        assertArrayEquals(bytes, (byte[]) VALUES.parse("AAEC__4-", DataType.BLOB));
        assertArrayEquals(new byte[] {0, 1}, (byte[]) VALUES.parse("AAE=", DataType.BLOB));
        assertArrayEquals(new byte[] {0, 1}, (byte[]) VALUES.parse("AAE", DataType.BLOB));
        assertArrayEquals(new byte[0], (byte[]) VALUES.parse("", DataType.BLOB));
        assertEquals("expected Base64: Illegal base64 character 2a", error("a*b", DataType.BLOB));
    }

    @Test
    void otherTypesAsACastReadsThem() {
        String text = "x";
        assertSame(text, VALUES.parse(text, DataType.VARCHAR));
        assertEquals(9_007_199_254_740_993L, VALUES.parse("9007199254740993", DataType.BIGINT));
        assertEquals(new BigDecimal("1.50"), VALUES.parse("1.5", DataType.decimal(5, 2)));
        assertEquals(Double.POSITIVE_INFINITY, VALUES.parse("INF", DataType.DOUBLE));
        assertEquals(Float.NEGATIVE_INFINITY, VALUES.parse("-INF", DataType.FLOAT));
        assertEquals(Double.NaN, VALUES.parse("NaN", DataType.DOUBLE));
        assertEquals(1.5E10, VALUES.parse("1.5E+10", DataType.DOUBLE));
        assertEquals(true, VALUES.parse("true", DataType.BOOLEAN));
        assertEquals(UUID.fromString("005056a2-0d31-1ee1-b4b6-56d0e1a5c3e8"),
                VALUES.parse("005056A2-0D31-1EE1-B4B6-56D0E1A5C3E8", DataType.UUID));
    }
}
