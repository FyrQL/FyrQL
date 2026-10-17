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
     * Opens the reader of a part of the plan that a source runs, for a
     * query that a context cancels, see {@link #open(PlanNode.Scan, QueryContext)}.
     */
    default BatchReader open(PlanNode.Remote remote, QueryContext context) {
        return open(remote);
    }
}
