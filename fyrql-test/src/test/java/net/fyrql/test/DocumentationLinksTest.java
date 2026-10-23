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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README, the security policy, the guide for contributors, the
 * documents in {@code docs} and the READMEs of the examples in
 * {@code examples}: every relative link and
 * image names a file that exists, every anchor a heading of its file, as
 * GitHub derives anchors from headings, and the text is ASCII with line
 * feeds only.
 */
class DocumentationLinksTest {

    private static final Pattern HEADING = Pattern.compile("^#{1,6} (.+)$");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)|src=\"([^\"]+)\"");

    @Test
    void theLinksAndAnchorsResolve() throws IOException {
        // The tests run in the directory of the module.
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> documents = new ArrayList<>(List.of(root.resolve("README.md"), root.resolve("SECURITY.md"),
                root.resolve("CONTRIBUTING.md")));
        try (var files = Files.list(root.resolve("docs"))) {
            files.filter(p -> p.toString().endsWith(".md")).sorted().forEach(documents::add);
        }
        try (var files = Files.walk(root.resolve("examples"))) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .sorted().forEach(documents::add);
        }
        assertTrue(documents.size() >= 11, documents.toString());
        Map<Path, List<String>> anchors = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Path document : documents) {
            String text = Files.readString(document, StandardCharsets.UTF_8);
            String name = root.relativize(document).toString().replace('\\', '/');
            if (text.indexOf('\r') >= 0) {
                problems.add(name + ": carriage return");
            }
            String[] lines = text.split("\n", -1);
            boolean fence = false;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.chars().anyMatch(c -> c > 127)) {
                    problems.add(name + ":" + (i + 1) + ": not ASCII");
                }
                if (line.strip().startsWith("```")) {
                    fence = !fence;
                    continue;
                }
                if (fence) {
                    continue;
                }
                Matcher m = LINK.matcher(line);
                while (m.find()) {
                    String target = m.group(1) != null ? m.group(1) : m.group(2);
                    if (!target.matches("^[a-z]+:.*")) {
                        check(document, name + ":" + (i + 1), target, anchors, problems);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static void check(Path document, String where, String target, Map<Path, List<String>> anchors,
            List<String> problems) throws IOException {
        int hash = target.indexOf('#');
        String file = hash < 0 ? target : target.substring(0, hash);
        Path path = file.isEmpty() ? document : document.getParent().resolve(file).normalize();
        if (!Files.exists(path)) {
            problems.add(where + ": no file " + target);
        } else if (hash >= 0) {
            List<String> known = anchors.get(path);
            if (known == null) {
                known = anchors(Files.readString(path, StandardCharsets.UTF_8));
                anchors.put(path, known);
            }
            if (!known.contains(target.substring(hash + 1))) {
                problems.add(where + ": no anchor " + target);
            }
        }
    }

    /** The anchors of the headings outside code fences, as GitHub derives them, numbered when they repeat. */
    static List<String> anchors(String text) {
        List<String> anchors = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        boolean fence = false;
        for (String line : text.split("\n")) {
            if (line.strip().startsWith("```")) {
                fence = !fence;
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (!fence && m.matches()) {
                String base = m.group(1).strip().toLowerCase().replaceAll("[^a-z0-9 _-]", "").replace(' ', '-');
                int n = seen.merge(base, 1, Integer::sum) - 1;
                anchors.add(n == 0 ? base : base + "-" + n);
            }
        }
        return anchors;
    }
}
