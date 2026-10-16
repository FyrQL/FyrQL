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
