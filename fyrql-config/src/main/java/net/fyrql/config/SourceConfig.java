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

package net.fyrql.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A source of a configuration: the name it is registered under, the type
 * of its connector, such as {@code csv} or {@code jdbc}, and its options,
 * flat, with nested keys joined by dots, such as
 * {@code tables.products.url}.
 */
public record SourceConfig(String name, String type, Map<String, String> options) {

    /**
     * @throws IllegalArgumentException if the name or the type is blank
     */
    public SourceConfig {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("A source needs a name");
        }
        if (Objects.requireNonNull(type, "type").isBlank()) {
            throw new IllegalArgumentException("The source " + name + " needs a type");
        }
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /** Names the source, its type and the keys of its options, but not their values, which may be secrets. */
    @Override
    public String toString() {
        return "SourceConfig[name=" + name + ", type=" + type + ", options=" + options.keySet() + "]";
    }
}
