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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
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
 * {@code json}, {@code parquet} and {@code memory} without tables, as far
 * as their connectors are on the class path, so that table functions such
 * as {@code read_csv} and file names in {@code FROM} work, and
 * {@code CREATE TABLE memory.t AS ...} keeps a table for as long as the
 * connection.</li>
 * <li>{@code defaultSource}, {@code defaultSchema}, {@code batchSize},
 * {@code memoryBudget}, {@code spill}, {@code spillDirectory},
 * {@code codegen}, {@code codegenThreshold}, {@code pushdown},
 * {@code threads}, {@code preserveOrder}, {@code timeZone},
 * {@code fileAccess}, {@code allowedLocations}, {@code queryTimeout} and
 * {@code maxQueryTimeout} are the settings of the engine, as the
 * configuration file takes them, over whose settings they win; a relative
 * allowed location is resolved against the directory of the configuration
 * file, or the working directory without one.</li>
 * </ul>
 * The same keys may be given as properties of the connection, which win
 * over the URL. Each connection builds an engine of its own and closes it
 * with the connection; {@link #connect(FyrQL, boolean)} opens a
 * connection over an engine of the application instead. The connectors
 * are found with the class loader of the driver.
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
     * become the catalogs of the connection.
     *
     * @param closeEngineOnClose whether closing the connection closes the engine
     */
    public static Connection connect(FyrQL engine, boolean closeEngineOnClose) {
        return new FyrQLConnection(Objects.requireNonNull(engine, "engine"), closeEngineOnClose, UrlSettings.PREFIX);
    }

    /**
     * Builds an engine from the settings of the URL and the properties and
     * opens a connection over it.
     *
     * @return the connection, or {@code null} if the URL is not one of FyrQL
     * @throws SQLException {@code 08001} if the URL is not valid, the
     *         configuration cannot be read, or a source cannot be created
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        UrlSettings settings = UrlSettings.parse(url, info);
        FyrQLConnection connection = new FyrQLConnection(EngineFactory.create(settings,
                FyrQLDriver.class.getClassLoader()), true, url);
        for (String name : settings.unknownProperties()) {
            connection.addWarning(new SQLWarning("The property " + name + " is unknown and ignored; known are "
                    + String.join(", ", keyNames())));
        }
        return connection;
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

    /** Returns {@code false}: the driver only reads and has no transactions. */
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
