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

import net.fyrql.core.type.DataType;
import net.fyrql.sql.Position;

import java.util.List;
import java.util.Objects;

/**
 * {@code CREATE [OR REPLACE] [TEMP] TABLE [IF NOT EXISTS] name (columns)} or
 * {@code CREATE [OR REPLACE] [TEMP] TABLE [IF NOT EXISTS] name AS query}.
 *
 * @param name the name of the table, of one to three parts
 * @param columns the columns, empty for {@code AS query}
 * @param query the query of {@code AS}, or {@code null}
 * @param primaryKey the columns of the primary key as written, empty for none
 * @param temporary whether the table lives in the session alone, as {@code TEMP} or {@code TEMPORARY} says
 * @param position the position of the name
 */
public record CreateTable(List<String> name, List<ColumnDefinition> columns, Query query, boolean orReplace,
        boolean ifNotExists, List<String> primaryKey, boolean temporary, Position position) implements Statement {

    public CreateTable {
        name = List.copyOf(name);
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        if (columns.isEmpty() == (query == null)) {
            throw new IllegalArgumentException("A table has either columns or a query");
        }
        if (orReplace && ifNotExists) {
            throw new IllegalArgumentException("OR REPLACE and IF NOT EXISTS exclude each other");
        }
        Objects.requireNonNull(position, "position");
    }

    /** A column of {@code CREATE TABLE name (columns)}: {@code name type [NOT NULL]}. */
    public record ColumnDefinition(String name, DataType type, boolean nullable) {
        public ColumnDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }
}
