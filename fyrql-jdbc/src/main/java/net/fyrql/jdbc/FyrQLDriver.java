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

import net.fyrql.FyrQL;
import net.fyrql.config.EngineConfig;
import net.fyrql.connector.ConnectorFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The JDBC driver of FyrQL, which runs an engine in the process of the
 * application. A URL has the form {@code jdbc:fyrql:} or
 * {@code jdbc:fyrql:?config=fyrql.yaml&codegen=off}:
 * <ul>
 * <li>{@code config} names a configuration file of {@code fyrql-config},
 * relative to the working directory; its sources become the catalogs of
 * the connection. Without it, the engine has the sources {@code csv},
 * {@code json}, {@code parquet}, {@code iceberg}, {@code xlsx} and
 * {@code memory} without tables, as far as their connectors are on the
 * class path, so that table functions such as {@code read_csv} and file
 * names in {@code FROM} work, and {@code CREATE TABLE memory.t AS ...}
 * keeps a table for as long as the engine.</li>
 * <li>{@code batchSize}, {@code memoryBudget}, {@code spill},
 * {@code spillDirectory}, {@code codegen}, {@code codegenThreshold},
 * {@code pushdown}, {@code threads}, {@code preserveOrder},
 * {@code fileAccess}, {@code allowedLocations} and
 * {@code maxQueryTimeout} are the settings of the engine, as the
 * configuration file takes them, over whose settings they win; a relative
 * allowed location is resolved against the directory of the configuration
 * file, or the working directory without one.</li>
 * <li>{@code timeZone}, {@code defaultSource}, {@code defaultSchema} and
 * {@code queryTimeout} are the settings of the session of the connection,
 * which win over those of the engine; {@code RESET} returns to the latter,
 * those of the configuration file or of the engine by default.</li>
 * <li>{@code shared} and {@code closeDelay} decide whether connections
 * share an engine and how long it lives.</li>
 * </ul>
 * The same keys may be given as properties of the connection, which win
 * over the URL.
 * <p>
 * Connections share an engine, and so its tables in memory, its memory
 * budget and its threads, if their configurations are equal: the same
 * sources, views and settings of the engine after reading the file anew,
 * and the same {@code closeDelay}. A connection with {@code fileAccess=read}
 * thus never shares the engine of one that may write. A configuration file
 * that changed gives the next connection a new engine, while the old one
 * stays open for its connections; a file that cannot be read fails the
 * new connection only. The last connection of an engine to close closes it,
 * after {@code closeDelay} if that is set, such as {@code 30s}; a new
 * connection meanwhile keeps it open, and {@code closeDelay=-1} keeps it
 * open until the JVM ends. With {@code shared=false} a connection builds an
 * engine of its own and closes it with the connection. Closing the engine
 * that {@code unwrap(FyrQL.class)} returns closes it for every connection
 * that shares it. The engines belong to the class loader of the driver;
 * the connectors are found with it, too.
 * <p>
 * {@link #connect(FyrQL, boolean)} opens a connection over an engine of the
 * application instead. Either way, a connection is a session of its own,
 * with its own settings.
 * <p>
 * The driver registers itself with {@link DriverManager} when its class is
 * loaded, and tools may create it with its public constructor.
 */
public final class FyrQLDriver implements Driver {

    /** The version of the driver, such as {@code 0.4.0}, or {@code 0.0} if it is unknown. */
    static final String VERSION = readVersion();
    static final int MAJOR_VERSION = versionPart(0);
    static final int MINOR_VERSION = versionPart(1);

    static {
        try {
            DriverManager.registerDriver(new FyrQLDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public FyrQLDriver() {
    }

    /**
     * Opens a connection over an engine of the application; its sources
     * become the catalogs of the connection. The connection is a new
     * session of the engine, see {@link FyrQL#newSession()}, so that
     * {@code SET} in it changes neither the engine nor other connections.
     *
     * @param closeEngineOnClose whether closing the connection closes the engine
     */
    public static Connection connect(FyrQL engine, boolean closeEngineOnClose) {
        Objects.requireNonNull(engine, "engine");
        return new FyrQLConnection(engine, closeEngineOnClose ? engine::close : () -> {
        }, UrlSettings.PREFIX);
    }

    /**
     * Opens a connection over the engine of the settings of the URL and the
     * properties: the one that connections with the same settings share,
     * built if there is none, or one of its own with {@code shared=false}.
     *
     * @return the connection, or {@code null} if the URL is not one of FyrQL
     * @throws SQLException {@code 08001} if the URL is not valid, the
     *         configuration cannot be read, a source cannot be created, or a
     *         setting of the session is invalid
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        UrlSettings settings = UrlSettings.parse(url, info);
        boolean shared = settings.shared();
        Duration closeDelay = settings.closeDelay();
        Map<String, ConnectorFactory> factories = EngineFactory.factories(FyrQLDriver.class.getClassLoader());
        EngineConfig config = EngineFactory.config(settings, factories);
        FyrQLConnection connection;
        if (shared) {
            SharedEngines.Lease lease = SharedEngines.acquire(new SharedEngines.Key(config, closeDelay),
                    () -> EngineFactory.open(config, factories, settings.config()));
            connection = start(lease.engine(), lease::release, url, settings);
        } else {
            FyrQL engine = EngineFactory.open(config, factories, settings.config());
            connection = start(engine, engine::close, url, settings);
        }
        for (String name : settings.unknownProperties()) {
            connection.addWarning(new SQLWarning("The property " + name + " is unknown and ignored; known are "
                    + String.join(", ", keyNames())));
        }
        return connection;
    }

    /**
     * Opens a connection over an engine with the settings of its session;
     * if they are invalid, the connection is closed, so that the owner gives
     * the engine back.
     */
    private static FyrQLConnection start(FyrQL engine, Runnable owner, String url, UrlSettings settings)
            throws SQLException {
        FyrQLConnection connection = new FyrQLConnection(engine, owner, url);
        try {
            connection.startWith(settings.sessionSettings());
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException c) {
                e.addSuppressed(c);
            }
            throw e;
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        if (url == null) {
            throw SqlStates.invalidArgument("The URL is null");
        }
        return UrlSettings.accepts(url);
    }

    /** Describes the keys of the URL, with the values that the URL and the properties give them. */
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        UrlSettings settings = acceptsURL(url) ? UrlSettings.lenient(url, info) : UrlSettings.lenient(
                UrlSettings.PREFIX, info);
        List<DriverPropertyInfo> result = new ArrayList<>();
        for (UrlSettings.Key key : UrlSettings.KEYS) {
            DriverPropertyInfo p = new DriverPropertyInfo(key.name(), settings.values().get(key.name()));
            p.description = key.description();
            p.required = false;
            p.choices = key.choices().isEmpty() ? null : key.choices().toArray(String[]::new);
            result.add(p);
        }
        return result.toArray(DriverPropertyInfo[]::new);
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    /** Returns {@code false}: the driver has no transactions, every statement takes effect at once. */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("net.fyrql.jdbc");
    }

    private static List<String> keyNames() {
        List<String> names = new ArrayList<>();
        for (UrlSettings.Key key : UrlSettings.KEYS) {
            names.add(key.name());
        }
        return names;
    }

    private static String readVersion() {
        try (InputStream in = FyrQLDriver.class.getResourceAsStream("driver.properties")) {
            if (in == null) {
                return "0.0";
            }
            Properties p = new Properties();
            p.load(in);
            String version = p.getProperty("version", "0.0").strip();
            return version.startsWith("${") ? "0.0" : version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int versionPart(int index) {
        String[] parts = VERSION.split("[.-]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
