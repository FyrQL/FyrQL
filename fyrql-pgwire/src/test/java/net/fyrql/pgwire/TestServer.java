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
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.jdbc.AllTypesTable;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A server on a free port of the loopback address with an engine over the
 * default source {@code mem} of memory, with the table {@code t} of
 * {@link AllTypesTable}, {@code numbers} with 1000 rows in batches of 64
 * and {@code tiny} with two rows, and the source {@code shop} over a
 * database of H2 with the schemas {@code public} (table {@code customers})
 * and {@code sales} (table {@code orders}). Closing it closes the
 * connections it opened, the server and the engine.
 */
final class TestServer implements AutoCloseable {

    static final int NUMBERS = 1000;

    private static final AtomicInteger DATABASES = new AtomicInteger();

    final FyrQL engine;
    final PgWireServer server;
    /** The connections opened, from several threads at once in some tests. */
    private final List<Connection> clients = new CopyOnWriteArrayList<>();

    TestServer() throws IOException {
        this(PgWireConfig.defaults().withPort(0), FyrQL.builder());
    }

    TestServer(PgWireConfig config) throws IOException {
        this(config, FyrQL.builder());
    }

    TestServer(PgWireConfig config, FyrQL.Builder builder) throws IOException {
        List<Object[]> numbers = new ArrayList<>();
        for (long i = 0; i < NUMBERS; i++) {
            numbers.add(new Object[] {i, "n" + i});
        }
        String h2 = "jdbc:h2:mem:pgwire" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (Connection c = DriverManager.getConnection(h2); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA sales");
            s.execute("CREATE TABLE public.customers (id INTEGER PRIMARY KEY, name VARCHAR(20))");
            s.execute("INSERT INTO public.customers VALUES (1, 'Ann'), (2, 'Bob')");
            s.execute("CREATE TABLE sales.orders (id INTEGER, customer INTEGER, amount DECIMAL(10, 2))");
            s.execute("INSERT INTO sales.orders VALUES (10, 1, 5.50), (11, 2, 7.25), (12, 1, 1.00)");
        } catch (SQLException e) {
            throw new IOException(e);
        }
        engine = builder.register(MemoryConnector.builder("mem")
                        .table("t", AllTypesTable.SCHEMA, AllTypesTable.ROWS)
                        .table("numbers", Schema.of(new Schema.Field("n", DataType.BIGINT),
                                new Schema.Field("s", DataType.VARCHAR)), numbers)
                        .table("tiny", Schema.of(new Schema.Field("k", DataType.INTEGER),
                                new Schema.Field("v", DataType.VARCHAR)), List.of(new Object[] {1, "one"},
                                new Object[] {2, "two"}))
                        .batchSize(64)
                        .build())
                .register(JdbcConnector.builder("shop").url(h2).build())
                .defaultSource("mem")
                .build();
        try {
            server = PgWireServer.builder(engine).config(config).build();
            server.start();
        } catch (IOException | RuntimeException e) {
            engine.close();
            throw e;
        }
    }

    /** Returns the URL of pgjdbc for a database with parameters such as {@code ?binaryTransfer=true}. */
    String url(String database, String parameters) {
        return "jdbc:postgresql://127.0.0.1:" + server.port() + "/" + database + parameters;
    }

    /** Opens a connection to the database {@code mem} as user {@code test}, which the server closes. */
    Connection connect() throws SQLException {
        return connect("mem", "");
    }

    /** Opens a connection as user {@code test}, which the server closes. */
    Connection connect(String database, String parameters) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "test");
        return connect(url(database, parameters), p);
    }

    Connection connect(String url, Properties properties) throws SQLException {
        Connection c = DriverManager.getConnection(url, properties);
        clients.add(c);
        return c;
    }

    /** Returns the rows of a query as lists of {@code getString}. */
    static List<List<String>> strings(ResultSet rs) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                row.add(rs.getString(i));
            }
            rows.add(row);
        }
        rs.close();
        return rows;
    }

    /** Returns the rows of a query as lists of {@code getString}. */
    static List<List<String>> query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return strings(s.executeQuery(sql));
        }
    }

    /** Returns the first value of a query as {@code getString}. */
    static String value(Connection c, String sql) throws SQLException {
        return query(c, sql).get(0).get(0);
    }

    @Override
    public void close() {
        for (Connection c : clients) {
            try {
                c.close();
            } catch (SQLException e) {
                // Closed by the test.
            }
        }
        try {
            server.close();
        } finally {
            engine.close();
        }
    }
}
