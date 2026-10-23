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

import net.fyrql.core.value.Interval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesJavaTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void datesAndTimesBecomeJavaTimeAndBack() {
        roundTrip(DataType.DATE, 20465, LocalDate.of(2026, 1, 12));
        roundTrip(DataType.DATE, -1, LocalDate.of(1969, 12, 31));
        roundTrip(DataType.TIME, 45_296_789_012L, LocalTime.of(12, 34, 56, 789_012_000));
        roundTrip(DataType.TIMESTAMP, 1_768_221_296_000_001L, LocalDateTime.of(2026, 1, 12, 12, 34, 56, 1000));
        roundTrip(DataType.TIMESTAMP, -1L, LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_000));
        roundTrip(DataType.TIMESTAMPTZ, 1_768_221_296_000_000L,
                OffsetDateTime.of(2026, 1, 12, 13, 34, 56, 0, ZoneOffset.ofHours(1)));
        roundTrip(DataType.TIMESTAMPTZ, -1L, OffsetDateTime.of(1970, 1, 1, 0, 59, 59, 999_999_000,
                ZoneOffset.ofHours(1)));
    }

    @Test
    void theZoneOfATimestampWithTimeZoneIsTheGivenOne() {
        long micros = 1_783_000_000_000_000L;
        OffsetDateTime summer = (OffsetDateTime) Values.toJava(micros, DataType.TIMESTAMPTZ, BERLIN);
        OffsetDateTime utc = (OffsetDateTime) Values.toJava(micros, DataType.TIMESTAMPTZ, ZoneOffset.UTC);
        assertEquals(ZoneOffset.ofHours(2), summer.getOffset());
        assertEquals(ZoneOffset.UTC, utc.getOffset());
        assertTrue(summer.isEqual(utc));
        assertEquals(micros, Values.fromJava(summer, DataType.TIMESTAMPTZ));
        assertEquals(micros, Values.fromJava(summer.toInstant(), DataType.TIMESTAMPTZ));
        assertEquals(micros, Values.fromJava(summer.atZoneSameInstant(BERLIN), DataType.TIMESTAMPTZ));
    }

    @Test
    void theEndOfTheDayIsTheLastNanosecond() {
        assertEquals(LocalTime.MAX, Values.toJava(86_400_000_000L, DataType.TIME, BERLIN));
        assertEquals(86_399_999_999L, Values.fromJava(LocalTime.MAX, DataType.TIME));
    }

    @Test
    void digitsBelowAMicrosecondAreCutOff() {
        assertEquals(1L, Values.fromJava(LocalTime.of(0, 0, 0, 1999), DataType.TIME));
        assertEquals(1L, Values.fromJava(Instant.ofEpochSecond(0, 1999), DataType.TIMESTAMPTZ));
    }

    @Test
    void intervalsStayAndComeFromDurationsAndPeriods() {
        Interval interval = Interval.of(14, 3, 5_000_000L);
        assertSame(interval, Values.toJava(interval, DataType.INTERVAL, BERLIN));
        assertEquals(Interval.ofMicros(5_000_001L), Values.fromJava(Duration.ofSeconds(5, 1000), DataType.INTERVAL));
        assertEquals(Interval.of(14, 3, 0), Values.fromJava(Period.of(1, 2, 3), DataType.INTERVAL));
        assertSame(interval, Values.fromJava(interval, DataType.INTERVAL));
        assertFalse(Values.hasJavaTime(DataType.INTERVAL));
    }

    @Test
    void nestedValuesAreConverted() {
        DataType dates = DataType.listOf(DataType.DATE);
        roundTrip(dates, Arrays.asList(0, null, 1), Arrays.asList(LocalDate.EPOCH, null, LocalDate.of(1970, 1, 2)));

        DataType struct = DataType.struct(new DataType.Struct.Field("name", DataType.VARCHAR),
                new DataType.Struct.Field("at", DataType.TIMESTAMP));
        roundTrip(struct, List.of("a", 1_000_000L), List.of("a", LocalDateTime.of(1970, 1, 1, 0, 0, 1)));

        DataType map = DataType.mapOf(DataType.DATE, DataType.TIMESTAMPTZ);
        Map<Object, Object> internal = new LinkedHashMap<>();
        internal.put(1, 0L);
        internal.put(0, null);
        Map<Object, Object> java = new LinkedHashMap<>();
        java.put(LocalDate.of(1970, 1, 2), OffsetDateTime.of(1970, 1, 1, 1, 0, 0, 0, ZoneOffset.ofHours(1)));
        java.put(LocalDate.EPOCH, null);
        roundTrip(map, internal, java);
        assertEquals(List.of(LocalDate.of(1970, 1, 2), LocalDate.EPOCH),
                new ArrayList<>(((Map<?, ?>) Values.toJava(internal, map, BERLIN)).keySet()));

        DataType intervals = DataType.listOf(DataType.INTERVAL);
        assertEquals(List.of(Interval.ofMicros(1)), Values.fromJava(List.of(Duration.ofNanos(1000)), intervals));
    }

    @Test
    void valuesWithoutTimeAreReturnedAsTheyAre() {
        List<Object> numbers = List.of(1, 2);
        DataType list = DataType.listOf(DataType.INTEGER);
        assertSame(numbers, Values.toJava(numbers, list, BERLIN));
        assertSame(numbers, Values.fromJava(numbers, list));
        BigDecimal amount = new BigDecimal("1.50");
        assertSame(amount, Values.toJava(amount, DataType.decimal(10, 2), BERLIN));
        assertFalse(Values.hasJavaTime(list));
        assertTrue(Values.hasJavaTime(DataType.mapOf(DataType.VARCHAR, DataType.listOf(DataType.TIME))));
        assertNull(Values.toJava(null, DataType.DATE, BERLIN));
        assertNull(Values.fromJava(null, DataType.DATE));
    }

    @Test
    void theFormInAVectorPassesAndOtherClassesAreLeftToRequire() {
        assertEquals(20465, Values.fromJava(20465, DataType.DATE));
        assertEquals(5L, Values.fromJava(5L, DataType.TIMESTAMPTZ));
        assertEquals(List.of(1, 2), Values.fromJava(List.of(1, 2), DataType.listOf(DataType.DATE)));
        Object wrong = Values.fromJava(LocalDateTime.of(2026, 1, 12, 0, 0), DataType.DATE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Values.require(DataType.DATE, wrong));
        assertTrue(e.getMessage().startsWith("Not a DATE: LocalDateTime 2026-01-12T00:00"), e.getMessage());
        assertThrows(ArithmeticException.class, () -> Values.fromJava(LocalDate.MAX, DataType.DATE));
    }

    private static void roundTrip(DataType type, Object internal, Object java) {
        Object converted = Values.toJava(internal, type, BERLIN);
        assertEquals(java, converted, type.sqlName());
        assertEquals(internal, Values.fromJava(converted, type), type.sqlName());
        Values.require(type, Values.fromJava(java, type));
    }
}
