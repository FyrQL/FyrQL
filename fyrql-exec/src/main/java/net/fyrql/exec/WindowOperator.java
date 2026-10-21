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
import net.fyrql.core.plan.PlanNode.BoundKind;
import net.fyrql.core.plan.PlanNode.Frame;
import net.fyrql.core.plan.PlanNode.FrameBound;
import net.fyrql.core.plan.PlanNode.FrameUnit;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.PlanNode.WindowCall;
import net.fyrql.core.plan.PlanNode.WindowFunction;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Computes the calls of a {@link PlanNode.Window}: materializes the input,
 * sorts it by the partition columns and then the order keys, and computes
 * each call partition by partition. The output has the rows in that order,
 * each with its result per call after its columns.
 * <p>
 * The peers of a row are the rows of its partition with equal order keys,
 * {@code NULL} equal to {@code NULL}. A frame is a range of rows of the
 * partition, as DuckDB takes it: {@code ROWS} counts rows from the current
 * one, a negative offset counting the other way; {@code GROUPS} counts
 * groups of peers; {@code RANGE} reaches to the first or last peer, or
 * with an offset to the rows whose key lies within the offset of the key
 * of the current row (see {@link RangeBounds}), and for a row whose key is
 * {@code NULL} to its peers. An exclusion cuts the frame into up to three
 * pieces. {@code IGNORE NULLS} counts only the rows whose value is not
 * {@code NULL}; a filter leaves the rows out of an aggregate whose filter is
 * not true.
 * <p>
 * An aggregate over a frame comes from a {@link SegmentTree} per partition,
 * or, for the aggregates that keep their values ({@code DISTINCT}, an
 * {@code ORDER BY}, {@code string_agg}, {@code list}), from the rows of the
 * frame one by one. Rows with the same frame as the row before share its
 * result.
 * <p>
 * On more than one thread, the input is sorted on several threads, see
 * {@link RowOrder#sort(Runnable, int)}, over a segment the workers keep its
 * batches, see {@link SortSink}, and the
 * partitions are computed in ranges of about equal numbers of rows, one
 * task each, see {@link Workers#run}; each call writes the results of a
 * partition to the places of its rows, so that they do not depend on the
 * threads. A task computes its partitions in their order, the calls of
 * each in theirs, so that the failure is the one of one thread.
 * <p>
 * The budget takes the input, the results and the nodes of the trees.
 * <p>
 * With a directory to spill to, the input that does not fit in the budget
 * is sorted as a sort over the budget sorts it, see {@link ExternalSort},
 * and the rows are then computed as they come, a few whole partitions at a
 * time, each with the rows of its partitions reserved; a partition that
 * does not fit fails the query. The results are the same.
 */
final class WindowOperator extends AbstractOperator {

    /** The source of a row without a value, and of a row that takes the default. */
    private static final int NONE = -1;
    private static final int DEFAULT = -2;
    /** The bytes of the peers of a row, of a result, of a key of a range and of an index of a value. */
    private static final long BYTES_PER_ROW = 16;
    private static final long BYTES_PER_RESULT = 16;
    private static final long BYTES_PER_KEY = 32;
    private static final long BYTES_PER_INDEX = 8;
    private static final long BYTES_PER_NODE = 128;
    /** The fewest rows of a range of partitions of its own, see {@link #ranges}. */
    private static final int MIN_RANGE_ROWS = 4096;

    private final PlanNode.Window window;
    private final Operator input;
    private final MemoryBudget budget;
    private final int batchSize;
    private final Schema inputSchema;
    private final boolean trees;
    private final int threads;
    private final SpillDirectory spill;
    private List<Batch> output;
    private final AtomicLong reserved = new AtomicLong();
    /** Once the input went to disk: its rows in order, and those of the partition read last, not yet computed. */
    private ExternalSort external;
    private final List<SortSink> sinks = new ArrayList<>();
    private final List<Batch> pending = new ArrayList<>();
    private long pendingBytes;

    // The sorted input and the peers of each of its rows, while the calls are computed.
    private Batch rows;
    /** The first row of the peers of a row. */
    private int[] peerStart;
    /** The row after the last peer of a row. */
    private int[] peerEnd;
    /** The number of the peers of a row within its partition, from 0. */
    private int[] peerGroup;
    /** At the first row of a partition plus a number of peers, the first row of these peers. */
    private int[] groupRow;

    WindowOperator(String label, PlanNode.Window window, Operator input, MemoryBudget budget, int batchSize) {
        this(label, window, input, budget, batchSize, 1);
    }

    /**
     * @param threads the most threads that sort the input and compute the partitions
     */
    WindowOperator(String label, PlanNode.Window window, Operator input, MemoryBudget budget, int batchSize,
            int threads) {
        this(label, window, input, budget, batchSize, true, threads, null);
    }

    /**
     * @param threads the most threads that sort the input and compute the partitions
     * @param spill   the directory to spill to when the budget is exhausted, or {@code null} to fail then
     */
    WindowOperator(String label, PlanNode.Window window, Operator input, MemoryBudget budget, int batchSize,
            int threads, SpillDirectory spill) {
        this(label, window, input, budget, batchSize, true, threads, spill);
    }

    /**
     * With {@code trees} false, every aggregate takes the rows of each frame
     * one by one, which the tests compare against.
     */
    WindowOperator(String label, PlanNode.Window window, Operator input, MemoryBudget budget, int batchSize,
            boolean trees) {
        this(label, window, input, budget, batchSize, trees, 1, null);
    }

    private WindowOperator(String label, PlanNode.Window window, Operator input, MemoryBudget budget,
            int batchSize, boolean trees, int threads, SpillDirectory spill) {
        super(label, window.schema());
        this.window = window;
        this.input = input;
        this.budget = budget;
        this.batchSize = batchSize;
        this.inputSchema = window.input().schema();
        this.trees = trees;
        this.threads = threads;
        this.spill = spill;
    }

    @Override
    Batch compute() {
        if (output == null) {
            output = computeAll();
        }
        while (output.isEmpty() && external != null) {
            computeSpilled();
        }
        return output.isEmpty() ? null : output.remove(0);
    }

    /** Returns the sort keys of the input: the partition, then the order. */
    private List<SortKey> keys() {
        List<SortKey> keys = new ArrayList<>();
        for (int c : window.partition()) {
            keys.add(SortKey.asc(c));
        }
        keys.addAll(window.order());
        return keys;
    }

    private List<Batch> computeAll() {
        List<SortKey> keys = keys();
        IntFunction<SortSink> sink = workers -> {
            SortSink s = new SortSink(label(), inputSchema, keys, budget, reserved, spill, context(), batchSize,
                    workers == 1 ? threads : 1);
            sinks.add(s);
            return s;
        };
        List<SortSink> read;
        if (input instanceof ParallelSegment segment) {
            read = segment.drain(sink);
        } else {
            SortSink s = sink.apply(1);
            for (Batch b = input.next(); b != null; b = input.next()) {
                s.accept(0, b);
            }
            read = List.of(s);
        }
        boolean spilled = false;
        for (SortSink s : read) {
            spilled |= s.spilled();
        }
        if (spilled) {
            List<ExternalSort.Run> runs = new ArrayList<>();
            for (SortSink s : read) {
                s.spillKept(threads);
                runs.addAll(s.runs());
            }
            // Rows come in batches whose peers and results take at most a quarter of the budget.
            long perRow = BYTES_PER_ROW + BYTES_PER_RESULT * window.calls().size() + BYTES_PER_NODE;
            int rows = (int) Math.max(1, Math.min(batchSize, budget.limit() / 4 / perRow));
            external = new ExternalSort(label(), inputSchema, keys, budget, spill, context(), rows);
            external.start(runs);
            return new ArrayList<>();
        }
        List<Batch> batches = SortSink.inOrder(read);
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }
        Batch all = Batches.concat(inputSchema, batches);
        batches.clear();
        return computeSorted(all.select(new RowOrder(all, keys).sort(this::checkCancelled, threads)), keys);
    }

    /**
     * Reads the sorted rows of the input that went to disk up to the end of
     * a partition and computes the partitions read; the rows of the
     * partition read last wait for the next call, unless the input ended.
     */
    private void computeSpilled() {
        List<SortKey> keys = keys();
        List<SortKey> partition = keys.subList(0, window.partition().size());
        while (true) {
            Batch b = external.next();
            if (b == null) {
                if (!pending.isEmpty()) {
                    computePending(new ArrayList<>(pending), keys);
                    pending.clear();
                }
                external.close();
                external = null;
                return;
            }
            // The first row of the last partition of the batch, 0 if the whole batch is of one partition.
            Vector[] of = RowOrder.columns(b, partition);
            int last = b.rowCount() - 1;
            int from = last;
            while (from > 0 && RowOrder.compare(partition, of, from - 1, of, last) == 0) {
                from--;
            }
            boolean continues = true;
            if (from == 0 && !pending.isEmpty()) {
                Batch p = pending.get(pending.size() - 1);
                continues = RowOrder.compare(partition, RowOrder.columns(p, partition), p.rowCount() - 1, of, 0)
                        == 0;
            }
            if (from == 0 && continues) {
                hold(b);
                continue;
            }
            List<Batch> done = new ArrayList<>(pending);
            if (from > 0) {
                done.add(b.select(range(0, from)));
            }
            long bytes = pendingBytes;
            pending.clear();
            pendingBytes = 0;
            hold(from == 0 ? b : b.select(range(from, b.rowCount())));
            computePending(done, keys);
            budget.release(bytes);
            reserved.addAndGet(-bytes);
            return;
        }
    }

    private static int[] range(int from, int to) {
        int[] rows = new int[to - from];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = from + i;
        }
        return rows;
    }

    /** Keeps rows of a partition not yet complete, reserved in the budget. */
    private void hold(Batch b) {
        long bytes = Batches.estimateBytes(b);
        if (!budget.tryReserve(bytes)) {
            throw overBudget(bytes);
        }
        reserved.addAndGet(bytes);
        pendingBytes += bytes;
        pending.add(b);
    }

    /** Computes whole partitions of sorted rows and gives back what they took in the budget. */
    private void computePending(List<Batch> done, List<SortKey> keys) {
        long before = reserved.get();
        try {
            output.addAll(computeSorted(Batches.concat(inputSchema, done), keys));
        } finally {
            long taken = reserved.get() - before;
            budget.release(taken);
            reserved.addAndGet(-taken);
        }
    }

    /** Computes the calls over rows sorted by the keys, whole partitions of them. */
    private List<Batch> computeSorted(Batch sorted, List<SortKey> keys) {
        rows = sorted;
        int n = rows.rowCount();
        reserve((BYTES_PER_ROW + BYTES_PER_RESULT * window.calls().size()) * n);
        peerStart = new int[n];
        peerEnd = new int[n];
        peerGroup = new int[n];
        groupRow = new int[n];
        List<Result> results = new ArrayList<>();
        for (WindowCall c : window.calls()) {
            results.add(result(c, n));
        }
        RowOrder partitions = new RowOrder(rows, keys.subList(0, window.partition().size()));
        RowOrder peers = new RowOrder(rows, window.order());
        Vector orderKey = window.order().size() == 1 ? rows.column(window.order().get(0).column()) : null;
        int[] starts = starts(partitions, n);
        int[] ranges = ranges(starts, n);
        Workers.run(ranges.length - 1, threads, t -> {
            Partition p = new Partition();
            for (int k = ranges[t]; k < ranges[t + 1]; k++) {
                p.compute(starts[k], starts[k + 1], peers, orderKey, results);
            }
        });
        List<Vector> columns = new ArrayList<>(rows.columns());
        for (Result r : results) {
            columns.add(r.build());
        }
        Batch out = new Batch(schema(), n, columns);
        rows = null;
        peerStart = null;
        peerEnd = null;
        peerGroup = null;
        groupRow = null;
        return new ArrayList<>(Batches.split(out, batchSize));
    }

    /**
     * Returns the first rows of the partitions of the sorted rows and, at
     * the end, their number; on several threads, each finds the first rows
     * within a range of the rows.
     */
    private int[] starts(RowOrder partitions, int n) {
        int chunks = Math.max(1, Math.min(threads, n / MIN_RANGE_ROWS));
        int[][] found = new int[chunks][];
        int[] counts = new int[chunks];
        Workers.run(chunks, threads, c -> {
            int from = (int) ((long) n * c / chunks);
            int to = (int) ((long) n * (c + 1) / chunks);
            int[] own = new int[16];
            int count = 0;
            for (int r = Math.max(1, from); r < to; r++) {
                if ((r & (CHECK_INTERVAL - 1)) == 0) {
                    checkCancelled();
                }
                if (partitions.compare(r - 1, r) != 0) {
                    if (count == own.length) {
                        own = Arrays.copyOf(own, 2 * count);
                    }
                    own[count++] = r;
                }
            }
            found[c] = own;
            counts[c] = count;
        });
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        int[] starts = new int[total + 2];
        int k = 1;
        for (int c = 0; c < chunks; c++) {
            System.arraycopy(found[c], 0, starts, k, counts[c]);
            k += counts[c];
        }
        starts[k] = n;
        return starts;
    }

    /**
     * Cuts the partitions into ranges of about equal numbers of rows, more
     * of them than threads, so that a fast thread takes more; returns the
     * first partition of each range and, at the end, the number of
     * partitions. A partition is never cut.
     */
    private int[] ranges(int[] starts, int n) {
        int partitions = starts.length - 1;
        if (threads == 1) {
            return new int[] {0, partitions};
        }
        long target = Math.max(MIN_RANGE_ROWS, n / (4L * threads));
        IntStream.Builder ranges = IntStream.builder();
        ranges.add(0);
        int from = 0;
        for (int k = 1; k < partitions; k++) {
            if (starts[k] - starts[from] >= target) {
                ranges.add(k);
                from = k;
            }
        }
        ranges.add(partitions);
        return ranges.build().toArray();
    }

    private void reserve(long bytes) {
        if (external == null) {
            budget.reserve(bytes, label());
        } else if (!budget.tryReserve(bytes)) {
            throw overBudget(bytes);
        }
        reserved.addAndGet(bytes);
    }

    private QueryException overBudget(long bytes) {
        return new QueryException(label(), "Memory budget of " + budget.limit() + " bytes exceeded: "
                + (budget.used() + bytes) + " bytes needed after spilling " + context().spill().bytesWritten()
                + " bytes");
    }

    /**
     * What one thread knows of the partition it computes: its rows, the
     * groups of peers, the frame of the current row, the frame of the row
     * before for an aggregate, and the memory it holds.
     */
    private final class Partition {
        private int start;
        private int end;
        /** The number of groups of peers of the partition. */
        private int groups;
        /** The rows of the partition whose order key is not {@code NULL}, for a range with an offset. */
        private int keyFrom;
        private int keyTo;
        /** The frame of a row, see {@link #frame}: the pieces {@code [pieceFrom[i], pieceTo[i])}. */
        private final int[] pieceFrom = new int[3];
        private final int[] pieceTo = new int[3];
        private int pieces;
        /** The frame of the row before: the pieces as from and to, and their number, -1 for none. */
        private final int[] lastPieces = new int[6];
        private int lastCount;
        private int steps;
        /** The bytes this thread holds in the budget. */
        private long held;

        /**
         * Finds the peers of the rows {@code [start, end)}, a partition, and
         * computes the calls over them.
         */
        void compute(int start, int end, RowOrder peers, Vector orderKey, List<Result> results) {
            this.start = start;
            this.end = end;
            int group = 0;
            for (int i = start; i < end; group++) {
                int j = i + 1;
                while (j < end && peers.compare(j - 1, j) == 0) {
                    j++;
                }
                for (int r = i; r < j; r++) {
                    peerStart[r] = i;
                    peerEnd[r] = j;
                    peerGroup[r] = group;
                }
                groupRow[start + group] = i;
                i = j;
            }
            groups = group;
            keyFrom = orderKey != null && orderKey.isNull(start) ? peerEnd[start] : start;
            keyTo = orderKey != null && orderKey.isNull(end - 1) ? peerStart[end - 1] : end;
            for (Result r : results) {
                r.partition(this);
            }
        }

        void reserve(long bytes) {
            WindowOperator.this.reserve(bytes);
            held += bytes;
        }

        void release(long bytes) {
            budget.release(bytes);
            reserved.addAndGet(-bytes);
            held -= bytes;
        }

        /** Tells whether the frame differs from the one before, and remembers it. */
        boolean changed() {
            boolean same = pieces == lastCount;
            for (int p = 0; same && p < pieces; p++) {
                same = pieceFrom[p] == lastPieces[2 * p] && pieceTo[p] == lastPieces[2 * p + 1];
            }
            if (!same) {
                lastCount = pieces;
                for (int p = 0; p < pieces; p++) {
                    lastPieces[2 * p] = pieceFrom[p];
                    lastPieces[2 * p + 1] = pieceTo[p];
                }
            }
            return !same;
        }

        /**
         * Sets the pieces of the frame of a row of the partition: the rows
         * from the start bound to the end bound, clamped to the partition,
         * without the excluded rows.
         */
        void frame(Frame f, RangeBounds ranges, int row) {
            long from = switch (f.start().kind()) {
                case UNBOUNDED_PRECEDING -> start;
                case CURRENT_ROW -> f.unit() == FrameUnit.ROWS ? row : peerStart[row];
                case PRECEDING, FOLLOWING -> bound(f.unit(), f.start(), true, ranges, row);
                case UNBOUNDED_FOLLOWING -> throw new IllegalStateException("A frame from " + f.start().kind());
            };
            long to = switch (f.end().kind()) {
                case UNBOUNDED_PRECEDING -> throw new IllegalStateException("A frame to " + f.end().kind());
                case CURRENT_ROW -> f.unit() == FrameUnit.ROWS ? row + 1 : peerEnd[row];
                case PRECEDING, FOLLOWING -> bound(f.unit(), f.end(), false, ranges, row);
                case UNBOUNDED_FOLLOWING -> end;
            };
            int a = Math.clamp(from, start, end);
            int b = Math.max(a, Math.clamp(to, start, end));
            pieces = 0;
            switch (f.exclusion()) {
                case NO_OTHERS -> piece(a, b);
                case CURRENT_ROW -> {
                    piece(a, Math.min(b, row));
                    piece(Math.max(a, row + 1), b);
                }
                case GROUP -> {
                    piece(a, Math.min(b, peerStart[row]));
                    piece(Math.max(a, peerEnd[row]), b);
                }
                case TIES -> {
                    piece(a, Math.min(b, peerStart[row]));
                    piece(Math.max(a, row), Math.min(b, row + 1));
                    piece(Math.max(a, peerEnd[row]), b);
                }
            }
        }

        private void piece(int from, int to) {
            if (from < to) {
                pieceFrom[pieces] = from;
                pieceTo[pieces++] = to;
            }
        }

        /**
         * Returns the row where a bound with an offset starts the frame of a
         * row of the partition, or the row after the end of the frame; it
         * may lie outside the partition.
         */
        private long bound(FrameUnit unit, FrameBound b, boolean starts, RangeBounds ranges, int row) {
            boolean preceding = b.kind() == BoundKind.PRECEDING;
            return switch (unit) {
                case ROWS -> {
                    long n = offset(b);
                    long r = preceding ? subtract(row, n) : add(row, n);
                    yield starts ? r : add(r, 1);
                }
                case GROUPS -> {
                    long n = offset(b);
                    if (n < 0) {
                        throw new ArithmeticException("Invalid GROUPS " + b.kind().keywords() + " value");
                    }
                    long g = preceding ? peerGroup[row] - n : add(peerGroup[row], n);
                    if (g < 0) {
                        yield start;
                    }
                    if (g >= groups) {
                        yield end;
                    }
                    yield starts ? groupRow[start + (int) g] : g + 1 < groups ? groupRow[start + (int) g + 1] : end;
                }
                case RANGE -> {
                    if (ranges.isNull(row)) {
                        yield starts ? peerStart[row] : peerEnd[row];
                    }
                    yield starts ? ranges.start(row, keyFrom, keyTo) : ranges.end(row, keyFrom, keyTo);
                }
            };
        }
    }

    /** Counts a step of a long loop of a partition and checks for a canceled query now and then. */
    private void step(Partition p) {
        if ((++p.steps & (CHECK_INTERVAL - 1)) == 0) {
            checkCancelled();
        }
    }

    private Result result(WindowCall c, int n) {
        return switch (c.function()) {
            case ROW_NUMBER, RANK, DENSE_RANK, NTILE -> new Ranks(c, n);
            case PERCENT_RANK, CUME_DIST -> new Shares(c, n);
            case LAG, LEAD, FIRST_VALUE, LAST_VALUE, NTH_VALUE -> new Picks(c, n);
            case AGGREGATE -> new Aggregates(c, n);
        };
    }

    /** The results of a call, in the places of the rows, partition by partition. */
    private abstract class Result {
        final WindowCall call;
        /** The bounds of a range with an offset, or {@code null}. */
        final RangeBounds ranges;

        Result(WindowCall call) {
            this.call = call;
            Frame f = call.frame();
            if (call.function().usesFrame() && f.unit() == FrameUnit.RANGE && f.hasOffset()) {
                reserve(BYTES_PER_KEY * rows.rowCount());
                SortKey key = window.order().get(0);
                this.ranges = new RangeBounds(rows.column(key.column()), key, f);
            } else {
                this.ranges = null;
            }
        }

        /** Sets the frame of a row of a partition. */
        void frame(Partition p, int row) {
            p.frame(call.frame(), ranges, row);
        }

        /**
         * Computes the results of the rows of a partition; the partitions
         * may come in any order, each on one thread.
         */
        abstract void partition(Partition p);

        /** Returns the results of all rows. */
        abstract Vector build();
    }

    /** {@code row_number}, {@code rank}, {@code dense_rank} and {@code ntile}. */
    private final class Ranks extends Result {
        private final long[] values;
        private final boolean[] nulls;

        Ranks(WindowCall call, int n) {
            super(call);
            this.values = new long[n];
            this.nulls = call.function() == WindowFunction.NTILE ? new boolean[n] : null;
        }

        @Override
        void partition(Partition p) {
            int start = p.start;
            int end = p.end;
            Vector buckets = call.function() == WindowFunction.NTILE ? rows.column(call.arguments().get(0)) : null;
            for (int i = start; i < end; i++) {
                step(p);
                values[i] = switch (call.function()) {
                    case ROW_NUMBER -> i - start + 1;
                    case RANK -> peerStart[i] - start + 1;
                    case DENSE_RANK -> peerGroup[i] + 1L;
                    default -> {
                        if (buckets.isNull(i)) {
                            nulls[i] = true;
                            yield 0;
                        }
                        yield ntile(((Number) buckets.get(i)).longValue(), end - start, i - start);
                    }
                };
            }
        }

        @Override
        Vector build() {
            return BigIntVector.wrap(DataType.BIGINT, values, nulls);
        }
    }

    /**
     * The bucket of a row among {@code buckets} as DuckDB numbers them: as
     * many rows each as they divide evenly, the first buckets one more.
     */
    static long ntile(long buckets, long rows, long row) {
        if (buckets < 1) {
            throw new IllegalArgumentException("Argument for ntile must be greater than zero");
        }
        long n = Math.min(buckets, rows);
        long size = rows / n;
        long large = rows - n * size;
        long small = large * (size + 1);
        return row < small ? 1 + row / (size + 1) : 1 + large + (row - small) / size;
    }

    /** {@code percent_rank} and {@code cume_dist}. */
    private final class Shares extends Result {
        private final double[] values;

        Shares(WindowCall call, int n) {
            super(call);
            this.values = new double[n];
        }

        @Override
        void partition(Partition p) {
            int start = p.start;
            int end = p.end;
            double rowCount = end - start;
            for (int i = start; i < end; i++) {
                step(p);
                values[i] = call.function() == WindowFunction.PERCENT_RANK
                        ? rowCount > 1 ? (peerStart[i] - start) / (rowCount - 1) : 0
                        : (peerEnd[i] - start) / rowCount;
            }
        }

        @Override
        Vector build() {
            return DoubleVector.wrap(values, null);
        }
    }

    /**
     * The functions that take the value of one row: {@code lag},
     * {@code lead}, {@code first_value}, {@code last_value} and
     * {@code nth_value}. A row whose offset or position is {@code NULL}
     * takes {@code NULL}; a row before or after the partition takes the
     * default of {@code lag} and {@code lead}. With {@code IGNORE NULLS},
     * the offsets and positions count only the rows whose value is not
     * {@code NULL}, but an offset of 0 is the current row.
     */
    private final class Picks extends Result {
        /** The row whose value a row takes, or {@link #NONE} or {@link #DEFAULT}. */
        private final int[] sources;
        /** With {@code IGNORE NULLS}, the number of values before each row that are not {@code NULL}. */
        private final int[] counts;
        /** With {@code IGNORE NULLS}, the rows whose value is not {@code NULL}, in their order. */
        private final int[] present;
        /** Whether a row takes the default; set by the threads of the partitions, read after them. */
        private volatile boolean defaults;

        Picks(WindowCall call, int n) {
            super(call);
            this.sources = new int[n];
            if (call.ignoreNulls()) {
                reserve(BYTES_PER_INDEX * n);
                Vector v = rows.column(call.arguments().get(0));
                counts = new int[n + 1];
                present = new int[n];
                int k = 0;
                for (int r = 0; r < n; r++) {
                    counts[r] = k;
                    if (!v.isNull(r)) {
                        present[k++] = r;
                    }
                }
                counts[n] = k;
            } else {
                counts = null;
                present = null;
            }
        }

        @Override
        void partition(Partition p) {
            Vector second = call.arguments().size() > 1 ? rows.column(call.arguments().get(1)) : null;
            for (int i = p.start; i < p.end; i++) {
                step(p);
                sources[i] = switch (call.function()) {
                    case LAG, LEAD -> shifted(i, p.start, p.end, second);
                    case FIRST_VALUE -> {
                        frame(p, i);
                        yield nth(p, 1);
                    }
                    case LAST_VALUE -> {
                        frame(p, i);
                        yield last(p);
                    }
                    default -> {
                        long position = second.isNull(i) ? 0 : ((Number) second.get(i)).longValue();
                        if (position < 1) {
                            yield NONE;
                        }
                        frame(p, i);
                        yield nth(p, position);
                    }
                };
            }
        }

        /** Returns the source of {@code lag} or {@code lead} for a row of the partition {@code [start, end)}. */
        private int shifted(int row, int start, int end, Vector second) {
            if (second != null && second.isNull(row)) {
                return NONE;
            }
            long offset = second == null ? 1 : ((Number) second.get(row)).longValue();
            boolean lag = call.function() == WindowFunction.LAG;
            if (offset == 0) {
                return row;
            }
            if (!call.ignoreNulls()) {
                long r = lag ? subtract(row, offset) : add(row, offset);
                if (r >= start && r < end) {
                    return (int) r;
                }
            } else {
                long distance = offset == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(offset);
                long index = lag == offset > 0 ? counts[row] - distance : add(counts[row + 1] - 1, distance);
                if (index >= counts[start] && index < counts[end]) {
                    return present[(int) index];
                }
            }
            boolean fallback = call.arguments().size() > 2;
            if (fallback) {
                defaults = true;
            }
            return fallback ? DEFAULT : NONE;
        }

        /** Returns the row of the n-th row of the frame, from 1, or {@link #NONE}. */
        private int nth(Partition f, long n) {
            for (int p = 0; p < f.pieces; p++) {
                int from = f.pieceFrom[p];
                int to = f.pieceTo[p];
                long count = counts == null ? to - from : counts[to] - counts[from];
                if (n <= count) {
                    return counts == null ? from + (int) n - 1 : present[counts[from] + (int) n - 1];
                }
                n -= count;
            }
            return NONE;
        }

        /** Returns the last row of the frame, or {@link #NONE}. */
        private int last(Partition f) {
            for (int p = f.pieces - 1; p >= 0; p--) {
                int from = f.pieceFrom[p];
                int to = f.pieceTo[p];
                if (counts == null ? from < to : counts[from] < counts[to]) {
                    return counts == null ? to - 1 : present[counts[to] - 1];
                }
            }
            return NONE;
        }

        @Override
        Vector build() {
            Vector values = rows.column(call.arguments().get(0));
            if (!defaults) {
                return Vectors.gather(values, sources);
            }
            Vector fallback = rows.column(call.arguments().get(2));
            Vector.Builder out = Vectors.builder(values.type(), sources.length);
            for (int i = 0; i < sources.length; i++) {
                int s = sources[i];
                out.append(s >= 0 ? values.get(s) : s == DEFAULT ? fallback.get(i) : null);
            }
            return out.build();
        }
    }

    /** An aggregate over the frame of each row. */
    private final class Aggregates extends Result {
        private final Object[] values;
        private final boolean mergeable;
        private final Vector filter;

        Aggregates(WindowCall call, int n) {
            super(call);
            this.values = new Object[n];
            this.mergeable = trees && ValueAccumulator.mergeable(call.aggregate());
            this.filter = call.filter() >= 0 ? rows.column(call.filter()) : null;
        }

        @Override
        void partition(Partition f) {
            int start = f.start;
            int end = f.end;
            SegmentTree tree = null;
            // One accumulator for the frames of the partition, emptied for each.
            ValueAccumulator frame = null;
            long treeBytes = 0;
            f.lastCount = -1;
            Object last = null;
            for (int i = start; i < end; i++) {
                step(f);
                frame(f, i);
                if (f.changed()) {
                    if (mergeable) {
                        if (tree == null) {
                            tree = new SegmentTree(rows, start, end, filter, () -> empty(f));
                            treeBytes = BYTES_PER_NODE * tree.nodes();
                            f.reserve(treeBytes);
                            frame = empty(f);
                        } else {
                            frame.reset();
                        }
                        for (int p = 0; p < f.pieces; p++) {
                            tree.aggregate(frame, f.pieceFrom[p], f.pieceTo[p]);
                        }
                        last = frame.result();
                    } else {
                        ValueAccumulator acc = empty(f);
                        // The values the accumulator keeps are gone with it.
                        long held = f.held;
                        for (int p = 0; p < f.pieces; p++) {
                            for (int r = f.pieceFrom[p]; r < f.pieceTo[p]; r++) {
                                step(f);
                                if (SegmentTree.takes(filter, r)) {
                                    acc.add(rows, r);
                                }
                            }
                        }
                        last = acc.result();
                        f.release(f.held - held);
                    }
                }
                values[i] = last;
            }
            f.release(treeBytes);
        }

        private ValueAccumulator empty(Partition f) {
            return new ValueAccumulator(call.aggregate(), inputSchema, f::reserve);
        }

        @Override
        Vector build() {
            Vector.Builder out = Vectors.builder(call.type(inputSchema), values.length);
            for (Object v : values) {
                out.append(v);
            }
            return out.build();
        }
    }

    /** Returns the offset of a bound of {@code ROWS} or {@code GROUPS}, a number of rows or groups. */
    private static long offset(FrameBound b) {
        return ((Number) b.offset().value()).longValue();
    }

    /** Adds without overflow, stopping at the bounds of a long. */
    static long add(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? b < 0 ? Long.MIN_VALUE : Long.MAX_VALUE : r;
    }

    /** Subtracts without overflow, stopping at the bounds of a long. */
    static long subtract(long a, long b) {
        long r = a - b;
        return ((a ^ b) & (a ^ r)) < 0 ? b < 0 ? Long.MAX_VALUE : Long.MIN_VALUE : r;
    }

    @Override
    public void close() {
        try {
            input.close();
        } finally {
            try {
                if (external != null) {
                    external.close();
                }
                for (SortSink s : sinks) {
                    s.close();
                }
                sinks.clear();
                pending.clear();
            } finally {
                budget.release(reserved.getAndSet(0));
            }
        }
    }
}
