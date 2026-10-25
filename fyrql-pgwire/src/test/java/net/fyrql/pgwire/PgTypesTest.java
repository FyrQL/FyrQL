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

package net.fyrql.pgwire;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Interval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The text and binary formats of PostgreSQL, against values PostgreSQL 18 writes. */
class PgTypesTest {

    @Test
    void floatingPointNumbersAsPostgreSqlWritesThem() {
        assertEquals("1.5", PgTypes.floating(1.5, false));
        assertEquals("100", PgTypes.floating(100.0, false));
        assertEquals("0.0001", PgTypes.floating(0.0001, false));
        assertEquals("1e-05", PgTypes.floating(0.00001, false));
        assertEquals("123456789012345", PgTypes.floating(123456789012345.0, false));
        assertEquals("1e+15", PgTypes.floating(1e15, false));
        assertEquals("1.2345678901234568e+16", PgTypes.floating(12345678901234567.0, false));
        assertEquals("0.1", PgTypes.floating(0.1, false));
        assertEquals("1e+06", PgTypes.floating(1e6, true));
        assertEquals("123456", PgTypes.floating(123456, true));
        assertEquals("NaN", PgTypes.floating(Double.NaN, false));
        assertEquals("-Infinity", PgTypes.floating(Double.NEGATIVE_INFINITY, false));
        assertEquals("-0", PgTypes.floating(-0.0, false));
    }

    @Test
    void datesTimesAndIntervals() {
        assertEquals("2024-02-29", PgTypes.date((int) LocalDate.of(2024, 2, 29).toEpochDay()));
        assertEquals("0044-03-15 BC", PgTypes.date((int) LocalDate.of(-43, 3, 15).toEpochDay()));
        assertEquals("24:00:00", PgTypes.time(86_400_000_000L));
        assertEquals("1970-01-01 00:00:00.5", PgTypes.timestamp(500_000));
        assertEquals("1969-12-31 23:59:59.5", PgTypes.timestamp(-500_000));
        assertEquals("2024-01-01 13:00:00+01", PgTypes.timestamptz(1_704_110_400_000_000L, ZoneId.of("Europe/Berlin")));
        assertEquals("2024-01-01 17:30:00+05:30", PgTypes.timestamptz(1_704_110_400_000_000L,
                ZoneId.of("Asia/Kolkata")));
        assertEquals("1 year 2 mons 3 days 04:05:06", PgTypes.interval(Interval.of(14, 3, 14_706_000_000L)));
        assertEquals("-1 days +01:00:00", PgTypes.interval(Interval.of(0, -1, 3_600_000_000L)));
        assertEquals("-00:00:01.5", PgTypes.interval(Interval.of(0, 0, -1_500_000L)));
        assertEquals("00:00:00", PgTypes.interval(Interval.ZERO));
        assertEquals("1 mon", PgTypes.interval(Interval.ofMonths(1)));
    }

    @Test
    void arraysQuoteTheirElementsWherePostgreSqlDoes() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals("{a,\"b c\",\"\",\"NULL\",NULL,\"x\\\"y\",\"{}\"}", PgTypes.array(Arrays.asList("a",
                "b c", "", "NULL", null, "x\"y", "{}"), DataType.VARCHAR, utc));
        assertEquals(Arrays.asList("a", "b c", "", "NULL", null, "x\"y", "{}"), PgTypes.parseArray(
                "{a,\"b c\",\"\",\"NULL\",NULL,\"x\\\"y\",\"{}\"}", DataType.VARCHAR, utc));
        assertEquals(List.of(1, 2), PgTypes.parseArray(" { 1 , 2 } ", DataType.INTEGER, utc));
        assertEquals(List.of(), PgTypes.parseArray("{}", DataType.INTEGER, utc));
        assertThrows(IllegalArgumentException.class, () -> PgTypes.parseArray("{{1}}", DataType.INTEGER, utc));
    }

    @Test
    void parametersInTextAsPostgreSqlTakesThem() {
        ZoneId utc = ZoneOffset.UTC;
        assertEquals(true, PgTypes.parseText("on", DataType.BOOLEAN, utc));
        assertEquals(false, PgTypes.parseText(" F ", DataType.BOOLEAN, utc));
        assertArrayEquals(new byte[] {1, (byte) 0xAB}, (byte[]) PgTypes.parseText("\\x01ab", DataType.BLOB, utc));
        assertArrayEquals(new byte[] {'a', '\\', 7}, (byte[]) PgTypes.parseText("a\\\\\\007", DataType.BLOB, utc));
        assertEquals(PgTypes.parseText("2024-02-29 01:02:03", DataType.TIMESTAMP, utc),
                PgTypes.parseText("2024-02-29 01:02:03+01", DataType.TIMESTAMP, utc));
        assertEquals((int) LocalDate.of(2024, 2, 29).toEpochDay(), PgTypes.parseText("2024-02-29 +01:00",
                DataType.DATE, utc));
        assertEquals(new BigDecimal("1.50"), PgTypes.parseText("1.5", DataType.decimal(10, 2), utc));
    }

    @Test
    void theBinaryFormats() {
        ZoneId utc = ZoneOffset.UTC;
        // numeric 12345.6789 is the digits 1, 2345 and 6789 of base 10000 with the weight 1 and the scale 4.
        assertEquals("0003" + "0001" + "0000" + "0004" + "0001" + "0929" + "1a85",
                HexFormat.of().formatHex(PgTypes.numeric(new BigDecimal("12345.6789"))));
        for (String v : List.of("0", "-1.5", "0.0001", "100000000", "123456789012345678901234567890.5", "0.00")) {
            BigDecimal d = new BigDecimal(v);
            assertEquals(d, PgTypes.numeric(PgTypes.numeric(d)), v);
        }
        assertEquals("00000000", HexFormat.of().formatHex(PgTypes.binary((int) LocalDate.of(2000, 1, 1).toEpochDay(),
                DataType.DATE, utc, StandardCharsets.UTF_8)));
        byte[] array = PgTypes.binary(Arrays.asList(1, null), DataType.listOf(DataType.INTEGER), utc,
                StandardCharsets.UTF_8);
        assertEquals("00000001" + "00000001" + "00000017" + "00000002" + "00000001" + "00000004" + "00000001"
                + "ffffffff", HexFormat.of().formatHex(array));
        assertEquals(Arrays.asList(1, null), PgTypes.parseBinary(array, 1007, DataType.listOf(DataType.INTEGER), utc,
                StandardCharsets.UTF_8));
        assertEquals(5L, PgTypes.parseBinary(new byte[] {0, 0, 0, 5}, PgTypes.INT4, DataType.BIGINT, utc,
                StandardCharsets.UTF_8));
        // A decimal below 1 has fewer digits than its scale.
        assertEquals(new BigDecimal("0.05"), PgTypes.parseBinary(PgTypes.numeric(new BigDecimal("0.05")),
                PgTypes.NUMERIC, DataType.decimal(15, 2), utc, StandardCharsets.UTF_8));
        assertEquals(PgTypes.of(DataType.decimal(10, 2)).modifier(), (10 << 16 | 2) + 4);
    }
}
