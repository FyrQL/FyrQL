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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The lists of third-party software in the JARs that carry other libraries name the versions the build puts in.
 * Each module that renames libraries into its JAR writes the artifacts it takes into
 * {@code target/shaded-dependencies.txt}; a JAR that takes the JAR of such a module carries its libraries as well.
 * A library that the table of entries does not know fails the test, so that a new library gets its entry.
 */
class ThirdPartyListsTest {

    /** The modules with a list of third-party software, and the modules whose JARs they take in turn. */
    private static final Map<String, List<String>> MODULES = Map.of(
            "fyrql-config", List.of(),
            "fyrql-connector-parquet", List.of(),
            "fyrql-jdbc-all", List.of("fyrql-codegen", "fyrql-config", "fyrql-connector-parquet"),
            "fyrql-flight-sql-all", List.of("fyrql-codegen", "fyrql-config", "fyrql-connector-parquet"),
            "fyrql-pgwire-all", List.of("fyrql-codegen", "fyrql-config", "fyrql-connector-parquet"));

    /** The start of the entry of each group of artifacts, for the group itself and the groups below it. */
    private static final Map<String, String> ENTRIES = Map.ofEntries(
            Map.entry("com.fasterxml.jackson", "Jackson"),
            Map.entry("org.yaml", "SnakeYAML"),
            Map.entry("com.github.jsqlparser", "JSqlParser"),
            Map.entry("de.siegmar", "FastCSV"),
            Map.entry("io.github.janino-lts", "Janino"),
            Map.entry("org.apache.parquet", "Apache Parquet Java"),
            Map.entry("blue.strategic.parquet", "parquet-floor"),
            Map.entry("io.airlift", "aircompressor"),
            Map.entry("org.locationtech.jts", "JTS Topology Suite"),
            Map.entry("org.slf4j", "SLF4J"),
            Map.entry("org.apache.arrow", "Apache Arrow Java"),
            Map.entry("io.grpc", "gRPC Java"),
            Map.entry("io.netty", "Netty"),
            Map.entry("com.google.protobuf", "Protocol Buffers for Java"),
            Map.entry("com.google.api.grpc", "proto-google-common-protos"),
            Map.entry("com.google.guava", "Guava"),
            Map.entry("com.google.code.gson", "Gson"),
            Map.entry("io.perfmark", "PerfMark"),
            Map.entry("com.google.flatbuffers", "FlatBuffers for Java"),
            Map.entry("commons-codec", "Apache Commons Codec"));

    /** A version in the text of an entry, as 2.22.4, 33.4.8-jre or 4.2.19.Final. */
    private static final Pattern VERSION = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\w+)+(?:-\\w+)?");

    private record Artifact(String group, String id, String version) {

        @Override
        public String toString() {
            return group + ":" + id + ":" + version;
        }
    }

    @Test
    void theListsNameTheVersionsOfTheLibrariesInTheJars() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (Map.Entry<String, List<String>> module : MODULES.entrySet()) {
            Set<Artifact> artifacts = new LinkedHashSet<>(artifacts(root, module.getKey()));
            for (String taken : module.getValue()) {
                artifacts.addAll(artifacts(root, taken));
            }
            Path list = root.resolve(module.getKey()).resolve("src/main/resources/META-INF/licenses/THIRD-PARTY.txt");
            List<String> entries = entries(Files.readString(list, StandardCharsets.UTF_8));
            Set<String> used = new LinkedHashSet<>();
            for (Artifact a : artifacts) {
                String name = ENTRIES.entrySet().stream()
                        .filter(e -> a.group().equals(e.getKey()) || a.group().startsWith(e.getKey() + "."))
                        .map(Map.Entry::getValue).findFirst()
                        .orElseThrow(() -> new AssertionError(module.getKey() + " carries " + a + ", which neither "
                                + list + " nor the entries of this test know; add it to both"));
                String entry = entries.stream().filter(e -> e.startsWith("- " + name + " "))
                        .filter(e -> namesVersion(e, a.version()) && namesArtifact(e, a.id())).findFirst()
                        .orElse(null);
                if (entry == null) {
                    fail(list + " has no entry \"" + name + "\" that names " + a.id() + " and version " + a.version()
                            + "; " + module.getKey() + " carries " + a);
                }
                used.add(entry);
            }
            for (String entry : entries) {
                assertTrue(used.contains(entry), list + " names a library that " + module.getKey()
                        + " does not carry: " + entry.lines().findFirst().orElse(entry));
            }
        }
    }

    /** The artifacts that the dependency plugin listed for a module. */
    private static List<Artifact> artifacts(Path root, String module) throws IOException {
        Path file = root.resolve(module).resolve("target/shaded-dependencies.txt");
        assumeTrue(Files.isRegularFile(file), "the build wrote no " + file);
        List<Artifact> artifacts = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("The following") || s.equals("none")) {
                continue;
            }
            // group:artifact:type[:classifier]:version:scope, followed by the name of the module, if any.
            String[] parts = s.split("\\s+")[0].split(":");
            assertTrue(parts.length == 5 || parts.length == 6, "unexpected line in " + file + ": " + line);
            artifacts.add(new Artifact(parts[0], parts[1], parts[parts.length - 2]));
        }
        assertTrue(!artifacts.isEmpty(), file + " lists no artifact");
        return artifacts;
    }

    /** The entries of a list: the paragraphs that start with a dash, each with its lines joined. */
    private static List<String> entries(String text) {
        List<String> entries = new ArrayList<>();
        for (String paragraph : text.replace("\r", "").split("\n\\s*\n")) {
            String p = paragraph.strip();
            if (p.startsWith("- ")) {
                entries.add(p.replaceAll("\\s+", " "));
            }
        }
        return entries;
    }

    /**
     * Whether an entry names a version. Since 2.20, jackson-annotations has only a major and a minor version, which
     * the entry of Jackson names as the start of its version.
     */
    private static boolean namesVersion(String entry, String version) {
        Matcher m = VERSION.matcher(entry);
        while (m.find()) {
            if (m.group().equals(version) || m.group().startsWith(version + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Whether an entry names an artifact, if it lists its artifacts in parentheses. */
    private static boolean namesArtifact(String entry, String id) {
        int open = entry.indexOf('(');
        int close = entry.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return true;
        }
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(id) + "(?![\\w-])")
                .matcher(entry.substring(open + 1, close)).find();
    }
}
