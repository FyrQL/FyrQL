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

/** An error that S3 answered a request with: the status and the code of the error, such as {@code AccessDenied}. */
public final class S3Exception extends IOException {

    private final int status;
    private final String code;

    S3Exception(String message, int status, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Returns the status of the answer, such as 403. */
    public int status() {
        return status;
    }

    /** Returns the code of the error, such as {@code AccessDenied}, or empty if the answer had none. */
    public String code() {
        return code;
    }
}
