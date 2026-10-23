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

import net.fyrql.config.EngineConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The section {@code server} of a configuration: its keys, the defaults, and errors that never show a secret. */
class ServerConfigTest {

    private static final Path DIR = Path.of("conf");

    @Test
    void readsTheKeysOfTheSection() {
        System.setProperty("fyrql.test.server.password", "wonderland");
        try {
            EngineConfig engine = EngineConfig.parse("""
                    sources: []
                    server:
                      host: 0.0.0.0
                      port: 4000
                      tls: {certificate: tls/server.pem, key: tls/server.key}
                      users:
                        alice: ${sys:fyrql.test.server.password}
                        bob: builder
                      token: t0ken
                      handleTimeout: 90s
                      sessionTimeout: 2h
                      maxQueries: 3
                    """, DIR);
            ServerConfig config = ServerConfig.of(engine);
            assertEquals("0.0.0.0", config.host());
            assertEquals(4000, config.port());
            assertEquals(DIR.resolve("tls/server.pem"), config.certificate());
            assertEquals(DIR.resolve("tls/server.key"), config.key());
            assertEquals(Map.of("alice", "wonderland", "bob", "builder"), config.users());
            assertEquals("t0ken", config.token());
            assertEquals(Duration.ofSeconds(90), config.handleTimeout());
            assertEquals(Duration.ofHours(2), config.sessionTimeout());
            assertEquals(3, config.maxQueries());
            assertTrue(config.tls());
            assertTrue(config.signIn());
            assertFalse(config.toString().contains("wonderland"), config.toString());
            assertFalse(config.toString().contains("t0ken"), config.toString());
        } finally {
            System.clearProperty("fyrql.test.server.password");
        }
    }

    @Test
    void theDefaultsAreTheLoopbackAddressWithoutTlsAndSignIn() {
        ServerConfig config = ServerConfig.of(EngineConfig.parse("sources: []\n", DIR));
        assertEquals(ServerConfig.defaults(), config);
        assertEquals("127.0.0.1", config.host());
        assertEquals(32010, config.port());
        assertNull(config.certificate());
        assertEquals(Map.of(), config.users());
        assertEquals(Duration.ofMinutes(10), config.handleTimeout());
        assertEquals(Duration.ofHours(1), config.sessionTimeout());
        assertEquals(Duration.ZERO, ServerConfig.read(Map.of("sessionTimeout", "0"), DIR).sessionTimeout());
        assertEquals(0, config.maxQueries());
        assertFalse(config.tls());
        assertFalse(config.signIn());
    }

    @Test
    void signInWithoutTlsBeyondTheLoopbackAddressIsInPlainText() {
        assertTrue(ServerConfig.read(Map.of("host", "0.0.0.0", "users.alice", "pw"), DIR)
                .plainSignInBeyondLoopback());
        assertTrue(ServerConfig.read(Map.of("host", "0.0.0.0", "token", "t"), DIR).plainSignInBeyondLoopback());
        assertFalse(ServerConfig.read(Map.of("users.alice", "pw"), DIR).plainSignInBeyondLoopback());
        assertFalse(ServerConfig.read(Map.of("host", "0.0.0.0", "token", "t", "tls.certificate", "c.pem", "tls.key",
                "k.pem"), DIR).plainSignInBeyondLoopback());
        assertFalse(ServerConfig.read(Map.of("host", "0.0.0.0"), DIR).plainSignInBeyondLoopback());
    }

    @Test
    void errorsNameTheSettingAndNeverItsValue() {
        assertEquals("The server has an unknown option prot; known are host, port, tls.certificate, tls.key,"
                + " users.<name>, token, handleTimeout, sessionTimeout, maxQueries", failure(Map.of("prot", "1")));
        assertEquals("The option port of the server must be an integer: x", failure(Map.of("port", "x")));
        assertEquals("The port of the server must be from 0 to 65535, not 70000", failure(Map.of("port", "70000")));
        assertEquals("TLS needs both tls.certificate and tls.key", failure(Map.of("tls.certificate", "c.pem")));
        String secret = failure(Map.of("users", "alice,s3cret"));
        assertTrue(secret.startsWith("The server has an unknown option users;"), secret);
        assertFalse(secret.contains("s3cret"), secret);
        assertEquals("The option handleTimeout of the server must be a duration such as 500ms, 30s, 2m or PT30S:"
                + " 0s", failure(Map.of("handleTimeout", "0s")));
        assertEquals("maxQueries of the server must not be negative", failure(Map.of("maxQueries", "-1")));
    }

    private static String failure(Map<String, String> settings) {
        return assertThrows(IllegalArgumentException.class, () -> ServerConfig.read(settings, DIR)).getMessage();
    }
}
