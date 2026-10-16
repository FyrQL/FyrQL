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
