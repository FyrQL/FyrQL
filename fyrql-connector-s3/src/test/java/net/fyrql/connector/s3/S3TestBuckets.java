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

import java.io.IOException;
import java.util.List;

/** Buckets of the tests of other modules, made and removed through a source of S3, such as against MinIO. */
public final class S3TestBuckets {

    private S3TestBuckets() {
    }

    /** Creates a bucket. */
    public static void create(S3Connector s3, String bucket) throws IOException {
        client(s3).createBucket(bucket);
    }

    /** Returns the keys of the objects of a bucket, sorted. */
    public static List<String> keys(S3Connector s3, String bucket) throws IOException {
        return client(s3).list(bucket, "", null).stream().map(S3Client.Listed::key).toList();
    }

    /** Deletes the objects of a bucket and the bucket. */
    public static void delete(S3Connector s3, String bucket) throws IOException {
        S3Client client = client(s3);
        for (String key : keys(s3, bucket)) {
            client.delete(new S3Location(bucket, key));
        }
        client.deleteBucket(bucket);
    }

    private static S3Client client(S3Connector s3) {
        return ((S3FileStore) s3.fileStore().orElseThrow()).client();
    }
}
