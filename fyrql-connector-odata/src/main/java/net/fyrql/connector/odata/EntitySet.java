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

import javax.xml.namespace.QName;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An entity set of the entity container of a service, which FyrQL lists
 * as a table.
 *
 * @param name the name, which is also the last segment of its URL
 * @param entityType the qualified name of the type of its entities
 * @param attributes the attributes in other namespaces than that of the
 *        document, such as {@code sap:pageable}
 * @param annotations the annotations
 */
public record EntitySet(String name, String entityType, Map<QName, String> attributes,
        List<Annotation> annotations) {

    public EntitySet {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(entityType, "entityType");
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        annotations = List.copyOf(annotations);
    }

    /** Returns the value of an attribute in another namespace, or {@code null}. */
    public String attribute(String namespace, String name) {
        return attributes.get(new QName(namespace, name));
    }
}
