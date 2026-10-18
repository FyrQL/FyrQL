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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Creates OData sources of the type {@code odata} from options:
 * <ul>
 * <li>{@code url} for the one service of a source, the schema
 * {@code main}; or {@code services.<schema>} or
 * {@code services.<schema>.url} for each service, with
 * {@code services.<schema>.version} ({@code v2} or {@code v4}) to read it
 * as that version, whatever its {@code $metadata} says, and
 * {@code services.<schema>.trustFilters} to trust it with filters or not,
 * whatever the source says;</li>
 * <li>at most one authentication: {@code user} and {@code password} for
 * HTTP Basic, {@code token} for a bearer token, or
 * {@code oauth.tokenUrl}, {@code oauth.clientId},
 * {@code oauth.clientSecret} and optionally {@code oauth.scope} for OAuth
 * 2.0 with the client credentials grant, with
 * {@code oauth.clientAuthentication} {@code basic} (the default) or
 * {@code post} for how the client proves itself;</li>
 * <li>{@code trustFilters} ({@code false} by default) to take over the
 * filters with an exact counterpart, see
 * {@link ODataConnector.Builder#trustFilters(boolean)}, and
 * {@code rowCounts} ({@code false} by default) to count the rows of the
 * tables for the engine;</li>
 * <li>{@code headers.<name>} for a header of every request to the
 * services, {@code timeout} (such as {@code 10s}), {@code pageSize} for
 * the pages of services of version 4, and {@code batchSize}.</li>
 * </ul>
 */
public final class ODataConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "odata";
    }

    @Override
    public ODataConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the OData source " + name, options, directory);
        ODataConnector.Builder b = ODataConnector.builder(name);
        Optional<String> url = o.string("url");
        Map<String, String> services = o.prefixed("services", "<schema>.<attribute>");
        Optional<String> user = o.string("user");
        Optional<String> password = o.string("password");
        Optional<String> token = o.string("token");
        Optional<String> tokenUrl = o.string("oauth.tokenUrl");
        Optional<String> clientId = o.string("oauth.clientId");
        Optional<String> clientSecret = o.string("oauth.clientSecret");
        Optional<String> scope = o.string("oauth.scope");
        Optional<Boolean> post = o.get("oauth.clientAuthentication", "basic or post",
                v -> switch (v.strip().toLowerCase(Locale.ROOT)) {
                    case "basic" -> false;
                    case "post" -> true;
                    default -> throw new IllegalArgumentException(v);
                });
        o.duration("timeout").ifPresent(b::timeout);
        o.integer("pageSize").ifPresent(b::pageSize);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bool("trustFilters").ifPresent(b::trustFilters);
        o.bool("rowCounts").ifPresent(b::rowCounts);
        Map<String, String> headers = o.prefixed("headers", "<name>");
        o.rejectUnknown();

        if (url.isPresent() == !services.isEmpty()) {
            throw new IllegalArgumentException(url.isPresent()
                    ? "The OData source " + name + " has both url and services; url is the one service of a source"
                    : "The OData source " + name + " needs the option url or services.<schema>.url");
        }
        url.ifPresent(b::url);
        services(name, services).forEach((schema, attributes) -> {
            b.service(schema, URI.create(attributes.get("url")), version(name, schema, attributes.get("version")));
            String trust = attributes.get("trustFilters");
            if (trust != null) {
                b.trustFilters(schema, bool(name, "services." + schema + ".trustFilters", trust));
            }
        });

        List<String> kinds = new ArrayList<>();
        if (user.isPresent() || password.isPresent()) {
            kinds.add("user and password");
        }
        if (token.isPresent()) {
            kinds.add("token");
        }
        boolean oauth = tokenUrl.isPresent() || clientId.isPresent() || clientSecret.isPresent() || scope.isPresent()
                || post.isPresent();
        if (oauth) {
            kinds.add("oauth");
        }
        if (kinds.size() > 1) {
            throw new IllegalArgumentException("The OData source " + name + " has more than one authentication: "
                    + String.join(", ", kinds) + "; it may have one of them");
        }
        if (user.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("The OData source " + name + " needs both user and password");
        }
        if (user.isPresent()) {
            b.basic(user.get(), password.get());
        }
        token.ifPresent(b::bearer);
        if (oauth) {
            if (tokenUrl.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
                throw new IllegalArgumentException("The OData source " + name + " needs oauth.tokenUrl,"
                        + " oauth.clientId and oauth.clientSecret for OAuth 2.0");
            }
            b.oauth2(URI.create(tokenUrl.get()), clientId.get(), clientSecret.get(), scope.orElse(null),
                    post.orElse(false));
        }
        b.headers(headers);
        return b.build();
    }

    /**
     * Groups the options of the services by schema: the URL alone, or
     * {@code url}, {@code version} and {@code trustFilters}.
     */
    private static Map<String, Map<String, String>> services(String name, Map<String, String> options) {
        Map<String, Map<String, String>> services = new LinkedHashMap<>();
        options.forEach((key, value) -> {
            int dot = key.indexOf('.');
            String schema = dot < 0 ? key : key.substring(0, dot);
            String attribute = dot < 0 ? "url" : key.substring(dot + 1);
            if (!attribute.equals("url") && !attribute.equals("version") && !attribute.equals("trustFilters")) {
                throw new IllegalArgumentException("The service " + schema + " of the OData source " + name
                        + " has an unknown attribute " + attribute + "; known are url, version, trustFilters");
            }
            if (services.computeIfAbsent(schema, s -> new LinkedHashMap<>()).putIfAbsent(attribute, value) != null) {
                throw new IllegalArgumentException("The service " + schema + " of the OData source " + name
                        + " has both services." + schema + " and services." + schema + ".url");
            }
        });
        services.forEach((schema, attributes) -> {
            if (!attributes.containsKey("url")) {
                throw new IllegalArgumentException("The service " + schema + " of the OData source " + name
                        + " needs services." + schema + ".url");
            }
        });
        return services;
    }

    private static boolean bool(String name, String option, String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on" -> true;
            case "false", "off" -> false;
            default -> throw new IllegalArgumentException("The option " + option + " of the OData source " + name
                    + " must be true, false, on or off: " + value);
        };
    }

    private static ODataVersion version(String name, String schema, String version) {
        if (version == null) {
            return null;
        }
        try {
            return ODataVersion.parse(version);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The option services." + schema + ".version of the OData source "
                    + name + " must be v2 or v4: " + version, e);
        }
    }
}
