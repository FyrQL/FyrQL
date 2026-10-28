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
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.compiled.InterpretedKeys;
import net.fyrql.exec.compiled.KeyFunctions;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

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
 * {@link ValueAccumulator} per group; an aggregate with a filter passes it
 * only the rows the filter takes. The results are computed group by group,
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
 * <p>
 * With a directory to spill to and a limited budget, a part whose groups
 * do not fit, or would take more than half of the budget, so that the
 * merge has room, keeps its groups and scatters its further rows as they
 * are, as above, also the part that takes all rows and for every
 * aggregate; the partitions go to disk once they do not fit either. The
 * merge then takes the partitions one after the other, see
 * {@link #mergeSpilled}. An aggregation without keys does not spill.
 */
final class HashAggregateOperator extends AbstractOperator {

    private static final long BYTES_PER_GROUP = 128;

    /** The partitions in which the groups of the workers are merged, see {@link Partitions}. */
    private static final int PARTITIONS = Partitions.PARTITIONS;

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

    /** The partitions cut into smaller ones so far because they did not fit, which tests read. */
    static final AtomicLong REFINED = new AtomicLong();

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
    /** The directory to spill to, or {@code null}; the schema of the rows on disk: scattered, hashes, places. */
    private final SpillDirectory spill;
    private final Schema rawSchema;
    private final List<Integer> rawColumns;
    private final List<SpillFile> files = Collections.synchronizedList(new ArrayList<>());
    private List<Batch> output;
    private ExternalSort results;
    private final AtomicLong reserved = new AtomicLong();

    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen) {
        this(label, aggregate, input, budget, batchSize, codegen, true, 1, true, null);
    }

    /** With {@code unboxed} false, every aggregate keeps accumulator objects, which the tests compare against. */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, boolean unboxed) {
        this(label, aggregate, input, budget, batchSize, codegen, unboxed, 1, true, null);
    }

    /**
     * @param threads the most threads that merge the groups of the workers
     * @param ordered whether the groups come in the order of their first rows, as on one thread
     */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, int threads, boolean ordered) {
        this(label, aggregate, input, budget, batchSize, codegen, true, threads, ordered, null);
    }

    /**
     * @param threads the most threads that merge the groups of the workers
     * @param ordered whether the groups come in the order of their first rows, as on one thread
     * @param spill   the directory to spill to when the budget is exhausted, or {@code null} to fail then
     */
    HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, int threads, boolean ordered, SpillDirectory spill) {
        this(label, aggregate, input, budget, batchSize, codegen, true, threads, ordered, spill);
    }

    private HashAggregateOperator(String label, PlanNode.Aggregate aggregate, Operator input, MemoryBudget budget,
            int batchSize, Codegen codegen, boolean unboxed, int threads, boolean ordered, SpillDirectory spill) {
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
        this.spill = spill;
        this.keyColumnList = List.copyOf(aggregate.groupColumns());
        this.keyColumns = keyColumnList.stream().mapToInt(Integer::intValue).toArray();
        TreeSet<Integer> used = new TreeSet<>(keyColumnList);
        for (AggregateCall call : aggregate.aggregates()) {
            if (call.column() >= 0) {
                used.add(call.column());
            }
            for (SortKey k : call.orderBy()) {
                used.add(k.column());
            }
            if (call.filter() >= 0) {
                used.add(call.filter());
            }
        }
        this.scatteredColumns = List.copyOf(used);
        this.scatteredSchema = inputSchema.select(scatteredColumns);
        this.scatteredKeys = keyColumnList.stream().map(scatteredColumns::indexOf).toList();
        // On workers, only mergeable aggregates scatter rows; the part that takes all rows does so for any.
        this.scatteredCalls = aggregate.aggregates().stream()
                .map(c -> c.remap(scatteredColumns::indexOf))
                .toList();
        this.rawSchema = scatteredSchema.concat(Schema.of(new Schema.Field("$hash", DataType.INTEGER, false),
                new Schema.Field("$place", DataType.BIGINT, false)));
        List<Integer> columns = new ArrayList<>();
        for (int c = 0; c < scatteredSchema.size(); c++) {
            columns.add(c);
        }
        this.rawColumns = List.copyOf(columns);
    }

    /**
     * Tells whether the groups of an aggregation merge into one another:
     * {@code count}, {@code sum}, {@code avg}, {@code min}, {@code max},
     * {@code bool_and}, {@code bool_or} and the standard deviations and
     * variances, without {@code DISTINCT} and {@code ORDER BY}, for which
     * the order of the rows does not matter, but for the last bits of
     * doubles.
     */
    static boolean mergeable(PlanNode.Aggregate aggregate) {
        for (AggregateCall call : aggregate.aggregates()) {
            if (call.distinct() || !call.orderBy().isEmpty()) {
                return false;
            }
            switch (call.function()) {
                case COUNT_STAR, COUNT, SUM, AVG, MIN, MAX, BOOL_AND, BOOL_OR, STDDEV_SAMP, STDDEV_POP, VAR_SAMP,
                        VAR_POP -> {
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
        if (results != null) {
            return results.next();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    private List<Batch> aggregateAll() {
        Partial all = new Partial(true);
        for (Batch b = input.next(); b != null; b = input.next()) {
            all.accept(0, b);
        }
        return all.pressured ? mergeSpilled(List.of(all)) : output(all);
    }

    private List<Batch> aggregateParallel(ParallelSegment segment) {
        List<Partial> parts = segment.drain(workers -> new Partial(workers == 1));
        for (Partial p : parts) {
            if (p.pressured) {
                return mergeSpilled(parts);
            }
        }
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
        Workers.run(partitions, threads, p -> merged[p] = mergePartition(parts, pieces, p, this::reserve));
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
            all = all.select(Partitions.mergeOrder(places));
        }
        return new ArrayList<>(Batches.split(all, batchSize));
    }

    /**
     * Merges one partition: the groups and rows of all parts in the order of
     * their places, so that the groups are numbered in the order of their
     * first rows, then the states of the groups of the parts and the rows,
     * and computes the results of the groups in their order.
     *
     * @param reserve takes the bytes of the groups of the partition from the budget
     */
    private Merged mergePartition(List<Partial> parts, Piece[][] pieces, int partition, LongConsumer reserve) {
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
        reserve.accept(storedBytes[0] + BYTES_PER_GROUP * groups);
        firstPlaces = Arrays.copyOf(firstPlaces, groups);
        // Given the places of the rows, min and max keep the first of equal values in whatever order the parts come.
        AggregateState[] states = newStates(scatteredCalls, scatteredSchema, reserve);
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
            long morsel = places[entry + from] >>> Partitions.ROW_BITS;
            to = from + 1;
            while (to < n && places[entry + to] >>> Partitions.ROW_BITS == morsel) {
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
     * segment of its own, in the order of {@link Partitions#mergeOrder}.
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
        for (int index : Partitions.mergeOrder(runs)) {
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

    /**
     * Returns the states of aggregates over the columns of a schema for new
     * groups.
     *
     * @param reserve takes the bytes of the values that accumulators keep
     */
    private AggregateState[] newStates(List<AggregateCall> calls, Schema schema, LongConsumer reserve) {
        AggregateState[] states = new AggregateState[calls.size()];
        for (int i = 0; i < states.length; i++) {
            AggregateState state = unboxed ? AggregateState.of(calls.get(i), schema) : null;
            states[i] = state != null ? state : new Accumulators(calls.get(i), schema, reserve);
            if (calls.get(i).filter() >= 0) {
                states[i] = new Filtered(states[i], calls.get(i).filter());
            }
        }
        return states;
    }

    private void reserve(long bytes) {
        budget.reserve(bytes, label());
        reserved.addAndGet(bytes);
    }

    /**
     * Takes bytes from the budget for the groups or the rows of a part if
     * they are left and the operator then holds at most half of the budget,
     * so that the merge has room.
     */
    private boolean tryTake(long bytes) {
        if (reserved.get() + bytes > budget.limit() / 2 || !budget.tryReserve(bytes)) {
            return false;
        }
        reserved.addAndGet(bytes);
        return true;
    }

    private void release(long bytes) {
        budget.release(bytes);
        reserved.addAndGet(-bytes);
    }

    /** Creates a file of this operator, which closing it deletes. */
    private SpillFile newFile(String what, Schema schema) {
        SpillFile file = spill.create(label(), what, schema, context());
        files.add(file);
        return file;
    }

    /** Returns rows of {@link #scatteredSchema} with the hashes and places of their keys, see {@link #rawSchema}. */
    private Batch withHashesAndPlaces(Batch rows, int[] hashes, long[] places) {
        List<Vector> columns = new ArrayList<>(rows.columns());
        columns.add(IntVector.of(hashes));
        columns.add(BigIntVector.of(places));
        return new Batch(rawSchema, rows.rowCount(), columns);
    }

    /**
     * Thrown when a merge of a partition that went to disk does not fit in
     * the budget, so that the partition is cut into smaller ones.
     */
    @SuppressWarnings("serial")
    private static final class OverBudget extends RuntimeException {
        final long bytes;

        OverBudget(long bytes) {
            super(null, null, false, false);
            this.bytes = bytes;
        }
    }

    /** Takes bytes from the budget for the merge of a partition, until it gives them all back. */
    private final class Attempt implements LongConsumer {
        private long bytes;

        @Override
        public void accept(long more) {
            if (!budget.tryReserve(more)) {
                throw new OverBudget(more);
            }
            bytes += more;
        }

        /** Gives back bytes taken before. */
        void giveBack(long fewer) {
            budget.release(fewer);
            bytes -= fewer;
        }

        void release() {
            budget.release(bytes);
            bytes = 0;
        }
    }

    /**
     * Reads a file of rows that went to disk a batch at a time, with the
     * hashes and the places of their keys; the rows are in the order of
     * their places.
     */
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
            IntVector h = (IntVector) b.column(scatteredSchema.size());
            BigIntVector p = (BigIntVector) b.column(scatteredSchema.size() + 1);
            hashes = new int[n];
            places = new long[n];
            for (int r = 0; r < n; r++) {
                hashes[r] = h.getInt(r);
                places[r] = p.getLong(r);
            }
            batch = b;
            at = 0;
            return true;
        }
    }

    /**
     * What the merge of a partition of the rows that went to disk reads, in
     * the order of the places: the groups of a part there, or the rows of a
     * file, a batch at a time, each reserved while it is read.
     */
    private final class Source {
        /** The part of the groups, or {@code null} for the rows of a file. */
        final Partial part;
        private final int[] localGroups;
        private final Cursor cursor;
        private final Attempt attempt;
        /** The keys of the rows at hand, their hashes and places; the rows themselves, {@code null} for groups. */
        Batch keys;
        int[] hashes;
        long[] places;
        Batch raw;
        int at;
        private long bytes;

        /** The groups of a part, with their keys, hashes and places, in the order of their numbers. */
        Source(Partial part, Batch keys, int[] localGroups, int[] hashes, long[] places) {
            this.part = part;
            this.localGroups = localGroups;
            this.cursor = null;
            this.attempt = null;
            this.keys = keys;
            this.hashes = hashes;
            this.places = places;
        }

        /** The rows of a file. */
        Source(SpillFile file, Attempt attempt) {
            this.part = null;
            this.localGroups = null;
            this.cursor = new Cursor(file);
            this.attempt = attempt;
            next();
        }

        /** Tells whether rows are at hand. */
        boolean hasRows() {
            return keys != null && at < keys.rowCount();
        }

        /** Returns the group of the part of a row at hand, which is a group of the part. */
        int localGroup(int row) {
            return localGroups[row];
        }

        /** Goes on with the next batch of the file, giving back the one at hand; none after the groups. */
        void next() {
            keys = null;
            raw = null;
            if (cursor == null) {
                return;
            }
            attempt.giveBack(bytes);
            bytes = 0;
            if (!cursor.next()) {
                return;
            }
            bytes = Batches.estimateBytes(cursor.batch);
            attempt.accept(bytes);
            hashes = cursor.hashes;
            places = cursor.places;
            raw = cursor.batch.project(rawColumns);
            keys = raw.project(scatteredKeys);
            at = 0;
        }
    }

    /**
     * Merges a partition of the rows that went to disk: the groups of the
     * parts there and the rows of its files in the order of their places, a
     * run of rows of one of them at a time, so that the groups are numbered
     * in the order of their first rows, the states of the groups of the
     * parts combined and the rows added as they come; then computes the
     * results of the groups in their order. Only the groups of the partition
     * and a batch of each file are in memory, reserved by the attempt.
     */
    private Merged mergeStreamed(List<Partial> parts, Work w, Attempt attempt) {
        List<Integer> stored = new ArrayList<>();
        for (int k = 0; k < keyColumns.length; k++) {
            stored.add(k);
        }
        GroupTable table = new GroupTable(stored);
        // The key functions of the workers, compiled once they were due, hash as the interpreted ones.
        KeyFunctions keys = parts.get(0).keys;
        AggregateState[] states = newStates(scatteredCalls, scatteredSchema, attempt);
        List<Source> sources = new ArrayList<>();
        for (Partial p : parts) {
            Source s = p.source(w, attempt);
            if (s.hasRows()) {
                sources.add(s);
            }
        }
        for (SpillFile f : w.files()) {
            Source s = new Source(f, attempt);
            if (s.hasRows()) {
                sources.add(s);
            }
        }
        long[] firstPlaces = new long[16];
        int groups = 0;
        long[] storedBytes = new long[1];
        int done = 0;
        while (!sources.isEmpty()) {
            if ((done++ & (CHECK_INTERVAL - 1)) == 0) {
                checkCancelled();
            }
            long[] least = Partitions.least(sources.size(), i -> sources.get(i).places[sources.get(i).at]);
            int best = (int) least[0];
            Source s = sources.get(best);
            int from = s.at;
            int to = from + 1;
            int n = s.keys.rowCount();
            while (to < n && s.places[to] < least[1]) {
                to++;
            }
            storedBytes[0] = 0;
            int before = groups;
            int[] groupOf = table.groups(s.keys, from, to, s.hashes, from, keys,
                    b -> storedBytes[0] += Batches.estimateBytes(b));
            for (int i = 0; i < groupOf.length; i++) {
                if (groupOf[i] == groups) {
                    firstPlaces = AggregateState.grow(firstPlaces, groups + 1);
                    firstPlaces[groups++] = s.places[from + i];
                }
            }
            attempt.accept(storedBytes[0] + BYTES_PER_GROUP * (groups - before));
            if (s.raw == null) {
                AggregateState[] own = s.part.states;
                for (int i = 0; i < groupOf.length; i++) {
                    for (int c = 0; c < states.length; c++) {
                        states[c].combine(groupOf[i], own[c], s.localGroup(from + i));
                    }
                }
            } else {
                Batch rows = s.raw;
                if (from > 0 || to < n) {
                    int[] range = new int[to - from];
                    for (int i = 0; i < range.length; i++) {
                        range[i] = from + i;
                    }
                    rows = rows.select(range);
                }
                for (AggregateState state : states) {
                    state.add(rows, groupOf, to - from, groups, s.places, from);
                }
            }
            s.at = to;
            if (to == n) {
                s.next();
                if (!s.hasRows()) {
                    sources.remove(best);
                }
            }
        }
        if (groups == 0) {
            return new Merged(null, null, null, 0);
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
            } catch (QueryCancelledException | OverBudget e) {
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
        return new Merged(new Batch(schema(), groups, columns), Arrays.copyOf(firstPlaces, groups), null, 0);
    }

    /**
     * A partition of the rows that went to disk: those whose hash is in
     * {@code index} of {@code count} partitions, see {@link Partitions#partition}, in
     * files whose rows are each in the order of their places.
     */
    private record Work(int index, int count, List<SpillFile> files) {
    }

    /**
     * Merges the groups and the rows of parts of which one or more went
     * over the budget, on the thread of the query: the rows kept as they
     * are all go to disk first, then the partitions are merged one after the
     * other, each with what it needs reserved in the budget, which it gives
     * back once its results are on disk with the places of the first rows of
     * their groups. A partition that does not fit is cut into
     * {@value Partitions#PARTITIONS} smaller ones by more of the bits of the hashes of
     * the keys, its rows going to disk again, a file for each, down to
     * {@value Partitions#MAX_PARTITIONS} partitions. The results are then read merged
     * by their places, see {@link ExternalSort}, so that the groups come in
     * the order of their first rows and the group that fails is the first
     * one, as in memory.
     */
    private List<Batch> mergeSpilled(List<Partial> parts) {
        for (Partial p : parts) {
            p.flush();
        }
        Deque<Work> work = new ArrayDeque<>();
        for (int i = 0; i < PARTITIONS; i++) {
            List<SpillFile> of = new ArrayList<>();
            for (Partial p : parts) {
                if (p.files != null && p.files[i] != null) {
                    of.add(p.files[i]);
                }
            }
            work.add(new Work(i, PARTITIONS, of));
        }
        Schema runSchema = ExternalSort.runSchema(schema());
        List<ExternalSort.Run> runs = new ArrayList<>();
        // The results of the partitions stay in memory while they take at most a quarter of the budget.
        List<Merged> held = new ArrayList<>();
        long heldBytes = 0;
        RuntimeException failure = null;
        long failedAt = Long.MAX_VALUE;
        while (!work.isEmpty()) {
            Work w = work.poll();
            Attempt attempt = new Attempt();
            List<Work> finer = null;
            boolean again = false;
            try {
                Merged m = mergeStreamed(parts, w, attempt);
                if (m.failure() != null && m.failedAt() < failedAt) {
                    failure = m.failure();
                    failedAt = m.failedAt();
                }
                if (m.batch() != null) {
                    long bytes = Batches.estimateBytes(m.batch()) + 8L * m.batch().rowCount();
                    if (heldBytes + bytes > budget.limit() / 4 || !budget.tryReserve(bytes)) {
                        if (!held.isEmpty()) {
                            runs.add(writeRun(inPlaceOrder(held), runSchema));
                            held.clear();
                            release(heldBytes);
                            heldBytes = 0;
                        }
                        runs.add(writeRun(m, runSchema));
                    } else {
                        reserved.addAndGet(bytes);
                        held.add(m);
                        heldBytes += bytes;
                    }
                }
            } catch (OverBudget e) {
                attempt.release();
                if (!held.isEmpty()) {
                    // The results in memory go to disk first, and the partition is merged again.
                    runs.add(writeRun(inPlaceOrder(held), runSchema));
                    held.clear();
                    release(heldBytes);
                    heldBytes = 0;
                    again = true;
                } else if ((long) w.count() * PARTITIONS > Partitions.MAX_PARTITIONS) {
                    throw new QueryException(label(), "Memory budget of " + budget.limit() + " bytes exceeded: "
                            + (budget.used() + e.bytes) + " bytes needed after spilling "
                            + context().spill().bytesWritten() + " bytes");
                } else {
                    REFINED.incrementAndGet();
                    finer = refine(w);
                }
            } finally {
                attempt.release();
            }
            if (again) {
                work.addFirst(w);
                continue;
            }
            for (SpillFile f : w.files()) {
                f.close();
            }
            if (finer != null) {
                for (int i = finer.size() - 1; i >= 0; i--) {
                    work.addFirst(finer.get(i));
                }
            }
        }
        // The groups of the parts are merged; what they took goes to the merge of the results.
        budget.release(reserved.getAndSet(heldBytes) - heldBytes);
        if (failure != null) {
            throw failure;
        }
        if (runs.isEmpty()) {
            return held.isEmpty() ? new ArrayList<>()
                    : new ArrayList<>(Batches.split(inPlaceOrder(held).batch(), batchSize));
        }
        if (!held.isEmpty()) {
            runs.add(writeRun(inPlaceOrder(held), runSchema));
            release(heldBytes);
        }
        results = new ExternalSort(label(), schema(), List.of(), budget, spill, context(), batchSize);
        results.start(runs);
        return new ArrayList<>();
    }

    /** Returns the results of partitions as one, in the order of the places of the first rows of their groups. */
    private Merged inPlaceOrder(List<Merged> merged) {
        if (merged.size() == 1) {
            return merged.get(0);
        }
        List<Batch> batches = new ArrayList<>();
        List<long[]> places = new ArrayList<>();
        for (Merged m : merged) {
            batches.add(m.batch());
            places.add(m.places());
        }
        int[] order = Partitions.mergeOrder(places);
        long[] all = new long[order.length];
        int at = 0;
        for (long[] p : places) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        long[] sorted = new long[order.length];
        for (int i = 0; i < order.length; i++) {
            sorted[i] = all[order[i]];
        }
        return new Merged(Batches.concat(schema(), batches).select(order), sorted, null, 0);
    }

    /** Writes the results of a partition with the places of the first rows of their groups. */
    private ExternalSort.Run writeRun(Merged m, Schema runSchema) {
        SpillFile file = newFile("groups", runSchema);
        long batchBytes = 0;
        int from = 0;
        Batch all = m.batch();
        int step = ExternalSort.runRows(Batches.estimateBytes(all), all.rowCount(), budget, batchSize);
        for (Batch b : Batches.split(all, step)) {
            int to = from + b.rowCount();
            Batch withPlaces = ExternalSort.withPlaces(runSchema, b, Arrays.copyOfRange(m.places(), from, to));
            batchBytes = Math.max(batchBytes, Batches.estimateBytes(withPlaces));
            file.append(withPlaces);
            from = to;
        }
        file.finish();
        return new ExternalSort.Run(file, batchBytes);
    }

    /**
     * Cuts a partition into {@value Partitions#PARTITIONS} smaller ones: the rows of
     * its files, read in the order of their places, go to a new file for
     * each smaller partition, in this order.
     */
    private List<Work> refine(Work w) {
        int count = w.count() * PARTITIONS;
        SpillFile[] finer = new SpillFile[PARTITIONS];
        List<Cursor> cursors = new ArrayList<>();
        for (SpillFile f : w.files()) {
            Cursor c = new Cursor(f);
            if (c.next()) {
                cursors.add(c);
            }
        }
        while (!cursors.isEmpty()) {
            long[] least = Partitions.least(cursors.size(), i -> cursors.get(i).places[cursors.get(i).at]);
            int best = (int) least[0];
            Cursor c = cursors.get(best);
            int n = c.batch.rowCount();
            int from = c.at;
            int to = from + 1;
            while (to < n && c.places[to] < least[1]) {
                to++;
            }
            int[] sub = new int[to - from];
            int[] counts = new int[PARTITIONS];
            for (int r = from; r < to; r++) {
                sub[r - from] = Partitions.partition(c.hashes[r], count) - w.index() * PARTITIONS;
                counts[sub[r - from]]++;
            }
            for (int s = 0; s < PARTITIONS; s++) {
                if (counts[s] == 0) {
                    continue;
                }
                int[] rows = new int[counts[s]];
                for (int r = from, k = 0; r < to; r++) {
                    if (sub[r - from] == s) {
                        rows[k++] = r;
                    }
                }
                if (finer[s] == null) {
                    finer[s] = newFile("partition", rawSchema);
                }
                finer[s].append(counts[s] == n ? c.batch : c.batch.select(rows));
            }
            c.at = to;
            if (to == n && !c.next()) {
                cursors.remove(best);
            }
        }
        List<Work> works = new ArrayList<>(PARTITIONS);
        for (int s = 0; s < PARTITIONS; s++) {
            works.add(new Work(w.index() * PARTITIONS + s, count, finer[s] == null ? List.of() : List.of(finer[s])));
        }
        return works;
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
        /** Whether the part spills, see {@link #tryTake}, and whether it went over the budget. */
        private final boolean spills;
        private boolean pressured;
        /**
         * In a part that spills: the bytes taken for a batch before grouping
         * it, those its groups use, and those of the rows kept.
         */
        private long taken;
        private long used;
        private long scatteredBytes;
        private boolean overBudget;
        /** The files of the rows kept as they are, per partition, once they go to disk. */
        private SpillFile[] files;

        Partial(boolean alone) {
            this.alone = alone;
            this.table = keyColumns.length == 0 ? null : new GroupTable(keyColumnList);
            this.spills = table != null && spill != null && budget.limit() < Long.MAX_VALUE;
            this.states = newStates(aggregate.aggregates(), inputSchema, this::reserveValue);
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
            long first = Partitions.place(morsel, row);
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
                if (spills) {
                    // At most every row is a new group; the half of the budget for the merge stays free.
                    long worst = Batches.estimateBytes(b.project(keyColumnList)) + BYTES_PER_GROUP * n;
                    if (!tryTake(worst)) {
                        pressure();
                        scatter(b, first);
                        return;
                    }
                    taken = worst;
                }
                rowsSeen += n;
                groupOf = table.groups(b, keys, stored -> keep(Batches.estimateBytes(stored)));
                if (table.size() > groups) {
                    keep(BYTES_PER_GROUP * (table.size() - groups));
                    places = AggregateState.grow(places, table.size());
                    // New groups are numbered in the order of their first rows.
                    for (int r = 0, next = groups; next < table.size(); r++) {
                        if (groupOf[r] == next) {
                            places[next++] = first + r;
                        }
                    }
                    groups = table.size();
                }
                if (spills) {
                    settle();
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
            if (overBudget) {
                pressure();
            }
            if (!alone && table != null && groups > nextCheck && scattered == null) {
                if (rowsSeen - checkedRows < (long) minRowsPerGroup * (groups - checkedGroups)) {
                    startScattering();
                }
                checkedRows = rowsSeen;
                checkedGroups = groups;
                nextCheck = (int) Math.min(Integer.MAX_VALUE, 2L * groups);
            }
        }

        private void startScattering() {
            scattered = new ArrayList<>();
            for (int p = 0; p < PARTITIONS; p++) {
                scattered.add(new ArrayList<>());
            }
            scatteredHashes = new int[PARTITIONS][16];
            scatteredPlaces = new long[PARTITIONS][16];
            scatteredRows = new int[PARTITIONS];
        }

        /** The part went over the budget: it keeps its groups and scatters its further rows. */
        private void pressure() {
            pressured = true;
            if (scattered == null) {
                startScattering();
            }
        }

        /** Takes bytes for the groups of a batch, at once or, in a part that spills, once they are grouped. */
        private void keep(long bytes) {
            if (spills) {
                used += bytes;
            } else {
                reserve(bytes);
            }
        }

        /** Gives back what the part took for a batch beyond what its groups use, or takes what they use beyond. */
        private void settle() {
            if (used < taken) {
                release(taken - used);
            } else {
                take(used - taken);
            }
            taken = 0;
            used = 0;
        }

        /** Takes the bytes of the values that the accumulators of the part keep. */
        private void reserveValue(long bytes) {
            if (!spills) {
                reserve(bytes);
            } else {
                take(bytes);
            }
        }

        /**
         * Takes bytes of memory the part holds already, such as the values of
         * accumulators; if they do not fit, they are taken anyway and the part
         * scatters the rows of the next batches, so that it goes beyond the
         * budget by the values of a batch at most.
         */
        private void take(long bytes) {
            if (bytes > 0 && !tryTake(bytes)) {
                budget.reserveAnyway(bytes);
                reserved.addAndGet(bytes);
                overBudget = true;
            }
        }

        private void askKeys() {
            if (!keysAsked && codegen.due(rowsSeen)) {
                keysAsked = true;
                KeyFunctions compiled = codegen.keys(keyColumnList.stream().map(inputSchema::type).toList(),
                        context());
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
                int p = Partitions.partition(hashes[r], PARTITIONS);
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
            int[][] owns = new int[PARTITIONS][];
            Batch[] rowsOf = new Batch[PARTITIONS];
            for (int p = 0; p < PARTITIONS; p++) {
                int count = start[p + 1] - start[p];
                if (count == 0) {
                    continue;
                }
                owns[p] = Arrays.copyOfRange(byPartition, start[p], start[p + 1]);
                rowsOf[p] = count == n ? columns : columns.select(owns[p]);
                bytes += Batches.estimateBytes(rowsOf[p]) + 12L * count;
            }
            rawRows += n;
            if (spills && !tryTake(bytes)) {
                // The rows kept go to disk first, so that the rows of each file stay in the order of their places.
                pressured = true;
                flush();
                for (int p = 0; p < PARTITIONS; p++) {
                    if (rowsOf[p] == null) {
                        continue;
                    }
                    int[] own = owns[p];
                    int[] h = new int[own.length];
                    long[] at = new long[own.length];
                    for (int i = 0; i < own.length; i++) {
                        h[i] = hashes[own[i]];
                        at[i] = first + own[i];
                    }
                    file(p).append(withHashesAndPlaces(rowsOf[p], h, at));
                }
                return;
            }
            if (spills) {
                scatteredBytes += bytes;
            } else {
                reserve(bytes);
            }
            for (int p = 0; p < PARTITIONS; p++) {
                if (rowsOf[p] == null) {
                    continue;
                }
                int[] own = owns[p];
                int count = own.length;
                scattered.get(p).add(rowsOf[p]);
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
        }

        /** Returns the file of the rows of a partition, created when the first of them goes to disk. */
        private SpillFile file(int partition) {
            if (files == null) {
                files = new SpillFile[PARTITIONS];
            }
            if (files[partition] == null) {
                files[partition] = newFile("partition-" + partition, rawSchema);
            }
            return files[partition];
        }

        /** Writes the rows kept as they are in memory to the files of their partitions and gives their bytes back. */
        void flush() {
            if (scattered == null) {
                return;
            }
            for (int p = 0; p < PARTITIONS; p++) {
                int at = 0;
                for (Batch rows : scattered.get(p)) {
                    int count = rows.rowCount();
                    file(p).append(withHashesAndPlaces(rows, Arrays.copyOfRange(scatteredHashes[p], at, at + count),
                            Arrays.copyOfRange(scatteredPlaces[p], at, at + count)));
                    at += count;
                }
                scattered.get(p).clear();
                scatteredRows[p] = 0;
            }
            release(scatteredBytes);
            scatteredBytes = 0;
        }

        /**
         * Returns what the part holds of a partition of the rows that went
         * to disk: the groups whose hashes fall into it, their keys reserved
         * for the merge.
         */
        Source source(Work w, Attempt attempt) {
            int[] own = new int[16];
            int count = 0;
            for (int g = 0; g < groups; g++) {
                if (Partitions.partition(table.hash(g), w.count()) == w.index()) {
                    own = count == own.length ? Arrays.copyOf(own, count * 2) : own;
                    own[count++] = g;
                }
            }
            own = Arrays.copyOf(own, count);
            Batch keysOf = count == 0 ? Batch.empty(inputSchema.select(keyColumnList)) : table.keys(own);
            attempt.accept(Batches.estimateBytes(keysOf));
            int[] hashesOf = new int[count];
            long[] at = new long[count];
            for (int i = 0; i < count; i++) {
                hashesOf[i] = table.hash(own[i]);
                at[i] = places[own[i]];
            }
            return new Source(this, keysOf, own, hashesOf, at);
        }

        /** Divides the groups and the rows of the part into partitions by the hashes of their keys. */
        Piece[] pieces(int partitions) {
            int[] counts = new int[partitions];
            int[] partitionOfGroup = new int[groups];
            for (int g = 0; g < groups; g++) {
                partitionOfGroup[g] = Partitions.partition(table.hash(g), partitions);
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

    /** The state of an aggregate that takes only the rows where its filter column is true. */
    private static final class Filtered extends AggregateState {
        private final AggregateState state;
        private final int filter;

        Filtered(AggregateState state, int filter) {
            this.state = state;
            this.filter = filter;
        }

        /** Returns the rows the filter takes, or {@code null} if it takes all of them. */
        private int[] taken(Batch b, int rows) {
            Vector v = b.column(filter);
            int[] taken = new int[rows];
            int n = 0;
            for (int r = 0; r < rows; r++) {
                if (!v.isNull(r) && (v instanceof BooleanVector bv ? bv.getBoolean(r) : (Boolean) v.get(r))) {
                    taken[n++] = r;
                }
            }
            return n == rows ? null : Arrays.copyOf(taken, n);
        }

        private static int[] groupsOf(int[] groupOf, int[] taken) {
            int[] result = new int[taken.length];
            for (int i = 0; i < taken.length; i++) {
                result[i] = groupOf[taken[i]];
            }
            return result;
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups) {
            int[] taken = taken(b, rows);
            if (taken == null) {
                state.add(b, groupOf, rows, groups);
            } else {
                state.add(b.select(taken), groupsOf(groupOf, taken), taken.length, groups);
            }
        }

        @Override
        void add(Batch b, int[] groupOf, int rows, int groups, long[] places, int from) {
            int[] taken = taken(b, rows);
            if (taken == null) {
                state.add(b, groupOf, rows, groups, places, from);
            } else {
                long[] at = new long[taken.length];
                for (int i = 0; i < taken.length; i++) {
                    at[i] = places[from + taken[i]];
                }
                state.add(b.select(taken), groupsOf(groupOf, taken), taken.length, groups, at, 0);
            }
        }

        @Override
        boolean keepsPlaces() {
            return state.keepsPlaces();
        }

        @Override
        void combine(int group, AggregateState other, int otherGroup) {
            state.combine(group, ((Filtered) other).state, otherGroup);
        }

        @Override
        void startResult(int groups) {
            state.startResult(groups);
        }

        @Override
        void result(int group) {
            state.result(group);
        }

        @Override
        Vector finishResult() {
            return state.finishResult();
        }
    }

    /** The state of an aggregate as one accumulator object per group. */
    private static final class Accumulators extends AggregateState {
        private final AggregateCall call;
        private final Schema schema;
        private final LongConsumer reserve;
        private final List<ValueAccumulator> groups = new ArrayList<>();
        private Vector.Builder out;

        Accumulators(AggregateCall call, Schema schema, LongConsumer reserve) {
            this.call = call;
            this.schema = schema;
            this.reserve = reserve;
        }

        private void grow(int n) {
            while (groups.size() < n) {
                groups.add(new ValueAccumulator(call, schema, reserve));
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
                // Only the part that takes all rows combines aggregates that do not merge, into new groups.
                if (ValueAccumulator.mergeable(call)) {
                    groups.get(group).merge(o.groups.get(otherGroup));
                } else {
                    groups.get(group).copyOf(o.groups.get(otherGroup));
                }
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
        try {
            input.close();
        } finally {
            try {
                if (results != null) {
                    results.close();
                }
                synchronized (files) {
                    for (SpillFile f : files) {
                        f.close();
                    }
                    files.clear();
                }
            } finally {
                budget.release(reserved.getAndSet(0));
            }
        }
    }
}
