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

import java.math.BigInteger;
import java.time.Duration;
import java.time.Period;

/**
 * A value of the type {@code INTERVAL}, as in DuckDB 1.5.6: a number of
 * months, a number of days and a number of microseconds, each with its own
 * sign, so that {@code 1 month -30 days} is a value of its own.
 * <p>
 * Arithmetic works component by component. Equality, order and the hash
 * code use the normalized form that DuckDB compares: the whole months in the
 * microseconds and the days, at 30 days per month, move to the months, then
 * the whole days in the remaining microseconds to the days, each division
 * rounding towards zero. So {@code INTERVAL '1 month'} equals
 * {@code INTERVAL '30 days'} and {@code INTERVAL '720 hours'}, while
 * {@code INTERVAL '1 day -1 hour'} does not equal {@code INTERVAL '23 hours'}.
 * <p>
 * The text form is DuckDB's: {@code 1 year 2 months -3 days 04:05:06.5},
 * {@code 00:00:00} for zero; {@link #parse} reads it and the other forms of
 * DuckDB, without ISO 8601.
 * <p>
 * Part of the SPI for connectors; covered by the compatibility promise.
 */
public final class Interval implements Comparable<Interval> {

    /** The days of a month in the normalization and in conversions. */
    public static final int DAYS_PER_MONTH = 30;

    /** The microseconds of a day. */
    public static final long MICROS_PER_DAY = 86_400_000_000L;

    /** The microseconds of a month of 30 days. */
    public static final long MICROS_PER_MONTH = DAYS_PER_MONTH * MICROS_PER_DAY;

    private static final long MICROS_PER_HOUR = 3_600_000_000L;
    private static final long MICROS_PER_MINUTE = 60_000_000L;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long SECONDS_PER_DAY = 86_400L;

    /** The interval of length zero. */
    public static final Interval ZERO = new Interval(0, 0, 0);

    private final int months;
    private final int days;
    private final long micros;

    private Interval(int months, int days, long micros) {
        this.months = months;
        this.days = days;
        this.micros = micros;
    }

    /** Returns the interval of the given months, days and microseconds. */
    public static Interval of(int months, int days, long micros) {
        return months == 0 && days == 0 && micros == 0 ? ZERO : new Interval(months, days, micros);
    }

    /** Returns the interval of a number of months. */
    public static Interval ofMonths(int months) {
        return of(months, 0, 0);
    }

    /** Returns the interval of a number of days. */
    public static Interval ofDays(int days) {
        return of(0, days, 0);
    }

    /** Returns the interval of a number of microseconds. */
    public static Interval ofMicros(long micros) {
        return of(0, 0, micros);
    }

    /**
     * Returns the interval of a duration, as microseconds; nanoseconds below
     * a microsecond are cut off.
     *
     * @throws ArithmeticException if the duration does not fit 64 bits of microseconds
     */
    public static Interval from(Duration duration) {
        return ofMicros(Math.addExact(Math.multiplyExact(duration.getSeconds(), MICROS_PER_SECOND),
                duration.getNano() / 1000));
    }

    /**
     * Returns the interval of a period: its years and months as months, its
     * days as days.
     *
     * @throws ArithmeticException if the months do not fit 32 bits
     */
    public static Interval from(Period period) {
        return of(Math.toIntExact(period.toTotalMonths()), period.getDays(), 0);
    }

    /** Returns the months. */
    public int months() {
        return months;
    }

    /** Returns the days. */
    public int days() {
        return days;
    }

    /** Returns the microseconds. */
    public long micros() {
        return micros;
    }

    /**
     * Returns the interval as a duration of days and microseconds.
     *
     * @throws ArithmeticException if the interval has months, whose length depends on the date
     */
    public Duration toDuration() {
        if (months != 0) {
            throw new ArithmeticException("An INTERVAL with months is no Duration: " + this);
        }
        return Duration.ofDays(days).plus(Duration.of(micros, java.time.temporal.ChronoUnit.MICROS));
    }

    /**
     * Returns the interval as a period of months and days.
     *
     * @throws ArithmeticException if the interval has microseconds
     */
    public Period toPeriod() {
        if (micros != 0) {
            throw new ArithmeticException("An INTERVAL with a time of day is no Period: " + this);
        }
        return Period.of(0, months, days);
    }

    /** Returns the months of the normalized form of an interval. */
    public static long normalizedMonths(int months, int days, long micros) {
        return (long) months + days / DAYS_PER_MONTH + micros / MICROS_PER_MONTH;
    }

    /** Returns the days of the normalized form of an interval. */
    public static long normalizedDays(int days, long micros) {
        return days % DAYS_PER_MONTH + micros % MICROS_PER_MONTH / MICROS_PER_DAY;
    }

    /** Returns the microseconds of the normalized form of an interval. */
    public static long normalizedMicros(long micros) {
        return micros % MICROS_PER_DAY;
    }

    /** Compares two intervals given by their components, by their normalized forms. */
    public static int compare(int monthsA, int daysA, long microsA, int monthsB, int daysB, long microsB) {
        int c = Long.compare(normalizedMonths(monthsA, daysA, microsA), normalizedMonths(monthsB, daysB, microsB));
        if (c == 0) {
            c = Long.compare(normalizedDays(daysA, microsA), normalizedDays(daysB, microsB));
        }
        return c != 0 ? c : Long.compare(normalizedMicros(microsA), normalizedMicros(microsB));
    }

    /** Returns the hash of an interval given by its components, over its normalized form. */
    public static int hash(int months, int days, long micros) {
        int h = Long.hashCode(normalizedMonths(months, days, micros));
        h = 31 * h + Long.hashCode(normalizedDays(days, micros));
        return 31 * h + Long.hashCode(normalizedMicros(micros));
    }

    @Override
    public int compareTo(Interval other) {
        return compare(months, days, micros, other.months, other.days, other.micros);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Interval other && compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return hash(months, days, micros);
    }

    /**
     * Adds another interval, component by component.
     *
     * @throws ArithmeticException if a component overflows
     */
    public Interval plus(Interval other) {
        return of(add(months, other.months), add(days, other.days), add(micros, other.micros));
    }

    /**
     * Subtracts another interval, component by component.
     *
     * @throws ArithmeticException if a component overflows
     */
    public Interval minus(Interval other) {
        return of(subtract(months, other.months), subtract(days, other.days), subtract(micros, other.micros));
    }

    /**
     * Negates every component.
     *
     * @throws ArithmeticException if a component is the smallest of its type
     */
    public Interval negate() {
        if (months == Integer.MIN_VALUE || days == Integer.MIN_VALUE || micros == Long.MIN_VALUE) {
            throw new ArithmeticException("Overflow in negation of numeric value!");
        }
        return of(-months, -days, -micros);
    }

    /**
     * Multiplies every component by an integer.
     *
     * @throws ArithmeticException if a component overflows
     */
    public Interval times(long factor) {
        return of(multiply(months, factor), multiply(days, factor), multiply(micros, factor));
    }

    /**
     * Multiplies by a number with a fraction, as DuckDB and PostgreSQL do.
     * The months and the days are multiplied and cut toward zero; the
     * fraction of the months goes to days at 30 days per month, rounded to
     * millionths of a day, and its whole days to the days; what is left of a
     * day, from both, goes to seconds, rounded to microseconds, and whole
     * days of it to the days; the microseconds are multiplied and take these
     * seconds, rounded to the nearest, halves to the even neighbor.
     *
     * @throws ArithmeticException if the result does not fit, or the factor is not finite
     */
    public Interval times(double factor) {
        return multiply(factor, "Overflow in multiplication of INTERVAL.");
    }

    /**
     * Divides by a number as DuckDB does: {@link #times(double)} with the
     * reciprocal of the divisor.
     *
     * @throws ArithmeticException if the divisor is zero, or the result does not fit
     */
    public Interval dividedBy(double divisor) {
        if (divisor == 0) {
            throw new ArithmeticException("Division of an INTERVAL by zero");
        }
        return multiply(1.0 / divisor, "Overflow in INTERVAL division");
    }

    /**
     * Returns the mean of intervals, given by the sums of their components
     * and their number, as DuckDB and PostgreSQL compute it: each sum is
     * divided by the number, cut toward zero, and the remainders move on to
     * the next smaller component, those of the months at 30 days per month
     * and those of the days at 24 hours per day; the remainder of the
     * microseconds is added as it is.
     *
     * @throws ArithmeticException if the result does not fit
     */
    public static Interval mean(long months, long days, BigInteger micros, long count) {
        BigInteger n = BigInteger.valueOf(count);
        BigInteger[] u = micros.divideAndRemainder(n);
        long monthRemainder = Math.multiplyExact(months % count, (long) DAYS_PER_MONTH);
        long resultDays = Math.addExact(days / count, monthRemainder / count);
        long dayRemainder = days % count + monthRemainder % count;
        BigInteger microRemainder = u[1].add(BigInteger.valueOf(dayRemainder)
                .multiply(BigInteger.valueOf(MICROS_PER_DAY)).divide(n));
        BigInteger resultMicros = u[0].add(microRemainder);
        if (resultMicros.bitLength() > 63) {
            throw new ArithmeticException("Overflow in the average of INTERVAL values");
        }
        return of(Math.toIntExact(months / count), Math.toIntExact(resultDays), resultMicros.longValue());
    }

    /** {@link #times(double)}, in the order of operations of DuckDB, which decides the last microsecond. */
    private Interval multiply(double f, String overflow) {
        double m = months * f;
        if (!(m >= Integer.MIN_VALUE && m <= Integer.MAX_VALUE)) {
            throw new ArithmeticException(overflow);
        }
        int resultMonths = (int) m;
        double d = days * f;
        if (!(d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE)) {
            throw new ArithmeticException(overflow);
        }
        int resultDays = (int) d;
        double monthRemainder = toMicros((months * f - resultMonths) * DAYS_PER_MONTH);
        int dayRemainder = (int) monthRemainder;
        double secondRemainder = toMicros((days * f - resultDays + monthRemainder - dayRemainder) * SECONDS_PER_DAY);
        try {
            if (Math.abs(secondRemainder) >= SECONDS_PER_DAY) {
                int extraDays = (int) (secondRemainder / SECONDS_PER_DAY);
                resultDays = Math.addExact(resultDays, extraDays);
                secondRemainder -= extraDays * SECONDS_PER_DAY;
            }
            resultDays = Math.addExact(resultDays, dayRemainder);
        } catch (ArithmeticException e) {
            throw new ArithmeticException(overflow);
        }
        double u = Math.rint((double) micros * f + secondRemainder * MICROS_PER_SECOND);
        if (!(u >= -0x1p63 && u < 0x1p63)) {
            throw new ArithmeticException(overflow);
        }
        return of(resultMonths, resultDays, (long) u);
    }

    /** Rounds a number of a unit to millionths of it, halves to the even neighbor. */
    private static double toMicros(double x) {
        return Math.rint(x * MICROS_PER_SECOND) / MICROS_PER_SECOND;
    }

    /**
     * Returns the microseconds of the interval as {@code epoch_us} counts
     * them, every month as 30 days.
     *
     * @throws ArithmeticException if they do not fit 64 bits
     */
    public long epochMicros() {
        return Math.addExact(Math.addExact(Math.multiplyExact((long) months, MICROS_PER_MONTH),
                Math.multiplyExact((long) days, MICROS_PER_DAY)), micros);
    }

    /**
     * Returns the seconds of the interval as {@code epoch} counts them: a year
     * of twelve months as 365.25 days, the remaining months as 30 days each.
     */
    public double epochSeconds() {
        long years = months / 12;
        long rest = months % 12;
        double seconds = years * 365.25 * 86_400 + rest * (DAYS_PER_MONTH * 86_400L) + days * 86_400.0;
        return seconds + micros / (double) MICROS_PER_SECOND;
    }

    private static int add(int a, int b) {
        int r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            throw new ArithmeticException("Overflow in addition of INT32 (" + a + " + " + b + ")!");
        }
        return r;
    }

    private static long add(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            throw new ArithmeticException("Overflow in addition of INT64 (" + a + " + " + b + ")!");
        }
        return r;
    }

    private static int subtract(int a, int b) {
        int r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            throw new ArithmeticException("Overflow in subtraction of INT32 (" + a + " - " + b + ")!");
        }
        return r;
    }

    private static long subtract(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            throw new ArithmeticException("Overflow in subtraction of INT64 (" + a + " - " + b + ")!");
        }
        return r;
    }

    private static int multiply(int a, long b) {
        long r = (long) a * b;
        if (b != (int) b && a != 0 || r != (int) r) {
            throw new ArithmeticException("Overflow in multiplication of INT32 (" + a + " * " + b + ")!");
        }
        return (int) r;
    }

    private static long multiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long r = a * b;
        if (high != (r >> 63)) {
            throw new ArithmeticException("Overflow in multiplication of INT64 (" + a + " * " + b + ")!");
        }
        return r;
    }

    /**
     * Returns the text form of DuckDB: the years and the remaining months,
     * the days, each with its unit in the singular for one and minus one,
     * then the time of day as {@code [-]HH:MM:SS[.ffffff]} without trailing
     * zeros of the fraction, if it is not zero, or {@code 00:00:00} for the
     * interval of length zero.
     */
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        int years = months / 12;
        int rest = months % 12;
        unit(b, years, "year");
        unit(b, rest, "month");
        unit(b, days, "day");
        if (micros != 0 || b.isEmpty()) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            if (micros < 0) {
                b.append('-');
            }
            long hours = Math.abs(micros / MICROS_PER_HOUR);
            long rem = Math.abs(micros % MICROS_PER_HOUR);
            if (hours < 10) {
                b.append('0');
            }
            b.append(hours).append(':');
            two(b, rem / MICROS_PER_MINUTE).append(':');
            two(b, rem % MICROS_PER_MINUTE / MICROS_PER_SECOND);
            fraction(b, rem % MICROS_PER_SECOND);
        }
        return b.toString();
    }

    private static void unit(StringBuilder b, int value, String name) {
        if (value == 0) {
            return;
        }
        if (!b.isEmpty()) {
            b.append(' ');
        }
        b.append(value).append(' ').append(name);
        if (value != 1 && value != -1) {
            b.append('s');
        }
    }

    private static StringBuilder two(StringBuilder b, long value) {
        if (value < 10) {
            b.append('0');
        }
        return b.append(value);
    }

    /** Appends the microseconds of a second as a fraction without trailing zeros, nothing for zero. */
    static void fraction(StringBuilder b, long micros) {
        if (micros == 0) {
            return;
        }
        String digits = Long.toString(1_000_000 + micros).substring(1);
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') {
            end--;
        }
        b.append('.').append(digits, 0, end);
    }

    /**
     * Parses an interval as DuckDB reads one: surrounding whitespace, an
     * optional {@code @}, then pairs of a number and a unit and times of day
     * {@code [-]H:M[:S[.f]]}, and an optional {@code ago} at the end that
     * negates the whole. A number may have a minus sign and a fraction, of
     * which six digits count; a fraction of a year, decade, century,
     * millennium or quarter goes to whole months, of a month to whole days,
     * of a week to days and microseconds, and of the smaller units to
     * microseconds. The units are the names of the parts of a date that
     * {@link Dates.Part} lists, without regard to case. A single number
     * without a unit is a number of seconds.
     *
     * @throws ConversionException if the string is no interval
     * @throws ArithmeticException if a component overflows
     */
    public static Interval parse(String s) {
        Parser p = new Parser(s);
        return p.parse();
    }

    private static final class Parser {
        private final String s;
        private final String t;
        private int p;
        private long months;
        private long days;
        private long micros;

        Parser(String s) {
            this.s = s;
            this.t = s.strip();
        }

        Interval parse() {
            int n = t.length();
            if (p < n && t.charAt(p) == '@') {
                p++;
            }
            boolean any = false;
            while (true) {
                skipSpaces();
                if (p == n) {
                    break;
                }
                if (any && t.regionMatches(true, p, "ago", 0, 3) && p + 3 == n) {
                    months = -months;
                    days = -days;
                    micros = -micros;
                    p = n;
                    break;
                }
                boolean negative = t.charAt(p) == '-';
                if (negative) {
                    p++;
                }
                int start = p;
                p = digits(p);
                if (p == start) {
                    throw invalid();
                }
                if (p < n && t.charAt(p) == ':') {
                    time(start, negative);
                    any = true;
                    continue;
                }
                long whole = number(start, p);
                long fraction = 0;
                if (p < n && t.charAt(p) == '.') {
                    int from = ++p;
                    p = digits(p);
                    fraction = sixDigits(from, p);
                }
                skipSpaces();
                int unitStart = p;
                while (p < n && Character.isLetter(t.charAt(p))) {
                    p++;
                }
                String unit = t.substring(unitStart, p);
                if (unit.isEmpty()) {
                    if (p != n) {
                        throw invalid();
                    }
                    if (any) {
                        throw new ConversionException("extract specifier \"\" not recognized");
                    }
                    unit = "second";
                }
                apply(Dates.Part.of(unit), whole, fraction, negative);
                any = true;
            }
            if (!any) {
                throw invalid();
            }
            return of(toInt(months), toInt(days), micros);
        }

        /** Reads a time of day from the digits of the hours on, with the sign before them. */
        private void time(int hoursStart, boolean negative) {
            int n = t.length();
            long hours = number(hoursStart, p);
            p++;
            int start = p;
            p = digits(p);
            if (p == start) {
                throw invalid();
            }
            long minutes = number(start, p);
            long seconds = 0;
            long fraction = 0;
            if (p < n && t.charAt(p) == ':') {
                start = ++p;
                p = digits(p);
                if (p == start) {
                    throw invalid();
                }
                seconds = number(start, p);
                if (p < n && t.charAt(p) == '.') {
                    start = ++p;
                    p = digits(p);
                    fraction = sixDigits(start, p);
                }
            }
            if (minutes >= 60 || seconds >= 60) {
                throw invalid();
            }
            long value;
            long rest = minutes * MICROS_PER_MINUTE + seconds * MICROS_PER_SECOND + fraction;
            try {
                // With its sign from the start, so that the least value of 64 bits is read too.
                value = negative ? Math.subtractExact(Math.multiplyExact(-hours, MICROS_PER_HOUR), rest)
                        : Math.addExact(Math.multiplyExact(hours, MICROS_PER_HOUR), rest);
            } catch (ArithmeticException e) {
                throw outOfRange();
            }
            micros = Math.addExact(micros, value);
        }

        /**
         * Adds a number of a unit, given as its whole part and its fraction in
         * millionths, both without the sign.
         */
        private void apply(Dates.Part part, long whole, long fraction, boolean negative) {
            long m = 0;
            long d = 0;
            long u = 0;
            try {
                switch (part) {
                    case MILLENNIUM -> m = months(whole, fraction, 12_000);
                    case CENTURY -> m = months(whole, fraction, 1_200);
                    case DECADE -> m = months(whole, fraction, 120);
                    case YEAR -> m = months(whole, fraction, 12);
                    case QUARTER -> m = months(whole, fraction, 3);
                    case MONTH -> {
                        m = whole;
                        d = fraction * DAYS_PER_MONTH / 1_000_000;
                    }
                    case WEEK -> {
                        long sevenths = fraction * 7;
                        d = Math.addExact(Math.multiplyExact(whole, 7), sevenths / 1_000_000);
                        u = sevenths % 1_000_000 * 86_400;
                    }
                    case DAY -> {
                        d = whole;
                        u = fraction * 86_400;
                    }
                    case HOUR -> u = micros(whole, MICROS_PER_HOUR, fraction * 3_600);
                    case MINUTE -> u = micros(whole, MICROS_PER_MINUTE, fraction * 60);
                    case SECOND -> u = micros(whole, MICROS_PER_SECOND, fraction);
                    case MILLISECOND -> u = micros(whole, 1_000, Math.round(fraction / 1_000.0));
                    case MICROSECOND -> u = micros(whole, 1, Math.round(fraction / 1_000_000.0));
                    default -> throw invalid();
                }
                months = negative ? Math.subtractExact(months, m) : Math.addExact(months, m);
                days = negative ? Math.subtractExact(days, d) : Math.addExact(days, d);
                micros = negative ? Math.subtractExact(micros, u) : Math.addExact(micros, u);
            } catch (ArithmeticException e) {
                throw outOfRange();
            }
        }

        private static long months(long whole, long fraction, int monthsPerUnit) {
            return Math.addExact(Math.multiplyExact(whole, monthsPerUnit), fraction * monthsPerUnit / 1_000_000);
        }

        private static long micros(long whole, long microsPerUnit, long fractionMicros) {
            return Math.addExact(Math.multiplyExact(whole, microsPerUnit), fractionMicros);
        }

        private int toInt(long value) {
            if (value != (int) value) {
                throw new ArithmeticException("Type INT64 with value " + value
                        + " can't be cast because the value is out of range for the destination type INT32");
            }
            return (int) value;
        }

        private long number(int from, int to) {
            try {
                return Long.parseLong(t, from, to, 10);
            } catch (NumberFormatException e) {
                throw outOfRange();
            }
        }

        /** The first six digits of a fraction as millionths, the digits after them cut off. */
        private long sixDigits(int from, int to) {
            long value = 0;
            for (int i = 0; i < 6; i++) {
                value = value * 10 + (from + i < to ? t.charAt(from + i) - '0' : 0);
            }
            return value;
        }

        private int digits(int from) {
            int i = from;
            while (i < t.length() && t.charAt(i) >= '0' && t.charAt(i) <= '9') {
                i++;
            }
            return i;
        }

        private void skipSpaces() {
            while (p < t.length() && Character.isWhitespace(t.charAt(p))) {
                p++;
            }
        }

        private ConversionException invalid() {
            return new ConversionException("Could not convert string '" + s + "' to INTERVAL");
        }

        private ArithmeticException outOfRange() {
            return new ArithmeticException("Interval value out of range: '" + s + "'");
        }
    }
}
