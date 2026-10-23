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

package net.fyrql.connector.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Where a source gets its connections: one per statement from a URL or a
 * data source, which the connector closes when the statement is read, or
 * one connection the application shares and closes itself.
 */
abstract sealed class Connections {

    /** Returns a connection for one statement or one look into the metadata. */
    abstract Connection open() throws SQLException;

    /** Gives a connection back after use. */
    abstract void release(Connection connection) throws SQLException;

    /** Tells whether a connection is opened for one use and closed after it, so its settings may change. */
    abstract boolean owned();

    /** A description for messages, without credentials. */
    abstract String describe();

    static Connections url(String url, Properties properties) {
        return new Url(url, properties);
    }

    static Connections dataSource(DataSource dataSource) {
        return new FromDataSource(dataSource);
    }

    static Connections shared(Connection connection) {
        return new Shared(connection);
    }

    /**
     * Connections from the {@link DriverManager}, or else from a driver in
     * the class loader of the connector that is not registered with it: a
     * tool such as DBeaver loads the drivers of the sources next to FyrQL
     * in a class loader of its own, without registering them.
     */
    private static final class Url extends Connections {
        private final String url;
        private final Properties properties;
        /** The driver that is not registered, once it is found. */
        private volatile Driver unregistered;

        Url(String url, Properties properties) {
            this.url = url;
            this.properties = properties;
        }

        /**
         * Asks the DriverManager for a driver before it connects: a failure
         * of {@code getConnection} does not tell a URL that no driver
         * accepts from a driver that fails.
         */
        @Override
        Connection open() throws SQLException {
            try {
                return connect();
            } catch (SQLException e) {
                throw withoutUrl(e);
            }
        }

        private Connection connect() throws SQLException {
            Driver driver = unregistered;
            if (driver == null && !registered()) {
                driver = unregisteredDriver();
                unregistered = driver;
            }
            if (driver == null) {
                return DriverManager.getConnection(url, properties);
            }
            Connection c = driver.connect(url, properties);
            if (c == null) {
                throw new SQLException("The driver " + driver.getClass().getName() + " does not connect to "
                        + describe(), "08001");
            }
            return c;
        }

        /**
         * Returns the exception, or a copy with the URL replaced by its
         * description if the message of the exception or of one of its causes
         * holds the URL, as that of the DriverManager does when no driver
         * accepts it. The copy keeps the state, the code and the stack trace,
         * but not the causes, whose messages may hold the credentials.
         */
        private SQLException withoutUrl(SQLException e) {
            boolean shown = false;
            Throwable t = e;
            for (int depth = 0; t != null && depth < 32 && !shown; depth++, t = t.getCause()) {
                shown = t.getMessage() != null && t.getMessage().contains(url);
            }
            if (!shown) {
                return e;
            }
            String message = e.getMessage() == null ? null : e.getMessage().replace(url, describe());
            SQLException copy = new SQLException(message, e.getSQLState(), e.getErrorCode());
            copy.setStackTrace(e.getStackTrace());
            return copy;
        }

        /** Tells whether a driver of the DriverManager accepts the URL. */
        private boolean registered() {
            try {
                DriverManager.getDriver(url);
                return true;
            } catch (SQLException e) {
                return false;
            }
        }

        /** Returns the first driver of the class loader of the connector that accepts the URL, or null. */
        private Driver unregisteredDriver() throws SQLException {
            Iterator<Driver> drivers = ServiceLoader.load(Driver.class, Connections.class.getClassLoader()).iterator();
            while (hasNext(drivers)) {
                Driver driver;
                try {
                    driver = drivers.next();
                } catch (ServiceConfigurationError e) {
                    // A driver that cannot be created is none for this URL either.
                    continue;
                }
                if (driver.acceptsURL(url)) {
                    return driver;
                }
            }
            return null;
        }

        /** Tells whether there is another driver, and stops at a broken list of drivers. */
        private static boolean hasNext(Iterator<Driver> drivers) {
            try {
                return drivers.hasNext();
            } catch (ServiceConfigurationError e) {
                return false;
            }
        }

        @Override
        void release(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        boolean owned() {
            return true;
        }

        /**
         * The URL without its parameters and without a user and password
         * before an {@code @}, as in {@code jdbc:mysql://user:password@host}
         * or {@code jdbc:oracle:thin:user/password@host}.
         */
        @Override
        String describe() {
            return describe(url);
        }

        static String describe(String url) {
            int end = url.length();
            for (char c : new char[] {'?', ';'}) {
                int i = url.indexOf(c);
                end = i >= 0 ? Math.min(end, i) : end;
            }
            String shown = url.substring(0, end);
            int at = shown.indexOf('@');
            if (at < 0) {
                return shown;
            }
            int slashes = shown.indexOf("//");
            if (slashes >= 0 && slashes < at) {
                int authority = slashes + 2;
                int path = shown.indexOf('/', authority);
                return path >= 0 && path < at ? shown : shown.substring(0, authority) + shown.substring(at + 1);
            }
            int colon = shown.lastIndexOf(':', at);
            return colon < 0 ? shown : shown.substring(0, colon + 1) + shown.substring(at);
        }
    }

    private static final class FromDataSource extends Connections {
        private final DataSource dataSource;

        FromDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        Connection open() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        void release(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        boolean owned() {
            return true;
        }

        @Override
        String describe() {
            return "a data source";
        }
    }

    private static final class Shared extends Connections {
        private final Connection connection;

        Shared(Connection connection) {
            this.connection = connection;
        }

        @Override
        Connection open() {
            return connection;
        }

        @Override
        void release(Connection c) {
            // The application closes the connection it shares.
        }

        @Override
        boolean owned() {
            return false;
        }

        @Override
        String describe() {
            return "a shared connection";
        }
    }
}
