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

package net.fyrql.examples.movedata;

import net.fyrql.FyrQL;
import net.fyrql.connector.jdbc.JdbcConnector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Moves data between a database and files with SQL: the orders of a database into a Parquet file, that file back
 * into a new table of the database, and a CSV file into an existing table.
 */
public final class MoveData {

    static final String URL = "jdbc:h2:mem:warehouse;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    private MoveData() {
    }

    public static void main(String[] args) throws SQLException, IOException {
        // COPY ... TO writes into an existing directory.
        Files.createDirectories(Path.of("target", "out"));
        try (Connection database = DriverManager.getConnection(URL, "sa", "")) {
            createTables(database);

            try (FyrQL engine = FyrQL.builder()
                    .register(JdbcConnector.builder("shop").url(URL).user("sa").password("").build())
                    .threads(4)
                    .build()) {
                // 1. The orders of the database into a Parquet file, compressed with zstd.
                long exported = engine.update("""
                        COPY (SELECT id, customer_id, amount, order_date FROM shop.orders ORDER BY id)
                        TO 'target/out/orders.parquet' (compression zstd)""");
                System.out.println("Exported " + exported + " orders to target/out/orders.parquet");

                // 2. The file is a table of its own; its name is enough, and it keeps the types.
                print(engine, "The Parquet file", """
                        SELECT count(*) AS orders, sum(amount) AS total,
                               CAST(min(order_date) AS VARCHAR) AS first, CAST(max(order_date) AS VARCHAR) AS last
                        FROM 'target/out/orders.parquet'""");

                // 3. The large orders of the file into a new table of the database.
                String createBigOrders = """
                        CREATE TABLE shop.big_orders AS
                        SELECT id, customer_id, amount FROM 'target/out/orders.parquet' WHERE amount >= 100""";
                System.out.println("== Plan");
                System.out.print(engine.explain(createBigOrders));
                long created = engine.update(createBigOrders);
                System.out.println("Created shop.big_orders with " + created + " rows");
                print(engine, "shop.big_orders", "SELECT * FROM shop.big_orders ORDER BY id");

                // 4. A CSV file into an existing table, read with the types of its columns.
                long loaded = engine.update("COPY shop.archive FROM 'data/orders-2025.csv'");
                System.out.println("Loaded " + loaded + " orders of 2025 into shop.archive");
                print(engine, "Orders by year in shop.orders and shop.archive", """
                        SELECT year(order_date) AS year, count(*) AS orders, sum(amount) AS total
                        FROM (SELECT order_date, amount FROM shop.orders
                              UNION ALL
                              SELECT order_date, amount FROM shop.archive)
                        GROUP BY ALL
                        ORDER BY year""");
            }
        }
    }

    private static void print(FyrQL engine, String title, String query) {
        System.out.println("== " + title);
        for (Object[] row : engine.rows(query)) {
            System.out.println(Arrays.toString(row));
        }
    }

    private static void createTables(Connection database) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute("DROP TABLE IF EXISTS big_orders");
            statement.execute("DROP TABLE IF EXISTS archive");
            statement.execute("""
                    CREATE TABLE orders (
                        id INTEGER PRIMARY KEY,
                        customer_id INTEGER NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        order_date DATE NOT NULL)""");
            statement.execute("""
                    INSERT INTO orders VALUES
                        (1, 1, 49.00, DATE '2026-01-12'),
                        (2, 2, 149.00, DATE '2026-01-20'),
                        (3, 3, 98.00, DATE '2026-02-03'),
                        (4, 1, 32.50, DATE '2026-02-07'),
                        (5, 4, 178.00, DATE '2026-02-11'),
                        (6, 5, 298.00, DATE '2026-02-14'),
                        (7, 6, 65.00, DATE '2026-02-21'),
                        (8, 2, 89.00, DATE '2026-03-02'),
                        (9, 3, 16.25, DATE '2026-03-05'),
                        (10, 4, 73.50, DATE '2026-03-09')""");
            statement.execute("""
                    CREATE TABLE archive (
                        id INTEGER PRIMARY KEY,
                        customer_id INTEGER NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        order_date DATE NOT NULL)""");
        }
    }
}
