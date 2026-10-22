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
 * values of the headers never appear in messages.
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
            throw new HttpSourceException("GET " + uri + " answered with the status " + response.status()
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

    private Response send(String method, String kind, URI uri, HttpRequest.BodyPublisher body,
            Map<String, String> extra) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).method(method, body).timeout(timeout);
        if (!hasHeader(this.headers, "Accept") && !hasHeader(extra, "Accept")) {
            request.header("Accept", accept);
        }
        if (!hasHeader(this.headers, "Accept-Encoding") && !hasHeader(extra, "Accept-Encoding")) {
            request.header("Accept-Encoding", "gzip");
        }
        this.headers.forEach((k, v) -> {
            if (!hasHeader(extra, k)) {
                request.header(k, v);
            }
        });
        extra.forEach(request::header);
        HttpResponse<InputStream> response;
        SourceCounters counters = SourceCounters.current();
        SourceRequest call = SourceRequest.start();
        try {
            response = client.send(request.build(), info -> HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofInputStream(), counters::counted));
        } catch (HttpTimeoutException e) {
            call.done(source, kind, uri.toString(), 0, 0);
            throw new HttpSourceException(method + " " + uri + " timed out after " + timeout.toMillis() + " ms",
                    uri, -1, e);
        } catch (IOException e) {
            call.done(source, kind, uri.toString(), 0, 0);
            throw new HttpSourceException(method + " " + uri + " failed: " + e, uri, -1, e);
        } catch (InterruptedException e) {
            call.done(source, kind, uri.toString(), 0, 0);
            Thread.currentThread().interrupt();
            throw new HttpSourceException(method + " " + uri + " was interrupted", uri, -1, e);
        }
        int status = response.statusCode();
        call.done(source, kind, uri.toString(), status,
                response.headers().firstValueAsLong("Content-Length").orElse(0));
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").strip()
                .toLowerCase(Locale.ROOT);
        if (!encoding.equals("identity") && !encoding.equals("gzip")) {
            quietlyClose(response.body());
            throw new HttpSourceException(method + " " + uri + " answered with the Content-Encoding " + encoding
                    + ", of which only gzip is supported", uri, status, null);
        }
        InputStream uncompressed;
        try {
            uncompressed = uncompressed(response.body());
        } catch (IOException e) {
            quietlyClose(response.body());
            throw new HttpSourceException(method + " " + uri + " answered with a body that cannot be read: " + e,
                    uri, status, e);
        }
        return new Response(uri, status, response.headers(), response.body(), uncompressed);
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
            HttpRequest.newBuilder().header(header, value);
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
         * own; the transport does not shut it down. By default the
         * transport creates one that follows redirects except from https
         * to http.
         */
        public Builder client(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        public HttpTransport build() {
            boolean owns = client == null;
            HttpClient c = owns ? HttpClient.newBuilder().connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL).build() : client;
            return new HttpTransport(this, c, owns);
        }
    }
}
