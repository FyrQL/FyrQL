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
