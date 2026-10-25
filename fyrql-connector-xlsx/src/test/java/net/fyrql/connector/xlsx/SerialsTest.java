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

package net.fyrql.connector.xlsx;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerialsTest {

    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    /** Serial numbers and the timestamps DuckDB 1.5.6 reads them as, one day later from 0 to below 61. */
    @Test
    void timestampsAsDuckDbReadsThem() {
        String[][] cases = {
            {"61", "1900-03-01T00:00"},
            {"61.5", "1900-03-01T12:00"},
            {"45293.52426839263", "2024-01-02T12:34:56.789123"},
            {"45293.99999999999", "2024-01-03T00:00"},
            {"2958465.9999999", "9999-12-31T23:59:59.991360"},
            {"100000.123456789", "2173-10-14T02:57:46.666571"},
            {"45000.000005787", "2023-03-15T00:00:00.499996"},
            {"45000.000011574", "2023-03-15T00:00:01"},
            {"45000.999994213", "2023-03-15T23:59:59.500003"},
            {"45000.9999999", "2023-03-15T23:59:59.991359"},
            {"45000.99999999", "2023-03-16T00:00"},
            {"73415.99930555555", "2100-12-31T23:59"},
            {"25947.86738799817", "1971-01-14T20:49:02.323041"},
            {"5645.172705131463", "1915-06-15T04:08:41.723359"},
            {"23212.07674006787", "1963-07-20T01:50:30.341865"},
            {"0.04166666782407408", "1899-12-31T01:00"},
            {"45000.04166667824", "2023-03-15T01:00"},
            {"45000.04166667836", "2023-03-15T01:00:00.001010"},
            {"45000.50000001169", "2023-03-15T12:00:00.001010"},
            {"2958466", "+10000-01-01T00:00"},
            {"-1", "1899-12-29T00:00"},
        };
        for (String[] c : cases) {
            assertEquals(LocalDateTime.parse(c[1]), timestamp(Serials.toMicros(Double.parseDouble(c[0]), false)), c[0]);
        }
    }

    /** Before 1900-03-01, Excel counts from 1899-12-31 and has a 29th of February 1900. */
    @Test
    void theFirstDaysOf1900AreExcels() {
        assertEquals(LocalDateTime.parse("1899-12-31T00:00"), timestamp(Serials.toMicros(0, false)));
        assertEquals(LocalDateTime.parse("1899-12-31T12:00"), timestamp(Serials.toMicros(0.5, false)));
        assertEquals(LocalDateTime.parse("1900-01-01T00:00"), timestamp(Serials.toMicros(1, false)));
        assertEquals(LocalDateTime.parse("1900-01-01T00:00"), timestamp(Serials.toMicros(0.999999999988426, false)));
        assertEquals(LocalDateTime.parse("1900-02-28T00:00"), timestamp(Serials.toMicros(59, false)));
        assertEquals(LocalDateTime.parse("1900-03-01T00:00"), timestamp(Serials.toMicros(60, false)));
        assertEquals(LocalDateTime.parse("1900-03-01T00:00"), timestamp(Serials.toMicros(61, false)));
        assertEquals(1.0, Serials.toSerial(micros("1900-01-01T00:00")));
        assertEquals(59.0, Serials.toSerial(micros("1900-02-28T00:00")));
        assertEquals(61.0, Serials.toSerial(micros("1900-03-01T00:00")));
        assertEquals(-1.0, Serials.toSerial(micros("1899-12-29T00:00")));
    }

    @Test
    void theCalendarOf1904() {
        assertEquals(LocalDateTime.parse("1904-01-01T00:00"), timestamp(Serials.toMicros(0, true)));
        assertEquals(LocalDateTime.parse("1904-01-02T06:00"), timestamp(Serials.toMicros(1.25, true)));
        assertEquals(LocalDateTime.parse("2024-02-29T00:00"), timestamp(Serials.toMicros(43889, true)));
    }

    @Test
    void datesAndTimesOfDay() {
        long micros = Serials.toMicros(25.75, false);
        assertEquals(LocalDateTime.parse("1900-01-25T18:00").toLocalDate().toEpochDay(), Serials.days(micros));
        assertEquals(18L * 3600 * 1_000_000, Serials.timeOfDay(micros));
        long negative = Serials.toMicros(-1.25, false);
        assertEquals(LocalDateTime.parse("1899-12-28T18:00"), timestamp(negative));
        assertEquals(18L * 3600 * 1_000_000, Serials.timeOfDay(negative));
    }

    @Test
    void numbersOutOfRange() {
        for (double v : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1e300, -1e300}) {
            assertThrows(ArithmeticException.class, () -> Serials.toMicros(v, false));
        }
    }

    /**
     * A written timestamp reads back exactly unless it is less than a
     * millisecond away from a whole second, up to the serial number 65536 in
     * 2079: beyond it a double has no number for every microsecond.
     */
    @Test
    void writtenSerialsReadBack() {
        Random random = new Random(20261025);
        long from = micros("1900-03-01T00:00");
        long to = micros("2079-06-05T00:00");
        int checked = 0;
        while (checked < 20_000) {
            long m = from + (long) (random.nextDouble() * (to - from));
            long fraction = Math.floorMod(m, 1_000_000L);
            if (fraction < 1000 || fraction > 999_000) {
                continue;
            }
            assertEquals(m, Serials.toMicros(Serials.toSerial(m), false), () -> timestamp(m).toString());
            checked++;
        }
        long whole = micros("2024-02-29T13:45:30");
        assertEquals(whole, Serials.toMicros(Serials.toSerial(whole), false));
    }

    private static LocalDateTime timestamp(long micros) {
        return EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    private static long micros(String timestamp) {
        LocalDateTime t = LocalDateTime.parse(timestamp);
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1000;
    }
}
