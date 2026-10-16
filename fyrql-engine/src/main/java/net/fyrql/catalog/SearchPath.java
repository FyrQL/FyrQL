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

package net.fyrql.catalog;

import net.fyrql.core.plan.TableRef;

import java.util.Objects;

/**
 * Where names of fewer than three parts are looked up first: a source and a
 * schema in it. Without a default source, such names are looked up in all
 * sources and must be unique.
 *
 * @param defaultSource the default source, or {@code null} for none
 * @param defaultSchema the default schema, {@code main} unless set
 */
public record SearchPath(String defaultSource, String defaultSchema) {

    public SearchPath {
        Objects.requireNonNull(defaultSchema, "defaultSchema");
    }

    /** A search path without a default source and with the schema {@code main}. */
    public static SearchPath none() {
        return new SearchPath(null, TableRef.MAIN);
    }
}
