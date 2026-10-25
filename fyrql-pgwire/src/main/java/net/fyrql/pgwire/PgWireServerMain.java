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

/**
 * Starts a PostgreSQL wire protocol server from a configuration file, whose
 * sources and section {@code engine} build the engine and whose section
 * {@code pgwire} holds the settings of the server, see {@link PgWireConfig}:
 * <pre>
 * java -jar fyrql-pgwire-all.jar fyrql.yaml [--host HOST] [--port PORT]
 * </pre>
 * The options override the host and port of the file. As for the Flight
 * SQL server, statements of clients may read and write no files unless
 * the section {@code engine} sets {@code fileAccess}, no query runs longer
 * than an hour unless it sets {@code maxQueryTimeout}, and the queries hold
 * at most half the heap unless it sets {@code memoryBudget}; {@code 0}
 * lifts either limit. The server prints where it listens and what it
 * allows, warns on standard error if clients would sign in without TLS on
 * an address other than a loopback address, runs until the JVM ends, and
 * then closes the engine. The JDBC drivers of the databases of JDBC sources
 * go on the class path next to the JAR:
 * <pre>
 * java -cp "fyrql-pgwire-all.jar:drivers/*" net.fyrql.pgwire.PgWireServerMain fyrql.yaml
 * </pre>
 */
public final class PgWireServerMain {

    static final String USAGE = "Usage: java -jar fyrql-pgwire-all.jar <configuration file> [--host <host>]"
            + " [--port <port>]";

    private static final String FILE_ACCESS = "fileAccess";
    private static final String MAX_QUERY_TIMEOUT = "maxQueryTimeout";
    static final String DEFAULT_MAX_QUERY_TIMEOUT = "1h";
    private static final String MEMORY_BUDGET = "memoryBudget";

    private PgWireServerMain() {
    }

    /** A wrong command line. */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** A running server and the engine it closes along. */
    record Running(PgWireServer server, FyrQL engine) implements AutoCloseable {
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
        Running running;
        try {
            running = start(args, System.out, System.err);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        } catch (RuntimeException | IOException e) {
            System.err.println("The FyrQL PostgreSQL wire protocol server cannot start: " + e.getMessage());
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(running::close, "fyrql-pgwire-shutdown"));
        running.server().awaitTermination();
    }

    /**
     * Reads the configuration, opens the engine with the limits of the
     * server unless the configuration sets them, starts the server and
     * prints where it listens and what it allows, after a warning about
     * sign-in in plain text.
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
        PgWireConfig server;
        try {
            server = PgWireConfig.of(config);
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
            err.println("Warning: clients sign in to " + server.host() + " without TLS; SCRAM keeps the passwords"
                    + " off the network, but queries and their rows cross it in plain text; set pgwire.tls.certificate"
                    + " and pgwire.tls.key");
        }
        if (!config.settings().containsKey(FILE_ACCESS)) {
            config = config.withSetting(FILE_ACCESS, FileAccess.NONE.toString());
        }
        if (!config.settings().containsKey(MAX_QUERY_TIMEOUT)) {
            config = config.withSetting(MAX_QUERY_TIMEOUT, DEFAULT_MAX_QUERY_TIMEOUT);
        }
        long heap = Runtime.getRuntime().maxMemory();
        if (!config.settings().containsKey(MEMORY_BUDGET) && heap != Long.MAX_VALUE) {
            config = config.withSetting(MEMORY_BUDGET, Math.max(1, heap / 2 / (1024 * 1024)) + "MiB");
        }
        String budget = config.settings().getOrDefault(MEMORY_BUDGET, "0").strip();
        FyrQL engine;
        try {
            engine = FyrQLConfig.open(config);
        } catch (ConfigException e) {
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
        PgWireServer s = null;
        try {
            s = PgWireServer.builder(engine).config(server).build();
            s.start();
        } catch (IOException | RuntimeException e) {
            if (s != null) {
                s.close();
            }
            engine.close();
            throw e;
        }
        out.println("FyrQL PostgreSQL wire protocol server " + PgWireServer.VERSION + " listening on "
                + server.host() + ":" + s.port() + (server.tls() ? " (TLS)" : ""));
        out.println("File access from SQL: " + fileAccess(engine) + "; query timeout: " + timeout(engine)
                + "; memory budget: " + (budget.equals("0") ? "none" : budget) + "; databases: "
                + String.join(", ", engine.sources()));
        return new Running(s, engine);
    }

    /** Describes what statements may do with files, such as {@code read under /data}. */
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
        String text = timeout.map(PgWireServerMain::text).orElse("none");
        return max.isEmpty() ? text : text + ", at most " + text(max.get());
    }

    private static String text(Duration d) {
        if (d.getNano() % 1_000_000 != 0) {
            return d.toString();
        }
        long ms = d.toMillis();
        return ms % 3_600_000 == 0 ? ms / 3_600_000 + "h" : ms % 60_000 == 0 ? ms / 60_000 + "m"
                : ms % 1000 == 0 ? ms / 1000 + "s" : ms + "ms";
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new UsageException("The option " + option + " needs a value");
        }
        return args[i];
    }
}
