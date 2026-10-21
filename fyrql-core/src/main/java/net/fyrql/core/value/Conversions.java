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

import net.fyrql.core.type.DataType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conversions between strings and the other types, for {@code CAST} and
 * for the text formats. Parsing accepts surrounding whitespace, as DuckDB
 * does, and throws {@link ConversionException} on invalid input. A
 * {@code DATE} is the number of days since 1970-01-01, a {@code TIMESTAMP}
 * the number of microseconds since 1970-01-01 00:00:00, a {@code TIME} the
 * number of microseconds since midnight, a {@code TIMESTAMP WITH TIME ZONE}
 * the number of microseconds since 1970-01-01 00:00:00 UTC.
 */
public final class Conversions {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;

    private Conversions() {
    }

    /*
     * The number grammar of DuckDB, measured in 1.5.6: surrounding whitespace;
     * an optional sign; digits with single underscores between them; an
     * optional fraction and exponent, after which an integral target rounds
     * half away from zero; {@code 0x} and {@code 0b} prefixes for the
     * integral types up to BIGINT, without a sign. A decimal target takes no
     * underscores, a floating-point target no prefixes.
     */
    private static final Pattern NUMBER = Pattern.compile(
            "([+-]?)(\\d+(?:_\\d+)*)?(?:\\.(\\d+(?:_\\d+)*)?)?(?:[eE]([+-]?\\d+))?");
    private static final Pattern DECIMAL_NUMBER = Pattern.compile("([+-]?)(\\d+)?(?:\\.(\\d+)?)?(?:[eE]([+-]?\\d+))?");
    private static final Pattern HEX = Pattern.compile("0[xX]([0-9a-fA-F]+(?:_[0-9a-fA-F]+)*)");
    private static final Pattern BINARY = Pattern.compile("0[bB]([01]+(?:_[01]+)*)");

    /** Parses a {@code TINYINT}. */
    public static byte parseByte(String s) {
        return (byte) parseIntegral(s, DataType.TINYINT).longValue();
    }

    /** Parses a {@code SMALLINT}. */
    public static short parseShort(String s) {
        return (short) parseIntegral(s, DataType.SMALLINT).longValue();
    }

    /** Parses an {@code INTEGER}. */
    public static int parseInt(String s) {
        return (int) parseIntegral(s, DataType.INTEGER).longValue();
    }

    /** Parses a {@code BIGINT}. */
    public static long parseLong(String s) {
        return parseIntegral(s, DataType.BIGINT).longValue();
    }

    /** Parses a {@code HUGEINT}. */
    public static BigInteger parseHugeInt(String s) {
        return parseIntegral(s, DataType.HUGEINT);
    }

    /**
     * Parses an integral number as DuckDB does, see the number grammar
     * above.
     *
     * @throws ConversionException if the string is not a number
     * @throws ArithmeticException if the value is out of the range of the type
     */
    public static BigInteger parseIntegral(String s, DataType type) {
        String t = s.strip();
        if (type != DataType.HUGEINT) {
            Matcher hex = HEX.matcher(t);
            if (hex.matches()) {
                return checkIntegral(new BigInteger(hex.group(1).replace("_", ""), 16), s, type);
            }
            Matcher binary = BINARY.matcher(t);
            if (binary.matches()) {
                return checkIntegral(new BigInteger(binary.group(1).replace("_", ""), 2), s, type);
            }
        }
        Matcher m = NUMBER.matcher(t);
        if (!m.matches() || m.group(2) == null && m.group(3) == null) {
            throw notA(s, "an integer");
        }
        BigDecimal value = decimalOf(m, s);
        if (value == null) {
            throw outOfRange(s, type.sqlName());
        }
        return checkIntegral(value.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact(), s, type);
    }

    private static BigInteger checkIntegral(BigInteger v, String s, DataType type) {
        boolean inRange = type == DataType.HUGEINT ? net.fyrql.core.type.Values.inHugeIntRange(v)
                : v.bitLength() < 64 && v.longValue() >= net.fyrql.core.type.Values.min(type)
                        && v.longValue() <= net.fyrql.core.type.Values.max(type);
        if (!inRange) {
            throw outOfRange(s, type.sqlName());
        }
        return v;
    }

    /**
     * Builds the value of a matched number; {@code null} if the exponent is
     * so large that the value cannot fit any type. A very small value is
     * zero.
     */
    private static BigDecimal decimalOf(Matcher m, String s) {
        String digits = (m.group(2) == null ? "" : m.group(2)) + "." + (m.group(3) == null ? "" : m.group(3));
        BigDecimal mantissa = new BigDecimal(m.group(1) + digits.replace("_", ""));
        if (m.group(4) == null) {
            return mantissa;
        }
        int exponent;
        try {
            exponent = Integer.parseInt(m.group(4));
        } catch (NumberFormatException e) {
            exponent = m.group(4).startsWith("-") ? -1000 : 1000;
        }
        if (mantissa.signum() == 0) {
            return mantissa;
        }
        if (exponent > 100) {
            return null;
        }
        if (exponent < -100) {
            return BigDecimal.ZERO;
        }
        return mantissa.scaleByPowerOfTen(exponent);
    }

    /** Parses a {@code FLOAT}: like {@link #parseDouble}, rounded to single precision. */
    public static float parseFloat(String s) {
        return (float) parseDouble(s);
    }

    /**
     * Parses a {@code DOUBLE} as DuckDB does: the number grammar without
     * prefixes, and {@code nan}, {@code inf} and {@code infinity} in any
     * case with an optional sign.
     */
    public static double parseDouble(String s) {
        String t = s.strip();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        String unsigned = lower.startsWith("+") || lower.startsWith("-") ? lower.substring(1) : lower;
        boolean negative = lower.startsWith("-");
        if (unsigned.equals("nan")) {
            return Double.NaN;
        }
        if (unsigned.equals("inf") || unsigned.equals("infinity")) {
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        Matcher m = NUMBER.matcher(t);
        if (!m.matches() || m.group(2) == null && m.group(3) == null) {
            throw notA(s, "a DOUBLE");
        }
        return Double.parseDouble(t.replace("_", ""));
    }

    /**
     * Parses a decimal number with any scale: the number grammar without
     * underscores and prefixes.
     */
    public static BigDecimal parseDecimal(String s) {
        Matcher m = DECIMAL_NUMBER.matcher(s.strip());
        if (!m.matches() || m.group(2) == null && m.group(3) == null) {
            throw notA(s, "a DECIMAL");
        }
        BigDecimal value = decimalOf(m, s);
        if (value == null) {
            throw outOfRange(s, "DECIMAL");
        }
        return value;
    }

    /**
     * Parses a {@code BOOLEAN} as DuckDB does: {@code true}, {@code false},
     * {@code t}, {@code f}, {@code yes}, {@code no}, {@code y}, {@code n} in
     * any case, and {@code 1} and {@code 0}; no surrounding whitespace.
     */
    public static boolean parseBoolean(String s) {
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "t", "yes", "y", "1":
                return true;
            case "false", "f", "no", "n", "0":
                return false;
            default:
                throw notA(s, "a BOOLEAN");
        }
    }

    /**
     * Parses a date as DuckDB does: surrounding whitespace, an optional
     * sign, the year with any number of digits, then month and day with one
     * or two digits, separated by {@code -}, {@code /} or a space. A single
     * whitespace character and {@code (BC)} after the day count a positive
     * year backwards, so that {@code 0001-01-01 (BC)} is year 0; whatever
     * else follows the day is ignored. {@code epoch} is 1970-01-01. The day
     * must lie between {@link Dates#MIN_DAYS} and {@link Dates#MAX_DAYS}.
     *
     * @return the days since 1970-01-01
     */
    public static int parseDate(String s) {
        String t = s.strip();
        if (t.equalsIgnoreCase("epoch")) {
            return 0;
        }
        DateParse d = parseDateParts(t, s);
        if (d == null) {
            throw notA(s, "a DATE");
        }
        return d.days;
    }

    private static int toEpochDay(int year, int month, int day, String s) {
        try {
            long days = LocalDate.of(year, month, day).toEpochDay();
            if (days >= Dates.MIN_DAYS && days <= Dates.MAX_DAYS) {
                return (int) days;
            }
        } catch (DateTimeException e) {
            // reported below
        }
        throw notA(s, "a DATE");
    }

    /** A parsed date and the index after it, including a {@code (BC)}. */
    private record DateParse(int days, int end) {
    }

    private static final Pattern DATE_PARTS = Pattern.compile("([+-]?\\d+)([-/ ])(\\d{1,2})\\2(\\d{1,2})");

    private static DateParse parseDateParts(String t, String s) {
        Matcher m = DATE_PARTS.matcher(t);
        if (!m.lookingAt()) {
            return null;
        }
        int year;
        int month;
        int day;
        try {
            year = Integer.parseInt(m.group(1));
            month = Integer.parseInt(m.group(3));
            day = Integer.parseInt(m.group(4));
        } catch (NumberFormatException e) {
            return null;
        }
        int end = m.end();
        if (t.length() - end >= 5 && Character.isWhitespace(t.charAt(end))
                && t.regionMatches(true, end + 1, "(bc)", 0, 4)) {
            if (year <= 0) {
                return null;
            }
            year = 1 - year;
            end += 5;
        }
        return new DateParse(toEpochDay(year, month, day, s), end);
    }

    /**
     * Formats days since 1970-01-01 as DuckDB does: {@code yyyy-MM-dd} with
     * at least four digits of the year, and a year before year 1 as the year
     * before Christ with {@code (BC)}, e.g. {@code 0001-12-31 (BC)} for the
     * day before 0001-01-01.
     */
    public static String formatDate(int days) {
        return appendDate(new StringBuilder(16), LocalDate.ofEpochDay(days)).toString();
    }

    private static StringBuilder appendDate(StringBuilder b, LocalDate date) {
        int year = date.getYear();
        String digits = Integer.toString(year > 0 ? year : 1 - year);
        for (int i = digits.length(); i < 4; i++) {
            b.append('0');
        }
        b.append(digits).append('-');
        append2(b, date.getMonthValue()).append('-');
        append2(b, date.getDayOfMonth());
        return year > 0 ? b : b.append(" (BC)");
    }

    /**
     * Parses a timestamp as DuckDB does: leading whitespace, a date as in
     * {@link #parseDate}, which must end the string or be followed by a
     * {@code T} or a space and a time {@code H:[M[:[S[.ffffff]]]]}: an hour
     * with one or two digits, then minutes and seconds with up to two digits
     * each, an empty field counting as zero, and a fraction of any length
     * truncated to microseconds. Only after the field of the seconds may
     * come {@code Z} or an offset of two, four or six digits with a sign and
     * optional colons, or a space and {@code UTC} in any case, which are
     * ignored, and then trailing whitespace. A date
     * alone counts as midnight, {@code 24:00:00} is the midnight that
     * follows, and {@code epoch} is 1970-01-01 00:00:00.
     *
     * @return the microseconds since 1970-01-01 00:00:00
     */
    public static long parseTimestamp(String s) {
        if (s.strip().equalsIgnoreCase("epoch")) {
            return 0;
        }
        String t = s.stripLeading();
        DateParse d = parseDateParts(t, s);
        if (d == null) {
            throw notA(s, "a TIMESTAMP");
        }
        int n = t.length();
        int p = d.end;
        long time = 0;
        if (p < n) {
            if (t.charAt(p) != ' ' && t.charAt(p) != 'T') {
                throw notA(s, "a TIMESTAMP");
            }
            int start = ++p;
            p = digits(t, p, 2);
            if (p == start || p == n || t.charAt(p) != ':') {
                throw notA(s, "a TIMESTAMP");
            }
            int hour = Integer.parseInt(t, start, p, 10);
            start = ++p;
            p = digits(t, p, 2);
            int minute = p == start ? 0 : Integer.parseInt(t, start, p, 10);
            int second = 0;
            long micros = 0;
            boolean withSeconds = p < n && t.charAt(p) == ':';
            if (withSeconds) {
                start = ++p;
                p = digits(t, p, 2);
                second = p == start ? 0 : Integer.parseInt(t, start, p, 10);
                if (p < n && t.charAt(p) == '.') {
                    start = ++p;
                    p = digits(t, p, Integer.MAX_VALUE);
                    String fraction = t.substring(start, Math.min(p, start + 6));
                    micros = fraction.isEmpty() ? 0 : Long.parseLong((fraction + "00000").substring(0, 6));
                }
            }
            if (withSeconds) {
                p = zone(t, p);
                while (p < n && Character.isWhitespace(t.charAt(p))) {
                    p++;
                }
            }
            if (p != n || minute > 59 || second > 59 || hour > 24
                    || hour == 24 && (minute != 0 || second != 0 || micros != 0)) {
                throw notA(s, "a TIMESTAMP");
            }
            time = hour * 3_600_000_000L + minute * 60_000_000L + second * MICROS_PER_SECOND + micros;
        }
        try {
            return Math.addExact(Math.multiplyExact(d.days, MICROS_PER_DAY), time);
        } catch (ArithmeticException e) {
            throw notA(s, "a TIMESTAMP");
        }
    }

    /** Returns the index after at most {@code max} digits from an index on. */
    private static int digits(String t, int p, int max) {
        int end = p;
        while (end < t.length() && end - p < max && t.charAt(end) >= '0' && t.charAt(end) <= '9') {
            end++;
        }
        return end;
    }

    /**
     * Returns the index after a time zone at an index: {@code Z}, an offset
     * {@code +HH}, {@code +HH:MM}, {@code +HHMM} or with seconds as well, or
     * a space and {@code UTC}; the index itself if there is none.
     */
    private static int zone(String t, int p) {
        int n = t.length();
        if (p < n && t.charAt(p) == 'Z') {
            return p + 1;
        }
        if (p < n && (t.charAt(p) == '+' || t.charAt(p) == '-')) {
            int q = p + 1;
            if (digits(t, q, 2) != q + 2) {
                return p;
            }
            q += 2;
            for (int part = 0; part < 2; part++) {
                int r = q < n && t.charAt(q) == ':' ? q + 1 : q;
                if (digits(t, r, 2) != r + 2) {
                    break;
                }
                q = r + 2;
            }
            return q;
        }
        return t.regionMatches(true, p, " utc", 0, 4) ? p + 4 : p;
    }

    private static final String TIME_FORMAT = "expected format is ([YYYY-MM-DD ]HH:MM:SS[.MS])";

    /**
     * Parses a time of day as DuckDB does: surrounding whitespace, an
     * optional date and a space or {@code T} before it, which are ignored, a
     * date or {@code epoch} alone being midnight,
     * then {@code H:M[:S[.ffffff]]} with one or two digits per field and a
     * fraction of any length cut off after six digits, then an optional
     * {@code Z} or offset as {@link #parseTimestamp} reads it, which is
     * ignored. {@code 24:00:00} is the end of the day.
     *
     * @return the microseconds since midnight, 0 to 86,400,000,000
     */
    public static long parseTime(String s) {
        String t = s.strip();
        int n = t.length();
        int p = 0;
        Matcher m = DATE_PARTS.matcher(t);
        if (t.equalsIgnoreCase("epoch") || m.matches()) {
            // A date alone is its midnight.
            return 0;
        }
        if (m.lookingAt() && m.end() < n && (t.charAt(m.end()) == ' ' || t.charAt(m.end()) == 'T')) {
            p = m.end() + 1;
        }
        int start = p;
        p = digits(t, p, 2);
        if (p == start || p == n || t.charAt(p) != ':') {
            throw timeOutOfRange(s);
        }
        int hour = Integer.parseInt(t, start, p, 10);
        start = ++p;
        p = digits(t, p, 2);
        if (p == start) {
            throw timeOutOfRange(s);
        }
        int minute = Integer.parseInt(t, start, p, 10);
        int second = 0;
        long micros = 0;
        if (p < n && t.charAt(p) == ':') {
            start = ++p;
            p = digits(t, p, 2);
            if (p == start) {
                throw timeOutOfRange(s);
            }
            second = Integer.parseInt(t, start, p, 10);
            if (p < n && t.charAt(p) == '.') {
                start = ++p;
                p = digits(t, p, Integer.MAX_VALUE);
                micros = sixDigits(t, start, p);
            }
        }
        p = zone(t, p);
        if (p != n || minute > 59 || second > 59 || hour > 24
                || hour == 24 && (minute != 0 || second != 0 || micros != 0)) {
            throw timeOutOfRange(s);
        }
        return hour * 3_600_000_000L + minute * 60_000_000L + second * MICROS_PER_SECOND + micros;
    }

    private static ConversionException timeOutOfRange(String s) {
        return new ConversionException("time field value out of range: \"" + s + "\", " + TIME_FORMAT);
    }

    /** The first six digits of a fraction as microseconds, the digits after them cut off. */
    private static long sixDigits(String t, int from, int to) {
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = value * 10 + (from + i < to ? t.charAt(from + i) - '0' : 0);
        }
        return value;
    }

    /**
     * Formats a time of day as DuckDB does: {@code HH:mm:ss}, with the
     * fraction of a second only if it is not zero, without trailing zeros;
     * the end of the day is {@code 24:00:00}.
     */
    public static String formatTime(long micros) {
        StringBuilder b = new StringBuilder(15);
        appendTime(b, micros);
        return b.toString();
    }

    private static void appendTime(StringBuilder b, long micros) {
        append2(b, (int) (micros / 3_600_000_000L)).append(':');
        append2(b, (int) (micros % 3_600_000_000L / 60_000_000L)).append(':');
        append2(b, (int) (micros % 60_000_000L / MICROS_PER_SECOND));
        Interval.fraction(b, micros % MICROS_PER_SECOND);
    }

    private static final String TIMESTAMPTZ_FORMAT =
            "expected format is (YYYY-MM-DD HH:MM:SS[.US][\u00B1HH[:MM[:SS]]| ZONE])";

    /**
     * Parses a timestamp with time zone as DuckDB does: a date as
     * {@link #parseDate} reads it, optionally followed by a {@code T} or a
     * space and a time {@code H:M[:S[.ffffff]]}, then optionally {@code Z},
     * an offset {@code +HH[[:]MM[[:]SS]]} with a sign, or a space and the name
     * of a time zone ({@link ZonedDates#zone}). Without a zone the date and
     * time are local in the given time zone. {@code epoch} is 1970-01-01
     * 00:00:00 UTC.
     *
     * @return the microseconds since 1970-01-01 00:00:00 UTC
     * @throws ConversionException if the string is no timestamp or names an unknown time zone
     */
    public static long parseTimestampTz(String s, ZoneId zone) {
        String t = s.strip();
        if (t.equalsIgnoreCase("epoch")) {
            return 0;
        }
        DateParse d = parseDateParts(t, s);
        if (d == null) {
            throw invalidTimestampTz(s);
        }
        int n = t.length();
        int p = d.end;
        long time = 0;
        if (p + 1 < n && (t.charAt(p) == ' ' || t.charAt(p) == 'T') && Character.isDigit(t.charAt(p + 1))) {
            int start = ++p;
            p = digits(t, p, 2);
            if (p == n || t.charAt(p) != ':') {
                throw invalidTimestampTz(s);
            }
            int hour = Integer.parseInt(t, start, p, 10);
            start = ++p;
            p = digits(t, p, 2);
            if (p == start) {
                throw invalidTimestampTz(s);
            }
            int minute = Integer.parseInt(t, start, p, 10);
            int second = 0;
            long micros = 0;
            if (p < n && t.charAt(p) == ':') {
                start = ++p;
                p = digits(t, p, 2);
                if (p == start) {
                    throw invalidTimestampTz(s);
                }
                second = Integer.parseInt(t, start, p, 10);
                if (p < n && t.charAt(p) == '.') {
                    start = ++p;
                    p = digits(t, p, Integer.MAX_VALUE);
                    micros = sixDigits(t, start, p);
                }
            }
            if (minute > 59 || second > 59 || hour > 24 || hour == 24 && (minute != 0 || second != 0 || micros != 0)) {
                throw invalidTimestampTz(s);
            }
            time = hour * 3_600_000_000L + minute * 60_000_000L + second * MICROS_PER_SECOND + micros;
        }
        Integer offset = null;
        ZoneId named = null;
        if (p < n && t.charAt(p) == 'Z') {
            offset = 0;
            p++;
        } else if (p < n && (t.charAt(p) == '+' || t.charAt(p) == '-')) {
            int sign = t.charAt(p) == '-' ? -1 : 1;
            int q = p + 1;
            if (digits(t, q, 2) != q + 2) {
                throw invalidTimestampTz(s);
            }
            int seconds = Integer.parseInt(t, q, q + 2, 10) * 3600;
            q += 2;
            for (int part = 0, factor = 60; part < 2; part++, factor = 1) {
                int r = q < n && t.charAt(q) == ':' ? q + 1 : q;
                if (digits(t, r, 2) != r + 2) {
                    break;
                }
                seconds += Integer.parseInt(t, r, r + 2, 10) * factor;
                q = r + 2;
            }
            offset = sign * seconds;
            p = q;
        } else if (p < n && t.charAt(p) == ' ') {
            named = ZonedDates.zone(t.substring(p + 1).strip());
            p = n;
        }
        if (p != n) {
            throw invalidTimestampTz(s);
        }
        try {
            long local = Math.addExact(Math.multiplyExact(d.days, MICROS_PER_DAY), time);
            if (offset != null) {
                return Math.subtractExact(local, offset * MICROS_PER_SECOND);
            }
            return ZonedDates.toInstant(local, named != null ? named : zone);
        } catch (ArithmeticException e) {
            throw invalidTimestampTz(s);
        }
    }

    private static ConversionException invalidTimestampTz(String s) {
        return new ConversionException("invalid timestamp field format: \"" + s + "\", " + TIMESTAMPTZ_FORMAT);
    }

    /**
     * Formats a timestamp with time zone as DuckDB does: the local date and
     * time in the time zone as {@link #formatTimestamp} formats them, then
     * the offset as {@code +HH}, or {@code +HH:MM} if it has minutes; the
     * seconds of an offset of local mean time are left out.
     */
    public static String formatTimestampTz(long micros, ZoneId zone) {
        int offset = ZonedDates.offsetSeconds(micros, zone);
        StringBuilder b = new StringBuilder(formatTimestamp(micros + offset * MICROS_PER_SECOND));
        appendOffset(b, offset, false);
        return b.toString();
    }

    /**
     * Appends an offset in seconds as {@code +HH} or {@code +HH:MM}, or as
     * {@code +HHMM} always with the minutes if compact.
     */
    public static void appendOffset(StringBuilder b, int offset, boolean compact) {
        int abs = Math.abs(offset);
        b.append(offset < 0 ? '-' : '+');
        append2(b, abs / 3600);
        int minutes = abs / 60 % 60;
        if (compact) {
            append2(b, minutes);
        } else if (minutes != 0) {
            append2(b.append(':'), minutes);
        }
    }

    /** Converts a date and time to microseconds since 1970-01-01 00:00:00. */
    public static long toMicros(LocalDateTime dt) {
        long seconds = dt.toEpochSecond(ZoneOffset.UTC);
        return Math.addExact(Math.multiplyExact(seconds, MICROS_PER_SECOND), dt.getNano() / 1000);
    }

    /** Converts microseconds since 1970-01-01 00:00:00 to a date and time. */
    public static LocalDateTime toLocalDateTime(long micros) {
        long seconds = Math.floorDiv(micros, MICROS_PER_SECOND);
        int microOfSecond = (int) Math.floorMod(micros, MICROS_PER_SECOND);
        return LocalDateTime.ofEpochSecond(seconds, microOfSecond * 1000, ZoneOffset.UTC);
    }

    /**
     * Formats a timestamp as DuckDB does: the date as {@link #formatDate}
     * formats it, then {@code HH:mm:ss}, with the fraction of a second only
     * if it is not zero, without trailing zeros.
     */
    public static String formatTimestamp(long micros) {
        LocalDateTime dt = toLocalDateTime(micros);
        StringBuilder b = new StringBuilder(26);
        appendDate(b, dt.toLocalDate()).append(' ');
        LocalTime time = dt.toLocalTime();
        append2(b, time.getHour()).append(':');
        append2(b, time.getMinute()).append(':');
        append2(b, time.getSecond());
        int micro = time.getNano() / 1000;
        if (micro != 0) {
            String fraction = String.format("%06d", micro);
            int end = fraction.length();
            while (fraction.charAt(end - 1) == '0') {
                end--;
            }
            b.append('.').append(fraction, 0, end);
        }
        return b.toString();
    }

    private static StringBuilder append2(StringBuilder b, int value) {
        if (value < 10) {
            b.append('0');
        }
        return b.append(value);
    }

    /** Returns the days since 1970-01-01 of a timestamp, as DuckDB casts {@code TIMESTAMP} to {@code DATE}. */
    public static int timestampToDate(long micros) {
        return Math.toIntExact(Math.floorDiv(micros, MICROS_PER_DAY));
    }

    /**
     * Returns the midnight of a date as microseconds, as DuckDB casts
     * {@code DATE} to {@code TIMESTAMP}.
     *
     * @throws ArithmeticException if the date lies beyond the range of a
     *         timestamp, after 294247-01-10
     */
    public static long dateToTimestamp(int days) {
        return Math.multiplyExact(days, MICROS_PER_DAY);
    }

    /** Parses a UUID in its canonical hexadecimal form. */
    public static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            throw notA(s, "a UUID");
        }
    }

    /**
     * Formats a {@code BLOB} as DuckDB does: printable ASCII bytes but the
     * backslash and the quotes as themselves, every other byte as
     * {@code \xHH}.
     */
    public static String formatBlob(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length);
        for (byte x : bytes) {
            int c = x & 0xFF;
            if (c >= 0x20 && c < 0x7F && c != '\\' && c != '\'' && c != '"') {
                b.append((char) c);
            } else {
                b.append("\\x").append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return b.toString();
    }

    /** Parses the {@code BLOB} format of {@link #formatBlob}; other characters are their UTF-8 bytes. */
    public static byte[] parseBlob(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.charAt(i + 1) == 'x') {
                int hi = Character.digit(s.charAt(i + 2), 16);
                int lo = Character.digit(s.charAt(i + 3), 16);
                if (hi < 0 || lo < 0) {
                    throw notA(s, "a BLOB");
                }
                out.write(hi << 4 | lo);
                i += 4;
            } else {
                int cp = s.codePointAt(i);
                out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return out.toByteArray();
    }

    /** Formats a decimal without an exponent and with its scale, e.g. {@code 1.50}. */
    public static String formatDecimal(BigDecimal d) {
        return d.toPlainString();
    }

    /**
     * Formats a {@code DOUBLE} or {@code FLOAT} close to the way DuckDB does:
     * the shortest digits that round-trip, {@code NaN}, {@code inf} and
     * {@code -inf}, integral values with a trailing {@code .0}, and an
     * exponent only from 1e16 on and below 1e-4, as measured in DuckDB 1.5.6.
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        BigDecimal digits = new BigDecimal(Double.toString(d));
        if (Math.abs(d) < Double.MIN_NORMAL) {
            digits = shortest(digits, x -> Double.parseDouble(x.toString()) == d);
        }
        return formatDigits(digits);
    }

    /** Formats a {@code FLOAT} as {@link #formatDouble} formats a double, with the shortest digits of the float. */
    public static String formatFloat(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f) || f == 0) {
            return formatDouble(f);
        }
        BigDecimal digits = new BigDecimal(Float.toString(f));
        if (Math.abs(f) < Float.MIN_NORMAL) {
            digits = shortest(digits, x -> Float.parseFloat(x.toString()) == f);
        }
        return formatDigits(digits);
    }

    /**
     * Drops digits as long as the number still reads back as the same
     * value. Java writes two digits for some subnormal numbers where one is
     * enough, e.g. {@code 4.9E-324} for {@code 5e-324}.
     */
    private static BigDecimal shortest(BigDecimal digits, java.util.function.Predicate<BigDecimal> readsBack) {
        BigDecimal best = digits;
        for (int p = digits.precision() - 1; p >= 1; p--) {
            BigDecimal candidate = digits.round(new java.math.MathContext(p, RoundingMode.HALF_EVEN));
            if (!readsBack.test(candidate)) {
                break;
            }
            best = candidate;
        }
        return best;
    }

    /** Writes the digits of a nonzero floating-point number in the form of {@link #formatDouble}. */
    private static String formatDigits(BigDecimal number) {
        BigDecimal exact = number.stripTrailingZeros();
        double magnitude = exact.abs().doubleValue();
        if (magnitude >= 1e16 || magnitude < 1e-4) {
            String digits = exact.unscaledValue().abs().toString();
            int exponent = digits.length() - 1 - exact.scale();
            String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
            return (exact.signum() < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+")
                    + (Math.abs(exponent) < 10 ? "0" : "") + Math.abs(exponent);
        }
        String plain = exact.toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }

    private static ConversionException notA(String s, String what) {
        return new ConversionException("Could not convert string '" + s + "' to " + what);
    }

    private static ArithmeticException outOfRange(String s, String type) {
        return new ArithmeticException("Out of range for " + type + ": '" + s + "'");
    }
}
