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
import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.compiled.InterpretedKeys;
import net.fyrql.exec.compiled.JoinTable;
import net.fyrql.exec.compiled.KeyFunctions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads the build side of a join into a {@link JoinTable}, each batch
 * hashed where it is read: by the workers of a segment, which collects the
 * batches in the order of its morsels, see {@link ParallelSegment#collect},
 * or else on the thread of the query. The table is the one that hashing
 * the concatenated batches would give, since every key function hashes
 * alike; each reader switches to compiled key functions once
 * {@link Codegen#due} says so for the rows it has hashed. The memory of
 * each batch, and then of the table, is reserved under the label of the
 * join.
 * <p>
 * A join that spills, see {@link #spills}, reads its build side with
 * {@link #side}: once it does not fit, its rows go to disk, see
 * {@link JoinSide.Spilled}.
 */
final class JoinBuild {

    /** A batch of the build side and the hashes of its keys, {@code null} without keys. */
    private record Part(Batch batch, int[] hashes) {
    }

    private JoinBuild() {
    }

    /**
     * Reads the right input of a join into its table.
     *
     * @param right    the operator of the right input
     * @param label    the label of the join
     * @param reserved counts the bytes reserved in the budget, which the caller releases
     * @param context  the context of the query, which counts the compiled key functions
     */
    static JoinTable read(Operator right, PlanNode.Join join, String label, MemoryBudget budget, Codegen codegen,
            AtomicLong reserved, QueryContext context) {
        int[] keys = keys(join);
        List<DataType> types = types(join);
        List<Part> parts;
        if (right instanceof ParallelSegment segment) {
            parts = segment.collect(() -> new Hasher(keys, types, label, budget, codegen, reserved, context));
        } else {
            Hasher hasher = new Hasher(keys, types, label, budget, codegen, reserved, context);
            parts = new ArrayList<>();
            for (Batch b = right.next(); b != null; b = right.next()) {
                parts.add(hasher.apply(b));
            }
        }
        List<Batch> batches = new ArrayList<>(parts.size());
        List<int[]> hashes = new ArrayList<>(parts.size());
        for (Part p : parts) {
            batches.add(p.batch());
            hashes.add(p.hashes());
        }
        return table(batches, keys.length > 0, label, budget, reserved,
                () -> new JoinTable(join.right().schema(), batches, hashes, keys));
    }

    private static int[] keys(PlanNode.Join join) {
        return join.rightKeys().stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns the types of the keys, which the key functions are made for. */
    private static List<DataType> types(PlanNode.Join join) {
        List<DataType> types = new ArrayList<>();
        for (int k : join.leftKeys()) {
            types.add(join.left().schema().type(k));
        }
        return types;
    }

    /**
     * Builds a table over batches whose memory is reserved already. Before,
     * it reserves what the table holds besides them, and their copy into one
     * batch if there are several; after, it gives back the batches that the
     * copy replaces.
     *
     * @param keyed tells whether the join has keys
     */
    static JoinTable table(List<Batch> batches, boolean keyed, String label, MemoryBudget budget,
            AtomicLong reserved, Supplier<JoinTable> build) {
        int rows = 0;
        long copy = 0;
        for (Batch b : batches) {
            rows += b.rowCount();
            copy += batches.size() > 1 ? Batches.estimateBytes(b) : 0;
        }
        long bytes = copy + JoinTable.indexBytes(rows, keyed);
        budget.reserve(bytes, label);
        reserved.addAndGet(bytes);
        JoinTable table = build.get();
        budget.release(copy);
        reserved.addAndGet(-copy);
        return table;
    }

    /**
     * Tells whether a join spills its build side when it does not fit: one
     * with keys, a directory to spill to and a limited budget.
     */
    static boolean spills(PlanNode.Join join, MemoryBudget budget, SpillDirectory spill) {
        return spill != null && budget.limit() < Long.MAX_VALUE && !join.leftKeys().isEmpty();
    }

    /**
     * Reads the right input of a join that spills, see {@link #spills}. The
     * batches are kept while the join then holds at most half of the budget,
     * so that probing has room, and the table is built if it fits too;
     * otherwise every row goes to disk, cut into partitions by the hash of
     * its keys, with its place among the rows of the build side, see
     * {@link JoinPartitions}. The workers of a segment each keep and hash the
     * batches they read and, once one of them finds the budget exhausted, all
     * write their rows to files of their own. A row with a {@code NULL} key
     * matches nothing: it is put aside for the joins that emit the unmatched
     * build rows, and dropped for the others.
     *
     * @param reserved counts the bytes reserved in the budget for a table in memory, which the caller releases
     */
    static JoinSide side(Operator right, PlanNode.Join join, String label, MemoryBudget budget,
            SpillDirectory spill, Codegen codegen, AtomicLong reserved, QueryContext context) {
        Spilling shared = new Spilling(join, label, budget, spill, codegen, context);
        try {
            List<Keeper> keepers;
            if (right instanceof ParallelSegment segment) {
                keepers = segment.drain(n -> new Keeper(shared));
            } else {
                Keeper keeper = new Keeper(shared);
                for (Batch b = right.next(); b != null; b = right.next()) {
                    keeper.accept(0, b);
                }
                keepers = List.of(keeper);
            }
            if (!shared.spilling) {
                // The batches of all keepers in the order of their morsels, as one thread would have read them.
                List<Kept> all = new ArrayList<>();
                for (Keeper k : keepers) {
                    all.addAll(k.kept);
                }
                all.sort(Comparator.comparingLong(Kept::first));
                List<Batch> batches = new ArrayList<>(all.size());
                List<int[]> hashes = new ArrayList<>(all.size());
                int rows = 0;
                long copy = 0;
                for (Kept k : all) {
                    batches.add(k.batch());
                    hashes.add(k.hashes());
                    rows += k.batch().rowCount();
                    copy += all.size() > 1 ? Batches.estimateBytes(k.batch()) : 0;
                }
                if (shared.take(copy + JoinTable.indexBytes(rows, true))) {
                    JoinTable table = new JoinTable(join.right().schema(), batches, hashes, shared.keys);
                    budget.release(copy);
                    reserved.addAndGet(shared.held.get() - copy);
                    shared.held.set(0);
                    return new JoinSide.Table(table);
                }
                shared.spilling = true;
            }
            long rows = 0;
            boolean nullKey = false;
            for (Keeper k : keepers) {
                k.spillKept();
                rows += k.rows;
                nullKey |= k.nullKey;
            }
            budget.release(shared.held.getAndSet(0));
            shared.parts.partitions();
            return new JoinSide.Spilled(shared.parts, rows, nullKey);
        } catch (RuntimeException e) {
            budget.release(shared.held.getAndSet(0));
            shared.parts.close();
            throw e;
        }
    }

    /**
     * Writes rows of a build side to the partitions of a writer, those with
     * a {@code NULL} key aside or nowhere.
     *
     * @param keepsNullKeys whether the rows with a {@code NULL} key are put aside rather than dropped
     * @return whether a row has a {@code NULL} key
     */
    static boolean scatter(JoinPartitions.Writer writer, Batch b, int[] hashes, long[] places, int[] keys,
            boolean keepsNullKeys) {
        int n = b.rowCount();
        int[] nulls = new int[n];
        int m = 0;
        for (int r = 0; r < n; r++) {
            if (JoinTable.hasNullKey(b, keys, r)) {
                nulls[m++] = r;
            }
        }
        if (m == 0) {
            writer.scatter(b, hashes, places);
            return false;
        }
        int[] keyed = new int[n - m];
        long[] keyedPlaces = new long[n - m];
        int[] keyedHashes = new int[n - m];
        long[] nullPlaces = new long[m];
        for (int r = 0, i = 0, k = 0; r < n; r++) {
            if (i < m && nulls[i] == r) {
                nullPlaces[i++] = places[r];
            } else {
                keyed[k] = r;
                keyedHashes[k] = hashes[r];
                keyedPlaces[k++] = places[r];
            }
        }
        if (keyed.length > 0) {
            writer.scatter(b.select(keyed), keyedHashes, keyedPlaces);
        }
        if (keepsNullKeys) {
            writer.aside(b.select(Arrays.copyOf(nulls, m)), nullPlaces);
        }
        return true;
    }

    /**
     * What the readers of a build side that spills share: the bytes they
     * keep, at most half of the budget, whether they spill, and the
     * partitions on disk.
     */
    private static final class Spilling {
        final int[] keys;
        final List<DataType> types;
        final MemoryBudget budget;
        final Codegen codegen;
        final QueryContext context;
        final boolean keepsNullKeys;
        final JoinPartitions parts;
        final AtomicLong held = new AtomicLong();
        volatile boolean spilling;

        Spilling(PlanNode.Join join, String label, MemoryBudget budget, SpillDirectory spill, Codegen codegen,
                QueryContext context) {
            this.keys = keys(join);
            this.types = types(join);
            this.budget = budget;
            this.codegen = codegen;
            this.context = context;
            this.keepsNullKeys = SegmentPlanner.keepsUnmatchedRight(join);
            this.parts = new JoinPartitions(label, "build", join.right().schema(), spill, context);
        }

        /** Takes bytes from the budget if they are left and the join then holds at most half of it. */
        synchronized boolean take(long bytes) {
            if (held.get() + bytes > budget.limit() / 2 || !budget.tryReserve(bytes)) {
                return false;
            }
            held.addAndGet(bytes);
            return true;
        }

        synchronized void giveBack(long bytes) {
            budget.release(bytes);
            held.addAndGet(-bytes);
        }
    }

    /** A batch a reader keeps in memory, with the hashes and places of its rows. */
    private record Kept(Batch batch, int[] hashes, long[] places, long bytes) {
        long first() {
            return places.length == 0 ? Long.MIN_VALUE : places[0];
        }
    }

    /**
     * Keeps the batches of one reader of a build side that spills and hashes
     * their keys; once the readers spill, writes them, and those it reads
     * after, to files of its own, each row with its place by its morsel.
     */
    private static final class Keeper implements ParallelSegment.Sink {
        private final Spilling shared;
        private final List<Kept> kept = new ArrayList<>();
        private JoinPartitions.Writer writer;
        private final KeyHasher hasher;
        private int morsel = -1;
        private long row;
        long rows;
        boolean nullKey;

        Keeper(Spilling shared) {
            this.shared = shared;
            this.hasher = new KeyHasher(shared.keys, shared.types, shared.codegen, shared.context);
        }

        @Override
        public void accept(int morsel, Batch b) {
            int n = b.rowCount();
            if (morsel != this.morsel) {
                this.morsel = morsel;
                row = 0;
            }
            long[] places = new long[n];
            for (int r = 0; r < n; r++) {
                places[r] = Partitions.place(morsel, row + r);
            }
            row += n;
            rows += n;
            int[] hashes = hasher.hash(b);
            if (!shared.spilling) {
                long bytes = Batches.estimateBytes(b);
                if (shared.take(bytes)) {
                    kept.add(new Kept(b, hashes, places, bytes));
                    return;
                }
                shared.spilling = true;
            }
            spillKept();
            nullKey |= scatter(writer(), b, hashes, places, shared.keys, shared.keepsNullKeys);
        }

        private JoinPartitions.Writer writer() {
            if (writer == null) {
                writer = shared.parts.writer();
            }
            return writer;
        }

        /** Writes the batches kept, in their order, and gives back what they took. */
        void spillKept() {
            for (Kept k : kept) {
                nullKey |= scatter(writer(), k.batch(), k.hashes(), k.places(), shared.keys, shared.keepsNullKeys);
                shared.giveBack(k.bytes());
            }
            kept.clear();
        }
    }

    /**
     * Hashes the keys of the batches of one reader, with compiled key
     * functions once {@link Codegen#due} says so for the rows it hashed;
     * every key function hashes alike.
     */
    private static final class KeyHasher {
        private final int[] keys;
        private final List<DataType> types;
        private final Codegen codegen;
        private final QueryContext context;
        private KeyFunctions functions = InterpretedKeys.INSTANCE;
        private boolean asked;
        private long rows;

        /**
         * @param keys  the key columns
         * @param types the types of the keys
         */
        KeyHasher(int[] keys, List<DataType> types, Codegen codegen, QueryContext context) {
            this.keys = keys;
            this.types = types;
            this.codegen = codegen;
            this.context = context;
        }

        /** Returns the hashes of the keys of the rows of a batch. */
        int[] hash(Batch b) {
            if (!asked && codegen.due(rows)) {
                asked = true;
                KeyFunctions compiled = codegen.keys(types, context);
                if (compiled != null) {
                    functions = compiled;
                }
            }
            rows += b.rowCount();
            int[] hashes = new int[b.rowCount()];
            functions.hash(b, keys, hashes);
            return hashes;
        }
    }

    /** Reserves the memory of the batches of one reader and hashes their keys. */
    private static final class Hasher implements Function<Batch, Part> {
        private final int[] keys;
        private final List<DataType> types;
        private final String label;
        private final MemoryBudget budget;
        private final Codegen codegen;
        private final AtomicLong reserved;
        private final QueryContext context;
        private KeyFunctions functions = InterpretedKeys.INSTANCE;
        private boolean asked;
        private long rows;

        Hasher(int[] keys, List<DataType> types, String label, MemoryBudget budget, Codegen codegen,
                AtomicLong reserved, QueryContext context) {
            this.keys = keys;
            this.types = types;
            this.label = label;
            this.budget = budget;
            this.codegen = codegen;
            this.reserved = reserved;
            this.context = context;
        }

        @Override
        public Part apply(Batch b) {
            long bytes = Batches.estimateBytes(b);
            budget.reserve(bytes, label);
            reserved.addAndGet(bytes);
            if (keys.length == 0) {
                return new Part(b, null);
            }
            if (!asked && codegen.due(rows)) {
                asked = true;
                KeyFunctions compiled = codegen.keys(types, context);
                if (compiled != null) {
                    functions = compiled;
                }
            }
            rows += b.rowCount();
            int[] hashes = new int[b.rowCount()];
            functions.hash(b, keys, hashes);
            return new Part(b, hashes);
        }
    }
}
