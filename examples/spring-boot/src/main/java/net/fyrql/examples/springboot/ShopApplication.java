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

import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.spring.FyrQLBuilderCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * An application of Spring Boot whose engine of FyrQL the starter builds from {@code application.yml}, with the
 * database of the application as one more source.
 */
@SpringBootApplication
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }

    /**
     * Registers the database of the application as the source {@code shop}, before the starter builds the engine;
     * after the database is filled from schema.sql and data.sql.
     */
    @Bean
    @DependsOnDatabaseInitialization
    FyrQLBuilderCustomizer shopDatabase(DataSource dataSource) {
        return builder -> builder.register(JdbcConnector.builder("shop").dataSource(dataSource).build());
    }
}
