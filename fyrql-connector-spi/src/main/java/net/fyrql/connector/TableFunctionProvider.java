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

import net.fyrql.core.plan.TableRef;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A {@link Connector} that also offers table functions, such as
 * {@code read_csv('orders.csv', delim = ';')}: a call with constant
 * arguments names a table of the connector, which the engine then scans
 * like any other. The connector may create the table for the call and
 * should give the same table to the same call, so that repeated queries do
 * not create tables without end.
 */
public interface TableFunctionProvider {

    /** Returns the names of the table functions, in lower case. */
    List<String> tableFunctions();

    /**
     * Returns the table a call reads, a table of this connector.
     *
     * @param name one of {@link #tableFunctions()}
     * @param arguments the positional arguments as Java values of their
     *        types (see {@link net.fyrql.core.type.DataType}); a text as
     *        the first one is the location the call reads, which the engine
     *        has checked against its {@link FileAccessPolicy}
     * @param namedArguments the named arguments, with the names in lower
     *        case, in the order of the call, a struct such as
     *        {@code {'year': 'INTEGER'}} as a map from the names of its
     *        fields to their values, and
     *        {@link FileWriterProvider#TIME_ZONE} as a string at the end
     *        unless the call has it
     * @throws IllegalArgumentException with a message for the user if the
     *         arguments do not fit or the table cannot be read
     */
    TableRef bind(String name, List<Object> arguments, Map<String, Object> namedArguments);

    /**
     * Returns the text of a call, for the name of the table it reads, such
     * as {@code read_csv('orders.csv', delim = ';')}: the function, the
     * positional arguments in their order and the named ones in the order
     * of their names, each value as a literal of SQL, without the
     * {@link FileWriterProvider#TIME_ZONE} that the engine adds. A table
     * whose name is {@code #} and the text is printed as the call, see
     * {@link TableRef#toString()}.
     *
     * @param name the function
     * @param arguments the positional arguments
     * @param namedArguments the named arguments
     * @return the text
     */
    static String callText(String name, List<?> arguments, Map<String, ?> namedArguments) {
        StringBuilder b = new StringBuilder(name).append('(');
        String separator = "";
        for (Object a : arguments) {
            b.append(separator);
            literal(b, a);
            separator = ", ";
        }
        for (String key : new TreeSet<>(namedArguments.keySet())) {
            if (!key.equals(FileWriterProvider.TIME_ZONE)) {
                b.append(separator).append(key).append(" = ");
                literal(b, namedArguments.get(key));
                separator = ", ";
            }
        }
        return b.append(')').toString();
    }

    private static void literal(StringBuilder b, Object value) {
        switch (value) {
            case null -> b.append("NULL");
            case String s -> b.append('\'').append(s.replace("'", "''")).append('\'');
            case Number n -> b.append(n);
            case Boolean v -> b.append(v);
            case List<?> list -> {
                b.append('[');
                for (int i = 0; i < list.size(); i++) {
                    b.append(i > 0 ? ", " : "");
                    literal(b, list.get(i));
                }
                b.append(']');
            }
            case Map<?, ?> map -> {
                b.append('{');
                String separator = "";
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    b.append(separator);
                    literal(b, String.valueOf(e.getKey()));
                    b.append(": ");
                    literal(b, e.getValue());
                    separator = ", ";
                }
                b.append('}');
            }
            default -> literal(b, value.toString());
        }
    }
}
