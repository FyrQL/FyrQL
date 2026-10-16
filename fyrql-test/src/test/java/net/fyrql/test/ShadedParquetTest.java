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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The JAR of {@code fyrql-connector-parquet} carries parquet-java, the
 * Hadoop stubs, aircompressor, JTS and slf4j under {@code net.fyrql.internal}:
 * no class of the original packages, the licenses, and a connector that
 * reads files while an unrenamed parquet-java lies on the class path as
 * well, and no Hadoop.
 */
class ShadedParquetTest {

    /** The original packages, put together so that no tool rewrites them. */
    private static final String PARQUET = String.join(".", "org", "apache", "parquet");
    private static final String HADOOP = String.join(".", "org", "apache", "hadoop");

    @TempDir
    Path dir;

    @Test
    void theConnectorReadsAFileNextToAnotherParquetJava() throws Exception {
        Path file = dir.resolve("orders.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT range AS id, 'n' || range AS name, {'x': range::INTEGER} AS s FROM range(1000))"
                    + " TO '" + file.toString().replace('\\', '/') + "' (FORMAT parquet, ROW_GROUP_SIZE 100)");
        }
        String path = file.toString().replace('\\', '/');
        try (FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("pq").build()).build()) {
            Schema schema = engine.plan("SELECT * FROM '" + path + "'").schema();
            assertEquals(List.of(DataType.BIGINT, DataType.VARCHAR, DataType.struct(new DataType.Struct.Field("x",
                    DataType.INTEGER))), schema.types());
            assertEquals(List.of(DataType.BIGINT), engine.plan("SELECT count(*) FROM '" + path + "'").schema().types());
            String explain = engine.explain("SELECT count(*) FROM read_parquet('" + path + "')");
            assertTrue(explain.contains("Scan pq.main.#1"), explain);
            assertEquals(OptionalLong.of(1000), engine.connector("pq").catalog().rowCount(TableRef.of("pq", "#1")));
            Object[] row = engine.rows("SELECT sum(id), max(name), count(*) FROM '" + path + "' WHERE id % 2 = 1")
                    .get(0);
            assertEquals("[250000, n999, 500]", java.util.Arrays.toString(row));
        }
        // The unrenamed parquet-java of the class path is another one, and there is no Hadoop at all.
        Class<?> parser = Class.forName(PARQUET + ".schema.MessageTypeParser");
        assertNotNull(parser.getMethod("parseMessageType", String.class).invoke(null, "message m { required int32 a; }"));
        Class<?> renamed = Class.forName("net.fyrql.internal.parquet.schema.MessageTypeParser");
        assertNotEquals(parser.getProtectionDomain().getCodeSource().getLocation(),
                renamed.getProtectionDomain().getCodeSource().getLocation());
        assertThrows(ClassNotFoundException.class, () -> Class.forName(HADOOP + ".conf.Configuration"));
    }

    @Test
    void theJarHasNoOriginalClassesAndCarriesTheLicenses() throws IOException, URISyntaxException {
        Path location = Path.of(ParquetConnector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Only the packaged JAR is shaded; a run on the classes of the module, e.g. in an IDE, has nothing to check.
        assumeTrue(Files.isRegularFile(location), "fyrql-connector-parquet comes from " + location);
        try (JarFile jar = new JarFile(location.toFile())) {
            List<String> names = jar.stream().map(JarEntry::getName).toList();
            for (String original : List.of(PARQUET, HADOOP, "shaded.parquet", "io.airlift", "org.slf4j",
                    "org.locationtech", "blue.strategic")) {
                String prefix = original.replace('.', '/') + "/";
                assertTrue(names.stream().noneMatch(n -> n.startsWith(prefix) || n.contains("/" + prefix)),
                        "classes of " + original + " in " + location);
            }
            assertTrue(names.stream().noneMatch(n -> n.matches("META-INF/[^/]*\\.(SF|DSA|RSA)")), "signature files");
            for (String license : List.of("THIRD-PARTY.txt", "parquet-java-LICENSE.txt", "parquet-java-NOTICE.txt",
                    "jts-LICENSE.txt", "slf4j-LICENSE.txt")) {
                assertTrue(names.contains("META-INF/licenses/" + license), license);
            }
            assertTrue(names.contains("META-INF/services/net.fyrql.connector.ConnectorFactory"));
            assertTrue(names.contains("net/fyrql/internal/hadoop/conf/Configuration.class"));
            assertTrue(names.contains("net/fyrql/internal/slf4j/impl/StaticLoggerBinder.class"), "the NOP binding");
            assertEquals("net.fyrql.connector.parquet",
                    jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
        }
    }

    @Test
    void anErrorOfParquetJavaNamesTheFile() throws SQLException, IOException {
        Path file = Files.writeString(dir.resolve("broken.parquet"), "not a Parquet file");
        try (FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("pq").build()).build()) {
            String message = assertThrows(RuntimeException.class,
                    () -> engine.plan("SELECT * FROM '" + file.toString().replace('\\', '/') + "'")).getMessage();
            assertTrue(message.contains("Cannot read the Parquet file " + file.toAbsolutePath()), message);
        }
    }
}
