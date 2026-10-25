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

package net.fyrql.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConfigTest {

    private static final Path DIR = Path.of("conf");

    @TempDir
    Path dir;

    private static EngineConfig parse(String yaml) {
        return EngineConfig.parse(yaml, DIR, Map.of("SHOP_PASSWORD", "s3cret", "EMPTY", "")::get);
    }

    private static String failure(String yaml) {
        return assertThrows(ConfigException.class, () -> parse(yaml)).getMessage();
    }

    @Test
    void readsSourcesWithFlatOptionsAndTheSettings() {
        EngineConfig config = parse("""
                # The shop and its files.
                sources:
                  - name: shop
                    type: jdbc
                    url: jdbc:postgresql://localhost/shop
                    password: 0123
                    fetchSize: 1_000
                    schemas: [public, sales]
                    properties:
                      ssl: true
                      ssl.mode: require
                  - name: api
                    type: http
                    tables:
                      products: { url: "http://localhost:8080/products", pointer: /data }
                      prices: http://localhost:8080/prices
                    headers:
                      Authorization: Bearer x
                  - {name: files, type: csv}
                engine:
                  defaultSource: shop
                  batchSize: 1024
                  pushdown: off
                """);
        assertEquals(List.of("shop", "api", "files"), config.sources().stream().map(SourceConfig::name).toList());
        SourceConfig shop = config.sources().get(0);
        assertEquals("jdbc", shop.type());
        assertEquals(List.of("url", "password", "fetchSize", "schemas", "properties.ssl", "properties.ssl.mode"),
                List.copyOf(shop.options().keySet()));
        assertEquals("0123", shop.options().get("password"), "a value is the text it is written as");
        assertEquals("1_000", shop.options().get("fetchSize"));
        assertEquals("public,sales", shop.options().get("schemas"));
        assertEquals("true", shop.options().get("properties.ssl"));
        assertEquals(Map.of("tables.products.url", "http://localhost:8080/products", "tables.products.pointer",
                "/data", "tables.prices", "http://localhost:8080/prices", "headers.Authorization", "Bearer x"),
                config.sources().get(1).options());
        assertEquals(Map.of(), config.sources().get(2).options());
        assertEquals(Map.of("defaultSource", "shop", "batchSize", "1024", "pushdown", "off"), config.settings());
        assertEquals(Map.of(), config.server());
        assertEquals(DIR, config.directory());
    }

    @Test
    void theSettingsOfAServerAreReadFlatAndLeftToTheServer() {
        EngineConfig config = parse("""
                sources: []
                server:
                  port: 32010
                  tls: {certificate: cert.pem, key: key.pem}
                  users:
                    alice: ${env:SHOP_PASSWORD}
                """);
        assertEquals(Map.of("port", "32010", "tls.certificate", "cert.pem", "tls.key", "key.pem", "users.alice",
                "s3cret"), config.server());
        assertEquals(Map.of(), config.settings());
        assertEquals("line 1: server must be a mapping of settings", failure("server: [a]\n"));
        assertEquals(Map.of(), parse("server:\n").server());
        assertEquals(Map.of(), new EngineConfig(List.of(), Map.of(), DIR).server());
    }

    @Test
    void theSectionPgwireHoldsTheSettingsOfThePostgreSqlServer() {
        EngineConfig config = parse("""
                pgwire:
                  port: 5433
                  users:
                    alice: s3cret
                server:
                  port: 32010
                """);
        assertEquals(Map.of("port", "5433", "users.alice", "s3cret"), config.pgwire());
        assertEquals(Map.of("port", "32010"), config.server());
        assertEquals("line 1: pgwire must be a mapping of settings", failure("pgwire: [a]\n"));
        assertEquals(Map.of(), new EngineConfig(List.of(), Map.of(), Map.of("port", "1"), DIR).pgwire());
        assertFalse(config.toString().contains("s3cret"), config.toString());
        assertEquals(config.pgwire(), config.withSetting("threads", "2").pgwire());
    }

    @Test
    void aValueMayNameAnEnvironmentVariableOrASystemProperty() {
        System.setProperty("fyrql.test.config.user", "alice");
        try {
            SourceConfig s = parse("""
                    sources:
                      - name: shop
                        type: jdbc
                        url: jdbc:h2:mem:${env:MISSING:-shop}
                        user: ${sys:fyrql.test.config.user}
                        password: ${env:SHOP_PASSWORD}
                        a: x${env:EMPTY}y
                        b: price $5 $${not a variable}
                    """).sources().get(0);
            assertEquals(Map.of("url", "jdbc:h2:mem:shop", "user", "alice", "password", "s3cret", "a", "xy", "b",
                    "price $5 ${not a variable}"), s.options());
        } finally {
            System.clearProperty("fyrql.test.config.user");
        }
        assertEquals("line 3: the option password of the source shop names the environment variable NOPE, which is"
                + " not set", failure("sources:\n  - name: shop\n    password: ${env:NOPE}\n    type: jdbc\n"));
        assertEquals("line 3: the option a of the source s names a variable of the unknown kind file; known are env"
                + " and sys", failure("sources:\n  - {name: s, type: csv,\n     a: '${file:x}'}\n"));
        assertTrue(failure("sources:\n  - {name: s, type: csv, a: '${env'}\n").contains("has a ${ that is not of"
                + " the form ${env:NAME} or ${sys:name}"));
        String path = System.getenv("PATH");
        if (path != null) {
            assertEquals(path, EngineConfig.parse("sources: [{name: s, type: csv, p: '${env:PATH}'}]", DIR)
                    .sources().get(0).options().get("p"));
        }
    }

    @Test
    void errorsNameTheLineAndNeverTheValue() {
        assertEquals("the configuration is empty", failure(""));
        assertEquals("line 1: the configuration must be a mapping with the keys sources, engine, server and pgwire",
                failure("- a\n- b\n"));
        assertEquals("line 2: the key source is unknown; known are sources, engine, server and pgwire",
                failure("sources: []\nsource: []\n"));
        assertEquals("line 2: a source has no name", failure("sources:\n  - type: csv\n"));
        assertEquals("line 2: the source s has no type", failure("sources:\n  - name: s\n"));
        assertEquals("line 3: there are two sources named S",
                failure("sources:\n  - {name: s, type: csv}\n  - {name: S, type: json}\n"));
        assertEquals("line 4: the key url appears twice",
                failure("sources:\n  - name: s\n    url: a\n    url: b\n    type: jdbc\n"));
        assertEquals("line 2: the option tables.x.url of the source s is set twice",
                failure("sources:\n  - {name: s, type: http, tables: {x: {url: a}}, tables.x.url: b}\n"));
        assertEquals("line 4: the option nullString of the source s has no value; a value such as null or ~ that is"
                + " meant as text needs quotes", failure("sources:\n  - name: s\n    type: csv\n    nullString: NULL\n"));
        assertEquals("line 5: the option tables of the source s is a list, which may hold only plain values",
                failure("sources:\n  - name: s\n    type: csv\n    tables:\n      - {a: b}\n"));
        assertEquals("line 1: sources must be a list of sources, each with a name and a type",
                failure("sources: {name: s}\n"));
        assertEquals("line 2: engine must be a mapping of settings", failure("sources: []\nengine: fast\n"));
        String message = failure("sources:\n  - name: s\n    type: jdbc\n    password: [s3cret\n");
        assertTrue(message.startsWith("line "), message);
        assertTrue(message.contains("the configuration does not parse"), message);
        assertFalse(failure("sources:\n  - {name: s, type: csv, p: '${env:SHOP_PASSWORD}', p: x}\n").contains(
                "s3cret"));
    }

    @Test
    void readsAFileAsYamlOrAsJsonWithItsDirectory() throws IOException {
        Path yaml = Files.writeString(dir.resolve("fyrql.yaml"), "sources:\n  - {name: a, type: csv, directory:"
                + " data}\n", StandardCharsets.UTF_8);
        EngineConfig fromYaml = EngineConfig.read(yaml);
        assertEquals(dir.toAbsolutePath(), fromYaml.directory());
        assertEquals(Map.of("directory", "data"), fromYaml.sources().get(0).options());
        Path json = Files.writeString(dir.resolve("fyrql.json"), "{\"sources\": [{\"name\": \"a\", \"type\":"
                + " \"csv\",\n\t\"sampleSize\": 10, \"header\": true}], \"engine\": {\"batchSize\": 64}}",
                StandardCharsets.UTF_8);
        EngineConfig fromJson = EngineConfig.read(json);
        assertEquals(Map.of("sampleSize", "10", "header", "true"), fromJson.sources().get(0).options());
        assertEquals(Map.of("batchSize", "64"), fromJson.settings());
        Path broken = Files.writeString(dir.resolve("broken.json"), "{\"sources\": [}", StandardCharsets.UTF_8);
        String message = assertThrows(ConfigException.class, () -> EngineConfig.read(broken)).getMessage();
        assertTrue(message.startsWith(broken + ", line 1: the configuration does not parse"), message);
        message = assertThrows(ConfigException.class, () -> EngineConfig.read(dir.resolve("missing.yaml")))
                .getMessage();
        assertTrue(message.startsWith("Cannot read the configuration " + dir.resolve("missing.yaml")), message);
    }

    @Test
    void theTextOfASourceHoldsNoValueOfAnOption() {
        SourceConfig s = new SourceConfig("shop", "jdbc", Map.of("password", "s3cret"));
        assertEquals("SourceConfig[name=shop, type=jdbc, options=[password]]", s.toString());
    }

    @Test
    void theTextOfAConfigurationHoldsNoValueOfTheServer() {
        EngineConfig config = new EngineConfig(List.of(new SourceConfig("shop", "jdbc", Map.of("password", "s3cret"))),
                Map.of("batchSize", "64"), Map.of("users.alice", "s3cret", "token", "s3cret"), DIR);
        String text = config.toString();
        assertFalse(text.contains("s3cret"), text);
        assertTrue(text.contains("users.alice") && text.contains("token") && text.contains("batchSize=64"), text);
    }
}
