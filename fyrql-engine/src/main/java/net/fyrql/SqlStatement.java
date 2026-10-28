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

package net.fyrql;

import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.bind.BoundStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A statement parsed and bound in a session, ready to run: a query,
 * {@code EXPLAIN}, a statement that writes, or one that changes a setting.
 * It comes from {@link Session#statement(String)} or
 * {@link FyrQL#statement(String)} and runs with
 * {@link Session#execute(SqlStatement, net.fyrql.exec.QueryContext)}, e.g.
 * when a caller needs the columns of the result before it runs, or a
 * context of its own to cancel it. Its content is not part of the API.
 */
public final class SqlStatement {

    private final BoundStatement bound;
    /** The text of the statement, {@code null} if it has none, e.g. one prepared with values. */
    private final String sql;

    SqlStatement(BoundStatement bound, String sql) {
        this.bound = Objects.requireNonNull(bound, "bound");
        this.sql = sql;
    }

    /** Returns the bound statement. */
    BoundStatement bound() {
        return bound;
    }

    /** Returns the text of the statement, {@code null} if it has none. */
    String sql() {
        return sql;
    }

    /**
     * Returns the columns of the result: those of a query, the column
     * {@code plan} of {@code EXPLAIN}, or the column {@code Count} of a
     * statement that writes, which holds the number of rows written.
     */
    public Schema schema() {
        return bound.schema();
    }

    /**
     * Tells whether the result is rows of its own, as of a query and of
     * {@code EXPLAIN}; otherwise it is the row of the number of rows
     * written, and the statement runs to its end before its result is there.
     */
    public boolean isQuery() {
        return bound instanceof BoundStatement.Query || bound instanceof BoundStatement.Explain;
    }

    /**
     * A column of a table that a column of the result of a query shows as
     * it is, so that a tool may change it with an {@code UPDATE} of the
     * table.
     *
     * @param table the table
     * @param column the name of the column in the table
     */
    public record ColumnOrigin(TableRef table, String column) {
        public ColumnOrigin {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(column, "column");
        }
    }

    /**
     * Returns for each column of the result of a query the column of a
     * table it shows as it is: through projections of columns, filters,
     * sorts and limits down to the table; empty for a column that the query
     * computes, and for each column of a statement that is no query.
     */
    public List<Optional<ColumnOrigin>> origins() {
        List<Optional<ColumnOrigin>> result = new ArrayList<>();
        if (bound instanceof BoundStatement.Query q) {
            for (Plans.Origin o : Plans.origins(q.plan())) {
                result.add(o == null ? Optional.empty() : Optional.of(new ColumnOrigin(o.table(), o.column())));
            }
        } else {
            for (int i = 0; i < schema().size(); i++) {
                result.add(Optional.empty());
            }
        }
        return result;
    }

    /**
     * Tells whether running the statement changes something: a table, a
     * file or a setting of the session. A query does not, nor does
     * {@code EXPLAIN} without {@code ANALYZE}.
     */
    public boolean writes() {
        return bound.writes();
    }

    /** Returns the text of the statement, or a description of it if it has none. */
    @Override
    public String toString() {
        return sql != null ? sql : bound.getClass().getSimpleName();
    }
}
