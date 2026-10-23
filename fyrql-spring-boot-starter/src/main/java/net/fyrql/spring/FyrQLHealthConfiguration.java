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
import net.fyrql.config.SourceConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * The health indicator {@code fyrql} of an engine, when Spring Boot has
 * health on its class path; {@code management.health.fyrql.enabled} turns
 * it off.
 */
@AutoConfiguration(after = FyrQLAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(FyrQL.class)
@ConditionalOnEnabledHealthIndicator("fyrql")
class FyrQLHealthConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "fyrqlHealthIndicator")
    HealthIndicator fyrqlHealthIndicator(FyrQL engine, ObjectProvider<FyrQLProperties> properties) {
        FyrQLProperties p = properties.getIfAvailable();
        List<String> sources = p == null ? List.of()
                : EngineConfigs.of(p).sources().stream().map(SourceConfig::name).toList();
        return new FyrQLHealthIndicator(engine, sources);
    }
}
