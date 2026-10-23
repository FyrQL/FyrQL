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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sources and settings of an engine, as a configuration file holds
 * them:
 * <pre>{@code
 * sources:
 *   - name: shop
 *     type: jdbc
 *     url: jdbc:postgresql://localhost/shop
 *     user: shop
 *     password: ${env:SHOP_PASSWORD}
 *   - name: files
 *     type: csv
 *     directory: data
 *   - name: api
 *     type: http
 *     tables:
 *       products: { url: "https://example.com/products", pointer: /data }
 * engine:
 *   defaultSource: shop
 *   pushdown: true
 * server:
 *   port: 32010
 * }</pre>
 * Each source has a name, a type and the options of its type, which are
 * read flat: a nested key becomes the keys on its path joined by dots, such
 * as {@code tables.products.url}, and a list of plain values one value with
 * commas between them. Every value is taken as the text it is written as,
 * so that {@code 0123} stays {@code 0123}. A value may name an environment
 * variable as {@code ${env:NAME}} or a system property as
 * {@code ${sys:name}}, either with a default as {@code ${env:NAME:-default}},
 * so that a secret need not be in the file; <code>$${</code> stands for the
 * text <code>${</code>. The settings of the engine are those of
 * {@link FyrQLConfig#builder(EngineConfig)}. The section {@code server}
 * holds the settings of the Flight SQL server of the module
 * {@code fyrql-flight-sql}, read flat as the options of a source; the
 * engine does not read them.
 * <p>
 * A file is YAML, or JSON if its name ends in {@code .json}. Relative paths
 * in the options are resolved against the directory of the file.
 *
 * @param sources the sources in the order of the file
 * @param settings the settings of the engine, flat as the options of a source
 * @param server the settings of a server for the engine, flat as the options of a source
 * @param directory the directory against which relative paths are resolved
 */
public record EngineConfig(List<SourceConfig> sources, Map<String, String> settings, Map<String, String> server,
        Path directory) {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\$\\{|\\$\\{(?:([a-z]+):([^}]*)})?");
    private static final String DEFAULT = ":-";

    public EngineConfig {
        sources = List.copyOf(sources);
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        server = Collections.unmodifiableMap(new LinkedHashMap<>(server));
        Objects.requireNonNull(directory, "directory");
    }

    /** A configuration without settings of a server. */
    public EngineConfig(List<SourceConfig> sources, Map<String, String> settings, Path directory) {
        this(sources, settings, Map.of(), directory);
    }

    /** Returns the configuration with a setting of the engine added or replaced. */
    public EngineConfig withSetting(String key, String value) {
        Map<String, String> with = new LinkedHashMap<>(settings);
        with.put(key, value);
        return new EngineConfig(sources, with, server, directory);
    }

    /**
     * Names the sources as {@link SourceConfig#toString()} does, the
     * settings of the engine, and the keys of the settings of the server,
     * but not their values, which hold the passwords of its users and its
     * token.
     */
    @Override
    public String toString() {
        return "EngineConfig[sources=" + sources + ", settings=" + settings + ", server=" + server.keySet()
                + ", directory=" + directory + "]";
    }

    /**
     * Reads a configuration file, as YAML, or as JSON if its name ends in
     * {@code .json}.
     *
     * @throws ConfigException if the file cannot be read, does not parse
     *         or is not a configuration; the message names the file and
     *         the line
     */
    public static EngineConfig read(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Cannot read the configuration " + file + ": " + e, e);
        }
        boolean json = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
        Path directory = file.toAbsolutePath().getParent();
        return new Reader(file.toString(), System::getenv).read(json ? new JsonFactory() : new YAMLFactory(), text,
                directory);
    }

    /**
     * Reads a configuration from YAML text.
     *
     * @param directory the directory against which relative paths are resolved
     * @throws ConfigException if the text does not parse or is not a configuration
     */
    public static EngineConfig parse(String yaml, Path directory) {
        return parse(yaml, directory, System::getenv);
    }

    /** Reads a configuration from YAML text with the given environment variables. */
    static EngineConfig parse(String yaml, Path directory, Function<String, String> environment) {
        return new Reader(null, environment).read(new YAMLFactory(), yaml, directory);
    }

    /** A value of the file and the line it starts on: text, {@code null}, a map or a list of nodes. */
    private record Node(Object value, int line) {
    }

    /** Turns the text of a file into a configuration. */
    private static final class Reader {
        private final String origin;
        private final Function<String, String> environment;

        Reader(String origin, Function<String, String> environment) {
            this.origin = origin;
            this.environment = environment;
        }

        EngineConfig read(JsonFactory factory, String text, Path directory) {
            Node root;
            try (JsonParser p = factory.createParser(text)) {
                if (p.nextToken() == null) {
                    throw failure(0, "the configuration is empty");
                }
                root = node(p);
                if (p.nextToken() != null) {
                    throw failure(p.currentTokenLocation().getLineNr(), "the configuration holds more than one"
                            + " document");
                }
            } catch (JsonProcessingException e) {
                int line = e.getLocation() == null ? 0 : e.getLocation().getLineNr();
                throw new ConfigException(where(line) + "the configuration does not parse: "
                        + e.getOriginalMessage().strip(), e);
            } catch (IOException e) {
                throw new ConfigException(where(0) + "the configuration cannot be read: " + e, e);
            }
            if (!(root.value() instanceof Map<?, ?> top)) {
                throw failure(root.line(), "the configuration must be a mapping with the keys sources, engine and"
                        + " server");
            }
            List<SourceConfig> sources = new ArrayList<>();
            Map<String, String> settings = new LinkedHashMap<>();
            Map<String, String> server = new LinkedHashMap<>();
            for (Map.Entry<String, Node> e : entries(top)) {
                Node n = e.getValue();
                switch (e.getKey()) {
                    case "sources" -> sources = sources(n);
                    case "engine" -> section(n, settings, "engine", "the engine");
                    case "server" -> section(n, server, "server", "the server");
                    default -> throw failure(n.line(), "the key " + e.getKey() + " is unknown; known are sources,"
                            + " engine and server");
                }
            }
            return new EngineConfig(sources, settings, server, directory);
        }

        /** Adds the settings of a section that is a mapping of settings, or empty. */
        private void section(Node n, Map<String, String> settings, String key, String owner) {
            if (n.value() != null && !(n.value() instanceof Map)) {
                throw failure(n.line(), key + " must be a mapping of settings");
            }
            if (n.value() != null) {
                flatten("", n, settings, owner);
            }
        }

        private List<SourceConfig> sources(Node n) {
            if (n.value() == null) {
                return List.of();
            }
            if (!(n.value() instanceof List<?> list)) {
                throw failure(n.line(), "sources must be a list of sources, each with a name and a type");
            }
            List<SourceConfig> sources = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Object o : list) {
                Node s = (Node) o;
                if (!(s.value() instanceof Map<?, ?> map)) {
                    throw failure(s.line(), "a source must be a mapping with a name, a type and its options");
                }
                String name = null;
                String type = null;
                Map<String, Node> rest = new LinkedHashMap<>();
                for (Map.Entry<String, Node> e : entries(map)) {
                    switch (e.getKey()) {
                        case "name" -> name = text(e.getValue(), "the name of a source");
                        case "type" -> type = text(e.getValue(), "the type of a source");
                        default -> rest.put(e.getKey(), e.getValue());
                    }
                }
                if (name == null || name.isBlank()) {
                    throw failure(s.line(), "a source has no name");
                }
                if (type == null || type.isBlank()) {
                    throw failure(s.line(), "the source " + name + " has no type");
                }
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw failure(s.line(), "there are two sources named " + name);
                }
                Map<String, String> options = new LinkedHashMap<>();
                flatten("", new Node(rest, s.line()), options, "the source " + name);
                sources.add(new SourceConfig(name, type, options));
            }
            return sources;
        }

        /** Adds the plain values of a node to the options, under the keys of their paths. */
        private void flatten(String key, Node n, Map<String, String> options, String owner) {
            Object v = n.value();
            if (v instanceof Map<?, ?> map) {
                for (Map.Entry<String, Node> e : entries(map)) {
                    flatten(key.isEmpty() ? e.getKey() : key + "." + e.getKey(), e.getValue(), options, owner);
                }
                return;
            }
            String value;
            if (v instanceof List<?> list) {
                List<String> values = new ArrayList<>();
                for (Object o : list) {
                    Node element = (Node) o;
                    if (!(element.value() instanceof String s)) {
                        throw failure(element.line(), "the option " + key + " of " + owner + " is a list, which may"
                                + " hold only plain values");
                    }
                    values.add(s);
                }
                value = String.join(",", values);
            } else if (v == null) {
                throw failure(n.line(), "the option " + key + " of " + owner + " has no value; a value such as"
                        + " null or ~ that is meant as text needs quotes");
            } else {
                value = (String) v;
            }
            if (options.putIfAbsent(key, substitute(value, key, owner, n.line())) != null) {
                throw failure(n.line(), "the option " + key + " of " + owner + " is set twice");
            }
        }

        /** Replaces the variables in a value. */
        private String substitute(String value, String key, String owner, int line) {
            Matcher m = VARIABLE.matcher(value);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String replacement;
                if (m.group().equals("$${")) {
                    replacement = "${";
                } else if (m.group(1) == null) {
                    throw failure(line, "the option " + key + " of " + owner + " has a ${ that is not of the form"
                            + " ${env:NAME} or ${sys:name}; $${ stands for the text ${");
                } else {
                    String body = m.group(2);
                    int d = body.indexOf(DEFAULT);
                    String name = d < 0 ? body : body.substring(0, d);
                    String fallback = d < 0 ? null : body.substring(d + DEFAULT.length());
                    String found = switch (m.group(1)) {
                        case "env" -> environment.apply(name);
                        case "sys" -> System.getProperty(name);
                        default -> throw failure(line, "the option " + key + " of " + owner + " names a variable"
                                + " of the unknown kind " + m.group(1) + "; known are env and sys");
                    };
                    if (found == null && fallback == null) {
                        throw failure(line, "the option " + key + " of " + owner + " names the "
                                + (m.group(1).equals("env") ? "environment variable " : "system property ") + name
                                + ", which is not set");
                    }
                    replacement = found != null ? found : fallback;
                }
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }

        private String text(Node n, String what) {
            if (!(n.value() instanceof String s)) {
                throw failure(n.line(), what + " must be a plain value");
            }
            return s;
        }

        private Node node(JsonParser p) throws IOException {
            int line = p.currentTokenLocation().getLineNr();
            JsonToken t = p.currentToken();
            if (t == JsonToken.START_OBJECT) {
                Map<String, Node> map = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String key = p.currentName();
                    int keyLine = p.currentTokenLocation().getLineNr();
                    p.nextToken();
                    Node value = node(p);
                    if (map.putIfAbsent(key, new Node(value.value(), keyLine)) != null) {
                        throw failure(keyLine, "the key " + key + " appears twice");
                    }
                }
                return new Node(map, line);
            }
            if (t == JsonToken.START_ARRAY) {
                List<Node> list = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    list.add(node(p));
                }
                return new Node(list, line);
            }
            return new Node(t == JsonToken.VALUE_NULL ? null : p.getText(), line);
        }

        @SuppressWarnings("unchecked")
        private static Iterable<Map.Entry<String, Node>> entries(Map<?, ?> map) {
            return ((Map<String, Node>) map).entrySet();
        }

        private ConfigException failure(int line, String message) {
            return new ConfigException(where(line) + message);
        }

        private String where(int line) {
            String place = line > 0 ? "line " + line : "";
            if (origin != null) {
                place = place.isEmpty() ? origin : origin + ", " + place;
            }
            return place.isEmpty() ? "" : place + ": ";
        }
    }
}
