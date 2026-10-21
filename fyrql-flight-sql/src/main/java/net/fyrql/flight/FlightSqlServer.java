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

import net.fyrql.FyrQL;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Properties;

/**
 * An Arrow Flight SQL server for an engine:
 * <pre>{@code
 * try (FlightSqlServer server = FlightSqlServer.builder(engine).config(ServerConfig.defaults()).build()) {
 *     server.start();
 *     server.awaitTermination();
 * }
 * }</pre>
 * Clients such as the Flight SQL driver of ADBC or the Flight SQL JDBC
 * driver of Arrow connect to {@link #uri()}. Without users or a token in
 * its settings the server listens only on a loopback address. Closing the
 * server stops it and leaves the engine open. Arrow needs the JVM option
 * {@code --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED}.
 */
public final class FlightSqlServer implements AutoCloseable {

    /** The version of the server, as it reports it to its clients. */
    public static final String VERSION;

    /** The version of Arrow the server was built with. */
    public static final String ARROW_VERSION;

    /** How long closing waits for the calls that ended to give back their buffers. */
    private static final long CLOSE_MILLIS = 3000;

    static {
        Properties p = new Properties();
        try (InputStream in = FlightSqlServer.class.getResourceAsStream("server.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        VERSION = property(p, "version");
        ARROW_VERSION = property(p, "arrow");
    }

    private static String property(Properties p, String key) {
        String value = p.getProperty(key, "0.0").strip();
        return value.startsWith("${") ? "0.0" : value;
    }

    private final ServerConfig config;
    private final BufferAllocator allocator;
    private final FyrQLFlightSqlProducer producer;
    private final FlightServer server;

    private FlightSqlServer(FyrQL engine, ServerConfig config) {
        this.config = config;
        Location location = config.tls() ? Location.forGrpcTls(config.host(), config.port())
                : Location.forGrpcInsecure(config.host(), config.port());
        this.allocator = new RootAllocator();
        try {
            this.producer = new FyrQLFlightSqlProducer(engine, allocator, config);
            FlightServer.Builder b = FlightServer.builder(allocator, location, producer)
                    .headerAuthenticator(SignIn.of(config));
            if (config.tls()) {
                b.useTls(config.certificate().toFile(), config.key().toFile());
            }
            this.server = b.build();
        } catch (IOException e) {
            allocator.close();
            throw new UncheckedIOException("Cannot read the certificate or key of TLS: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            allocator.close();
            throw e;
        }
    }

    /** Returns a builder of a server for an engine. */
    public static Builder builder(FyrQL engine) {
        return new Builder(engine);
    }

    /** Builds a server. */
    public static final class Builder {
        private final FyrQL engine;
        private ServerConfig config = ServerConfig.defaults();

        private Builder(FyrQL engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        /** Sets the settings of the server; the default is {@link ServerConfig#defaults()}. */
        public Builder config(ServerConfig serverConfig) {
            this.config = Objects.requireNonNull(serverConfig, "serverConfig");
            return this;
        }

        /**
         * Builds the server, which does not listen until it starts.
         *
         * @throws IllegalArgumentException if the server would take every
         *         client on an address other than a loopback address
         * @throws UncheckedIOException if the certificate or key of TLS cannot be read
         */
        public FlightSqlServer build() {
            config.checkSignInBeyondLoopback();
            return new FlightSqlServer(engine, config);
        }
    }

    /**
     * Starts listening.
     *
     * @throws IOException if the server cannot listen on its host and port
     */
    public FlightSqlServer start() throws IOException {
        server.start();
        return this;
    }

    /** Returns the settings of the server. */
    public ServerConfig config() {
        return config;
    }

    /** Returns the port the server listens on, which is a free one if the settings ask for port 0. */
    public int port() {
        return server.getPort();
    }

    /** Returns the URI clients connect to, such as {@code grpc+tcp://127.0.0.1:32010}. */
    public String uri() {
        String host = config.host().indexOf(':') >= 0 ? "[" + config.host() + "]" : config.host();
        return (config.tls() ? "grpc+tls" : "grpc+tcp") + "://" + host + ":" + port();
    }

    /** Returns the most queries that run at once. */
    public int maxQueries() {
        return producer.maxQueries();
    }

    /** Waits until the server stops. */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    /** Returns the number of queries that run. */
    int runningQueries() {
        return producer.runningQueries();
    }

    /** Returns the number of statements and prepared statements the server holds handles for. */
    int handles() {
        return producer.handles();
    }

    /**
     * Stops the server: it takes no new calls, ends those that run and waits
     * at most {@value #CLOSE_MILLIS} ms for them to give back their buffers,
     * which a call may still hold when its client has read its last batch;
     * the engine stays open. A thread interrupted while it waits for the
     * calls keeps its interrupt status.
     */
    @Override
    public void close() {
        try {
            server.close();
            producer.awaitBuffers(CLOSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            producer.close();
            allocator.close();
        }
    }
}
