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

package net.fyrql.exec;

import java.util.Locale;

/**
 * When the executor compiles expressions and keys instead of interpreting them.
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
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
