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

package net.fyrql.flight;

import net.fyrql.FyrQL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Flight SQL driver of ADBC against the server, with the Python the
 * system property {@code fyrql.test.python} names, which has
 * {@code adbc-driver-flightsql} installed: with the option
 * {@code adbc.flight.sql.rpc.with_cookie_middleware} a connection keeps its
 * session and its settings, without it each call has the settings of the
 * engine.
 */
@EnabledIfSystemProperty(named = "fyrql.test.python", matches = ".+")
class AdbcSessionTest {

    @Test
    void theCookieMiddlewareKeepsTheSession(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("adbc_check.py");
        try (InputStream in = AdbcSessionTest.class.getResourceAsStream("adbc_check.py")) {
            Files.copy(in, script);
        }
        try (TestServer server = new TestServer(ServerConfig.defaults().withPort(0),
                FyrQL.builder().timeZone(ZoneId.of("UTC")))) {
            ProcessBuilder pb = new ProcessBuilder(System.getProperty("fyrql.test.python"), "-I", script.toString(),
                    Integer.toString(server.server.port()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(120, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue(), out);
            assertTrue(out.contains("adbc ok"), out);
        }
    }
}
