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
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.spi.CompiledProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the output columns from each input batch; once {@link Codegen#due}
 * says so, the columns that are not plain input columns with compiled
 * expressions.
 */
final class ProjectOperator extends AbstractOperator {

    private final PlanNode.Project project;
    private final Operator input;
    private final Codegen codegen;
    private final CompiledProjection[] compiled;
    private long rowsSeen;
    private boolean asked;

    ProjectOperator(String label, PlanNode.Project project, Operator input, Codegen codegen) {
        super(label, project.schema());
        this.project = project;
        this.input = input;
        this.codegen = codegen;
        this.compiled = new CompiledProjection[project.columns().size()];
    }

    @Override
    Batch compute() {
        Batch b = input.next();
        if (b == null) {
            return null;
        }
        if (!asked && codegen.due(rowsSeen)) {
            asked = true;
            for (int i = 0; i < compiled.length; i++) {
                Expression e = project.columns().get(i).expression();
                if (!(e instanceof Expression.Column)) {
                    compiled[i] = codegen.projection(e, project.input().schema());
                }
            }
        }
        rowsSeen += b.rowCount();
        List<Vector> columns = new ArrayList<>(project.columns().size());
        for (int i = 0; i < compiled.length; i++) {
            columns.add(compiled[i] != null ? compiled[i].evaluate(b)
                    : Evaluator.eval(project.columns().get(i).expression(), b));
        }
        return new Batch(schema(), b.rowCount(), columns);
    }

    @Override
    public void close() {
        input.close();
    }
}
