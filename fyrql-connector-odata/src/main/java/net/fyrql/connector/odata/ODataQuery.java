/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.odata;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The URLs of the requests to a service: those of its {@code $metadata}
 * and of the queries of its entity sets, with their system query options.
 * A URL of a service, its root, may have query options of its own, such as
 * {@code sap-client=100}, which every request carries first. Paths and
 * query options are encoded as RFC 3986 requires: every character except
 * the unreserved ones and those that OData writes as they are, such as
 * {@code $}, {@code '}, {@code (}, {@code ,} and {@code /}, is
 * percent-encoded in UTF-8, also {@code +}, which some servers read as a
 * space, {@code &}, {@code =}, {@code ;} and {@code #}.
 */
final class ODataQuery {

    /** The characters other than letters and digits that stay as they are in a path segment. */
    private static final String SEGMENT = "-._~!$'()*,:@";

    /** The characters other than letters and digits that stay as they are in a query option. */
    private static final String QUERY = "-._~!$'()*,:@/";

    private ODataQuery() {
    }

    /**
     * Returns the URL of a resource of a service: its root without query,
     * ending in {@code /}, followed by the path, and then the query options
     * of the root followed by those given.
     *
     * @param path the path relative to the root, its segments encoded with {@link #segment}
     * @param options the system query options, such as {@code $select}, unencoded, in order
     */
    static URI of(URI root, String path, Map<String, String> options) {
        StringBuilder b = new StringBuilder(base(root)).append(path);
        String query = root.getRawQuery();
        boolean first = true;
        if (query != null && !query.isEmpty()) {
            b.append('?').append(query);
            first = false;
        }
        for (Map.Entry<String, String> e : options.entrySet()) {
            b.append(first ? '?' : '&').append(encode(e.getKey(), QUERY)).append('=')
                    .append(encode(e.getValue(), QUERY));
            first = false;
        }
        return URI.create(b.toString());
    }

    /** Returns the URL of the root of a service without its query and fragment, ending in {@code /}. */
    static String base(URI root) {
        String s = root.getScheme() + "://" + root.getRawAuthority() + (root.getRawPath() == null ? ""
                : root.getRawPath());
        return s.endsWith("/") ? s : s + "/";
    }

    /** Encodes the name or the value of a query option. */
    static String option(String s) {
        return encode(s, QUERY);
    }

    /** Encodes a segment of a path, such as the name of an entity set. */
    static String segment(String segment) {
        return encode(segment, SEGMENT);
    }

    /**
     * Returns the system query options of a query of an entity set.
     *
     * @param select the properties to select, or the paths into them, such
     *        as {@code Address/City}; empty to select all
     * @param filter the {@code $filter}, or {@code null}
     * @param orderBy the {@code $orderby}, or {@code null}
     * @param top the {@code $top}, or {@code null}
     * @param count whether to ask for the number of all records
     */
    static Map<String, String> options(ODataVersion version, List<String> select, String filter, String orderBy,
            Integer top, boolean count) {
        Map<String, String> options = new LinkedHashMap<>();
        if (!select.isEmpty()) {
            options.put("$select", String.join(",", select));
        }
        if (filter != null) {
            options.put("$filter", filter);
        }
        if (orderBy != null) {
            options.put("$orderby", orderBy);
        }
        if (top != null) {
            options.put("$top", Integer.toString(top));
        }
        if (count) {
            if (version == ODataVersion.V2) {
                options.put("$inlinecount", "allpages");
            } else {
                options.put("$count", "true");
            }
        }
        if (version == ODataVersion.V2) {
            options.put("$format", "json");
        }
        return options;
    }

    /**
     * Returns the URL of the next page of an answer: the link as it is if
     * it is absolute, otherwise resolved against the root of the service.
     *
     * @throws IllegalArgumentException if the link is no URL
     */
    static URI next(URI root, String link) {
        URI uri;
        try {
            uri = new URI(link.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return uri.isAbsolute() ? uri : URI.create(base(root)).resolve(uri);
    }

    /** Tells whether two URLs have the same scheme, host and port, the ports that their schemes imply included. */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().toLowerCase(Locale.ROOT).equals("https") ? 443 : 80;
    }

    /**
     * Returns a URL for a message: without user information, query and
     * fragment, since the query may hold values that are not meant to be
     * shown, such as a key in the URL of a service.
     */
    static String shown(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return (uri.getScheme() == null ? "" : uri.getScheme() + ":") + (uri.getRawPath() == null ? ""
                    : uri.getRawPath());
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    private static String encode(String s, String keep) {
        StringBuilder b = new StringBuilder(s.length());
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c < 0x80
                    && keep.indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return b.toString();
    }
}
