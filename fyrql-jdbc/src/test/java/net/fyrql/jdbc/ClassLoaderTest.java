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
                assertEquals(List.of("csv", "json", "parquet", "memory"), c.unwrap(FyrQL.class).sources());
            }
        } finally {
            thread.setContextClassLoader(saved);
        }
    }
}
