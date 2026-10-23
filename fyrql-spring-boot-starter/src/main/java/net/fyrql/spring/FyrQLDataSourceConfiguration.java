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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * The {@code DataSource} of an engine with {@code fyrql.datasource.enabled},
 * as the bean {@code fyrqlDataSource}. It is no default candidate: it does
 * not count as the {@code DataSource} of the application, which Spring
 * Boot still creates, and is injected only by its name, such as with
 * {@code @Qualifier("fyrqlDataSource")}. Its connections share the engine
 * and do not close it.
 */
@AutoConfiguration(after = FyrQLAutoConfiguration.class)
@ConditionalOnBooleanProperty("fyrql.datasource.enabled")
@ConditionalOnBean(FyrQL.class)
class FyrQLDataSourceConfiguration {

    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "fyrqlDataSource")
    FyrQLDataSource fyrqlDataSource(FyrQL engine) {
        return new FyrQLDataSource(engine);
    }
}
