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

import net.fyrql.FyrQL;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcCatalogTest {

    @Test
    void theDefaultSchemaIsListedAsMain() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE p (n INTEGER)", "INSERT INTO p VALUES (1)", "CREATE SCHEMA sales",
                "CREATE TABLE sales.orders (n INTEGER)", "INSERT INTO sales.orders VALUES (2), (3)",
                "CREATE VIEW sales.big_orders AS SELECT n FROM sales.orders WHERE n > 2");
        JdbcConnector connector = db.connector("h2");
        assertEquals(List.of(TableRef.MAIN, "SALES"), connector.catalog().schemas());
        assertEquals(List.of("BIG_ORDERS", "ORDERS"), connector.catalog().tables("SALES"));
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(1, engine.rows("SELECT * FROM h2.p").size());
            assertEquals(1, engine.rows("SELECT * FROM p").size());
            assertEquals(2, engine.rows("SELECT * FROM h2.sales.orders").size());
            assertEquals(1, engine.rows("SELECT * FROM sales.big_orders").size());
        }
        JdbcConnector sales = JdbcConnector.builder("h2").url(db.url()).defaultSchema("SALES").build();
        assertEquals(List.of(TableRef.MAIN, "PUBLIC"), sales.catalog().schemas());
        assertEquals(List.of("BIG_ORDERS", "ORDERS"), sales.catalog().tables(TableRef.MAIN));
        JdbcConnector only = JdbcConnector.builder("h2").url(db.url()).schemas(List.of()).build();
        assertEquals(List.of(TableRef.MAIN), only.catalog().schemas());
        assertEquals(List.of(), only.catalog().tables("SALES"));

        assertEquals(OptionalLong.of(1), connector.catalog().rowCount(TableRef.of("h2", "P")));
        assertEquals(OptionalLong.of(2), connector.catalog().rowCount(new TableRef("h2", "SALES", "ORDERS")));
        db.execute("CREATE TABLE q (n INTEGER)", "INSERT INTO p VALUES (2)");
        assertEquals(List.of("P"), connector.catalog().tables(TableRef.MAIN));
        // The count is kept until the catalog is refreshed.
        assertEquals(OptionalLong.of(1), connector.catalog().rowCount(TableRef.of("h2", "P")));
        connector.refresh();
        assertEquals(List.of("P", "Q"), connector.catalog().tables(TableRef.MAIN));
        assertEquals(OptionalLong.of(2), connector.catalog().rowCount(TableRef.of("h2", "P")));
        assertEquals("No table h2.main.nope in the JDBC source h2", assertThrows(IllegalArgumentException.class,
                () -> connector.catalog().rowCount(TableRef.of("h2", "nope"))).getMessage());
    }

    @Test
    void sqliteListsItsTablesAndViewsWithTheirDeclaredTypes() {
        TestDatabase db = TestDatabase.create(JdbcDialect.SQLITE);
        db.execute("CREATE TABLE \"Mixed Case\" (id INTEGER PRIMARY KEY, name TEXT NOT NULL, price DECIMAL(8,2), "
                + "seen DATETIME, raw)", "CREATE VIEW cheap AS SELECT id FROM \"Mixed Case\" WHERE price < 10",
                "CREATE TABLE seq (n INTEGER PRIMARY KEY AUTOINCREMENT)", "INSERT INTO seq DEFAULT VALUES");
        JdbcConnector connector = db.connector("lite");
        assertEquals(List.of(TableRef.MAIN), connector.catalog().schemas());
        // SQLite's own table of the autoincrement counters is not listed.
        assertEquals(List.of("Mixed Case", "cheap", "seq"), connector.catalog().tables(TableRef.MAIN));
        assertEquals(new Schema(List.of(new Schema.Field("id", DataType.BIGINT, true),
                new Schema.Field("name", DataType.VARCHAR, false), new Schema.Field("price", DataType.decimal(8, 2), true),
                new Schema.Field("seen", DataType.TIMESTAMP, true), new Schema.Field("raw", DataType.VARCHAR, true))),
                connector.catalog().schema(TableRef.of("lite", "Mixed Case")));
        assertEquals(List.of(), connector.catalog().tables("other"));
        try (FyrQL engine = FyrQL.builder().register(connector).build()) {
            assertEquals(0, engine.rows("SELECT * FROM \"Mixed Case\"").size());
            assertEquals(1, engine.rows("SELECT * FROM lite.seq").size());
        }
        assertEquals(List.of("id"), connector.catalog().primaryKey(TableRef.of("lite", "Mixed Case")));
        assertEquals(List.of(), connector.catalog().primaryKey(TableRef.of("lite", "cheap")));
    }

    @Test
    void thePrimaryKeyHasItsColumnsInTheOrderOfTheKey() {
        TestDatabase db = TestDatabase.create(JdbcDialect.H2);
        db.execute("CREATE TABLE k (a INTEGER, b VARCHAR(10), c INTEGER, PRIMARY KEY (c, a))",
                "CREATE TABLE one (id INTEGER PRIMARY KEY, v INTEGER)", "CREATE TABLE none (v INTEGER)");
        JdbcConnector connector = db.connector("h2");
        assertEquals(List.of("C", "A"), connector.catalog().primaryKey(TableRef.of("h2", "K")));
        assertEquals(List.of("ID"), connector.catalog().primaryKey(TableRef.of("h2", "ONE")));
        assertEquals(List.of(), connector.catalog().primaryKey(TableRef.of("h2", "NONE")));

        TestDatabase lite = TestDatabase.create(JdbcDialect.SQLITE);
        lite.execute("CREATE TABLE k (a INTEGER, b TEXT, c INTEGER, PRIMARY KEY (c, a))");
        assertEquals(List.of("c", "a"), lite.connector("lite").catalog().primaryKey(TableRef.of("lite", "k")));
    }
}
