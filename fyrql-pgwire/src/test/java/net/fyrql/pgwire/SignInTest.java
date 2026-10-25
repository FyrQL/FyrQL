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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import static net.fyrql.pgwire.TestServer.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The sign-in with SCRAM-SHA-256, and TLS. */
class SignInTest {

    private static Properties user(String name, String password) {
        Properties p = new Properties();
        p.setProperty("user", name);
        p.setProperty("password", password);
        return p;
    }

    @Test
    void aUserSignsInWithScram() throws Exception {
        try (TestServer server = new TestServer(PgWireConfig.defaults().withPort(0)
                .withUsers(Map.of("alice", "s3cret", "bob", "naïve-café")))) {
            Connection c = server.connect(server.url("mem", ""), user("alice", "s3cret"));
            assertEquals("alice", value(c, "SELECT current_user"));
            assertEquals("1", value(server.connect(server.url("mem", ""), user("bob", "naïve-café")), "SELECT 1"));
            for (Properties wrong : new Properties[] {user("alice", "wrong"), user("mallory", "s3cret")}) {
                SQLException e = assertThrows(SQLException.class, () -> server.connect(server.url("mem", ""), wrong));
                assertEquals("28P01", e.getSQLState());
            }
        }
    }

    @Test
    void withTlsTheServerTakesOnlyConnectionsWithTls() throws Exception {
        Path certificate = Path.of(SignInTest.class.getResource("server.pem").toURI());
        Path key = Path.of(SignInTest.class.getResource("server.key").toURI());
        try (TestServer server = new TestServer(PgWireConfig.defaults().withPort(0).withTls(certificate, key)
                .withUsers(Map.of("alice", "s3cret")))) {
            Connection c = server.connect(server.url("mem", "?sslmode=require"), user("alice", "s3cret"));
            assertEquals("1", value(c, "SELECT 1"));
            SQLException e = assertThrows(SQLException.class,
                    () -> server.connect(server.url("mem", "?sslmode=disable"), user("alice", "s3cret")));
            assertEquals("28000", e.getSQLState());
        }
    }
}
