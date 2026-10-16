/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
