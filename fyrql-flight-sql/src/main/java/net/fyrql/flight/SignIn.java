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

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.auth2.Auth2Constants;
import org.apache.arrow.flight.auth2.AuthUtilities;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.GeneratedBearerTokenAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks the credentials of each call. With users, a client signs in with
 * its name and password as HTTP basic authentication and gets a bearer
 * token for its next calls, and its identity is its name; with a token, a
 * client sends that token as its bearer token, and its identity is
 * {@value #TOKEN_IDENTITY}. Passwords and tokens are compared in constant
 * time, and a client without credentials or with wrong ones learns which
 * the server takes.
 */
final class SignIn implements CallHeaderAuthenticator {

    /** The identity of a client that sends the token of the server. */
    static final String TOKEN_IDENTITY = "<token>";

    private final byte[] token;
    private final CallHeaderAuthenticator users;

    private SignIn(String token, CallHeaderAuthenticator users) {
        this.token = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
        this.users = users;
    }

    /** Returns the check of the credentials of a server, or one that lets every client in. */
    static CallHeaderAuthenticator of(ServerConfig config) {
        if (!config.signIn()) {
            return CallHeaderAuthenticator.NO_OP;
        }
        CallHeaderAuthenticator users = null;
        if (!config.users().isEmpty()) {
            Map<String, byte[]> passwords = new HashMap<>();
            config.users().forEach((name, password) -> passwords.put(name, password.getBytes(StandardCharsets.UTF_8)));
            users = new GeneratedBearerTokenAuthenticator(new BasicCallHeaderAuthenticator((name, password) -> {
                byte[] expected = passwords.get(name);
                if (expected == null || !MessageDigest.isEqual(expected, password.getBytes(StandardCharsets.UTF_8))) {
                    throw unauthenticated("Wrong user name or password");
                }
                return () -> name;
            }));
        }
        return new SignIn(config.token(), users);
    }

    @Override
    public AuthResult authenticate(CallHeaders headers) {
        String bearer = AuthUtilities.getValueFromAuthHeader(headers, Auth2Constants.BEARER_PREFIX);
        if (token != null && bearer != null && MessageDigest.isEqual(token, bearer.getBytes(StandardCharsets.UTF_8))) {
            return () -> TOKEN_IDENTITY;
        }
        if (users == null) {
            throw unauthenticated(bearer == null ? "The server needs a bearer token" : "Wrong bearer token");
        }
        if (!headers.containsKey(Auth2Constants.AUTHORIZATION_HEADER)) {
            throw unauthenticated(token == null ? "The server needs a user name and password"
                    : "The server needs a user name and password or a bearer token");
        }
        try {
            return users.authenticate(headers);
        } catch (FlightRuntimeException e) {
            // Arrow rejects a bearer token it did not give out without a description.
            String description = e.status().description();
            if (e.status().code() == FlightStatusCode.UNAUTHENTICATED && (description == null || description.isEmpty())
                    && bearer != null) {
                throw unauthenticated("Wrong bearer token");
            }
            throw e;
        }
    }

    private static FlightRuntimeException unauthenticated(String message) {
        return CallStatus.UNAUTHENTICATED.withDescription(message).toRuntimeException();
    }
}
