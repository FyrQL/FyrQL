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

package net.fyrql.core.vector;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Keys;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dictionary form of {@link BytesVector} against the flat form with the same values. */
class DictionaryVectorTest {

    private static final String[] WORDS = {"", "a", "b", "ab", "abc", "R", "N", "A", "O", "F", "MAIL", "SHIP",
        "TRUCK", "ä", "€", "𝄞", "zz", "DELIVER IN PERSON"};

    @Test
    void theDictionaryFormTakesArraysWithoutCopying() {
        byte[] data = "xxabcde".getBytes(StandardCharsets.UTF_8);
        int[] offsets = {2, 4, 4, 7};
        BytesVector.Dictionary d = BytesVector.Dictionary.of(data, offsets);
        assertTrue(data == d.data());
        assertEquals(3, d.size());
        assertEquals("ab", d.getString(0));
        assertEquals("", d.getString(1));
        assertEquals(4, d.start(2));
        assertEquals(7, d.end(2));
        assertEquals(3, d.length(2));
        assertArrayEquals("cde".getBytes(StandardCharsets.UTF_8), d.getBytes(2));
        int[] codes = {2, 0, 1, 2};
        BytesVector v = BytesVector.dictionary(DataType.VARCHAR, codes, d, new boolean[] {false, true, false, false});
        assertTrue(v.hasDictionary());
        assertTrue(d == v.dictionary());
        assertTrue(data == v.data());
        assertEquals(4, v.size());
        assertEquals(2, v.code(0));
        assertEquals("cde", v.getString(0));
        assertTrue(v.isNull(1));
        assertNull(v.get(1));
        assertEquals("", v.get(2));
        assertEquals(4, v.start(3));
        assertEquals(7, v.end(3));
        assertTrue(v.hasNulls());
        assertFalse(BytesVector.dictionary(DataType.BLOB, codes, d, new boolean[4]).hasNulls());
        assertEquals(0, BytesVector.dictionary(DataType.VARCHAR, new int[0],
                BytesVector.Dictionary.of(new byte[0], new int[] {0}), null).size());

        BytesVector flat = BytesVector.ofStrings("a");
        assertFalse(flat.hasDictionary());
        assertNull(flat.dictionary());
        assertThrows(IllegalStateException.class, () -> flat.code(0));

        assertEquals("Offsets outside of 7 bytes", assertThrows(IllegalArgumentException.class,
                () -> BytesVector.Dictionary.of(data, new int[] {0, 8})).getMessage());
        assertEquals("The offset of value 2 is before that of value 1", assertThrows(IllegalArgumentException.class,
                () -> BytesVector.Dictionary.of(data, new int[] {0, 3, 2})).getMessage());
        assertThrows(IllegalArgumentException.class, () -> BytesVector.Dictionary.of(data, new int[0]));
        // Every code must lie in the dictionary, that of a NULL too.
        assertEquals("The code 3 of row 1 is beyond the 3 values of the dictionary",
                assertThrows(IllegalArgumentException.class, () -> BytesVector.dictionary(DataType.VARCHAR,
                        new int[] {0, 3}, d, new boolean[] {false, true})).getMessage());
        assertEquals("The code -1 of row 0 is beyond the 3 values of the dictionary",
                assertThrows(IllegalArgumentException.class, () -> BytesVector.dictionary(DataType.VARCHAR,
                        new int[] {-1}, d, null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> BytesVector.dictionary(DataType.VARCHAR, codes, d,
                new boolean[3]));
        assertThrows(IllegalArgumentException.class, () -> BytesVector.dictionary(DataType.BIGINT, codes, d, null));
    }

    @Test
    void bothFormsAgreeInValuesHashesAndOrder() {
        SplittableRandom random = new SplittableRandom(20261024);
        for (int round = 0; round < 60; round++) {
            DataType type = round % 3 == 2 ? DataType.BLOB : DataType.VARCHAR;
            BytesVector.Dictionary d = dictionary(random);
            Pair a = pair(type, d, random, round);
            Pair b = pair(type, d, random, round + 1);
            // Another dictionary with the same values, so that the codes cannot decide.
            Pair c = pair(type, copy(d), random, round + 2);
            String what = "round " + round;
            assertSameValues(a.flat, a.coded, what);
            for (int i = 0; i < 300 && a.flat.size() > 0; i++) {
                Pair[] others = {a, b, c};
                Pair o = others[random.nextInt(others.length)];
                if (o.flat.size() == 0) {
                    continue;
                }
                int x = random.nextInt(a.flat.size());
                int y = random.nextInt(o.flat.size());
                if (a.flat.isNull(x) || o.flat.isNull(y)) {
                    assertEquals(Vectors.compareNullable(a.flat, x, o.flat, y),
                            Vectors.compareNullable(a.coded, x, o.coded, y), what);
                    continue;
                }
                int expected = Integer.signum(BytesVector.compare(a.flat, x, o.flat, y));
                boolean equal = BytesVector.equal(a.flat, x, o.flat, y);
                BytesVector[] lefts = {a.flat, a.coded};
                BytesVector[] rights = {o.flat, o.coded};
                for (BytesVector l : lefts) {
                    for (BytesVector r : rights) {
                        String at = what + ", rows " + x + " and " + y;
                        assertEquals(expected, Integer.signum(BytesVector.compare(l, x, r, y)), at);
                        assertEquals(-expected, Integer.signum(BytesVector.compare(r, y, l, x)), at);
                        assertEquals(equal, BytesVector.equal(l, x, r, y), at);
                        assertEquals(expected, Integer.signum(Vectors.compare(l, x, r, y)), at);
                        if (equal) {
                            assertEquals(l.hash(x), r.hash(y), at);
                        }
                    }
                }
            }
        }
    }

    @Test
    void selectAndGatherKeepTheDictionary() {
        SplittableRandom random = new SplittableRandom(20261025);
        for (int round = 0; round < 40; round++) {
            BytesVector.Dictionary d = dictionary(random);
            Pair p = pair(DataType.VARCHAR, d, random, round);
            if (p.flat.size() == 0) {
                continue;
            }
            int[] rows = new int[random.nextInt(50)];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = random.nextInt(p.flat.size());
            }
            BytesVector selected = p.coded.select(rows);
            assertTrue(selected.hasDictionary());
            assertTrue(d == selected.dictionary());
            assertSameValues(p.flat.select(rows), selected, "select of round " + round);
            int[] gathered = rows.clone();
            for (int i = 0; i < gathered.length; i++) {
                if (random.nextInt(3) == 0) {
                    gathered[i] = -1;
                }
            }
            Vector g = Vectors.gather(p.coded, gathered);
            assertTrue(((BytesVector) g).hasDictionary());
            assertSameValues((BytesVector) Vectors.gather(p.flat, gathered), (BytesVector) g, "gather of round "
                    + round);
            for (int i = 0; i < gathered.length; i++) {
                assertEquals(gathered[i] < 0 ? 0 : Vectors.hash(p.flat, gathered[i]), Vectors.hash(g, i));
            }
        }
        // Without rows the dictionary has no code for a NULL from -1, so the result is flat.
        BytesVector empty = BytesVector.dictionary(DataType.VARCHAR, new int[0],
                BytesVector.Dictionary.of(new byte[0], new int[] {0}), null);
        Vector none = Vectors.gather(empty, new int[] {-1, -1});
        assertTrue(none.isNull(0) && none.isNull(1));
        assertFalse(((BytesVector) none).hasDictionary());
    }

    @Test
    void concatAndGatherOfPartsKeepASharedDictionaryAndAreFlatOtherwise() {
        SplittableRandom random = new SplittableRandom(20261026);
        for (int round = 0; round < 40; round++) {
            BytesVector.Dictionary d = dictionary(random);
            int count = 1 + random.nextInt(4);
            List<Pair> same = new ArrayList<>();
            List<Pair> mixed = new ArrayList<>();
            for (int k = 0; k < count + 1; k++) {
                same.add(pair(DataType.VARCHAR, d, random, round + k));
                mixed.add(pair(DataType.VARCHAR, k % 2 == 0 ? d : copy(d), random, round + k));
            }
            check(same, true, random, "shared, round " + round);
            check(mixed, false, random, "different, round " + round);
            // A flat part among dictionaries makes the result flat.
            List<Pair> withFlat = new ArrayList<>(same);
            Pair f = same.get(0);
            withFlat.add(new Pair(f.flat, f.flat));
            check(withFlat, false, random, "with a flat part, round " + round);
        }
    }

    private static void check(List<Pair> parts, boolean shared, SplittableRandom random, String what) {
        List<Vector> flats = new ArrayList<>();
        List<Vector> codeds = new ArrayList<>();
        for (Pair p : parts) {
            flats.add(p.flat);
            codeds.add(p.coded);
        }
        BytesVector all = (BytesVector) Vectors.concat(DataType.VARCHAR, codeds);
        assertEquals(shared, all.hasDictionary(), what);
        assertSameValues((BytesVector) Vectors.concat(DataType.VARCHAR, flats), all, "concat, " + what);
        int total = 5 + random.nextInt(60);
        int[] partOf = new int[total];
        int[] rowOf = new int[total];
        int n = 0;
        while (n < total) {
            int p = random.nextInt(parts.size());
            if (parts.get(p).flat.size() > 0) {
                partOf[n] = p;
                rowOf[n++] = random.nextInt(parts.get(p).flat.size());
            } else if (parts.stream().allMatch(x -> x.flat.size() == 0)) {
                return;
            }
        }
        int from = random.nextInt(total);
        int to = from + 1 + random.nextInt(total - from);
        BytesVector g = (BytesVector) Vectors.gather(DataType.VARCHAR, codeds, partOf, rowOf, from, to);
        assertEquals(shared, g.hasDictionary(), what);
        assertSameValues((BytesVector) Vectors.gather(DataType.VARCHAR, flats, partOf, rowOf, from, to), g,
                "gather, " + what);
    }

    /** Asserts that a vector in either form holds the values, NULLs and hashes of a flat one. */
    private static void assertSameValues(BytesVector flat, BytesVector other, String what) {
        assertEquals(flat.size(), other.size(), what);
        assertEquals(flat.hasNulls(), other.hasNulls(), what);
        for (int r = 0; r < flat.size(); r++) {
            String at = what + ", row " + r;
            assertEquals(flat.isNull(r), other.isNull(r), at);
            assertTrue(Keys.equalObjects(Keys.canonical(flat.get(r)), Keys.canonical(other.get(r))), at);
            assertEquals(Vectors.hash(flat, r), Vectors.hash(other, r), at);
            if (!flat.isNull(r)) {
                assertEquals(flat.length(r), other.length(r), at);
                assertEquals(flat.hash(r), other.hash(r), at);
                assertArrayEquals(flat.getBytes(r), other.getBytes(r), at);
                assertEquals(flat.getString(r), other.getString(r), at);
                assertTrue(Arrays.equals(flat.data(), flat.start(r), flat.end(r), other.data(), other.start(r),
                        other.end(r)), at);
            } else if (!other.hasDictionary()) {
                assertEquals(0, other.length(r), at + ": a NULL made flat has no bytes");
            }
        }
    }

    /** A vector in the dictionary form and the flat vector with the same values. */
    private record Pair(BytesVector flat, BytesVector coded) {
    }

    private static Pair pair(DataType type, BytesVector.Dictionary d, SplittableRandom random, int round) {
        int n = random.nextInt(4) == 0 ? random.nextInt(3) : random.nextInt(120);
        int[] codes = new int[n];
        boolean[] nulls = round % 4 == 0 ? null : new boolean[n];
        int share = round % 4 == 3 ? 100 : random.nextInt(60);
        BytesVector.Builder b = new BytesVector.Builder(type, n);
        for (int i = 0; i < n; i++) {
            codes[i] = random.nextInt(d.size());
            if (nulls != null && random.nextInt(100) < share) {
                nulls[i] = true;
                b.appendNull();
            } else {
                b.appendBytes(d.data(), d.start(codes[i]), d.length(codes[i]));
            }
        }
        return new Pair(b.build(), BytesVector.dictionary(type, codes, d, nulls));
    }

    /** A dictionary of some words, with the same word twice at times, its bytes not from the start of the buffer. */
    private static BytesVector.Dictionary dictionary(SplittableRandom random) {
        int size = 1 + random.nextInt(WORDS.length + 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'#', '#'});
        int[] offsets = new int[size + 1];
        offsets[0] = 2;
        for (int c = 0; c < size; c++) {
            out.writeBytes(WORDS[random.nextInt(WORDS.length)].getBytes(StandardCharsets.UTF_8));
            offsets[c + 1] = out.size();
        }
        out.writeBytes(new byte[] {'#'});
        return BytesVector.Dictionary.of(out.toByteArray(), offsets);
    }

    private static BytesVector.Dictionary copy(BytesVector.Dictionary d) {
        return BytesVector.Dictionary.of(d.data().clone(), d.offsets.clone());
    }
}
