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

/**
 * Adds to the builder of the engine of the starter before it is built,
 * such as sources of the application, tables in memory or functions:
 * <pre>{@code
 * @Bean
 * FyrQLBuilderCustomizer products(ProductRepository repository) {
 *     return builder -> builder.register(MemoryConnector.builder("app")
 *             .table("products", Product.class, repository.findAll()).build());
 * }
 * }</pre>
 * The engine applies every bean of this type, in the order of their
 * {@code @Order}, after the sources and settings of the configuration. If
 * a customizer fails, the connectors of the configuration are closed; those
 * that a customizer registers are its own to close then.
 */
@FunctionalInterface
public interface FyrQLBuilderCustomizer {

    /** Adds to the builder of the engine. */
    void customize(FyrQL.Builder builder);
}
