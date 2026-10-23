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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The health of the engine: up if each source of the configuration lists
 * its schemas, which reaches a database or service behind it, down if one
 * fails; without such sources, up if the engine answers {@code SELECT 1}.
 * The details hold the status of each source and the error of one that
 * failed.
 */
final class FyrQLHealthIndicator implements HealthIndicator {

    private final FyrQL engine;

    /** The sources of the configuration. */
    private final List<String> sources;

    FyrQLHealthIndicator(FyrQL engine, List<String> sources) {
        this.engine = engine;
        this.sources = List.copyOf(sources);
    }

    @Override
    public Health health() {
        List<String> checked = sources.stream().filter(engine.sources()::contains).toList();
        if (checked.isEmpty()) {
            try {
                engine.rows("SELECT 1");
                return Health.up().build();
            } catch (RuntimeException e) {
                return Health.down(e).build();
            }
        }
        Health.Builder health = Health.up();
        Map<String, Object> details = new LinkedHashMap<>();
        for (String source : checked) {
            try {
                engine.connector(source).catalog().schemas();
                details.put(source, Map.of("status", "UP"));
            } catch (RuntimeException e) {
                details.put(source, Map.of("status", "DOWN", "error", e.getClass().getName() + ": "
                        + e.getMessage()));
                health.down();
            }
        }
        return health.withDetails(details).build();
    }
}
