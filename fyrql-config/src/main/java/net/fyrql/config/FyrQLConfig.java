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

import net.fyrql.FyrQL;
import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
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
 * {@code codegen} ({@code off}, {@code auto} or {@code always}),
 * {@code codegenThreshold} and {@code pushdown} ({@code true} or
 * {@code false}), as the methods of {@link FyrQL.Builder} of the same
 * names take them.
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
        return open(EngineConfig.read(file), file.toString());
    }

    /**
     * Builds an engine from a configuration.
     *
     * @throws ConfigException if a setting is invalid, or a source cannot be
     *         created; the sources created until then are closed
     */
    public static FyrQL open(EngineConfig config) {
        return open(config, null);
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
        return prepare(EngineConfig.read(file), file.toString()).builder();
    }

    /**
     * Returns a builder with the sources and settings of a configuration.
     *
     * @throws ConfigException if a setting is invalid, or a source cannot be
     *         created; the sources created until then are closed
     */
    public static FyrQL.Builder builder(EngineConfig config) {
        return prepare(config, null).builder();
    }

    private static FyrQL open(EngineConfig config, String origin) {
        Prepared prepared = prepare(config, origin);
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

    private static Prepared prepare(EngineConfig config, String origin) {
        FyrQL.Builder builder = FyrQL.builder();
        try {
            settings(config, builder);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(prefix(origin) + e.getMessage(), e);
        }
        Map<String, ConnectorFactory> factories = Connectors.factories();
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
        o.get("codegen", "off, auto or always", v -> CodegenMode.valueOf(v.strip().toUpperCase(Locale.ROOT)))
                .ifPresent(b::codegen);
        o.get("codegenThreshold", "an integer", v -> Long.parseLong(v.strip())).ifPresent(b::codegenThreshold);
        o.bool("pushdown").ifPresent(b::pushdown);
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
