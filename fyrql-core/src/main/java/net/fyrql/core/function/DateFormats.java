/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.core.function;

import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Dates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The formats of {@code strftime} and {@code strptime}, with a subset of the
 * specifiers of DuckDB 1.5.6 and its rules, measured against it:
 * {@code %Y %y %m %d %H %M %S %f %j %a %A %b %B %p %I %%}, and the forms
 * {@code %-y %-m %-d %-H %-M %-S %-j %-I} that print no leading zeros. Any
 * other specifier is an error.
 * <p>
 * Writing: {@code %Y} prints a year from 0 to 9999 with four digits and
 * any other one as it is, a year before 1 BC with a minus sign; {@code %y}
 * the last two digits of the year without its sign; {@code %f} the six
 * digits of the microseconds; names are English.
 * <p>
 * Reading: leading and trailing whitespace is skipped; a run of whitespace
 * in the format matches a nonempty run of whitespace; a number has at most
 * as many digits as its specifier writes (four for {@code %Y}, six for
 * {@code %f}, whose fewer digits are a fraction) and must lie in its range;
 * names are not case-sensitive, and a day name is read but not checked;
 * {@code %y} below 69 lies in the 2000s; {@code %p} turns hours up to 12
 * into the 24-hour clock; {@code %j} counts days from January 1 of the year
 * unless {@code %m} or {@code %d} is read too, a month name does not
 * count. Missing fields default to
 * 1900-01-01 00:00:00.
 */
public final class DateFormats {

    private static final String[] DAY_NAMES = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday"};
    private static final String[] DAY_ABBREVIATIONS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTH_NAMES = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};
    private static final String[] MONTH_ABBREVIATIONS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep",
        "Oct", "Nov", "Dec"};

    private static final String SPECIFIERS = "YymdHMSfjaAbBpI";
    private static final String UNPADDED_SPECIFIERS = "ymdHMSjI";

    private DateFormats() {
    }

    /** A specifier: its letter, and whether a number is written without leading zeros. */
    record Specifier(char letter, boolean unpadded) {
    }

    /** A parsed format: the literal text around the specifiers, one piece more than specifiers. */
    record Format(String text, List<String> literals, List<Specifier> specifiers) {
    }

    /**
     * Checks a format, as a binder does for a constant format before any row
     * is read.
     *
     * @throws IllegalArgumentException if the format is empty, ends in a
     *         single {@code %}, or uses another specifier
     */
    public static void check(String format) {
        parse(format);
    }

    /**
     * Returns a reader of strings in a format, as {@code strptime} reads
     * them, for readers of files that parse many strings in one format. The
     * reader returns the microseconds since 1970-01-01 00:00:00 and throws a
     * {@link ConversionException} for a string that does not match.
     *
     * @throws IllegalArgumentException if the format is not valid, see
     *         {@link #check}
     */
    public static ToLongFunction<String> reader(String format) {
        Format parsed = parse(format);
        return s -> parse(parsed, s);
    }

    /**
     * Parses a format.
     *
     * @throws IllegalArgumentException if the format is empty, ends in a
     *         single {@code %}, or uses another specifier
     */
    static Format parse(String format) {
        if (format.isEmpty()) {
            throw new IllegalArgumentException("Failed to parse format specifier: Empty format string");
        }
        List<String> literals = new ArrayList<>();
        List<Specifier> specifiers = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                literal.append(c);
                continue;
            }
            if (i + 1 == format.length()) {
                throw new IllegalArgumentException("Failed to parse format specifier " + format
                        + ": Trailing format character %");
            }
            char letter = format.charAt(++i);
            if (letter == '%') {
                literal.append('%');
                continue;
            }
            boolean unpadded = letter == '-' && i + 1 < format.length();
            if (unpadded) {
                letter = format.charAt(++i);
            }
            if ((unpadded ? UNPADDED_SPECIFIERS : SPECIFIERS).indexOf(letter) < 0) {
                throw new IllegalArgumentException("Failed to parse format specifier " + format
                        + ": Unrecognized format for strftime/strptime: %" + (unpadded ? "-" : "") + letter);
            }
            literals.add(literal.toString());
            literal.setLength(0);
            specifiers.add(new Specifier(letter, unpadded));
        }
        literals.add(literal.toString());
        return new Format(format, List.copyOf(literals), List.copyOf(specifiers));
    }

    /** Writes a date or timestamp, given as its day and the microseconds since midnight, in a format. */
    static String format(Format format, int days, long time) {
        LocalDate date = LocalDate.ofEpochDay(days);
        int hour = (int) (time / Dates.MICROS_PER_HOUR);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < format.specifiers().size(); i++) {
            b.append(format.literals().get(i));
            Specifier s = format.specifiers().get(i);
            switch (s.letter()) {
                case 'Y' -> {
                    int year = date.getYear();
                    if (year >= 0 && year <= 9999) {
                        number(b, year, 4, false);
                    } else {
                        b.append(year);
                    }
                }
                case 'y' -> number(b, Math.abs(date.getYear()) % 100, 2, s.unpadded());
                case 'm' -> number(b, date.getMonthValue(), 2, s.unpadded());
                case 'd' -> number(b, date.getDayOfMonth(), 2, s.unpadded());
                case 'H' -> number(b, hour, 2, s.unpadded());
                case 'I' -> number(b, hour % 12 == 0 ? 12 : hour % 12, 2, s.unpadded());
                case 'M' -> number(b, time % Dates.MICROS_PER_HOUR / Dates.MICROS_PER_MINUTE, 2, s.unpadded());
                case 'S' -> number(b, time % Dates.MICROS_PER_MINUTE / Dates.MICROS_PER_SECOND, 2, s.unpadded());
                case 'f' -> number(b, time % Dates.MICROS_PER_SECOND, 6, false);
                case 'j' -> number(b, date.getDayOfYear(), 3, s.unpadded());
                case 'a' -> b.append(DAY_ABBREVIATIONS[date.getDayOfWeek().getValue() % 7]);
                case 'A' -> b.append(DAY_NAMES[date.getDayOfWeek().getValue() % 7]);
                case 'b' -> b.append(MONTH_ABBREVIATIONS[date.getMonthValue() - 1]);
                case 'B' -> b.append(MONTH_NAMES[date.getMonthValue() - 1]);
                case 'p' -> b.append(hour < 12 ? "AM" : "PM");
                default -> throw new IllegalStateException("Specifier %" + s.letter());
            }
        }
        return b.append(format.literals().get(format.literals().size() - 1)).toString();
    }

    private static void number(StringBuilder b, long value, int width, boolean unpadded) {
        String digits = Long.toString(value);
        if (!unpadded) {
            for (int i = digits.length(); i < width; i++) {
                b.append('0');
            }
        }
        b.append(digits);
    }

    /**
     * Reads a timestamp in a format.
     *
     * @return the microseconds since 1970-01-01 00:00:00
     * @throws ConversionException if the string does not match the format
     *         or names no valid date
     */
    static long parse(Format format, String s) {
        int n = s.length();
        int pos = skipSpaces(s, 0);
        int year = 1900;
        int month = 1;
        int day = 1;
        int hour = 0;
        int minute = 0;
        int second = 0;
        long micros = 0;
        int yearDay = 0;
        boolean monthOrDay = false;
        Boolean pm = null;
        for (int i = 0;; i++) {
            String literal = format.literals().get(i);
            for (int l = 0; l < literal.length();) {
                char c = literal.charAt(l);
                if (isSpace(c)) {
                    if (pos >= n || !isSpace(s.charAt(pos))) {
                        throw mismatch(format, s, "Space does not match, expected " + literal);
                    }
                    pos = skipSpaces(s, pos);
                    while (l < literal.length() && isSpace(literal.charAt(l))) {
                        l++;
                    }
                    continue;
                }
                if (pos >= n || s.charAt(pos) != c) {
                    throw mismatch(format, s, "Literal does not match, expected " + literal);
                }
                pos++;
                l++;
            }
            if (i == format.specifiers().size()) {
                break;
            }
            char letter = format.specifiers().get(i).letter();
            int width = switch (letter) {
                case 'Y' -> 4;
                case 'f' -> 6;
                case 'j' -> 3;
                case 'y', 'm', 'd', 'H', 'I', 'M', 'S' -> 2;
                default -> 0;
            };
            if (width == 0) {
                switch (letter) {
                    case 'a' -> pos += DAY_ABBREVIATIONS[name(format, s, pos, DAY_ABBREVIATIONS,
                            "an abbreviated day name")].length();
                    case 'A' -> pos += DAY_NAMES[name(format, s, pos, DAY_NAMES, "a full day name")].length();
                    case 'b', 'B' -> {
                        String[] names = letter == 'b' ? MONTH_ABBREVIATIONS : MONTH_NAMES;
                        int k = name(format, s, pos, names,
                                letter == 'b' ? "an abbreviated month name" : "a full month name");
                        pos += names[k].length();
                        month = k + 1;
                    }
                    case 'p' -> {
                        if (pos + 2 > n || Character.toLowerCase(s.charAt(pos + 1)) != 'm'
                                || "ap".indexOf(Character.toLowerCase(s.charAt(pos))) < 0) {
                            throw mismatch(format, s, "Expected AM/PM");
                        }
                        pm = Character.toLowerCase(s.charAt(pos)) == 'p';
                        pos += 2;
                    }
                    default -> throw new IllegalStateException("Specifier %" + letter);
                }
                continue;
            }
            int start = pos;
            int number = 0;
            while (pos < n && pos - start < width && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                number = number * 10 + s.charAt(pos) - '0';
                pos++;
            }
            if (pos == start) {
                throw mismatch(format, s, "Expected a number");
            }
            switch (letter) {
                case 'Y' -> year = number;
                case 'y' -> year = number >= 69 ? 1900 + number : 2000 + number;
                case 'm' -> {
                    month = inRange(format, s, number, 1, 12, "Month");
                    monthOrDay = true;
                }
                case 'd' -> {
                    day = inRange(format, s, number, 1, 31, "Day");
                    monthOrDay = true;
                }
                case 'H' -> hour = inRange(format, s, number, 0, 23, "Hour");
                case 'I' -> hour = inRange(format, s, number, 1, 12, "Hour12");
                case 'M' -> minute = inRange(format, s, number, 0, 59, "Minutes");
                case 'S' -> second = inRange(format, s, number, 0, 59, "Seconds");
                case 'j' -> yearDay = inRange(format, s, number, 1, 366, "Year day");
                case 'f' -> {
                    micros = number;
                    for (int k = pos - start; k < 6; k++) {
                        micros *= 10;
                    }
                }
                default -> throw new IllegalStateException("Specifier %" + letter);
            }
        }
        if (skipSpaces(s, pos) != n) {
            throw mismatch(format, s, "Full specifier did not match: trailing characters");
        }
        if (pm != null) {
            if (hour > 12) {
                throw mismatch(format, s, "Invalid hour: " + hour + " AM/PM, expected an hour within the range [0..12]");
            }
            if (!pm && hour == 12) {
                hour = 0;
            } else if (pm && hour != 12) {
                hour += 12;
            }
        }
        long days = yearDay > 0 && !monthOrDay ? Dates.checkDays((long) Dates.makeDate(year, 1, 1) + yearDay - 1)
                : Dates.makeDate(year, month, day);
        return Dates.timestamp(days, hour * Dates.MICROS_PER_HOUR + minute * Dates.MICROS_PER_MINUTE
                + second * Dates.MICROS_PER_SECOND + micros);
    }

    /**
     * Finds the first of some names that the string continues with at a
     * position, ignoring case.
     *
     * @return the index of the name
     */
    private static int name(Format format, String s, int pos, String[] names, String expected) {
        for (int k = 0; k < names.length; k++) {
            if (s.regionMatches(true, pos, names[k], 0, names[k].length())) {
                return k;
            }
        }
        throw mismatch(format, s, "Expected " + expected);
    }

    private static int inRange(Format format, String s, int number, int min, int max, String field) {
        if (number < min || number > max) {
            throw mismatch(format, s, field + " out of range, expected a value between " + min + " and " + max);
        }
        return number;
    }

    /** The whitespace characters of C: space, tab, line feed, vertical tab, form feed, carriage return. */
    private static boolean isSpace(char c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    private static int skipSpaces(String s, int pos) {
        while (pos < s.length() && isSpace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static ConversionException mismatch(Format format, String s, String error) {
        return new ConversionException("Could not parse string \"" + s + "\" according to format specifier \""
                + format.text() + "\": " + error);
    }
}
