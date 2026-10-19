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

/**
 * Where the executor gets the batches of a scan: the engine maps the table
 * of the scan to its connector; tests give batches directly.
 */
@FunctionalInterface
public interface TableSource {

    /** Opens a reader that delivers the columns of the scan, in its order. */
    BatchReader open(PlanNode.Scan scan);

    /**
     * Opens a reader that delivers the rows of a part of the plan that a
     * source runs. The default runs none.
     *
     * @throws UnsupportedOperationException if there is no source that runs parts of plans
     */
    default BatchReader open(PlanNode.Remote remote) {
        throw new UnsupportedOperationException("No source runs a part of a plan, as " + remote.source() + " would");
    }

    /**
     * Opens the reader of a scan of a query that a context cancels; the
     * reader may check the context between its parts. The caller registers
     * the reader with the context. The default ignores the context.
     */
    default BatchReader open(PlanNode.Scan scan, QueryContext context) {
        return open(scan);
    }

    /**
     * Returns the parts of a scan that can be read independently of each
     * other, for a query that a context cancels; read one after the other,
     * they give what {@link #open(PlanNode.Scan, QueryContext)} reads. The
     * default is one part, which that method opens.
     */
    default Splits splits(PlanNode.Scan scan, QueryContext context) {
        return Splits.single(() -> open(scan, context));
    }

    /**
     * Returns the parts of a scan as {@link #splits(PlanNode.Scan, QueryContext)}
     * does, for at most {@code threads} threads that read them at a time,
     * see {@link net.fyrql.connector.Connector#splits(net.fyrql.connector.ScanPlan, int)}.
     * The default ignores the threads.
     */
    default Splits splits(PlanNode.Scan scan, int threads, QueryContext context) {
        return splits(scan, context);
    }

    /**
     * Opens the reader of a part of the plan that a source runs, for a
     * query that a context cancels, see {@link #open(PlanNode.Scan, QueryContext)}.
     */
    default BatchReader open(PlanNode.Remote remote, QueryContext context) {
        return open(remote);
    }
}
