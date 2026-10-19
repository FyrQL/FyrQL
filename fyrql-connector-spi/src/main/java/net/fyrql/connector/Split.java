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

/**
 * One part of a scan that can be read on its own: a file, a row group, a
 * partition, a shard. A connector without parallelism returns one split.
 *
 * @param plan the scan
 * @param index the number of the part, from 0
 * @param state whatever the connector needs to read the part
 */
public record Split(ScanPlan plan, int index, Object state) {
}
