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
