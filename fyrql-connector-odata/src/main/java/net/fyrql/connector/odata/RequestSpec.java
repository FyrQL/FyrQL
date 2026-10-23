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

import net.fyrql.connector.http.HttpTransport;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A request to a service before it is sent, which an {@link ODataProfile}
 * can add query options and headers to: what it is for, the URL, and the
 * headers of the request besides those of the source and its
 * authentication.
 */
public final class RequestSpec {

    /** What a request is for. */
    public enum Kind {
        /** The {@code $metadata} of a service. */
        METADATA,
        /** The first page of a query of an entity set. */
        QUERY,
        /** A next page of a query, by the link of the page before. */
        NEXT_PAGE,
        /** The number of the records of an entity set, by {@code /$count}. */
        COUNT
    }

    private final Kind kind;
    private final String service;
    private final String entitySet;
    private URI uri;
    private final Map<String, String> headers;

    RequestSpec(Kind kind, String service, String entitySet, URI uri, Map<String, String> headers) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.service = Objects.requireNonNull(service, "service");
        this.entitySet = entitySet;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = new LinkedHashMap<>(headers);
    }

    public Kind kind() {
        return kind;
    }

    /** Returns the schema of the service. */
    public String service() {
        return service;
    }

    /** Returns the entity set, or {@code null} for the {@code $metadata}. */
    public String entitySet() {
        return entitySet;
    }

    /** Returns the URL of the request, with the query options added so far. */
    public URI uri() {
        return uri;
    }

    /**
     * Returns the value of a query option of the URL, decoded, or
     * {@code null} if the URL does not have it; of an option that appears
     * more than once, the first.
     */
    public String parameter(String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (decode(eq < 0 ? pair : pair.substring(0, eq)).equals(name)) {
                return eq < 0 ? "" : decode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    /**
     * Adds a query option to the URL, encoded, unless the URL has it
     * already, as a link to a next page that keeps the options of the
     * query usually does.
     *
     * @return this request
     */
    public RequestSpec parameter(String name, String value) {
        Objects.requireNonNull(value, "value");
        if (Objects.requireNonNull(name, "name").isEmpty()) {
            throw new IllegalArgumentException("The name of a query option must not be empty");
        }
        if (parameter(name) == null) {
            String query = uri.getRawQuery();
            String s = uri.toString();
            int hash = s.indexOf('#');
            String head = hash < 0 ? s : s.substring(0, hash);
            uri = URI.create(head + (query == null ? "?" : query.isEmpty() ? "" : "&") + ODataQuery.option(name)
                    + "=" + ODataQuery.option(value) + (hash < 0 ? "" : s.substring(hash)));
        }
        return this;
    }

    /**
     * Sets a header, in place of one of the same name in any case; the
     * header {@code Authorization} of the authentication of the source
     * replaces one set here.
     *
     * @return this request
     * @throws IllegalArgumentException if the HTTP client does not let a
     *         request set the header, such as {@code Host}
     */
    public RequestSpec header(String name, String value) {
        HttpTransport.checkHeader(name, value);
        headers.keySet().removeIf(name::equalsIgnoreCase);
        headers.put(name, value);
        return this;
    }

    /** Returns the headers of the request besides those of the source and its authentication. */
    public Map<String, String> headers() {
        return Collections.unmodifiableMap(headers);
    }

    /** Decodes a part of a query, with {@code +} as it is. */
    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
