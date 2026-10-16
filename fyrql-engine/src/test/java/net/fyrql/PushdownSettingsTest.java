/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
