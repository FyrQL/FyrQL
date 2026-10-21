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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The settings of a connection, from a URL of the form
 * {@code jdbc:fyrql:} or {@code jdbc:fyrql:?config=fyrql.yaml&codegen=off}
 * and from the properties of the connection, which win over the URL. The
 * prefix is matched without regard to case, as are the keys. Values in
 * the URL are decoded from {@code %xx}; a {@code +} stays a {@code +}. A
 * blank value counts as not set, since tools send a property that a user
 * left empty. The properties {@code user} and {@code password}, which
 * tools always send, are ignored; other unknown properties are collected
 * for a warning, while an unknown key in the URL is an error.
 *
 * @param values the settings by the names of their {@link #KEYS}, in the order of the keys
 * @param unknownProperties the names of the properties that are no key
 */
record UrlSettings(Map<String, String> values, List<String> unknownProperties) {

    /** The prefix of every URL of the driver. */
    static final String PREFIX = "jdbc:fyrql:";

    /** The key of the configuration file. */
    static final String CONFIG = "config";

    /** A key of the settings, with its description and the values it takes, if they are few. */
    record Key(String name, String description, List<String> choices) {
    }

    /** The keys, {@code config} first and then the settings of the engine of {@code fyrql-config}. */
    static final List<Key> KEYS = List.of(
            new Key(CONFIG, "The configuration file of the sources and the engine, YAML or JSON; without it the"
                    + " engine has the sources csv, json and parquet without tables, for table functions and file"
                    + " names in FROM", List.of()),
            new Key("defaultSource", "The source in which names of fewer than three parts are looked up first",
                    List.of()),
            new Key("defaultSchema", "The schema of the default source in which one-part names are looked up"
                    + " first; main by default", List.of()),
            new Key("batchSize", "The number of rows per batch", List.of()),
            new Key("memoryBudget", "The bytes a query may hold in joins, aggregations and sorts, such as 512MB",
                    List.of()),
            new Key("codegen", "When expressions are compiled: off, auto or always", List.of("off", "auto",
                    "always")),
            new Key("codegenThreshold", "The rows after which an operator compiles in the mode auto", List.of()),
            new Key("pushdown", "Whether sources take over filters, aggregations, sorts and limits", List.of("true",
                    "false")),
            new Key("threads", "The most threads on which one query runs at a time; the number of processors by"
                    + " default", List.of()),
            new Key("preserveOrder", "Whether rows that no ORDER BY orders come in the order of their sources",
                    List.of("true", "false")),
            new Key("timeZone", "The time zone of the session, such as Europe/Berlin, in which a timestamp with"
                    + " time zone is shown; the one of the JVM by default", List.of()));

    private static final List<String> IGNORED = List.of("user", "password");

    UrlSettings {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        unknownProperties = List.copyOf(unknownProperties);
    }

    /** Tells whether a URL is one of the driver. */
    static boolean accepts(String url) {
        return url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * Reads the settings of a URL of the driver and of the properties.
     *
     * @throws SQLException {@code 08001} if the URL has no valid form or an unknown key
     */
    static UrlSettings parse(String url, Properties info) throws SQLException {
        Map<String, String> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : pairs(url)) {
            String key = canonical(e.getKey());
            if (key == null) {
                throw SqlStates.connectionFailure("The URL has the unknown key " + e.getKey() + "; known are "
                        + names(), null);
            }
            if (found.put(key, e.getValue()) != null) {
                throw SqlStates.connectionFailure("The URL sets the key " + key + " twice", null);
            }
        }
        List<String> unknown = new ArrayList<>();
        if (info != null) {
            for (String name : info.stringPropertyNames()) {
                String key = canonical(name);
                if (key != null) {
                    found.put(key, info.getProperty(name));
                } else if (!IGNORED.contains(name.toLowerCase(Locale.ROOT))) {
                    unknown.add(name);
                }
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Key k : KEYS) {
            String v = found.get(k.name());
            if (v != null && !v.isBlank()) {
                values.put(k.name(), v.strip());
            }
        }
        Collections.sort(unknown);
        return new UrlSettings(values, unknown);
    }

    /**
     * Reads the settings of a URL and of the properties as far as they are
     * valid, for {@link java.sql.Driver#getPropertyInfo}: a URL that does
     * not parse counts as one without settings.
     */
    static UrlSettings lenient(String url, Properties info) {
        try {
            return parse(url, info);
        } catch (SQLException e) {
            try {
                return parse(PREFIX, info);
            } catch (SQLException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    /** Returns the configuration file, if the settings name one. */
    Optional<Path> config() throws SQLException {
        String file = values.get(CONFIG);
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(file));
        } catch (InvalidPathException e) {
            throw SqlStates.connectionFailure("The configuration file " + file + " is no valid path: "
                    + e.getMessage(), e);
        }
    }

    /** Returns the settings of the engine, all but {@code config}, as the configuration file names them. */
    Map<String, String> engineSettings() {
        Map<String, String> settings = new LinkedHashMap<>(values);
        settings.remove(CONFIG);
        return settings;
    }

    /** Returns the decoded pairs of the query of a URL, in their order. */
    private static List<Map.Entry<String, String>> pairs(String url) throws SQLException {
        String rest = url.substring(PREFIX.length());
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (rest.isEmpty()) {
            return pairs;
        }
        if (rest.charAt(0) != '?') {
            throw SqlStates.connectionFailure("The URL " + url + " is not of the form jdbc:fyrql: or"
                    + " jdbc:fyrql:?config=<file>&<key>=<value>...", null);
        }
        for (String part : rest.substring(1).split("&", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw SqlStates.connectionFailure("The URL has the part " + part + ", which is not of the form"
                        + " <key>=<value>", null);
            }
            pairs.add(Map.entry(decode(part.substring(0, eq)), decode(part.substring(eq + 1))));
        }
        return pairs;
    }

    private static String decode(String text) throws SQLException {
        try {
            return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw SqlStates.connectionFailure("The URL has the part " + text + ", which is not validly encoded: "
                    + e.getMessage(), e);
        }
    }

    /** Returns the name of the key a name matches without regard to case, or {@code null}. */
    private static String canonical(String name) {
        for (Key k : KEYS) {
            if (k.name().equalsIgnoreCase(name)) {
                return k.name();
            }
        }
        return null;
    }

    private static String names() {
        List<String> names = new ArrayList<>();
        for (Key k : KEYS) {
            names.add(k.name());
        }
        return String.join(", ", names);
    }
}
