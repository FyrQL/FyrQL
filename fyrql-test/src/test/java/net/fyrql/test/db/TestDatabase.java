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

package net.fyrql.test.db;

import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.SqlRenderer;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A database the tests of the JDBC connector run against: where it is,
 * how to log in, and its dialect.
 *
 * @param user the user, or {@code null} for none
 * @param password the password, or {@code null} for none
 */
public record TestDatabase(JdbcDialect dialect, String url, String user, String password) {

    private static final int INSERT_CHUNK = 500;

    /** Opens a connection of the test's own. */
    public Connection connect() throws SQLException {
        Properties p = new Properties();
        if (user != null) {
            p.setProperty("user", user);
        }
        if (password != null) {
            p.setProperty("password", password);
        }
        return DriverManager.getConnection(url, p);
    }

    /** A connector over the database under a name, which records its statements. */
    public JdbcConnector connector(String name) {
        return connector(name, b -> {
        });
    }

    /** A connector over the database under a name, which records its statements, with further settings. */
    public JdbcConnector connector(String name, Consumer<JdbcConnector.Builder> settings) {
        JdbcConnector.Builder b = JdbcConnector.builder(name).url(url).dialect(dialect).recordStatements(true);
        settings.accept(b);
        if (user != null) {
            b.user(user);
        }
        if (password != null) {
            b.password(password);
        }
        return b.build();
    }

    /** Runs statements in one connection. */
    public void execute(String... sql) {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(dialect + " rejects: " + String.join("; ", sql), e);
        }
    }

    /**
     * Creates a table in the default schema under a name, quoted as given,
     * with the types the dialect writes for the schema, and fills it with
     * rows of Java values; an existing table of the name is dropped first.
     */
    public void load(String table, Schema schema, List<Object[]> rows) {
        SqlRenderer renderer = new SqlRenderer(dialect.sql(), t -> {
            throw new UnsupportedOperationException("The renderer only writes tables here");
        });
        String name = dialect.sql().quoteIdentifier(table);
        List<String> statements = new ArrayList<>();
        statements.add("DROP TABLE IF EXISTS " + name);
        statements.add(renderer.createTable(name, schema));
        for (int from = 0; from < rows.size(); from += INSERT_CHUNK) {
            statements.add(renderer.insert(name, schema, rows.subList(from, Math.min(rows.size(), from + INSERT_CHUNK))));
        }
        execute(statements.toArray(String[]::new));
    }

    /**
     * Creates a table in the default schema under a name in double quotes,
     * with the type names given, and fills it with rows of Java values
     * through a prepared statement, dates and timestamps with a calendar in
     * UTC, as the generic dialect reads them; an existing table of the name
     * is dropped first. For a database without a dialect of its own, whose
     * types the dialect of FyrQL does not write.
     */
    public void load(String table, Schema schema, Function<DataType, String> typeNames, List<Object[]> rows) {
        String name = "\"" + table + "\"";
        StringBuilder create = new StringBuilder("CREATE TABLE ").append(name).append(" (");
        StringBuilder insert = new StringBuilder("INSERT INTO ").append(name).append(" VALUES (");
        for (int i = 0; i < schema.size(); i++) {
            create.append(i > 0 ? ", " : "").append('"').append(schema.name(i)).append("\" ")
                    .append(typeNames.apply(schema.type(i))).append(schema.nullable(i) ? "" : " NOT NULL");
            insert.append(i > 0 ? ", ?" : "?");
        }
        try (Connection c = connect()) {
            try (Statement s = c.createStatement()) {
                try {
                    s.execute("DROP TABLE " + name);
                } catch (SQLException e) {
                    // Not every database has DROP TABLE IF EXISTS; the table did not exist.
                }
                s.execute(create.append(')').toString());
            }
            Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            ((GregorianCalendar) utc).setGregorianChange(new Date(Long.MIN_VALUE));
            try (PreparedStatement p = c.prepareStatement(insert.append(')').toString())) {
                for (int r = 0; r < rows.size(); r++) {
                    for (int i = 0; i < schema.size(); i++) {
                        bind(p, i + 1, schema.type(i), typeNames.apply(schema.type(i)), rows.get(r)[i], utc);
                    }
                    p.addBatch();
                    if (r % INSERT_CHUNK == INSERT_CHUNK - 1 || r == rows.size() - 1) {
                        p.executeBatch();
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(url + " does not load " + table, e);
        }
    }

    private static void bind(PreparedStatement p, int i, DataType type, String typeName, Object v, Calendar utc)
            throws SQLException {
        if (v == null) {
            int sqlType;
            try {
                sqlType = p.getParameterMetaData().getParameterType(i);
            } catch (SQLException e) {
                sqlType = Types.VARCHAR;
            }
            p.setNull(i, sqlType);
            return;
        }
        switch (v) {
            case Boolean b -> p.setBoolean(i, b);
            case Byte b -> p.setByte(i, b);
            case Short s -> p.setShort(i, s);
            case Integer n when type == DataType.DATE -> p.setDate(i, new java.sql.Date(n * 86_400_000L), utc);
            case Integer n -> p.setInt(i, n);
            case Long n when type == DataType.TIMESTAMP -> {
                Timestamp t = new Timestamp(Math.floorDiv(n, 1_000_000L) * 1000);
                t.setNanos((int) Math.floorMod(n, 1_000_000L) * 1000);
                p.setTimestamp(i, t, utc);
            }
            case Long n -> p.setLong(i, n);
            case BigInteger b -> p.setBigDecimal(i, new BigDecimal(b));
            case BigDecimal d -> p.setBigDecimal(i, d);
            case Float f -> p.setFloat(i, f);
            case Double d -> p.setDouble(i, d);
            case String s -> p.setString(i, s);
            case byte[] b -> p.setBytes(i, b);
            case UUID u when typeName.equals("UUID") -> p.setObject(i, u);
            case UUID u -> p.setString(i, u.toString());
            default -> throw new IllegalArgumentException("No value of a column: " + v);
        }
    }

    /**
     * Creates a table in the default schema of PostgreSQL as {@link #load}
     * does and fills it from a CSV file with a header and a delimiter,
     * through {@code COPY}; an empty unquoted field is {@code NULL}.
     *
     * @throws IllegalStateException if the database is not PostgreSQL or rejects the file
     */
    public void copy(String table, Schema schema, Path csv, char delimiter) {
        if (dialect != JdbcDialect.POSTGRESQL) {
            throw new IllegalStateException("COPY needs PostgreSQL, not " + dialect);
        }
        SqlRenderer renderer = new SqlRenderer(dialect.sql(), t -> {
            throw new UnsupportedOperationException("The renderer only writes tables here");
        });
        String name = dialect.sql().quoteIdentifier(table);
        execute("DROP TABLE IF EXISTS " + name, renderer.createTable(name, schema));
        try (Connection c = connect(); Reader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            c.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY " + name + " FROM STDIN WITH (FORMAT csv, HEADER, "
                    + "DELIMITER '" + delimiter + "')", in);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("PostgreSQL does not copy " + csv + " into " + table, e);
        }
    }
}
