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
        assertEquals(List.of("csv", "http", "jdbc", "json", "odata", "parquet"), List.copyOf(factories.keySet()));
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
                + " http, jdbc, json, odata, parquet", assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("mongo", "mongodb", Map.of()), factories, DIR)).getMessage());
        assertEquals("Cannot create the source shop: The JDBC source shop needs the option url",
                assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("shop", "jdbc", Map.of("user", "sa")), factories, DIR)).getMessage());
        assertEquals("Cannot create the source shop: The JDBC source shop has an unknown option usr; known are url,"
                + " user, password, dialect, schemas, defaultSchema, fetchSize, batchSize, recordStatements,"
                + " splitRows, splitColumn.<table>, properties.<key>",assertThrows(ConfigException.class, () -> Connectors.create(
                        new SourceConfig("shop", "jdbc", Map.of("url", "jdbc:h2:mem:x", "usr", "sa")), factories, DIR))
                .getMessage());
    }
}
