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

import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.spi.CodegenStatistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The settings of code generation in an engine without the module {@code fyrql-codegen} on the class path. */
class CodegenSettingsTest {

    @Test
    void withoutACompilerEverythingIsInterpreted() {
        try (FyrQL engine = FyrQL.builder().codegen(CodegenMode.AUTO).build()) {
            assertEquals(CodegenMode.OFF, engine.codegen());
            assertEquals(CodegenStatistics.NONE, engine.codegenStatistics());
            String plan = engine.explain("SELECT x + 1 FROM (VALUES (1), (2)) v(x) WHERE x > 1");
            assertFalse(plan.contains("[codegen]"), plan);
        }
        assertThrows(IllegalStateException.class, () -> FyrQL.builder().codegen(CodegenMode.ALWAYS).build());
        assertThrows(IllegalArgumentException.class, () -> FyrQL.builder().codegenThreshold(-1));
    }

    @Test
    void systemPropertySetsTheDefault() {
        String before = System.getProperty(CodegenMode.PROPERTY);
        try {
            System.setProperty(CodegenMode.PROPERTY, "Always");
            assertEquals(CodegenMode.ALWAYS, CodegenMode.fromSystemProperty());
            assertThrows(IllegalStateException.class, () -> FyrQL.builder().build());
            System.setProperty(CodegenMode.PROPERTY, "sometimes");
            assertThrows(IllegalArgumentException.class, () -> FyrQL.builder().build());
            System.setProperty(CodegenMode.PROPERTY, "off");
            try (FyrQL engine = FyrQL.builder().build()) {
                assertEquals(CodegenMode.OFF, engine.codegen());
            }
        } finally {
            if (before == null) {
                System.clearProperty(CodegenMode.PROPERTY);
            } else {
                System.setProperty(CodegenMode.PROPERTY, before);
            }
        }
    }
}
