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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The signatures of the examples of the documentation of AWS for the
 * Signature Version 4 of S3, with the access key
 * {@code AKIAIOSFODNN7EXAMPLE} at 2013-05-24 in {@code us-east-1}.
 */
class SigV4Test {

    private static final String ACCESS = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final Instant TIME = Instant.parse("2013-05-24T00:00:00Z");
    private static final String HOST = "examplebucket.s3.amazonaws.com";
    private static final String PREFIX = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
            + "aws4_request, SignedHeaders=";

    private static Map<String, String> headers(String... namesAndValues) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST);
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    @Test
    void getObject() {
        assertEquals(PREFIX + "host;range;x-amz-content-sha256;x-amz-date, Signature="
                + "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                SigV4.authorization("GET", "/test.txt", "", headers("Range", "bytes=0-9",
                        "x-amz-content-sha256", SigV4.EMPTY_HASH, "x-amz-date", "20130524T000000Z"),
                        SigV4.EMPTY_HASH, TIME, "us-east-1", ACCESS, SECRET));
    }

    @Test
    void putObject() {
        String payload = SigV4.sha256("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payload);
        assertEquals("/test%24file.text", "/" + S3Location.encode("test$file.text", true));
        assertEquals(PREFIX + "date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, Signature="
                + "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                SigV4.authorization("PUT", "/test%24file.text", "", headers("date", "Fri, 24 May 2013 00:00:00 GMT",
                        "x-amz-date", "20130524T000000Z", "x-amz-storage-class", "REDUCED_REDUNDANCY",
                        "x-amz-content-sha256", payload), payload, TIME, "us-east-1", ACCESS, SECRET));
    }

    @Test
    void getBucketLifecycle() {
        String query = SigV4.canonicalQuery(Map.of("lifecycle", ""));
        assertEquals("lifecycle=", query);
        assertEquals(PREFIX + "host;x-amz-content-sha256;x-amz-date, Signature="
                + "fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
                SigV4.authorization("GET", "/", query, headers("x-amz-content-sha256", SigV4.EMPTY_HASH,
                        "x-amz-date", "20130524T000000Z"), SigV4.EMPTY_HASH, TIME, "us-east-1", ACCESS, SECRET));
    }

    @Test
    void listObjects() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("prefix", "J");
        params.put("max-keys", "2");
        String query = SigV4.canonicalQuery(params);
        assertEquals("max-keys=2&prefix=J", query);
        assertEquals(PREFIX + "host;x-amz-content-sha256;x-amz-date, Signature="
                + "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
                SigV4.authorization("GET", "/", query, headers("x-amz-content-sha256", SigV4.EMPTY_HASH,
                        "x-amz-date", "20130524T000000Z"), SigV4.EMPTY_HASH, TIME, "us-east-1", ACCESS, SECRET));
    }

    @Test
    void keysAndQueriesAreEncodedOnceAsS3ExpectsThem() {
        assertEquals("dir/a%20b%2Bc~d_e.f-g/%C3%A4%E2%82%AC",
                S3Location.encode("dir/a b+c~d_e.f-g/\u00E4\u20AC", true));
        assertEquals("dir%2Fx%3Dy%26z", S3Location.encode("dir/x=y&z", false));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("prefix", "dir/a b");
        params.put("list-type", "2");
        params.put("delimiter", "/");
        params.put("continuation-token", "t+k=");
        assertEquals("continuation-token=t%2Bk%3D&delimiter=%2F&list-type=2&prefix=dir%2Fa%20b",
                SigV4.canonicalQuery(params));
    }
}
