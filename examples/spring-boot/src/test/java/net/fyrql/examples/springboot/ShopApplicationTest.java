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

package net.fyrql.examples.springboot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The application answers on its endpoints what its README shows under "Output". */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ShopApplicationTest {

    /** The requests of the README, with the path after the host. */
    private static final String[] PATHS = {"/revenue", "/customers?region=north", "/actuator/health/fyrql"};

    @LocalServerPort
    private int port;

    @Test
    void answersWhatTheReadmeShows() throws Exception {
        StringBuilder transcript = new StringBuilder();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : PATHS) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + port + path)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                transcript.append("$ curl -s 'localhost:8080").append(path).append("'\n")
                        .append(response.body()).append('\n');
            }
        }
        String actual = transcript.toString();
        String expected = expectedOutput();
        if (!expected.equals(actual)) {
            Files.writeString(Path.of("target", "actual-output.txt"), actual, StandardCharsets.UTF_8);
        }
        assertEquals(expected, actual, "README.md, section Output; the output is in target/actual-output.txt");
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
