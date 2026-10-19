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
     *        types (see {@link net.fyrql.core.type.DataType})
     * @param namedArguments the named arguments, with the names in lower
     *        case, in the order of the call
     * @throws IllegalArgumentException with a message for the user if the
     *         arguments do not fit or the table cannot be read
     */
    TableRef bind(String name, List<Object> arguments, Map<String, Object> namedArguments);
}
