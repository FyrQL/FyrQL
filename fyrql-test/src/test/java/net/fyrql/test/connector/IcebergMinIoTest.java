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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.s3.S3Credentials;
import net.fyrql.connector.s3.S3TestBuckets;

/**
 * Iceberg tables in a bucket of a running MinIO, opt-in with
 * {@code -Dfyrql.test.s3.endpoint=http://127.0.0.1:9000} and
 * {@code fyrql.test.s3.accessKey} and {@code fyrql.test.s3.secretKey},
 * {@code minioadmin} by default; the bucket is removed afterwards.
 */
class IcebergMinIoTest extends IcebergS3 {

    private final String bucket = "fyrql-iceberg-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());

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
}
