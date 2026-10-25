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
import net.fyrql.core.vector.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the columns of a {@link PlanNode.CommonTableScan} from the buffer
 * of its common table, from the start, once the
 * {@link CommonTableOperator} above has filled it.
 */
final class CommonTableScanOperator extends AbstractOperator {

    private final List<Integer> columns;
    private final CommonTableBuffer buffer;
    private CommonTableBuffer.Reader reader;

    CommonTableScanOperator(String label, PlanNode.CommonTableScan scan, CommonTableBuffer buffer) {
        super(label, scan.schema());
        if (buffer == null) {
            throw new IllegalArgumentException("No common table " + scan.name() + " #" + scan.id()
                    + " above its scan " + label);
        }
        this.columns = scan.columns();
        this.buffer = buffer;
    }

    @Override
    Batch compute() {
        if (reader == null) {
            reader = buffer.reader();
        }
        Batch b = reader.next();
        if (b == null) {
            return null;
        }
        List<Vector> vectors = new ArrayList<>(columns.size());
        for (int c : columns) {
            vectors.add(b.column(c));
        }
        return new Batch(schema(), b.rowCount(), vectors);
    }

    @Override
    public void close() {
        // The buffer belongs to the common table, which closes it with its readers.
    }
}
