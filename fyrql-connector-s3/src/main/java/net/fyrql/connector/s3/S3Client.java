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

package net.fyrql.connector.s3;

import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.SourceRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A client of the API of S3 over {@link HttpClient}, with the few
 * operations FyrQL needs: the size and entity tag of an object, a range of
 * it or all of it, the objects under a prefix, an object written at once
 * or in parts, and the deletion of objects and the creation and deletion
 * of buckets. Requests are signed with {@link SigV4} if there are
 * credentials. A request that fails on the network, with a status of 500
 * or more or with 429 is tried again up to a number of times, after 200
 * milliseconds and then twice as long each time; one whose answer has been
 * read in part is not.
 */
final class S3Client implements AutoCloseable {

    /** The size and the entity tag of an object. */
    record Head(long size, String etag) {
    }

    /** An object under a prefix: its key, size and entity tag. */
    record Listed(String key, long size, String etag) {
    }

    private static final long BACKOFF_MILLIS = 200;
    private static final int MAX_ERROR_BODY = 1 << 16;
    /** The most bytes of XML an answer may have, such as a list of 1000 objects with long keys. */
    static final int MAX_XML = 16 << 20;

    private final URI endpoint;
    private final String region;
    private final boolean pathStyle;
    private final S3Credentials credentials;
    private final Duration timeout;
    private final int retries;
    private final HttpClient http;
    private final String source;

    /**
     * Creates a client.
     *
     * @param source the name of the source, for the requests it counts, see {@link SourceRequest}
     * @param endpoint the endpoint, such as {@code https://s3.eu-central-1.amazonaws.com}
     * @param region the region of the signatures
     * @param pathStyle whether the bucket is the first part of the path
     *        rather than of the host name
     * @param credentials the credentials, or {@code null} for requests without signatures
     * @param timeout the time to wait for a connection and for an answer
     * @param retries how often a failed request is tried again
     */
    S3Client(String source, URI endpoint, String region, boolean pathStyle, S3Credentials credentials,
            Duration timeout, int retries) {
        this.source = Objects.requireNonNull(source, "source");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.region = Objects.requireNonNull(region, "region");
        this.pathStyle = pathStyle;
        this.credentials = credentials;
        this.timeout = timeout;
        this.retries = retries;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    URI endpoint() {
        return endpoint;
    }

    String region() {
        return region;
    }

    boolean pathStyle() {
        return pathStyle;
    }

    /**
     * Returns the size and entity tag of an object.
     *
     * @throws NoSuchFileException if there is none
     */
    Head head(S3Location location) throws IOException {
        HttpResponse<InputStream> r = send("HEAD", location.bucket(), location.key(), Map.of(), Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "HEAD", location.toString());
            }
            long size = r.headers().firstValueAsLong("content-length").orElseThrow(() ->
                    new IOException("S3 answered HEAD " + location + " without a length"));
            return new Head(size, r.headers().firstValue("etag").orElse(""));
        }
    }

    /**
     * Reads a range of an object into a buffer, as many bytes as the
     * buffer has room for, which the object must hold.
     *
     * @param etag the entity tag the object must still have, or {@code null}
     */
    void read(S3Location location, String etag, long position, ByteBuffer buffer) throws IOException {
        int length = buffer.remaining();
        if (length == 0) {
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("range", "bytes=" + position + "-" + (position + length - 1));
        if (etag != null && !etag.isEmpty()) {
            headers.put("if-match", etag);
        }
        HttpResponse<InputStream> r = send("GET", location.bucket(), location.key(), Map.of(), headers, null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 206 && r.statusCode() != 200) {
                throw error(r, "GET", location.toString());
            }
            if (r.statusCode() == 200) {
                body.skipNBytes(position);
            }
            byte[] bytes = body.readNBytes(length);
            if (bytes.length < length) {
                throw new IOException("S3 answered GET " + location + " with " + bytes.length + " bytes instead of "
                        + length);
            }
            buffer.put(bytes);
        }
    }

    /**
     * Opens a stream over a whole object.
     *
     * @param etag the entity tag the object must still have, or {@code null}
     */
    InputStream get(S3Location location, String etag) throws IOException {
        Map<String, String> headers = etag == null || etag.isEmpty() ? Map.of() : Map.of("if-match", etag);
        HttpResponse<InputStream> r = send("GET", location.bucket(), location.key(), Map.of(), headers, null, 0);
        if (r.statusCode() != 200) {
            try (InputStream body = r.body()) {
                throw error(r, "GET", location.toString());
            }
        }
        return r.body();
    }

    /**
     * Returns the objects whose keys start with a prefix, sorted by their
     * keys, with a delimiter only those without the delimiter after the
     * prefix.
     *
     * @param delimiter the delimiter, such as {@code /}, or {@code null}
     */
    List<Listed> list(String bucket, String prefix, String delimiter) throws IOException {
        List<Listed> objects = new ArrayList<>();
        String token = null;
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("list-type", "2");
            query.put("prefix", prefix);
            if (delimiter != null) {
                query.put("delimiter", delimiter);
            }
            if (token != null) {
                query.put("continuation-token", token);
            }
            HttpResponse<InputStream> r = send("GET", bucket, null, query, Map.of(), null, 0);
            Xml.Element result;
            try (InputStream body = r.body()) {
                if (r.statusCode() != 200) {
                    throw error(r, "GET", S3Location.PREFIX + bucket + "/" + prefix);
                }
                result = Xml.parse(xml(body, "GET", S3Location.PREFIX + bucket + "/" + prefix));
            }
            for (Xml.Element c : result.all("Contents")) {
                String size = c.text("Size");
                objects.add(new Listed(c.text("Key"), size == null ? 0 : Long.parseLong(size.strip()),
                        Optional.ofNullable(c.text("ETag")).orElse("")));
            }
            token = "true".equals(result.text("IsTruncated")) ? result.text("NextContinuationToken") : null;
        } while (token != null && !token.isEmpty());
        objects.sort((a, b) -> a.key().compareTo(b.key()));
        return objects;
    }

    /** Writes an object at once. */
    void put(S3Location location, byte[] bytes, int length) throws IOException {
        HttpResponse<InputStream> r = send("PUT", location.bucket(), location.key(), Map.of(), Map.of(), bytes,
                length);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "PUT", location.toString());
            }
        }
    }

    /** Starts to write an object in parts and returns the id of the upload. */
    String createMultipart(S3Location location) throws IOException {
        HttpResponse<InputStream> r = send("POST", location.bucket(), location.key(), Map.of("uploads", ""),
                Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "POST", location.toString());
            }
            String id = Xml.parse(xml(body, "POST", location.toString())).text("UploadId");
            if (id == null || id.isEmpty()) {
                throw new IOException("S3 started an upload of " + location + " without an id");
            }
            return id;
        }
    }

    /** Writes a part of an object and returns its entity tag. */
    String uploadPart(S3Location location, String uploadId, int part, byte[] bytes, int length) throws IOException {
        HttpResponse<InputStream> r = send("PUT", location.bucket(), location.key(), Map.of("partNumber",
                Integer.toString(part), "uploadId", uploadId), Map.of(), bytes, length);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "PUT", location + " (part " + part + ")");
            }
            return r.headers().firstValue("etag").orElseThrow(() ->
                    new IOException("S3 answered the part " + part + " of " + location + " without an entity tag"));
        }
    }

    /** Makes an object of its parts, which then appears. */
    void completeMultipart(S3Location location, String uploadId, List<String> etags) throws IOException {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (int p = 0; p < etags.size(); p++) {
            xml.append("<Part><PartNumber>").append(p + 1).append("</PartNumber><ETag>")
                    .append(Xml.escape(etags.get(p))).append("</ETag></Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        HttpResponse<InputStream> r = send("POST", location.bucket(), location.key(), Map.of("uploadId", uploadId),
                Map.of("content-type", "application/xml"), bytes, bytes.length);
        byte[] answer;
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "POST", location.toString());
            }
            answer = xml(body, "POST", location.toString());
        }
        // S3 may report an error of the completion with the status 200.
        if (answer.length > 0) {
            Xml.Element result = Xml.parse(answer);
            if (result.name().equals("Error")) {
                throw new S3Exception("S3 refused to complete the upload of " + location + ": " + result.text("Code")
                        + " (" + result.text("Message") + ")", 200, Optional.ofNullable(result.text("Code"))
                        .orElse(""));
            }
        }
    }

    /** Abandons an object written in parts. */
    void abortMultipart(S3Location location, String uploadId) throws IOException {
        HttpResponse<InputStream> r = send("DELETE", location.bucket(), location.key(), Map.of("uploadId", uploadId),
                Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 204 && r.statusCode() != 200) {
                throw error(r, "DELETE", location + " (upload)");
            }
        }
    }

    /** Deletes an object, if there is one. */
    void delete(S3Location location) throws IOException {
        HttpResponse<InputStream> r = send("DELETE", location.bucket(), location.key(), Map.of(), Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 204 && r.statusCode() != 200) {
                throw error(r, "DELETE", location.toString());
            }
        }
    }

    /** Creates a bucket. */
    void createBucket(String bucket) throws IOException {
        HttpResponse<InputStream> r = send("PUT", bucket, null, Map.of(), Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 200) {
                throw error(r, "PUT", S3Location.PREFIX + bucket);
            }
        }
    }

    /** Deletes an empty bucket. */
    void deleteBucket(String bucket) throws IOException {
        HttpResponse<InputStream> r = send("DELETE", bucket, null, Map.of(), Map.of(), null, 0);
        try (InputStream body = r.body()) {
            if (r.statusCode() != 204 && r.statusCode() != 200) {
                throw error(r, "DELETE", S3Location.PREFIX + bucket);
            }
        }
    }

    /**
     * Sends a request, again after a failure that may pass; each attempt
     * counts as a request for the scan the thread works for, the bytes of
     * the body as they are read, see {@link SourceCounters}.
     *
     * @param key the key, or {@code null} for a request to the bucket
     * @param headers further headers by their names in lower case, all signed
     * @param body the payload, or {@code null}
     */
    private HttpResponse<InputStream> send(String method, String bucket, String key, Map<String, String> query,
            Map<String, String> headers, byte[] body, int length) throws IOException {
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/+$", "");
        String host = endpoint.getHost();
        if (pathStyle) {
            path += "/" + S3Location.encode(bucket, false) + (key == null ? "" : "/" + S3Location.encode(key, true));
        } else {
            host = bucket + "." + host;
            path += "/" + (key == null ? "" : S3Location.encode(key, true));
        }
        String authority = host + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        String canonicalQuery = SigV4.canonicalQuery(query);
        URI uri = URI.create(endpoint.getScheme() + "://" + authority + path
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));
        String payloadHash = body == null ? SigV4.EMPTY_HASH : SigV4.sha256(body, 0, length);
        for (int attempt = 0; ; attempt++) {
            Instant now = Instant.now();
            Map<String, String> signed = new LinkedHashMap<>(headers);
            signed.put("host", authority);
            signed.put("x-amz-date", SigV4.AMZ_DATE.format(now));
            signed.put("x-amz-content-sha256", payloadHash);
            if (credentials != null && credentials.sessionToken() != null) {
                signed.put("x-amz-security-token", credentials.sessionToken());
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).method(method,
                    body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body, 0, length));
            signed.forEach((name, value) -> {
                if (!name.equals("host")) {
                    header(request, name, value);
                }
            });
            if (credentials != null) {
                header(request, "authorization", SigV4.authorization(method, path, canonicalQuery, signed,
                        payloadHash, now, region, credentials.accessKey(), credentials.secretKey()));
            }
            HttpResponse<InputStream> response;
            SourceCounters counters = SourceCounters.current();
            SourceRequest call = SourceRequest.start();
            try {
                response = http.send(request.build(), info -> HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodySubscribers.ofInputStream(), counters::counted));
            } catch (InterruptedException e) {
                call.done(source, method, uri.toString(), 0, 0);
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for S3: " + method + " " + uri);
            } catch (IOException e) {
                call.done(source, method, uri.toString(), 0, 0);
                if (attempt >= retries) {
                    throw new IOException("S3 did not answer " + method + " " + uri + ": " + e, e);
                }
                pause(attempt);
                continue;
            }
            int status = response.statusCode();
            call.done(source, method, uri.toString(), status,
                    response.headers().firstValueAsLong("Content-Length").orElse(0));
            if ((status >= 500 || status == 429) && attempt < retries) {
                response.body().close();
                pause(attempt);
                continue;
            }
            return response;
        }
    }

    /**
     * Sets a header of a request; the message of a value the HTTP client
     * does not take names the header, never the value, which may hold the
     * session token or the access key.
     */
    private static void header(HttpRequest.Builder request, String name, String value) {
        try {
            request.header(name, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The header " + name
                    + " of a request to S3 has a value that a request cannot carry, such as one with a line break");
        }
    }

    /**
     * Reads the XML of an answer, at most {@link #MAX_XML} bytes: an answer
     * of S3 is small, a larger one is no answer of S3.
     *
     * @throws IOException if the answer is larger or cannot be read
     */
    static byte[] xml(InputStream body, String method, String what) throws IOException {
        byte[] bytes = body.readNBytes(MAX_XML + 1);
        if (bytes.length > MAX_XML) {
            throw new IOException("S3 answered " + method + " " + what + " with more than " + (MAX_XML >> 20)
                    + " MiB of XML");
        }
        return bytes;
    }

    private static void pause(int attempt) throws InterruptedIOException {
        try {
            Thread.sleep(BACKOFF_MILLIS << Math.min(attempt, 6));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to ask S3 again");
        }
    }

    /** Returns the error of an answer, from the code and message of its body if it has one. */
    private static IOException error(HttpResponse<InputStream> r, String method, String what) throws IOException {
        int status = r.statusCode();
        byte[] body = r.body().readNBytes(MAX_ERROR_BODY);
        String code = null;
        String message = null;
        if (body.length > 0) {
            try {
                Xml.Element e = Xml.parse(body);
                code = e.text("Code");
                message = e.text("Message");
            } catch (IOException e) {
                // The body is no error document of S3; the status says enough.
            }
        }
        if (status == 404 && (code == null || code.equals("NoSuchKey") || code.equals("NotFound"))) {
            return new NoSuchFileException(what);
        }
        if (status == 412) {
            return new S3Exception("The object " + what + " changed since it was looked up", status,
                    code == null ? "PreconditionFailed" : code);
        }
        return new S3Exception("S3 refused " + method + " " + what + ": " + status + (code == null ? "" : " " + code)
                + (message == null || message.isEmpty() ? "" : " (" + message + ")"), status,
                code == null ? "" : code);
    }

    @Override
    public void close() {
        http.close();
    }
}
