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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The types in FyrQL of the properties of OData:
 * <ul>
 * <li>{@code Edm.Boolean} is {@code BOOLEAN}; {@code Edm.SByte},
 * {@code Edm.Byte}, {@code Edm.Int16}, {@code Edm.Int32} and
 * {@code Edm.Int64} are {@code TINYINT}, {@code SMALLINT} (which holds the
 * 0 to 255 of a byte), {@code SMALLINT}, {@code INTEGER} and
 * {@code BIGINT}; {@code Edm.Single} and {@code Edm.Double} are
 * {@code FLOAT} and {@code DOUBLE};</li>
 * <li>{@code Edm.Decimal} with a {@code Precision} of at most 38 and a
 * numeric {@code Scale}, 0 if it is missing, is {@code DECIMAL} of them;
 * without a precision, with a larger one, or with a {@code Scale} of
 * {@code variable} or {@code floating} it is {@code DOUBLE};</li>
 * <li>{@code Edm.String} is {@code VARCHAR}, {@code Edm.Guid} is
 * {@code UUID}, {@code Edm.Binary} is {@code BLOB};</li>
 * <li>{@code Edm.DateTime} of version 2 and {@code Edm.DateTimeOffset} are
 * {@code TIMESTAMP}, the latter as the instant in UTC; {@code Edm.Date} is
 * {@code DATE}; {@code Edm.Time}, {@code Edm.TimeOfDay} and
 * {@code Edm.Duration}, for which FyrQL has no type, are {@code VARCHAR}
 * with the text the service sends;</li>
 * <li>a complex type is a {@code STRUCT} of its properties, those of its
 * base types first, a {@code Collection} a {@code LIST}, an enumeration
 * type {@code VARCHAR} with the names of its members, and a type
 * definition the type of its underlying type;</li>
 * <li>{@code Edm.Stream} and the geographic and geometric types are no
 * columns, and no fields of a {@code STRUCT}; a complex type without
 * other properties is no column either;</li>
 * <li>any other type, also one of a later version of OData and a complex
 * type that contains itself, is {@code VARCHAR} with the JSON text of the
 * value, so that a new type does not make the table unusable.</li>
 * </ul>
 */
final class EdmTypes {

    private EdmTypes() {
    }

    /**
     * Returns the type of a property, or {@code null} if it is no column.
     *
     * @throws IllegalArgumentException if a base type of a complex type is
     *         missing or the types derive from each other
     */
    static DataType of(Property property, ServiceMetadata metadata) {
        return of(property.type(), property.precision(), property.scale(), metadata, new HashSet<>());
    }

    private static DataType of(String type, String precision, String scale, ServiceMetadata metadata,
            Set<String> visiting) {
        if (type.startsWith("Collection(") && type.endsWith(")")) {
            DataType element = of(type.substring("Collection(".length(), type.length() - 1), precision, scale,
                    metadata, visiting);
            return element == null ? null : DataType.listOf(element);
        }
        switch (type) {
            case "Edm.Boolean" -> {
                return DataType.BOOLEAN;
            }
            case "Edm.SByte" -> {
                return DataType.TINYINT;
            }
            case "Edm.Byte", "Edm.Int16" -> {
                return DataType.SMALLINT;
            }
            case "Edm.Int32" -> {
                return DataType.INTEGER;
            }
            case "Edm.Int64" -> {
                return DataType.BIGINT;
            }
            case "Edm.Single" -> {
                return DataType.FLOAT;
            }
            case "Edm.Double" -> {
                return DataType.DOUBLE;
            }
            case "Edm.Decimal" -> {
                return decimal(precision, scale);
            }
            case "Edm.Guid" -> {
                return DataType.UUID;
            }
            case "Edm.Binary" -> {
                return DataType.BLOB;
            }
            case "Edm.DateTime", "Edm.DateTimeOffset" -> {
                return DataType.TIMESTAMP;
            }
            case "Edm.Date" -> {
                return DataType.DATE;
            }
            case "Edm.Stream" -> {
                return null;
            }
            default -> {
                // Handled below.
            }
        }
        if (type.startsWith("Edm.Geography") || type.startsWith("Edm.Geometry")) {
            return null;
        }
        if (type.startsWith("Edm.")) {
            return DataType.VARCHAR;
        }
        TypeDefinition definition = metadata.typeDefinitions().get(type);
        if (definition != null) {
            return of(definition.underlyingType(), precision != null ? precision : definition.precision(),
                    scale != null ? scale : definition.scale(), metadata, visiting);
        }
        ComplexType complex = metadata.complexTypes().get(type);
        if (complex == null) {
            return DataType.VARCHAR;
        }
        if (!visiting.add(type)) {
            return DataType.VARCHAR;
        }
        List<DataType.Struct.Field> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Property p : metadata.properties(complex)) {
            DataType field = of(p.type(), p.precision(), p.scale(), metadata, visiting);
            if (field != null && names.add(p.name())) {
                fields.add(new DataType.Struct.Field(p.name(), field));
            }
        }
        visiting.remove(type);
        return fields.isEmpty() ? null : new DataType.Struct(fields);
    }

    /** Returns {@code DECIMAL} of the facets, or {@code DOUBLE} if they do not fit one. */
    static DataType decimal(String precision, String scale) {
        if (precision == null) {
            return DataType.DOUBLE;
        }
        try {
            int p = Integer.parseInt(precision.strip());
            int s = scale == null ? 0 : Integer.parseInt(scale.strip());
            if (p < 1 || p > DataType.MAX_DECIMAL_PRECISION || s < 0 || s > p) {
                return DataType.DOUBLE;
            }
            return DataType.decimal(p, s);
        } catch (NumberFormatException e) {
            return DataType.DOUBLE;
        }
    }
}
