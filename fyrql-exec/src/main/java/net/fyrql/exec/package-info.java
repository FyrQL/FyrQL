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

/**
 * The vectorized execution of plans: operators, pipelines, parallel
 * segments, the memory budget and spilling, and the context and statistics
 * of a query.
 * <p>
 * Internal; not covered by the compatibility promise. Classes marked
 * otherwise in their documentation are the exceptions.
 */
package net.fyrql.exec;
