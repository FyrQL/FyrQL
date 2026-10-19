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
 * Results of FyrQL as Apache Arrow vectors, and Arrow vectors as batches
 * of FyrQL. The vectors of Arrow live outside the heap; the memory module
 * of Arrow needs {@code --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED}
 * on the command line of the JVM.
 */
package net.fyrql.arrow;
