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

/**
 * A configuration that cannot be read or used: the file does not parse, a
 * key is unknown or misplaced, an option is missing or invalid, or a source
 * cannot be created. The message names the file, if there is one, and the
 * line or the source and option; it never holds the value of an option
 * that may be a secret.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
        super(message);
    }

    ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
