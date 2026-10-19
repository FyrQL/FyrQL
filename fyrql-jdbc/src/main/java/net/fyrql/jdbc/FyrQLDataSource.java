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

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A data source of FyrQL connections: either by a URL and properties, as
 * {@link FyrQLDriver} takes them, each connection with an engine of its
 * own; or over one engine of the application, which the connections share
 * and do not close.
 */
public final class FyrQLDataSource implements DataSource {

    private final FyrQL engine;
    private final Properties properties = new Properties();
    private volatile String url = UrlSettings.PREFIX;
    private volatile PrintWriter logWriter;
    private volatile int loginTimeout;

    /** A data source of the URL {@code jdbc:fyrql:}, until {@link #setUrl} sets another. */
    public FyrQLDataSource() {
        this.engine = null;
    }

    /** A data source of a URL of the form {@code jdbc:fyrql:?config=fyrql.yaml}. */
    public FyrQLDataSource(String url) {
        this.engine = null;
        setUrl(url);
    }

    /** A data source whose connections share an engine of the application, which they do not close. */
    public FyrQLDataSource(FyrQL engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Returns the URL of the connections, or {@code null} if they share an engine. */
    public String getUrl() {
        return engine == null ? url : null;
    }

    /**
     * Sets the URL of the connections.
     *
     * @throws IllegalArgumentException if the URL is not one of FyrQL
     * @throws IllegalStateException if the connections share an engine
     */
    public void setUrl(String url) {
        if (engine != null) {
            throw new IllegalStateException("The connections of this data source share an engine");
        }
        if (!UrlSettings.accepts(Objects.requireNonNull(url, "url"))) {
            throw new IllegalArgumentException("Not a URL of FyrQL: " + url);
        }
        this.url = url;
    }

    /** Sets a property of the connections, such as {@code config}; {@code null} removes it. */
    public void setProperty(String name, String value) {
        synchronized (properties) {
            if (value == null) {
                properties.remove(name);
            } else {
                properties.setProperty(name, value);
            }
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (engine != null) {
            return FyrQLDriver.connect(engine, false);
        }
        Properties copy = new Properties();
        synchronized (properties) {
            copy.putAll(properties);
        }
        return new FyrQLDriver().connect(url, copy);
    }

    /** Opens a connection; FyrQL has no users, so both are ignored. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("net.fyrql.jdbc");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The data source does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
