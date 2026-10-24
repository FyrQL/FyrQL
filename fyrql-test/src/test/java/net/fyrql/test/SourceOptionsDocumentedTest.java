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

import net.fyrql.connector.ConnectorFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every option of every type of source is in the manual, in a paragraph that speaks of the source of that type: the
 * options are keys of the configuration file, which the compatibility promise covers. The factories name the options
 * they know in the message about an unknown one, from which the test reads them.
 */
class SourceOptionsDocumentedTest {

    /** The options without which a factory fails before it looks for unknown ones. */
    private static final Map<String, Map<String, String>> REQUIRED = Map.of(
            "jdbc", Map.of("url", "jdbc:h2:mem:"),
            "odata", Map.of("url", "http://localhost/odata/"));

    private static final String UNKNOWN = "zzUnknown";

    @Test
    void theManualNamesEveryOptionOfEveryTypeOfSource() throws IOException {
        Map<String, ConnectorFactory> factories = new TreeMap<>();
        for (ConnectorFactory f : ServiceLoader.load(ConnectorFactory.class)) {
            factories.put(f.type(), f);
        }
        assertTrue(factories.keySet().containsAll(List.of("csv", "http", "iceberg", "jdbc", "json", "memory", "odata",
                "parquet", "s3")), factories.keySet().toString());
        List<String> blocks = blocks(Files.readString(Path.of("..", "docs", "manual.md"), StandardCharsets.UTF_8));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, ConnectorFactory> e : factories.entrySet()) {
            String type = e.getKey();
            String text = String.join("\n", blocks.stream()
                    .filter(b -> b.contains("source of the type `" + type + "`")
                            || b.contains("sources of the type `" + type + "`"))
                    .toList());
            if (text.isEmpty()) {
                missing.add(type + ": no paragraph speaks of a source of the type " + type);
                continue;
            }
            for (String key : known(type, e.getValue())) {
                if (!documented(text, key)) {
                    missing.add(type + ": " + key);
                }
            }
        }
        assertEquals(List.of(), missing, "options that the manual does not name next to their type");
    }

    /** The options a factory knows, from its message about an unknown one. */
    private static List<String> known(String type, ConnectorFactory factory) {
        Map<String, String> options = new HashMap<>(REQUIRED.getOrDefault(type, Map.of()));
        options.put(UNKNOWN, "x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("s", options, Path.of(".")).close());
        String message = e.getMessage();
        int at = message.indexOf("; known are ");
        assertTrue(message.contains("unknown option " + UNKNOWN) && at >= 0, type + ": " + message);
        List<String> keys = new ArrayList<>();
        for (String key : message.substring(at + "; known are ".length()).split(", ")) {
            keys.add(key.strip());
        }
        assertTrue(!keys.isEmpty(), type + ": " + message);
        return keys;
    }

    /**
     * Whether a text names an option: as itself in backticks, a key under a placeholder such as
     * {@code tables.<name>} also by its prefix ({@code `tables`} or {@code `tables.}), and an attribute of a table
     * such as {@code tables.<name>.file} also by its last part ({@code `.file`} or {@code `file`}).
     */
    private static boolean documented(String text, String key) {
        if (text.contains("`" + key + "`")) {
            return true;
        }
        int open = key.indexOf(".<");
        if (open < 0) {
            return false;
        }
        String prefix = key.substring(0, open);
        int close = key.indexOf('>', open);
        String attribute = key.substring(Math.min(close + 2, key.length()));
        if (attribute.isEmpty() || attribute.startsWith("<")) {
            return text.contains("`" + prefix + "`") || text.contains("`" + prefix + ".");
        }
        return text.contains("`." + attribute + "`") || text.contains("`" + attribute + "`");
    }

    /** The paragraphs and the items of lists of a text, each with its lines joined by single spaces. */
    private static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            if (line.isBlank() || line.startsWith("- ")) {
                if (!block.isEmpty()) {
                    blocks.add(block.toString());
                    block.setLength(0);
                }
            }
            if (!line.isBlank()) {
                block.append(line.strip()).append(' ');
            }
        }
        if (!block.isEmpty()) {
            blocks.add(block.toString());
        }
        return blocks;
    }
}
