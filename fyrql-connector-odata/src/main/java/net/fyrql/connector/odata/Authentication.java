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

package net.fyrql.connector.odata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import net.fyrql.connector.http.HttpSourceException;
import net.fyrql.connector.http.HttpTransport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How the requests of a source prove who sends them: with the header
 * {@code Authorization} of HTTP Basic, of a fixed bearer token, or of a
 * bearer token that the source gets from the token endpoint of OAuth 2.0
 * with the client credentials grant. The credentials never appear in
 * messages or in {@link Object#toString()}.
 */
sealed interface Authentication permits Authentication.None, Authentication.Basic, Authentication.Bearer,
        Authentication.ClientCredentials {

    /**
     * Returns the value of the header {@code Authorization} of the next
     * request, or {@code null} for none.
     *
     * @throws ODataSourceException if a token cannot be had
     */
    String header();

    /**
     * Forgets a token that the service refused, so that the next request
     * gets a new one.
     *
     * @return whether a new request may succeed where the last one failed
     */
    default boolean renew() {
        return false;
    }

    /** No header. */
    final class None implements Authentication {

        static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public String header() {
            return null;
        }

        @Override
        public String toString() {
            return "none";
        }
    }

    /** HTTP Basic with a user and a password. */
    final class Basic implements Authentication {

        private final String header;

        Basic(String user, String password) {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
            if (user.indexOf(':') >= 0) {
                throw new IllegalArgumentException("The user of HTTP Basic must not contain a colon");
            }
            this.header = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                    .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String header() {
            return header;
        }

        @Override
        public String toString() {
            return "basic";
        }
    }

    /** A fixed bearer token. */
    final class Bearer implements Authentication {

        private final String header;

        Bearer(String token) {
            if (token.isBlank()) {
                throw new IllegalArgumentException("The bearer token is empty");
            }
            this.header = "Bearer " + token.strip();
        }

        @Override
        public String header() {
            return header;
        }

        @Override
        public String toString() {
            return "bearer";
        }
    }

    /**
     * A bearer token from the token endpoint of OAuth 2.0 with the client
     * credentials grant of RFC 6749, section 4.4. The client proves itself
     * with HTTP Basic of its identifier and secret, or with both in the
     * body of the request. A token is kept until shortly before it
     * expires, 60 seconds or half its lifetime, whichever is less, or
     * until the service refuses it; one without {@code expires_in} until
     * then.
     */
    final class ClientCredentials implements Authentication {

        private static final JsonFactory FACTORY = new JsonFactory();

        private final String source;
        private final URI tokenUrl;
        private final String clientId;
        private final String clientSecret;
        private final String scope;
        private final boolean secretInBody;
        private final HttpTransport transport;
        private final InstantSource clock;
        private String token;
        /** When to get a new token, or {@code null} if only when the service refuses it. */
        private Instant renewAt;

        /**
         * @param source the name of the source, for messages
         * @param scope the scope to ask for, or {@code null}
         * @param secretInBody whether the client identifier and secret go in the body rather than in HTTP Basic
         * @param transport the transport of the requests to the token endpoint
         * @param clock the clock against which tokens expire
         */
        ClientCredentials(String source, URI tokenUrl, String clientId, String clientSecret, String scope,
                boolean secretInBody, HttpTransport transport, InstantSource clock) {
            this.source = Objects.requireNonNull(source, "source");
            this.tokenUrl = Objects.requireNonNull(tokenUrl, "tokenUrl");
            this.clientId = Objects.requireNonNull(clientId, "clientId");
            this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
            this.scope = scope == null || scope.isBlank() ? null : scope.strip();
            this.secretInBody = secretInBody;
            this.transport = Objects.requireNonNull(transport, "transport");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public synchronized String header() {
            if (token == null || renewAt != null && !clock.instant().isBefore(renewAt)) {
                fetch();
            }
            return "Bearer " + token;
        }

        @Override
        public synchronized boolean renew() {
            token = null;
            return true;
        }

        private void fetch() {
            StringBuilder form = new StringBuilder("grant_type=client_credentials");
            if (scope != null) {
                form.append("&scope=").append(form(scope));
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (secretInBody) {
                form.append("&client_id=").append(form(clientId)).append("&client_secret=").append(form(clientSecret));
            } else {
                headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((form(clientId) + ":"
                        + form(clientSecret)).getBytes(StandardCharsets.UTF_8)));
            }
            Instant asked = clock.instant();
            String shown = ODataQuery.shown(tokenUrl);
            try (HttpTransport.Response response = transport.post(tokenUrl, "application/x-www-form-urlencoded",
                    form.toString().getBytes(StandardCharsets.UTF_8), headers)) {
                Map<String, String> answer = read(response.body());
                if (!response.ok()) {
                    String error = answer.getOrDefault("error", "");
                    String description = answer.getOrDefault("error_description", "");
                    String text = error.isEmpty() || description.isEmpty() ? error + description
                            : error + ": " + description;
                    throw error("The token endpoint " + shown + " of the OData source " + source
                            + " refused the client credentials with the status " + response.status()
                            + (text.isEmpty() ? "" : ": " + text), response.status(), text.isEmpty() ? null : text,
                            null);
                }
                String accessToken = answer.get("access_token");
                if (accessToken == null || accessToken.isBlank()) {
                    throw error("The token endpoint " + shown + " of the OData source " + source
                            + " answered without an access_token", -1, null, null);
                }
                String type = answer.get("token_type");
                if (type != null && !type.equalsIgnoreCase("bearer")) {
                    throw error("The token endpoint " + shown + " of the OData source " + source
                            + " issued a token of the type " + type + ", of which only Bearer is supported", -1, null,
                            null);
                }
                renewAt = null;
                String expiresIn = answer.get("expires_in");
                if (expiresIn != null) {
                    long seconds;
                    try {
                        seconds = Long.parseLong(expiresIn.strip());
                    } catch (NumberFormatException e) {
                        throw error("The token endpoint " + shown + " of the OData source " + source
                                + " answered expires_in " + expiresIn + ", which is no number of seconds", -1, null,
                                null);
                    }
                    renewAt = asked.plus(Duration.ofSeconds(seconds - Math.min(60, seconds / 2)));
                }
                token = accessToken.strip();
            } catch (HttpSourceException e) {
                throw error("The token endpoint " + shown + " of the OData source " + source + " did not answer: "
                        + e.getMessage().replace(tokenUrl.toString(), shown), e.status(), null, e);
            }
        }

        private ODataSourceException error(String message, int status, String serviceMessage, Throwable cause) {
            return new ODataSourceException(message, source, null, null, status, serviceMessage, cause);
        }

        /** Reads the scalar members of the JSON object of an answer, or returns none if it is no such object. */
        private static Map<String, String> read(InputStream body) {
            Map<String, String> members = new LinkedHashMap<>();
            try (InputStream in = body; JsonParser p = FACTORY.createParser(in.readNBytes(1 << 16))) {
                if (p.nextToken() != JsonToken.START_OBJECT) {
                    return members;
                }
                String key;
                while ((key = p.nextFieldName()) != null) {
                    JsonToken t = p.nextToken();
                    if (t.isScalarValue() && t != JsonToken.VALUE_NULL) {
                        members.putIfAbsent(key, p.getText());
                    } else {
                        p.skipChildren();
                    }
                }
            } catch (IOException e) {
                // What was read so far is all there is.
            }
            return members;
        }

        private static String form(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return "oauth2 client credentials";
        }
    }
}
