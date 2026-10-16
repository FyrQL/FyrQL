/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

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

    private static final class Url extends Connections {
        private final String url;
        private final Properties properties;

        Url(String url, Properties properties) {
            this.url = url;
            this.properties = properties;
        }

        @Override
        Connection open() throws SQLException {
            return DriverManager.getConnection(url, properties);
        }

        @Override
        void release(Connection connection) throws SQLException {
            connection.close();
        }

        @Override
        boolean owned() {
            return true;
        }

        /** The URL without its parameters, which may hold credentials. */
        @Override
        String describe() {
            int end = url.length();
            for (char c : new char[] {'?', ';'}) {
                int i = url.indexOf(c);
                end = i >= 0 ? Math.min(end, i) : end;
            }
            return url.substring(0, end);
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
