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

package net.fyrql.sql.bind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings of a session besides its time zone, as {@code SHOW} and
 * {@code current_setting} show them; the binder reads them at each binding.
 * The names are those of {@link #NAMES}, in any case in SQL:
 * <ul>
 * <li>{@code TimeZone}: the time zone, which the binder takes on its own.</li>
 * <li>{@code search_path}: the default source and schema, written
 * {@code source} or {@code source.schema}, empty for no default source,
 * see {@link #searchPath(String)}.</li>
 * <li>{@code statement_timeout}: the timeout of a statement, written as
 * in PostgreSQL in milliseconds or with a unit, {@code 0} for none, see
 * {@link #timeout(String)}.</li>
 * </ul>
 */
@FunctionalInterface
public interface Settings {

    String TIME_ZONE = "TimeZone";
    String SEARCH_PATH = "search_path";
    String STATEMENT_TIMEOUT = "statement_timeout";
    /** The name with which {@code RESET ALL} and {@code SHOW ALL} name every setting. */
    String ALL = "ALL";

    /** The names of the settings as FyrQL spells them, in the order of {@code SHOW ALL}. */
    List<String> NAMES = List.of(TIME_ZONE, SEARCH_PATH, STATEMENT_TIMEOUT);

    /** The settings of a session without a default source and without a timeout. */
    Settings DEFAULT = name -> name.equals(STATEMENT_TIMEOUT) ? "0" : "";

    /**
     * Returns the value of a setting other than {@code TimeZone}, as
     * {@code SHOW} shows it.
     *
     * @param name the name as FyrQL spells it
     */
    String value(String name);

    /** Returns the name of a setting as FyrQL spells it, or {@code null} if there is none of the name. */
    static String name(String name) {
        for (String n : NAMES) {
            if (n.equalsIgnoreCase(name)) {
                return n;
            }
        }
        return null;
    }

    /**
     * Returns the parts of a value of {@code search_path}: none for an empty
     * value, else a source and maybe a schema, separated by a dot outside
     * double quotes; a part in double quotes is taken as it is, with a
     * doubled quote for one.
     *
     * @throws IllegalArgumentException if the value has more than two parts or an empty one
     */
    static List<String> searchPath(String value) {
        List<String> parts = new ArrayList<>();
        if (value.isBlank()) {
            return parts;
        }
        StringBuilder part = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    part.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                    wasQuoted = true;
                }
            } else if (c == '.' && !quoted) {
                parts.add(searchPathPart(part, wasQuoted, value));
                part.setLength(0);
                wasQuoted = false;
            } else {
                part.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("invalid value for parameter \"search_path\": \"" + value
                    + "\": a double quote is not closed");
        }
        parts.add(searchPathPart(part, wasQuoted, value));
        if (parts.size() > 2) {
            throw new IllegalArgumentException("invalid value for parameter \"search_path\": \"" + value
                    + "\": it names a source, or a source and a schema, such as memory or memory.main");
        }
        return parts;
    }

    private static String searchPathPart(StringBuilder part, boolean quoted, String value) {
        String p = quoted ? part.toString() : part.toString().strip();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("invalid value for parameter \"search_path\": \"" + value
                    + "\": a part is empty");
        }
        return p;
    }

    /**
     * Returns the value of {@code search_path} for its parts, see
     * {@link #searchPath(String)}: empty for none, else the parts separated
     * by a dot, a part in double quotes if it has a dot or a double quote.
     */
    static String searchPathText(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(sb.isEmpty() ? "" : ".").append(quoted(part));
        }
        return sb.toString();
    }

    private static String quoted(String part) {
        return part.indexOf('.') >= 0 || part.indexOf('"') >= 0 || !part.equals(part.strip())
                ? '"' + part.replace("\"", "\"\"") + '"' : part;
    }

    /**
     * Returns the timeout a value of {@code statement_timeout} gives, as in
     * PostgreSQL: a whole number of milliseconds, or with one of the units
     * {@code ms}, {@code s}, {@code min}, {@code h} and {@code d}; the
     * units {@code m} for minutes and a duration of ISO 8601 such as
     * {@code PT30S} are taken as in the configuration. {@link Duration#ZERO}
     * is no timeout.
     *
     * @throws IllegalArgumentException if the value is none of these
     */
    static Duration timeout(String value) {
        Matcher m = Pattern.compile("\\s*(\\d+)\\s*([a-zA-Z]*)\\s*").matcher(value);
        if (m.matches()) {
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                throw invalidTimeout(value);
            }
            try {
                return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                    case "", "ms" -> Duration.ofMillis(n);
                    case "s" -> Duration.ofSeconds(n);
                    case "m", "min" -> Duration.ofMinutes(n);
                    case "h" -> Duration.ofHours(n);
                    case "d" -> Duration.ofDays(n);
                    default -> throw invalidTimeout(value);
                };
            } catch (ArithmeticException e) {
                throw invalidTimeout(value);
            }
        }
        try {
            Duration d = Duration.parse(value.strip());
            if (!d.isNegative()) {
                return d;
            }
        } catch (RuntimeException e) {
            // Not a duration of ISO 8601 either.
        }
        throw invalidTimeout(value);
    }

    private static IllegalArgumentException invalidTimeout(String value) {
        return new IllegalArgumentException("invalid value for parameter \"statement_timeout\": \"" + value
                + "\": it is a number of milliseconds, or one with the unit ms, s, min, h or d, such as 30s");
    }

    /**
     * Returns the value of {@code statement_timeout} for a timeout, as
     * PostgreSQL shows it: {@code 0} for none, else in the largest of the
     * units {@code d}, {@code h}, {@code min}, {@code s} and {@code ms}
     * that gives a whole number, such as {@code 30s} or {@code 1500ms}.
     */
    static String timeoutText(Duration timeout) {
        if (timeout.isZero()) {
            return "0";
        }
        if (timeout.getNano() % 1_000_000 != 0) {
            return timeout.toString();
        }
        long ms = timeout.toMillis();
        return ms % 86_400_000 == 0 ? ms / 86_400_000 + "d" : ms % 3_600_000 == 0 ? ms / 3_600_000 + "h"
                : ms % 60_000 == 0 ? ms / 60_000 + "min" : ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }
}
