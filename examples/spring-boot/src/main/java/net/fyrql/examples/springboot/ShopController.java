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

package net.fyrql.examples.springboot;

import net.fyrql.FyrQL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;

/** Answers over the engine of FyrQL: once through the bean of the engine, once through its DataSource. */
@RestController
public class ShopController {

    /** The revenue of a region. */
    public record Revenue(String region, long orders, BigDecimal revenue) {
    }

    /** A customer of a region. */
    public record Customer(long id, String name) {
    }

    private final FyrQL engine;
    private final JdbcTemplate fyrql;

    /**
     * Takes the engine, and its DataSource by the name of its bean: by its type, a DataSource is still that of the
     * application.
     */
    public ShopController(FyrQL engine, @Qualifier("fyrqlDataSource") DataSource fyrqlDataSource) {
        this.engine = engine;
        this.fyrql = new JdbcTemplate(fyrqlDataSource);
    }

    /** The orders of the database with the customers of the CSV file, in one query. */
    @GetMapping("/revenue")
    public List<Revenue> revenue() {
        return engine.rows("""
                SELECT c.region, count(*) AS orders, sum(o.amount) AS revenue
                FROM shop.orders AS o JOIN files.customers AS c ON o.customer_id = c.id
                GROUP BY c.region
                ORDER BY revenue DESC""").stream()
                .map(row -> new Revenue((String) row[0], (Long) row[1], (BigDecimal) row[2]))
                .toList();
    }

    /** The customers of a region, through JDBC with a parameter. */
    @GetMapping("/customers")
    public List<Customer> customers(@RequestParam String region) {
        return fyrql.query("SELECT id, name FROM files.customers WHERE region = ? ORDER BY id",
                (rs, i) -> new Customer(rs.getLong("id"), rs.getString("name")), region);
    }
}
