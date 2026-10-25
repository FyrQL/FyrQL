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
 * A server for an engine of FyrQL that speaks the wire protocol of
 * PostgreSQL (version 3). psql, psycopg, the JDBC driver of PostgreSQL and
 * the tools built on them connect without a driver of their own; the
 * database of a connection is a source of the engine. The server shows
 * {@code pg_catalog} and {@code information_schema} to its own connections
 * only. It runs on the classes of the JDK, with TLS from the JDK.
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
package net.fyrql.pgwire;
