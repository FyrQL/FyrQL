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
