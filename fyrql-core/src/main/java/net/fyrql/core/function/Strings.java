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

package net.fyrql.core.function;

/**
 * String operations on code points, as DuckDB counts characters: a
 * character outside the Basic Multilingual Plane is one character, not two.
 * The arguments are never {@code null}.
 */
public final class Strings {

    /** The largest offset and length that DuckDB accepts in {@code substring}. */
    private static final long MAX_SUBSTRING = 4_294_967_295L;

    private Strings() {
    }

    /** Returns the number of code points. */
    public static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** {@code substring(s, start)}: the rest of the string from the start, see {@link #substring(String, long, long)}. */
    public static String substring(String s, long start) {
        return substring(s, start, MAX_SUBSTRING);
    }

    /**
     * Returns a substring by code points, 1-based, as DuckDB computes it: a
     * negative start counts from the end, a start of 0 begins at the first
     * character but shortens the length by one, and a negative length takes
     * the characters before the start. The range is computed first and cut
     * to the string after, so that a range before or after the string is
     * empty. An offset or length beyond 32 bits is an error.
     */
    public static String substring(String s, long start, long length) {
        if (start > MAX_SUBSTRING || start < -MAX_SUBSTRING - 1) {
            throw new ArithmeticException("Substring offset outside of supported range: " + start);
        }
        if (length > MAX_SUBSTRING) {
            throw new ArithmeticException("Substring length outside of supported range: " + length);
        }
        if (length == 0) {
            return "";
        }
        long n = length(s);
        long from;
        if (start > 0) {
            from = start - 1;
        } else if (start < 0) {
            from = n + start;
        } else {
            from = 0;
            length--;
        }
        long to;
        if (length < 0) {
            to = from;
            from = from + length;
        } else {
            to = from + length;
        }
        from = Math.min(Math.max(from, 0), n);
        to = Math.min(Math.max(to, 0), n);
        if (to <= from) {
            return "";
        }
        return s.substring(s.offsetByCodePoints(0, (int) from), s.offsetByCodePoints(0, (int) to));
    }

    /**
     * Converts each code point with {@link Character#toUpperCase(int)}; the
     * sharp s becomes the capital sharp s, as in DuckDB.
     */
    public static String upper(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> b.appendCodePoint(cp == 0xDF ? 0x1E9E : Character.toUpperCase(cp)));
        return b.toString();
    }

    /** Converts each code point with {@link Character#toLowerCase(int)}. */
    public static String lower(String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> b.appendCodePoint(Character.toLowerCase(cp)));
        return b.toString();
    }

    /** Reverses the code points. */
    public static String reverse(String s) {
        int[] cps = s.codePoints().toArray();
        StringBuilder b = new StringBuilder(s.length());
        for (int i = cps.length - 1; i >= 0; i--) {
            b.appendCodePoint(cps[i]);
        }
        return b.toString();
    }

    /** Removes the given code points from the left and/or the right end. */
    public static String trim(String s, String characters, boolean left, boolean right) {
        if (characters.isEmpty()) {
            return s;
        }
        int[] cps = s.codePoints().toArray();
        int from = 0;
        int to = cps.length;
        if (left) {
            while (from < to && characters.indexOf(new String(Character.toChars(cps[from]))) >= 0) {
                from++;
            }
        }
        if (right) {
            while (to > from && characters.indexOf(new String(Character.toChars(cps[to - 1]))) >= 0) {
                to--;
            }
        }
        return new String(cps, from, to - from);
    }

    /** Returns the 1-based position in code points of a substring, 0 if absent; an empty substring is at 1. */
    public static long position(String s, String sub) {
        int i = s.indexOf(sub);
        return i < 0 ? 0 : s.codePointCount(0, i) + 1;
    }

    /** The first {@code count} code points; a negative count drops that many from the end. */
    public static String left(String s, long count) {
        if (count > MAX_SUBSTRING) {
            throw new ArithmeticException("Substring length outside of supported range: " + count);
        }
        int n = length(s);
        long keep = count >= 0 ? Math.min(count, n) : Math.max(n + count, 0);
        return s.substring(0, s.offsetByCodePoints(0, (int) keep));
    }

    /**
     * The last {@code count} code points; a negative count drops that many
     * from the start. Unlike {@link #left}, any count is allowed, as in DuckDB.
     */
    public static String right(String s, long count) {
        int n = length(s);
        long keep = count >= 0 ? Math.min(count, n) : Math.max(n + count, 0);
        return s.substring(s.offsetByCodePoints(0, (int) (n - keep)));
    }

    /** Repeats a string; a count of zero or less gives the empty string. */
    public static String repeat(String s, long count) {
        if (count <= 0 || s.isEmpty()) {
            return "";
        }
        if (count * (long) s.length() > Integer.MAX_VALUE - 8) {
            throw new ArithmeticException("Result of repeat is too long: " + count + " times " + s.length() + " chars");
        }
        return s.repeat((int) count);
    }

    /**
     * Pads to a length in code points on the left or the right, cycling
     * through the padding; a string longer than the length is cut to it.
     *
     * @throws IllegalArgumentException if padding is needed and the padding is empty
     */
    public static String pad(String s, int length, String padding, boolean left, String functionName) {
        int n = length(s);
        if (length <= n) {
            return left(s, Math.max(length, 0));
        }
        if (padding.isEmpty()) {
            throw new IllegalArgumentException("Insufficient padding in " + functionName.toUpperCase(java.util.Locale.ROOT));
        }
        int[] pad = padding.codePoints().toArray();
        StringBuilder fill = new StringBuilder();
        for (int i = 0; i < length - n; i++) {
            fill.appendCodePoint(pad[i % pad.length]);
        }
        return left ? fill + s : s + fill;
    }

    /**
     * The {@code index}-th part of a string split by a separator, counted
     * from 1, from the end if negative; the empty string outside the range
     * or for an index of 0. An empty separator splits into characters.
     */
    public static String splitPart(String s, String separator, long index) {
        if (index == 0) {
            return "";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (separator.isEmpty()) {
            s.codePoints().forEach(cp -> parts.add(new String(Character.toChars(cp))));
            if (parts.isEmpty()) {
                parts.add("");
            }
        } else {
            int from = 0;
            while (true) {
                int at = s.indexOf(separator, from);
                if (at < 0) {
                    parts.add(s.substring(from));
                    break;
                }
                parts.add(s.substring(from, at));
                from = at + separator.length();
            }
        }
        long i = index > 0 ? index - 1 : parts.size() + index;
        return i >= 0 && i < parts.size() ? parts.get((int) i) : "";
    }

    /**
     * Matches a string against a {@code LIKE} pattern on code points:
     * {@code %} matches any sequence, also an empty one, {@code _} exactly
     * one code point, every other code point itself. There is no escape
     * character, as in DuckDB without an {@code ESCAPE} clause.
     */
    public static boolean like(String s, String pattern) {
        int[] text = s.codePoints().toArray();
        int[] pat = pattern.codePoints().toArray();
        return like(text, 0, pat, 0);
    }

    private static boolean like(int[] s, int si, int[] p, int pi) {
        while (pi < p.length) {
            int pc = p[pi];
            if (pc == '%') {
                // Collapse consecutive wildcards, then try every split.
                while (pi < p.length && p[pi] == '%') {
                    pi++;
                }
                if (pi == p.length) {
                    return true;
                }
                for (int k = si; k <= s.length; k++) {
                    if (like(s, k, p, pi)) {
                        return true;
                    }
                }
                return false;
            }
            if (si >= s.length) {
                return false;
            }
            if (pc == '_' || s[si] == pc) {
                si++;
                pi++;
            } else {
                return false;
            }
        }
        return si == s.length;
    }
}
