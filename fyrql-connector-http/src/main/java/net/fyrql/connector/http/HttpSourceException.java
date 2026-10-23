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

package net.fyrql.connector.http;

import java.net.URI;

/**
 * A request of an HTTP source that failed: the server answered with a
 * status other than 2xx, or there was no answer, such as when the
 * connection was refused or the request timed out. The message names the
 * URL without user information and query, and the status; never the values
 * of the headers or the query, which may hold secrets.
 */
public final class HttpSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final URI uri;
    private final int status;

    /**
     * @param message the message, which must not show the values of headers or the query of the URL
     * @param uri the URL of the request
     * @param status the status of the answer, or -1 if there was none
     * @param cause the cause, or {@code null}
     */
    public HttpSourceException(String message, URI uri, int status, Throwable cause) {
        super(message, cause);
        this.uri = uri;
        this.status = status;
    }

    /** Returns the whole URL of the request, with its query; not meant for messages and logs. */
    public URI uri() {
        return uri;
    }

    /** Returns the status of the answer, or -1 if there was none. */
    public int status() {
        return status;
    }
}
