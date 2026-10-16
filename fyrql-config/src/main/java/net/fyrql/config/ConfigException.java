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
