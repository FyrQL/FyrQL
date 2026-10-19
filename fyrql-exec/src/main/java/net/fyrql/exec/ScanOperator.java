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

import net.fyrql.connector.BatchReader;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.util.function.Function;

/**
 * Reads the batches of a scan, or of a part of the plan that a source runs,
 * from its source and checks that they have the expected types.
 */
final class ScanOperator extends AbstractOperator {

    private final String what;
    private final Function<QueryContext, BatchReader> opener;
    private BatchReader reader;

    ScanOperator(String label, PlanNode.Scan scan, TableSource source) {
        this(label, scan, scan.table().toString(), context -> source.open(scan, context));
    }

    ScanOperator(String label, PlanNode.Remote remote, TableSource source) {
        this(label, remote, "the part of the plan that " + remote.source() + " runs",
                context -> source.open(remote, context));
    }

    private ScanOperator(String label, PlanNode node, String what, Function<QueryContext, BatchReader> opener) {
        super(label, node.schema());
        this.what = what;
        this.opener = opener;
    }

    @Override
    Batch compute() {
        if (reader == null) {
            reader = opener.apply(context());
            context().register(reader);
        }
        while (true) {
            Batch b = reader.next();
            if (b == null) {
                return null;
            }
            if (b.rowCount() == 0) {
                continue;
            }
            return checked(b, schema(), what);
        }
    }

    /**
     * Returns a batch of a source with the names of the scan, after checking
     * that it has the types of the scan.
     *
     * @param what the scan in the message of a failure
     */
    static Batch checked(Batch b, Schema schema, String what) {
        if (!b.schema().types().equals(schema.types())) {
            throw new IllegalStateException("The source of " + what + " delivered the types "
                    + b.schema().types() + ", the scan expects " + schema.types());
        }
        return b.schema().equals(schema) ? b : new Batch(schema, b.rowCount(), b.columns());
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
