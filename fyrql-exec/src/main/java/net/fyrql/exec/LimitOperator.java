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
