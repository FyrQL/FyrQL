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

package net.fyrql.connector.odata;

/**
 * A request to an OData service that failed or whose answer cannot be
 * used: the service answered with an error, did not answer, answered in
 * another format than JSON, or cut an answer short. The message names the
 * source, the service and the entity set, the URL without its query, the
 * status and the message of the service, never the values of headers or
 * credentials.
 */
public final class ODataSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String source;
    private final String service;
    private final String entitySet;
    private final int status;
    private final String serviceMessage;

    /**
     * @param message the message, which must not show credentials or the values of headers
     * @param source the name of the source
     * @param service the schema of the service, or {@code null} if the request was for no service, such as
     *        that of a token
     * @param entitySet the entity set, or {@code null} if the request was for none
     * @param status the status of the answer, or -1 if there was none or it was 2xx
     * @param serviceMessage the message of the service, or {@code null} if it sent none
     * @param cause the cause, or {@code null}
     */
    public ODataSourceException(String message, String source, String service, String entitySet, int status,
            String serviceMessage, Throwable cause) {
        super(message, cause);
        this.source = source;
        this.service = service;
        this.entitySet = entitySet;
        this.status = status;
        this.serviceMessage = serviceMessage;
    }

    /** Returns the name of the source. */
    public String source() {
        return source;
    }

    /** Returns the schema of the service, or {@code null} if the request was for no service. */
    public String service() {
        return service;
    }

    /** Returns the entity set, or {@code null} if the request was for none. */
    public String entitySet() {
        return entitySet;
    }

    /** Returns the status of the answer, or -1 if there was none or it was 2xx. */
    public int status() {
        return status;
    }

    /** Returns the message of the service, with its code, or {@code null} if it sent none. */
    public String serviceMessage() {
        return serviceMessage;
    }
}
