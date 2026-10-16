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

import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * Finds the factories of connectors on the class path and creates sources
 * with them. A type is there when the module of its connector is, such as
 * {@code fyrql-connector-csv} for {@code csv}.
 */
public final class Connectors {

    private Connectors() {
    }

    /**
     * Returns the factories that {@link ServiceLoader} finds with the
     * class loader of the current thread.
     *
     * @return the factories by their types, in the order of the types
     * @throws ConfigException if two factories have the same type
     */
    public static Map<String, ConnectorFactory> factories() {
        return factories(ServiceLoader.load(ConnectorFactory.class));
    }

    /**
     * Returns the factories that {@link ServiceLoader} finds with a class loader.
     *
     * @throws ConfigException if two factories have the same type
     */
    public static Map<String, ConnectorFactory> factories(ClassLoader loader) {
        return factories(ServiceLoader.load(ConnectorFactory.class, loader));
    }

    private static Map<String, ConnectorFactory> factories(ServiceLoader<ConnectorFactory> loader) {
        Map<String, ConnectorFactory> factories = new TreeMap<>();
        for (ConnectorFactory f : loader) {
            ConnectorFactory other = factories.putIfAbsent(f.type().toLowerCase(Locale.ROOT), f);
            if (other != null) {
                throw new ConfigException("Two factories have the type " + f.type() + ": "
                        + other.getClass().getName() + " and " + f.getClass().getName());
            }
        }
        return factories;
    }

    /**
     * Creates the connector of a source with the factory of its type.
     *
     * @param directory the directory against which relative paths in the
     *        options are resolved
     * @throws ConfigException if no factory has the type, or the factory
     *         cannot create the connector; the message names the source
     */
    public static Connector create(SourceConfig source, Map<String, ConnectorFactory> factories, Path directory) {
        ConnectorFactory factory = factories.get(source.type().toLowerCase(Locale.ROOT));
        if (factory == null) {
            throw new ConfigException("The source " + source.name() + " has the type " + source.type()
                    + ", which no connector on the class path has; known are " + String.join(", ", factories.keySet()));
        }
        try {
            return factory.create(source.name(), source.options(), directory);
        } catch (RuntimeException e) {
            throw new ConfigException("Cannot create the source " + source.name() + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
    }
}
