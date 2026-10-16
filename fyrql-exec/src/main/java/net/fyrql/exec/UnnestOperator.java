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

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Repeats each input row once per element of its list, see
 * {@link PlanNode.Unnest}. The lists of a batch are evaluated at once; the
 * output comes in batches of at most the batch size, so that a long list
 * spreads over several.
 */
final class UnnestOperator extends AbstractOperator {

    private final PlanNode.Unnest unnest;
    private final Operator input;
    private final int batchSize;
    private Batch current;
    private ListVector lists;
    private int row;
    private int element;

    UnnestOperator(String label, PlanNode.Unnest unnest, Operator input, int batchSize) {
        super(label, unnest.schema());
        this.unnest = unnest;
        this.input = input;
        this.batchSize = batchSize;
    }

    @Override
    Batch compute() {
        int[] rows = new int[batchSize];
        int[] elements = new int[batchSize];
        while (true) {
            if (current == null) {
                current = input.next();
                if (current == null) {
                    return null;
                }
                lists = (ListVector) Evaluator.eval(unnest.list(), current);
                row = 0;
                element = -1;
            }
            int n = 0;
            while (n < batchSize && row < current.rowCount()) {
                if (element < 0) {
                    element = lists.isNull(row) ? lists.end(row) : lists.start(row);
                }
                if (element < lists.end(row)) {
                    rows[n] = row;
                    elements[n++] = element++;
                } else {
                    row++;
                    element = -1;
                }
            }
            Batch output = n == 0 ? null : output(rows, elements, n);
            if (row >= current.rowCount()) {
                current = null;
                lists = null;
            }
            if (output != null) {
                return output;
            }
        }
    }

    private Batch output(int[] rows, int[] elements, int n) {
        List<Vector> columns = new ArrayList<>(current.select(Arrays.copyOf(rows, n)).columns());
        columns.add(lists.child().select(Arrays.copyOf(elements, n)));
        return new Batch(schema(), n, columns);
    }

    @Override
    public void close() {
        input.close();
    }
}
