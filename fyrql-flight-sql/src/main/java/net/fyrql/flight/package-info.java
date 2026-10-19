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
 * An Arrow Flight SQL server for an engine of FyrQL. Clients in Python,
 * Java and other languages send SQL and read the results as Arrow record
 * batches. The server reads and does not write; it runs without native
 * code, with TLS from the JDK.
 */
package net.fyrql.flight;
