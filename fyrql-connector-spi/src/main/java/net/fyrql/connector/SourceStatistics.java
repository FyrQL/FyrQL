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

package net.fyrql.connector;

import java.util.List;

/**
 * What a scan read from its source, see {@link SourceCounters}.
 *
 * @param splits           the splits of the scan
 * @param requests         the requests to a remote store or service
 * @param requestNanos     the time of the requests together, in nanoseconds
 * @param bytes            the bytes read, by requests or from local files
 * @param rowGroupsRead    the row groups read
 * @param rowGroupsSkipped the row groups skipped, since no row of them could pass the filters
 * @param filesRead        the data files of a table read
 * @param filesSkipped     the data files of a table skipped
 * @param statements       the statements sent to a database, in their order
 */
public record SourceStatistics(long splits, long requests, long requestNanos, long bytes, long rowGroupsRead,
        long rowGroupsSkipped, long filesRead, long filesSkipped, List<String> statements) {

    /** Nothing read. */
    public static final SourceStatistics NONE = new SourceStatistics(0, 0, 0, 0, 0, 0, 0, 0, List.of());

    public SourceStatistics {
        statements = List.copyOf(statements);
    }
}
