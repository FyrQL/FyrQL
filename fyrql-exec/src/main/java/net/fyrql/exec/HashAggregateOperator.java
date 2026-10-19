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
import java.util.List;
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
 * parallel. A worker that holds more than {@value #MAX_LOCAL_GROUPS} groups
 * keeps its further rows as they are, for the merge to group, once more
 * than half of the rows of a window of as many rows have made new groups:
 * its groups then hold fewer than two rows each, where grouping does not
 * pay. Over the rows of the window, not over all rows so far, so that keys
 * in a random order, of which the first rows find few groups to join, are
 * not mistaken for such keys. Each row knows its place among the rows of one
 * thread, by its morsel and its place in it, so that the groups come out in
 * the order of their first rows and with the keys of these rows, and the
 * group that fails is the first one, as on one thread; unless the order of
 * the output does not matter, then the groups come partition by partition.
 * What differs from one thread: sums of {@code FLOAT}s and {@code DOUBLE}s
 * add up in another order, and {@code min} and {@code max} may keep another
 * one of equal values, e.g. {@code 0.0} instead of {@code -0.0}. Otherwise
 * the operator reads the segment as any other input.
 */
final class HashAggregateOperator extends AbstractOperator {

    private static final long BYTES_PER_GROUP = 128;

    /** The partitions in which the groups of the workers are merged. */
    static final int PARTITIONS = 16;

    /** The groups and rows from which on the merge runs in partitions; fewer are merged on one thread. */
    static final int PARTITIONED_MERGE = 16_384;

    /**
     * The groups of a worker from which on it stops grouping once more than
     * half of the rows of a window of as many rows make new groups.
     */
    static final int MAX_LOCAL_GROUPS = 65_536;

    /** {@link #MAX_LOCAL_GROUPS}, which tests lower. */
    static volatile int maxLocalGroups = MAX_LOCAL_GROUPS;

    /** The highest morsel and the highest row in a morsel that a place tells apart. */
    private static final long MAX_MORSEL = (1L << 23) - 1;
    private static final long MAX_ROW = (1L << 40) - 1;

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
        if (parts.size() == 1 && first.raw == null) {
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
        for (Partial p : parts) {
            total += p.groups + p.rawRows;
        }
        int partitions = total < PARTITIONED_MERGE ? 1 : PARTITIONS;
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
        Schema keySchema = inputSchema.select(keyColumnList);
        List<Batch> keyParts = new ArrayList<>();
        List<long[]> runs = new ArrayList<>();
        int m = 0;
        for (Piece[] ofPart : pieces) {
            Piece piece = ofPart[partition];
            keyParts.add(piece.keys());
            keyParts.add(piece.raw().project(keyColumnList));
            runs.add(piece.places());
            m += piece.places().length;
        }
        if (m == 0) {
            return new Merged(null, null, null, 0);
        }
        int[] hashes = new int[m];
        long[] places = new long[m];
        int at = 0;
        for (Piece[] ofPart : pieces) {
            Piece piece = ofPart[partition];
            System.arraycopy(piece.hashes(), 0, hashes, at, piece.hashes().length);
            System.arraycopy(piece.places(), 0, places, at, piece.places().length);
            at += piece.places().length;
        }
        int[] order = mergeOrder(runs);
        Batch keys = Batches.concat(keySchema, keyParts).select(order);
        int[] sortedHashes = new int[m];
        for (int i = 0; i < m; i++) {
            sortedHashes[i] = hashes[order[i]];
        }
        int[] storedColumns = new int[keyColumns.length];
        List<Integer> stored = new ArrayList<>();
        for (int k = 0; k < storedColumns.length; k++) {
            storedColumns[k] = k;
            stored.add(k);
        }
        GroupTable table = new GroupTable(stored);
        int[] groupOf = table.groups(keys, sortedHashes, InterpretedKeys.INSTANCE,
                b -> reserve(Batches.estimateBytes(b)));
        int groups = table.size();
        reserve(BYTES_PER_GROUP * groups);
        long[] firstPlaces = new long[groups];
        int[] groupAt = new int[m];
        for (int i = 0, next = 0; i < m; i++) {
            int g = groupOf[i];
            if (g == next) {
                firstPlaces[next++] = places[order[i]];
            }
            groupAt[order[i]] = g;
        }
        AggregateState[] states = newStates();
        at = 0;
        for (int w = 0; w < pieces.length; w++) {
            Piece piece = pieces[w][partition];
            AggregateState[] own = parts.get(w).states;
            int[] local = piece.groups();
            for (int j = 0; j < local.length; j++) {
                if ((j & (CHECK_INTERVAL - 1)) == 0) {
                    checkCancelled();
                }
                for (int c = 0; c < states.length; c++) {
                    states[c].combine(groupAt[at + j], own[c], local[j]);
                }
            }
            at += local.length;
            int rows = piece.raw().rowCount();
            if (rows > 0) {
                checkCancelled();
                int[] rawGroups = Arrays.copyOfRange(groupAt, at, at + rows);
                for (AggregateState state : states) {
                    state.add(piece.raw(), rawGroups, rows, groups);
                }
                at += rows;
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
     * The result of the merge of a partition: the results of its groups and
     * the places of their first rows, both {@code null} without groups, or
     * the failure of the first group whose result fails, and its place.
     */
    private record Merged(Batch batch, long[] places, RuntimeException failure, long failedAt) {
    }

    /**
     * What a part holds of a partition: the keys of its groups, their
     * numbers in the part and the hashes and places of their keys, then its
     * rows as they are with the hashes and places of their keys; the places
     * of the groups are those of their first rows, all in the order of the
     * places.
     */
    private record Piece(Batch keys, int[] groups, Batch raw, int[] hashes, long[] places) {
    }

    /** Returns the states of the aggregates for new groups. */
    private AggregateState[] newStates() {
        List<AggregateCall> calls = aggregate.aggregates();
        AggregateState[] states = new AggregateState[calls.size()];
        for (int i = 0; i < states.length; i++) {
            AggregateState state = unboxed ? AggregateState.of(calls.get(i), inputSchema) : null;
            states[i] = state != null ? state : new Accumulators(calls.get(i));
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
        return Math.min(morsel, MAX_MORSEL) << 40 | Math.min(row, MAX_ROW);
    }

    /**
     * Merges runs of places, each in ascending order, and returns the
     * indexes of the places in the runs one after the other, in the order of
     * the places; of equal places, the one of the earlier run comes first.
     */
    static int[] mergeOrder(List<long[]> runs) {
        int r = runs.size();
        int[] start = new int[r];
        int[] at = new int[r];
        int n = 0;
        for (int i = 0; i < r; i++) {
            start[i] = n;
            n += runs.get(i).length;
        }
        // A binary heap of the runs that have places left, by their next place.
        int[] heap = new int[r];
        int size = 0;
        for (int i = 0; i < r; i++) {
            if (runs.get(i).length > 0) {
                heap[size++] = i;
                siftUp(heap, size - 1, runs, at);
            }
        }
        int[] order = new int[n];
        for (int k = 0; k < n; k++) {
            int i = heap[0];
            order[k] = start[i] + at[i];
            at[i]++;
            if (at[i] == runs.get(i).length) {
                heap[0] = heap[--size];
            }
            siftDown(heap, size, runs, at);
        }
        return order;
    }

    private static boolean before(int x, int y, List<long[]> runs, int[] at) {
        long a = runs.get(x)[at[x]];
        long b = runs.get(y)[at[y]];
        return a < b || a == b && x < y;
    }

    private static void siftUp(int[] heap, int i, List<long[]> runs, int[] at) {
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

    private static void siftDown(int[] heap, int size, List<long[]> runs, int[] at) {
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
     * of their first rows; or, once grouping does not pay, its further rows
     * as they are.
     */
    private final class Partial implements ParallelSegment.Sink {
        /** Whether the part takes all rows. */
        private final boolean alone;
        private final GroupTable table;
        private final AggregateState[] states;
        private int groups;
        private long[] places = new long[16];
        private KeyFunctions keys = InterpretedKeys.INSTANCE;
        private boolean keysAsked;
        private long rowsSeen;
        private int[] noKeys = new int[0];
        private int morsel = -1;
        private long row;
        /** The rows kept as they are, with the hashes of their keys and the place of their first row. */
        private List<Raw> raw;
        private long rawRows;
        /** The rows and groups at the start of the window of rows in which new groups are counted; -1 before. */
        private long windowRows = -1;
        private int windowGroups;

        Partial(boolean alone) {
            this.alone = alone;
            this.table = keyColumns.length == 0 ? null : new GroupTable(keyColumnList);
            this.states = newStates();
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
            if (raw != null) {
                keep(b, first);
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
            for (AggregateState state : states) {
                state.add(b, groupOf, n, groups);
            }
            if (!alone && table != null && groups > maxLocalGroups) {
                if (windowRows < 0) {
                    windowRows = rowsSeen;
                    windowGroups = groups;
                } else if (rowsSeen - windowRows >= maxLocalGroups) {
                    if ((groups - windowGroups) * 2L > rowsSeen - windowRows) {
                        raw = new ArrayList<>();
                    }
                    windowRows = rowsSeen;
                    windowGroups = groups;
                }
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

        /** Keeps the rows of a batch as they are, with the hashes of their keys. */
        private void keep(Batch b, long first) {
            int n = b.rowCount();
            askKeys();
            rowsSeen += n;
            int[] hashes = new int[n];
            keys.hash(b, keyColumns, hashes);
            reserve(Batches.estimateBytes(b) + 4L * n);
            raw.add(new Raw(b, hashes, first));
            rawRows += n;
        }

        /** Divides the groups and the rows of the part into partitions by the hashes of their keys. */
        Piece[] pieces(int partitions) {
            int[] counts = new int[partitions];
            int[] partitionOf = new int[groups];
            for (int g = 0; g < groups; g++) {
                partitionOf[g] = partition(table.hash(g), partitions);
                counts[partitionOf[g]]++;
            }
            int[][] ofPartition = new int[partitions][];
            for (int p = 0; p < partitions; p++) {
                ofPartition[p] = new int[counts[p]];
                counts[p] = 0;
            }
            for (int g = 0; g < groups; g++) {
                int p = partitionOf[g];
                ofPartition[p][counts[p]++] = g;
            }
            Batch all = groups == 0 ? null : table.keys();
            Schema keySchema = inputSchema.select(keyColumnList);
            // The rows kept as they are, partition by partition.
            List<List<Batch>> rawBatches = new ArrayList<>();
            List<List<int[]>> rawHashes = new ArrayList<>();
            List<List<long[]>> rawPlaces = new ArrayList<>();
            for (int p = 0; p < partitions; p++) {
                rawBatches.add(new ArrayList<>());
                rawHashes.add(new ArrayList<>());
                rawPlaces.add(new ArrayList<>());
            }
            if (raw != null) {
                for (Raw r : raw) {
                    int n = r.batch().rowCount();
                    int[] partOfRow = new int[n];
                    int[] rowCounts = new int[partitions];
                    for (int i = 0; i < n; i++) {
                        partOfRow[i] = partition(r.hashes()[i], partitions);
                        rowCounts[partOfRow[i]]++;
                    }
                    int[][] rows = new int[partitions][];
                    for (int p = 0; p < partitions; p++) {
                        rows[p] = new int[rowCounts[p]];
                        rowCounts[p] = 0;
                    }
                    for (int i = 0; i < n; i++) {
                        int p = partOfRow[i];
                        rows[p][rowCounts[p]++] = i;
                    }
                    for (int p = 0; p < partitions; p++) {
                        int[] own = rows[p];
                        if (own.length == 0) {
                            continue;
                        }
                        int[] h = new int[own.length];
                        long[] at = new long[own.length];
                        for (int i = 0; i < own.length; i++) {
                            h[i] = r.hashes()[own[i]];
                            at[i] = r.first() + own[i];
                        }
                        rawBatches.get(p).add(own.length == n ? r.batch() : r.batch().select(own));
                        rawHashes.get(p).add(h);
                        rawPlaces.get(p).add(at);
                    }
                }
            }
            Piece[] pieces = new Piece[partitions];
            for (int p = 0; p < partitions; p++) {
                int[] own = ofPartition[p];
                Batch keysOf = all == null ? Batch.empty(keySchema)
                        : own.length == groups ? all : all.select(own);
                Batch rawOf = Batches.concat(inputSchema, rawBatches.get(p));
                int m = own.length + rawOf.rowCount();
                int[] hashes = new int[m];
                long[] at = new long[m];
                for (int i = 0; i < own.length; i++) {
                    hashes[i] = table.hash(own[i]);
                    at[i] = places[own[i]];
                }
                int i = own.length;
                for (int k = 0; k < rawHashes.get(p).size(); k++) {
                    int[] h = rawHashes.get(p).get(k);
                    System.arraycopy(h, 0, hashes, i, h.length);
                    System.arraycopy(rawPlaces.get(p).get(k), 0, at, i, h.length);
                    i += h.length;
                }
                pieces[p] = new Piece(keysOf, own, rawOf, hashes, at);
            }
            return pieces;
        }
    }

    /** Rows kept as they are, the hashes of their keys and the place of the first of them. */
    private record Raw(Batch batch, int[] hashes, long first) {
    }

    /** The state of an aggregate as one accumulator object per group. */
    private final class Accumulators extends AggregateState {
        private final AggregateCall call;
        private final List<ValueAccumulator> groups = new ArrayList<>();
        private Vector.Builder out;

        Accumulators(AggregateCall call) {
            this.call = call;
        }

        private void grow(int n) {
            while (groups.size() < n) {
                groups.add(new ValueAccumulator(call, inputSchema, HashAggregateOperator.this::reserve));
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
            out = Vectors.builder(call.type(inputSchema), n);
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
