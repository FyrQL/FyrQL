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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake of the API of S3 in memory on the loopback interface, with
 * buckets in the path as MinIO has them: objects with {@code HEAD},
 * {@code GET} of all or a range, {@code PUT} and {@code DELETE}; uploads in
 * parts; listings of version 2 in pages of {@link #pageSize}; buckets made
 * and deleted. With credentials it checks the signature of every request
 * with a signer of its own, and answers {@code SignatureDoesNotMatch} as S3
 * does. It keeps every request and can fail the next ones with a status,
 * so that tests see what a client asks for and how it tries again.
 */
public final class FakeS3Server implements AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "AWS4-HMAC-SHA256 Credential=([^/]+)/(\\d{8})/([^/]+)/s3/aws4_request, SignedHeaders=([a-z0-9;-]+),"
                    + " Signature=([0-9a-f]{64})");
    private static final Pattern PART = Pattern.compile(
            "<Part><PartNumber>(\\d+)</PartNumber><ETag>([^<]*)</ETag></Part>");

    /** An object: its bytes and entity tag. */
    private record StoredObject(byte[] bytes, String etag) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final String accessKey;
    private final String secretKey;
    private final Map<String, Map<String, StoredObject>> buckets = new TreeMap<>();
    private final Map<String, Map<Integer, StoredObject>> uploads = new LinkedHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger uploadCount = new AtomicInteger();
    private volatile int failures;
    private volatile int failureStatus;
    private volatile int pageSize = 1000;
    private volatile int minPartSize = S3Connector.MIN_PART_SIZE;

    private FakeS3Server(String accessKey, String secretKey) throws IOException {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Starts a server that checks the signatures of the credentials. */
    public static FakeS3Server start(String accessKey, String secretKey) {
        try {
            return new FakeS3Server(accessKey, secretKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Starts a server that takes requests without signatures, as for public buckets. */
    public static FakeS3Server anonymous() {
        return start(null, null);
    }

    /** Returns the endpoint, such as {@code http://127.0.0.1:50123}. */
    public String endpoint() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Returns the requests so far as {@code METHOD /path?query}, a range appended as {@code [bytes=a-b]}. */
    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** Forgets the requests. */
    public void clearRequests() {
        requests.clear();
    }

    /** Answers the next requests with a status and an error of S3, such as 503 for {@code SlowDown}. */
    public void fail(int count, int status) {
        failureStatus = status;
        failures = count;
    }

    /** Sets the objects of a page of a listing at most; 1000 by default. */
    public void pageSize(int size) {
        pageSize = size;
    }

    /** Sets the bytes a part of an upload but the last must have at least; 5 MiB by default, as in S3. */
    public void minPartSize(int size) {
        minPartSize = size;
    }

    /** Creates a bucket. */
    public synchronized void createBucket(String bucket) {
        buckets.putIfAbsent(bucket, new TreeMap<>());
    }

    /** Puts an object, creating its bucket. */
    public synchronized void put(String bucket, String key, byte[] bytes) {
        createBucket(bucket);
        buckets.get(bucket).put(key, new StoredObject(bytes.clone(), etag(bytes)));
    }

    /** Returns the bytes of an object, or {@code null}. */
    public synchronized byte[] get(String bucket, String key) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        StoredObject o = objects == null ? null : objects.get(key);
        return o == null ? null : o.bytes().clone();
    }

    /** Returns the keys of a bucket, sorted. */
    public synchronized List<String> keys(String bucket) {
        Map<String, StoredObject> objects = buckets.get(bucket);
        return objects == null ? List.of() : List.copyOf(objects.keySet());
    }

    /** Returns the number of uploads in parts that are neither complete nor abandoned. */
    public synchronized int openUploads() {
        return uploads.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HEX.formatHex(MessageDigest.getInstance("MD5").digest(bytes)) + "\"";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            String rawPath = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String range = exchange.getRequestHeaders().getFirst("Range");
            requests.add(method + " " + rawPath + (rawQuery == null ? "" : "?" + rawQuery)
                    + (range == null ? "" : " [" + range + "]"));
            if (failures > 0) {
                failures--;
                error(exchange, failureStatus, failureStatus == 503 ? "SlowDown" : "InternalError",
                        "Injected failure");
                return;
            }
            if (accessKey != null) {
                String problem = checkSignature(exchange, method, rawPath, rawQuery, body);
                if (problem != null) {
                    error(exchange, 403, problem.startsWith("Signature") ? "SignatureDoesNotMatch" : "AccessDenied",
                            problem);
                    return;
                }
            }
            Map<String, String> query = query(rawQuery);
            String path = rawPath.substring(1);
            int slash = path.indexOf('/');
            String bucket = decode(slash < 0 ? path : path.substring(0, slash));
            String key = slash < 0 || slash == path.length() - 1 ? null : decode(path.substring(slash + 1));
            synchronized (this) {
                if (key == null) {
                    bucket(exchange, method, bucket, query);
                } else {
                    object(exchange, method, bucket, key, query, body);
                }
            }
        } catch (RuntimeException e) {
            error(exchange, 500, "InternalError", e.toString());
        }
    }

    private void bucket(HttpExchange exchange, String method, String bucket, Map<String, String> query)
            throws IOException {
        switch (method) {
            case "PUT" -> {
                if (buckets.containsKey(bucket)) {
                    error(exchange, 409, "BucketAlreadyOwnedByYou", "The bucket exists");
                } else {
                    buckets.put(bucket, new TreeMap<>());
                    send(exchange, 200, new byte[0]);
                }
            }
            case "DELETE" -> {
                if (!buckets.containsKey(bucket)) {
                    error(exchange, 404, "NoSuchBucket", "The bucket does not exist");
                } else if (!buckets.get(bucket).isEmpty()) {
                    error(exchange, 409, "BucketNotEmpty", "The bucket is not empty");
                } else {
                    buckets.remove(bucket);
                    send(exchange, 204, null);
                }
            }
            case "GET" -> list(exchange, bucket, query);
            default -> error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    private void list(HttpExchange exchange, String bucket, Map<String, String> query) throws IOException {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            error(exchange, 404, "NoSuchBucket", "The bucket does not exist");
            return;
        }
        if (!"2".equals(query.get("list-type"))) {
            error(exchange, 400, "InvalidArgument", "Only listings of version 2");
            return;
        }
        String prefix = query.getOrDefault("prefix", "");
        String delimiter = query.get("delimiter");
        String after = query.get("continuation-token");
        int max = Math.min(pageSize, Integer.parseInt(query.getOrDefault("max-keys", "1000")));
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>")
                .append(Xml.escape(bucket)).append("</Name><Prefix>").append(Xml.escape(prefix)).append("</Prefix>");
        TreeSet<String> common = new TreeSet<>();
        int n = 0;
        String last = null;
        boolean truncated = false;
        for (Map.Entry<String, StoredObject> e : objects.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix) || after != null && key.compareTo(after) <= 0) {
                continue;
            }
            if (n == max) {
                truncated = true;
                break;
            }
            int d = delimiter == null ? -1 : key.indexOf(delimiter, prefix.length());
            if (d >= 0) {
                common.add(key.substring(0, d + delimiter.length()));
            } else {
                xml.append("<Contents><Key>").append(Xml.escape(key)).append("</Key><LastModified>"
                        + "2026-10-20T00:00:00.000Z</LastModified><ETag>").append(Xml.escape(e.getValue().etag()))
                        .append("</ETag><Size>").append(e.getValue().bytes().length)
                        .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            }
            n++;
            last = key;
        }
        for (String c : common) {
            xml.append("<CommonPrefixes><Prefix>").append(Xml.escape(c)).append("</Prefix></CommonPrefixes>");
        }
        xml.append("<KeyCount>").append(n).append("</KeyCount><MaxKeys>").append(max).append("</MaxKeys>")
                .append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            xml.append("<NextContinuationToken>").append(Xml.escape(last)).append("</NextContinuationToken>");
        }
        xml.append("</ListBucketResult>");
        send(exchange, 200, xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void object(HttpExchange exchange, String method, String bucket, String key, Map<String, String> query,
            byte[] body) throws IOException {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            error(exchange, 404, "NoSuchBucket", "The bucket does not exist");
            return;
        }
        String uploadId = query.get("uploadId");
        switch (method) {
            case "HEAD", "GET" -> {
                StoredObject o = objects.get(key);
                if (o == null) {
                    error(exchange, 404, "NoSuchKey", "The key does not exist");
                    return;
                }
                String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
                if (ifMatch != null && !ifMatch.equals(o.etag())) {
                    error(exchange, 412, "PreconditionFailed", "The entity tag does not match");
                    return;
                }
                exchange.getResponseHeaders().set("ETag", o.etag());
                if (method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(o.bytes().length));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range == null) {
                    send(exchange, 200, o.bytes());
                    return;
                }
                Matcher m = RANGE.matcher(range);
                if (!m.matches()) {
                    error(exchange, 400, "InvalidRange", range);
                    return;
                }
                int from = Integer.parseInt(m.group(1));
                int to = m.group(2).isEmpty() ? o.bytes().length - 1
                        : Math.min(Integer.parseInt(m.group(2)), o.bytes().length - 1);
                if (from >= o.bytes().length) {
                    error(exchange, 416, "InvalidRange", range);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/"
                        + o.bytes().length);
                byte[] part = new byte[to - from + 1];
                System.arraycopy(o.bytes(), from, part, 0, part.length);
                send(exchange, 206, part);
            }
            case "PUT" -> {
                if (uploadId != null) {
                    Map<Integer, StoredObject> parts = uploads.get(uploadId);
                    if (parts == null) {
                        error(exchange, 404, "NoSuchUpload", "The upload does not exist");
                        return;
                    }
                    StoredObject part = new StoredObject(body, etag(body));
                    parts.put(Integer.parseInt(query.get("partNumber")), part);
                    exchange.getResponseHeaders().set("ETag", part.etag());
                    send(exchange, 200, new byte[0]);
                } else {
                    StoredObject o = new StoredObject(body, etag(body));
                    objects.put(key, o);
                    exchange.getResponseHeaders().set("ETag", o.etag());
                    send(exchange, 200, new byte[0]);
                }
            }
            case "POST" -> {
                if (query.containsKey("uploads")) {
                    String id = "upload-" + uploadCount.incrementAndGet();
                    uploads.put(id, new TreeMap<>());
                    send(exchange, 200, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<InitiateMultipartUploadResult>"
                            + "<Bucket>" + Xml.escape(bucket) + "</Bucket><Key>" + Xml.escape(key) + "</Key><UploadId>"
                            + id + "</UploadId></InitiateMultipartUploadResult>").getBytes(StandardCharsets.UTF_8));
                } else if (uploadId != null) {
                    complete(exchange, objects, key, uploadId, new String(body, StandardCharsets.UTF_8));
                } else {
                    error(exchange, 400, "InvalidRequest", "POST without uploads or uploadId");
                }
            }
            case "DELETE" -> {
                if (uploadId != null) {
                    if (uploads.remove(uploadId) == null) {
                        error(exchange, 404, "NoSuchUpload", "The upload does not exist");
                        return;
                    }
                } else {
                    objects.remove(key);
                }
                send(exchange, 204, null);
            }
            default -> error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    private void complete(HttpExchange exchange, Map<String, StoredObject> objects, String key, String uploadId,
            String body) throws IOException {
        Map<Integer, StoredObject> parts = uploads.get(uploadId);
        if (parts == null) {
            error(exchange, 404, "NoSuchUpload", "The upload does not exist");
            return;
        }
        Matcher m = PART.matcher(body);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Integer> numbers = new ArrayList<>();
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
            StoredObject part = parts.get(numbers.get(numbers.size() - 1));
            if (part == null || !part.etag().equals(m.group(2))) {
                error(exchange, 400, "InvalidPart", "Part " + m.group(1));
                return;
            }
            bytes.writeBytes(part.bytes());
        }
        for (int i = 0; i < numbers.size(); i++) {
            if (numbers.get(i) != i + 1) {
                error(exchange, 400, "InvalidPartOrder", "Part " + numbers.get(i));
                return;
            }
            if (i < numbers.size() - 1 && parts.get(numbers.get(i)).bytes().length < minPartSize) {
                error(exchange, 400, "EntityTooSmall", "Part " + numbers.get(i));
                return;
            }
        }
        if (numbers.isEmpty()) {
            error(exchange, 400, "MalformedXML", "No parts");
            return;
        }
        uploads.remove(uploadId);
        byte[] all = bytes.toByteArray();
        objects.put(key, new StoredObject(all, "\"" + etag(all).replace("\"", "") + "-" + numbers.size() + "\""));
        send(exchange, 200, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUploadResult><Key>"
                + Xml.escape(key) + "</Key></CompleteMultipartUploadResult>").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks the signature of a request with a signer of its own.
     *
     * @return what is wrong, or {@code null}
     */
    private String checkSignature(HttpExchange exchange, String method, String rawPath, String rawQuery,
            byte[] body) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null) {
            return "Anonymous access is not allowed";
        }
        Matcher m = AUTHORIZATION.matcher(authorization);
        if (!m.matches()) {
            return "Signature header malformed: " + authorization;
        }
        if (!m.group(1).equals(accessKey)) {
            return "Unknown access key";
        }
        String amzDate = exchange.getRequestHeaders().getFirst("X-Amz-Date");
        String payload = exchange.getRequestHeaders().getFirst("X-Amz-Content-Sha256");
        if (amzDate == null || payload == null || !amzDate.startsWith(m.group(2))) {
            return "Signature lacks x-amz-date or x-amz-content-sha256";
        }
        if (!payload.equals("UNSIGNED-PAYLOAD") && !payload.equals(sha256(body))) {
            return "Signature over a payload hash that does not match the body";
        }
        List<String> signed = List.of(m.group(4).split(";"));
        if (!signed.containsAll(List.of("host", "x-amz-content-sha256", "x-amz-date"))) {
            return "Signature without host, x-amz-content-sha256 and x-amz-date";
        }
        StringBuilder canonical = new StringBuilder(method).append('\n').append(rawPath).append('\n');
        List<String> pairs = new ArrayList<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                pairs.add(uriEncode(decode(name)) + "=" + uriEncode(decode(value)));
            }
        }
        Collections.sort(pairs);
        canonical.append(String.join("&", pairs)).append('\n');
        for (String header : signed) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value == null) {
                return "Signature over a missing header " + header;
            }
            canonical.append(header).append(':').append(value.strip()).append('\n');
        }
        canonical.append('\n').append(m.group(4)).append('\n').append(payload);
        String scope = m.group(2) + "/" + m.group(3) + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), m.group(2));
        for (String part : List.of(m.group(3), "s3", "aws4_request")) {
            key = hmac(key, part);
        }
        String expected = HEX.formatHex(hmac(key, toSign));
        return expected.equals(m.group(5)) ? null : "SignatureDoesNotMatch: the canonical request is\n" + canonical;
    }

    private static String uriEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (Character.isLetterOrDigit(c) && c < 128 || "-_.~".indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                query.put(decode(eq < 0 ? pair : pair.substring(0, eq)), eq < 0 ? "" : decode(pair.substring(eq + 1)));
            }
        }
        return query;
    }

    /** Decodes {@code %XX}, leaving a {@code +} as it is, as S3 does in paths. */
    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>" + code + "</Code><Message>"
                + Xml.escape(message) + "</Message></Error>").getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null || exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
