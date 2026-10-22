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

package net.fyrql.core.value;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Period;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Intervals with the values and texts that DuckDB 1.5.6 gives. */
class IntervalTest {

    private static String text(String s) {
        return Interval.parse(s).toString();
    }

    @Test
    void textForms() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("1 year 2 months -3 days 04:05:06", "1 year 2 months -3 days 04:05:06"),
                Map.entry("1 year 13 months", "2 years 1 month"),
                Map.entry("25 hours", "25:00:00"),
                Map.entry("1.5 days", "1 day 12:00:00"),
                Map.entry("0.5 years", "6 months"),
                Map.entry("1 year 1.5 months", "1 year 1 month 15 days"),
                Map.entry("-0.5 seconds", "-00:00:00.5"),
                Map.entry("0 days", "00:00:00"),
                Map.entry("100 hours", "100:00:00"),
                Map.entry("1 week", "7 days"),
                Map.entry("1 quarter", "3 months"),
                Map.entry("1 decade", "10 years"),
                Map.entry("1 centuries", "100 years"),
                Map.entry("1 millennia", "1000 years"),
                Map.entry("1 ms", "00:00:00.001"),
                Map.entry("1 usec", "00:00:00.000001"),
                Map.entry("2 mins", "00:02:00"),
                Map.entry("3 s", "00:00:03"),
                Map.entry("1 yrs", "1 year"),
                Map.entry("1 mons", "1 month"),
                Map.entry("1 d", "1 day"),
                Map.entry("@ 1 day", "1 day"),
                Map.entry("1 day ago", "-1 day"),
                Map.entry("1 day -1 hour", "1 day -01:00:00"),
                Map.entry("1:2", "01:02:00"),
                Map.entry("1:2:3", "01:02:03"),
                Map.entry("1", "00:00:01"),
                Map.entry("1.5", "00:00:01.5"),
                Map.entry("  1 day  ", "1 day"),
                Map.entry("1DAY", "1 day"),
                Map.entry("1 Day", "1 day"),
                Map.entry("1 day 1 day", "2 days"),
                Map.entry("-1.5 months", "-1 month -15 days"),
                Map.entry("1 day 25:00:00", "1 day 25:00:00"),
                Map.entry("00:00:00.1234567", "00:00:00.123456"),
                Map.entry("2147483647 days", "2147483647 days"),
                Map.entry("9223372036854775807 microseconds", "2562047788:00:54.775807"),
                Map.entry("1000000 years", "1000000 years"),
                Map.entry("0.142857142857 months", "4 days"),
                Map.entry("0.1 years", "1 month"),
                Map.entry("0.01 years", "00:00:00"),
                Map.entry("1.3333333 days", "1 day 07:59:59.9712"),
                Map.entry("1.0000005 seconds", "00:00:01"),
                Map.entry("1.0000015 seconds", "00:00:01.000001"),
                Map.entry("0.5 microseconds", "00:00:00.000001"),
                Map.entry("1.5 weeks", "10 days 12:00:00"),
                Map.entry("1.5 decades", "15 years"),
                Map.entry("1.5 hours", "01:30:00"),
                Map.entry("-2147483648 months", "-178956970 years -8 months"));
        cases.forEach((in, out) -> assertEquals(out, text(in), in));
        for (String bad : new String[] {"+1 day", "-1 day +02:00", "1 2:00", "P1D", "P1Y2M", "1 micros", "1e2 days",
            "1 dayss", "1 day 2", "", "day", "25:61:62.5"}) {
            assertThrows(ConversionException.class, () -> Interval.parse(bad), bad);
        }
        assertThrows(ArithmeticException.class, () -> Interval.parse("2147483648 days"));
        assertEquals("extract specifier \"micros\" not recognized",
                assertThrows(ConversionException.class, () -> Interval.parse("1 micros")).getMessage());
    }

    @Test
    void normalizedComparison() {
        assertEquals(Interval.parse("1 month"), Interval.parse("30 days"));
        assertEquals(Interval.parse("1 month"), Interval.parse("720 hours"));
        assertEquals(Interval.parse("1 month").hashCode(), Interval.parse("720 hours").hashCode());
        assertEquals(Interval.parse("1 year 13 months"), Interval.parse("2 years 1 month"));
        assertNotEquals(Interval.parse("1 day -1 hour"), Interval.parse("23 hours"));
        assertNotEquals(Interval.parse("1 month -1 day"), Interval.parse("29 days"));
        assertEquals(Interval.parse("-36 hours"), Interval.parse("-1 day -12 hours"));
        assertEquals(Interval.parse("1 day -24 hours"), Interval.ZERO);
        assertEquals(Interval.parse("-1 month"), Interval.parse("-30 days"));
        assertTrue(Interval.parse("-36 hours").compareTo(Interval.parse("-1 day")) < 0);
        assertTrue(Interval.parse("1 month").compareTo(Interval.parse("29 days 23:59:59")) > 0);
        assertTrue(Interval.parse("1 month").compareTo(Interval.parse("30 days 00:00:00.000001")) < 0);
        assertTrue(Interval.parse("-1 day").compareTo(Interval.parse("-23 hours")) < 0);
        // The days that the microseconds add are not carried into months again.
        assertNotEquals(Interval.parse("1 month"), Interval.parse("29 days 24 hours"));
    }

    @Test
    void arithmetic() {
        assertEquals("1 month -30 days", Interval.parse("1 month 1 day").minus(Interval.parse("31 days")).toString());
        assertEquals("-1 month -2 days -03:00:00", Interval.parse("1 month 2 days 3 hours").negate().toString());
        assertEquals("Overflow in addition of INT32 (2000000000 + 2000000000)!",
                assertThrows(ArithmeticException.class, () -> Interval.ofMonths(2_000_000_000)
                        .plus(Interval.ofMonths(2_000_000_000))).getMessage());
        assertEquals("Overflow in multiplication of INT32 (2000000000 * 2)!",
                assertThrows(ArithmeticException.class, () -> Interval.ofMonths(2_000_000_000).times(2))
                        .getMessage());
        assertEquals("Overflow in negation of numeric value!",
                assertThrows(ArithmeticException.class, () -> Interval.ofMonths(Integer.MIN_VALUE).negate())
                        .getMessage());
        assertEquals("178956970 years 7 months", Interval.ofMonths(1).times(2_147_483_647L).toString());
        assertEquals("3 days", Interval.ofDays(1).times(3).toString());
    }

    @Test
    void fractions() {
        Interval month = Interval.ofMonths(1);
        Interval day = Interval.ofDays(1);
        assertEquals("4 days 06:51:25.6896", month.dividedBy(7).toString());
        assertEquals("4 days 06:51:25.6896", month.times(1.0 / 7).toString());
        assertEquals("10 days", month.dividedBy(3).toString());
        assertEquals("3 days 07:59:59.9712", month.dividedBy(9).toString());
        assertEquals("2 days 17:27:16.3872", month.dividedBy(11).toString());
        assertEquals("9 days", month.times(0.3).toString());
        assertEquals("9 days 21:36:00", month.times(0.33).toString());
        assertEquals("9 days 23:59:59.9136", month.times(0.3333333).toString());
        assertEquals("1 month 00:00:00.2592", month.times(1.0000001).toString());
        assertEquals("07:59:59.99712", day.times(0.3333333).toString());
        assertEquals("08:00:00", day.dividedBy(3).toString());
        assertEquals("03:25:42.857143", day.dividedBy(7).toString());
        assertEquals("17:08:34.285714", Interval.ofDays(5).dividedBy(7).toString());
        assertEquals("3 months 15 days", Interval.ofMonths(7).dividedBy(2).toString());
        assertEquals("3 months 16 days 12:00:00.5", Interval.parse("7 months 3 days 1 second").dividedBy(2).toString());
        assertEquals("-3 months -15 days", Interval.ofMonths(-7).dividedBy(2).toString());
        assertEquals("2 months 15 days", month.times(2.5).toString());
        assertEquals("1 month 16 days 12:00:00", Interval.parse("1 month 1 day").times(1.5).toString());
        assertEquals("15 days 12:00:00.5", Interval.parse("1 month 1 day 1 second").times(0.5).toString());
        assertEquals("01:00:00.0036", Interval.parse("1 hour").times(1.000001).toString());
        assertEquals("00:00:00", Interval.ofMicros(1).dividedBy(2).toString());
        assertEquals("00:00:00.000002", Interval.ofMicros(3).dividedBy(2).toString());
        assertEquals("-00:00:00.000002", Interval.ofMicros(-3).dividedBy(2).toString());
        assertEquals("4 months", Interval.parse("1 year").dividedBy(3).toString());
        assertEquals("2 days 12:00:00", Interval.ofDays(10).dividedBy(4).toString());
        assertEquals("Overflow in multiplication of INTERVAL.",
                assertThrows(ArithmeticException.class, () -> day.times(1e10)).getMessage());
        assertThrows(ArithmeticException.class, () -> Interval.ofMicros(1_000_000).times(1e20));
        assertThrows(ArithmeticException.class, () -> day.times(Double.NaN));
        assertThrows(ArithmeticException.class, () -> day.dividedBy(0));
    }

    /**
     * The values of DuckDB 1.5.6: a fraction of a day that the months and
     * the days leave together goes to the days when it reaches a whole day,
     * a division multiplies by the reciprocal, and the mean carries the
     * remainders of whole numbers.
     */
    @Test
    void fractionsCarryAsInDuckDb() {
        Interval mixed = Interval.parse("10 years 3 months 900 days 12345:06:07.891011");
        assertEquals("22 days 06:51:25.677257", Interval.parse("5 months 6 days").dividedBy(7).toString());
        assertEquals("-22 days -06:51:25.677257", Interval.parse("-5 months -6 days").dividedBy(7).toString());
        assertEquals("-8 days -26:22:08.988782", mixed.dividedBy(-561).toString());
        assertEquals("7 days 38:56:03.449934", mixed.dividedBy(592).toString());
        assertEquals("14 years 1248 days 16919:59:24.010685", mixed.times(1.37).toString());
        assertEquals("-18 days -06:55:30.493394", Interval.parse("-7 years -900 days 01:00:00.5").dividedBy(187)
                .toString());
        assertEquals("-23:05:14.46213", Interval.parse("-161:36:41.234911").dividedBy(7).toString());
        assertEquals("Overflow in INTERVAL division",
                assertThrows(ArithmeticException.class, () -> Interval.ofDays(1).dividedBy(1e-10)).getMessage());
        assertEquals("10 days 08:00:00.333334", Interval.mean(1, 1, BigInteger.valueOf(1_000_000), 3).toString());
        assertEquals("21 days 30:51:25.714285", Interval.mean(5, 6, BigInteger.ZERO, 7).toString());
        assertEquals("00:00:00.000002", Interval.mean(0, 0, BigInteger.TWO, 3).toString());
        assertEquals("-15 days", Interval.mean(-1, 0, BigInteger.ZERO, 2).toString());
    }

    @Test
    void epochAndConversions() {
        assertEquals(2_592_000.0, Interval.ofMonths(1).epochSeconds());
        assertEquals(71_845_262.5, Interval.parse("1 year 14 months 40 days 25:01:02.5").epochSeconds());
        assertEquals(Interval.parse("1 day 02:00:00.5"), Interval.from(Duration.ofHours(26).plusMillis(500)));
        assertEquals(Interval.of(14, 3, 0), Interval.from(Period.of(1, 2, 3)));
        assertEquals(Duration.ofHours(26), Interval.parse("1 day 2 hours").toDuration());
        assertEquals(Period.of(0, 14, 3), Interval.parse("1 year 2 months 3 days").toPeriod());
        assertThrows(ArithmeticException.class, () -> Interval.ofMonths(1).toDuration());
        assertThrows(ArithmeticException.class, () -> Interval.ofMicros(1).toPeriod());
    }

    @Test
    void theLeastAndGreatestMicrosecondsReadBack() {
        Interval least = Interval.ofMicros(Long.MIN_VALUE);
        assertEquals("-2562047788:00:54.775808", least.toString());
        assertEquals(least, Interval.parse(least.toString()));
        Interval greatest = Interval.ofMicros(Long.MAX_VALUE);
        assertEquals(greatest, Interval.parse(greatest.toString()));
    }
}
