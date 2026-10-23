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

import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import net.fyrql.FyrQL;
import net.fyrql.config.ConfigException;
import net.fyrql.config.EngineConfig;
import net.fyrql.config.FyrQLConfig;
import net.fyrql.connector.FileAccess;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Starts a Flight SQL server from a configuration file, whose sources and
 * section {@code engine} build the engine and whose section {@code server}
 * holds the settings of the server, see {@link ServerConfig}:
 * <pre>
 * java -jar fyrql-flight-sql-all.jar fyrql.yaml [--host HOST] [--port PORT]
 * </pre>
 * The options override the host and port of the file. Unless the section
 * {@code engine} sets {@code fileAccess}, statements of clients may read
 * and write no files ({@code fileAccess: none}); the tables of the sources
 * stay readable. Unless it sets {@code maxQueryTimeout} and
 * {@code memoryBudget}, no query runs longer than an hour and the queries
 * hold at most half the heap in joins, aggregations and sorts, above which
 * they spill; {@code 0} lifts either limit. The server prints its URI, what
 * statements may do with files, the timeout of queries and the memory
 * budget, and warns on standard error if clients would sign in without TLS
 * on an address other than a loopback address. It runs until the JVM ends,
 * and then closes the engine. The JDBC drivers of the
 * databases of JDBC sources go on the class path next to the JAR, which
 * then starts with its class name and the option of Arrow the manifest of
 * the JAR otherwise sets:
 * <pre>
 * java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "fyrql-flight-sql-all.jar:drivers/*"
 *     net.fyrql.flight.FlightSqlServerMain fyrql.yaml
 * </pre>
 */
public final class FlightSqlServerMain {

    static final String USAGE = "Usage: java -jar fyrql-flight-sql-all.jar <configuration file> [--host <host>]"
            + " [--port <port>]";

    /** The setting of the engine that the server sets to {@code none} unless the file sets it. */
    private static final String FILE_ACCESS = "fileAccess";

    /** The setting of the engine that the server sets to {@link #DEFAULT_MAX_QUERY_TIMEOUT} unless the file sets it. */
    private static final String MAX_QUERY_TIMEOUT = "maxQueryTimeout";

    /** How long any query may run unless the file says otherwise. */
    static final String DEFAULT_MAX_QUERY_TIMEOUT = "1h";

    /** The setting of the engine that the server sets to half the heap unless the file sets it. */
    private static final String MEMORY_BUDGET = "memoryBudget";

    /** The logger of gRPC that reports errors of single streams; kept, so that its filter stays. */
    private static final Logger STREAM_ERRORS = Logger.getLogger("io.grpc.netty.NettyServerHandler");

    private FlightSqlServerMain() {
    }

    /** A wrong command line. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** A running server and the engine it closes along. */
    record Running(FlightSqlServer server, FyrQL engine) implements AutoCloseable {
        @Override
        public void close() {
            try {
                server.close();
            } finally {
                engine.close();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        quietClosedStreams();
        Running running;
        try {
            running = start(args, System.out, System.err);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        } catch (RuntimeException | IOException e) {
            System.err.println("The FyrQL Flight SQL server cannot start: " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(running::close, "fyrql-flight-shutdown"));
        running.server().awaitTermination();
    }

    /** Starts the server as {@link #start(String[], PrintStream, PrintStream)} does, warning on standard error. */
    static Running start(String[] args, PrintStream out) throws IOException {
        return start(args, out, System.err);
    }

    /**
     * Reads the configuration, opens the engine, with the file access
     * {@code none}, the most time of a query and the memory budget of the
     * server unless the configuration sets them, starts the server and
     * prints its URI, the file access, the timeout of queries and the
     * memory budget, after a warning about sign-in in plain text.
     *
     * @param out where the server says where it listens and what it allows
     * @param err where the server warns
     * @throws UsageException if the arguments are wrong
     * @throws ConfigException if the configuration is no valid configuration
     * @throws IllegalArgumentException if a setting of the server is invalid, or the engine cannot be built
     * @throws IOException if the server cannot listen on its host and port
     */
    static Running start(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path file = null;
        String host = null;
        Integer port = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--host" -> host = value(args, ++i, a);
                case "--port" -> {
                    String p = value(args, ++i, a);
                    try {
                        port = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        throw new UsageException("The port " + p + " is no number");
                    }
                }
                default -> {
                    if (a.startsWith("-")) {
                        throw new UsageException("Unknown option " + a);
                    }
                    if (file != null) {
                        throw new UsageException("More than one configuration file: " + file + " and " + a);
                    }
                    file = Path.of(a);
                }
            }
        }
        if (file == null) {
            throw new UsageException("No configuration file");
        }
        if (!Files.isRegularFile(file)) {
            throw new UsageException("The configuration file " + file + " does not exist");
        }
        EngineConfig config = EngineConfig.read(file);
        ServerConfig server;
        try {
            server = ServerConfig.of(config);
            if (host != null) {
                server = server.withHost(host);
            }
            if (port != null) {
                server = server.withPort(port);
            }
            server.checkSignInBeyondLoopback();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        if (server.plainSignInBeyondLoopback()) {
            err.println("Warning: clients sign in to " + server.host() + " without TLS, so that their passwords"
                    + " and tokens cross the network in plain text; set server.tls.certificate and server.tls.key,"
                    + " or put a proxy with TLS in front of the server");
        }
        if (!config.settings().containsKey(FILE_ACCESS)) {
            config = config.withSetting(FILE_ACCESS, FileAccess.NONE.toString());
        }
        if (!config.settings().containsKey(MAX_QUERY_TIMEOUT)) {
            config = config.withSetting(MAX_QUERY_TIMEOUT, DEFAULT_MAX_QUERY_TIMEOUT);
        }
        long heap = Runtime.getRuntime().maxMemory();
        if (!config.settings().containsKey(MEMORY_BUDGET) && heap != Long.MAX_VALUE) {
            config = config.withSetting(MEMORY_BUDGET, defaultMemoryBudget(heap));
        }
        String budget = config.settings().getOrDefault(MEMORY_BUDGET, "0").strip();
        FyrQL engine;
        try {
            engine = FyrQLConfig.open(config);
        } catch (ConfigException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        FlightSqlServer s = null;
        try {
            s = FlightSqlServer.builder(engine).config(server).build();
            s.start();
        } catch (IOException | RuntimeException e) {
            if (s != null) {
                close(s, e);
            }
            engine.close();
            throw e;
        }
        out.println("FyrQL Flight SQL server " + FlightSqlServer.VERSION + " listening on " + s.uri());
        out.println("File access from SQL: " + fileAccess(engine) + "; query timeout: " + timeout(engine)
                + "; session timeout: " + (s.config().sessionTimeout().isZero() ? "none"
                : text(s.config().sessionTimeout())) + "; memory budget: " + (budget.equals("0") ? "none" : budget));
        return new Running(s, engine);
    }

    /** Returns half of a heap, in whole MiB, as the setting {@code memoryBudget} takes it. */
    static String defaultMemoryBudget(long heap) {
        return Math.max(1, heap / 2 / (1024 * 1024)) + "MiB";
    }

    /** Describes what statements may do with files, such as {@code read under /data, s3://lake/}. */
    static String fileAccess(FyrQL engine) {
        FileAccess access = engine.fileAccess();
        if (access == FileAccess.NONE) {
            return "none";
        }
        return access + (engine.allowedLocations().isEmpty() ? " anywhere"
                : " under " + String.join(", ", engine.allowedLocations()));
    }

    /** Describes the timeout of queries, such as {@code 30s, at most 5m}. */
    static String timeout(FyrQL engine) {
        Optional<Duration> timeout = engine.queryTimeout();
        Optional<Duration> max = engine.maxQueryTimeout();
        String text = timeout.map(FlightSqlServerMain::text).orElse("none");
        return max.isEmpty() ? text : text + ", at most " + text(max.get());
    }

    /** Returns a duration as the configuration takes it, such as {@code 30s}. */
    private static String text(Duration d) {
        if (d.getNano() % 1_000_000 != 0) {
            return d.toString();
        }
        long ms = d.toMillis();
        return ms % 3_600_000 == 0 ? ms / 3_600_000 + "h" : ms % 60_000 == 0 ? ms / 60_000 + "m"
                : ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }

    /**
     * Drops the warning with a stack trace that gRPC logs when a client
     * closes a stream, e.g. to cancel a query, while results of the server
     * are still on the way to it; the other errors of streams stay.
     */
    static void quietClosedStreams() {
        STREAM_ERRORS.setFilter(r -> !(r.getThrown() instanceof Http2Exception.StreamException e
                && e.error() == Http2Error.STREAM_CLOSED));
    }

    private static void close(FlightSqlServer server, Exception failure) {
        try {
            server.close();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new UsageException("The option " + option + " needs a value");
        }
        return args[i];
    }
}
