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

package net.fyrql.connector.odata;

import net.fyrql.connector.http.HttpSourceException;
import net.fyrql.connector.http.HttpTransport;

import java.net.URI;

/**
 * What a request to a service is for: the {@code $metadata} of the service
 * or the query of one of its entity sets. Makes the messages of the
 * requests that fail.
 *
 * @param source the name of the source
 * @param service the schema of the service
 * @param entitySet the entity set, or {@code null} for the {@code $metadata}
 */
record RequestTarget(String source, String service, String entitySet) {

    /** Returns who answers, for the start of a message. */
    String subject() {
        return "The service " + service + " of the OData source " + source;
    }

    /** Returns what was asked, for a message. */
    String request() {
        return entitySet == null ? "the request of its $metadata" : "the query of the entity set " + entitySet;
    }

    /** Returns an error of this target with a message that follows the subject. */
    ODataSourceException error(String message, int status, Throwable cause) {
        return new ODataSourceException(subject() + " " + message, source, service, entitySet, status, null, cause);
    }

    /** Returns the error of an answer with a status other than 2xx, with the error the service sends, and closes it. */
    ODataSourceException status(HttpTransport.Response response) {
        ODataError error = isJson(response) ? ODataError.read(response.body()) : null;
        String text = error != null ? error.toString() : response.excerpt();
        response.close();
        int status = response.status();
        String hint = status == 401 ? "; the credentials of the source are missing or wrong"
                : status == 403 ? "; the credentials of the source do not allow it" : "";
        return new ODataSourceException(subject() + " answered " + request() + " at "
                + ODataQuery.shown(response.uri()) + " with the status " + status + (text.isEmpty() ? "" : ": " + text)
                + hint, source, service, entitySet, status, error == null ? null : error.toString(), null);
    }

    /** Returns the error of a request that got no answer, or one that cannot be read. */
    ODataSourceException failed(URI uri, HttpSourceException e) {
        String reason = e.getMessage().replace(uri.toString(), ODataQuery.shown(uri));
        return new ODataSourceException(subject() + " did not answer " + request() + ": " + reason, source, service,
                entitySet, e.status(), null, e);
    }

    /** Tells whether the body of an answer is JSON, or of no stated type. */
    static boolean isJson(HttpTransport.Response response) {
        String type = response.contentType();
        return type.isEmpty() || type.equals("application/json") || type.endsWith("+json");
    }
}
