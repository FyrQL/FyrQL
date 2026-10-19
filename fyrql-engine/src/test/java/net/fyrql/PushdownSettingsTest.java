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

package net.fyrql;

import net.fyrql.optimizer.Optimizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pushdown setting of an engine and its system property. */
class PushdownSettingsTest {

    @Test
    void theBuilderAndTheSystemPropertySetPushdown() {
        try (FyrQL engine = FyrQL.builder().build()) {
            assertTrue(engine.pushdown());
        }
        try (FyrQL engine = FyrQL.builder().pushdown(false).build()) {
            assertFalse(engine.pushdown());
            assertEquals("""
                    Project #0 AS x
                      Filter (#1 > CAST(1 AS INTEGER))
                        Values 1 row (col0 INTEGER, col1 INTEGER)
                    """, engine.explain("SELECT x FROM (VALUES (1, 2)) v(x, y) WHERE y > 1"));
        }
        String before = System.getProperty(Optimizer.Options.PROPERTY);
        try {
            System.setProperty(Optimizer.Options.PROPERTY, "Off");
            try (FyrQL engine = FyrQL.builder().build()) {
                assertFalse(engine.pushdown());
            }
            try (FyrQL engine = FyrQL.builder().pushdown(true).build()) {
                assertTrue(engine.pushdown(), "the builder wins");
            }
            System.setProperty(Optimizer.Options.PROPERTY, "sometimes");
            assertThrows(IllegalArgumentException.class, () -> FyrQL.builder().build());
            System.setProperty(Optimizer.Options.PROPERTY, "on");
            assertEquals(Optimizer.Options.DEFAULT, Optimizer.Options.fromSystemProperty());
        } finally {
            if (before == null) {
                System.clearProperty(Optimizer.Options.PROPERTY);
            } else {
                System.setProperty(Optimizer.Options.PROPERTY, before);
            }
        }
    }
}
