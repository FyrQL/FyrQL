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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The section pgwire of a configuration file. */
class PgWireConfigTest {

    private static final Path DIR = Path.of("conf").toAbsolutePath();

    @Test
    void theSettingsAndTheirDefaults() {
        PgWireConfig d = PgWireConfig.read(Map.of(), DIR);
        assertEquals(PgWireConfig.defaults(), d);
        assertEquals("127.0.0.1", d.host());
        assertEquals(5433, d.port());
        assertEquals(100, d.maxConnections());
        assertEquals("18.0", d.serverVersion());
        assertEquals(180_000, d.versionNumber());
        PgWireConfig c = PgWireConfig.read(Map.of("host", "0.0.0.0", "port", "6000", "tls.certificate", "s.pem",
                "tls.key", "s.key", "users.alice", "s3cret", "maxConnections", "5", "maxQueries", "3",
                "serverVersion", "17.4"), DIR);
        assertEquals(DIR.resolve("s.pem"), c.certificate());
        assertEquals(Map.of("alice", "s3cret"), c.users());
        assertEquals(170_004, c.versionNumber());
        assertTrue(c.tls() && c.signIn());
        assertFalse(c.toString().contains("s3cret"), c.toString());
    }

    @Test
    void wrongSettingsAreErrors() {
        assertThrows(IllegalArgumentException.class, () -> PgWireConfig.read(Map.of("nope", "1"), DIR));
        assertThrows(IllegalArgumentException.class, () -> PgWireConfig.read(Map.of("port", "70000"), DIR));
        assertThrows(IllegalArgumentException.class, () -> PgWireConfig.read(Map.of("tls.key", "k"), DIR));
        assertThrows(IllegalArgumentException.class, () -> PgWireConfig.read(Map.of("maxConnections", "0"), DIR));
        assertThrows(IllegalArgumentException.class, () -> PgWireConfig.read(Map.of("serverVersion", "x"), DIR));
        assertThrows(IllegalArgumentException.class,
                () -> PgWireConfig.defaults().withHost("0.0.0.0").checkSignInBeyondLoopback());
        PgWireConfig.defaults().withHost("0.0.0.0").withUsers(Map.of("a", "b")).checkSignInBeyondLoopback();
        assertTrue(PgWireConfig.defaults().withHost("0.0.0.0").withUsers(Map.of("a", "b"))
                .plainSignInBeyondLoopback());
    }
}
