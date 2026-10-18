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

package net.fyrql.exec;

import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.spi.KeyFunctions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The groups of an aggregation, numbered in the order of their first row:
 * a hash table with open addressing over group numbers, and the keys of the
 * groups in small batches, one per input batch that brought new groups,
 * which hold only the key columns of the new rows. Nothing is boxed.
 * <p>
 * The key functions may change between batches, since all of them hash
 * alike.
 */
final class GroupTable {

    private final int[] keyColumns;
    private final List<Integer> keyColumnList;
    private final int[] storedColumns;
    private final List<Batch> keyBatches = new ArrayList<>();

    /** The group number plus one per slot, 0 for an empty slot. */
    private int[] slots = new int[64];
    private int[] groupHash = new int[16];
    /** The key batch of a group, or -1 while its row is still in the current input batch. */
    private int[] groupBatch = new int[16];
    private int[] groupRow = new int[16];
    private int size;

    GroupTable(List<Integer> keyColumns) {
        this.keyColumnList = List.copyOf(keyColumns);
        this.keyColumns = keyColumns.stream().mapToInt(Integer::intValue).toArray();
        this.storedColumns = new int[this.keyColumns.length];
        for (int i = 0; i < storedColumns.length; i++) {
            storedColumns[i] = i;
        }
    }

    /** Returns the number of groups. */
    int size() {
        return size;
    }

    /**
     * Finds the group of every row of a batch, adding new groups, and
     * returns the group numbers by row. A batch with the keys of the new
     * groups is handed to {@code newKeys} before it is kept, so that the
     * caller can account for its memory.
     */
    int[] groups(Batch b, KeyFunctions keys, Consumer<Batch> newKeys) {
        int[] hashes = new int[b.rowCount()];
        keys.hash(b, keyColumns, hashes);
        return groups(b, hashes, keys, newKeys);
    }

    /**
     * Finds the groups of the rows of a batch as {@link #groups(Batch, KeyFunctions, Consumer)}
     * does, with the hashes of their keys given, which {@code keys} would compute.
     */
    int[] groups(Batch b, int[] hashes, KeyFunctions keys, Consumer<Batch> newKeys) {
        int n = b.rowCount();
        int[] result = new int[n];
        int[] newRows = new int[n];
        int added = 0;
        int first = size;
        for (int row = 0; row < n; row++) {
            int h = hashes[row];
            int mask = slots.length - 1;
            int slot = h & mask;
            int group = -1;
            while (slots[slot] != 0) {
                int g = slots[slot] - 1;
                if (groupHash[g] == h && (groupBatch[g] < 0
                        ? keys.equal(b, row, keyColumns, b, groupRow[g], keyColumns)
                        : keys.equal(b, row, keyColumns, keyBatches.get(groupBatch[g]), groupRow[g], storedColumns))) {
                    group = g;
                    break;
                }
                slot = (slot + 1) & mask;
            }
            if (group < 0) {
                group = add(h, row);
                newRows[added++] = row;
                if (size * 2 > slots.length) {
                    grow();
                }
            }
            result[row] = group;
        }
        if (added > 0) {
            Batch stored = b.project(keyColumnList).select(Arrays.copyOf(newRows, added));
            newKeys.accept(stored);
            keyBatches.add(stored);
            for (int i = 0; i < added; i++) {
                groupBatch[first + i] = keyBatches.size() - 1;
                groupRow[first + i] = i;
            }
        }
        return result;
    }

    /**
     * Returns the values of a key column of all groups, in the order of the
     * groups; there must be at least one group.
     */
    Vector keys(int column) {
        List<Vector> parts = new ArrayList<>(keyBatches.size());
        for (Batch b : keyBatches) {
            parts.add(b.column(column));
        }
        return Vectors.concat(keyBatches.get(0).schema().type(column), parts);
    }

    /** Returns the keys of all groups in their order, the key columns only; there must be at least one group. */
    Batch keys() {
        List<Vector> columns = new ArrayList<>(keyColumns.length);
        for (int k = 0; k < keyColumns.length; k++) {
            columns.add(keys(k));
        }
        return new Batch(keyBatches.get(0).schema(), size, columns);
    }

    /** Returns the hash of the key of a group. */
    int hash(int group) {
        return groupHash[group];
    }

    private int add(int hash, int row) {
        if (size == groupHash.length) {
            groupHash = Arrays.copyOf(groupHash, size * 2);
            groupBatch = Arrays.copyOf(groupBatch, size * 2);
            groupRow = Arrays.copyOf(groupRow, size * 2);
        }
        int g = size++;
        groupHash[g] = hash;
        groupBatch[g] = -1;
        groupRow[g] = row;
        int mask = slots.length - 1;
        int slot = hash & mask;
        while (slots[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        slots[slot] = g + 1;
        return g;
    }

    /** Doubles the slots and places the groups again by their hashes. */
    private void grow() {
        slots = new int[slots.length * 2];
        int mask = slots.length - 1;
        for (int g = 0; g < size; g++) {
            int slot = groupHash[g] & mask;
            while (slots[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            slots[slot] = g + 1;
        }
    }
}
