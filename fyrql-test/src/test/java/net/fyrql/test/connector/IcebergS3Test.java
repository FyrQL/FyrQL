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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import net.fyrql.connector.s3.FakeS3Server;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.s3.S3Credentials;

/** Iceberg tables in the bucket of the S3 fake. */
class IcebergS3Test extends IcebergS3 {

    private FakeS3Server server;

    @Override
    S3Connector connect() {
        server = FakeS3Server.start("fyrql", "fyrql-secret");
        server.createBucket(bucket());
        return S3Connector.builder("s3").endpoint(server.endpoint()).credentials(S3Credentials.of("fyrql",
                "fyrql-secret")).useEnvironment(false).build();
    }

    @Override
    String bucket() {
        return "lake";
    }

    @Override
    void cleanUp() {
        server.close();
    }

    @Test
    void aPrunedDataFileIsNeverRequested() {
        assumeTrue(S3RoundTrip.pushdown(), "without pushdown the scan gets no filters to leave files out by");
        server.clearRequests();
        engine.rows("SELECT count(*) FROM lake.pos WHERE cat = 'b'");
        // The keys of the requests are URL-encoded: cat=b is cat%3Db.
        assertTrue(server.requests().stream().noneMatch(r -> r.contains("/cat%3Da/")), server.requests().toString());
        assertTrue(server.requests().stream().anyMatch(r -> r.contains("/cat%3Db/")), server.requests().toString());
    }
}
