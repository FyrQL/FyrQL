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

import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.CompiledFilter;

/**
 * Keeps the rows whose condition is {@code true}, see {@link Evaluator#select};
 * once {@link Codegen#due} says so, with a compiled condition.
 */
final class FilterOperator extends AbstractOperator {

    private final PlanNode.Filter filter;
    private final Operator input;
    private final Codegen codegen;
    private long rowsSeen;
    private boolean asked;
    private CompiledFilter compiled;

    FilterOperator(String label, PlanNode.Filter filter, Operator input, Codegen codegen) {
        super(label, filter.schema());
        this.filter = filter;
        this.input = input;
        this.codegen = codegen;
    }

    @Override
    Batch compute() {
        for (Batch b = input.next(); b != null; b = input.next()) {
            int[] rows = select(b);
            if (rows.length == b.rowCount()) {
                return b;
            }
            if (rows.length > 0) {
                return b.select(rows);
            }
        }
        return null;
    }

    private int[] select(Batch b) {
        if (!asked && codegen.due(rowsSeen)) {
            asked = true;
            compiled = codegen.filter(filter.condition(), filter.input().schema());
        }
        rowsSeen += b.rowCount();
        return compiled != null ? compiled.select(b) : Evaluator.select(filter.condition(), b);
    }

    @Override
    public void close() {
        input.close();
    }
}
