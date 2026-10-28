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

import net.fyrql.connector.Batches;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The rows of one side of a join on disk, cut into
 * {@value Partitions#PARTITIONS} partitions by the hashes of their keys,
 * see {@link Partitions#partition}. Each row carries its hash and its place
 * among the rows of one thread, see {@link Partitions#place}, in two last
 * columns. Each thread that writes has a {@link Writer} with files of its
 * own, whose rows are each in the order of their places; a partition is
 * read in the order of the places across the files of all writers, so that
 * its rows come as one thread would have written them. A partition that
 * does not fit is cut into {@value Partitions#PARTITIONS} smaller ones by
 * more of the bits of the same hashes, see {@link #refine}. Closing deletes
 * every file.
 */
final class JoinPartitions implements AutoCloseable {

    /** The rows of a partition a writer holds before it writes them. */
    static final int BUFFERED_ROWS = 256;

    /** The bytes of the rows of a partition from which on a writer writes them. */
    static final long BUFFERED_BYTES = 16 * 1024;

    /**
     * A partition: the rows whose hash is in {@code index} of {@code count}
     * partitions, in files whose rows are each in the order of their places.
     *
     * @param rows    the rows of the partition
     * @param oneHash whether all rows have the same hash, so that no cut divides them
     */
    record Work(int index, int count, List<SpillFile> files, long rows, boolean oneHash) {
    }

    /** Rows of a partition in the order of their places, with their hashes and places. */
    record Chunk(Batch rows, int[] hashes, long[] places) {
    }

    private final String label;
    private final String what;
    private final Schema schema;
    private final Schema fileSchema;
    private final SpillDirectory spill;
    private final QueryContext context;
    private final List<Writer> writers = Collections.synchronizedList(new ArrayList<>());
    private final List<SpillFile> files = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param label   the label of the join, under which the files count
     * @param what    what the files hold, e.g. {@code build}
     * @param schema  the schema of the rows, without hashes and places
     */
    JoinPartitions(String label, String what, Schema schema, SpillDirectory spill, QueryContext context) {
        this.label = label;
        this.what = what;
        this.schema = schema;
        this.fileSchema = schema.concat(Schema.of(new Schema.Field("$hash", DataType.INTEGER, false),
                new Schema.Field("$place", DataType.BIGINT, false)));
        this.spill = spill;
        this.context = context;
    }

    /** Returns the schema of the rows, without hashes and places. */
    Schema schema() {
        return schema;
    }

    /** Returns a writer for one thread, which writes files of its own. */
    Writer writer() {
        Writer w = new Writer();
        writers.add(w);
        return w;
    }

    /**
     * Finishes the files of all writers and returns the partitions, each
     * with the files of every writer that wrote rows to it.
     */
    List<Work> partitions() {
        List<Work> works = new ArrayList<>(Partitions.PARTITIONS);
        for (int p = 0; p < Partitions.PARTITIONS; p++) {
            List<SpillFile> of = new ArrayList<>();
            long rows = 0;
            boolean oneHash = true;
            Integer hash = null;
            for (Writer w : writers) {
                w.flush(p);
                if (w.files[p] == null) {
                    continue;
                }
                w.files[p].finish();
                of.add(w.files[p]);
                rows += w.rows[p];
                oneHash &= w.oneHash[p] && (hash == null || hash == w.firstHash[p]);
                hash = w.firstHash[p];
            }
            works.add(new Work(p, Partitions.PARTITIONS, of, rows, oneHash));
        }
        return works;
    }

    /**
     * Finishes the files of the rows that all writers put aside, see
     * {@link Writer#aside}, and returns them as a partition of no index.
     */
    Work aside() {
        List<SpillFile> of = new ArrayList<>();
        long rows = 0;
        for (Writer w : writers) {
            w.flush(Partitions.PARTITIONS);
            if (w.aside != null) {
                w.aside.finish();
                of.add(w.aside);
                rows += w.asideRows;
            }
        }
        return new Work(-1, 0, of, rows, false);
    }

    /** Returns a reader of the rows of a partition, in the order of their places. */
    Reader read(Work w) {
        return new Reader(w);
    }

    /**
     * Cuts a partition into {@value Partitions#PARTITIONS} smaller ones: its
     * rows, read in the order of their places, go to a new file for each
     * smaller partition, in this order. The files of the partition are
     * deleted.
     */
    List<Work> refine(Work w) {
        int count = w.count() * Partitions.PARTITIONS;
        Writer finer = new Writer();
        try (Reader r = read(w)) {
            for (Chunk c = r.next(); c != null; c = r.next()) {
                finer.scatter(c.rows(), c.hashes(), c.places(), count, w.index() * Partitions.PARTITIONS);
            }
        }
        close(w);
        List<Work> works = new ArrayList<>(Partitions.PARTITIONS);
        for (int s = 0; s < Partitions.PARTITIONS; s++) {
            finer.flush(s);
            SpillFile f = finer.files[s];
            if (f != null) {
                f.finish();
            }
            works.add(new Work(w.index() * Partitions.PARTITIONS + s, count, f == null ? List.of() : List.of(f),
                    finer.rows[s], finer.oneHash[s]));
        }
        return works;
    }

    /** Deletes the files of a partition. */
    void close(Work w) {
        for (SpillFile f : w.files()) {
            f.close();
        }
    }

    /** Deletes every file. */
    @Override
    public void close() {
        synchronized (files) {
            for (SpillFile f : files) {
                f.close();
            }
            files.clear();
        }
    }

    private SpillFile newFile(String name) {
        SpillFile f = spill.create(label, name, fileSchema, context);
        files.add(f);
        return f;
    }

    /**
     * Writes the rows of one thread to files of its own, one for each
     * partition, created when a row goes there. The rows of a partition are
     * written once there are {@value #BUFFERED_ROWS} of them or they take
     * {@value #BUFFERED_BYTES} bytes, so that the batches of a file are not
     * too small, as each costs room beyond its rows when it is read back.
     */
    final class Writer {
        private final SpillFile[] files = new SpillFile[Partitions.PARTITIONS];
        private final long[] rows = new long[Partitions.PARTITIONS];
        private final int[] firstHash = new int[Partitions.PARTITIONS];
        private final boolean[] oneHash = new boolean[Partitions.PARTITIONS];
        private SpillFile aside;
        private long asideRows;
        /** The rows not yet written, of each partition and then of those put aside. */
        private final List<List<Batch>> buffered = new ArrayList<>();
        private final int[] bufferedRows = new int[Partitions.PARTITIONS + 1];
        private final long[] bufferedBytes = new long[Partitions.PARTITIONS + 1];

        private Writer() {
            Arrays.fill(oneHash, true);
            for (int p = 0; p <= Partitions.PARTITIONS; p++) {
                buffered.add(new ArrayList<>());
            }
        }

        /** Adds rows of the file schema to those of a partition, or of those put aside, and writes them in time. */
        private void buffer(int p, Batch b) {
            buffered.get(p).add(b);
            bufferedRows[p] += b.rowCount();
            bufferedBytes[p] += Batches.estimateBytes(b);
            if (bufferedRows[p] >= BUFFERED_ROWS || bufferedBytes[p] >= BUFFERED_BYTES) {
                flush(p);
            }
        }

        /** Writes the rows of a partition, or of those put aside, not yet written. */
        private void flush(int p) {
            List<Batch> batches = buffered.get(p);
            if (batches.isEmpty()) {
                return;
            }
            Batch b = batches.size() == 1 ? batches.get(0) : Batches.concat(fileSchema, batches);
            (p == Partitions.PARTITIONS ? aside : files[p]).append(b);
            batches.clear();
            bufferedRows[p] = 0;
            bufferedBytes[p] = 0;
        }

        /**
         * Puts rows aside, in no partition, such as rows with a {@code NULL}
         * key, which match nothing; see {@link JoinPartitions#aside()}.
         */
        void aside(Batch rows, long[] places) {
            int n = rows.rowCount();
            if (n == 0) {
                return;
            }
            if (aside == null) {
                aside = newFile(what + "-aside");
            }
            List<Vector> columns = new ArrayList<>(rows.columns());
            columns.add(IntVector.of(new int[n]));
            columns.add(BigIntVector.of(places));
            buffer(Partitions.PARTITIONS, new Batch(fileSchema, n, columns));
            asideRows += n;
        }

        /**
         * Scatters rows into the partitions by their hashes; the rows of
         * each writer come in the order of their places.
         *
         * @param rows   rows of {@link #schema()}
         * @param hashes the hashes of the keys of the rows
         * @param places the places of the rows
         */
        void scatter(Batch rows, int[] hashes, long[] places) {
            scatter(rows, hashes, places, Partitions.PARTITIONS, 0);
        }

        /** Scatters rows into the partitions {@code first} on of {@code count}, by their hashes. */
        private void scatter(Batch rows, int[] hashes, long[] places, int count, int first) {
            int n = rows.rowCount();
            int[] partitionOf = new int[n];
            int[] start = new int[Partitions.PARTITIONS + 1];
            for (int r = 0; r < n; r++) {
                int p = Partitions.partition(hashes[r], count) - first;
                partitionOf[r] = p;
                start[p + 1]++;
            }
            for (int p = 0; p < Partitions.PARTITIONS; p++) {
                start[p + 1] += start[p];
            }
            int[] byPartition = new int[n];
            int[] next = Arrays.copyOf(start, Partitions.PARTITIONS);
            for (int r = 0; r < n; r++) {
                byPartition[next[partitionOf[r]]++] = r;
            }
            for (int p = 0; p < Partitions.PARTITIONS; p++) {
                int m = start[p + 1] - start[p];
                if (m == 0) {
                    continue;
                }
                int[] own = Arrays.copyOfRange(byPartition, start[p], start[p + 1]);
                int[] h = new int[m];
                long[] at = new long[m];
                for (int i = 0; i < m; i++) {
                    h[i] = hashes[own[i]];
                    at[i] = places[own[i]];
                }
                if (files[p] == null) {
                    files[p] = newFile(what + "-" + (first + p) + "-of-" + count);
                    firstHash[p] = h[0];
                }
                for (int i = 0; i < m && oneHash[p]; i++) {
                    oneHash[p] = h[i] == firstHash[p];
                }
                List<Vector> columns = new ArrayList<>((m == n ? rows : rows.select(own)).columns());
                columns.add(IntVector.of(h));
                columns.add(BigIntVector.of(at));
                buffer(p, new Batch(fileSchema, m, columns));
                this.rows[p] += m;
            }
        }
    }

    /** Reads the rows of a partition from its files, in the order of their places. */
    final class Reader implements AutoCloseable {
        private final List<Cursor> cursors = new ArrayList<>();

        private Reader(Work w) {
            for (SpillFile f : w.files()) {
                Cursor c = new Cursor(f);
                if (c.next()) {
                    cursors.add(c);
                } else {
                    c.reader.close();
                }
            }
        }

        /** Returns the next rows, or {@code null} after the last ones. */
        Chunk next() {
            if (cursors.isEmpty()) {
                return null;
            }
            long[] least = Partitions.least(cursors.size(), i -> cursors.get(i).places[cursors.get(i).at]);
            int best = (int) least[0];
            Cursor c = cursors.get(best);
            int n = c.batch.rowCount();
            int from = c.at;
            int to = from + 1;
            while (to < n && c.places[to] < least[1]) {
                to++;
            }
            Chunk chunk;
            if (from == 0 && to == n) {
                chunk = new Chunk(c.batch, c.hashes, c.places);
            } else {
                int[] rows = new int[to - from];
                for (int r = from; r < to; r++) {
                    rows[r - from] = r;
                }
                chunk = new Chunk(c.batch.select(rows), Arrays.copyOfRange(c.hashes, from, to),
                        Arrays.copyOfRange(c.places, from, to));
            }
            c.at = to;
            if (to == n && !c.next()) {
                cursors.remove(best);
            }
            return chunk;
        }

        @Override
        public void close() {
            for (Cursor c : cursors) {
                c.reader.close();
            }
            cursors.clear();
        }
    }

    /** Reads a file of a partition a batch at a time, the rows without their hashes and places. */
    private final class Cursor {
        private final SpillFile.SpillReader reader;
        Batch batch;
        int[] hashes;
        long[] places;
        int at;

        Cursor(SpillFile file) {
            this.reader = file.reader();
        }

        /** Reads the next batch; {@code false} at the end, which closes the reader. */
        boolean next() {
            Batch b = reader.next();
            if (b == null) {
                reader.close();
                batch = null;
                return false;
            }
            int n = b.rowCount();
            int k = schema.size();
            IntVector h = (IntVector) b.column(k);
            BigIntVector p = (BigIntVector) b.column(k + 1);
            hashes = new int[n];
            places = new long[n];
            for (int r = 0; r < n; r++) {
                hashes[r] = h.getInt(r);
                places[r] = p.getLong(r);
            }
            batch = new Batch(schema, n, b.columns().subList(0, k));
            at = 0;
            return true;
        }
    }
}
