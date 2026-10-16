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

package net.fyrql.config;

import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.http.HttpConnector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectorsTest {

    private static final Path DIR = Path.of("");

    @Test
    void findsTheFactoriesOfTheConnectorsOnTheClassPath() {
        Map<String, ConnectorFactory> factories = Connectors.factories();
        assertEquals(List.of("csv", "http", "jdbc", "json", "parquet"), List.copyOf(factories.keySet()));
        try (Connector c = Connectors.create(new SourceConfig("files", "CSV", Map.of()), factories, DIR)) {
            assertInstanceOf(CsvConnector.class, c);
            assertEquals("files", c.name());
        }
        try (Connector c = Connectors.create(new SourceConfig("api", "http", Map.of("tables.p", "http://localhost/p")),
                factories, DIR)) {
            assertInstanceOf(HttpConnector.class, c);
            assertEquals(List.of("p"), c.catalog().tables("main"));
        }
    }

    @Test
    void anUnknownTypeOrAMissingOptionNamesTheSource() {
        Map<String, ConnectorFactory> factories = Connectors.factories();
        assertEquals("The source mongo has the type mongodb, which no connector on the class path has; known are csv,"
                + " http, jdbc, json, parquet", assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("mongo", "mongodb", Map.of()), factories, DIR)).getMessage());
        assertEquals("Cannot create the source shop: The JDBC source shop needs the option url",
                assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("shop", "jdbc", Map.of("user", "sa")), factories, DIR)).getMessage());
        assertEquals("Cannot create the source shop: The JDBC source shop has an unknown option usr; known are url,"
                + " user, password, dialect, schemas, defaultSchema, fetchSize, batchSize, recordStatements,"
                + " properties.<key>", assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("shop", "jdbc", Map.of("url", "jdbc:h2:mem:x", "usr", "sa")), factories, DIR))
                .getMessage());
    }
}
