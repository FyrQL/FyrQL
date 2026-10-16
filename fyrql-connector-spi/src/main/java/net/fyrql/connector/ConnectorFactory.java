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

package net.fyrql.connector;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creates a connector from the options of a configuration, so that a
 * configuration file can name a source by its type. Implementations are
 * found with {@link java.util.ServiceLoader} under this interface; they read
 * the options with {@link ConfigOptions}.
 */
public interface ConnectorFactory {

    /** Returns the type the factory creates, in lower case, e.g. {@code csv} or {@code jdbc}. */
    String type();

    /**
     * Creates a connector.
     *
     * @param name the name under which the source will be registered
     * @param options the options of the source, with nested keys joined by
     *        dots, e.g. {@code tables.orders.url}
     * @param directory the directory against which relative paths in the
     *        options are resolved, such as that of the configuration file
     * @throws IllegalArgumentException if an option is missing, unknown or
     *         has a value the type cannot use; the message names the option
     */
    Connector create(String name, Map<String, String> options, Path directory);

    /**
     * Creates a connector whose relative paths are resolved against the
     * working directory.
     *
     * @see #create(String, Map, Path)
     */
    default Connector create(String name, Map<String, String> options) {
        return create(name, options, Path.of(""));
    }
}
