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

package net.fyrql.connector.json;

import net.fyrql.core.function.DateFormats;
import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Conversions;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forms of strings that make a {@code DATE}, {@code TIMESTAMP} or
 * {@code UUID} instead of a {@code VARCHAR}, as DuckDB 1.5.6 detects them in
 * JSON, measured against it. A column takes the first form, in the order of
 * {@link #kinds()}, that every string of the sample has; with none it is a
 * {@code VARCHAR}. The forms are:
 * <ul>
 * <li>dates in the formats of {@code strptime} {@code %y-%m-%d},
 * {@code %Y-%m-%d}, {@code %d-%m-%y}, {@code %d-%m-%Y}, {@code %m-%d-%y}
 * and {@code %m-%d-%Y}, so that {@code 01-02-2020} is February 1;</li>
 * <li>timestamps in {@code %Y-%m-%dT%H:%M:%SZ}, {@code %y-%m-%d %H:%M:%S},
 * {@code %Y-%m-%d %H:%M:%S}, {@code %d-%m-%y %H:%M:%S},
 * {@code %d-%m-%Y %H:%M:%S}, {@code %m-%d-%y %I:%M:%S %p},
 * {@code %m-%d-%Y %I:%M:%S %p} and {@code %Y-%m-%d %H:%M:%S.%f}, and in the
 * ISO forms {@code 2020-01-02T03:04:05}, the same with an offset such as
 * {@code +02:00}, with a fraction of a second and {@code Z}, and with a
 * fraction and an offset; a timestamp with an offset is its instant in
 * UTC;</li>
 * <li>UUIDs in their canonical form of 36 characters, in any case.</li>
 * </ul>
 * Each form is a kind of its own: {@code 2020-01-02 03:04:05} and
 * {@code 2020-01-02 03:04:05.5} in one column make a {@code VARCHAR}, as in
 * DuckDB. A {@code dateformat} or {@code timestampformat} replaces the forms
 * of its type. Strings of a time of day stay {@code VARCHAR}; DuckDB reads
 * them as {@code TIME}, which FyrQL does not have.
 */
final class StringKinds {

    private static final List<String> DATE_FORMATS = List.of("%y-%m-%d", "%Y-%m-%d", "%d-%m-%y", "%d-%m-%Y",
            "%m-%d-%y", "%m-%d-%Y");

    private static final List<String> TIMESTAMP_FORMATS = List.of("%Y-%m-%dT%H:%M:%SZ", "%y-%m-%d %H:%M:%S",
            "%Y-%m-%d %H:%M:%S", "%d-%m-%y %H:%M:%S", "%d-%m-%Y %H:%M:%S", "%m-%d-%y %I:%M:%S %p",
            "%m-%d-%Y %I:%M:%S %p", "%Y-%m-%d %H:%M:%S.%f");

    /** The ISO forms with a {@code T}, each with a fraction or not, and an offset, {@code Z} or neither. */
    private static final Pattern ISO = Pattern.compile(
            "\\s*(\\d{4})-(\\d{1,2})-(\\d{1,2})T(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,6}))?"
                    + "(Z|([+-])(\\d{2}):(\\d{2}))?\\s*");

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * A form of strings of a type.
     *
     * @param type the type of the values
     * @param name the format or a description, for messages
     * @param parser the value of a string of the form, or {@code null} if it does not have it
     */
    record Kind(DataType type, String name, Function<String, Object> parser) {

        /** Returns the value of a string of this form, or {@code null}. */
        Object parse(String s) {
            return parser.apply(s);
        }
    }

    private final List<Kind> kinds;

    private StringKinds(List<Kind> kinds) {
        if (kinds.size() > 31) {
            throw new IllegalStateException("Too many kinds of strings: " + kinds.size());
        }
        this.kinds = List.copyOf(kinds);
    }

    /** Returns the forms for the options: their date and timestamp formats replace those of DuckDB. */
    static StringKinds of(JsonOptions options) {
        List<Kind> kinds = new ArrayList<>();
        boolean ownDate = options.dateFormat() != null;
        for (String format : ownDate ? List.of(options.dateFormat()) : DATE_FORMATS) {
            ToLongFunction<String> reader = DateFormats.reader(format);
            kinds.add(new Kind(DataType.DATE, format, s -> {
                Long micros = parse(reader, s, !ownDate);
                return micros == null ? null : Conversions.timestampToDate(micros);
            }));
        }
        boolean ownTimestamp = options.timestampFormat() != null;
        for (String format : ownTimestamp ? List.of(options.timestampFormat()) : TIMESTAMP_FORMATS) {
            ToLongFunction<String> reader = DateFormats.reader(format);
            kinds.add(new Kind(DataType.TIMESTAMP, format, s -> parse(reader, s, !ownTimestamp)));
        }
        if (!ownTimestamp) {
            kinds.add(new Kind(DataType.TIMESTAMP, "ISO 8601 with T", s -> iso(s, false, 0)));
            kinds.add(new Kind(DataType.TIMESTAMP, "ISO 8601 with T and an offset", s -> iso(s, false, 2)));
            kinds.add(new Kind(DataType.TIMESTAMP, "ISO 8601 with T, a fraction and Z", s -> iso(s, true, 1)));
            kinds.add(new Kind(DataType.TIMESTAMP, "ISO 8601 with T, a fraction and an offset",
                    s -> iso(s, true, 2)));
        }
        kinds.add(new Kind(DataType.UUID, "UUID",
                s -> s.length() == 36 && UUID.matcher(s).matches() ? Conversions.parseUuid(s) : null));
        return new StringKinds(kinds);
    }

    /** Returns the forms in the order they are tried. */
    List<Kind> kinds() {
        return kinds;
    }

    /** Returns the set of all forms, as a mask of bits by their index. */
    int all() {
        return (1 << kinds.size()) - 1;
    }

    /** Returns the forms of {@code alive} that the string has. */
    int matching(String s, int alive) {
        if (alive == 0 || s.length() > 64) {
            return 0;
        }
        int matching = 0;
        for (int k = 0; k < kinds.size(); k++) {
            if ((alive & 1 << k) != 0 && kinds.get(k).parse(s) != null) {
                matching |= 1 << k;
            }
        }
        return matching;
    }

    /** Returns the first form of a nonempty set. */
    Kind first(int mask) {
        return kinds.get(Integer.numberOfTrailingZeros(mask));
    }

    /**
     * Reads a string in a format of {@code strptime}, or returns {@code null}
     * if it does not match.
     *
     * @param number whether the format starts with a number, so that a
     *        string that does not is rejected without parsing it
     */
    private static Long parse(ToLongFunction<String> reader, String s, boolean number) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (number && (i == s.length() || s.charAt(i) < '0' || s.charAt(i) > '9')) {
            return null;
        }
        try {
            return reader.applyAsLong(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads an ISO timestamp with a {@code T}, or returns {@code null} if
     * the string has another form.
     *
     * @param fraction whether the seconds have a fraction
     * @param zone 0 for none, 1 for {@code Z}, 2 for an offset
     */
    private static Long iso(String s, boolean fraction, int zone) {
        Matcher m = ISO.matcher(s);
        if (!m.matches() || (m.group(7) != null) != fraction) {
            return null;
        }
        String z = m.group(8);
        if (zone == 0 ? z != null : zone == 1 ? !"Z".equals(z) : z == null || z.equals("Z")) {
            return null;
        }
        try {
            LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)));
            long micros = Conversions.toMicros(t);
            if (fraction) {
                String digits = m.group(7);
                micros += Long.parseLong(digits) * (long) Math.pow(10, 6 - digits.length());
            }
            if (zone == 2) {
                int hours = Integer.parseInt(m.group(10));
                int minutes = Integer.parseInt(m.group(11));
                if (hours > 23 || minutes > 59) {
                    return null;
                }
                long offset = (hours * 60L + minutes) * 60_000_000L;
                micros -= m.group(9).equals("+") ? offset : -offset;
            }
            return micros;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
