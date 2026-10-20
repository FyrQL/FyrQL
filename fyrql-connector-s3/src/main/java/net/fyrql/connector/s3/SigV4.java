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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Signs requests to S3 with the Signature Version 4 of AWS: the canonical
 * request of the method, the path, the query and the signed headers with
 * the hash of the payload, the string to sign with the scope of the day,
 * the region and the service, and the signature with a key derived from
 * the secret key by HMAC-SHA256.
 */
final class SigV4 {

    /** The hash of an empty payload. */
    static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    static final String ALGORITHM = "AWS4-HMAC-SHA256";

    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final HexFormat HEX = HexFormat.of();

    private SigV4() {
    }

    /**
     * Returns the query of a request in the canonical form: the names and
     * values encoded, sorted by the encoded names and values, each name
     * with {@code =} even without a value.
     */
    static String canonicalQuery(Map<String, String> query) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : query.entrySet()) {
            parts.add(S3Location.encode(e.getKey(), false) + "=" + S3Location.encode(e.getValue(), false));
        }
        parts.sort(null);
        return String.join("&", parts);
    }

    /**
     * Returns the value of the header {@code Authorization} of a request.
     *
     * @param method the method, such as {@code GET}
     * @param path the path, encoded as it is sent, such as {@code /bucket/a%20b}
     * @param query the canonical query, see {@link #canonicalQuery}
     * @param headers the headers to sign by their names in lower case, the
     *        host and {@code x-amz-date} included
     * @param payloadHash the hash of the payload in hexadecimal, as sent in
     *        {@code x-amz-content-sha256}
     * @param time the time of {@code x-amz-date}
     * @param region the region, such as {@code us-east-1}
     * @param accessKey the access key
     * @param secretKey the secret key
     */
    static String authorization(String method, String path, String query, Map<String, String> headers,
            String payloadHash, Instant time, String region, String accessKey, String secretKey) {
        SortedMap<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) -> sorted.put(name.toLowerCase(java.util.Locale.ROOT),
                value.strip().replaceAll(" +", " ")));
        StringBuilder canonical = new StringBuilder();
        canonical.append(method).append('\n').append(path).append('\n').append(query).append('\n');
        sorted.forEach((name, value) -> canonical.append(name).append(':').append(value).append('\n'));
        String signed = String.join(";", sorted.keySet());
        canonical.append('\n').append(signed).append('\n').append(payloadHash);

        String amzDate = AMZ_DATE.format(time);
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HEX.formatHex(hmac(key, toSign));
        return ALGORITHM + " Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature="
                + signature;
    }

    /** Returns the SHA-256 hash of bytes in hexadecimal. */
    static String sha256(byte[] bytes) {
        return sha256(bytes, 0, bytes.length);
    }

    /** Returns the SHA-256 hash of a range of bytes in hexadecimal. */
    static String sha256(byte[] bytes, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return HEX.formatHex(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
