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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A driver of a database FyrQL has no dialect for: the URLs
 * {@code jdbc:fake:<h2 url>} connect to H2, whose metadata then calls the
 * product {@value #PRODUCT}. With {@code jdbc:fake-nocolumns:} the metadata
 * lists no columns either, as some drivers do for some tables.
 */
public final class FakeProductDriver implements Driver {

    static final String PREFIX = "jdbc:fake:";
    static final String NO_COLUMNS = "jdbc:fake-nocolumns:";
    static final String PRODUCT = "Fake DB";

    static {
        try {
            DriverManager.registerDriver(new FakeProductDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        boolean noColumns = url.startsWith(NO_COLUMNS);
        String h2 = "jdbc:h2:" + url.substring(noColumns ? NO_COLUMNS.length() : PREFIX.length());
        Connection real = new org.h2.Driver().connect(h2, info);
        Connection[] proxy = new Connection[1];
        InvocationHandler metadata = (p, m, args) -> {
            DatabaseMetaData md = real.getMetaData();
            return switch (m.getName()) {
                case "getDatabaseProductName" -> PRODUCT;
                case "getConnection" -> proxy[0];
                case "getColumns" -> noColumns ? md.getColumns(null, null, "NO_SUCH_TABLE", null) : call(md, m, args);
                default -> call(md, m, args);
            };
        };
        proxy[0] = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (p, m, args) -> m.getName().equals("getMetaData")
                        ? Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[] {DatabaseMetaData.class}, metadata)
                        : call(real, m, args));
        return proxy[0];
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && (url.startsWith(PREFIX) || url.startsWith(NO_COLUMNS));
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
