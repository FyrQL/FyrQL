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
package net.fyrql.connector;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigOptionsTest {

    private static final Path DIR = Path.of("conf");

    private static ConfigOptions options(String... keysAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new ConfigOptions("the test source t", m, DIR);
    }

    private static String failure(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }

    @Test
    void readsTypedValues() {
        ConfigOptions o = options("s", "text", "i", " 42 ", "d", "0.5", "b", "TRUE", "off", "Off", "c", ";", "tab", "\\t",
                "p", "data/x.csv", "abs", DIR.toAbsolutePath().resolve("y").toString());
        assertEquals(Optional.of("text"), o.string("s"));
        assertEquals(Optional.of(42), o.integer("i"));
        assertEquals(Optional.of(0.5), o.number("d"));
        assertEquals(Optional.of(true), o.bool("b"));
        assertEquals(Optional.of(false), o.bool("off"));
        assertEquals(Optional.of(';'), o.character("c"));
        assertEquals(Optional.of('\t'), o.character("tab"));
        assertEquals(Optional.of(DIR.resolve("data/x.csv")), o.path("p"));
        assertEquals(Optional.of(DIR.toAbsolutePath().resolve("y")), o.path("abs"));
        assertEquals(Optional.empty(), o.integer("missing"));
        o.rejectUnknown();
    }

    @Test
    void readsDurationsAndSizes() {
        ConfigOptions o = options("a", "500ms", "b", "30s", "c", "2 m", "d", "1h", "e", "PT1.5S", "f", "1000",
                "g", "512MB", "h", "2GiB", "i", "1kib", "j", "7b");
        assertEquals(Duration.ofMillis(500), o.duration("a").orElseThrow());
        assertEquals(Duration.ofSeconds(30), o.duration("b").orElseThrow());
        assertEquals(Duration.ofMinutes(2), o.duration("c").orElseThrow());
        assertEquals(Duration.ofHours(1), o.duration("d").orElseThrow());
        assertEquals(Duration.ofMillis(1500), o.duration("e").orElseThrow());
        assertEquals(1000L, o.bytes("f").orElseThrow());
        assertEquals(512_000_000L, o.bytes("g").orElseThrow());
        assertEquals(2L << 30, o.bytes("h").orElseThrow());
        assertEquals(1024L, o.bytes("i").orElseThrow());
        assertEquals(7L, o.bytes("j").orElseThrow());
        assertEquals("The option x of the test source t must be a duration such as 500ms, 30s, 2m or PT30S: 0s",
                failure(() -> options("x", "0s").duration("x")));
        assertEquals("The option x of the test source t must be a duration such as 500ms, 30s, 2m or PT30S: 10",
                failure(() -> options("x", "10").duration("x")));
        assertEquals("The option x of the test source t must be a number of bytes such as 1000000, 512MB or 2GiB:"
                + " 1.5GB", failure(() -> options("x", "1.5GB").bytes("x")));
    }

    @Test
    void namesTheOptionAndWhatItBelongsToInEveryMessage() {
        assertEquals("The option n of the test source t must be an integer: many",
                failure(() -> options("n", "many").integer("n")));
        assertEquals("The option f of the test source t must be true, false, on or off: yes",
                failure(() -> options("f", "yes").bool("f")));
        assertEquals("The option c of the test source t must be one character: ab",
                failure(() -> options("c", "ab").character("c")));
        assertEquals("The option e of the test source t is invalid: no such kind",
                failure(() -> options("e", "x").get("e", null, v -> {
                    throw new IllegalArgumentException("no such kind");
                })));
        assertEquals("The test source t needs the option url", failure(() -> options().required("url")));
        assertEquals("The test source t needs the option url", failure(() -> options("url", " ").required("url")));
    }

    @Test
    void anOptionThatNothingAskedForIsUnknown() {
        ConfigOptions o = options("url", "u", "pasword", "secret", "headers.Accept", "a", "tables.x", "f");
        o.required("url");
        o.string("password");
        assertEquals(Map.of("Accept", "a"), o.prefixed("headers", "<name>"));
        o.tables("file");
        String message = failure(o::rejectUnknown);
        assertEquals("The test source t has an unknown option pasword; known are url, password, headers.<name>,"
                + " tables.<name>, tables.<name>.file", message);
        assertFalse(message.contains("secret"));
        ConfigOptions two = options("a", "1", "b", "2");
        assertTrue(failure(two::rejectUnknown).startsWith("The test source t has the unknown options a, b; known are"));
    }

    @Test
    void tablesHaveAMainAttributeEitherWay() {
        Map<String, Map<String, String>> tables = options("tables.b", "b.json", "tables.a.url", "http://a",
                "tables.a.pointer", "/data").tables("url", "pointer");
        assertEquals(List.of("a", "b"), List.copyOf(tables.keySet()));
        assertEquals(Map.of("url", "http://a", "pointer", "/data"), tables.get("a"));
        assertEquals(Map.of("url", "b.json"), tables.get("b"));
        assertEquals("The table a of the test source t needs tables.a.url",
                failure(() -> options("tables.a.pointer", "/x").tables("url", "pointer")));
        assertEquals("The table a of the test source t has an unknown attribute header; known are url, pointer",
                failure(() -> options("tables.a.url", "u", "tables.a.header", "h").tables("url", "pointer")));
        assertEquals("The table a of the test source t has both tables.a and tables.a.url",
                failure(() -> options("tables.a", "u", "tables.a.url", "v").tables("url")));
    }
}
