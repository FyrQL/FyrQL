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
import net.fyrql.config.EngineConfig;
import net.fyrql.config.FyrQLConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Starts a Flight SQL server from a configuration file, whose sources and
 * section {@code engine} build the engine and whose section {@code server}
 * holds the settings of the server, see {@link ServerConfig}:
 * <pre>
 * java -jar fyrql-flight-sql-all.jar fyrql.yaml [--host HOST] [--port PORT]
 * </pre>
 * The options override the host and port of the file. The server runs
 * until the JVM ends, and then closes the engine. The JDBC drivers of the
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
            running = start(args, System.out);
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

    /**
     * Reads the configuration, opens the engine, starts the server and
     * prints its URI.
     *
     * @throws UsageException if the arguments are wrong
     * @throws net.fyrql.config.ConfigException if the configuration is no valid configuration
     * @throws IllegalArgumentException if a setting of the server is invalid
     * @throws IOException if the server cannot listen on its host and port
     */
    static Running start(String[] args, PrintStream out) throws IOException {
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
        FyrQL engine = FyrQLConfig.open(file);
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
        return new Running(s, engine);
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
