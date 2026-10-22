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
 * What operators spilled to disk when the memory budget was exhausted: the
 * parts they wrote (a sorted run, the rows of a partition of an
 * aggregation), the bytes of those parts and the bytes they read back.
 *
 * @param parts        the parts written
 * @param bytesWritten the bytes of the parts written
 * @param bytesRead    the bytes read back
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
public record SpillStatistics(long parts, long bytesWritten, long bytesRead) {

    /** Nothing spilled. */
    public static final SpillStatistics NONE = new SpillStatistics(0, 0, 0);
}
