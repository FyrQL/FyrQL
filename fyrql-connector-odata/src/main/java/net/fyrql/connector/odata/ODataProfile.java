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

package net.fyrql.connector.odata;

import net.fyrql.core.type.DataType;

/**
 * What the services of a kind of server need besides OData: query options
 * or headers with every request, such as the client of an SAP system, and
 * other types for the columns of properties that the server marks, such as
 * a date that version 2 can only send as {@code Edm.DateTime}. The
 * standard profile changes nothing. A profile is called by the threads that
 * use the connector, at the same time, and must not block.
 */
public interface ODataProfile {

    /**
     * Changes a request before it is sent, see {@link RequestSpec}; called
     * once for every request, also for every page of an answer.
     */
    default void decorate(RequestSpec request) {
    }

    /**
     * Returns the type of the column of a property of the type of an entity
     * set, or {@code null} to leave the property out of the table. The
     * values of a column of another type than the mapped one are read from
     * the JSON of the service as that type, and the connector neither
     * filters nor sorts by it.
     *
     * @param mapped the type the connector maps the property to, see
     *        {@link ODataConnector}
     */
    default DataType type(EntitySet set, Property property, DataType mapped) {
        return mapped;
    }

    /** Returns the profile that changes nothing. */
    static ODataProfile standard() {
        return new ODataProfile() {
        };
    }
}
