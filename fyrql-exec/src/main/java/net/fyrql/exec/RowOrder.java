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

import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.Arrays;
import java.util.List;

/**
 * The order of the rows of a batch by sort keys, each with its direction
 * and the place of {@code NULL}. Rows whose keys are all equal are equal,
 * and so are two {@code NULL}s; without keys, all rows are equal.
 */
final class RowOrder {

    private final Batch batch;
    private final List<SortKey> keys;
    private final Vector[] columns;

    RowOrder(Batch batch, List<SortKey> keys) {
        this.batch = batch;
        this.keys = keys;
        this.columns = new Vector[keys.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = batch.column(keys.get(i).column());
        }
    }

    /** Compares two rows of the batch. */
    int compare(int a, int b) {
        for (int i = 0; i < columns.length; i++) {
            SortKey k = keys.get(i);
            Vector v = columns[i];
            boolean nullA = v.isNull(a);
            boolean nullB = v.isNull(b);
            int c;
            if (nullA || nullB) {
                c = nullA == nullB ? 0 : nullA == k.nullsFirst() ? -1 : 1;
            } else {
                c = Vectors.compare(v, a, v, b);
                c = k.ascending() ? c : -c;
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Returns the rows of the batch in this order; equal rows keep their
     * order.
     *
     * @param check runs every {@link AbstractOperator#CHECK_INTERVAL}
     *        comparisons, to stop a canceled query
     */
    int[] sort(Runnable check) {
        Integer[] order = new Integer[batch.rowCount()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        int[] comparisons = new int[1];
        Arrays.sort(order, (a, b) -> {
            if ((++comparisons[0] & (AbstractOperator.CHECK_INTERVAL - 1)) == 0) {
                check.run();
            }
            return compare(a, b);
        });
        int[] rows = new int[order.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = order[i];
        }
        return rows;
    }
}
