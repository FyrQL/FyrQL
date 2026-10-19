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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sends the requests of a source to its services with its
 * authentication, after its profile changed them. A request that the
 * service refuses with the status 401 is sent once more with a new token,
 * if the authentication can get one.
 */
final class ODataClient implements AutoCloseable {

    private final HttpTransport transport;
    private final Authentication authentication;
    private final ODataProfile profile;
    /** The transport of the token endpoint, which the client closes as well, or {@code null}. */
    private final HttpTransport tokenTransport;

    ODataClient(HttpTransport transport, Authentication authentication, HttpTransport tokenTransport,
            ODataProfile profile) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.tokenTransport = tokenTransport;
    }

    /**
     * Sends a {@code GET}, as the profile changes it, and returns the answer.
     *
     * @param kind what the request is for
     * @param headers headers of this request besides those of the source
     * @throws ODataSourceException if there is no answer, its status is
     *         not 2xx, or a token cannot be had
     */
    HttpTransport.Response get(RequestTarget target, RequestSpec.Kind kind, URI uri, Map<String, String> headers) {
        RequestSpec spec = new RequestSpec(kind, target.service(), target.entitySet(), uri, headers);
        profile.decorate(spec);
        URI decorated = spec.uri();
        for (int attempt = 0; ; attempt++) {
            Map<String, String> all = new LinkedHashMap<>(spec.headers());
            all.keySet().removeIf("Authorization"::equalsIgnoreCase);
            String authorization = authentication.header();
            if (authorization != null) {
                all.put("Authorization", authorization);
            }
            HttpTransport.Response response;
            try {
                response = transport.getAllowingErrors(decorated, all);
            } catch (HttpSourceException e) {
                throw target.failed(decorated, e);
            }
            if (response.status() == 401 && attempt == 0 && authentication.renew()) {
                response.close();
                continue;
            }
            if (!response.ok()) {
                throw target.status(response);
            }
            return response;
        }
    }

    @Override
    public void close() {
        transport.close();
        if (tokenTransport != null) {
            tokenTransport.close();
        }
    }
}
