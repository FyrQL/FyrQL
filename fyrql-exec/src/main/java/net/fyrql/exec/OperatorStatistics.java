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

/**
 * What an operator of a query did, all its copies on the workers of a
 * segment together.
 *
 * @param rows         the rows it returned
 * @param batches      the batches it returned
 * @param nanos        the time it worked, without the time of its inputs, summed over the threads, in
 *                     nanoseconds
 * @param threads      the threads it worked on
 * @param spilledParts the parts it spilled to disk
 * @param spilledBytes the bytes of the parts it spilled
 * @param readBack     the bytes it read back from disk
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
public record OperatorStatistics(long rows, long batches, long nanos, int threads, long spilledParts,
        long spilledBytes, long readBack) {

    /** Nothing done, such as by a stage of a compiled pipeline. */
    public static final OperatorStatistics NONE = new OperatorStatistics(0, 0, 0, 0, 0, 0, 0);
}
