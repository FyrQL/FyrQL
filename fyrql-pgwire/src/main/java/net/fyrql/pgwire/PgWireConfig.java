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

import net.fyrql.config.EngineConfig;
import net.fyrql.connector.ConfigOptions;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The settings of a PostgreSQL wire protocol server, as the section
 * {@code pgwire} of a configuration file holds them:
 * <pre>{@code
 * pgwire:
 *   host: 0.0.0.0
 *   port: 5433
 *   tls:
 *     certificate: server.pem
 *     key: server.key
 *   users:
 *     alice: ${env:ALICE_PASSWORD}
 *   maxConnections: 100
 *   maxQueries: 16
 *   serverVersion: "18.0"
 * }</pre>
 * With {@code users}, a client signs in with a name and password by
 * SCRAM-SHA-256; without, the server takes every client and listens only
 * on a loopback address. With TLS, it takes only connections that ask for
 * TLS, as {@code sslmode=require} and the default {@code prefer} of the
 * clients do.
 *
 * @param host the address the server listens on
 * @param port the port, or 0 for a free one
 * @param certificate the certificate chain of TLS as PEM, or {@code null} without TLS
 * @param key the private key of the certificate as PEM in PKCS #8, or {@code null} without TLS
 * @param users the passwords by user name
 * @param maxConnections the most connections at once
 * @param maxQueries the most queries that run at once, or 0 for twice the threads of the engine
 * @param serverVersion the version of PostgreSQL the server tells its clients, such as {@code 18.0}
 */
public record PgWireConfig(String host, int port, Path certificate, Path key, Map<String, String> users,
        int maxConnections, int maxQueries, String serverVersion) {

    /** The address the server listens on by default: the loopback address. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** The port the server listens on by default, next to the 5432 of a PostgreSQL on the same host. */
    public static final int DEFAULT_PORT = 5433;

    /** The most connections at once by default. */
    public static final int DEFAULT_MAX_CONNECTIONS = 100;

    /** The version of PostgreSQL the server tells its clients by default. */
    public static final String DEFAULT_SERVER_VERSION = "18.0";

    private static final int MAX_PORT = 65_535;
    private static final Pattern VERSION = Pattern.compile("\\d{1,2}(\\.\\d{1,4})?");

    /**
     * @throws IllegalArgumentException if a value is out of range, or only
     *         one of certificate and key is set
     */
    public PgWireConfig {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("The host of the PostgreSQL wire protocol server is blank");
        }
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("The port of the PostgreSQL wire protocol server must be from 0 to "
                    + MAX_PORT + ", not " + port);
        }
        if ((certificate == null) != (key == null)) {
            throw new IllegalArgumentException("TLS needs both tls.certificate and tls.key");
        }
        users = Collections.unmodifiableMap(new LinkedHashMap<>(users));
        for (Map.Entry<String, String> e : users.entrySet()) {
            if (e.getKey().isBlank() || e.getValue() == null || e.getValue().isEmpty()) {
                throw new IllegalArgumentException("A user of the server needs a name and a password");
            }
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections of the server must be positive");
        }
        if (maxQueries < 0) {
            throw new IllegalArgumentException("maxQueries of the server must not be negative");
        }
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (!VERSION.matcher(serverVersion).matches()) {
            throw new IllegalArgumentException("serverVersion must be a version of PostgreSQL such as 18.0, not "
                    + serverVersion);
        }
    }

    /** Returns the settings of a server on the loopback address and the default port, without TLS and sign-in. */
    public static PgWireConfig defaults() {
        return new PgWireConfig(DEFAULT_HOST, DEFAULT_PORT, null, null, Map.of(), DEFAULT_MAX_CONNECTIONS, 0,
                DEFAULT_SERVER_VERSION);
    }

    /**
     * Reads the section {@code pgwire} of a configuration.
     *
     * @throws IllegalArgumentException if a setting is unknown or invalid
     */
    public static PgWireConfig of(EngineConfig config) {
        return read(config.pgwire(), config.directory());
    }

    /**
     * Reads the settings of a server, flat as {@link EngineConfig#pgwire()}
     * holds them; relative paths are resolved against the directory.
     *
     * @throws IllegalArgumentException if a setting is unknown or invalid
     */
    public static PgWireConfig read(Map<String, String> settings, Path directory) {
        ConfigOptions o = new ConfigOptions("the PostgreSQL wire protocol server", settings, directory);
        String host = o.string("host").map(String::strip).orElse(DEFAULT_HOST);
        int port = o.integer("port").orElse(DEFAULT_PORT);
        Path certificate = o.path("tls.certificate").orElse(null);
        Path key = o.path("tls.key").orElse(null);
        Map<String, String> users = o.prefixed("users", "<name>");
        int maxConnections = o.integer("maxConnections").orElse(DEFAULT_MAX_CONNECTIONS);
        int maxQueries = o.integer("maxQueries").orElse(0);
        String serverVersion = o.string("serverVersion").map(String::strip).orElse(DEFAULT_SERVER_VERSION);
        o.rejectUnknown();
        return new PgWireConfig(host, port, certificate, key, users, maxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with another host. */
    public PgWireConfig withHost(String newHost) {
        return new PgWireConfig(newHost, port, certificate, key, users, maxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with another port. */
    public PgWireConfig withPort(int newPort) {
        return new PgWireConfig(host, newPort, certificate, key, users, maxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with users who sign in with their passwords. */
    public PgWireConfig withUsers(Map<String, String> newUsers) {
        return new PgWireConfig(host, port, certificate, key, newUsers, maxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with TLS from a certificate chain and its private key. */
    public PgWireConfig withTls(Path newCertificate, Path newKey) {
        return new PgWireConfig(host, port, newCertificate, newKey, users, maxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with another number of connections at once. */
    public PgWireConfig withMaxConnections(int newMaxConnections) {
        return new PgWireConfig(host, port, certificate, key, users, newMaxConnections, maxQueries, serverVersion);
    }

    /** Returns these settings with another number of queries that run at once. */
    public PgWireConfig withMaxQueries(int newMaxQueries) {
        return new PgWireConfig(host, port, certificate, key, users, maxConnections, newMaxQueries, serverVersion);
    }

    /** Returns these settings with another version of PostgreSQL for the clients. */
    public PgWireConfig withServerVersion(String newServerVersion) {
        return new PgWireConfig(host, port, certificate, key, users, maxConnections, maxQueries, newServerVersion);
    }

    /** Tells whether the server uses TLS. */
    public boolean tls() {
        return certificate != null;
    }

    /** Tells whether a client must sign in. */
    public boolean signIn() {
        return !users.isEmpty();
    }

    /**
     * Fails for a server that would take every client on an address other
     * hosts reach.
     *
     * @throws IllegalArgumentException if the host is no loopback address and no users are set
     */
    void checkSignInBeyondLoopback() {
        if (!signIn() && !isLoopback(host)) {
            throw new IllegalArgumentException("The server would listen on " + host + " without sign-in; without"
                    + " users it listens only on a loopback address such as " + DEFAULT_HOST);
        }
    }

    /**
     * Tells whether the server would take connections with sign-in but
     * without TLS on an address other than a loopback address.
     */
    boolean plainSignInBeyondLoopback() {
        return signIn() && !tls() && !isLoopback(host);
    }

    /** Tells whether every address of a host is a loopback address. */
    private static boolean isLoopback(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!a.isLoopbackAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The version as {@code server_version_num} gives it, such as {@code 180000}. */
    int versionNumber() {
        String[] parts = serverVersion.split("\\.");
        return Integer.parseInt(parts[0]) * 10_000 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
    }

    /** Leaves the passwords out. */
    @Override
    public String toString() {
        return "PgWireConfig[host=" + host + ", port=" + port + ", certificate=" + certificate + ", key=" + key
                + ", users=" + users.keySet() + ", maxConnections=" + maxConnections + ", maxQueries=" + maxQueries
                + ", serverVersion=" + serverVersion + "]";
    }
}
