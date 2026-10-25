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
package net.fyrql.connector.xlsx;

/**
 * The escapes of the texts of cells in Open XML: a character that XML
 * cannot hold, such as a control character, is written as
 * {@code _xHHHH_} with its UTF-16 code in hex, and a text that would read
 * as such an escape has its underscore written as {@code _x005F_}.
 */
final class Escapes {

    private Escapes() {
    }

    /** Replaces every {@code _xHHHH_} in a text by its character, as Excel reads them. */
    static String decode(String text) {
        int at = text.indexOf("_x");
        if (at < 0) {
            return text;
        }
        StringBuilder b = null;
        int copied = 0;
        while (at >= 0) {
            if (isEscape(text, at)) {
                if (b == null) {
                    b = new StringBuilder(text.length());
                }
                b.append(text, copied, at).append((char) Integer.parseInt(text, at + 2, at + 6, 16));
                copied = at + 7;
                at = text.indexOf("_x", copied);
            } else {
                at = text.indexOf("_x", at + 1);
            }
        }
        return b == null ? text : b.append(text, copied, text.length()).toString();
    }

    /**
     * Escapes the characters of a text that XML 1.0 cannot hold, and the
     * underscore of every part that would read as an escape.
     */
    static String encode(String text) {
        StringBuilder b = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && !escaped(text, i)) {
                if (b != null) {
                    b.append(c).append(text.charAt(i + 1));
                }
                i++;
                continue;
            }
            if (escaped(text, i)) {
                if (b == null) {
                    b = new StringBuilder(text.length() + 16).append(text, 0, i);
                }
                b.append("_x").append(String.format("%04X", (int) c)).append('_');
            } else if (b != null) {
                b.append(c);
            }
        }
        return b == null ? text : b.toString();
    }

    /**
     * Tells whether the character at a position is written as an escape:
     * one that XML cannot hold or that would not keep its value, a surrogate
     * without its partner, and an underscore that would start an escape
     * together with what follows it as written, which an escape of the
     * character after {@code _xHHHH} starts with an underscore as well.
     */
    private static boolean escaped(String text, int i) {
        char c = text.charAt(i);
        if (c == '_') {
            if (i + 7 > text.length() || text.charAt(i + 1) != 'x') {
                return false;
            }
            for (int k = i + 2; k < i + 6; k++) {
                if (Character.digit(text.charAt(k), 16) < 0) {
                    return false;
                }
            }
            return text.charAt(i + 6) == '_' || escaped(text, i + 6);
        }
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(text.charAt(i - 1));
        }
        return !allowed(c);
    }

    /**
     * Tells whether a character can stand in XML 1.0 as it is and keeps its
     * value when read; a carriage return does not, as XML reads it as a line
     * feed. Surrogates are checked in pairs elsewhere.
     */
    private static boolean allowed(char c) {
        return c == '\t' || c == '\n' || (c >= 0x20 && c <= 0xd7ff) || (c >= 0xe000 && c <= 0xfffd);
    }

    private static boolean isEscape(String text, int at) {
        if (at + 7 > text.length() || text.charAt(at) != '_' || text.charAt(at + 1) != 'x'
                || text.charAt(at + 6) != '_') {
            return false;
        }
        for (int i = at + 2; i < at + 6; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
