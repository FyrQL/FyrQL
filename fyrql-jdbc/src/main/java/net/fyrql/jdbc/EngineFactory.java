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
 * Builds the engine of a connection from its settings: from the
 * configuration file the settings name, whose settings of the engine they
 * overlay, or, without a file, with a source without tables for each of
 * the types {@code csv}, {@code json}, {@code parquet} and {@code memory}
 * whose connector is there, so that table functions such as
 * {@code read_csv} and file names in {@code FROM} work, {@code COPY}
 * writes files and {@code CREATE TABLE memory.t AS ...} keeps a table for
 * as long as the connection. The factories of the connectors are found
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
     * Builds an engine.
     *
     * @throws SQLException {@code 08001} if the configuration cannot be
     *         read, a setting is invalid or a source cannot be created
     */
    static FyrQL create(UrlSettings settings, ClassLoader loader) throws SQLException {
        String origin = null;
        try {
            Map<String, ConnectorFactory> factories = Connectors.factories(loader);
            EngineConfig config;
            Optional<Path> file = settings.config();
            if (file.isPresent()) {
                origin = file.get().toString();
                EngineConfig read = EngineConfig.read(file.get());
                Map<String, String> merged = new LinkedHashMap<>(read.settings());
                merged.putAll(settings.engineSettings());
                config = new EngineConfig(read.sources(), merged, read.directory());
            } else {
                List<SourceConfig> sources = new ArrayList<>();
                for (String type : IMPLICIT_SOURCES) {
                    if (factories.containsKey(type)) {
                        sources.add(new SourceConfig(type, type, Map.of()));
                    }
                }
                config = new EngineConfig(sources, settings.engineSettings(), Path.of("").toAbsolutePath());
            }
            return FyrQLConfig.open(config, factories);
        } catch (ConfigException | IllegalArgumentException | IllegalStateException e) {
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            if (origin != null && !message.contains(origin)) {
                message = origin + ": " + message;
            }
            throw SqlStates.connectionFailure(message, e);
        }
    }
}
