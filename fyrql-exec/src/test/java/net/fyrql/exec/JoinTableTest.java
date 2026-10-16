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

import net.fyrql.connector.Batches;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.InterpretedKeys;
import net.fyrql.exec.spi.JoinTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The join table keeps the build rows in order across batches, chains every
 * row with a key without {@code NULL} so that the rows of one key come from
 * the last to the first, and reports the rows that never matched.
 */
class JoinTableTest {

    private static final Schema SCHEMA = Schema.of(DataType.INTEGER, DataType.VARCHAR, DataType.decimal(15, 2));

    @Test
    void chainsHoldEveryRowOfAKeyFromTheLastToTheFirst() {
        SplittableRandom random = new SplittableRandom(20261016);
        for (int round = 0; round < 50; round++) {
            List<Object[]> rows = new ArrayList<>();
            int n = random.nextInt(300);
            for (int i = 0; i < n; i++) {
                rows.add(new Object[] {random.nextInt(8) == 0 ? null : random.nextInt(20),
                        random.nextInt(8) == 0 ? null : "k" + random.nextInt(3),
                        new java.math.BigDecimal(i).movePointLeft(2)});
            }
            List<Batch> batches = rows.isEmpty() ? List.of()
                    : Batches.split(Batch.ofRows(SCHEMA, rows), 1 + random.nextInt(40));
            int[] keys = random.nextBoolean() ? new int[] {0} : new int[] {0, 1};
            JoinTable table = new JoinTable(SCHEMA, batches, keys, InterpretedKeys.INSTANCE);
            assertEquals(n, table.rowCount());
            boolean nullKey = false;
            for (int i = 0; i < n; i++) {
                assertEquals(Arrays.asList(rows.get(i)), Arrays.asList(table.batch().row(i)), "row " + i);
                nullKey |= rows.get(i)[0] == null || keys.length == 2 && rows.get(i)[1] == null;
            }
            assertEquals(nullKey, table.hasNullKey());
            int[] hashes = new int[n];
            InterpretedKeys.INSTANCE.hash(table.batch(), keys, hashes);
            for (int i = 0; i < n; i++) {
                if (JoinTable.hasNullKey(table.batch(), keys, i)) {
                    continue;
                }
                List<Integer> chained = new ArrayList<>();
                for (int j = table.first(hashes[i]); j >= 0; j = table.next(j)) {
                    assertEquals(hashes[j], table.hash(j));
                    if (InterpretedKeys.INSTANCE.equal(table.batch(), i, keys, table.batch(), j, keys)) {
                        chained.add(j);
                    }
                }
                List<Integer> expected = new ArrayList<>();
                for (int j = n - 1; j >= 0; j--) {
                    if (!JoinTable.hasNullKey(table.batch(), keys, j)
                            && InterpretedKeys.INSTANCE.equal(table.batch(), i, keys, table.batch(), j, keys)) {
                        expected.add(j);
                    }
                }
                assertEquals(expected, chained, "round " + round + " row " + i);
            }
        }
    }

    @Test
    void theUnmatchedRowsComeInBuildOrder() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(new Object[] {i, "r" + i, null});
        }
        List<Batch> batches = Batches.split(Batch.ofRows(SCHEMA, rows), 4);
        JoinTable table = new JoinTable(SCHEMA, batches, new int[0], InterpretedKeys.INSTANCE);
        assertFalse(table.hasNullKey());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, table.unmatched());
        table.markMatched(4);
        table.markMatched(1);
        table.markMatched(4);
        assertArrayEquals(new int[] {0, 2, 3, 5}, table.unmatched());
        JoinTable empty = new JoinTable(SCHEMA, List.of(), new int[] {0}, InterpretedKeys.INSTANCE);
        assertEquals(0, empty.rowCount());
        assertEquals(-1, empty.first(42));
        assertArrayEquals(new int[0], empty.unmatched());
        assertTrue(empty.batch().schema().equals(SCHEMA));
    }
}
