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

import net.fyrql.exec.Executor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The number of threads of a query and whether rows keep the order of their sources. */
class ThreadSettingsTest {

    @Test
    void theBuilderSetsThreadsAndOrder() {
        try (FyrQL engine = FyrQL.builder().threads(3).preserveOrder(false).build()) {
            assertEquals(3, engine.threads());
            assertFalse(engine.preserveOrder());
        }
        assertThrows(IllegalArgumentException.class, () -> FyrQL.builder().threads(0));
    }

    @Test
    void systemPropertySetsTheDefault() {
        String before = System.getProperty(Executor.THREADS_PROPERTY);
        try {
            System.clearProperty(Executor.THREADS_PROPERTY);
            try (FyrQL engine = FyrQL.builder().build()) {
                assertEquals(Runtime.getRuntime().availableProcessors(), engine.threads());
                assertTrue(engine.preserveOrder());
            }
            System.setProperty(Executor.THREADS_PROPERTY, " 2 ");
            try (FyrQL engine = FyrQL.builder().build()) {
                assertEquals(2, engine.threads());
            }
            try (FyrQL engine = FyrQL.builder().threads(1).build()) {
                assertEquals(1, engine.threads());
            }
            for (String bad : new String[] {"0", "-1", "many"}) {
                System.setProperty(Executor.THREADS_PROPERTY, bad);
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> FyrQL.builder().build());
                assertEquals("The system property fyrql.threads must be a number from 1 on: " + bad, e.getMessage());
            }
        } finally {
            if (before == null) {
                System.clearProperty(Executor.THREADS_PROPERTY);
            } else {
                System.setProperty(Executor.THREADS_PROPERTY, before);
            }
        }
    }
}
