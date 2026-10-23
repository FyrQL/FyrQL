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

package net.fyrql.examples.federatedquery;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The example prints what its README shows under "Output". */
class FederatedQueryTest {

    @Test
    void printsTheOutputOfTheReadme() throws Exception {
        String expected = expectedOutput();
        String actual = run();
        if (!expected.equals(actual)) {
            Files.writeString(Path.of("target", "actual-output.txt"), actual, StandardCharsets.UTF_8);
        }
        assertEquals(expected, actual, "README.md, section Output; the output is in target/actual-output.txt");
    }

    private static String run() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = System.out;
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            FederatedQuery.main(new String[0]);
        } finally {
            System.setOut(out);
        }
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The first block of text under the heading "## Output" of the README. */
    private static String expectedOutput() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int section = readme.indexOf("\n## Output\n");
        assertTrue(section >= 0, "README.md has no section Output");
        int start = readme.indexOf("```text\n", section);
        assertTrue(start >= 0, "README.md has no block of text under Output");
        start += "```text\n".length();
        return readme.substring(start, readme.indexOf("```\n", start));
    }
}
