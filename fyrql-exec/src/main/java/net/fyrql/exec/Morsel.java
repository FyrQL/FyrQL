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
 * A unit of work of a segment: a split of the scan of one of its chains.
 *
 * @param chain the chain, in the order of the inputs of a union
 * @param split the split of the scan of the chain
 * @param index the place among all morsels of the segment, in the order one thread reads them
 */
record Morsel(int chain, int split, int index) {
}
