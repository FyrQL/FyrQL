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

import java.util.List;
import java.util.Objects;

/**
 * A type definition of OData 4: a primitive type under a name of its own,
 * with facets that a property of the type may also give.
 *
 * @param name the qualified name
 * @param underlyingType the primitive type, such as {@code Edm.Decimal}
 * @param maxLength the facet {@code MaxLength} as written, or {@code null}
 * @param precision the facet {@code Precision} as written, or {@code null}
 * @param scale the facet {@code Scale} as written, or {@code null}
 * @param annotations the annotations
 */
public record TypeDefinition(String name, String underlyingType, String maxLength, String precision, String scale,
        List<Annotation> annotations) {

    public TypeDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(underlyingType, "underlyingType");
        annotations = List.copyOf(annotations);
    }
}
