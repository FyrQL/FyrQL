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

package net.fyrql.pgwire;

import net.fyrql.SqlStates;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The parameters of PostgreSQL of a connection, as {@code SET},
 * {@code RESET} and {@code SHOW} see them, with the values PostgreSQL
 * starts with. The server takes the values that only describe the client,
 * such as {@code application_name} and {@code extra_float_digits}, and
 * keeps them; it refuses values it does not follow, such as a
 * {@code DateStyle} other than ISO. {@code TimeZone},
 * {@code statement_timeout} and {@code search_path} belong to the session
 * of the engine, see {@link Connection}. A name with a dot, as an
 * extension or an application uses it, takes any value.
 */
final class ServerSettings {

    /** A value the server does not take, with the SQLSTATE of PostgreSQL. */
    static final class InvalidSetting extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String state;

        InvalidSetting(String message, String state) {
            super(message);
            this.state = state;
        }

        String state() {
            return state;
        }
    }

    static final String UNDEFINED_OBJECT = "42704";
    static final String INVALID_PARAMETER_VALUE = "22023";
    static final String CANT_CHANGE_RUNTIME_PARAM = "55P02";

    static final String TIME_ZONE = "TimeZone";
    static final String STATEMENT_TIMEOUT = "statement_timeout";
    static final String SEARCH_PATH = "search_path";
    static final String CLIENT_ENCODING = "client_encoding";
    static final String APPLICATION_NAME = "application_name";

    /** The parameters the server tells a client about at the start and after a change, as PostgreSQL does. */
    static final List<String> REPORTED = List.of(APPLICATION_NAME, CLIENT_ENCODING, "DateStyle",
            "default_transaction_read_only", "in_hot_standby", "integer_datetimes", "IntervalStyle", "is_superuser",
            "scram_iterations", "server_encoding", "server_version", "session_authorization",
            "standard_conforming_strings", TIME_ZONE);

    /** The parameters that a client may read but not set. */
    private static final Set<String> READ_ONLY = Set.of("server_version", "server_version_num", "server_encoding",
            "integer_datetimes", "is_superuser", "session_authorization", "in_hot_standby", "max_identifier_length",
            "max_index_keys", "block_size", "lc_collate", "lc_ctype", "scram_iterations", "max_connections");

    /** The names in the case of PostgreSQL, by their names in lower case. */
    private final Map<String, String> names = new LinkedHashMap<>();
    /** The values PostgreSQL starts with, by name. */
    private final Map<String, String> defaults = new LinkedHashMap<>();
    /** The values now, by name. */
    private final Map<String, String> values = new LinkedHashMap<>();
    private Charset charset = StandardCharsets.UTF_8;

    /**
     * @param version the version of PostgreSQL the server tells, such as {@code 18.0}
     * @param versionNumber the same as a number, such as {@code 180000}
     * @param user the user of the connection
     * @param maxConnections the most connections of the server
     */
    ServerSettings(String version, int versionNumber, String user, int maxConnections) {
        define(APPLICATION_NAME, "");
        define(CLIENT_ENCODING, "UTF8");
        define("DateStyle", "ISO, MDY");
        define("IntervalStyle", "postgres");
        define("extra_float_digits", "1");
        define("client_min_messages", "notice");
        define("default_transaction_isolation", "read committed");
        define("default_transaction_read_only", "off");
        define("default_transaction_deferrable", "off");
        define("transaction_isolation", "read committed");
        define("transaction_read_only", "off");
        define("transaction_deferrable", "off");
        define("standard_conforming_strings", "on");
        define("bytea_output", "hex");
        define("lock_timeout", "0");
        define("idle_in_transaction_session_timeout", "0");
        define("idle_session_timeout", "0");
        define("row_security", "on");
        define("jit", "off");
        define("work_mem", "4MB");
        define("synchronous_commit", "on");
        define("default_tablespace", "");
        define("lc_messages", "C");
        define("lc_monetary", "C");
        define("lc_numeric", "C");
        define("lc_time", "C");
        define("server_version", version);
        define("server_version_num", Integer.toString(versionNumber));
        define("server_encoding", "UTF8");
        define("integer_datetimes", "on");
        define("in_hot_standby", "off");
        define("is_superuser", "off");
        define("session_authorization", user);
        define("max_identifier_length", "63");
        define("max_index_keys", "32");
        define("block_size", "8192");
        define("lc_collate", "C");
        define("lc_ctype", "C");
        define("scram_iterations", Integer.toString(Scram.ITERATIONS));
        define("max_connections", Integer.toString(maxConnections));
    }

    private void define(String name, String value) {
        names.put(name.toLowerCase(Locale.ROOT), name);
        defaults.put(name, value);
        values.put(name, value);
    }

    /** Returns the name of a parameter in the case of PostgreSQL, a name with a dot as it is, or {@code null}. */
    String name(String name) {
        String known = names.get(name.toLowerCase(Locale.ROOT));
        if (known != null) {
            return known;
        }
        return name.indexOf('.') > 0 ? name.toLowerCase(Locale.ROOT) : null;
    }

    /** Tells whether the server keeps a parameter here, rather than in the session of the engine. */
    boolean holds(String name) {
        return name(name) != null;
    }

    /**
     * Returns the value of a parameter.
     *
     * @throws InvalidSetting if there is no such parameter
     */
    String get(String name) {
        String n = known(name);
        String v = values.get(n);
        if (v == null) {
            throw undefined(name);
        }
        return v;
    }

    /** Returns the value of a parameter, or {@code null} if there is none of the name. */
    String find(String name) {
        String n = name(name);
        return n == null ? null : values.get(n);
    }

    /** Returns the parameters and their values, in the order of their definition. */
    Map<String, String> all() {
        return new LinkedHashMap<>(values);
    }

    /** Returns the value a parameter starts with, or {@code null} for a name with a dot. */
    String defaultValue(String name) {
        return defaults.get(name(name));
    }

    /** Returns the encoding of the text the client sends and receives. */
    Charset charset() {
        return charset;
    }

    private String known(String name) {
        String n = name(name);
        if (n == null) {
            throw undefined(name);
        }
        return n;
    }

    static InvalidSetting undefined(String name) {
        return new InvalidSetting("unrecognized configuration parameter \"" + name + "\"", UNDEFINED_OBJECT);
    }

    /**
     * Sets a parameter, or returns it to the value it starts with for
     * {@code null}.
     *
     * @return the name of the parameter in the case of PostgreSQL
     * @throws InvalidSetting if there is no such parameter, it cannot be set,
     *         or the value is not one the server takes
     */
    String set(String name, String value) {
        String n = known(name);
        if (READ_ONLY.contains(n)) {
            throw new InvalidSetting("parameter \"" + n + "\" cannot be changed", CANT_CHANGE_RUNTIME_PARAM);
        }
        String v = value == null ? defaults.getOrDefault(n, "") : check(n, value);
        if (n.equals(CLIENT_ENCODING)) {
            charset = charset(v);
        }
        values.put(n, v);
        return n;
    }

    /** Returns every parameter that may be set to the value it starts with. */
    void resetAll() {
        for (String n : List.copyOf(values.keySet())) {
            if (!READ_ONLY.contains(n)) {
                set(n, null);
            }
        }
    }

    /** Checks a value and returns it as {@code SHOW} shows it. */
    private static String check(String name, String value) {
        String v = value.strip();
        String lower = v.toLowerCase(Locale.ROOT);
        return switch (name) {
            case CLIENT_ENCODING -> {
                charset(v);
                yield canonicalEncoding(v);
            }
            case "DateStyle" -> {
                if (!lower.contains("iso")) {
                    throw new InvalidSetting("FyrQL writes dates only in the style ISO; DateStyle must be ISO, not "
                            + v, SqlStates.FEATURE_NOT_SUPPORTED);
                }
                yield lower.contains("dmy") ? "ISO, DMY" : lower.contains("ymd") ? "ISO, YMD" : "ISO, MDY";
            }
            case "IntervalStyle" -> only(name, lower, "postgres");
            case "standard_conforming_strings" -> only(name, bool(name, v) ? "on" : "off", "on");
            case "bytea_output" -> only(name, lower, "hex");
            case "default_transaction_isolation", "transaction_isolation" -> {
                String level = lower.replaceAll("\\s+", " ");
                if (!List.of("read uncommitted", "read committed", "repeatable read", "serializable").contains(level)) {
                    throw invalid(name, v);
                }
                yield level;
            }
            case "default_transaction_read_only", "transaction_read_only", "default_transaction_deferrable",
                    "transaction_deferrable", "row_security", "jit", "synchronous_commit" ->
                    bool(name, v) ? "on" : "off";
            case "extra_float_digits" -> {
                try {
                    int digits = Integer.parseInt(v);
                    if (digits < -15 || digits > 3) {
                        throw invalid(name, v);
                    }
                    yield Integer.toString(digits);
                } catch (NumberFormatException e) {
                    throw invalid(name, v);
                }
            }
            default -> v;
        };
    }

    private static String only(String name, String value, String allowed) {
        if (!value.equals(allowed)) {
            throw new InvalidSetting("FyrQL supports only " + name + " = " + allowed + ", not " + value,
                    SqlStates.FEATURE_NOT_SUPPORTED);
        }
        return value;
    }

    private static boolean bool(String name, String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1", "t", "y" -> true;
            case "off", "false", "no", "0", "f", "n" -> false;
            default -> throw invalid(name, v);
        };
    }

    static InvalidSetting invalid(String name, String value) {
        return new InvalidSetting("invalid value for parameter \"" + name + "\": \"" + value + "\"",
                INVALID_PARAMETER_VALUE);
    }

    /** The encodings of PostgreSQL that the server speaks, by their names in lower case without separators. */
    private static final Map<String, String> ENCODINGS = Map.ofEntries(Map.entry("utf8", "UTF-8"),
            Map.entry("unicode", "UTF-8"), Map.entry("sqlascii", "US-ASCII"), Map.entry("latin1", "ISO-8859-1"),
            Map.entry("latin2", "ISO-8859-2"), Map.entry("latin3", "ISO-8859-3"), Map.entry("latin4", "ISO-8859-4"),
            Map.entry("latin5", "ISO-8859-9"), Map.entry("latin7", "ISO-8859-13"), Map.entry("latin9", "ISO-8859-15"),
            Map.entry("win866", "IBM866"), Map.entry("win874", "x-windows-874"), Map.entry("win1250", "windows-1250"),
            Map.entry("win1251", "windows-1251"), Map.entry("win1252", "windows-1252"),
            Map.entry("win1253", "windows-1253"), Map.entry("win1254", "windows-1254"),
            Map.entry("win1255", "windows-1255"), Map.entry("win1256", "windows-1256"),
            Map.entry("win1257", "windows-1257"), Map.entry("win1258", "windows-1258"), Map.entry("koi8r", "KOI8-R"),
            Map.entry("koi8u", "KOI8-U"), Map.entry("iso88595", "ISO-8859-5"), Map.entry("iso88596", "ISO-8859-6"),
            Map.entry("iso88597", "ISO-8859-7"), Map.entry("iso88598", "ISO-8859-8"));

    private static String key(String encoding) {
        return encoding.toLowerCase(Locale.ROOT).replaceAll("[-_ ]", "");
    }

    /**
     * Returns the Java encoding of an encoding of PostgreSQL.
     *
     * @throws InvalidSetting if the server does not speak it
     */
    static Charset charset(String encoding) {
        String java = ENCODINGS.get(key(encoding));
        if (java == null || !Charset.isSupported(java)) {
            throw invalid(CLIENT_ENCODING, encoding);
        }
        return Charset.forName(java);
    }

    /** Returns an encoding as PostgreSQL names it, such as {@code UTF8} or {@code WIN1252}. */
    private static String canonicalEncoding(String encoding) {
        String k = key(encoding);
        return switch (k) {
            case "unicode", "utf8" -> "UTF8";
            case "sqlascii" -> "SQL_ASCII";
            default -> k.toUpperCase(Locale.ROOT);
        };
    }
}
