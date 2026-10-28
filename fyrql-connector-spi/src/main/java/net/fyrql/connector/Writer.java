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

package net.fyrql.connector;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.util.List;
import java.util.OptionalLong;

/**
 * What a source offers to change its tables, through
 * {@link Connector#writer()}: create a table, append rows to one, drop one,
 * change its rows, add a column, create a schema. The engine checks the
 * catalog first, so that {@link #create} is called for a table that does
 * not exist, in a schema that does, the other methods for a table that
 * exists, and {@link #createSchema} for a schema that does not exist. After
 * the commit of a {@link #create} or of {@link #change changes}, and after
 * the other methods, the {@link Connector#catalog() catalog} of the source
 * shows the change.
 * <p>
 * Rows are changed in one of two ways. A source that can run a whole
 * {@code UPDATE} or {@code DELETE} itself takes it over with
 * {@link #update} or {@link #delete}; the expressions refer to the columns
 * of the table by their position in its
 * {@linkplain Catalog#schema(TableRef) schema}, and the source evaluates
 * them with exactly the semantics of the engine, like a filter it takes
 * over in a scan. Otherwise, and for statements that read other tables,
 * the engine computes the changed rows itself and hands them to
 * {@link #change} by key.
 */
public interface Writer {

    /**
     * Opens a sink that creates a table with the given columns; the table
     * appears with the commit of the sink, even if no row was written.
     *
     * @throws IllegalArgumentException with a message for the user that
     *         names the column and the type, if the source cannot store a
     *         type
     */
    Sink create(TableRef table, Schema schema);

    /**
     * Opens a sink that creates a table with the given columns and primary
     * key. The source checks the key with every later change: its columns
     * hold no {@code NULL}, and no two rows the same values.
     *
     * @param primaryKey the names of the key columns in the order of the
     *        key; empty for a table without a key, which this default
     *        creates with {@link #create(TableRef, Schema)}
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot keep a primary key
     */
    default Sink create(TableRef table, Schema schema, List<String> primaryKey) {
        if (!primaryKey.isEmpty()) {
            throw new IllegalArgumentException("Source " + table.source() + " does not keep primary keys");
        }
        return create(table, schema);
    }

    /**
     * Opens a sink that appends rows to an existing table, in the columns
     * of its {@linkplain Catalog#schema(TableRef) schema}.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot write the table
     */
    Sink append(TableRef table);

    /**
     * Drops an existing table.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot drop the table
     */
    void drop(TableRef table);

    /**
     * Deletes the rows of a table for which a condition holds, as a whole
     * statement, if the source can.
     *
     * @param condition a {@code BOOLEAN} expression over the columns of the
     *        table; {@code null} to delete every row
     * @return the number of rows deleted, or empty if the source does not
     *         take the statement over, in which case the engine deletes the
     *         rows by key with {@link #change}
     */
    default OptionalLong delete(TableRef table, Expression condition) {
        return OptionalLong.empty();
    }

    /**
     * Sets columns of the rows of a table for which a condition holds, as a
     * whole statement, if the source can. The new values are computed from
     * the values of each row before the update.
     *
     * @param columns the positions of the columns to set, each once
     * @param values for each column to set, an expression over the columns
     *        of the table, of the type of the column
     * @param condition a {@code BOOLEAN} expression over the columns of the
     *        table; {@code null} to update every row
     * @return the number of rows updated, or empty if the source does not
     *         take the statement over, in which case the engine updates the
     *         rows by key with {@link #change}
     */
    default OptionalLong update(TableRef table, int[] columns, List<Expression> values, Expression condition) {
        return OptionalLong.empty();
    }

    /**
     * Opens changes to the rows of a table by key.
     *
     * @param keys the positions of the key columns: the primary key, or
     *        every column of a table without one
     * @param updated the positions of the columns that updates set
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot change rows
     */
    default RowChanges change(TableRef table, int[] keys, int[] updated) {
        throw new IllegalArgumentException("Source " + table.source() + " does not change rows");
    }

    /**
     * Adds a column at the end of a table, holding the default value in
     * every row.
     *
     * @param defaultValue the value of the column in every existing row, as
     *        a value of its type; {@code null} for {@code NULL}
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot add the column
     */
    default void addColumn(TableRef table, Schema.Field column, Object defaultValue) {
        throw new IllegalArgumentException("Source " + table.source() + " does not add columns");
    }

    /**
     * Creates a schema.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         source cannot create schemas
     */
    default void createSchema(String schema) {
        throw new IllegalArgumentException("This source does not create schemas");
    }
}
