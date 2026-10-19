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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JDBC source with a URL of the driver of FyrQL is refused when the
 * engine is built, so that a configuration that names itself does not open
 * engines without end.
 */
class RecursionTest {

    @TempDir
    Path dir;

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    @Test
    void aConfigurationThatReadsItselfThroughTheDriverFailsToConnect() throws IOException {
        Path config = dir.resolve("self.yaml");
        Files.writeString(config, """
                sources:
                  - name: files
                    type: csv
                  - name: self
                    type: jdbc
                    url: "jdbc:fyrql:?config=%s"
                """.formatted(path(config)), StandardCharsets.UTF_8);
        SQLException e = assertThrows(SQLException.class,
                () -> DriverManager.getConnection("jdbc:fyrql:?config=" + path(config)));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().startsWith(Path.of(path(config)) + ": "), e.getMessage());
        assertTrue(e.getMessage().contains("The JDBC source self has the URL jdbc:fyrql: of FyrQL itself"),
                e.getMessage());
    }

    @Test
    void theUrlIsRefusedInAnyCase() throws IOException {
        Path config = Files.writeString(dir.resolve("upper.yaml"), """
                sources:
                  - name: upper
                    type: jdbc
                    url: "JDBC:FyrQL:"
                """, StandardCharsets.UTF_8);
        SQLException e = assertThrows(SQLException.class,
                () -> new FyrQLDriver().connect("jdbc:fyrql:?config=" + path(config), null));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("The JDBC source upper has the URL jdbc:fyrql: of FyrQL itself"),
                e.getMessage());
    }
}
