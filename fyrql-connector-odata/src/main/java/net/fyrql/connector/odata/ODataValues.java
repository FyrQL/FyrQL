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

import net.fyrql.connector.json.JsonStringParser;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the strings in the JSON of OData as the types of their columns:
 * <ul>
 * <li>a {@code TIMESTAMP} from {@code /Date(ms)/} of version 2, the
 * milliseconds since 1970-01-01 00:00:00, of which an {@code Edm.DateTime}
 * has no zone and an {@code Edm.DateTimeOffset} is in UTC, so that an
 * offset after them, as in {@code /Date(ms+0120)/}, is ignored; or from an
 * ISO date and time such as {@code 2024-01-02T03:04:05.1234567+02:00},
 * whose offset or {@code Z} makes it the instant in UTC, its fraction
 * truncated to microseconds;</li>
 * <li>a {@code TIMESTAMP WITH TIME ZONE} as a {@code TIMESTAMP}, the instant
 * in UTC;</li>
 * <li>a {@code DATE} from an ISO date, or from {@code /Date(ms)/} at
 * midnight;</li>
 * <li>a {@code TIME} from a duration of ISO 8601 within a day, as
 * {@code PT13H20M15.5S} of version 2, or from {@code hh:mm[:ss[.f]]} of
 * version 4, its fraction truncated to microseconds;</li>
 * <li>an {@code INTERVAL} from a duration of ISO 8601 such as
 * {@code -P1DT2H}, its years and months as months, its days as days, the
 * rest as microseconds, all with the sign;</li>
 * <li>a {@code BLOB} from Base64, also in its form for URLs, of version 4,
 * with or without padding;</li>
 * <li>numbers, also {@code INF}, {@code -INF} and {@code NaN}, booleans and
 * UUIDs as a cast reads them, a {@code VARCHAR} as it is.</li>
 * </ul>
 */
final class ODataValues implements JsonStringParser {

    static final ODataValues INSTANCE = new ODataValues();

    private static final Pattern DATE = Pattern.compile("/Date\\((-?\\d{1,19})(?:[+-]\\d{1,4})?\\)/");

    private static final Pattern ISO = Pattern.compile(
            "(-?\\d{4,9})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,12}))?)?"
                    + "([Zz]|([+-])(\\d{2}):?(\\d{2}))?");

    private static final Pattern DURATION = Pattern.compile("(-)?P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?"
            + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.(\\d{1,12}))?S)?)?");

    private static final Pattern TIME_OF_DAY = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,12}))?)?");

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    private ODataValues() {
    }

    @Override
    public Object parse(String text, DataType type) {
        if (type == DataType.VARCHAR) {
            return text;
        }
        if (type == DataType.TIMESTAMP || type == DataType.TIMESTAMPTZ) {
            return timestamp(text);
        }
        if (type == DataType.TIME) {
            return time(text);
        }
        if (type == DataType.INTERVAL) {
            return duration(text);
        }
        if (type == DataType.DATE) {
            return date(text);
        }
        if (type == DataType.BLOB) {
            return binary(text);
        }
        return Casts.parse(text, type);
    }

    /** Returns the microseconds since 1970-01-01 00:00:00 of a date and time of OData. */
    static long timestamp(String text) {
        String t = text.strip();
        Matcher m = DATE.matcher(t);
        if (m.matches()) {
            return Math.multiplyExact(millis(m.group(1), text), 1000L);
        }
        m = ISO.matcher(t);
        if (m.matches()) {
            return iso(m, text);
        }
        try {
            return Conversions.parseTimestamp(t);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("expected /Date(milliseconds)/ or an ISO 8601 date and time", e);
        }
    }

    /** Returns the microseconds of a time of day of OData, a duration within a day or {@code hh:mm:ss}. */
    static long time(String text) {
        String t = text.strip();
        Matcher m = TIME_OF_DAY.matcher(t);
        long micros;
        if (m.matches()) {
            micros = (Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60_000_000L
                    + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)) * 1_000_000L) + fraction(m.group(4));
        } else {
            Interval d = duration(text);
            if (d.months() != 0 || d.days() < 0 || d.days() > 1) {
                throw new IllegalArgumentException("expected a time of day: " + text);
            }
            micros = d.days() * MICROS_PER_DAY + d.micros();
        }
        if (micros < 0 || micros > MICROS_PER_DAY) {
            throw new IllegalArgumentException("expected a time of day: " + text);
        }
        return micros;
    }

    /** Returns the interval of a duration of ISO 8601. */
    static Interval duration(String text) {
        Matcher m = DURATION.matcher(text.strip());
        boolean any = false;
        for (int g = 2; m.matches() && g <= 7; g++) {
            any |= m.group(g) != null;
        }
        if (!any || text.strip().endsWith("T")) {
            throw new IllegalArgumentException("expected a duration of ISO 8601 such as P1DT2H30M: " + text);
        }
        try {
            long months = Math.addExact(Math.multiplyExact(number(m.group(2)), 12L), number(m.group(3)));
            long days = number(m.group(4));
            long micros = Math.addExact(Math.addExact(Math.multiplyExact(number(m.group(5)), 3_600_000_000L),
                    Math.multiplyExact(number(m.group(6)), 60_000_000L)),
                    Math.addExact(Math.multiplyExact(number(m.group(7)), 1_000_000L), fraction(m.group(8))));
            Interval d = Interval.of(Math.toIntExact(months), Math.toIntExact(days), micros);
            return m.group(1) != null ? d.negate() : d;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("the duration " + text + " is out of range", e);
        }
    }

    private static long number(String digits) {
        return digits == null ? 0 : Long.parseLong(digits);
    }

    /** The microseconds of the digits of a fraction of a second, truncated. */
    private static long fraction(String digits) {
        if (digits == null) {
            return 0;
        }
        String six = digits.length() >= 6 ? digits.substring(0, 6) : digits + "0".repeat(6 - digits.length());
        return Long.parseLong(six);
    }

    /** Returns the days since 1970-01-01 of a date of OData. */
    static int date(String text) {
        String t = text.strip();
        Matcher m = DATE.matcher(t);
        if (m.matches()) {
            return Math.toIntExact(Math.floorDiv(millis(m.group(1), text), MILLIS_PER_DAY));
        }
        return Conversions.parseDate(t);
    }

    /** Returns the bytes of Base64, in its usual form or in that for URLs, with or without padding. */
    static byte[] binary(String text) {
        String t = text.strip().replace('-', '+').replace('_', '/');
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("expected Base64: " + e.getMessage(), e);
        }
    }

    private static long millis(String digits, String text) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the milliseconds of " + text + " are out of range", e);
        }
    }

    private static long iso(Matcher m, String text) {
        try {
            LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            long micros = Conversions.toMicros(t);
            String fraction = m.group(7);
            if (fraction != null) {
                String six = fraction.length() >= 6 ? fraction.substring(0, 6) : fraction + "0".repeat(
                        6 - fraction.length());
                micros = Math.addExact(micros, Long.parseLong(six));
            }
            if (m.group(9) != null) {
                int hours = Integer.parseInt(m.group(10));
                int minutes = Integer.parseInt(m.group(11));
                if (hours > 23 || minutes > 59) {
                    throw new IllegalArgumentException("the offset of " + text + " is out of range");
                }
                long offset = (hours * 60L + minutes) * 60_000_000L;
                micros = Math.subtractExact(micros, m.group(9).equals("+") ? offset : -offset);
            }
            return micros;
        } catch (DateTimeException | ArithmeticException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
