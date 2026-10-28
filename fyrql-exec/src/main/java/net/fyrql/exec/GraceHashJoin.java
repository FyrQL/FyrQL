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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.compiled.JoinTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A join whose build side went to disk, see {@link JoinSide.Spilled}: its
 * probe side goes to disk too, cut into the same partitions by the hashes
 * of its keys, and the partitions are joined one after the other, on the
 * thread of the query. The rows of a partition of the build side become a
 * {@link JoinTable}, with what it needs reserved in the budget, at most
 * half of it, which the rows of the partition of the probe side then probe,
 * see {@link JoinProbe}. A partition that does not fit is cut into
 * {@value Partitions#PARTITIONS} smaller ones by more of the bits of the
 * same hashes, on both sides, down to {@value Partitions#MAX_PARTITIONS}
 * partitions; a partition whose rows all have one hash fails at once, since
 * no cut divides it. {@code RIGHT} and {@code FULL} joins emit the unmatched
 * build rows of a partition after probing it, and those with a {@code NULL}
 * key, which are in no partition, at the end.
 * <p>
 * Unless the order of the output does not matter, every row of the output
 * carries the place of its probe row, or, for an unmatched build row, its
 * own place: the output stays in memory while it takes at most a quarter of
 * the budget, and otherwise goes to disk in sorted runs, which are read
 * merged by their places, see {@link ExternalSort}, the unmatched build rows
 * after all others. The output is then the one of the join in memory, as
 * the matches of a probe row come in build order within its partition.
 */
final class GraceHashJoin implements AutoCloseable {

    /** The partitions cut into smaller ones so far because they did not fit, which tests read. */
    static final AtomicLong REFINED = new AtomicLong();

    /** A partition of both sides. */
    private record Pair(JoinPartitions.Work build, JoinPartitions.Work probe) {
    }

    private final AbstractOperator owner;
    private final PlanNode.Join join;
    private final JoinProbe probe;
    private final JoinSide.Spilled build;
    private final Operator left;
    private final MemoryBudget budget;
    private final SpillDirectory spill;
    private final int batchSize;
    private final boolean ordered;
    private final int[] leftKeys;
    private final int[] rightKeys;
    private final JoinType type;
    private final List<SpillFile> files = new ArrayList<>();
    private JoinPartitions probes;
    private final Deque<Pair> work = new ArrayDeque<>();

    /** The partition being probed, the table of its build rows with their places, and the reader of its probe rows. */
    private Pair current;
    private JoinTable table;
    private long[] buildPlaces;
    private JoinPartitions.Reader reader;
    private long taken;
    /** The places of the rows of the last output, and whether they are unmatched build rows. */
    private long[] places;
    private boolean unmatched;
    /** The reader of the build rows put aside, and whether they are all read. */
    private JoinPartitions.Reader aside;
    private JoinPartitions.Work asideWork;
    private boolean asideDone;

    /** The output in order, and its unmatched build rows, once every partition is probed. */
    private Collector output;
    private Collector leftovers;
    private boolean started;

    /**
     * @param owner     the join, whose label and query count
     * @param build     the build side on disk, which this join deletes when it closes
     * @param left      the probe side
     * @param ordered   whether the output comes in the order of the join in memory
     */
    GraceHashJoin(AbstractOperator owner, PlanNode.Join join, JoinProbe probe, JoinSide.Spilled build,
            Operator left, MemoryBudget budget, SpillDirectory spill, int batchSize, boolean ordered) {
        this.owner = owner;
        this.join = join;
        this.probe = probe;
        this.build = build;
        this.left = left;
        this.budget = budget;
        this.spill = spill;
        this.batchSize = batchSize;
        this.ordered = ordered;
        this.leftKeys = join.leftKeys().stream().mapToInt(Integer::intValue).toArray();
        this.rightKeys = join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
        this.type = join.type();
        probe.wholeBuildSide(build.rowCount(), build.hasNullKey());
    }

    /** Returns the next batch of the output, or {@code null} after the last. */
    Batch next() {
        if (!started) {
            started = true;
            scatterProbeSide();
            if (ordered) {
                output = new Collector();
                leftovers = new Collector();
                for (Batch out = produce(); out != null; out = produce()) {
                    (unmatched ? leftovers : output).add(out, places);
                }
                output.finish();
            }
        }
        if (!ordered) {
            return produce();
        }
        Batch b = output.next();
        if (b == null && leftovers != null) {
            output.close();
            output = leftovers;
            leftovers = null;
            output.finish();
            b = output.next();
        }
        return b;
    }

    /**
     * Writes the probe side to its partitions, each row with its place; the
     * rows with a {@code NULL} key go there too, unless the join drops them.
     */
    private void scatterProbeSide() {
        probes = new JoinPartitions(owner.label(), "probe", join.left().schema(), spill, owner.context());
        JoinPartitions.Writer writer = probes.writer();
        long row = 0;
        for (Batch b = left.next(); b != null; b = left.next()) {
            int n = b.rowCount();
            long[] at = new long[n];
            for (int r = 0; r < n; r++) {
                at[r] = Partitions.place(0, row + r);
            }
            row += n;
            scatterProbeRows(writer, b, probe.hash(b), at);
        }
        List<JoinPartitions.Work> builds = build.partitions().partitions();
        List<JoinPartitions.Work> probed = probes.partitions();
        for (int p = 0; p < builds.size(); p++) {
            work.add(new Pair(builds.get(p), probed.get(p)));
        }
    }

    /** Writes probe rows to the partitions of a writer, without those of a {@code NULL} key if the join drops them. */
    private void scatterProbeRows(JoinPartitions.Writer writer, Batch b, int[] hashes, long[] at) {
        // A probe row with a NULL key matches nothing, and these joins emit nothing for it.
        if (type == JoinType.INNER || type == JoinType.SEMI || type == JoinType.RIGHT) {
            int n = b.rowCount();
            int[] keep = new int[n];
            int m = 0;
            for (int r = 0; r < n; r++) {
                if (!JoinTable.hasNullKey(b, leftKeys, r)) {
                    keep[m++] = r;
                }
            }
            if (m == 0) {
                return;
            }
            if (m < n) {
                keep = Arrays.copyOf(keep, m);
                int[] h = new int[m];
                long[] p = new long[m];
                for (int i = 0; i < m; i++) {
                    h[i] = hashes[keep[i]];
                    p[i] = at[keep[i]];
                }
                b = b.select(keep);
                hashes = h;
                at = p;
            }
        }
        writer.scatter(b, hashes, at);
    }

    /**
     * Returns the next output of the partitions, the places of its rows in
     * {@link #places}, or {@code null} after the last partition.
     */
    private Batch produce() {
        while (true) {
            if (reader != null) {
                JoinPartitions.Chunk c = reader.next();
                if (c != null) {
                    Batch out = probe.probe(c.rows(), c.hashes(), table);
                    if (out != null) {
                        int[] rows = probe.probeRows();
                        places = new long[rows.length];
                        for (int i = 0; i < rows.length; i++) {
                            places[i] = c.places()[rows[i]];
                        }
                        unmatched = false;
                        return out;
                    }
                    continue;
                }
                reader.close();
                reader = null;
                Batch out = null;
                if (type == JoinType.RIGHT || type == JoinType.FULL) {
                    int[] rows = table.unmatched();
                    if (rows.length > 0) {
                        out = table.leftovers(join.left().schema(), join.schema());
                        places = new long[rows.length];
                        for (int i = 0; i < rows.length; i++) {
                            places[i] = buildPlaces[rows[i]];
                        }
                        unmatched = true;
                    }
                }
                endPartition();
                if (out != null) {
                    return out;
                }
                continue;
            }
            Pair p = work.poll();
            if (p == null) {
                return asideRows();
            }
            startPartition(p);
        }
    }

    /**
     * Returns the next build rows with a {@code NULL} key, which went to no
     * partition, see {@link JoinBuild#scatter}, as unmatched rows of the
     * joins that emit those; {@code null} after the last.
     */
    private Batch asideRows() {
        if (!SegmentPlanner.keepsUnmatchedRight(join) || asideDone) {
            return null;
        }
        if (aside == null) {
            asideWork = build.partitions().aside();
            aside = build.partitions().read(asideWork);
        }
        JoinPartitions.Chunk c = aside.next();
        if (c == null) {
            aside.close();
            aside = null;
            asideDone = true;
            build.partitions().close(asideWork);
            return null;
        }
        int m = c.rows().rowCount();
        int[] none = new int[m];
        Arrays.fill(none, -1);
        List<Vector> columns = new ArrayList<>(Batches.selectOrNull(Batch.empty(join.left().schema()), none)
                .columns());
        columns.addAll(c.rows().columns());
        places = c.places();
        unmatched = true;
        return new Batch(join.schema(), m, columns);
    }

    /** Builds the table of a partition and opens the reader of its probe rows, or cuts it if it does not fit. */
    private void startPartition(Pair p) {
        boolean keepsBuild = type == JoinType.RIGHT || type == JoinType.FULL;
        boolean keepsProbe = type != JoinType.INNER && type != JoinType.SEMI && type != JoinType.RIGHT;
        if (p.probe().rows() == 0 && !keepsBuild || p.build().rows() == 0 && !keepsProbe) {
            // Nothing of the partition comes out.
            close(p);
            return;
        }
        owner.checkCancelled();
        try {
            load(p.build());
        } catch (OverBudget e) {
            release();
            if (output != null && (output.holds() || leftovers.holds())) {
                // The output in memory goes to disk first, and the partition is loaded again.
                output.flush();
                leftovers.flush();
                work.addFirst(p);
                return;
            }
            long count = (long) p.build().count() * Partitions.PARTITIONS;
            if (p.build().oneHash() || count > Partitions.MAX_PARTITIONS) {
                throw new QueryException(owner.label(), "Memory budget of " + budget.limit() + " bytes exceeded: "
                        + (budget.used() + e.bytes) + " bytes needed after spilling "
                        + owner.context().spill().bytesWritten() + " bytes");
            }
            REFINED.incrementAndGet();
            List<JoinPartitions.Work> builds = build.partitions().refine(p.build());
            List<JoinPartitions.Work> probed = probes.refine(p.probe());
            for (int s = builds.size() - 1; s >= 0; s--) {
                work.addFirst(new Pair(builds.get(s), probed.get(s)));
            }
            return;
        }
        current = p;
        reader = probes.read(p.probe());
    }

    /** Reads the rows of a partition of the build side into its table, with what it takes reserved. */
    private void load(JoinPartitions.Work w) {
        List<Batch> batches = new ArrayList<>();
        List<int[]> hashes = new ArrayList<>();
        long[] at = new long[(int) Math.min(Integer.MAX_VALUE - 8, w.rows())];
        int rows = 0;
        long copy = 0;
        try (JoinPartitions.Reader r = build.partitions().read(w)) {
            for (JoinPartitions.Chunk c = r.next(); c != null; c = r.next()) {
                long bytes = Batches.estimateBytes(c.rows());
                take(bytes + 12L * c.rows().rowCount());
                batches.add(c.rows());
                hashes.add(c.hashes());
                System.arraycopy(c.places(), 0, at, rows, c.places().length);
                rows += c.rows().rowCount();
                copy += bytes;
            }
        }
        copy = batches.size() > 1 ? copy : 0;
        take(copy + JoinTable.indexBytes(rows, true));
        table = new JoinTable(build.partitions().schema(), batches, hashes, rightKeys);
        budget.release(copy);
        taken -= copy;
        buildPlaces = at;
    }

    /** Takes bytes for the table of a partition, while it then takes at most half of the budget. */
    private void take(long bytes) {
        if (taken + bytes > budget.limit() / 2 || !budget.tryReserve(bytes)) {
            throw new OverBudget(bytes);
        }
        taken += bytes;
    }

    private void release() {
        budget.release(taken);
        taken = 0;
        table = null;
        buildPlaces = null;
    }

    /** Gives back the table of the partition probed and deletes its files. */
    private void endPartition() {
        release();
        close(current);
        current = null;
    }

    private void close(Pair p) {
        build.partitions().close(p.build());
        probes.close(p.probe());
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (aside != null) {
            aside.close();
            aside = null;
        }
        release();
        if (output != null) {
            output.close();
        }
        if (leftovers != null) {
            leftovers.close();
        }
        for (SpillFile f : files) {
            f.close();
        }
        build.close();
        if (probes != null) {
            probes.close();
        }
    }

    /** Thrown when the table of a partition does not fit in the budget, so that the partition is cut. */
    @SuppressWarnings("serial")
    private static final class OverBudget extends RuntimeException {
        final long bytes;

        OverBudget(long bytes) {
            super(null, null, false, false);
            this.bytes = bytes;
        }
    }

    /**
     * Brings output in the order of its places: batches whose rows are each
     * in the order of their places stay in memory while they take at most a
     * quarter of the budget; beyond, what is held goes to disk as one sorted
     * run, and once all is there, the runs are read merged.
     */
    private final class Collector {
        private final List<Batch> batches = new ArrayList<>();
        private final List<long[]> placesOf = new ArrayList<>();
        private final List<ExternalSort.Run> runs = new ArrayList<>();
        private final Schema runSchema = ExternalSort.runSchema(join.schema());
        private long held;
        private ArrayDeque<Batch> inMemory;
        private ExternalSort sort;

        boolean holds() {
            return !batches.isEmpty();
        }

        void add(Batch b, long[] places) {
            long bytes = Batches.estimateBytes(b) + 8L * b.rowCount();
            boolean fits = held + bytes <= budget.limit() / 4 && budget.tryReserve(bytes);
            if (!fits && holds()) {
                flush();
                fits = bytes <= budget.limit() / 4 && budget.tryReserve(bytes);
            }
            if (!fits) {
                runs.add(writeRun(b, places));
                return;
            }
            held += bytes;
            batches.add(b);
            placesOf.add(places);
        }

        /** Writes what is held as one run, merged by the places, and gives back what it took. */
        void flush() {
            if (!holds()) {
                return;
            }
            long[] all = sortedPlaces();
            Batch merged = merged();
            batches.clear();
            placesOf.clear();
            runs.add(writeRun(merged, all));
            budget.release(held);
            held = 0;
        }

        private Batch merged() {
            return batches.size() == 1 ? batches.get(0)
                    : Batches.concat(join.schema(), batches).select(Partitions.mergeOrder(placesOf));
        }

        private long[] sortedPlaces() {
            if (placesOf.size() == 1) {
                return placesOf.get(0);
            }
            int[] order = Partitions.mergeOrder(placesOf);
            long[] concat = new long[order.length];
            int at = 0;
            for (long[] p : placesOf) {
                System.arraycopy(p, 0, concat, at, p.length);
                at += p.length;
            }
            long[] sorted = new long[order.length];
            for (int i = 0; i < order.length; i++) {
                sorted[i] = concat[order[i]];
            }
            return sorted;
        }

        private ExternalSort.Run writeRun(Batch rows, long[] places) {
            SpillFile file = spill.create(owner.label(), "joined", runSchema, owner.context());
            files.add(file);
            long batchBytes = 0;
            int step = ExternalSort.runRows(Batches.estimateBytes(rows), rows.rowCount(), budget, batchSize);
            int from = 0;
            for (Batch b : Batches.split(rows, step)) {
                int to = from + b.rowCount();
                Batch withPlaces = ExternalSort.withPlaces(runSchema, b, Arrays.copyOfRange(places, from, to));
                batchBytes = Math.max(batchBytes, Batches.estimateBytes(withPlaces));
                file.append(withPlaces);
                from = to;
            }
            file.finish();
            return new ExternalSort.Run(file, batchBytes);
        }

        /** Ends adding: the output comes from memory if all of it is there, else from the runs. */
        void finish() {
            if (runs.isEmpty()) {
                inMemory = new ArrayDeque<>(holds() ? Batches.split(merged(), batchSize) : List.of());
                batches.clear();
                placesOf.clear();
                return;
            }
            flush();
            sort = new ExternalSort(owner.label(), join.schema(), List.of(), budget, spill, owner.context(),
                    batchSize);
            sort.start(runs);
        }

        Batch next() {
            if (sort != null) {
                return sort.next();
            }
            Batch b = inMemory.poll();
            if (b == null && held > 0) {
                budget.release(held);
                held = 0;
            }
            return b;
        }

        void close() {
            if (sort != null) {
                sort.close();
                sort = null;
            }
            budget.release(held);
            held = 0;
        }
    }
}
