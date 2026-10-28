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

import net.fyrql.sql.Position;

import java.util.List;
import java.util.Objects;

/**
 * {@code ALTER TABLE name ADD [COLUMN] [IF NOT EXISTS] column type
 * [DEFAULT value]}, the one change of a table that is supported.
 *
 * @param name the name of the table, of one to three parts
 * @param column the new column, which may hold {@code NULL}
 * @param defaultValue the value of the column in the rows the table has, or {@code null} for {@code NULL}
 * @param position the position of the name
 */
public record AlterTable(List<String> name, CreateTable.ColumnDefinition column, SqlExpr defaultValue,
        boolean ifNotExists, Position position) implements Statement {

    public AlterTable {
        name = List.copyOf(name);
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(position, "position");
    }
}
