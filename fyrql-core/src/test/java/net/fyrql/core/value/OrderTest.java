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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Strings in the order of their UTF-8 bytes, also with surrogates with and without their other half. */
class OrderTest {

    /** A smiling face, U+1F600, a pair of surrogates. */
    private static final String FACE = "\uD83D\uDE00";

    /** The chars of the random strings: around the bounds of the lengths in UTF-8 and the surrogates. */
    private static final char[] ALPHABET = {'?', 'a', 'z', '\u007F', '\u0080', '\u00E9', '\u07FF', '\u0800',
        '\uD7FF', '\uD800', '\uD83D', '\uDBFF', '\uDC00', '\uDE00', '\uDFFF', '\uE000', '\uFFFD', '\uFFFF'};

    /** The order of the bytes, as {@link Order} compared strings before. */
    private static int bytes(String a, String b) {
        return Integer.signum(Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)));
    }

    private static int order(String a, String b) {
        return Integer.signum(Order.compare(a, b, DataType.VARCHAR));
    }

    private static void assertOrder(int expected, String a, String b) {
        String what = escaped(a) + " against " + escaped(b);
        assertEquals(expected, bytes(a, b), what + " by bytes");
        assertEquals(expected, order(a, b), what);
        assertEquals(-expected, order(b, a), what + " the other way");
    }

    private static String escaped(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            out.append(c >= ' ' && c < 0x7F ? String.valueOf(c) : String.format("\\u%04X", (int) c));
        }
        return out.append('"').toString();
    }

    @Test
    void stringsCompareAsTheirBytes() {
        assertOrder(0, "", "");
        assertOrder(-1, "", "a");
        assertOrder(-1, "ab", "abc");
        assertOrder(-1, "abc", "abd");
        assertOrder(1, "b", "abc");
        assertOrder(-1, "z", "\u00E4");
        assertOrder(-1, "\u00E4", "\u0800");
        // UTF-16 puts these after the face, UTF-8 before it.
        assertOrder(-1, "\uFFFF", FACE);
        assertOrder(-1, "\uE000", FACE);
        assertOrder(-1, "x\uFFFF", "x" + FACE);
        assertOrder(1, FACE, "\uD7FF");
        assertOrder(-1, FACE, FACE + "a");
        // A surrogate without its other half is a question mark.
        assertOrder(0, "\uD800x", "?x");
        assertOrder(0, "a\uDC00b", "a?b");
        assertOrder(0, "ab\uD83D", "ab?");
        assertOrder(-1, "\uD83D", FACE);
        assertOrder(-1, "ab\uD83D", "ab" + FACE);
        assertOrder(-1, "ab\uD83D", "ab\uD83Dz");
        assertOrder(0, "ab\uDE00", "ab\uD83D");
        assertOrder(-1, "\uDE00", "a");
    }

    @Test
    void randomStringsCompareAsTheirBytes() {
        SplittableRandom random = new SplittableRandom(20261025);
        for (int i = 0; i < 2_000_000; i++) {
            String prefix = random(random, random.nextInt(4));
            String a = prefix + random(random, random.nextInt(4));
            String b = random.nextInt(8) == 0 ? a : prefix + random(random, random.nextInt(4));
            int expected = bytes(a, b);
            if (order(a, b) != expected || order(b, a) != -expected) {
                assertEquals(expected, order(a, b), escaped(a) + " against " + escaped(b));
                assertEquals(-expected, order(b, a), escaped(b) + " against " + escaped(a));
            }
        }
    }

    @Test
    void nullsSortLast() {
        for (List<String> pair : List.of(List.of("a", "b"), List.of(FACE, "\uFFFF"))) {
            assertEquals(1, Integer.signum(Order.compareNullable(null, pair.get(0), DataType.VARCHAR)));
            assertEquals(-1, Integer.signum(Order.compareNullable(pair.get(1), null, DataType.VARCHAR)));
        }
        assertEquals(0, Order.compareNullable(null, null, DataType.VARCHAR));
    }

    private static String random(SplittableRandom random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
