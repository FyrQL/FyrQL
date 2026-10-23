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
import net.fyrql.config.Connectors;
import net.fyrql.config.EngineConfig;
import net.fyrql.config.FyrQLConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * The engine of the starter, as configured under {@code fyrql}, unless the
 * application has an engine of its own or sets {@code fyrql.enabled} to
 * {@code false}. The engine closes with the context. Its connectors are
 * those that the class loader of the application finds for the types of
 * the sources.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "fyrql.enabled", matchIfMissing = true)
@EnableConfigurationProperties(FyrQLProperties.class)
public class FyrQLAutoConfiguration {

    /** The engine of the configuration, with the customizers of the application applied in their order. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public FyrQL fyrql(FyrQLProperties properties, ObjectProvider<FyrQLBuilderCustomizer> customizers,
            ResourceLoader resources) {
        ClassLoader loader = resources.getClassLoader() != null ? resources.getClassLoader()
                : FyrQL.class.getClassLoader();
        EngineConfig config = EngineConfigs.of(properties);
        return FyrQLConfig.open(config, Connectors.factories(loader), builder -> {
            builder.classLoader(loader);
            customizers.orderedStream().forEach(c -> c.customize(builder));
        });
    }
}
