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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3LocationTest {

    @Test
    void aLocationHasABucketAndAKey() {
        S3Location l = S3Location.parse("s3://lake/events/2026/a.parquet");
        assertEquals("lake", l.bucket());
        assertEquals("events/2026/a.parquet", l.key());
        assertEquals("a.parquet", l.name());
        assertEquals("events/2026/", l.parent());
        assertEquals("s3://lake/events/2026/a.parquet", l.toString());
        assertEquals("s3://lake/x", l.withKey("x").toString());
        S3Location bucket = S3Location.parse("S3://lake");
        assertEquals("", bucket.key());
        assertEquals("", bucket.parent());
        assertEquals("s3://lake/", bucket.toString());
        assertEquals("dir/", S3Location.parse("s3://lake/dir/").key());
        assertEquals("", S3Location.parse("s3://lake/dir/").name());
    }

    @Test
    void anInvalidLocationIsAnError() {
        assertEquals("Not an S3 location: /tmp/x", assertThrows(IllegalArgumentException.class,
                () -> S3Location.parse("/tmp/x")).getMessage());
        assertEquals("The S3 location s3:///x has no valid bucket", assertThrows(IllegalArgumentException.class,
                () -> S3Location.parse("s3:///x")).getMessage());
        assertEquals("The S3 location s3://a b/x has no valid bucket", assertThrows(IllegalArgumentException.class,
                () -> S3Location.parse("s3://a b/x")).getMessage());
        assertEquals("The key of the S3 location s3://b/x/../y has a part ..",
                assertThrows(IllegalArgumentException.class, () -> S3Location.parse("s3://b/x/../y")).getMessage());
    }
}
