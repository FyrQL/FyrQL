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

package net.fyrql.flight;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server runs without native code: none of the JARs it runs with, as
 * the build lists them, holds a native library.
 */
class NoNativeLibrariesTest {

    private static final List<String> NATIVE = List.of(".dll", ".so", ".dylib", ".jnilib");

    @Test
    void noJarOfTheServerHoldsANativeLibrary() throws IOException {
        String classpath;
        try (InputStream in = NoNativeLibrariesTest.class.getResourceAsStream("runtime-classpath.txt")) {
            assertTrue(in != null, "the build writes the class path of the server");
            classpath = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        List<String> jars = new ArrayList<>();
        List<String> natives = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            Path path = Path.of(entry);
            if (!entry.endsWith(".jar") || !Files.isRegularFile(path)) {
                continue;
            }
            jars.add(path.getFileName().toString());
            try (JarFile jar = new JarFile(path.toFile())) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements();) {
                    String name = e.nextElement().getName().toLowerCase(Locale.ROOT);
                    if (NATIVE.stream().anyMatch(name::endsWith) || name.contains(".so.")) {
                        natives.add(path.getFileName() + "!" + name);
                    }
                }
            }
        }
        assertTrue(jars.stream().anyMatch(j -> j.startsWith("flight-core-")), String.join(", ", jars));
        assertTrue(jars.stream().anyMatch(j -> j.startsWith("netty-handler-")), String.join(", ", jars));
        assertEquals(List.of(), natives);
    }
}
