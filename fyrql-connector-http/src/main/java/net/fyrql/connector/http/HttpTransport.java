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

package net.fyrql.connector.http;

import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceRequest;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Sends the requests of an HTTP source and hands their answers over as
 * streams: every request carries the headers of the transport and of the
 * request, asks for the type the transport accepts, and accepts gzip; an
 * answer compressed with gzip, also one without {@code Content-Encoding}
 * whose body starts as gzip does, is uncompressed. A refused connection, a
 * timeout and an encoding other than gzip are an
 * {@link HttpSourceException} that names the URL; so is a status other
 * than 2xx where the caller asks for it, with the start of the body. The
 * values of the headers never appear in messages, and URLs appear in
 * messages and in events of {@link SourceRequest} as {@link #shown(URI)}
 * gives them, without user information and query. Redirects are followed
 * as {@link Builder#client} says, without the credentials of the headers to
 * another origin.
 * <p>
 * Each request counts for the scan the thread works for, the bytes of its
 * body as they are read, see {@link SourceCounters}.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class HttpTransport implements AutoCloseable {

    /** The time to connect and to wait for the status of an answer, by default. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** The most bytes of the body of an answer that {@link Response#excerpt()} shows. */
    private static final int EXCERPT = 200;

    /** The most redirects a request follows. */
    private static final int MAX_REDIRECTS = 5;

    /** The headers, in lower case, that a redirect to another origin keeps, since they hold no credentials. */
    private static final Set<String> SAFE_HEADERS = Set.of("accept", "accept-encoding", "accept-language",
            "content-type");

    private final Map<String, String> headers;
    private final String accept;
    private final Duration timeout;
    private final HttpClient client;
    private final boolean ownsClient;
    private final String source;

    private HttpTransport(Builder b, HttpClient client, boolean ownsClient) {
        this.source = b.source;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.accept = b.accept;
        this.timeout = b.timeout;
        this.client = client;
        this.ownsClient = ownsClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns the time to connect and to wait for the status of an answer. */
    public Duration timeout() {
        return timeout;
    }

    /**
     * The answer to a request: its status and headers, its body as it
     * comes, and its body uncompressed. Closing it closes the body.
     */
    public record Response(URI uri, int status, HttpHeaders headers, InputStream raw, InputStream body)
            implements Closeable {

        /** Tells whether the status is 2xx. */
        public boolean ok() {
            return status / 100 == 2;
        }

        /** Returns the media type of the body without parameters, in lower case, or "" if there is none. */
        public String contentType() {
            String type = headers.firstValue("Content-Type").orElse("");
            int semicolon = type.indexOf(';');
            return (semicolon < 0 ? type : type.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
        }

        /** Returns the start of the body on one line, for a message, and closes the body. */
        public String excerpt() {
            try (InputStream in = body) {
                byte[] bytes = in.readNBytes(EXCERPT + 1);
                boolean more = bytes.length > EXCERPT;
                String text = new String(bytes, 0, Math.min(bytes.length, EXCERPT), StandardCharsets.UTF_8);
                text = text.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
                return more && !text.isEmpty() ? text + " ..." : text;
            } catch (IOException e) {
                return "";
            }
        }

        /**
         * Closes the body as it comes, so that a reader that waits on it
         * fails at once; this may be called from another thread.
         */
        public void abort() {
            quietlyClose(raw);
        }

        @Override
        public void close() {
            quietlyClose(body);
            quietlyClose(raw);
        }
    }

    /**
     * Sends a {@code GET} and returns the answer.
     *
     * @param headers headers of this request, which replace those of the transport of the same name
     * @throws HttpSourceException if the status is not 2xx or there is no answer
     */
    public Response get(URI uri, Map<String, String> headers) {
        Response response = getAllowingErrors(uri, headers);
        if (!response.ok()) {
            String excerpt = response.excerpt();
            throw new HttpSourceException("GET " + shown(uri) + " answered with the status " + response.status()
                    + (excerpt.isEmpty() ? "" : ": " + excerpt), uri, response.status(), null);
        }
        return response;
    }

    /**
     * Sends a {@code GET} and returns the answer, whatever its status, so
     * that the caller can read an error from its body.
     *
     * @param headers headers of this request, which replace those of the transport of the same name
     * @throws HttpSourceException if there is no answer
     */
    public Response getAllowingErrors(URI uri, Map<String, String> headers) {
        return getAllowingErrors(uri, headers, "GET");
    }

    /**
     * Sends a {@code GET} as {@link #getAllowingErrors(URI, Map)} does, that
     * counts as a request of a kind, such as {@code query}, see
     * {@link SourceRequest}.
     */
    public Response getAllowingErrors(URI uri, Map<String, String> headers, String kind) {
        return send("GET", kind, uri, HttpRequest.BodyPublishers.noBody(), headers);
    }

    /**
     * Sends a {@code POST} with a body and returns the answer, whatever its
     * status.
     *
     * @param headers headers of this request, which replace those of the transport of the same name
     * @throws HttpSourceException if there is no answer
     */
    public Response post(URI uri, String contentType, byte[] body, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", contentType);
        return send("POST", "POST", uri, HttpRequest.BodyPublishers.ofByteArray(body), all);
    }

    /**
     * Sends a request and follows the redirects of the answers if the
     * transport created its client, see {@link Builder#client}.
     */
    private Response send(String method, String kind, URI uri, HttpRequest.BodyPublisher body,
            Map<String, String> extra) {
        URI at = uri;
        String m = method;
        HttpRequest.BodyPublisher b = body;
        HttpResponse<InputStream> response;
        for (int redirects = 0; ; redirects++) {
            response = exchange(m, kind, at, b, extra, origin(at).equals(origin(uri)));
            URI next = ownsClient ? redirect(at, response) : null;
            if (next == null) {
                break;
            }
            quietlyClose(response.body());
            if (redirects == MAX_REDIRECTS) {
                throw new HttpSourceException(method + " " + shown(uri) + " was redirected more than "
                        + MAX_REDIRECTS + " times", uri, response.statusCode(), null);
            }
            int status = response.statusCode();
            if (status == 303 || (status == 301 || status == 302) && m.equals("POST")) {
                m = "GET";
                b = HttpRequest.BodyPublishers.noBody();
            }
            at = next;
        }
        String shown = shown(at);
        int status = response.statusCode();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").strip()
                .toLowerCase(Locale.ROOT);
        if (!encoding.equals("identity") && !encoding.equals("gzip")) {
            quietlyClose(response.body());
            throw new HttpSourceException(method + " " + shown + " answered with the Content-Encoding " + encoding
                    + ", of which only gzip is supported", uri, status, null);
        }
        InputStream uncompressed;
        try {
            uncompressed = uncompressed(response.body());
        } catch (IOException e) {
            quietlyClose(response.body());
            throw new HttpSourceException(method + " " + shown + " answered with a body that cannot be read: " + e,
                    uri, status, e);
        }
        return new Response(uri, status, response.headers(), response.body(), uncompressed);
    }

    /**
     * Sends one request and returns the answer as it comes. A request to
     * another origin than the first one of its redirects carries none of
     * the headers of the transport and of the request but
     * {@link #SAFE_HEADERS}, so that a credential goes nowhere else.
     */
    private HttpResponse<InputStream> exchange(String method, String kind, URI uri, HttpRequest.BodyPublisher body,
            Map<String, String> extra, boolean sameOrigin) {
        String shown = shown(uri);
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(uri).method(method, body).timeout(timeout);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot send a request to " + shown + ": the URL is not http or"
                    + " https with a host");
        }
        Map<String, String> configured = sameOrigin ? this.headers : safe(this.headers);
        Map<String, String> added = sameOrigin ? extra : safe(extra);
        if (!hasHeader(configured, "Accept") && !hasHeader(added, "Accept")) {
            request.header("Accept", accept);
        }
        if (!hasHeader(configured, "Accept-Encoding") && !hasHeader(added, "Accept-Encoding")) {
            request.header("Accept-Encoding", "gzip");
        }
        configured.forEach((k, v) -> {
            if (!hasHeader(added, k)) {
                header(request, k, v);
            }
        });
        added.forEach((k, v) -> header(request, k, v));
        HttpResponse<InputStream> response;
        SourceCounters counters = SourceCounters.current();
        SourceRequest call = SourceRequest.start();
        try {
            response = client.send(request.build(), info -> HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofInputStream(), counters::counted));
        } catch (HttpTimeoutException e) {
            call.done(source, kind, shown, 0, 0);
            throw new HttpSourceException(method + " " + shown + " timed out after " + timeout.toMillis() + " ms",
                    uri, -1, e);
        } catch (IOException e) {
            call.done(source, kind, shown, 0, 0);
            throw new HttpSourceException(method + " " + shown + " failed: " + e, uri, -1, e);
        } catch (InterruptedException e) {
            call.done(source, kind, shown, 0, 0);
            Thread.currentThread().interrupt();
            throw new HttpSourceException(method + " " + shown + " was interrupted", uri, -1, e);
        }
        call.done(source, kind, shown, response.statusCode(),
                response.headers().firstValueAsLong("Content-Length").orElse(0));
        return response;
    }

    /**
     * Returns where an answer redirects to, or {@code null} if it is no
     * redirect to follow: one with the status 301, 302, 303, 307 or 308
     * and a {@code Location} with http or https, but not from https to
     * http.
     */
    private static URI redirect(URI at, HttpResponse<InputStream> response) {
        int status = response.statusCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return null;
        }
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) {
            return null;
        }
        URI next;
        try {
            next = at.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || next.getHost() == null) {
            return null;
        }
        return scheme.equals("http") && at.getScheme().equalsIgnoreCase("https") ? null : next;
    }

    /** Returns the scheme, host and port of a URL, in lower case and with the default port. */
    private static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT)) + ":" + port;
    }

    /** Returns the headers that hold no credentials, see {@link #SAFE_HEADERS}. */
    private static Map<String, String> safe(Map<String, String> headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (SAFE_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                safe.put(k, v);
            }
        });
        return safe;
    }

    /** Sets a header of a request, see {@link #checkHeader}. */
    private static void header(HttpRequest.Builder request, String header, String value) {
        checkHeader(header, value);
        request.header(header, value);
    }

    /**
     * Checks that a request may carry a header with a value; the message
     * names the header, never the value, which may be a secret.
     *
     * @throws IllegalArgumentException if the HTTP client does not let a
     *         request set the header, such as {@code Host}, or not with
     *         this value, such as one with a line break
     */
    public static void checkHeader(String header, String value) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(value, "The value of the header " + header);
        HttpRequest.newBuilder().header(header, "");
        try {
            HttpRequest.newBuilder().header(header, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The header " + header
                    + " has a value that a request cannot carry, such as one with a line break");
        }
    }

    /**
     * Returns a URL for a message or an event: without user information,
     * query and fragment, since they may hold values that are not meant to
     * be shown, such as a key in the URL of a service.
     */
    public static String shown(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            return (uri.getScheme() == null ? "" : uri.getScheme() + ":") + (uri.getRawPath() == null ? ""
                    : uri.getRawPath());
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    /**
     * Parses a URL; the message of a URL that is not one names what it is
     * for and why, but not the URL, which may hold a key.
     *
     * @param what what the URL is for, such as {@code The URL of the table t}
     * @throws IllegalArgumentException if the text is not a URL
     */
    public static URI url(String text, String what) {
        Objects.requireNonNull(text, what);
        try {
            return new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(what + " is not a URL: " + e.getReason()
                    + (e.getIndex() >= 0 ? " at index " + e.getIndex() : ""));
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String header) {
        for (String h : headers.keySet()) {
            if (h.equalsIgnoreCase(header)) {
                return true;
            }
        }
        return false;
    }

    /** Uncompresses a body that starts as gzip does, whatever its headers say. */
    private static InputStream uncompressed(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 1 << 16);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        return first == 0x1f && second == 0x8b ? new GZIPInputStream(buffered, 1 << 16) : buffered;
    }

    private static void quietlyClose(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            // Nothing more to do; the body is no longer needed.
        }
    }

    /** Shuts the HTTP client down if the transport created it; a client that was given stays open. */
    @Override
    public void close() {
        if (ownsClient) {
            client.shutdown();
        }
    }

    /** Collects the settings of a transport. */
    public static final class Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String accept = "application/json";
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpClient client;
        private String source = "http";

        private Builder() {
        }

        /**
         * Sets the name of the source, for the requests it counts, see
         * {@link SourceRequest}; {@code http} by default.
         */
        public Builder source(String source) {
            this.source = Objects.requireNonNull(source, "source");
            return this;
        }

        /**
         * Adds a header that every request carries, such as
         * {@code Authorization}; a header of the same name replaces the
         * default {@code Accept} or {@code Accept-Encoding}.
         *
         * @throws IllegalArgumentException if the HTTP client does not let a
         *         request set the header, such as {@code Host}
         */
        public Builder header(String header, String value) {
            checkHeader(header, value);
            headers.put(header, value);
            return this;
        }

        /** Adds headers that every request carries, see {@link #header}. */
        public Builder headers(Map<String, String> headers) {
            headers.forEach(this::header);
            return this;
        }

        /** Sets the {@code Accept} header of a request without one; {@code application/json} by default. */
        public Builder accept(String accept) {
            HttpRequest.newBuilder().header("Accept", accept);
            this.accept = accept;
            return this;
        }

        /**
         * Sets the time to connect and to wait for the status of an answer;
         * reading the body has no time limit. 30 seconds by default.
         */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("The timeout must be positive: " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the HTTP client, for a proxy, TLS or authentication of its
         * own; the transport does not shut it down, and the client follows
         * redirects by its own rule. By default the transport creates one
         * and follows up to five redirects itself, except from https to
         * http; a request to another origin than the first one carries
         * none of the headers of the transport and of the request but
         * {@code Accept}, {@code Accept-Encoding}, {@code Accept-Language}
         * and {@code Content-Type}, so that credentials such as
         * {@code Authorization} go nowhere else.
         */
        public Builder client(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public HttpTransport build() {
            boolean owns = client == null;
            HttpClient c = owns ? HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NEVER).build() : client;
            return new HttpTransport(this, c, owns);
        }
    }
}
