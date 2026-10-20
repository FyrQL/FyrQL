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

import java.util.Objects;

/**
 * The credentials with which requests to S3 are signed.
 *
 * @param accessKey the access key
 * @param secretKey the secret key
 * @param sessionToken the token of temporary credentials, or {@code null}
 */
public record S3Credentials(String accessKey, String secretKey, String sessionToken) {

    public S3Credentials {
        Objects.requireNonNull(accessKey, "accessKey");
        Objects.requireNonNull(secretKey, "secretKey");
    }

    /** Returns credentials without a session token. */
    public static S3Credentials of(String accessKey, String secretKey) {
        return new S3Credentials(accessKey, secretKey, null);
    }

    /** Returns the access key only, so that the secret key appears in no message or log. */
    @Override
    public String toString() {
        return "S3Credentials[accessKey=" + accessKey + "]";
    }
}
