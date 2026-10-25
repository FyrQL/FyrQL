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

package net.fyrql.pgwire;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command line of the server: a configuration file, the options, and what the server prints. */
class MainTest {

    @Test
    void theServerStartsFromAFileAndTakesClients(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("data.csv"), "a,b\n1,x\n2,y\n");
        Path config = dir.resolve("fyrql.yaml");
        Files.writeString(config, """
                sources:
                  - name: files
                    type: csv
                    directory: .
                pgwire:
                  port: 1
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PgWireServerMain.Running running = PgWireServerMain.start(new String[] {config.toString(), "--port",
                "0"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true,
                StandardCharsets.UTF_8))) {
            String printed = out.toString(StandardCharsets.UTF_8);
            assertTrue(printed.startsWith("FyrQL PostgreSQL wire protocol server " + PgWireServer.VERSION
                    + " listening on 127.0.0.1:" + running.server().port()), printed);
            assertTrue(printed.contains("File access from SQL: none; query timeout: none, at most 1h"), printed);
            Properties p = new Properties();
            p.setProperty("user", "me");
            try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + running.server().port()
                    + "/files", p)) {
                assertEquals("2", TestServer.value(c, "SELECT count(*) FROM data"));
            }
        }
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void wrongCommandLines(@TempDir Path dir) {
        PrintStream nowhere = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThrows(PgWireServerMain.UsageException.class, () -> PgWireServerMain.start(new String[0], nowhere,
                nowhere));
        assertThrows(PgWireServerMain.UsageException.class, () -> PgWireServerMain.start(new String[] {"x.yaml",
                "--port"}, nowhere, nowhere));
        assertThrows(PgWireServerMain.UsageException.class, () -> PgWireServerMain.start(new String[] {
                dir.resolve("missing.yaml").toString()}, nowhere, nowhere));
    }
}
