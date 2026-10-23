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

package net.fyrql.spring;

import net.fyrql.FyrQL;
import net.fyrql.jdbc.FyrQLDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The DataSource of the engine on request, beside that of the application, and the health of its sources. */
class FyrQLDataSourceAndHealthTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FyrQLAutoConfiguration.class,
                    FyrQLDataSourceConfiguration.class, FyrQLHealthConfiguration.class));

    /** A bean of the application that takes the DataSource by its type. */
    record Repository(DataSource dataSource) {
    }

    @BeforeEach
    void reset() {
        ProbeConnectorFactory.reset();
    }

    @Test
    void withoutTheKeyThereIsNoDataSource() {
        runner.run(context -> assertThat(context).hasSingleBean(FyrQL.class).doesNotHaveBean(DataSource.class));
    }

    @Test
    void withTheKeyTheEngineAnswersOverJdbcAndStaysOpen() {
        runner.withPropertyValues("fyrql.datasource.enabled=true").run(context -> {
            DataSource dataSource = context.getBean("fyrqlDataSource", DataSource.class);
            assertThat(dataSource).isInstanceOf(FyrQLDataSource.class);
            for (int i = 0; i < 2; i++) {
                try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery("SELECT 41 + 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(42);
                }
            }
            assertThat(context.getBean(FyrQL.class).rows("SELECT 1")).hasSize(1);
        });
    }

    @Test
    void theDataSourceOfTheApplicationStaysTheOneByType() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("fyrql.datasource.enabled=true").withBean(Repository.class).run(context -> {
                    assertThat(context).hasBean("fyrqlDataSource").hasBean("dataSource");
                    DataSource application = context.getBean(Repository.class).dataSource();
                    assertThat(application).isNotInstanceOf(FyrQLDataSource.class);
                    try (Connection c = application.getConnection()) {
                        assertThat(c.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
                    }
                });
    }

    @Test
    void theHealthIsUpWhenEverySourceAnswers() {
        runner.withPropertyValues("fyrql.sources[0].name=p1", "fyrql.sources[0].type=probe",
                "fyrql.sources[1].name=p2", "fyrql.sources[1].type=probe").run(context -> {
                    Health health = context.getBean("fyrqlHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).isEqualTo(Map.of("p1", Map.of("status", "UP"), "p2",
                            Map.of("status", "UP")));
                });
        runner.run(context -> {
            Health health = context.getBean("fyrqlHealthIndicator", HealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).isEmpty();
        });
    }

    @Test
    void theHealthIsDownWhenASourceFails() {
        runner.withPropertyValues("fyrql.sources[0].name=p1", "fyrql.sources[0].type=probe",
                "fyrql.sources[1].name=p2", "fyrql.sources[1].type=probe", "fyrql.sources[1].failing=true")
                .run(context -> {
                    Health health = context.getBean("fyrqlHealthIndicator", HealthIndicator.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).isEqualTo(Map.of("p1", Map.of("status", "UP"), "p2",
                            Map.of("status", "DOWN", "error", "java.lang.IllegalStateException: The probe p2 is"
                                    + " down")));
                });
    }

    @Test
    void theHealthIndicatorIsOffOnRequest() {
        runner.withPropertyValues("management.health.fyrql.enabled=false").run(context -> assertThat(context)
                .hasSingleBean(FyrQL.class).doesNotHaveBean("fyrqlHealthIndicator"));
        runner.withPropertyValues("management.health.defaults.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean("fyrqlHealthIndicator"));
        runner.withPropertyValues("fyrql.enabled=false", "fyrql.datasource.enabled=true").run(context -> assertThat(
                context).doesNotHaveBean("fyrqlHealthIndicator").doesNotHaveBean("fyrqlDataSource"));
    }
}
