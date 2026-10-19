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
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.KeyFunctions;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Groups the input by its key columns and accumulates the aggregates per
 * group; groups come out in the order of their first row. Sums of integers
 * are exact 128-bit values, sums of decimals keep their scale, and
 * {@code avg} is a double. An aggregate with an {@code ORDER BY} keeps its
 * values with their sort keys and accumulates them in that order at the
 * end; the sort is stable.
 * <p>
 * The groups live in a {@link GroupTable}, whose keys are hashed and
 * compared by {@link InterpretedKeys}, or, once {@link Codegen#due} says so,
 * by compiled key functions. A key of a group comes out as its first row
 * has it, as in DuckDB, e.g. {@code -0.0} for a group of {@code -0.0} and
 * {@code 0.0} in this order.
 * <p>
 * Each aggregate keeps an {@link AggregateState} over all groups: one
 * without boxing where {@link AggregateState#of} has one, otherwise a
 * {@link ValueAccumulator} per group. The results are computed group by group,
 * so that the first group whose result fails is the one that fails the
 * query. The budget takes the bytes of the new groups of a batch at once.
 * <p>
 * Over a segment that runs on workers, see {@link ParallelSegment}, and if
 * every aggregate is {@link #mergeable}, each worker groups the rows it
 * reads by itself, and the groups of all workers are merged at the end, in
 * {@value #PARTITIONS} partitions by the hashes of their keys, which run in
 * parallel. Once a worker holds more than {@value #MAX_LOCAL_GROUPS} groups,
 * and again whenever their number has doubled since, it checks the rows per
 * new group since the last check: below {@value #MIN_ROWS_PER_GROUP}
 * grouping does not pay, as the table of each worker outgrows the caches
 * while it holds most keys of all, and the worker keeps its further rows as
 * they are, for the merge to group. It scatters them at once, while they
 * are still in the caches, into the partitions by the hashes of their keys,
 * with the key columns and the columns of the aggregates only, so that the
 * merge reads them as they are. Each row knows its place among the rows of
 * one thread, by its morsel and its place in it, so that the groups come
 * out in the order of their first rows and with the keys of these rows, the
 * group that fails is the first one, and {@code min} and {@code max} keep
 * the first of equal values, e.g. {@code -0.0} before {@code 0.0}, as on
 * one thread; unless the order of the output does not matter, then the
 * groups come partition by partition. What differs from one thread: sums of
 * {@code FLOAT}s and {@code DOUBLE}s add up in another order. Otherwise the
 * operator reads the segment as any other input.
 */
final class HashAggregateOperator extends AbstractOperator {

    private static final long BYTES_PER_GROUP = 128;

    /** The partitions in which the groups of the workers are merged. */
    static final int PARTITIONS = 16;

    /** The groups and rows from which on the merge runs in partitions; fewer are merged on one thread. */
    static final int PARTITIONED_MERGE = 16_384;

    /**
     * The groups of a worker from which on it checks whether grouping pays,
     * see {@link #MIN_ROWS_PER_GROUP}.
     */
    static final int MAX_LOCAL_GROUPS = 16_384;

    /** {@link #MAX_LOCAL_GROUPS}, which tests lower. */
    static volatile int maxLocalGroups = MAX_LOCAL_GROUPS;

    /**
     * The rows per new group since the last check below which a worker
     * stops grouping; at four rows per group, as the orders of TPC-H have
     * line items, grouping still pays.
     */
    static final int MIN_ROWS_PER_GROUP = 2;

    /** {@link #MIN_ROWS_PER_GROUP}, which tests raise. */
    static volatile int minRowsPerGroup = MIN_ROWS_PER_GROUP;

    /** The bits of a place for the row in its morsel, the highest morsel and the highest row it tells apart. */
    private static final int ROW_BITS = 40;
    private static final long MAX_MORSEL = (1L << 23) - 1;
    private static final long MAX_ROW = (1L << ROW_BITS) - 1;

    private final PlanNode.Aggregate aggregate;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private final Schema inputSchema;
    private final Codegen codegen;
    private final boolean unboxed;
    private final int threads;
    private final boolean ordered;
    private final List<Integer> keyColumnList;
    private final int[] keyColumns;
    /** The columns of the rows a worker scatters, the key columns among them, and the aggregates over them. */
    private final List<Integer> scatteredColumns;
    private final Schema scatteredSchema;
    private final List<Integer> scatteredKeys;
    private final List<AggregateCall> scatteredCalls;
    private List<Batch> output;
    private final AtomicLong reserved = new AtomicLong();

    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen) {
        this(label, aggregate, input, budget, batchSize, codegen, true, 1, true);
    }

    /** With {@code unboxed} false, every aggregate keeps accumulator objects, which the tests compare against. */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, boolean unboxed) {
        this(label, aggregate, input, budget, batchSize, codegen, unboxed, 1, true);
    }

    /**
     * @param threads the most threads that merge the groups of the workers
     * @param ordered whether the groups come in the order of their first rows, as on one thread
     */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, int threads, boolean ordered) {
        this(label, aggregate, input, budget, batchSize, codegen, true, threads, ordered);
    }

    private HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, boolean unboxed, int threads, boolean ordered) {
        super(label, aggregate.schema());
        this.aggregate = aggregate;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
        this.inputSchema = aggregate.input().schema();
        this.codegen = codegen;
        this.unboxed = unboxed;
        this.threads = threads;
        this.ordered = ordered;
        this.keyColumnList = List.copyOf(aggregate.groupColumns());
        this.keyColumns = keyColumnList.stream().mapToInt(Integer::intValue).toArray();
        TreeSet<Integer> used = new TreeSet<>(keyColumnList);
        for (AggregateCall call : aggregate.aggregates()) {
            if (call.column() >= 0) {
                used.add(call.column());
            }
        }
        this.scatteredColumns = List.copyOf(used);
        this.scatteredSchema = inputSchema.select(scatteredColumns);
        this.scatteredKeys = keyColumnList.stream().map(scatteredColumns::indexOf).toList();
        // Only the aggregates of a merge, without DISTINCT and ORDER BY, read scattered rows.
        this.scatteredCalls = !mergeable(aggregate) ? List.of() : aggregate.aggregates().stream()
                .map(c -> c.column() < 0 ? c
                        : new AggregateCall(c.function(), scatteredColumns.indexOf(c.column()), false, c.name()))
                .toList();
    }

    /**
     * Tells whether the groups of an aggregation merge into one another:
     * {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max},
     * {@code bool_and} and {@code bool_or}, without {@code DISTINCT} and
     * {@code ORDER BY}, for which the order of the rows does not matter.
     */
    static boolean mergeable(PlanNode.Aggregate aggregate) {
        for (AggregateCall call : aggregate.aggregates()) {
            if (call.distinct() || !call.orderBy().isEmpty()) {
                return false;
            }
            switch (call.function()) {
                case COUNT_STAR, COUNT, SUM, AVG, MIN, MAX, BOOL_AND, BOOL_OR -> {
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = input instanceof ParallelSegment segment && mergeable(aggregate) ? aggregateParallel(segment)
                    : aggregateAll();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> aggregateAll() {
        Partial all = new Partial(true);
        for (Batch b = input.next(); b != null; b = input.next()) {
            all.accept(0, b);
        }
        return output(all);
    }

    private List<Batch> aggregateParallel(ParallelSegment segment) {
        List<Partial> parts = segment.drain(workers -> new Partial(workers == 1));
        Partial first = parts.get(0);
        if (parts.size() == 1 && first.scattered == null) {
            return output(first);
        }
        if (keyColumns.length == 0) {
            for (Partial p : parts.subList(1, parts.size())) {
                for (int c = 0; c < first.states.length; c++) {
                    first.states[c].combine(0, p.states[c], 0);
                }
            }
            return output(first);
        }
        return merge(parts);
    }

    /** Returns the results of the groups of a part that holds all of them, in their order. */
    private List<Batch> output(Partial all) {
        int groups = all.groups;
        if (groups == 0) {
            return new ArrayList<>();
        }
        AggregateState[] states = all.states;
        for (AggregateState state : states) {
            state.startResult(groups);
        }
        for (int g = 0; g < groups; g++) {
            if ((g & (CHECK_INTERVAL - 1)) == 0) {
                checkCancelled();
            }
            for (AggregateState state : states) {
                state.result(g);
            }
        }
        List<Vector> columns = new ArrayList<>(keyColumns.length + states.length);
        for (int k = 0; k < keyColumns.length; k++) {
            columns.add(all.table.keys(k));
        }
        for (AggregateState state : states) {
            columns.add(state.finishResult());
        }
        return new ArrayList<>(Batches.split(new Batch(schema(), groups, columns), batchSize));
    }

    /**
     * Merges the groups and the rows of the parts of the workers, partition
     * by partition in parallel, and returns the results.
     */
    private List<Batch> merge(List<Partial> parts) {
        long total = 0;
        boolean scattered = false;
        for (Partial p : parts) {
            total += p.groups + p.rawRows;
            scattered |= p.scattered != null;
        }
        // Scattered rows are in partitions already.
        int partitions = total < PARTITIONED_MERGE && !scattered ? 1 : PARTITIONS;
        Piece[][] pieces = new Piece[parts.size()][];
        Workers.run(parts.size(), threads, w -> pieces[w] = parts.get(w).pieces(partitions));
        Merged[] merged = new Merged[partitions];
        Workers.run(partitions, threads, p -> merged[p] = mergePartition(parts, pieces, p));
        Merged failed = null;
        List<Batch> results = new ArrayList<>();
        List<long[]> places = new ArrayList<>();
        for (Merged m : merged) {
            if (m.failure() != null && (failed == null || m.failedAt() < failed.failedAt())) {
                failed = m;
            }
            if (m.batch() != null) {
                results.add(m.batch());
                places.add(m.places());
            }
        }
        if (failed != null) {
            throw failed.failure();
        }
        if (results.isEmpty()) {
            return new ArrayList<>();
        }
        Batch all = Batches.concat(schema(), results);
        if (ordered && results.size() > 1) {
            all = all.select(mergeOrder(places));
        }
        return new ArrayList<>(Batches.split(all, batchSize));
    }

    /**
     * Merges one partition: the groups and rows of all parts in the order of
     * their places, so that the groups are numbered in the order of their
     * first rows, then the states of the groups of the parts and the rows,
     * and computes the results of the groups in their order.
     */
    private Merged mergePartition(List<Partial> parts, Piece[][] pieces, int partition) {
        List<Segment> segments = new ArrayList<>();
        int m = 0;
        for (int w = 0; w < pieces.length; w++) {
            Piece piece = pieces[w][partition];
            int entry = cut(segments, w, piece.keys(), piece.places(), 0);
            for (Batch raw : piece.raw()) {
                entry = cut(segments, w, raw.project(scatteredKeys), piece.places(), entry);
            }
            m += entry;
        }
        if (m == 0) {
            return new Merged(null, null, null, 0);
        }
        List<Integer> stored = new ArrayList<>();
        for (int k = 0; k < keyColumns.length; k++) {
            stored.add(k);
        }
        GroupTable table = new GroupTable(stored);
        // The key functions of the workers, compiled once they were due, hash as the interpreted ones.
        KeyFunctions keys = parts.get(0).keys;
        long[] storedBytes = new long[1];
        int[][] groupAt = new int[pieces.length][];
        for (int w = 0; w < pieces.length; w++) {
            groupAt[w] = new int[pieces[w][partition].places().length];
        }
        long[] firstPlaces = new long[16];
        int groups = 0;
        int done = 0;
        List<long[]> runs = new ArrayList<>();
        for (Piece[] ofPart : pieces) {
            runs.add(ofPart[partition].places());
        }
        for (Segment segment : inOrder(segments, runs)) {
            if ((done++ & (CHECK_INTERVAL - 1)) == 0) {
                checkCancelled();
            }
            Piece piece = pieces[segment.part()][partition];
            int[] groupOf = table.groups(segment.keys(), segment.from(), segment.to(), piece.hashes(),
                    segment.entry(), keys, b -> storedBytes[0] += Batches.estimateBytes(b));
            int[] at = groupAt[segment.part()];
            for (int i = 0; i < groupOf.length; i++) {
                int g = groupOf[i];
                if (g == groups) {
                    firstPlaces = AggregateState.grow(firstPlaces, groups + 1);
                    firstPlaces[groups++] = piece.places()[segment.entry() + i];
                }
                at[segment.entry() + i] = g;
            }
        }
        reserve(storedBytes[0] + BYTES_PER_GROUP * groups);
        firstPlaces = Arrays.copyOf(firstPlaces, groups);
        // Given the places of the rows, min and max keep the first of equal values in whatever order the parts come.
        AggregateState[] states = newStates(scatteredCalls, scatteredSchema);
        for (int w = 0; w < pieces.length; w++) {
            Piece piece = pieces[w][partition];
            AggregateState[] own = parts.get(w).states;
            int[] local = piece.groups();
            int[] at = groupAt[w];
            for (int j = 0; j < local.length; j++) {
                if ((j & (CHECK_INTERVAL - 1)) == 0) {
                    checkCancelled();
                }
                for (int c = 0; c < states.length; c++) {
                    states[c].combine(at[j], own[c], local[j]);
                }
            }
            int from = local.length;
            for (Batch raw : piece.raw()) {
                checkCancelled();
                int rows = raw.rowCount();
                int[] rawGroups = Arrays.copyOfRange(at, from, from + rows);
                for (AggregateState state : states) {
                    state.add(raw, rawGroups, rows, groups, piece.places(), from);
                }
                from += rows;
            }
        }
        for (AggregateState state : states) {
            state.startResult(groups);
        }
        for (int g = 0; g < groups; g++) {
            if ((g & (CHECK_INTERVAL - 1)) == 0) {
                checkCancelled();
            }
            try {
                for (AggregateState state : states) {
                    state.result(g);
                }
            } catch (QueryCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                return new Merged(null, null, e, firstPlaces[g]);
            }
        }
        List<Vector> columns = new ArrayList<>(keyColumns.length + states.length);
        for (int k = 0; k < keyColumns.length; k++) {
            columns.add(table.keys(k));
        }
        for (AggregateState state : states) {
            columns.add(state.finishResult());
        }
        return new Merged(new Batch(schema(), groups, columns), firstPlaces, null, 0);
    }

    /**
     * Rows of a run, all of one morsel: the rows {@code from} to {@code to} of
     * the keys of a source of the run, the first of them its entry
     * {@code entry}, and the places of the first and the last of them.
     */
    record Segment(int part, Batch keys, int from, int to, int entry, long first, long last) {
    }

    /**
     * Cuts the keys of a source of the run of a part, whose first row is the
     * entry {@code entry} of the run, into segments of one morsel each, and
     * returns the entry after them.
     */
    static int cut(List<Segment> segments, int part, Batch keys, long[] places, int entry) {
        int n = keys.rowCount();
        for (int from = 0, to; from < n; from = to) {
            long morsel = places[entry + from] >>> ROW_BITS;
            to = from + 1;
            while (to < n && places[entry + to] >>> ROW_BITS == morsel) {
                to++;
            }
            segments.add(new Segment(part, keys, from, to, entry + from, places[entry + from],
                    places[entry + to - 1]));
        }
        return entry + n;
    }

    /**
     * Returns the segments of runs of places in the order of the places of
     * their rows. A morsel is read by one worker, so that the segments of
     * different runs do not interleave and sorting them by their first
     * places suffices; should they interleave nonetheless, each row becomes a
     * segment of its own, in the order of {@link #mergeOrder}.
     *
     * @param segments the segments of all runs, those of each run in its order, as {@link #cut} cuts them
     */
    static List<Segment> inOrder(List<Segment> segments, List<long[]> runs) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingLong(Segment::first).thenComparingInt(Segment::part));
        boolean apart = true;
        for (int i = 1; i < sorted.size() && apart; i++) {
            Segment a = sorted.get(i - 1);
            Segment b = sorted.get(i);
            apart = a.last() < b.first() || a.last() == b.first() && a.part() < b.part();
        }
        if (apart) {
            return sorted;
        }
        int[] start = new int[runs.size() + 1];
        for (int w = 0; w < runs.size(); w++) {
            start[w + 1] = start[w] + runs.get(w).length;
        }
        // The segments of a run, in the order of its entries, as they were cut.
        List<List<Segment>> ofRun = new ArrayList<>();
        for (int w = 0; w < runs.size(); w++) {
            ofRun.add(new ArrayList<>());
        }
        for (Segment segment : segments) {
            ofRun.get(segment.part()).add(segment);
        }
        int[] next = new int[runs.size()];
        List<Segment> rows = new ArrayList<>(start[runs.size()]);
        for (int index : mergeOrder(runs)) {
            int w = 0;
            while (start[w + 1] <= index) {
                w++;
            }
            int entry = index - start[w];
            while (ofRun.get(w).get(next[w]).entry() + ofRun.get(w).get(next[w]).to()
                    - ofRun.get(w).get(next[w]).from() <= entry) {
                next[w]++;
            }
            Segment of = ofRun.get(w).get(next[w]);
            int row = of.from() + entry - of.entry();
            long place = runs.get(w)[entry];
            rows.add(new Segment(w, of.keys(), row, row + 1, entry, place, place));
        }
        return rows;
    }

    /**
     * The result of the merge of a partition: the results of its groups and
     * the places of their first rows, both {@code null} without groups, or
     * the failure of the first group whose result fails, and its place.
     */
    private record Merged(Batch batch, long[] places, RuntimeException failure, long failedAt) {
    }

    /**
     * What a part holds of a partition: the keys of its groups, their
     * numbers in the part and the hashes and places of their keys, then its
     * rows as they are, of the columns of {@link #scatteredSchema}, with the
     * hashes and places of their keys; the places of the groups are those of
     * their first rows, all in the order of the places.
     */
    private record Piece(Batch keys, int[] groups, List<Batch> raw, int[] hashes, long[] places) {
    }

    /** Returns the states of the aggregates for new groups. */
    private AggregateState[] newStates() {
        return newStates(aggregate.aggregates(), inputSchema);
    }

    /** Returns the states of aggregates over the columns of a schema for new groups. */
    private AggregateState[] newStates(List<AggregateCall> calls, Schema schema) {
        AggregateState[] states = new AggregateState[calls.size()];
        for (int i = 0; i < states.length; i++) {
            AggregateState state = unboxed ? AggregateState.of(calls.get(i), schema) : null;
            states[i] = state != null ? state : new Accumulators(calls.get(i), schema);
        }
        return states;
    }

    /** Returns the partition of a hash: its bits mixed, the highest of them scaled to the partitions. */
    static int partition(int hash, int partitions) {
        int mixed = hash * 0x9E3779B9;
        return (int) (((mixed & 0xFFFFFFFFL) * partitions) >>> 32);
    }

    /** Returns the place of a row: its morsel and its place in the morsel, which order the rows as one thread. */
    private static long place(int morsel, long row) {
        return Math.min(morsel, MAX_MORSEL) << ROW_BITS | Math.min(row, MAX_ROW);
    }

    /**
     * Merges runs of places, each in ascending order, and returns the
     * indexes of the places in the runs one after the other, in the order of
     * the places; of equal places, the one of the earlier run comes first.
     */
    static int[] mergeOrder(List<long[]> places) {
        long[][] runs = places.toArray(new long[0][]);
        int r = runs.length;
        int[] start = new int[r];
        int[] at = new int[r];
        int n = 0;
        for (int i = 0; i < r; i++) {
            start[i] = n;
            n += runs[i].length;
        }
        // A binary heap of the runs that have places left, by their next place.
        int[] heap = new int[r];
        int size = 0;
        for (int i = 0; i < r; i++) {
            if (runs[i].length > 0) {
                heap[size++] = i;
                siftUp(heap, size - 1, runs, at);
            }
        }
        int[] order = new int[n];
        for (int k = 0; k < n; k++) {
            int i = heap[0];
            order[k] = start[i] + at[i];
            at[i]++;
            if (at[i] == runs[i].length) {
                heap[0] = heap[--size];
            }
            siftDown(heap, size, runs, at);
        }
        return order;
    }

    private static boolean before(int x, int y, long[][] runs, int[] at) {
        long a = runs[x][at[x]];
        long b = runs[y][at[y]];
        return a < b || a == b && x < y;
    }

    private static void siftUp(int[] heap, int i, long[][] runs, int[] at) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (!before(heap[i], heap[parent], runs, at)) {
                return;
            }
            int t = heap[i];
            heap[i] = heap[parent];
            heap[parent] = t;
            i = parent;
        }
    }

    private static void siftDown(int[] heap, int size, long[][] runs, int[] at) {
        int i = 0;
        while (true) {
            int least = i;
            for (int child = 2 * i + 1; child <= 2 * i + 2 && child < size; child++) {
                if (before(heap[child], heap[least], runs, at)) {
                    least = child;
                }
            }
            if (least == i) {
                return;
            }
            int t = heap[i];
            heap[i] = heap[least];
            heap[least] = t;
            i = least;
        }
    }

    private void reserve(long bytes) {
        budget.reserve(bytes, label());
        reserved.addAndGet(bytes);
    }

    /**
     * The groups of the rows of one worker, or of all rows, with the places
     * of their first rows; once grouping does not pay, its further rows as
     * they are, scattered into the partitions.
     */
    private final class Partial implements ParallelSegment.Sink {
        /** Whether the part takes all rows. */
        private final boolean alone;
        private final GroupTable table;
        private final AggregateState[] states;
        /** Whether the states are given the places of the rows, which the part that takes all rows does not need. */
        private final boolean placed;
        private int groups;
        private long[] places = new long[16];
        private KeyFunctions keys = InterpretedKeys.INSTANCE;
        private boolean keysAsked;
        private long rowsSeen;
        private int[] noKeys = new int[0];
        private long[] rowPlaces = new long[0];
        private int morsel = -1;
        private long row;
        /** The groups beyond which grouping is checked next, and the rows and groups at the last check. */
        private int nextCheck = maxLocalGroups;
        private long checkedRows;
        private int checkedGroups;
        /**
         * The rows kept as they are, per partition: of the columns of
         * {@link #scatteredSchema}, with the hashes and places of their keys,
         * in the order of the places; {@code null} while the part groups.
         */
        private List<List<Batch>> scattered;
        private int[][] scatteredHashes;
        private long[][] scatteredPlaces;
        private int[] scatteredRows;
        private long rawRows;
        private int[] hashes = new int[0];
        private int[] partitionOf = new int[0];
        private int[] byPartition = new int[0];

        Partial(boolean alone) {
            this.alone = alone;
            this.table = keyColumns.length == 0 ? null : new GroupTable(keyColumnList);
            this.states = newStates();
            boolean keepsPlaces = false;
            for (AggregateState state : states) {
                keepsPlaces |= state.keepsPlaces();
            }
            this.placed = !alone && keepsPlaces;
            // Without keys there is always the one group, even without rows.
            this.groups = table == null ? 1 : 0;
        }

        @Override
        public void accept(int morsel, Batch b) {
            if (morsel != this.morsel) {
                this.morsel = morsel;
                row = 0;
            }
            long first = place(morsel, row);
            int n = b.rowCount();
            row += n;
            if (scattered != null) {
                scatter(b, first);
                return;
            }
            int[] groupOf;
            if (table == null) {
                if (noKeys.length < n) {
                    noKeys = new int[n];
                }
                groupOf = noKeys;
            } else {
                askKeys();
                rowsSeen += n;
                groupOf = table.groups(b, keys, stored -> reserve(Batches.estimateBytes(stored)));
                if (table.size() > groups) {
                    reserve(BYTES_PER_GROUP * (table.size() - groups));
                    places = AggregateState.grow(places, table.size());
                    // New groups are numbered in the order of their first rows.
                    for (int r = 0, next = groups; next < table.size(); r++) {
                        if (groupOf[r] == next) {
                            places[next++] = first + r;
                        }
                    }
                    groups = table.size();
                }
            }
            if (placed) {
                if (rowPlaces.length < n) {
                    rowPlaces = new long[n];
                }
                for (int r = 0; r < n; r++) {
                    rowPlaces[r] = first + r;
                }
                for (AggregateState state : states) {
                    state.add(b, groupOf, n, groups, rowPlaces, 0);
                }
            } else {
                for (AggregateState state : states) {
                    state.add(b, groupOf, n, groups);
                }
            }
            if (!alone && table != null && groups > nextCheck) {
                if (rowsSeen - checkedRows < (long) minRowsPerGroup * (groups - checkedGroups)) {
                    scattered = new ArrayList<>();
                    for (int p = 0; p < PARTITIONS; p++) {
                        scattered.add(new ArrayList<>());
                    }
                    scatteredHashes = new int[PARTITIONS][16];
                    scatteredPlaces = new long[PARTITIONS][16];
                    scatteredRows = new int[PARTITIONS];
                }
                checkedRows = rowsSeen;
                checkedGroups = groups;
                nextCheck = (int) Math.min(Integer.MAX_VALUE, 2L * groups);
            }
        }

        private void askKeys() {
            if (!keysAsked && codegen.due(rowsSeen)) {
                keysAsked = true;
                KeyFunctions compiled = codegen.keys(keyColumnList.stream().map(inputSchema::type).toList());
                if (compiled != null) {
                    keys = compiled;
                }
            }
        }

        /**
         * Scatters the rows of a batch as they are into the partitions by the
         * hashes of their keys, with the hashes and the places of the rows.
         */
        private void scatter(Batch b, long first) {
            int n = b.rowCount();
            askKeys();
            rowsSeen += n;
            if (hashes.length < n) {
                hashes = new int[n];
                partitionOf = new int[n];
                byPartition = new int[n];
            }
            keys.hash(b, keyColumns, hashes);
            // The rows of each partition, in their order, one partition after the other.
            int[] start = new int[PARTITIONS + 1];
            for (int r = 0; r < n; r++) {
                int p = partition(hashes[r], PARTITIONS);
                partitionOf[r] = p;
                start[p + 1]++;
            }
            for (int p = 0; p < PARTITIONS; p++) {
                start[p + 1] += start[p];
            }
            int[] next = Arrays.copyOf(start, PARTITIONS);
            for (int r = 0; r < n; r++) {
                byPartition[next[partitionOf[r]]++] = r;
            }
            Batch columns = b.project(scatteredColumns);
            long bytes = 0;
            for (int p = 0; p < PARTITIONS; p++) {
                int count = start[p + 1] - start[p];
                if (count == 0) {
                    continue;
                }
                int[] own = Arrays.copyOfRange(byPartition, start[p], start[p + 1]);
                Batch rows = count == n ? columns : columns.select(own);
                bytes += Batches.estimateBytes(rows) + 12L * count;
                scattered.get(p).add(rows);
                int at = scatteredRows[p];
                if (scatteredHashes[p].length < at + count) {
                    int size = Math.max(at + count, scatteredHashes[p].length * 2);
                    scatteredHashes[p] = Arrays.copyOf(scatteredHashes[p], size);
                    scatteredPlaces[p] = Arrays.copyOf(scatteredPlaces[p], size);
                }
                int[] h = scatteredHashes[p];
                long[] placesOf = scatteredPlaces[p];
                for (int i = 0; i < count; i++) {
                    h[at + i] = hashes[own[i]];
                    placesOf[at + i] = first + own[i];
                }
                scatteredRows[p] = at + count;
            }
            reserve(bytes);
            rawRows += n;
        }

        /** Divides the groups and the rows of the part into partitions by the hashes of their keys. */
        Piece[] pieces(int partitions) {
            int[] counts = new int[partitions];
            int[] partitionOfGroup = new int[groups];
            for (int g = 0; g < groups; g++) {
                partitionOfGroup[g] = partition(table.hash(g), partitions);
                counts[partitionOfGroup[g]]++;
            }
            int[][] ofPartition = new int[partitions][];
            for (int p = 0; p < partitions; p++) {
                ofPartition[p] = new int[counts[p]];
                counts[p] = 0;
            }
            for (int g = 0; g < groups; g++) {
                int p = partitionOfGroup[g];
                ofPartition[p][counts[p]++] = g;
            }
            Batch all = groups == 0 ? null : table.keys();
            if (all != null) {
                reserve(Batches.estimateBytes(all));
            }
            Schema keySchema = inputSchema.select(keyColumnList);
            Piece[] pieces = new Piece[partitions];
            for (int p = 0; p < partitions; p++) {
                int[] own = ofPartition[p];
                Batch keysOf = all == null ? Batch.empty(keySchema) : own.length == groups ? all : all.select(own);
                if (keysOf != all && all != null) {
                    reserve(Batches.estimateBytes(keysOf));
                }
                // The rows kept as they are are in the partitions already; there are then as many partitions.
                int raw = scattered == null ? 0 : scatteredRows[p];
                int m = own.length + raw;
                int[] hashesOf = new int[m];
                long[] at = new long[m];
                for (int i = 0; i < own.length; i++) {
                    hashesOf[i] = table.hash(own[i]);
                    at[i] = places[own[i]];
                }
                if (raw > 0) {
                    System.arraycopy(scatteredHashes[p], 0, hashesOf, own.length, raw);
                    System.arraycopy(scatteredPlaces[p], 0, at, own.length, raw);
                }
                pieces[p] = new Piece(keysOf, own, scattered == null ? List.of() : scattered.get(p), hashesOf, at);
            }
            return pieces;
        }
    }

    /** The state of an aggregate as one accumulator object per group. */
    private final class Accumulators extends AggregateState {
        private final AggregateCall call;
        private final Schema schema;
        private final List<ValueAccumulator> groups = new ArrayList<>();
        private Vector.Builder out;

        Accumulators(AggregateCall call, Schema schema) {
            this.call = call;
            this.schema = schema;
        }

        private void grow(int n) {
            while (groups.size() < n) {
                groups.add(new ValueAccumulator(call, schema, HashAggregateOperator.this::reserve));
            }
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int n) {
            grow(n);
            for (int row = 0; row < rows; row++) {
                groups.get(groupOf[row]).add(b, row);
            }
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            Accumulators o = (Accumulators) other;
            grow(group + 1);
            // The one group of an aggregation without keys has no accumulator until a row comes.
            if (otherGroup < o.groups.size()) {
                groups.get(group).merge(o.groups.get(otherGroup));
            }
        }

        @Override
        void startResult(int n) {
            grow(n);
            out = Vectors.builder(call.type(schema), n);
        }

        @Override
        void result(int group) {
            out.append(groups.get(group).result());
        }

        @Override
        Vector finishResult() {
            return out.build();
        }
    }

    /**
     * Converts a 128-bit sum to a double as DuckDB does: the low 64 bits as
     * an unsigned number plus the high 64 bits times 2^64, except that a
     * negative number above -2^64 becomes minus its magnitude less one,
     * minus one. Beyond 2^53 this rounds differently from
     * {@link BigInteger#doubleValue()} now and then.
     */
    static double toDouble(BigInteger value) {
        long lower = value.longValue();
        long upper = value.shiftRight(64).longValue();
        if (upper == -1) {
            return -unsignedToDouble(~lower) - 1;
        }
        return unsignedToDouble(lower) + upper * 0x1p64;
    }

    /** Converts an unsigned 64-bit number to the nearest double, ties to even. */
    private static double unsignedToDouble(long x) {
        if (x >= 0) {
            return x;
        }
        // Halve and keep the lowest bit as a sticky bit, so that the rounding stays exact.
        return ((x >>> 1) | (x & 1)) * 2.0;
    }

    @Override
    public void close() {
        input.close();
        budget.release(reserved.getAndSet(0));
    }
}
