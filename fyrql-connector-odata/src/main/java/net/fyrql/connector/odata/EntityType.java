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
 * An entity type as declared: its own key and structural properties,
 * without those of its base type; {@link ServiceMetadata#properties} and
 * {@link ServiceMetadata#key} add them.
 *
 * @param name the qualified name
 * @param baseType the qualified name of the base type, or {@code null}
 * @param key the names of the properties of the key, empty if the base type declares it
 * @param properties the structural properties
 * @param attributes the attributes in other namespaces than that of the document
 * @param annotations the annotations
 */
public record EntityType(String name, String baseType, List<String> key, List<Property> properties,
        Map<QName, String> attributes, List<Annotation> annotations) {

    public EntityType {
        Objects.requireNonNull(name, "name");
        key = List.copyOf(key);
        properties = List.copyOf(properties);
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        annotations = List.copyOf(annotations);
    }

    /** Returns the value of an attribute in another namespace, or {@code null}. */
    public String attribute(String namespace, String name) {
        return attributes.get(new QName(namespace, name));
    }
}
