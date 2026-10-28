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
 * {@code INSERT INTO}, {@code UPDATE}, {@code DELETE}, {@code MERGE},
 * {@code DROP TABLE}, {@code CREATE SCHEMA} and {@code ALTER TABLE}, or one
 * that defines a view, {@code CREATE VIEW} and
 * {@code DROP VIEW}, or one that sets or shows a setting of the session:
 * {@code SET}, {@code RESET} and {@code SHOW}; or {@code EXPLAIN} of one of
 * these.
 */
public sealed interface Statement permits Query, CopyTo, CopyFrom, CreateTable, InsertInto, Update, Delete,
        Merge, DropTable, CreateSchema, AlterTable, CreateView, DropView, SetVariable, ResetVariable, ShowVariable,
        Explain {
}
