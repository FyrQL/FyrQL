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

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;

/** Skips the offset, then passes rows up to the count. */
final class LimitOperator extends AbstractOperator {

    private final Operator input;
    private long toSkip;
    private long remaining;

    LimitOperator(String label, PlanNode.Limit limit, Operator input) {
        super(label, limit.schema());
        this.input = input;
        this.toSkip = limit.offset();
        this.remaining = limit.count();
    }

    @Override
    Batch compute() {
        while (remaining > 0) {
            Batch b = input.next();
            if (b == null) {
                return null;
            }
            int from = 0;
            if (toSkip > 0) {
                if (toSkip >= b.rowCount()) {
                    toSkip -= b.rowCount();
                    continue;
                }
                from = (int) toSkip;
                toSkip = 0;
            }
            int to = from + (int) Math.min(b.rowCount() - from, remaining);
            remaining -= to - from;
            if (from == 0 && to == b.rowCount()) {
                return b;
            }
            int[] rows = new int[to - from];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = from + i;
            }
            return b.select(rows);
        }
        return null;
    }

    @Override
    public void close() {
        input.close();
    }
}
