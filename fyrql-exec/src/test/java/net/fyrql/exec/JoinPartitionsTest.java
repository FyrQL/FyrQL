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

package net.fyrql.exec;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partitions of a side of a join on disk: every row comes back from the
 * partition of its hash, with its hash and place, in the order of the
 * places, also when several writers wrote the rows in turns; a cut into
 * smaller partitions keeps the rows and their order; closing deletes every
 * file.
 */
class JoinPartitionsTest {

    private static final Schema SCHEMA = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR));

    @TempDir
    Path temp;

    /** A row: its number, a text or {@code NULL}. */
    private static Batch batch(int from, int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            rows.add(new Object[] {i, i % 5 == 0 ? null : "row " + i});
        }
        return Batch.ofRows(SCHEMA, rows);
    }

    /** The hash of row {@code i}: few distinct ones, so that partitions hold several rows of one hash. */
    private static int hash(int i) {
        return (i % 97) * 0x01000193;
    }

    private static int[] hashes(int from, int n) {
        int[] h = new int[n];
        for (int i = 0; i < n; i++) {
            h[i] = hash(from + i);
        }
        return h;
    }

    /** Reads a partition and checks that its rows belong to it and come in the order of their places. */
    private static List<Integer> read(JoinPartitions parts, JoinPartitions.Work w, long[] placeOf) {
        List<Integer> rows = new ArrayList<>();
        long last = -1;
        try (JoinPartitions.Reader r = parts.read(w)) {
            for (JoinPartitions.Chunk c = r.next(); c != null; c = r.next()) {
                assertEquals(SCHEMA, c.rows().schema());
                for (int i = 0; i < c.rows().rowCount(); i++) {
                    Object[] row = c.rows().row(i);
                    int k = (Integer) row[0];
                    assertEquals(hash(k), c.hashes()[i]);
                    assertEquals(w.index(), Partitions.partition(c.hashes()[i], w.count()));
                    assertEquals(placeOf[k], c.places()[i]);
                    assertTrue(c.places()[i] > last, "in the order of the places");
                    last = c.places()[i];
                    assertEquals(k % 5 == 0 ? null : "row " + k, row[1]);
                    rows.add(k);
                }
            }
        }
        assertEquals(w.rows(), rows.size());
        return rows;
    }

    @Test
    void rowsComeBackFromThePartitionOfTheirHashInTheOrderOfTheirPlaces() throws IOException {
        SpillDirectory dir = new SpillDirectory(temp);
        QueryContext context = new QueryContext();
        int n = 10_000;
        long[] placeOf = new long[n];
        try (JoinPartitions parts = new JoinPartitions("Join#1", "build", SCHEMA, dir, context)) {
            // Three writers take the morsels in turns, as the workers of a segment would.
            List<JoinPartitions.Writer> writers = List.of(parts.writer(), parts.writer(), parts.writer());
            SplittableRandom random = new SplittableRandom(7);
            int morsel = 0;
            for (int from = 0; from < n; morsel++) {
                int size = Math.min(n - from, 1 + random.nextInt(700));
                long[] places = new long[size];
                for (int i = 0; i < size; i++) {
                    places[i] = Partitions.place(morsel, i);
                    placeOf[from + i] = places[i];
                }
                writers.get(random.nextInt(writers.size())).scatter(batch(from, size), hashes(from, size), places);
                from += size;
            }
            List<JoinPartitions.Work> works = parts.partitions();
            assertEquals(Partitions.PARTITIONS, works.size());
            List<Integer> all = new ArrayList<>();
            for (JoinPartitions.Work w : works) {
                assertEquals(Partitions.PARTITIONS, w.count());
                assertTrue(w.files().size() <= writers.size());
                assertFalse(w.oneHash() && w.rows() > 0 && distinctHashes(read(parts, w, placeOf)) > 1);
                all.addAll(read(parts, w, placeOf));
            }
            all.sort(null);
            for (int i = 0; i < n; i++) {
                assertEquals(i, all.get(i));
            }
        }
        try (Stream<Path> files = Files.list(dir.path())) {
            assertEquals(0, files.count(), "closing deletes every file");
        }
        dir.close();
    }

    private static long distinctHashes(List<Integer> rows) {
        return rows.stream().map(JoinPartitionsTest::hash).distinct().count();
    }

    @Test
    void aCutKeepsTheRowsAndTheirOrder() throws IOException {
        SpillDirectory dir = new SpillDirectory(temp);
        QueryContext context = new QueryContext();
        int n = 5_000;
        long[] placeOf = new long[n];
        for (int i = 0; i < n; i++) {
            placeOf[i] = Partitions.place(0, i);
        }
        try (JoinPartitions parts = new JoinPartitions("Join#2", "probe", SCHEMA, dir, context)) {
            JoinPartitions.Writer w = parts.writer();
            for (int from = 0; from < n; from += 1000) {
                w.scatter(batch(from, 1000), hashes(from, 1000), Arrays.copyOfRange(placeOf, from, from + 1000));
            }
            JoinPartitions.Work top = parts.partitions().get(3);
            List<Integer> before = read(parts, top, placeOf);
            assertTrue(before.size() > 0);
            List<JoinPartitions.Work> finer = parts.refine(top);
            assertEquals(Partitions.PARTITIONS, finer.size());
            List<Integer> after = new ArrayList<>();
            for (int s = 0; s < finer.size(); s++) {
                JoinPartitions.Work f = finer.get(s);
                assertEquals(3 * Partitions.PARTITIONS + s, f.index());
                assertEquals(Partitions.PARTITIONS * Partitions.PARTITIONS, f.count());
                List<Integer> rows = read(parts, f, placeOf);
                assertEquals(f.oneHash() && !rows.isEmpty(), distinctHashes(rows) == 1);
                after.addAll(rows);
            }
            after.sort(null);
            List<Integer> sorted = new ArrayList<>(before);
            sorted.sort(null);
            assertEquals(sorted, after, "the cut keeps every row");
            for (JoinPartitions.Work f : finer) {
                parts.close(f);
            }
        }
        try (Stream<Path> files = Files.list(dir.path())) {
            assertEquals(0, files.count());
        }
        dir.close();
    }

    @Test
    void rowsOfOneHashAreOneHash() {
        SpillDirectory dir = new SpillDirectory(temp);
        QueryContext context = new QueryContext();
        try (JoinPartitions parts = new JoinPartitions("Join#3", "build", SCHEMA, dir, context)) {
            JoinPartitions.Writer a = parts.writer();
            JoinPartitions.Writer b = parts.writer();
            int[] same = new int[10];
            Arrays.fill(same, 42);
            long[] places = new long[10];
            for (int i = 0; i < 10; i++) {
                places[i] = i;
            }
            a.scatter(batch(0, 10), same, places);
            long[] later = Arrays.stream(places).map(p -> p + 10).toArray();
            b.scatter(batch(10, 10), same, later);
            int p = Partitions.partition(42, Partitions.PARTITIONS);
            List<JoinPartitions.Work> works = parts.partitions();
            JoinPartitions.Work w = works.get(p);
            assertEquals(20, w.rows());
            assertTrue(w.oneHash());
            List<Integer> rows = new ArrayList<>();
            try (JoinPartitions.Reader r = parts.read(w)) {
                for (JoinPartitions.Chunk c = r.next(); c != null; c = r.next()) {
                    for (Object[] row : c.rows().rows()) {
                        rows.add((Integer) row[0]);
                    }
                }
                assertNull(r.next());
            }
            int[] expected = new int[20];
            Arrays.setAll(expected, i -> i);
            assertArrayEquals(expected, rows.stream().mapToInt(Integer::intValue).toArray());
            // A second writer with another hash in the same partition is not one hash.
            JoinPartitions.Writer c = parts.writer();
            int other = 42;
            while (Partitions.partition(++other, Partitions.PARTITIONS) != p) {
                // Looks for another hash of the same partition.
            }
            c.scatter(batch(20, 1), new int[] {other}, new long[] {20});
            assertFalse(parts.partitions().get(p).oneHash());
        }
        dir.close();
    }
}
