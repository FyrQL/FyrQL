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
