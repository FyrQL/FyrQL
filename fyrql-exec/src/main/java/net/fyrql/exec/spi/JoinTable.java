/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.exec.spi;

import net.fyrql.connector.Batches;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The build side of a hash join: the rows of the right input in one batch,
 * chained by the hash of their keys. A row with a {@code NULL} key is in no
 * chain, since it matches nothing. Without keys there are no chains, and
 * every row is a candidate. The table records which rows matched, for the
 * joins that emit the unmatched ones at the end.
 * <p>
 * A chain runs from {@link #first} through {@link #next} to -1, from the
 * last row of the build side to the first. It holds the rows whose hash
 * falls into one slot; {@link #hash} tells the hash of a row, so that a
 * probe compares the keys only of the rows with its own hash.
 */
public final class JoinTable {

    /** The error of a {@code SINGLE} join whose left row matches more than one right row. */
    public static final String MORE_THAN_ONE_ROW = "More than one row returned by a subquery used as an expression";

    /**
     * The most pairs of probe and build rows that a join without keys forms
     * at once: its probe takes a batch in steps of {@link #stepRows} rows,
     * so that a cross product of large inputs does not become one batch with
     * all its pairs.
     */
    public static final int MAX_PAIRS = 1 << 16;

    /**
     * Returns the probe rows of one step when each of them pairs with up to
     * {@code pairsPerRow} build rows: as many as keep the pairs within
     * {@link #MAX_PAIRS}, and at least one.
     */
    public static int stepRows(long pairsPerRow) {
        return (int) Math.max(1, MAX_PAIRS / Math.max(1, pairsPerRow));
    }

    private final Batch batch;
    private final int[] heads;
    private final int[] chain;
    private final int[] hashes;
    private final boolean hasNullKey;
    private final boolean[] matched;

    /**
     * Builds the table over batches of the build side, concatenated without
     * boxing, hashed on the key columns, if any, by the key functions.
     */
    public JoinTable(Schema schema, List<Batch> batches, int[] keys, KeyFunctions functions) {
        this(concat(schema, batches), keys, functions);
    }

    /**
     * Builds the table over batches of the build side whose keys are hashed
     * already, as the key functions hash them, e.g. by the threads that read
     * the batches.
     *
     * @param hashes the hashes of the keys of each batch; ignored without keys
     */
    public JoinTable(Schema schema, List<Batch> batches, List<int[]> hashes, int[] keys) {
        this(concat(schema, batches), keys, keys.length == 0 ? null : concat(hashes));
    }

    private JoinTable(Batch batch, int[] keys, KeyFunctions functions) {
        this(batch, keys, keys.length == 0 ? null : hashes(batch, keys, functions));
    }

    private JoinTable(Batch batch, int[] keys, int[] hashes) {
        this.batch = batch;
        this.hashes = hashes;
        int n = batch.rowCount();
        matched = new boolean[n];
        if (keys.length == 0) {
            heads = null;
            chain = null;
            hasNullKey = false;
            return;
        }
        if (hashes.length != n) {
            throw new IllegalArgumentException(hashes.length + " hashes for " + n + " rows");
        }
        int capacity = Integer.highestOneBit(Math.max(16, n * 2 - 1)) << 1;
        heads = new int[capacity];
        Arrays.fill(heads, -1);
        chain = new int[n];
        boolean nullKey = false;
        for (int i = 0; i < n; i++) {
            if (hasNullKey(batch, keys, i)) {
                chain[i] = -1;
                nullKey = true;
                continue;
            }
            int slot = hashes[i] & (capacity - 1);
            chain[i] = heads[slot];
            heads[slot] = i;
        }
        hasNullKey = nullKey;
    }

    private static Batch concat(Schema schema, List<Batch> batches) {
        return batches.isEmpty() ? Batch.empty(schema) : Batches.concat(schema, batches);
    }

    private static int[] hashes(Batch batch, int[] keys, KeyFunctions functions) {
        int[] hashes = new int[batch.rowCount()];
        functions.hash(batch, keys, hashes);
        return hashes;
    }

    private static int[] concat(List<int[]> parts) {
        int n = 0;
        for (int[] part : parts) {
            n += part.length;
        }
        int[] all = new int[n];
        int at = 0;
        for (int[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }

    /** Returns the rows of the build side. */
    public Batch batch() {
        return batch;
    }

    /** Returns the number of rows of the build side. */
    public int rowCount() {
        return batch.rowCount();
    }

    /** Tells whether a row of the build side has a {@code NULL} key; never for a table without keys. */
    public boolean hasNullKey() {
        return hasNullKey;
    }

    /** Returns the last row whose key has a hash in the slot of the given hash, or -1; only with keys. */
    public int first(int hash) {
        return heads[hash & (heads.length - 1)];
    }

    /** Returns the hash of the key of a row; only with keys. */
    public int hash(int row) {
        return hashes[row];
    }

    /** Returns the row before a row in its chain, or -1. */
    public int next(int row) {
        return chain[row];
    }

    /**
     * Records that a row of the build side matched. Several threads that
     * probe the table may mark rows at once: the mark of each row is a
     * variable of its own, which they only set, and the marks are read once
     * the threads have finished.
     */
    public void markMatched(int row) {
        matched[row] = true;
    }

    /** Returns the rows of the build side that never matched, in build order. */
    public int[] unmatched() {
        int[] rows = new int[matched.length];
        int m = 0;
        for (int j = 0; j < matched.length; j++) {
            if (!matched[j]) {
                rows[m++] = j;
            }
        }
        return Arrays.copyOf(rows, m);
    }

    /**
     * Returns the rows of the build side that never matched, in build
     * order, after {@code NULL} for the columns of the probe side, or
     * {@code null} if there are none: the end of a {@code RIGHT} or
     * {@code FULL} join.
     *
     * @param probe  the schema of the probe side
     * @param joined the schema of the output of the join
     */
    public Batch leftovers(Schema probe, Schema joined) {
        int[] rows = unmatched();
        int m = rows.length;
        if (m == 0) {
            return null;
        }
        int[] none = new int[m];
        Arrays.fill(none, -1);
        List<Vector> columns = new ArrayList<>(Batches.selectOrNull(Batch.empty(probe), none).columns());
        columns.addAll(batch.select(rows).columns());
        return new Batch(joined, m, columns);
    }

    /** Tells whether any of the given columns is {@code NULL} at a row. */
    public static boolean hasNullKey(Batch b, int[] columns, int row) {
        for (int k : columns) {
            if (b.column(k).isNull(row)) {
                return true;
            }
        }
        return false;
    }
}
