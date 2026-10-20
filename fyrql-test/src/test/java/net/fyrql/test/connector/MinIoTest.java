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

package net.fyrql.test.connector;

import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.s3.S3Credentials;
import net.fyrql.connector.s3.S3TestBuckets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The round trip of {@link S3RoundTrip} against a running MinIO, or any
 * other store with the API of S3, given by the system property
 * {@code fyrql.test.s3.endpoint}, such as {@code http://127.0.0.1:9000},
 * with {@code fyrql.test.s3.accessKey} and {@code fyrql.test.s3.secretKey},
 * {@code minioadmin} by default. The tests run in a new bucket, which they
 * delete with its objects in the end; without the property they are
 * skipped.
 */
class MinIoTest extends S3RoundTrip {

    private final String bucket = "fyrql-test-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());

    @Override
    S3Connector connect() {
        String endpoint = System.getProperty("fyrql.test.s3.endpoint");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "-Dfyrql.test.s3.endpoint names a running MinIO");
        S3Connector s3 = S3Connector.builder("s3").endpoint(endpoint).credentials(S3Credentials.of(
                System.getProperty("fyrql.test.s3.accessKey", "minioadmin"),
                System.getProperty("fyrql.test.s3.secretKey", "minioadmin"))).useEnvironment(false).build();
        try {
            S3TestBuckets.create(s3, bucket);
        } catch (IOException e) {
            s3.close();
            throw new UncheckedIOException(e);
        }
        return s3;
    }

    @Override
    String bucket() {
        return bucket;
    }

    @Override
    void cleanUp() throws IOException {
        if (s3 != null) {
            S3TestBuckets.delete(s3, bucket);
        }
    }

    @Test
    void theBucketHoldsWhatTheTestsWrote() throws IOException {
        assertEquals(6, engine.update("COPY memory.t TO '" + location("listing/t.parquet") + "'"));
        assertTrue(S3TestBuckets.keys(s3, bucket).contains("listing/t.parquet"));
    }
}
