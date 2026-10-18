/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector.http;

import java.net.URI;

/**
 * A request of an HTTP source that failed: the server answered with a
 * status other than 2xx, or there was no answer, such as when the
 * connection was refused or the request timed out. The message names the
 * URL and the status, never the values of the headers, which may hold
 * secrets.
 */
public final class HttpSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final URI uri;
    private final int status;

    /**
     * @param message the message, which must not show the values of headers
     * @param uri the URL of the request
     * @param status the status of the answer, or -1 if there was none
     * @param cause the cause, or {@code null}
     */
    public HttpSourceException(String message, URI uri, int status, Throwable cause) {
        super(message, cause);
        this.uri = uri;
        this.status = status;
    }

    /** Returns the URL of the request. */
    public URI uri() {
        return uri;
    }

    /** Returns the status of the answer, or -1 if there was none. */
    public int status() {
        return status;
    }
}
