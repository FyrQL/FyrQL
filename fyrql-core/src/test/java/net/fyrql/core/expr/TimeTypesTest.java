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

import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.function.ZonedOperators;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.TypeRules;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.sub;
import static net.fyrql.core.expr.ThreeValuedLogicTest.eval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The arithmetic and the functions of {@code TIME}, {@code TIMESTAMP WITH
 * TIME ZONE} and {@code INTERVAL}, with the values that DuckDB 1.5.6 gives.
 */
class TimeTypesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Expression time(String s) {
        return lit(DataType.TIME, Conversions.parseTime(s));
    }

    private static Expression date(String s) {
        return lit(DataType.DATE, Conversions.parseDate(s));
    }

    private static Expression timestamp(String s) {
        return lit(DataType.TIMESTAMP, Conversions.parseTimestamp(s));
    }

    private static Expression tz(String s) {
        return lit(DataType.TIMESTAMPTZ, Conversions.parseTimestampTz(s, BERLIN));
    }

    private static Expression interval(String s) {
        return lit(DataType.INTERVAL, Interval.parse(s));
    }

    private static String text(Expression e) {
        return Casts.format(eval(e), e.type(), BERLIN);
    }

    /** {@code l + r} or {@code l - r} with an instant, in Berlin. */
    private static Expression zoned(String op, Expression l, Expression r) {
        ScalarFunction f = op.equals("+") ? ZonedOperators.add(l.type(), r.type())
                : ZonedOperators.subtract(l.type(), r.type());
        return new Expression.Call(f.inZone(BERLIN), List.of(l, r));
    }

    private static Expression mul(Expression l, Expression r) {
        return new Expression.Arithmetic(Expression.ArithmeticOp.MULTIPLY, l, r);
    }

    private static Expression div(Expression l, Expression r) {
        return new Expression.Arithmetic(Expression.ArithmeticOp.DIVIDE, l, r);
    }

    @Test
    void resultTypes() {
        assertEquals(DataType.TIMESTAMP, TypeRules.add(DataType.DATE, DataType.INTERVAL));
        assertEquals(DataType.TIMESTAMP, TypeRules.add(DataType.INTERVAL, DataType.TIMESTAMP));
        assertEquals(DataType.TIME, TypeRules.add(DataType.TIME, DataType.INTERVAL));
        assertEquals(DataType.TIME, TypeRules.add(DataType.INTERVAL, DataType.TIME));
        assertEquals(DataType.TIMESTAMP, TypeRules.add(DataType.TIME, DataType.DATE));
        assertEquals(DataType.TIMESTAMP, TypeRules.add(DataType.DATE, DataType.TIME));
        assertEquals(DataType.INTERVAL, TypeRules.add(DataType.INTERVAL, DataType.INTERVAL));
        assertEquals(DataType.INTERVAL, TypeRules.subtract(DataType.TIMESTAMP, DataType.TIMESTAMP));
        assertEquals(DataType.INTERVAL, TypeRules.subtract(DataType.DATE, DataType.TIMESTAMP));
        assertEquals(DataType.BIGINT, TypeRules.subtract(DataType.DATE, DataType.DATE));
        assertEquals(DataType.TIME, TypeRules.subtract(DataType.TIME, DataType.INTERVAL));
        assertEquals(DataType.INTERVAL, TypeRules.multiply(DataType.INTEGER, DataType.INTERVAL));
        assertEquals(DataType.INTERVAL, TypeRules.multiply(DataType.INTERVAL, DataType.decimal(3, 1)));
        assertEquals(DataType.INTERVAL, TypeRules.divide(DataType.INTERVAL, DataType.BIGINT));
        assertEquals(DataType.TIMESTAMPTZ, TypeRules.common(DataType.DATE, DataType.TIMESTAMPTZ));
        assertEquals(DataType.TIMESTAMPTZ, TypeRules.common(DataType.TIMESTAMPTZ, DataType.TIMESTAMP));
        for (DataType[] pair : new DataType[][] {{DataType.TIMESTAMP, DataType.TIME}, {DataType.TIME, DataType.TIME},
            {DataType.TIMESTAMPTZ, DataType.INTERVAL}, {DataType.INTERVAL, DataType.TIMESTAMPTZ}}) {
            assertThrows(IllegalArgumentException.class, () -> TypeRules.add(pair[0], pair[1]));
        }
        assertThrows(IllegalArgumentException.class, () -> TypeRules.subtract(DataType.TIME, DataType.TIME));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.subtract(DataType.INTERVAL, DataType.DATE));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.common(DataType.TIME, DataType.TIMESTAMP));
        assertThrows(IllegalArgumentException.class, () -> TypeRules.common(DataType.INTERVAL, DataType.INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> TypeRules.intDivide(DataType.INTERVAL, DataType.INTEGER));
    }

    @Test
    void arithmetic() {
        assertEquals("2024-02-29 00:00:00", text(add(date("2024-01-31"), interval("1 month"))));
        assertEquals("2024-02-29 00:00:00", text(sub(date("2024-03-31"), interval("1 month"))));
        assertEquals("2023-12-30 23:00:00", text(sub(date("2024-01-31"), interval("1 month 1 hour"))));
        assertEquals("2024-02-01 00:00:00", text(add(interval("1 day"), date("2024-01-31"))));
        assertEquals("2024-03-01 11:00:00",
                text(add(timestamp("2024-01-31 10:00:00"), interval("1 month 1 day 1 hour"))));
        assertEquals("2023-12-30 12:00:00", text(add(timestamp("2024-01-31 12:00:00"), interval("-1 month -1 day"))));
        assertEquals("2025-02-28 12:00:00", text(add(timestamp("2024-02-29 12:00:00"), interval("1 year"))));
        assertEquals("2024-03-30 12:00:00", text(add(timestamp("2024-01-31 12:00:00"), interval("1 month 30 days"))));
        assertEquals("731 days", text(sub(timestamp("2026-01-01 00:00:00"), timestamp("2024-01-01 00:00:00"))));
        assertEquals("-1 day -03:00:00", text(sub(timestamp("2024-01-01 00:00:00"), timestamp("2024-01-02 03:00:00"))));
        assertEquals("30 days", text(sub(timestamp("2024-03-01 00:00:00"), timestamp("2024-01-31 00:00:00"))));
        assertEquals("18:00:00", text(sub(date("2024-07-01"), timestamp("2024-06-30 06:00:00"))));
        assertEquals("1 day", text(sub(timestamp("2024-07-01 00:00:00"), date("2024-06-30"))));
        assertEquals(60L, eval(sub(date("2024-03-01"), date("2024-01-01"))));
        assertEquals("01:00:00", text(add(time("23:00:00"), interval("2 hours"))));
        assertEquals("13:00:00", text(add(time("12:00:00"), interval("1 month 1 day 1 hour"))));
        assertEquals("23:00:00", text(sub(time("01:00:00"), interval("2 hours"))));
        assertEquals("11:00:00", text(add(time("12:00:00"), interval("-25 hours"))));
        assertEquals("11:00:00", text(sub(time("12:00:00"), interval("-1 day 1 hour"))));
        assertEquals("00:30:00", text(add(interval("1 hour"), time("23:30:00"))));
        assertEquals("00:00:00", text(add(time("24:00:00"), interval("0 hours"))));
        assertEquals("2024-01-01 12:00:00", text(add(time("12:00:00"), date("2024-01-01"))));
        assertEquals("2024-01-01 12:00:00", text(add(date("2024-01-01"), time("12:00:00"))));
        assertEquals("1 month -30 days", text(sub(interval("1 month 1 day"), interval("31 days"))));
        assertEquals("2 days", text(mul(interval("1 day"), lit(2))));
        assertEquals("2 days", text(mul(lit(2L), interval("1 day"))));
        assertEquals("4 months", text(div(interval("1 year"), lit(3))));
        assertEquals("15 days 12:00:00.5", text(mul(interval("1 month 1 day 1 second"), lit(0.5))));
        assertEquals("1 day 12:00:00",
                text(mul(interval("1 day"), lit(DataType.decimal(2, 1), new java.math.BigDecimal("1.5")))));
        assertEquals("2 days 12:00:00", text(div(interval("10 days"), lit(4))));
        assertNull(eval(div(interval("1 day"), lit(0))));
        assertNull(eval(div(interval("1 day"), lit(0.0))));
        assertThrows(ArithmeticException.class, () -> eval(mul(interval("1 day"), lit(Double.NaN))));
        assertThrows(ArithmeticException.class, () -> eval(mul(interval("2000000000 months"), lit(2))));
        assertEquals("-1 day -02:00:00", text(new Expression.Negate(interval("1 day 02:00:00"))));
    }

    @Test
    void parts() {
        assertEquals(56_789_000L, eval(call("date_part", lit("microsecond"), time("12:34:56.789"))));
        assertEquals(12L, eval(call("hour", time("12:34:56"))));
        assertThrows(ConversionException.class, () -> eval(call("date_part", lit("year"), time("12:34:56"))));
        assertThrows(IllegalArgumentException.class, () -> call("year", time("12:34:56")));
        Expression iv = interval("1 year 14 months 40 days 25:01:02.5");
        assertEquals(2L, eval(call("date_part", lit("year"), iv)));
        assertEquals(2L, eval(call("month", iv)));
        assertEquals(40L, eval(call("day", iv)));
        assertEquals(25L, eval(call("hour", iv)));
        assertEquals(1L, eval(call("minute", iv)));
        assertEquals(2L, eval(call("second", iv)));
        assertEquals(2_500L, eval(call("millisecond", iv)));
        assertEquals(2_500_000L, eval(call("microsecond", iv)));
        assertEquals(1L, eval(call("quarter", iv)));
        assertEquals(0L, eval(call("decade", iv)));
        assertEquals(-25L, eval(call("date_part", lit("hour"), interval("-25 hours"))));
        assertEquals(30_500L, eval(call("date_part", lit("millisecond"), interval("90.5 seconds"))));
        assertEquals("interval units \"week\" not recognized", assertThrows(ConversionException.class,
                () -> eval(call("date_part", lit("week"), interval("40 days")))).getMessage());
        assertEquals(71_845_262.5, eval(call("epoch", iv)));
        assertEquals(2_592_000.0, eval(call("epoch", interval("1 month"))));
        assertEquals(1.0, eval(call("epoch", time("00:00:01"))));
        assertEquals("2 years 2 months", text(call("date_trunc", lit("month"), interval("1 year 14 months 40 days"))));
        assertEquals("2 years", text(call("date_trunc", lit("year"), interval("1 year 14 months 40 days"))));
        assertEquals("20 years", text(call("date_trunc", lit("decade"), interval("25 years"))));
        assertEquals("00:00:01", text(call("date_trunc", lit("second"), interval("1.5 seconds"))));
        assertEquals("35 days", text(call("date_trunc", lit("week"), interval("40 days"))));
        assertEquals("40 days 25:00:00", text(call("date_trunc", lit("hour"), interval("40 days 25:01:02.5"))));
        assertThrows(IllegalArgumentException.class, () -> call("date_trunc", lit("hour"), time("12:34:56")));
        assertEquals(2L, eval(call("date_diff", lit("hour"), time("01:00:00"), time("03:30:00"))));
    }

    @Test
    void timestampsWithTimeZoneInTheZone() {
        assertEquals("2024-07-01 00:00:00+02",
                text(call(BERLIN, "date_trunc", lit("day"), tz("2024-07-01 01:00:00+02"))));
        assertEquals(DataType.TIMESTAMPTZ, call("date_trunc", lit("day"), tz("2024-07-01 01:00:00+02")).type());
        assertEquals(2025L, eval(call(BERLIN, "year", tz("2024-12-31 23:30:00Z"))));
        assertEquals(2024L, eval(call("year", tz("2024-12-31 23:30:00Z"))), "in UTC without a zone");
        assertEquals(7200L, eval(call(BERLIN, "date_part", lit("timezone"), tz("2024-07-01 12:00:00Z"))));
        assertEquals(0L, eval(call(BERLIN, "date_part", lit("timezone"), timestamp("2024-07-01 12:00:00"))));
        assertEquals("+02", eval(call(BERLIN, "strftime", tz("2024-07-01 12:00:00Z"), lit("%z"))));
        assertEquals("Europe/Berlin", eval(call(BERLIN, "strftime", tz("2024-07-01 12:00:00Z"), lit("%Z"))));
        assertEquals("+00|", eval(call(BERLIN, "strftime", timestamp("2024-07-01 12:00:00"), lit("%z|%Z"))));
        Expression parsed = call(BERLIN, "strptime", lit("2024-07-01 12:00:00 +0200"), lit("%Y-%m-%d %H:%M:%S %z"));
        assertEquals(DataType.TIMESTAMPTZ, parsed.type());
        assertEquals("2024-07-01 12:00:00+02", text(parsed));
        assertEquals("2024-07-01 12:00:00+02", text(call(BERLIN, "strptime", lit("2024-07-01 12:00:00 Europe/Paris"),
                lit("%Y-%m-%d %H:%M:%S %Z"))));
        assertEquals("2024-07-01 08:30:00+02", text(call(BERLIN, "strptime", lit("2024-07-01 12:00:00 +05:30"),
                lit("%Y-%m-%d %H:%M:%S %z"))));
        assertEquals(DataType.TIMESTAMP, call("strptime", lit("2024-07-01"), lit("%Y-%m-%d")).type());
        assertEquals("2024-03-31", text(call(BERLIN, "last_day", tz("2024-02-29 23:30:00Z"))));
        assertEquals("Tuesday", eval(call(BERLIN, "dayname", tz("2024-07-01 23:30:00Z"))));
        assertEquals(1_719_835_200_000L, eval(call(BERLIN, "epoch_ms", tz("2024-07-01 12:00:00Z"))));
        assertEquals("2024-03-30 12:00:00+01",
                text(zoned("-", tz("2024-03-31 12:00:00+02"), interval("1 day"))));
        assertEquals("1 day",
                text(zoned("-", tz("2024-03-31 12:00:00+02"), tz("2024-03-30 12:00:00+01"))));
        assertEquals("2024-03-31 13:00:00+02",
                text(zoned("+", tz("2024-03-30 12:00:00+01"), interval("24 hours"))));
        assertEquals("2024-03-31 13:00:00+02",
                text(zoned("+", interval("24 hours"), tz("2024-03-30 12:00:00+01"))));
        assertEquals("2024-07-01 12:00:00", text(call("timezone", lit("UTC"), tz("2024-07-01 12:00:00Z"))));
        assertEquals("2024-07-01 18:00:00+02",
                text(call("timezone", lit("America/New_York"), timestamp("2024-07-01 12:00:00"))));
        assertThrows(ConversionException.class, () -> eval(call("timezone", lit("+05:30"),
                timestamp("2024-07-01 12:00:00"))));
        assertEquals(23L, eval(call(BERLIN, "date_diff", lit("hour"), tz("2024-03-30 12:00:00+01"),
                tz("2024-03-31 12:00:00+02"))));
        assertEquals(1L, eval(call(BERLIN, "date_diff", lit("day"), tz("2024-03-30 12:00:00+01"),
                tz("2024-03-31 11:00:00+02"))));
        Expression last = tz("2024-12-31 23:30:00Z");
        assertEquals(call(BERLIN, "year", last), call(BERLIN, "year", last), "equal calls in a zone");
    }

    @Test
    void constructors() {
        assertEquals("01:02:03.5", text(call("make_time", lit(1), lit(2), lit(3.5))));
        assertEquals("24:00:00", text(call("make_time", lit(24), lit(0), lit(0))));
        assertEquals("2024-07-01 12:00:00.5+02", text(call(BERLIN, "make_timestamptz", lit(2024), lit(7), lit(1),
                lit(12), lit(0), lit(0.5))));
        assertEquals("2024-07-01 14:00:00+02", text(call(BERLIN, "make_timestamptz", lit(2024), lit(7), lit(1),
                lit(12), lit(0), lit(0), lit("UTC"))));
        assertEquals("2024-07-01 14:00:00+02", text(call("make_timestamptz", lit(1_719_835_200_000_000L))));
        assertEquals("1970-01-01 01:00:01.5+01", text(call("to_timestamp", lit(1.5))));
        assertEquals("Epoch seconds out of range for TIMESTAMP WITH TIME ZONE", assertThrows(ConversionException.class,
                () -> eval(call("to_timestamp", lit(1e300)))).getMessage());
        assertEquals("2 years", text(call("to_years", lit(2))));
        assertEquals("1 year 2 months", text(call("to_months", lit(14))));
        assertEquals("40 days", text(call("to_days", lit(40))));
        assertEquals("25:00:00", text(call("to_hours", lit(25))));
        assertEquals("01:01:00", text(call("to_minutes", lit(61))));
        assertEquals("00:00:01.5", text(call("to_seconds", lit(1.5))));
        assertEquals("00:00:00.002", text(call("to_milliseconds", lit(2))));
        assertEquals("00:00:00.000003", text(call("to_microseconds", lit(3))));
        assertEquals("14 days", text(call("to_weeks", lit(2))));
        assertEquals("100 years", text(call("to_centuries", lit(1))));
        assertEquals("2147483648:00:00", text(call("to_hours", lit(2_147_483_648L))));
        assertEquals("2 months", text(call("age", timestamp("2024-03-01 00:00:00"), timestamp("2024-01-01 00:00:00"))));
        assertEquals("1 month 2 days", text(call("age", date("2024-03-31"), date("2024-02-29"))));
    }
}
