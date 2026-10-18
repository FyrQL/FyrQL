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
