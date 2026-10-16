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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.config.ConfigException;
import net.fyrql.config.FyrQLConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A tool such as DBeaver loads the driver in a class loader of its own and
 * leaves the class loader of the thread without the connectors; the driver
 * finds them with its own class loader.
 */
class ClassLoaderTest {

    @TempDir
    Path dir;

    @Test
    void theDriverFindsTheConnectorsWithoutTheClassLoaderOfTheThread() throws Exception {
        Files.writeString(dir.resolve("t.csv"), "a,b\n1,x\n2,y\n", StandardCharsets.UTF_8);
        Path config = Files.writeString(dir.resolve("fyrql.yaml"), """
                sources:
                  - name: files
                    type: csv
                    directory: .
                """, StandardCharsets.UTF_8);
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try (URLClassLoader empty = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(empty);
            assertThrows(ConfigException.class, () -> FyrQLConfig.open(config), "the thread sees no connector");
            try (Connection c = new FyrQLDriver().connect("jdbc:fyrql:?config=" + DriverTest.path(config), null)) {
                assertEquals(List.of(BigDecimal.valueOf(3)), DriverTest.first(c, "SELECT sum(a) FROM files.t"));
            }
            try (Connection c = new FyrQLDriver().connect("jdbc:fyrql:", null)) {
                assertEquals(List.of("csv", "json", "parquet"), c.unwrap(FyrQL.class).sources());
            }
        } finally {
            thread.setContextClassLoader(saved);
        }
    }
}
