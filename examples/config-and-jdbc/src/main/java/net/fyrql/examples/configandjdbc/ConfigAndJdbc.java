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

package net.fyrql.examples.configandjdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * FyrQL through plain JDBC: the driver builds an engine from the configuration file {@code fyrql.yaml}, which takes
 * the password of the database from the environment, and the application sees one database with the tables of all
 * sources and a view over two of them.
 */
public final class ConfigAndJdbc {

    /** The URL of the database the orders are in; fyrql.yaml names the same. */
    static final String DATABASE_URL = "jdbc:h2:./target/db/sales;DATABASE_TO_LOWER=TRUE";

    static final String FYRQL_URL = "jdbc:fyrql:?config=fyrql.yaml";

    private ConfigAndJdbc() {
    }

    public static void main(String[] args) throws SQLException {
        String password = System.getenv("SALES_PASSWORD");
        if (password == null || password.isBlank()) {
            System.err.println("Set the environment variable SALES_PASSWORD to the password of the example database,"
                    + " such as SALES_PASSWORD=example-only");
            return;
        }
        createOrders(password);

        // Nothing of FyrQL but the URL: the driver registers itself with the DriverManager.
        try (Connection connection = DriverManager.getConnection(FYRQL_URL)) {
            DatabaseMetaData metaData = connection.getMetaData();
            System.out.println("== Tables");
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[] {"TABLE", "VIEW"})) {
                while (tables.next()) {
                    System.out.println(tables.getString("TABLE_CAT") + "." + tables.getString("TABLE_SCHEM") + "."
                            + tables.getString("TABLE_NAME") + " " + tables.getString("TABLE_TYPE"));
                }
            }

            // The view of fyrql.yaml joins the orders of the database with the customers of the CSV files.
            String sql = """
                    SELECT name, orders, revenue
                    FROM customer_revenue
                    WHERE region = ?
                    ORDER BY revenue DESC""";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                // The columns of the result are known before the parameter has a value.
                ResultSetMetaData columns = statement.getMetaData();
                for (String region : List.of("north", "south")) {
                    System.out.println("== Region " + region);
                    if (region.equals("north")) {
                        for (int i = 1; i <= columns.getColumnCount(); i++) {
                            System.out.println(columns.getColumnLabel(i) + " " + columns.getColumnTypeName(i));
                        }
                    }
                    statement.setString(1, region);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            System.out.println(rows.getString("name") + " " + rows.getLong("orders") + " "
                                    + rows.getBigDecimal("revenue"));
                        }
                    }
                }
            }
        }
    }

    /** Creates the database of the example, an H2 file, with the user and password of fyrql.yaml. */
    private static void createOrders(String password) throws SQLException {
        try (Connection database = DriverManager.getConnection(DATABASE_URL, "sales", password);
                Statement statement = database.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute("""
                    CREATE TABLE orders (
                        id INTEGER PRIMARY KEY,
                        customer_id INTEGER NOT NULL,
                        amount DECIMAL(10,2) NOT NULL)""");
            statement.execute("""
                    INSERT INTO orders VALUES
                        (1, 1, 49.00), (2, 2, 149.00), (3, 3, 98.00), (4, 1, 32.50), (5, 4, 178.00),
                        (6, 5, 298.00), (7, 3, 65.00), (8, 2, 89.00), (9, 3, 16.25), (10, 4, 73.50)""");
        }
    }
}
