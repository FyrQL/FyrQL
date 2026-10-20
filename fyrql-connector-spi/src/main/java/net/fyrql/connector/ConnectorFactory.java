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

package net.fyrql.connector;

import java.nio.file.Path;
import java.util.List;
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
     * Returns the formats of files, in lower case, that a connector of this
     * type reads and writes without options; none by default. An engine
     * with the factory on its class path creates such a connector as the
     * source of the name {@link #type()} unless registered sources read and
     * write the formats, see {@code FyrQL.Builder.implicitSources}.
     */
    default List<String> fileFormats() {
        return List.of();
    }

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
