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

import net.fyrql.FyrQL;
import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.core.value.ZonedDates;
import net.fyrql.exec.CodegenMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds an engine from a configuration file:
 * <pre>{@code
 * try (FyrQL engine = FyrQLConfig.open(Path.of("fyrql.yaml"))) {
 *     engine.rows("SELECT ...");
 * }
 * }</pre>
 * See {@link EngineConfig} for the form of the file. The connectors of the
 * types it names must be on the class path. An application that registers
 * sources of its own, such as tables in memory, takes the
 * {@link #builder(Path) builder} instead and adds them.
 * <p>
 * The settings of the engine, under {@code engine}, are:
 * {@code defaultSource}, {@code defaultSchema}, {@code batchSize},
 * {@code memoryBudget} (bytes, such as {@code 512MB} or {@code 2GiB}),
 * {@code spill} ({@code true} or {@code false}), {@code spillDirectory} (a
 * path, relative to the file), {@code codegen} ({@code off}, {@code auto} or {@code always}),
 * {@code codegenThreshold}, {@code pushdown} ({@code true} or
 * {@code false}), {@code threads}, {@code preserveOrder},
 * {@code implicitSources} and {@code timeZone} (a name such as
 * {@code Europe/Berlin}), as the methods of {@link FyrQL.Builder} of the
 * same names take them.
 */
public final class FyrQLConfig {

    private FyrQLConfig() {
    }

    /**
     * Builds an engine from a configuration file.
     *
     * @throws ConfigException if the file is no valid configuration, or a
     *         source cannot be created; the sources created until then are closed
     */
    public static FyrQL open(Path file) {
        return open(EngineConfig.read(file), Connectors.factories(), file.toString());
    }

    /**
     * Builds an engine from a configuration.
     *
     * @throws ConfigException if a setting is invalid, or a source cannot be
     *         created; the sources created until then are closed
     */
    public static FyrQL open(EngineConfig config) {
        return open(config, Connectors.factories(), null);
    }

    /**
     * Builds an engine from a configuration with the given factories of
     * connectors, such as those {@link Connectors#factories(ClassLoader)}
     * finds with the class loader of a plugin, where the class loader of
     * the current thread does not see them.
     *
     * @throws ConfigException if a setting is invalid, no factory has the
     *         type of a source, or a source cannot be created; the sources
     *         created until then are closed
     */
    public static FyrQL open(EngineConfig config, Map<String, ConnectorFactory> factories) {
        return open(config, factories, null);
    }

    /**
     * Returns a builder with the sources and settings of a configuration
     * file, to which an application may add sources and settings before it
     * builds the engine.
     *
     * @throws ConfigException if the file is no valid configuration, or a
     *         source cannot be created; the sources created until then are closed
     */
    public static FyrQL.Builder builder(Path file) {
        return prepare(EngineConfig.read(file), Connectors.factories(), file.toString()).builder();
    }

    /**
     * Returns a builder with the sources and settings of a configuration.
     *
     * @throws ConfigException if a setting is invalid, or a source cannot be
     *         created; the sources created until then are closed
     */
    public static FyrQL.Builder builder(EngineConfig config) {
        return prepare(config, Connectors.factories(), null).builder();
    }

    /**
     * Returns a builder with the sources and settings of a configuration,
     * whose connectors come from the given factories.
     *
     * @throws ConfigException if a setting is invalid, no factory has the
     *         type of a source, or a source cannot be created; the sources
     *         created until then are closed
     * @see #open(EngineConfig, Map)
     */
    public static FyrQL.Builder builder(EngineConfig config, Map<String, ConnectorFactory> factories) {
        return prepare(config, factories, null).builder();
    }

    private static FyrQL open(EngineConfig config, Map<String, ConnectorFactory> factories, String origin) {
        Prepared prepared = prepare(config, factories, origin);
        try {
            return prepared.builder().build();
        } catch (RuntimeException e) {
            close(prepared.connectors(), e);
            throw new ConfigException(prefix(origin) + "Cannot build the engine: " + e.getMessage(), e);
        }
    }

    /** A builder and the connectors it holds, which are closed if the engine cannot be built. */
    private record Prepared(FyrQL.Builder builder, List<Connector> connectors) {
    }

    private static Prepared prepare(EngineConfig config, Map<String, ConnectorFactory> factories, String origin) {
        FyrQL.Builder builder = FyrQL.builder();
        try {
            settings(config, builder);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(prefix(origin) + e.getMessage(), e);
        }
        List<Connector> connectors = new ArrayList<>();
        for (SourceConfig source : config.sources()) {
            try {
                Connector c = Connectors.create(source, factories, config.directory());
                connectors.add(c);
                builder.register(c);
            } catch (RuntimeException e) {
                close(connectors, e);
                throw new ConfigException(prefix(origin) + e.getMessage(), e);
            }
        }
        return new Prepared(builder, connectors);
    }

    private static void settings(EngineConfig config, FyrQL.Builder b) {
        ConfigOptions o = new ConfigOptions("the engine", config.settings(), config.directory());
        Optional<String> defaultSource = o.string("defaultSource");
        o.string("defaultSchema").ifPresent(b::defaultSchema);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bytes("memoryBudget").ifPresent(b::memoryBudget);
        o.bool("spill").ifPresent(b::spill);
        o.path("spillDirectory").ifPresent(b::spillDirectory);
        o.get("codegen", "off, auto or always", v -> CodegenMode.valueOf(v.strip().toUpperCase(Locale.ROOT)))
                .ifPresent(b::codegen);
        o.get("codegenThreshold", "an integer", v -> Long.parseLong(v.strip())).ifPresent(b::codegenThreshold);
        o.bool("pushdown").ifPresent(b::pushdown);
        o.integer("threads").ifPresent(b::threads);
        o.bool("preserveOrder").ifPresent(b::preserveOrder);
        o.bool("implicitSources").ifPresent(b::implicitSources);
        o.get("timeZone", "the name of a time zone", v -> ZonedDates.zone(v.strip())).ifPresent(b::timeZone);
        o.rejectUnknown();
        if (defaultSource.isPresent()) {
            String name = defaultSource.get();
            List<String> names = new ArrayList<>();
            for (SourceConfig s : config.sources()) {
                names.add(s.name());
            }
            if (!names.contains(name)) {
                throw new IllegalArgumentException("The default source " + name + " of the engine is none of the"
                        + " sources " + String.join(", ", names));
            }
            b.defaultSource(name);
        }
    }

    private static void close(List<Connector> connectors, RuntimeException failure) {
        for (Connector c : connectors) {
            try {
                c.close();
            } catch (RuntimeException e) {
                failure.addSuppressed(e);
            }
        }
    }

    private static String prefix(String origin) {
        return origin == null ? "" : origin + ": ";
    }
}
