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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.codegen.JaninoCompiler;
import net.fyrql.exec.Codegen;
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JAR of {@code fyrql-codegen} carries Janino under
 * {@code net.fyrql.internal}: no class of the original packages, no
 * signature files, the renamed service file of the compiler factory, and
 * Janino's license; the engine finds the compiler through the
 * {@link java.util.ServiceLoader}.
 */
class ShadedJaninoTest {

    /** The original package, put together so that no tool rewrites it. */
    private static final String ORIGINAL = String.join(".", "org", "codehaus", "janino");

    @Test
    void janinoIsRenamed() throws ClassNotFoundException {
        assertNotNull(Class.forName("net.fyrql.internal.janino.ClassBodyEvaluator"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(ORIGINAL + ".ClassBodyEvaluator"));
        assertInstanceOf(JaninoCompiler.class, Codegen.loadCompiler());
        try (FyrQL engine = FyrQL.builder().codegen(CodegenMode.ALWAYS).build()) {
            assertEquals(List.of(List.of(3)), engine.rows("SELECT x + 1 FROM (VALUES (2)) v(x)").stream()
                    .map(List::of).toList());
            assertTrue(engine.codegenStatistics().compiled() + engine.codegenStatistics().cacheHits() > 0);
        }
    }

    @Test
    void theJarHasNoOriginalClassesAndNoSignatures() throws IOException, URISyntaxException {
        Path location = Path.of(JaninoCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Only the packaged JAR is shaded; a run on the classes of the module, e.g. in an IDE, has nothing to check.
        assumeTrue(Files.isRegularFile(location), "fyrql-codegen comes from " + location);
        try (JarFile jar = new JarFile(location.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();
            assertTrue(names.stream().noneMatch(n -> n.startsWith(ORIGINAL.replace('.', '/') + "/")
                    || n.startsWith("org/codehaus/")), "original Janino classes in " + location);
            assertTrue(names.stream().noneMatch(n -> n.matches("META-INF/[^/]*\\.(SF|DSA|RSA)")), "signature files");
            assertTrue(names.contains("META-INF/licenses/janino-LICENSE.txt"), "Janino's license");
            assertTrue(names.contains("META-INF/services/net.fyrql.internal.commons.compiler.ICompilerFactory"));
            assertTrue(names.contains("META-INF/services/net.fyrql.exec.spi.ExpressionCompiler"));
            assertEquals("net.fyrql.codegen", jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
        }
    }
}
