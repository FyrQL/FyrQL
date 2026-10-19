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

package net.fyrql.flight;

import net.fyrql.config.EngineConfig;
import net.fyrql.connector.ConfigOptions;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The settings of a Flight SQL server, as the section {@code server} of a
 * configuration file holds them:
 * <pre>{@code
 * server:
 *   host: 0.0.0.0
 *   port: 32010
 *   tls:
 *     certificate: server.pem
 *     key: server.key
 *   users:
 *     alice: ${env:ALICE_PASSWORD}
 *   token: ${env:FYRQL_TOKEN}
 *   handleTimeout: 10m
 *   maxQueries: 16
 * }</pre>
 * With {@code users}, a client signs in with a name and password and goes
 * on with the bearer token the server returns; with {@code token}, a client
 * sends that token as its bearer token. Without either, the server takes
 * every client and listens only on a loopback address.
 *
 * @param host the address the server listens on
 * @param port the port, or 0 for a free one
 * @param certificate the certificate chain of TLS as PEM, or {@code null} without TLS
 * @param key the private key of the certificate as PEM in PKCS #8, or {@code null} without TLS
 * @param users the passwords by user name
 * @param token the bearer token every client may send, or {@code null}
 * @param handleTimeout how long a statement or prepared statement stays
 *        without use until the server forgets it
 * @param maxQueries the most queries that run at once, or 0 for twice the
 *        threads of the engine
 */
public record ServerConfig(String host, int port, Path certificate, Path key, Map<String, String> users, String token,
        Duration handleTimeout, int maxQueries) {

    /** The address the server listens on by default: the loopback address. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** The port the server listens on by default. */
    public static final int DEFAULT_PORT = 32010;

    /** How long a statement or prepared statement stays without use by default. */
    public static final Duration DEFAULT_HANDLE_TIMEOUT = Duration.ofMinutes(10);

    private static final int MAX_PORT = 65_535;

    /**
     * @throws IllegalArgumentException if a value is out of range, or only
     *         one of certificate and key is set
     */
    public ServerConfig {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("The host of the server is blank");
        }
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("The port of the server must be from 0 to " + MAX_PORT + ", not "
                    + port);
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
        if (token != null && token.isBlank()) {
            throw new IllegalArgumentException("The token of the server is blank");
        }
        Objects.requireNonNull(handleTimeout, "handleTimeout");
        if (handleTimeout.isNegative() || handleTimeout.isZero()) {
            throw new IllegalArgumentException("The handle timeout of the server must be positive");
        }
        if (maxQueries < 0) {
            throw new IllegalArgumentException("maxQueries of the server must not be negative");
        }
    }

    /** Returns the settings of a server on the loopback address and the default port, without TLS and sign-in. */
    public static ServerConfig defaults() {
        return new ServerConfig(DEFAULT_HOST, DEFAULT_PORT, null, null, Map.of(), null, DEFAULT_HANDLE_TIMEOUT, 0);
    }

    /**
     * Reads the section {@code server} of a configuration.
     *
     * @throws IllegalArgumentException if a setting is unknown or invalid
     */
    public static ServerConfig of(EngineConfig config) {
        return read(config.server(), config.directory());
    }

    /**
     * Reads the settings of a server, flat as {@link EngineConfig#server()}
     * holds them; relative paths are resolved against the directory.
     *
     * @throws IllegalArgumentException if a setting is unknown or invalid
     */
    public static ServerConfig read(Map<String, String> settings, Path directory) {
        ConfigOptions o = new ConfigOptions("the server", settings, directory);
        String host = o.string("host").map(String::strip).orElse(DEFAULT_HOST);
        int port = o.integer("port").orElse(DEFAULT_PORT);
        Path certificate = o.path("tls.certificate").orElse(null);
        Path key = o.path("tls.key").orElse(null);
        Map<String, String> users = o.prefixed("users", "<name>");
        String token = o.string("token").orElse(null);
        Duration handleTimeout = o.duration("handleTimeout").orElse(DEFAULT_HANDLE_TIMEOUT);
        int maxQueries = o.integer("maxQueries").orElse(0);
        o.rejectUnknown();
        return new ServerConfig(host, port, certificate, key, users, token, handleTimeout, maxQueries);
    }

    /** Returns these settings with another host. */
    public ServerConfig withHost(String newHost) {
        return new ServerConfig(newHost, port, certificate, key, users, token, handleTimeout, maxQueries);
    }

    /** Returns these settings with another port. */
    public ServerConfig withPort(int newPort) {
        return new ServerConfig(host, newPort, certificate, key, users, token, handleTimeout, maxQueries);
    }

    /** Returns these settings with users who sign in with their passwords. */
    public ServerConfig withUsers(Map<String, String> newUsers) {
        return new ServerConfig(host, port, certificate, key, newUsers, token, handleTimeout, maxQueries);
    }

    /** Returns these settings with a bearer token every client may send. */
    public ServerConfig withToken(String newToken) {
        return new ServerConfig(host, port, certificate, key, users, newToken, handleTimeout, maxQueries);
    }

    /** Returns these settings with TLS from a certificate chain and its private key. */
    public ServerConfig withTls(Path newCertificate, Path newKey) {
        return new ServerConfig(host, port, newCertificate, newKey, users, token, handleTimeout, maxQueries);
    }

    /** Returns these settings with another handle timeout. */
    public ServerConfig withHandleTimeout(Duration newHandleTimeout) {
        return new ServerConfig(host, port, certificate, key, users, token, newHandleTimeout, maxQueries);
    }

    /** Returns these settings with another number of queries that run at once. */
    public ServerConfig withMaxQueries(int newMaxQueries) {
        return new ServerConfig(host, port, certificate, key, users, token, handleTimeout, newMaxQueries);
    }

    /** Tells whether the server uses TLS. */
    public boolean tls() {
        return certificate != null;
    }

    /** Tells whether a client must sign in. */
    public boolean signIn() {
        return !users.isEmpty() || token != null;
    }

    /**
     * Fails for a server that would take every client on an address other
     * hosts reach.
     *
     * @throws IllegalArgumentException if the host is no loopback address
     *         and neither users nor a token are set
     */
    void checkSignInBeyondLoopback() {
        if (!signIn() && !isLoopback(host)) {
            throw new IllegalArgumentException("The server would listen on " + host + " without sign-in; without"
                    + " users or a token it listens only on a loopback address such as " + DEFAULT_HOST);
        }
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

    /** Leaves the passwords and the token out. */
    @Override
    public String toString() {
        return "ServerConfig[host=" + host + ", port=" + port + ", certificate=" + certificate + ", key=" + key
                + ", users=" + users.keySet() + ", token=" + (token == null ? "none" : "set") + ", handleTimeout="
                + handleTimeout + ", maxQueries=" + maxQueries + "]";
    }
}
