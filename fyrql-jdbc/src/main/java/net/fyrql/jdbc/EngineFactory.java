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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.config.ConfigException;
import net.fyrql.config.Connectors;
import net.fyrql.config.EngineConfig;
import net.fyrql.config.FyrQLConfig;
import net.fyrql.config.SourceConfig;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the engine of a connection from its settings, in two steps: the
 * {@link #config configuration}, which decides whether connections may
 * share an engine, and the {@link #open engine} itself. The configuration
 * comes from the file the settings name, whose settings of the engine they
 * overlay, or, without a file, has a source without tables for each of
 * the types {@code csv}, {@code json}, {@code parquet}, {@code iceberg} and
 * {@code memory} whose connector is there, so that table functions such as
 * {@code read_csv} and file names in {@code FROM} work, {@code COPY}
 * writes files and {@code CREATE TABLE memory.t AS ...} keeps a table for
 * as long as the engine. The factories of the connectors are found
 * with a given class loader, that of the driver, not with the one of the
 * current thread: a tool that loads the driver in a class loader of its
 * own, as DBeaver does, leaves the class loader of the thread without
 * them.
 */
final class EngineFactory {

    /** The types of the sources of an engine without a configuration file, each a source of the same name. */
    static final List<String> IMPLICIT_SOURCES = List.of("csv", "json", "parquet", "iceberg", "memory");

    private EngineFactory() {
    }

    /**
     * Returns the factories of the connectors that a class loader finds.
     *
     * @throws SQLException {@code 08001} if a factory cannot be loaded
     */
    static Map<String, ConnectorFactory> factories(ClassLoader loader) throws SQLException {
        try {
            return Connectors.factories(loader);
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            throw failure(Optional.empty(), e);
        }
    }

    /**
     * Returns the configuration of the engine of a connection, read anew
     * from its file, if it names one. Two connections whose configurations
     * are equal may share an engine: equal sources, views, settings and
     * directory, whatever the file looked like.
     *
     * @throws SQLException {@code 08001} if the configuration cannot be read
     */
    static EngineConfig config(UrlSettings settings, Map<String, ConnectorFactory> factories)
            throws SQLException {
        Optional<Path> file = settings.config();
        try {
            if (file.isPresent()) {
                EngineConfig read = EngineConfig.read(file.get());
                Map<String, String> merged = new LinkedHashMap<>(read.settings());
                merged.putAll(settings.engineSettings());
                return new EngineConfig(read.sources(), read.views(), merged, Map.of(), Map.of(),
                        read.directory().normalize());
            }
            List<SourceConfig> sources = new ArrayList<>();
            for (String type : IMPLICIT_SOURCES) {
                if (factories.containsKey(type)) {
                    sources.add(new SourceConfig(type, type, Map.of()));
                }
            }
            return new EngineConfig(sources, settings.engineSettings(), Path.of("").toAbsolutePath());
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            throw failure(file, e);
        }
    }

    /**
     * Builds an engine.
     *
     * @param file the configuration file the configuration comes from, for messages
     * @throws SQLException {@code 08001} if a setting is invalid or a source cannot be created
     */
    static FyrQL open(EngineConfig config, Map<String, ConnectorFactory> factories, Optional<Path> file)
            throws SQLException {
        try {
            return FyrQLConfig.open(config, factories);
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            throw failure(file, e);
        }
    }

    /** Returns the failure of a connection, with the configuration file in front if its message lacks it. */
    private static SQLException failure(Optional<Path> file, RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        if (file.isPresent() && !message.contains(file.get().toString())) {
            message = file.get() + ": " + message;
        }
        return SqlStates.connectionFailure(message, e);
    }
}
