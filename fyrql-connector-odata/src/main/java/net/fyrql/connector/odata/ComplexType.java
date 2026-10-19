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
 * A complex type as declared: its own structural properties, without those
 * of its base type; {@link ServiceMetadata#properties(ComplexType)} adds them.
 *
 * @param name the qualified name
 * @param baseType the qualified name of the base type, or {@code null}
 * @param properties the structural properties
 * @param attributes the attributes in other namespaces than that of the document
 * @param annotations the annotations
 */
public record ComplexType(String name, String baseType, List<Property> properties, Map<QName, String> attributes,
        List<Annotation> annotations) {

    public ComplexType {
        Objects.requireNonNull(name, "name");
        properties = List.copyOf(properties);
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        annotations = List.copyOf(annotations);
    }
}
