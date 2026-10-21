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

package net.fyrql.core.expr;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.ThreeValuedLogicTest.eval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Dates, timestamps and their functions, with the values that DuckDB 1.5.6 gives. */
class DateTimeTest {

    private static Expression date(String s) {
        return lit(DataType.DATE, Conversions.parseDate(s));
    }

    private static Expression timestamp(String s) {
        return lit(DataType.TIMESTAMP, Conversions.parseTimestamp(s));
    }

    private static String text(Object value, DataType type) {
        return Casts.format(value, type);
    }

    @Test
    void datesBeforeYearOneAndAfter9999() {
        assertEquals("0001-12-31 (BC)", Conversions.formatDate(Conversions.parseDate("0001-01-01") - 1));
        assertEquals("0002-11-28 (BC)", Conversions.formatDate(Conversions.parseDate("0001-01-01") - 400));
        assertEquals("0151-03-01 (BC)", Conversions.formatDate(Conversions.parseDate("-0150-03-01")));
        assertEquals("10000-01-01", Conversions.formatDate(Conversions.parseDate("9999-12-31") + 1));
        assertEquals("0001-12-31 (BC) 10:00:00",
                Conversions.formatTimestamp(Conversions.parseTimestamp("0001-01-01 10:00:00") - 86_400_000_000L));
        assertEquals("0002-02-03 (BC) 10:11:12",
                Conversions.formatTimestamp(Conversions.parseTimestamp("0002-02-03 (BC) 10:11:12")));
        assertEquals("0002-02-03 (BC) 10:11:12",
                Conversions.formatTimestamp(Conversions.parseTimestamp("0002-02-03 (BC)T10:11:12")));
        assertEquals("0002-02-03 (BC) 00:00:00",
                Conversions.formatTimestamp(Conversions.parseTimestamp("0002-02-03 (BC)")));
        // Only a single whitespace character and (BC) count; anything else after the day is ignored.
        assertEquals("0002-02-03", Conversions.formatDate(Conversions.parseDate("0002-02-03(BC)")));
        assertEquals("0002-02-03", Conversions.formatDate(Conversions.parseDate("0002-02-03 BC")));
        assertEquals("0001-02-03 (BC)", Conversions.formatDate(Conversions.parseDate("0000-02-03")));
        assertThrows(ConversionException.class, () -> Conversions.parseDate("0000-02-03 (BC)"));
        assertThrows(ConversionException.class, () -> Conversions.parseTimestamp("0002-02-03 10:11:12 (BC)"));
        assertEquals("5881580-07-10", Conversions.formatDate(Conversions.parseDate("5881580-07-10")));
        assertThrows(ConversionException.class, () -> Conversions.parseDate("5881580-07-11"));
        assertThrows(ArithmeticException.class,
                () -> Casts.cast(Conversions.parseDate("294247-01-11"), DataType.DATE, DataType.TIMESTAMP));
        assertEquals("294247-01-10 00:00:00", text(Casts.cast(Conversions.parseDate("294247-01-10"), DataType.DATE,
                DataType.TIMESTAMP), DataType.TIMESTAMP));
    }

    @Test
    void dateArithmetic() {
        Expression d = date("2020-01-01");
        assertEquals(DataType.DATE, add(d, lit(1)).type());
        assertEquals("2020-01-02", text(eval(add(d, lit(1))), DataType.DATE));
        assertEquals("2020-01-03", text(eval(add(lit((byte) 2), d)), DataType.DATE));
        assertEquals("2019-12-31", text(eval(sub(d, lit((short) 1))), DataType.DATE));
        assertEquals(DataType.BIGINT, sub(d, date("1990-05-03")).type());
        assertEquals(10835L, eval(sub(d, date("1990-05-03"))));
        assertThrows(ArithmeticException.class, () -> eval(add(d, lit(Integer.MAX_VALUE))));
        assertThrows(ArithmeticException.class, () -> eval(sub(d, lit(-Integer.MAX_VALUE))));
        assertThrows(IllegalArgumentException.class, () -> add(d, lit(1L)), "DuckDB adds no BIGINT to a date");
        assertThrows(IllegalArgumentException.class, () -> sub(lit(1), d));
        assertThrows(IllegalArgumentException.class, () -> add(d, d));
        assertEquals(DataType.INTERVAL, sub(timestamp("2020-01-01"), timestamp("2020-01-01")).type());
    }

    @Test
    void dateParts() {
        Expression ts = timestamp("1969-12-28 13:14:15.678912");
        Map<String, Long> expected = Map.ofEntries(Map.entry("year", 1969L), Map.entry("month", 12L),
                Map.entry("day", 28L), Map.entry("decade", 196L), Map.entry("century", 20L),
                Map.entry("millennium", 2L), Map.entry("microseconds", 15_678_912L), Map.entry("ms", 15_678L),
                Map.entry("second", 15L), Map.entry("minute", 14L), Map.entry("hour", 13L), Map.entry("dow", 0L),
                Map.entry("isodow", 7L), Map.entry("week", 52L), Map.entry("doy", 362L), Map.entry("quarter", 4L),
                Map.entry("yearweek", 196_952L), Map.entry("isoyear", 1969L), Map.entry("era", 1L),
                Map.entry("Month", 12L), Map.entry("yrs", 1969L));
        expected.forEach((part, value) -> assertEquals(value, eval(call("date_part", lit(part), ts)), part));
        assertEquals(0L, eval(call("date_part", lit("hour"), date("1969-12-28"))));
        assertEquals(13L, eval(call("hour", ts)));
        assertEquals(1969L, eval(call("year", date("1969-12-28"))));
        assertEquals(0L, eval(call("year", date("0001-01-01 (BC)"))));
        assertEquals(-1L, eval(call("year", date("0002-01-01 (BC)"))));
        for (String part : List.of("foo", " year", "epoch", "julian")) {
            assertThrows(ConversionException.class, () -> eval(call("date_part", lit(part), ts)), part);
        }
        assertEquals(-297_944.321088, eval(call("epoch", ts)));
        assertEquals(-345_600.0, eval(call("epoch", date("1969-12-28"))));
        assertEquals(0L, eval(call("epoch_ms", timestamp("1969-12-31 23:59:59.9995"))), "rounded towards zero");
        assertEquals(-345_600_000L, eval(call("epoch_ms", date("1969-12-28"))));
        assertEquals(-297_944_321_088L, eval(call("epoch_us", ts)));
        assertEquals(-297_944_321_088_000L, eval(call("epoch_ns", ts)));
        assertThrows(ArithmeticException.class, () -> eval(call("epoch_ns", timestamp("2500-01-01"))));
        assertEquals("1969-12-31 23:59:59.999", text(eval(call("epoch_ms", lit(-1L))), DataType.TIMESTAMP));
        assertThrows(ArithmeticException.class, () -> eval(call("epoch_ms", lit(9_223_372_036_854_776L))));
        assertEquals("2024-02-29", text(eval(call("last_day", date("2024-02-03"))), DataType.DATE));
        assertEquals("February", eval(call("monthname", date("2024-02-03"))));
        assertEquals("Saturday", eval(call("dayname", date("2024-02-03"))));
    }

    @Test
    void dateTrunc() {
        Expression ts = timestamp("1969-12-28 13:14:15.678912");
        Map<String, String> expected = Map.ofEntries(Map.entry("millennium", "1000-01-01 00:00:00"),
                Map.entry("century", "1900-01-01 00:00:00"), Map.entry("decade", "1960-01-01 00:00:00"),
                Map.entry("year", "1969-01-01 00:00:00"), Map.entry("quarter", "1969-10-01 00:00:00"),
                Map.entry("month", "1969-12-01 00:00:00"), Map.entry("week", "1969-12-22 00:00:00"),
                Map.entry("yearweek", "1969-12-22 00:00:00"), Map.entry("isoyear", "1968-12-30 00:00:00"),
                Map.entry("day", "1969-12-28 00:00:00"), Map.entry("dow", "1969-12-28 00:00:00"),
                Map.entry("julian", "1969-12-28 00:00:00"), Map.entry("hour", "1969-12-28 13:00:00"),
                Map.entry("minute", "1969-12-28 13:14:00"), Map.entry("second", "1969-12-28 13:14:15"),
                Map.entry("epoch", "1969-12-28 13:14:15"), Map.entry("ms", "1969-12-28 13:14:15.678"),
                Map.entry("us", "1969-12-28 13:14:15.678912"));
        expected.forEach((part, value) -> assertEquals(value,
                text(eval(call("date_trunc", lit(part), ts)), DataType.TIMESTAMP), part));
        assertEquals(DataType.TIMESTAMP, call("date_trunc", lit("month"), date("1969-12-28")).type());
        assertEquals("1969-12-28 00:00:00",
                text(eval(call("date_trunc", lit("hour"), date("1969-12-28"))), DataType.TIMESTAMP));
        assertEquals("0011-01-01 (BC) 00:00:00",
                text(eval(call("date_trunc", lit("decade"), date("-0015-05-01"))), DataType.TIMESTAMP));
        assertThrows(ConversionException.class, () -> eval(call("date_trunc", lit("era"), ts)));
    }

    @Test
    void dateDiff() {
        assertEquals(0L, eval(call("date_diff", lit("week"), date("2024-01-07"), date("2024-01-01"))));
        assertEquals(0L, eval(call("date_diff", lit("week"), date("2024-01-01"), date("2024-01-07"))));
        assertEquals(1L, eval(call("date_diff", lit("week"), date("2024-01-01"), date("2024-01-08"))));
        assertEquals(1L, eval(call("date_diff", lit("hour"), timestamp("1969-12-31 23:30:00"),
                timestamp("1970-01-01 00:30:00"))), "the boundaries in between, also before 1970");
        assertEquals(1L, eval(call("date_diff", lit("second"), timestamp("1969-12-31 23:59:59.5"),
                timestamp("1970-01-01 00:00:00.5"))));
        assertEquals(-518_400L, eval(call("date_diff", lit("epoch"), date("2024-01-07"), date("2024-01-01"))));
        assertEquals(24L, eval(call("datediff", lit("dow"), date("2024-01-07"), date("2024-01-31"))));
        assertEquals(3L, eval(call("date_diff", lit("yearweek"), date("2024-01-07"), date("2024-01-31"))));
        assertEquals(1L, eval(call("date_diff", lit("month"), date("2020-01-31"), date("2020-02-01"))));
        assertEquals(315_506_361_600_000_000L, eval(call("date_diff", lit("microseconds"),
                timestamp("0001-01-01 00:00:00"), timestamp("9999-01-01 00:00:00"))));
        assertEquals(94L, eval(call("date_diff", lit("hour"), date("1969-12-28"), timestamp("1969-12-31 22:30:00"))));
        assertThrows(ConversionException.class, () -> eval(call("date_diff", lit("era"), date("2024-01-07"),
                date("2024-01-01"))));
    }

    @Test
    void makeDateAndTimestamp() {
        assertEquals("0003-04-01", text(eval(call("make_date", lit(3), lit(4L), lit((byte) 1))), DataType.DATE));
        assertEquals("1970-01-04", text(eval(call("make_date", lit(3))), DataType.DATE));
        assertEquals("0006-02-03 (BC)", text(eval(call("make_date", lit(-5), lit(2), lit(3))), DataType.DATE));
        assertEquals("5881580-07-10", text(eval(call("make_date", lit(5_881_580), lit(7), lit(10))), DataType.DATE));
        for (long[] bad : new long[][] {{2020, 2, 30}, {5_881_580, 7, 11}, {-5_877_641, 6, 24}, {2_147_483_648L, 1, 1}}) {
            assertThrows(ConversionException.class,
                    () -> eval(call("make_date", lit(bad[0]), lit(bad[1]), lit(bad[2]))));
        }
        Map<Double, String> seconds = Map.of(5.5, "2020-01-02 03:04:05.5", 60.0, "2020-01-02 03:05:00",
                59.9999999, "2020-01-02 03:05:00", 59.9999994, "2020-01-02 03:04:59.999999",
                0.0000005, "2020-01-02 03:04:00.000001", 1.0000015, "2020-01-02 03:04:01.000001",
                -0.0, "2020-01-02 03:04:00");
        seconds.forEach((s, value) -> assertEquals(value, text(eval(call("make_timestamp", lit(2020L), lit(1L),
                lit(2L), lit(3L), lit(4L), lit(s))), DataType.TIMESTAMP), String.valueOf(s)));
        assertEquals("2020-01-03 00:00:00", text(eval(call("make_timestamp", lit(2020), lit(1), lit(2), lit(24),
                lit(0), lit(0))), DataType.TIMESTAMP));
        for (double bad : new double[] {-0.1, Double.NaN, 61.0}) {
            assertThrows(ConversionException.class, () -> eval(call("make_timestamp", lit(2020), lit(1), lit(2),
                    lit(3), lit(4), lit(bad))), String.valueOf(bad));
        }
        for (int[] bad : new int[][] {{3, 60}, {25, 0}, {-1, 0}, {24, 1}}) {
            assertThrows(ConversionException.class, () -> eval(call("make_timestamp", lit(2020), lit(1), lit(2),
                    lit(bad[0]), lit(bad[1]), lit(5))));
        }
        assertEquals("294247-01-10 04:00:54.775806",
                text(eval(call("make_timestamp", lit(Long.MAX_VALUE - 1))), DataType.TIMESTAMP));
        assertThrows(ConversionException.class, () -> eval(call("make_timestamp", lit(Long.MAX_VALUE))));
        assertThrows(ConversionException.class, () -> eval(call("make_timestamp", lit(-Long.MAX_VALUE))));
    }

    @Test
    void strftime() {
        String all = "%Y-%m-%d %H:%M:%S.%f %j %a %A %b %B %p %I %% %y %-m %-d %-H %-M %-S %-j %-I";
        assertEquals("2024-02-03 00:00:00.000000 034 Sat Saturday Feb February AM 12 % 24 2 3 0 0 0 34 12",
                eval(call("strftime", date("2024-02-03"), lit(all))));
        assertEquals("2024-02-03 00:05:06.000789 034 Sat Saturday Feb February AM 12 % 24 2 3 0 5 6 34 12",
                eval(call("strftime", timestamp("2024-02-03 00:05:06.000789"), lit(all))));
        assertEquals("PM 01 1 366 24 24",
                eval(call("strftime", timestamp("2024-12-31 13:05:06.5"), lit("%p %I %-I %j %-y %y"))));
        assertEquals("0005|05|5", eval(call("strftime", date("0005-02-03"), lit("%Y|%y|%-y"))));
        assertEquals("-1|01|1", eval(call("strftime", date("0002-11-28 (BC)"), lit("%Y|%y|%-y"))));
        assertEquals("-150|50|50", eval(call("strftime", date("-0150-03-01"), lit("%Y|%y|%-y"))));
        assertEquals("0000|00", eval(call("strftime", date("0000-03-01"), lit("%Y|%y"))));
        assertEquals("12345|45", eval(call("strftime", date("12345-01-01"), lit("%Y|%y"))));
        assertEquals("2024", eval(call("strftime", lit("%Y"), date("2024-02-03"))));
        assertNull(eval(call("strftime", date("2024-02-03"), Expressions.nullOf(DataType.VARCHAR))));
        for (String bad : List.of("%Q", "abc%", "%-", "", "%e", "%-Y", "%-f")) {
            assertThrows(IllegalArgumentException.class, () -> eval(call("strftime", date("2024-02-03"), lit(bad))),
                    bad);
        }
    }

    @Test
    void strptime() {
        Map<List<String>, String> expected = Map.ofEntries(
                Map.entry(List.of("2024-02-03 04:05:06.123456", "%Y-%m-%d %H:%M:%S.%f"), "2024-02-03 04:05:06.123456"),
                Map.entry(List.of("2024-2-3", "%Y-%m-%d"), "2024-02-03 00:00:00"),
                Map.entry(List.of("2024-02-03", "%Y-%-m-%-d"), "2024-02-03 00:00:00"),
                Map.entry(List.of("69-02-03", "%y-%m-%d"), "1969-02-03 00:00:00"),
                Map.entry(List.of("68-02-03", "%y-%m-%d"), "2068-02-03 00:00:00"),
                Map.entry(List.of(" 2024-02-03 ", "%Y-%m-%d"), "2024-02-03 00:00:00"),
                Map.entry(List.of("2024-02-03  04", "%Y-%m-%d %H"), "2024-02-03 04:00:00"),
                Map.entry(List.of("2024-02-03\t04", "%Y-%m-%d %H"), "2024-02-03 04:00:00"),
                Map.entry(List.of("2024 034", "%Y %j"), "2024-02-03 00:00:00"),
                Map.entry(List.of("034", "%j"), "1900-02-03 00:00:00"),
                Map.entry(List.of("2023 366", "%Y %j"), "2024-01-01 00:00:00"),
                Map.entry(List.of("2024-02-03 366", "%Y-%m-%d %j"), "2024-02-03 00:00:00"),
                Map.entry(List.of("02", "%m"), "1900-02-01 00:00:00"),
                Map.entry(List.of("Sat Feb 2024 03 PM 04", "%a %b %Y %d %p %I"), "2024-02-03 16:00:00"),
                Map.entry(List.of("saturday february 2024 03 am 12", "%A %B %Y %d %p %I"), "2024-02-03 00:00:00"),
                Map.entry(List.of("Mon 2024-02-03", "%a %Y-%m-%d"), "2024-02-03 00:00:00"),
                Map.entry(List.of("7pm", "%I%p"), "1900-01-01 19:00:00"),
                Map.entry(List.of("0 PM", "%H %p"), "1900-01-01 12:00:00"),
                Map.entry(List.of("12 AM", "%H %p"), "1900-01-01 00:00:00"),
                Map.entry(List.of("2024-02-03 04:05:06.1", "%Y-%m-%d %H:%M:%S.%f"), "2024-02-03 04:05:06.1"),
                Map.entry(List.of("2024-02-03 100%", "%Y-%m-%d 100%%"), "2024-02-03 00:00:00"),
                Map.entry(List.of("20240203", "%Y%m%d"), "2024-02-03 00:00:00"),
                Map.entry(List.of("00240203", "%Y%m%d"), "0024-02-03 00:00:00"),
                Map.entry(List.of("9-02-03", "%Y-%m-%d"), "0009-02-03 00:00:00"),
                Map.entry(List.of("5", "%y"), "2005-01-01 00:00:00"));
        expected.forEach((args, value) -> assertEquals(value,
                text(eval(call("strptime", lit(args.get(0)), lit(args.get(1)))), DataType.TIMESTAMP), args.toString()));
        List<List<String>> bad = List.of(List.of("2024-02-03", "%Y-%m-%d "), List.of("2024-02-03", "%Y %m %d"),
                List.of("2024-02-30", "%Y-%m-%d"), List.of("12345-02-03", "%Y-%m-%d"),
                List.of("-12-02-03", "%Y-%m-%d"), List.of("2024-02-03 13", "%Y-%m-%d %I"),
                List.of("2024-02-03 13 PM", "%Y-%m-%d %H %p"), List.of("2024-02-03 04:05:06.1234567",
                        "%Y-%m-%d %H:%M:%S.%f"), List.of("2024-02-03 04:05:60", "%Y-%m-%d %H:%M:%S"),
                List.of("2024-02-03 24:00:00", "%Y-%m-%d %H:%M:%S"), List.of("2024-02-03X", "%Y-%m-%dx"),
                List.of("2024203", "%Y%m%d"), List.of("Saturday", "%a"), List.of("Sat", "%A"),
                List.of("feb", "%B"), List.of("100", "%y"), List.of("2024 000", "%Y %j"), List.of("", "%Y"),
                List.of("2024-02-03 extra", "%Y-%m-%d"), List.of("2024-02-03 0 AM", "%Y-%m-%d %I %p"));
        for (List<String> args : bad) {
            assertThrows(ConversionException.class, () -> eval(call("strptime", lit(args.get(0)), lit(args.get(1)))),
                    args.toString());
            assertNull(eval(call("try_strptime", lit(args.get(0)), lit(args.get(1)))), args.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> eval(call("try_strptime", lit("2024"), lit("%Q"))));
    }
}
