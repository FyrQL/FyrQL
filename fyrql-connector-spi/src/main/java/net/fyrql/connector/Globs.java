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
 * {@code ?} for one, everything else for itself. A location may have a
 * pattern only in its last part, the name of the file.
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

    /** Turns a pattern of names into a regular expression that matches the same names. */
    public static Pattern regex(String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
