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

package net.fyrql.sql.render;

import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A statement with what it needs besides its text: the values of its
 * parameters, one per {@code ?} in the order of the text, and the number
 * of rows it keeps where the dialect has no clause for that. A dialect that
 * {@linkplain Dialect#bindsLiterals binds its literals} renders a scan as
 * such a statement; any other gives a plain one.
 *
 * @param sql the text
 * @param parameters the values of the parameters, in order
 * @param maxRows the number of rows the statement keeps, which whoever runs
 *        it must enforce; {@code 0} keeps none, unlike
 *        {@code java.sql.Statement.setMaxRows}, where it means all
 */
public record BoundSql(String sql, List<Parameter> parameters, OptionalLong maxRows) {

    /**
     * The value of one parameter with its type in FyrQL; never
     * {@code NULL}, which the statement writes as a literal.
     */
    public record Parameter(DataType type, Object value) {

        public Parameter {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    public BoundSql {
        Objects.requireNonNull(sql, "sql");
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(maxRows, "maxRows");
        if (maxRows.isPresent() && maxRows.getAsLong() < 0) {
            throw new IllegalArgumentException("A negative number of rows: " + maxRows.getAsLong());
        }
    }

    /** A statement without parameters that keeps every row. */
    public static BoundSql of(String sql) {
        return new BoundSql(sql, List.of(), OptionalLong.empty());
    }

    /** Tells whether the statement has no parameters and keeps every row. */
    public boolean isPlain() {
        return parameters.isEmpty() && maxRows.isEmpty();
    }

    /**
     * Returns the text, followed by the values of the parameters and the
     * number of rows if there are any, e.g.
     * {@code SELECT ... WHERE (t."a" > ?) -- parameters: 5; max rows: 10}.
     */
    @Override
    public String toString() {
        if (isPlain()) {
            return sql;
        }
        List<String> notes = new ArrayList<>();
        if (!parameters.isEmpty()) {
            List<String> values = new ArrayList<>();
            for (Parameter p : parameters) {
                values.add(describe(p));
            }
            notes.add("parameters: " + String.join(", ", values));
        }
        if (maxRows.isPresent()) {
            notes.add("max rows: " + maxRows.getAsLong());
        }
        return sql + " -- " + String.join("; ", notes);
    }

    private static String describe(Parameter p) {
        try {
            return GenericDialect.DEFAULT.literal(p.value(), p.type());
        } catch (IllegalArgumentException | ClassCastException e) {
            return String.valueOf(p.value());
        }
    }
}
