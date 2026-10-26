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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Each JAR that a module of the build packs, the JARs of its tests included, carries the text of the MPL 2.0 as
 * {@code META-INF/LICENSE}, once and byte for byte as the file {@code LICENSE} at the root of the project. The
 * JARs that take in other libraries keep their licenses under {@code META-INF/licenses/}.
 */
class LicenseInJarsTest {

    /** The modules whose JARs are not published: this one and the benchmarks. */
    private static final List<String> UNPUBLISHED = List.of("fyrql-test", "fyrql-bench");

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

    private static final Pattern VERSION =
            Pattern.compile("<artifactId>fyrql</artifactId>\\s*<version>([^<]+)</version>");

    @Test
    void everyJarCarriesTheLicense() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        byte[] license = Files.readAllBytes(root.resolve("LICENSE"));
        String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher version = VERSION.matcher(pom);
        assumeTrue(version.find(), "no version in " + root.resolve("pom.xml"));
        List<String> modules = modules(pom);
        assertFalse(modules.isEmpty(), "no modules in " + root.resolve("pom.xml"));
        List<Path> checked = new ArrayList<>();
        for (String module : modules) {
            if (UNPUBLISHED.contains(module)) {
                continue;
            }
            Path target = root.resolve(module).resolve("target");
            List<Path> jars = jars(target, module + "-" + version.group(1));
            // A run on the classes of the modules, e.g. in an IDE, has no JARs to check.
            assumeTrue(!jars.isEmpty(), "no JAR of " + module + " in " + target);
            for (Path jar : jars) {
                List<byte[]> texts = entries(jar, "META-INF/LICENSE");
                assertEquals(1, texts.size(), "META-INF/LICENSE in " + jar);
                assertArrayEquals(license, texts.get(0), "the text of META-INF/LICENSE in " + jar);
                checked.add(jar);
            }
        }
        assertFalse(checked.isEmpty());
    }

    /** The modules of the build, as the parent POM lists them outside its profiles. */
    private static List<String> modules(String pom) {
        int start = pom.indexOf("<modules>");
        int end = pom.indexOf("</modules>", start);
        assumeTrue(start >= 0 && end > start, "no list of modules");
        List<String> modules = new ArrayList<>();
        Matcher m = MODULE.matcher(pom.substring(start, end));
        while (m.find()) {
            modules.add(m.group(1).strip());
        }
        return modules;
    }

    /**
     * The JARs a module packed in this version: its own and, if any, the one of its tests; not the ones of older
     * versions and not what the shade plugin kept.
     */
    private static List<Path> jars(Path target, String artifact) throws IOException {
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(p -> {
                String name = p.getFileName().toString();
                return name.equals(artifact + ".jar") || name.equals(artifact + "-tests.jar");
            }).sorted().toList();
        }
    }

    /** The contents of each entry with the given name; a ZIP file may hold a name more than once. */
    private static List<byte[]> entries(Path jar, String name) throws IOException {
        List<byte[]> contents = new ArrayList<>();
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals(name)) {
                    contents.add(zip.readAllBytes());
                }
            }
        }
        return contents;
    }
}
