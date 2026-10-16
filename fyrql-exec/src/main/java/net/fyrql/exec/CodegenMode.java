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

package net.fyrql.exec;

import java.util.Locale;

/** When the executor compiles expressions and keys instead of interpreting them. */
public enum CodegenMode {

    /** Never; everything runs in the interpreter. */
    OFF,

    /**
     * When a compiler is on the class path and an operator has processed a
     * number of rows, the threshold; before that it interprets.
     */
    AUTO,

    /** Always, from the first batch on; a compiler must be on the class path. */
    ALWAYS;

    /** The system property that sets the default mode: {@code off}, {@code auto} or {@code always}. */
    public static final String PROPERTY = "fyrql.codegen";

    /**
     * Returns the mode of the system property {@value #PROPERTY}, or
     * {@link #AUTO} if it is not set.
     *
     * @throws IllegalArgumentException if the property has another value
     */
    public static CodegenMode fromSystemProperty() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The system property " + PROPERTY + " must be off, auto or always: "
                    + value, e);
        }
    }
}
