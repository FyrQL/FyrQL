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

package net.fyrql.connector;

import java.util.regex.Pattern;

/**
 * Patterns of names of files: {@code *} stands for any characters,
 * {@code ?} for one, everything else for itself. For
 * {@link FileStore#list} a location may have a pattern only in its last
 * part, the name of the file; for {@link FileStore#listTree} in any part,
 * and a part {@code **} stands for any number of directories, also none.
 */
public final class Globs {

    private Globs() {
    }

    /** Tells whether a text has {@code *} or {@code ?}. */
    public static boolean isPattern(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }

    /** Returns the index of the last {@code /} or {@code \} of a location, or {@code -1}. */
    public static int lastSeparator(String location) {
        return Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
    }

    /**
     * Checks that a location has a pattern at most in its last part.
     *
     * @throws IllegalArgumentException if it has one before
     */
    public static void checkPattern(String location) {
        if (isPattern(location.substring(0, lastSeparator(location) + 1))) {
            throw new IllegalArgumentException("A pattern is only supported in the name of a file: " + location);
        }
    }

    /**
     * Returns the index at which the first part of a location with a
     * pattern starts, or {@code -1} if it has none; the part before is the
     * directory under which the files are looked for.
     */
    public static int patternStart(String location) {
        int star = location.indexOf('*');
        int question = location.indexOf('?');
        int first = star < 0 ? question : question < 0 ? star : Math.min(star, question);
        return first < 0 ? -1 : lastSeparator(location.substring(0, first)) + 1;
    }

    /**
     * Checks that a location has {@code **} only as a whole part, between
     * separators or at the end.
     *
     * @throws IllegalArgumentException if a part has it besides other characters
     */
    public static void checkTreePattern(String location) {
        for (String part : location.split("[/\\\\]", -1)) {
            if (part.contains("**") && !part.equals("**")) {
                throw new IllegalArgumentException("The pattern ** must be a whole part of a location: " + location);
            }
        }
    }

    /** Turns a pattern of names into a regular expression that matches the same names. */
    public static Pattern regex(String pattern) {
        StringBuilder regex = new StringBuilder();
        append(regex, pattern, ".*", ".");
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * Turns a pattern of relative paths, with parts separated by {@code /}
     * or {@code \}, into a regular expression that matches the same paths
     * written with {@code /}: {@code *} and {@code ?} stay within a part, and
     * a part {@code **} stands for any number of directories, also none, or
     * as the last part for any path.
     */
    public static Pattern pathRegex(String pattern) {
        String[] parts = pattern.split("[/\\\\]", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            if (parts[i].equals("**")) {
                regex.append(last ? ".*" : "(?:[^/]*/)*");
            } else {
                append(regex, parts[i], "[^/]*", "[^/]");
                if (!last) {
                    regex.append('/');
                }
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static void append(StringBuilder regex, String pattern, String any, String one) {
        StringBuilder literal = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? any : one);
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
    }
}
