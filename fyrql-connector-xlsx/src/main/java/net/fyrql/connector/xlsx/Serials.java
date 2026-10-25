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

/**
 * The serial numbers of Excel, the days since the start of its calendar
 * with the time of day as their fraction, converted to and from
 * microseconds since 1970-01-01.
 * <p>
 * The arithmetic is DuckDB's, so that both read a number to the same
 * microsecond: the seconds since 1970 are {@code v * 86400 - 25569 * 86400},
 * cut to microseconds, and a time less than a millisecond away from a whole
 * second goes to that second, as the time of day judges it.
 * <p>
 * Two things differ from DuckDB, which counts every serial number from
 * 1899-12-30: in the calendar of 1900 the numbers below 61 count from
 * 1899-12-31, as Excel and Apache POI read them, since Excel takes 1900
 * for a leap year (1 is 1900-01-01, the 60 of the 29th of February that
 * never was reads as 1900-03-01); and a workbook with dates of 1904 counts
 * from 1904-01-01.
 */
final class Serials {

    static final long MICROS_PER_DAY = 86_400_000_000L;

    /** The days from 1899-12-30 to 1970-01-01. */
    private static final double DAYS_TO_1970 = 25569;

    /** The days from the start of the calendar of 1900 to that of 1904. */
    private static final double DAYS_1900_TO_1904 = 1462;

    /** The magnitude of microseconds beyond which a number is no timestamp, about the year 290000. */
    private static final double MAX_MICROS = 9.0e18;

    private Serials() {
    }

    /**
     * Converts a serial number to microseconds since 1970-01-01.
     *
     * @throws ArithmeticException if the number is not finite or too large for a timestamp
     */
    static long toMicros(double serial, boolean date1904) {
        double v = serial;
        if (date1904) {
            v += DAYS_1900_TO_1904;
        } else if (v >= 0 && v < 61) {
            v += 1;
        }
        double seconds = v * 86400.0 - DAYS_TO_1970 * 86400.0;
        double micros = seconds * 1e6;
        if (!(Math.abs(micros) < MAX_MICROS)) {
            throw new ArithmeticException("the serial number " + serial + " is out of the range of a timestamp");
        }
        long result = (long) micros;
        double secondOfDay = (v - Math.floor(v)) * 86400.0;
        if (Math.abs(secondOfDay - Math.rint(secondOfDay)) < 0.001) {
            result = Math.floorDiv(result + 500_000L, 1_000_000L) * 1_000_000L;
        }
        return result;
    }

    /** Returns the days since 1970-01-01 of a number of microseconds since then. */
    static long days(long micros) {
        return Math.floorDiv(micros, MICROS_PER_DAY);
    }

    /** Returns the microseconds since midnight of a number of microseconds since 1970-01-01. */
    static long timeOfDay(long micros) {
        return Math.floorMod(micros, MICROS_PER_DAY);
    }

    /**
     * Converts microseconds since 1970-01-01 to a serial number of the
     * calendar of 1900, the inverse of {@link #toMicros}: the numbers before
     * 1900-03-01 count from 1899-12-31. Of the numbers next to the quotient,
     * the one that {@link #toMicros} reads back as the same microsecond is
     * taken if there is one, so that a written timestamp reads back exactly
     * unless it is less than a millisecond away from a whole second.
     */
    static double toSerial(long micros) {
        double v = micros / 1e6 / 86400.0 + DAYS_TO_1970;
        if (v >= 1 && v < 61) {
            v -= 1;
        }
        for (int i = 0; i < 3; i++) {
            long error = micros - toMicros(v, false);
            if (error == 0) {
                return v;
            }
            v += error / (double) MICROS_PER_DAY;
        }
        double up = v;
        double down = v;
        for (int i = 0; i < 16; i++) {
            up = Math.nextUp(up);
            if (toMicros(up, false) == micros) {
                return up;
            }
            down = Math.nextDown(down);
            if (toMicros(down, false) == micros) {
                return down;
            }
        }
        return v;
    }

    /**
     * Converts a time of day in microseconds since midnight to the fraction
     * of a day, which {@link #toMicros} and {@link #timeOfDay} read back as
     * the same microsecond where one of the numbers next to the quotient
     * does; 24:00:00 is the fraction 1.
     */
    static double timeSerial(long micros) {
        double v = micros / (double) MICROS_PER_DAY;
        if (micros >= MICROS_PER_DAY || timeOfDay(toMicros(v, false)) == micros) {
            return v;
        }
        double up = v;
        double down = v;
        for (int i = 0; i < 64; i++) {
            up = Math.nextUp(up);
            if (timeOfDay(toMicros(up, false)) == micros) {
                return up;
            }
            down = Math.nextDown(down);
            if (down >= 0 && timeOfDay(toMicros(down, false)) == micros) {
                return down;
            }
        }
        return v;
    }
}
