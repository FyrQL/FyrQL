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

package net.fyrql.sql.ast;

/**
 * A statement: a query, or one of the statements that write, which are
 * {@code COPY ... TO}, {@code COPY ... FROM}, {@code CREATE TABLE},
 * {@code INSERT INTO} and {@code DROP TABLE}, or one that sets or shows a
 * setting of the session: {@code SET}, {@code RESET} and {@code SHOW}.
 */
public sealed interface Statement permits Query, CopyTo, CopyFrom, CreateTable, InsertInto, DropTable,
        SetVariable, ResetVariable, ShowVariable {
}
