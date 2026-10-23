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

package net.fyrql.examples.federatedquery;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.json.JsonConnector;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * One query over three sources: orders in a database (H2 in memory), customers in a CSV file and products in a JSON
 * file. It prints the plan with what the database took over, the rows, and the statements the database received.
 */
public final class FederatedQuery {

    static final String URL = "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    static final String QUERY = """
            SELECT c.region, p.name AS product, sum(o.quantity) AS units,
                   sum(o.quantity * p.price) AS revenue
            FROM shop.orders AS o
            JOIN files.customers AS c ON o.customer_id = c.id
            JOIN catalog.products AS p ON o.sku = p.sku
            WHERE o.status = 'shipped' AND o.order_date >= DATE '2026-02-01'
            GROUP BY c.region, p.name
            ORDER BY c.region, product""";

    private FederatedQuery() {
    }

    public static void main(String[] args) throws SQLException {
        // The database the orders live in; any database with a JDBC driver would do.
        try (Connection database = DriverManager.getConnection(URL, "sa", "")) {
            createOrders(database);

            JdbcConnector shop = JdbcConnector.builder("shop").url(URL).user("sa").password("")
                    .recordStatements(true).build();
            try (FyrQL engine = FyrQL.builder()
                    .register(shop)
                    .register(CsvConnector.builder("files").directory(Path.of("data")).build())
                    .register(JsonConnector.builder("catalog").directory(Path.of("data")).build())
                    .threads(4)
                    .build()) {
                System.out.println("== Plan");
                System.out.print(engine.explain(QUERY));

                System.out.println("== Rows");
                for (Object[] row : engine.rows(QUERY)) {
                    System.out.println(Arrays.toString(row));
                }

                System.out.println("== Statements the database received");
                for (String statement : shop.statements()) {
                    System.out.println(statement);
                }
            }
        }
    }

    private static void createOrders(Connection database) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute("""
                    CREATE TABLE orders (
                        id INTEGER PRIMARY KEY,
                        customer_id INTEGER NOT NULL,
                        sku VARCHAR(10) NOT NULL,
                        quantity INTEGER NOT NULL,
                        status VARCHAR(10) NOT NULL,
                        order_date DATE NOT NULL)""");
            statement.execute("""
                    INSERT INTO orders VALUES
                        (1, 1, 'p1', 2, 'shipped', DATE '2026-01-12'),
                        (2, 2, 'p2', 1, 'shipped', DATE '2026-01-20'),
                        (3, 3, 'p1', 4, 'shipped', DATE '2026-02-03'),
                        (4, 1, 'p3', 10, 'shipped', DATE '2026-02-07'),
                        (5, 4, 'p4', 2, 'open', DATE '2026-02-11'),
                        (6, 5, 'p2', 2, 'shipped', DATE '2026-02-14'),
                        (7, 6, 'p3', 20, 'shipped', DATE '2026-02-21'),
                        (8, 2, 'p4', 1, 'shipped', DATE '2026-03-02'),
                        (9, 3, 'p3', 5, 'cancelled', DATE '2026-03-05'),
                        (10, 4, 'p1', 3, 'shipped', DATE '2026-03-09'),
                        (11, 5, 'p4', 1, 'shipped', DATE '2026-03-15'),
                        (12, 1, 'p1', 1, 'shipped', DATE '2026-03-22')""");
        }
    }
}
