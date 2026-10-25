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

import net.fyrql.FyrQL;
import net.fyrql.Overlay;
import net.fyrql.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A server for an engine that speaks the wire protocol of PostgreSQL
 * (version 3.0), so that psql, psycopg, the JDBC driver of PostgreSQL and
 * the tools built on them run queries without a driver of their own. Each
 * connection is a session of the engine on a thread of its own; its
 * database is a source of the engine, whose schemas are the schemas of
 * PostgreSQL. Closing the server leaves the engine open.
 *
 * <pre>{@code
 * try (PgWireServer server = PgWireServer.builder(engine).config(PgWireConfig.defaults()).build()) {
 *     server.start();
 *     // psql -h 127.0.0.1 -p 5433 -d <source>
 * }
 * }</pre>
 */
public final class PgWireServer implements AutoCloseable {

    /** The version of the server, that of FyrQL. */
    public static final String VERSION = version();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FyrQL engine;
    private final PgWireConfig config;
    private final Scram scram;
    private final Tls tls;
    private final int maxQueries;
    private final Semaphore queries;
    private final PgCatalog catalog;
    private final Map<Integer, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicInteger admitted = new AtomicInteger();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private ServerSocket socket;
    private Thread acceptor;
    private volatile boolean closed;

    private PgWireServer(FyrQL engine, PgWireConfig config) throws IOException {
        this.engine = engine;
        this.config = config;
        this.scram = new Scram(config.users());
        this.tls = config.tls() ? Tls.of(config.certificate(), config.key()) : null;
        this.maxQueries = config.maxQueries() > 0 ? config.maxQueries() : 2 * engine.threads();
        this.queries = new Semaphore(maxQueries);
        ServerSettings defaults = new ServerSettings(config.serverVersion(), config.versionNumber(), OWNER,
                config.maxConnections());
        PgFunctions.Lookups none = new PgFunctions.Lookups() {
            @Override
            public Integer relation(String name) {
                return null;
            }

            @Override
            public List<String> columns(int oid) {
                return List.of();
            }
        };
        this.catalog = new PgCatalog(engine, defaults, PgFunctions.registry(engine.functions(), OWNER, none).names());
    }

    /** The name of the owner of every object in the catalogs. */
    static final String OWNER = "fyrql";

    private static String version() {
        try (InputStream in = PgWireServer.class.getResourceAsStream("server.properties")) {
            if (in == null) {
                return "0.0";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "0.0").strip();
            return v.startsWith("${") ? "0.0" : v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts the building of a server for an engine. */
    public static Builder builder(FyrQL engine) {
        return new Builder(Objects.requireNonNull(engine, "engine"));
    }

    /** Builds a server. */
    public static final class Builder {
        private final FyrQL engine;
        private PgWireConfig config = PgWireConfig.defaults();

        private Builder(FyrQL engine) {
            this.engine = engine;
        }

        /** Sets the settings of the server; by default {@link PgWireConfig#defaults()}. */
        public Builder config(PgWireConfig newConfig) {
            this.config = Objects.requireNonNull(newConfig, "config");
            return this;
        }

        /**
         * Builds the server, which does not listen until {@link #start()}.
         *
         * @throws IOException if the certificate or the key of TLS cannot be read
         * @throws IllegalArgumentException if they hold no certificate or key that fit
         */
        public PgWireServer build() throws IOException {
            return new PgWireServer(engine, config);
        }
    }

    /**
     * Listens on the host and port of the settings and takes connections on
     * a thread of its own.
     *
     * @throws IOException if the server cannot listen there
     * @throws IllegalStateException if it was started or closed before
     */
    public synchronized void start() throws IOException {
        if (socket != null || closed) {
            throw new IllegalStateException("The server was started already");
        }
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName(config.host()), config.port()), 128);
        } catch (IOException e) {
            s.close();
            throw new IOException("The server cannot listen on " + config.host() + ":" + config.port() + ": "
                    + e.getMessage(), e);
        }
        socket = s;
        acceptor = new Thread(this::accept, "fyrql-pgwire-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void accept() {
        try {
            while (!closed) {
                Socket client;
                try {
                    client = socket.accept();
                } catch (SocketException e) {
                    if (closed) {
                        return;
                    }
                    continue;
                }
                try {
                    client.setTcpNoDelay(true);
                    client.setKeepAlive(true);
                } catch (SocketException e) {
                    // Options of a socket that is already gone.
                }
                int id = ids.incrementAndGet();
                Connection c = new Connection(this, client, id, RANDOM.nextInt());
                connections.put(id, c);
                Thread t = new Thread(c, "fyrql-pgwire-" + id);
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            // The socket of the server is gone.
        } finally {
            stopped.countDown();
        }
    }

    /** Returns the port the server listens on, also when the settings ask for a free one. */
    public int port() {
        ServerSocket s = socket;
        if (s == null) {
            throw new IllegalStateException("The server is not started");
        }
        return s.getLocalPort();
    }

    /** Returns the settings of the server. */
    public PgWireConfig config() {
        return config;
    }

    /** Returns the most queries the server runs at once. */
    public int maxQueries() {
        return maxQueries;
    }

    /** Waits until the server is closed. */
    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    /** Stops taking connections and closes the open ones, with their queries; the engine stays open. */
    @Override
    public void close() {
        closed = true;
        ServerSocket s;
        synchronized (this) {
            s = socket;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // Closed already.
            }
        } else {
            stopped.countDown();
        }
        for (Connection c : connections.values()) {
            c.close();
        }
    }

    FyrQL engine() {
        return engine;
    }

    Scram scram() {
        return scram;
    }

    /** The TLS of the server, or {@code null} without TLS. */
    Tls tls() {
        return tls;
    }

    Semaphore queries() {
        return queries;
    }

    PgCatalog catalog() {
        return catalog;
    }

    /**
     * Starts the session of a connection to a database, which sees the
     * catalogs of PostgreSQL and the functions of the server, which only the
     * clients of this server see.
     */
    Session newSession(String database, String user, PgFunctions.Lookups lookups) {
        return Overlay.newSession(engine, catalog.overlay(database), PgFunctions.registry(engine.functions(), user,
                lookups));
    }

    /** Admits a signed-in connection, unless the server has as many as it takes. */
    boolean admit(Connection c) {
        if (admitted.incrementAndGet() > config.maxConnections()) {
            admitted.decrementAndGet();
            connections.remove(c.id());
            return false;
        }
        c.admitted = true;
        return true;
    }

    /** Cancels the statement of a connection, if the key fits. */
    void cancel(int id, int key) {
        Connection c = connections.get(id);
        if (c != null) {
            c.cancel(key);
        }
    }

    void remove(Connection c) {
        if (connections.remove(c.id()) != null && c.admitted) {
            admitted.decrementAndGet();
        }
    }
}
