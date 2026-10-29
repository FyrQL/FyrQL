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

import net.fyrql.connector.ConfigOptions;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
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
 * <p>
 * The keys fall into three groups: those of the driver, {@code config},
 * {@code shared} and {@code closeDelay}, which choose the engine and how
 * long it lives; those of the session, {@code timeZone},
 * {@code defaultSource}, {@code defaultSchema} and {@code queryTimeout},
 * which each connection sets on its own session; and the settings of the
 * engine, all others, which connections that share an engine have in
 * common.
 *
 * @param values the settings by the names of their {@link #KEYS}, in the order of the keys
 * @param unknownProperties the names of the properties that are no key
 */
record UrlSettings(Map<String, String> values, List<String> unknownProperties) {

    /** The prefix of every URL of the driver. */
    static final String PREFIX = "jdbc:fyrql:";

    /** The key of the configuration file. */
    static final String CONFIG = "config";

    /** The key of whether connections with the same settings share an engine. */
    static final String SHARED = "shared";

    /** The key of how long a shared engine stays open after its last connection is closed. */
    static final String CLOSE_DELAY = "closeDelay";

    /** The key of the time zone of the session. */
    static final String TIME_ZONE = "timeZone";

    /** The key of the default source of the session. */
    static final String DEFAULT_SOURCE = "defaultSource";

    /** The key of the default schema of the session. */
    static final String DEFAULT_SCHEMA = "defaultSchema";

    /** The key of the timeout of the session. */
    static final String QUERY_TIMEOUT = "queryTimeout";

    /** The keys that each connection sets on its own session instead of on the engine. */
    static final List<String> SESSION_KEYS = List.of(TIME_ZONE, DEFAULT_SOURCE, DEFAULT_SCHEMA, QUERY_TIMEOUT);

    /** The delay of {@link #closeDelay()} for an engine that is never closed. */
    static final Duration NEVER = Duration.ofSeconds(-1);

    /** A key of the settings, with its description and the values it takes, if they are few. */
    record Key(String name, String description, List<String> choices) {
    }

    /**
     * The keys, {@code config} first, then the settings of the engine of
     * {@code fyrql-config} and the keys of the session, then those of the
     * driver.
     */
    static final List<Key> KEYS = List.of(
            new Key(CONFIG, "The configuration file of the sources and the engine, YAML or JSON; without it the"
                    + " engine has the sources csv, json, parquet, iceberg and memory without tables, for table"
                    + " functions, file names in FROM and the tables that SQL creates", List.of()),
            new Key("defaultSource", "The source in which names of fewer than three parts are looked up first",
                    List.of()),
            new Key("defaultSchema", "The schema of the default source in which one-part names are looked up"
                    + " first; main by default", List.of()),
            new Key("batchSize", "The number of rows per batch", List.of()),
            new Key("memoryBudget", "The bytes a query may hold in joins, aggregations and sorts, such as 512MB",
                    List.of()),
            new Key("spill", "Whether aggregations, sorts, windows and joins spill to disk above the memory budget",
                    List.of("true", "false")),
            new Key("spillDirectory", "The directory under which the engine spills; the temporary directory of"
                    + " the JVM by default", List.of()),
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
                    + " time zone is shown; the one of the JVM by default", List.of()),
            new Key("fileAccess", "What statements may do with the files they name: none, read or readWrite;"
                    + " readWrite by default", List.of("none", "read", "readWrite")),
            new Key("allowedLocations", "The directories and prefixes such as s3://lake/ under which statements"
                    + " may read and write files, separated by commas; anywhere by default", List.of()),
            new Key("queryTimeout", "How long a query may run without a query timeout of its statement, such as"
                    + " 30s or 5m; no limit by default", List.of()),
            new Key("maxQueryTimeout", "How long any query may run, also with a longer query timeout of its"
                    + " statement, such as 30s or 5m; no limit by default", List.of()),
            new Key(SHARED, "Whether connections with the same configuration and settings of the engine share one"
                    + " engine, and so its tables in memory; true by default", List.of("true", "false")),
            new Key(CLOSE_DELAY, "How long a shared engine stays open after its last connection is closed, such as"
                    + " 30s or 5m, or -1 for until the JVM ends; 0 by default", List.of()));

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

    /**
     * Returns the settings of the engine, as the configuration file names
     * them: all but the keys of the driver and of the session.
     */
    Map<String, String> engineSettings() {
        Map<String, String> settings = new LinkedHashMap<>(values);
        settings.keySet().removeAll(List.of(CONFIG, SHARED, CLOSE_DELAY));
        settings.keySet().removeAll(SESSION_KEYS);
        return settings;
    }

    /** Returns the settings that the connection sets on its session, by the names of their keys. */
    Map<String, String> sessionSettings() {
        Map<String, String> settings = new LinkedHashMap<>(values);
        settings.keySet().retainAll(SESSION_KEYS);
        return settings;
    }

    /**
     * Returns whether connections with the same settings share an engine.
     *
     * @throws SQLException {@code 08001} if the value is neither {@code true} nor {@code false}
     */
    boolean shared() throws SQLException {
        String value = values.get(SHARED);
        if (value == null || value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw SqlStates.connectionFailure("The key " + SHARED + " has the value " + value + ", which is neither"
                + " true nor false", null);
    }

    /**
     * Returns how long a shared engine stays open after its last connection
     * is closed: {@link Duration#ZERO} by default, {@link #NEVER} for
     * {@code -1}.
     *
     * @throws SQLException {@code 08001} if the value is no duration
     */
    Duration closeDelay() throws SQLException {
        String value = values.get(CLOSE_DELAY);
        if (value == null) {
            return Duration.ZERO;
        }
        if (value.equals("-1")) {
            return NEVER;
        }
        try {
            return new ConfigOptions("the connection", Map.of(CLOSE_DELAY, value), Path.of(""))
                    .timeLimit(CLOSE_DELAY).orElseThrow();
        } catch (IllegalArgumentException e) {
            throw SqlStates.connectionFailure("The key " + CLOSE_DELAY + " has the value " + value + ", which is no"
                    + " duration such as 30s or 5m, nor 0 or -1", e);
        }
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
