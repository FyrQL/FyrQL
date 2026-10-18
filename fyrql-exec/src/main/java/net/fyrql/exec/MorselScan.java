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
import net.fyrql.core.vector.Batch;

/**
 * The scan of one copy of a chain of a segment: it reads the splits of the
 * morsels it takes, one after the other, until no morsel of its chain is
 * left for it. It knows the morsel of the batch it read last; the operators
 * of a chain pass on what they make of a batch before they read the next
 * one, so that this is also the morsel of the batch the chain returns.
 */
final class MorselScan extends AbstractOperator {

    /** Hands out the morsels of the chains of a segment. */
    interface Morsels {

        /**
         * Returns the next morsel of a chain, or {@code null} if there is none
         * for this scan.
         *
         * @param finished the morsel the scan has read to its end, or {@code null}
         */
        Morsel next(int chain, Morsel finished);
    }

    private final String what;
    private final int chain;
    private final Splits splits;
    private final Morsels morsels;
    private Morsel morsel;
    private volatile BatchReader reader;
    private boolean registered;

    /**
     * @param chain   the chain whose morsels the scan takes
     * @param splits  the splits of the scan
     * @param morsels where the scan takes its morsels
     */
    MorselScan(String label, PlanNode.Scan scan, int chain, Splits splits, Morsels morsels) {
        super(label, scan.schema());
        this.what = scan.table().toString();
        this.chain = chain;
        this.splits = splits;
        this.morsels = morsels;
    }

    /** Returns the morsel of the batch read last, or {@code null} before the first and after the last one. */
    Morsel morsel() {
        return morsel;
    }

    @Override
    Batch compute() {
        if (!registered) {
            registered = true;
            // One registration for all splits: canceling the query stops the reader of the current one.
            context().register(new BatchReader() {
                @Override
                public Batch next() {
                    return null;
                }

                @Override
                public void cancel() {
                    BatchReader r = reader;
                    if (r != null) {
                        r.cancel();
                    }
                }
            });
        }
        while (true) {
            BatchReader r = reader;
            if (r == null) {
                Morsel next = morsels.next(chain, morsel);
                morsel = next;
                if (next == null) {
                    return null;
                }
                checkCancelled();
                r = splits.open(next.split());
                reader = r;
            }
            Batch b = r.next();
            if (b == null) {
                reader = null;
                r.close();
                continue;
            }
            if (b.rowCount() > 0) {
                return ScanOperator.checked(b, schema(), what);
            }
        }
    }

    @Override
    public void close() {
        BatchReader r = reader;
        if (r != null) {
            reader = null;
            r.close();
        }
    }
}
