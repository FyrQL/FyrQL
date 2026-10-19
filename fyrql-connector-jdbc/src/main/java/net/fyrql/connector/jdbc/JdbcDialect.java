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

import net.fyrql.sql.render.Dialect;
import net.fyrql.sql.render.H2Dialect;
import net.fyrql.sql.render.MySqlDialect;
import net.fyrql.sql.render.PostgresDialect;
import net.fyrql.sql.render.SqliteDialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The databases the connector knows: how it writes their SQL, how it finds
 * their schemas, and how it reads a result without holding all of it in
 * memory.
 */
public enum JdbcDialect {

    /** H2 2: schemas, a default schema {@code PUBLIC}. */
    H2(H2Dialect.INSTANCE, false, Set.of("INFORMATION_SCHEMA")),

    /**
     * SQLite: one schema {@code main}; a column has the type it was
     * declared with, read as FyrQL reads a type name, see
     * {@link JdbcTypes}, and a value that is not of that type is an error.
     */
    SQLITE(SqliteDialect.INSTANCE, false, Set.of()),

    /**
     * PostgreSQL: schemas, a default schema {@code public}; a result is
     * read through a cursor, which needs a transaction, so a connection
     * of the connector's own leaves auto-commit while it reads.
     */
    POSTGRESQL(PostgresDialect.INSTANCE, false, Set.of("information_schema", "pg_catalog", "pg_toast")),

    /**
     * MySQL 8 and MariaDB: the databases of the server are the schemas,
     * the database of the URL is the default; a result is streamed row by
     * row on a connection of the connector's own. A connection from a
     * {@code jdbc:mysql:} URL reads a {@code TIMESTAMP} in the time zone of
     * the session
     * ({@code connectionTimeZone=SERVER}) unless the URL or a property sets
     * another; a data source or a shared connection is used as configured.
     */
    MYSQL(MySqlDialect.INSTANCE, true, Set.of("information_schema", "mysql", "performance_schema", "sys"));

    private final Dialect sql;
    private final boolean catalogsAsSchemas;
    private final Set<String> systemSchemas;

    JdbcDialect(Dialect sql, boolean catalogsAsSchemas, Set<String> systemSchemas) {
        this.sql = sql;
        this.catalogsAsSchemas = catalogsAsSchemas;
        this.systemSchemas = systemSchemas;
    }

    /** Returns the dialect of the SQL the connector sends. */
    public Dialect sql() {
        return sql;
    }

    /** Tells whether the schemas of the database are the catalogs of JDBC, as in MySQL. */
    boolean catalogsAsSchemas() {
        return catalogsAsSchemas;
    }

    /** Tells whether a schema belongs to the database itself and is not listed. */
    boolean isSystemSchema(String schema) {
        return systemSchemas.contains(schema) || this == POSTGRESQL && schema.startsWith("pg_");
    }

    /** Returns the dialect of a JDBC URL, if it is one of the known databases. */
    public static Optional<JdbcDialect> ofUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:h2:")) {
            return Optional.of(H2);
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return Optional.of(SQLITE);
        }
        if (u.startsWith("jdbc:postgresql:")) {
            return Optional.of(POSTGRESQL);
        }
        if (u.startsWith("jdbc:mysql:") || u.startsWith("jdbc:mariadb:")) {
            return Optional.of(MYSQL);
        }
        return Optional.empty();
    }

    /** Returns the dialect of a product name as the JDBC metadata gives it, if it is one of the known databases. */
    public static Optional<JdbcDialect> ofProductName(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        if (p.equals("h2")) {
            return Optional.of(H2);
        }
        if (p.equals("sqlite")) {
            return Optional.of(SQLITE);
        }
        if (p.equals("postgresql")) {
            return Optional.of(POSTGRESQL);
        }
        if (p.equals("mysql") || p.equals("mariadb")) {
            return Optional.of(MYSQL);
        }
        return Optional.empty();
    }

    /** Returns the dialect by its name, as in a configuration: {@code h2}, {@code sqlite}, {@code postgresql}, {@code mysql}. */
    public static JdbcDialect ofName(String name) {
        for (JdbcDialect d : values()) {
            if (d.sql.name().equalsIgnoreCase(name) || d.name().equalsIgnoreCase(name)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown dialect " + name + "; known are h2, sqlite, postgresql, mysql");
    }

    /**
     * Creates a statement that reads a result in parts of the fetch size
     * instead of all at once, as far as the driver allows: PostgreSQL
     * needs a transaction for that, MySQL streams row by row. On a
     * connection the caller shares, nothing about the connection is
     * changed.
     *
     * @param owned whether the connection is the connector's own, opened for this one statement
     */
    Statement prepare(Connection connection, int fetchSize, boolean owned) throws SQLException {
        if (this == POSTGRESQL && owned && connection.getAutoCommit()) {
            connection.setAutoCommit(false);
        }
        Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        if (this == MYSQL) {
            if (owned) {
                statement.setFetchSize(Integer.MIN_VALUE);
            }
        } else {
            statement.setFetchSize(fetchSize);
        }
        return statement;
    }
}
