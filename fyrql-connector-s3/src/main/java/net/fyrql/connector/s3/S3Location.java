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

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * A location in S3, {@code s3://bucket/key}: the bucket and the key, which
 * may be empty or end with {@code /} for the objects under a prefix.
 *
 * @param bucket the bucket
 * @param key the key, without the {@code /} after the bucket
 */
record S3Location(String bucket, String key) {

    static final String PREFIX = "s3://";

    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * Parses a location.
     *
     * @throws IllegalArgumentException if it is no location of the scheme
     *         {@code s3}, has no valid bucket, or a part {@code .} or
     *         {@code ..} in its key
     */
    static S3Location parse(String location) {
        if (!location.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            throw new IllegalArgumentException("Not an S3 location: " + location);
        }
        String rest = location.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String key = slash < 0 ? "" : rest.substring(slash + 1);
        if (!BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("The S3 location " + location + " has no valid bucket");
        }
        for (String part : key.split("/", -1)) {
            if (part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("The key of the S3 location " + location + " has a part " + part);
            }
        }
        return new S3Location(bucket, key);
    }

    /** Returns the location as text, {@code s3://bucket/key}. */
    @Override
    public String toString() {
        return PREFIX + bucket + "/" + key;
    }

    /** Returns the last part of the key. */
    String name() {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    /** Returns the key up to and with its last {@code /}, or empty. */
    String parent() {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    /** Returns the location of another key in the bucket. */
    S3Location withKey(String other) {
        return new S3Location(bucket, other);
    }

    /**
     * Encodes a text for a URI as S3 expects it: every byte of UTF-8 as
     * {@code %XX}, except letters, digits, {@code -}, {@code _}, {@code .}
     * and {@code ~}, and except {@code /} if it is kept.
     */
    static String encode(String text, boolean keepSlash) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                    || c == '.' || c == '~' || c == '/' && keepSlash) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
