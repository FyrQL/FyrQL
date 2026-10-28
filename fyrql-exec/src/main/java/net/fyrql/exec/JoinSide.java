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

import net.fyrql.exec.compiled.JoinTable;

/**
 * The build side of a join once it is read: a table in memory, or rows on
 * disk in partitions, when they did not fit in the budget, which the join
 * then probes partition by partition, see {@link GraceHashJoin}.
 */
sealed interface JoinSide extends AutoCloseable {

    /** Returns the rows of the build side. */
    long rowCount();

    /** Tells whether a row of the build side has a {@code NULL} key. */
    boolean hasNullKey();

    /** Deletes what went to disk, if anything. */
    @Override
    void close();

    /** A build side in memory. */
    record Table(JoinTable table) implements JoinSide {

        @Override
        public long rowCount() {
            return table.rowCount();
        }

        @Override
        public boolean hasNullKey() {
            return table.hasNullKey();
        }

        @Override
        public void close() {
            // Its memory is given back by the operator that reserved it.
        }
    }

    /**
     * A build side on disk.
     *
     * @param partitions the rows, cut into partitions by the hashes of their keys
     * @param rowCount   the rows of the build side
     * @param hasNullKey whether a row of the build side has a {@code NULL} key
     */
    record Spilled(JoinPartitions partitions, long rowCount, boolean hasNullKey) implements JoinSide {

        @Override
        public void close() {
            partitions.close();
        }
    }
}
