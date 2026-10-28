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
 * {@code MERGE INTO name [[AS] alias] USING source (ON condition | USING
 * (columns)) WHEN ... THEN ...}, as in DuckDB.
 *
 * @param name the name of the table, of one to three parts
 * @param alias the alias of the table, or {@code null}
 * @param source the rows to merge, with their alias
 * @param on the condition that matches a row of the table with one of the
 *        source, or {@code null} with {@code using}
 * @param using the columns of {@code USING (columns)}, empty with {@code on}
 * @param whens the branches in their order, of which the first that holds
 *        for a pair of rows acts
 * @param position the position of the name
 */
public record Merge(List<String> name, String alias, FromItem source, SqlExpr on, List<String> using,
        List<When> whens, Position position) implements Statement {

    public Merge {
        name = List.copyOf(name);
        Objects.requireNonNull(source, "source");
        using = List.copyOf(using);
        if ((on == null) == using.isEmpty()) {
            throw new IllegalArgumentException("MERGE has either ON or USING");
        }
        whens = List.copyOf(whens);
        if (whens.isEmpty()) {
            throw new IllegalArgumentException("MERGE needs a WHEN");
        }
        Objects.requireNonNull(position, "position");
    }

    /** Which rows a branch is for. */
    public enum Match {
        /** {@code WHEN MATCHED}: a row of the table and one of the source. */
        MATCHED,
        /** {@code WHEN NOT MATCHED [BY TARGET]}: a row of the source that matches no row of the table. */
        NOT_MATCHED,
        /** {@code WHEN NOT MATCHED BY SOURCE}: a row of the table that matches no row of the source. */
        NOT_MATCHED_BY_SOURCE
    }

    /**
     * {@code WHEN match [AND condition] THEN action}.
     *
     * @param condition the condition after {@code AND}, or {@code null}
     */
    public record When(Match match, SqlExpr condition, Action action, Position position) {
        public When {
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(position, "position");
        }
    }

    /** What a branch does. */
    public sealed interface Action {
    }

    /**
     * {@code UPDATE [SET column = value, ...]}; without {@code SET}, every
     * column of the table takes the column of the source at its position.
     *
     * @param assignments the columns to set, or {@code null} for all of them
     */
    public record UpdateAction(List<Update.Assignment> assignments) implements Action {
        public UpdateAction {
            assignments = assignments == null ? null : List.copyOf(assignments);
        }
    }

    /** {@code DELETE}. */
    public record DeleteAction() implements Action {
    }

    /**
     * {@code INSERT [(columns)] VALUES (values)}, {@code INSERT} with the
     * columns of the source by position, or {@code INSERT BY NAME} with
     * those of the same names.
     *
     * @param columns the columns of the list, empty for all
     * @param values the values, or {@code null} for those of the source
     * @param byName whether the columns of the source go to the columns of their names
     */
    public record InsertAction(List<String> columns, List<SqlExpr> values, boolean byName) implements Action {
        public InsertAction {
            columns = List.copyOf(columns);
            values = values == null ? null : List.copyOf(values);
        }
    }

    /** {@code DO NOTHING}. */
    public record NothingAction() implements Action {
    }
}
