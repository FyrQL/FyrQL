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
 * A structural property of an entity or complex type, with the facets
 * that its type in FyrQL depends on. Navigation properties are not kept.
 *
 * @param name the name
 * @param type the qualified name of the type, such as {@code Edm.Int32},
 *        {@code Model.Address} or {@code Collection(Edm.String)}, also
 *        where the document names it by an alias
 * @param nullable whether the value may be null; {@code true} unless the
 *        document says {@code Nullable="false"}
 * @param maxLength the facet {@code MaxLength} as written, or {@code null}
 * @param precision the facet {@code Precision} as written, or {@code null}
 * @param scale the facet {@code Scale} as written, such as {@code 2} or
 *        {@code variable}, or {@code null}
 * @param attributes the attributes in other namespaces than that of the
 *        document, such as {@code sap:filterable}
 * @param annotations the annotations
 */
public record Property(String name, String type, boolean nullable, String maxLength, String precision, String scale,
        Map<QName, String> attributes, List<Annotation> annotations) {

    public Property {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        annotations = List.copyOf(annotations);
    }

    /** Tells whether the type is a collection, {@code Collection(...)}. */
    public boolean isCollection() {
        return type.startsWith("Collection(") && type.endsWith(")");
    }

    /** Returns the type of an element of a collection, or the type itself if it is none. */
    public String elementType() {
        return isCollection() ? type.substring("Collection(".length(), type.length() - 1) : type;
    }

    /** Returns the value of an attribute in another namespace, or {@code null}. */
    public String attribute(String namespace, String name) {
        return attributes.get(new QName(namespace, name));
    }

    /** Returns this property with other annotations. */
    Property withAnnotations(List<Annotation> annotations) {
        return new Property(name, type, nullable, maxLength, precision, scale, attributes, annotations);
    }
}
