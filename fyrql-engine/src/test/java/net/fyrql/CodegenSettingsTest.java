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

import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.CodegenStatistics;
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
