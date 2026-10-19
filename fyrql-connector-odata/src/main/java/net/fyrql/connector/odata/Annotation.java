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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An annotation of an element of the metadata of a service, such as
 * {@code Org.OData.Capabilities.V1.FilterRestrictions}, given in the
 * element or in an {@code Annotations} element that targets it. The term
 * is qualified by the namespace of its vocabulary, also where the document
 * names it by an alias.
 *
 * @param term the qualified name of the term
 * @param qualifier the qualifier, or {@code null}
 * @param value the value, or {@code null} if the annotation has none,
 *        which for a term of type {@code Edm.Boolean} means true
 */
public record Annotation(String term, String qualifier, Annotation.Value value) {

    public Annotation {
        Objects.requireNonNull(term, "term");
    }

    /** The value of an annotation, a constant expression or a record or collection of them. */
    public sealed interface Value permits Constant, Record, Collection, Expression {
    }

    /**
     * A constant or a path: its kind as the element or attribute names it,
     * such as {@code Bool}, {@code String}, {@code Int}, {@code EnumMember},
     * {@code PropertyPath} or {@code Null}, and its text. The text of an
     * {@code EnumMember} is qualified as a term is.
     */
    public record Constant(String kind, String text) implements Value {
        public Constant {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
        }
    }

    /** A record: its type, or {@code null}, and its property values in order. */
    public record Record(String type, Map<String, Value> properties) implements Value {
        public Record {
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }
    }

    /** A collection of values. */
    public record Collection(List<Value> items) implements Value {
        public Collection {
            items = List.copyOf(items);
        }
    }

    /** A dynamic expression, such as {@code If} or {@code Apply}, which is not evaluated: its kind. */
    public record Expression(String kind) implements Value {
        public Expression {
            Objects.requireNonNull(kind, "kind");
        }
    }
}
