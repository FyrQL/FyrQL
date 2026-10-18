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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.http.HttpTransport;
import net.fyrql.connector.json.JsonLayout;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entity sets of OData services, version 2 or 4, as tables: each
 * service is a schema, and each entity set of its entity container a
 * table, with the columns of {@link EdmTypes}. The {@code $metadata} of a
 * service is read when the service is first used and kept until
 * {@link #refresh()}; its version is that of the document, unless one is
 * given.
 * <p>
 * Every scan is a query of its entity set, in JSON, that selects the
 * columns the scan reads with {@code $select}, of version 4 also fields
 * inside complex properties, unless it reads all of them, and that asks
 * for the number of all records
 * unless the entity set says that it cannot count them. The answer is
 * read page after page, see {@link PagedReader}; a service of version 4
 * may be asked for pages of a size. A query that selects complex or
 * collection properties, on which some services fail, is asked once more
 * without {@code $select} if it fails, and so are the next queries of its
 * entity set that select such properties, until {@link #refresh()}, if
 * that is answered.
 * <p>
 * The filters of a scan go to the service as a {@code $filter} that holds
 * wherever they hold, see {@link ODataFilters}, and the engine still
 * evaluates them, unless the source trusts the service with filters
 * ({@code trustFilters}): then the filters that have an exact counterpart
 * are taken over. The connector takes over an order by numbers, dates and
 * timestamps whose {@code NULL}s are where OData puts them, as
 * {@code $orderby}; a limit, as {@code $top}, when it takes over every
 * filter and the order; and {@code count(*)} without groups, as a request
 * of {@code /$count}. What the {@code $metadata} restricts, by the
 * vocabulary {@code Capabilities} of version 4 or the attributes of SAP of
 * version 2, see {@link ODataRestrictions}, is not asked for. With
 * {@code rowCounts} the catalog tells the number of rows of a table, by a
 * request of {@code /$count} that it keeps until {@link #refresh()}.
 * <p>
 * Every request carries the headers of the source and its authentication:
 * HTTP Basic, a bearer token, or a token of OAuth 2.0 with the client
 * credentials grant; a request of version 2 also says
 * {@code MaxDataServiceVersion: 2.0}, so that a service of version 3
 * answers as version 2, and one of version 4 {@code OData-MaxVersion: 4.0}.
 * Errors are {@link ODataSourceException}s with the message of the
 * service; credentials and the values of headers never appear in messages.
 * <p>
 * A {@link ODataProfile} adapts the connector to a kind of server: it can
 * add query options and headers to every request, and give columns other
 * types, by which the connector then neither filters nor sorts.
 */
public final class ODataConnector implements Connector {

    /** The namespace of the attributes of SAP, such as {@code sap:countable}. */
    static final String SAP = ODataRestrictions.SAP;

    private final String name;
    /** The services by schema, in order. */
    private final Map<String, Service> services;
    private final ODataClient client;
    private final Integer pageSize;
    private final int batchSize;
    private final ODataCatalog catalog;
    /** The tables whose services fail on a {@code $select} of complex or collection properties. */
    private final Set<TableRef> unselectable = ConcurrentHashMap.newKeySet();

    /**
     * A service: the URL of its root, its version, or {@code null} for that
     * of its {@code $metadata}, and whether the source trusts it with filters.
     */
    private record Service(URI root, ODataVersion version, boolean trustFilters) {
    }

    /**
     * What a plan keeps for its scan: all filters, those taken over and the
     * hints, the limit and the order it was asked for.
     */
    private record State(List<Expression> filters, OptionalLong limit, List<PlanNode.SortKey> order) {
    }

    /** The state of the split of a scan that counts the records, {@code count(*)}. */
    private record CountState() {
    }

    private ODataConnector(Builder b, ODataClient client) {
        this.name = b.name;
        Map<String, Service> services = new LinkedHashMap<>();
        b.services.forEach((schema, s) -> services.put(schema, new Service(s.root(), s.version(),
                b.trust.getOrDefault(schema, b.trustFilters))));
        this.services = Collections.unmodifiableMap(services);
        this.client = client;
        this.pageSize = b.pageSize;
        this.batchSize = b.batchSize;
        this.catalog = new ODataCatalog(name, List.copyOf(services.keySet()), this::metadata,
                b.rowCounts ? this::rowCount : null, b.profile);
    }

    /** Starts a connector with the name under which it will be registered. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }

    /** Reads the {@code $metadata} of the service of a schema. */
    private ServiceMetadata metadata(String schema) {
        Service service = services.get(schema);
        RequestTarget target = new RequestTarget(name, schema, null);
        URI uri = ODataQuery.of(service.root(), "$metadata", Map.of());
        try (HttpTransport.Response response = client.get(target, RequestSpec.Kind.METADATA, uri,
                Map.of("Accept", "application/xml"))) {
            return EdmxParser.parse(response.body(), "the service " + schema + " of the OData source " + name);
        }
    }

    /** Returns the version of the service of a schema: the given one, or that of its {@code $metadata}. */
    ODataVersion version(String schema) {
        Service service = services.get(schema);
        return service.version() != null ? service.version() : catalog.metadata(schema).version();
    }

    /**
     * Forgets the {@code $metadata} of every service, so that the next use
     * of a service reads it again, and which services fail on a
     * {@code $select}.
     */
    public void refresh() {
        catalog.refresh();
        unselectable.clear();
    }

    /**
     * Forgets the {@code $metadata} of the service of a schema, so that its
     * next use reads it again, and whether it fails on a {@code $select}.
     */
    public void refresh(String schema) {
        catalog.refresh(schema);
        unselectable.removeIf(t -> t.schema().equals(schema));
    }

    /**
     * Takes over the filters that have an exact counterpart if the source
     * trusts the service with filters, an order and a limit under the rules
     * in {@link ODataFilters#orderBy} and {@link Capabilities}, and
     * {@code count(*)} without groups, once or more often, unless the
     * {@code $metadata} restricts them; reads fields inside complex
     * properties of version 4. Keeps all filters, to send those it does not
     * take over as hints.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        ODataCatalog.Table t = catalog.table(table);
        ODataVersion version = version(table.schema());
        boolean fields = version == ODataVersion.V4;
        // A request for fields is answered with whole columns, without filters, order and limit.
        ScanRequest asked = !fields && request.readsFields() ? request.withWholeColumns() : request;
        ODataRestrictions restrictions = ODataRestrictions.of(t.set());
        ODataFilters filters = filters(t, asked.columns(), version);
        boolean trust = services.get(table.schema()).trustFilters();
        Capabilities capabilities = new Capabilities(f -> trust && filters.exact(f) != null, restrictions.top(),
                order -> filters.orderBy(order) != null, a -> countsRows(a) && restrictions.countable(), fields);
        return capabilities.plan(table, t.schema(), request, new State(asked.filters(), asked.limit(),
                asked.order()));
    }

    private static boolean countsRows(PlanNode.Aggregation aggregation) {
        if (!aggregation.groupColumns().isEmpty() || aggregation.calls().isEmpty()) {
            return false;
        }
        for (PlanNode.AggregateCall call : aggregation.calls()) {
            if (call.function() != PlanNode.AggregateFunction.COUNT_STAR) {
                return false;
            }
        }
        return true;
    }

    /** Returns the translation of the filters and the order of a scan over columns of a table. */
    private ODataFilters filters(ODataCatalog.Table table, List<ColumnPath> columns, ODataVersion version) {
        return new ODataFilters(version, targets(table, columns, ODataRestrictions.of(table.set()),
                catalog.metadata(table.ref().schema())));
    }

    /**
     * Returns the columns of the output of a scan as the service knows
     * them: the paths of their properties, the primitive types of these,
     * and whether the service filters and sorts by them.
     */
    static List<ODataFilters.Target> targets(ODataCatalog.Table table, List<ColumnPath> columns,
            ODataRestrictions restrictions, ServiceMetadata metadata) {
        List<ODataFilters.Target> targets = new ArrayList<>();
        for (ColumnPath c : columns) {
            Property top = table.properties().get(c.column());
            Property leaf = top;
            StringBuilder path = new StringBuilder(top.name());
            for (String field : c.fields()) {
                ComplexType complex = leaf == null || leaf.isCollection() ? null
                        : metadata.complexTypes().get(leaf.type());
                leaf = null;
                if (complex != null) {
                    for (Property p : metadata.properties(complex)) {
                        if (p.name().equals(field)) {
                            leaf = p;
                            break;
                        }
                    }
                }
                path.append('/').append(field);
            }
            String edm = null;
            String precision = null;
            // The service knows nothing of a type that the profile gave the column.
            if (leaf != null && !leaf.isCollection() && !table.retyped().contains(top.name())) {
                edm = leaf.type();
                precision = leaf.precision();
                for (int depth = 0; depth < 8 && metadata.typeDefinitions().containsKey(edm); depth++) {
                    TypeDefinition definition = metadata.typeDefinitions().get(edm);
                    edm = definition.underlyingType();
                    precision = precision != null ? precision : definition.precision();
                }
                if (!edm.startsWith("Edm.")) {
                    edm = null;
                }
            }
            String p = path.toString();
            Schema.Field field = c.field(table.schema());
            boolean nullable = c.isWhole() ? field.nullable() : true;
            targets.add(new ODataFilters.Target(p, edm, precision, field.type(), nullable,
                    restrictions.filterable(p, top), restrictions.ascending(p, top),
                    restrictions.descending(p, top)));
        }
        return targets;
    }

    /** Returns one split: the query of the entity set, or the request of its number of records. */
    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, plan.aggregationAccepted() ? new CountState() : null));
    }

    /**
     * Opens a reader of the records of a query of the entity set of a
     * scan, or of their number; canceling the reader closes the body of the
     * current page, so that a reader that waits on it fails at once.
     */
    @Override
    public BatchReader open(Split split) {
        ScanPlan plan = split.plan();
        TableRef ref = plan.table();
        ODataCatalog.Table table = catalog.table(ref);
        ODataVersion version = version(ref.schema());
        Service service = services.get(ref.schema());
        State state = (State) plan.state();
        ODataFilters filters = filters(table, plan.columns(), version);
        String filter = filters.filter(state.filters(), plan.acceptedFilters());
        RequestTarget target = new RequestTarget(name, ref.schema(), table.set().name());
        if (split.state() instanceof CountState) {
            return new CountReader(plan.output(), () -> count(target, service.root(), table, version, filter));
        }
        String orderBy = plan.orderAccepted() ? filters.orderBy(state.order()) : null;
        long limit = plan.limitAccepted() ? state.limit().getAsLong() : -1;
        Integer top = limit >= 0 && limit <= Integer.MAX_VALUE ? (int) limit : null;
        boolean countable = ODataRestrictions.of(table.set()).countable();
        boolean structured = structured(table, plan.columns());
        List<String> select = structured && unselectable.contains(ref) ? List.of() : select(table, plan.columns());
        String path = ODataQuery.segment(table.set().name());
        URI uri = ODataQuery.of(service.root(), path, ODataQuery.options(version, select, filter, orderBy, top,
                countable));
        URI fallback = select.isEmpty() || !structured ? null : ODataQuery.of(service.root(),
                path, ODataQuery.options(version, List.of(), filter, orderBy, top, countable));
        Map<String, String> headers = headers(version);
        if (version == ODataVersion.V4 && pageSize != null) {
            headers.put("Prefer", "odata.maxpagesize=" + pageSize);
        }
        return new PagedReader(client, target, service.root(), version, uri, fallback, () -> unselectable.add(ref),
                headers, JsonLayout.of(table.schema(), ODataValues.INSTANCE), plan.columns(), batchSize, limit);
    }

    /** Returns the headers of every request of a version besides those of the source. */
    private static Map<String, String> headers(ODataVersion version) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (version == ODataVersion.V2) {
            headers.put("MaxDataServiceVersion", "2.0");
        } else {
            headers.put("OData-MaxVersion", "4.0");
        }
        return headers;
    }

    /**
     * Asks the service for the number of the records of an entity set that
     * a filter selects, with a request of {@code /$count}.
     *
     * @param filter the {@code $filter}, or {@code null} for all records
     */
    private long count(RequestTarget target, URI root, ODataCatalog.Table table, ODataVersion version,
            String filter) {
        Map<String, String> options = new LinkedHashMap<>();
        if (filter != null) {
            options.put("$filter", filter);
        }
        URI uri = ODataQuery.of(root, ODataQuery.segment(table.set().name()) + "/$count", options);
        Map<String, String> headers = headers(version);
        headers.put("Accept", "text/plain");
        String text;
        try (HttpTransport.Response response = client.get(target, RequestSpec.Kind.COUNT, uri, headers)) {
            text = response.excerpt();
        }
        try {
            long count = Long.parseLong(text.strip());
            if (count < 0) {
                throw new NumberFormatException();
            }
            return count;
        } catch (NumberFormatException e) {
            throw target.error("answered the request of the number of records of the entity set "
                    + table.set().name() + " with " + (text.isBlank() ? "nothing" : text.strip())
                    + ", which is no number", -1, null);
        }
    }

    /**
     * Returns the number of records of the entity set of a table, if the
     * entity set can count them, for the catalog of a source with
     * {@code rowCounts}.
     */
    private OptionalLong rowCount(TableRef ref) {
        ODataCatalog.Table table = catalog.table(ref);
        if (!ODataRestrictions.of(table.set()).countable()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(count(new RequestTarget(name, ref.schema(), table.set().name()),
                services.get(ref.schema()).root(), table, version(ref.schema()), null));
    }

    /**
     * Returns what a query selects for the columns a scan reads: the names
     * of their properties, or of version 4 the paths to fields inside
     * them, such as {@code Address/City}; the key if the scan reads no
     * column, so that the answer has one record for each entity; and
     * nothing if it reads every column whole, which keeps the URL short
     * and spares services that fail on some selections.
     */
    static List<String> select(ODataCatalog.Table table, List<ColumnPath> columns) {
        List<String> whole = new ArrayList<>();
        for (ColumnPath c : columns) {
            if (c.isWhole() && !whole.contains(propertyName(table, c))) {
                whole.add(propertyName(table, c));
            }
        }
        List<String> select = new ArrayList<>();
        for (ColumnPath c : columns) {
            String property = propertyName(table, c);
            String path = c.isWhole() ? property : property + "/" + String.join("/", c.fields());
            if ((c.isWhole() || !whole.contains(property)) && !select.contains(path)) {
                select.add(path);
            }
        }
        if (select.isEmpty()) {
            return table.key();
        }
        return whole.size() == table.properties().size() ? List.of() : select;
    }

    /** Tells whether a scan reads a complex or collection property, or a complex field inside one. */
    private static boolean structured(ODataCatalog.Table table, List<ColumnPath> columns) {
        for (ColumnPath c : columns) {
            DataType type = c.field(table.schema()).type();
            if (type instanceof DataType.Struct || type instanceof DataType.ListType) {
                return true;
            }
        }
        return false;
    }

    private static String propertyName(ODataCatalog.Table table, ColumnPath c) {
        return table.properties().get(c.column()).name();
    }

    /** Shuts the HTTP clients down if the connector created them; a client that was given stays open. */
    @Override
    public void close() {
        client.close();
    }

    /** Collects the services and settings of an OData connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, Service> services = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Duration timeout = HttpTransport.DEFAULT_TIMEOUT;
        private HttpClient client;
        private Integer pageSize;
        private int batchSize = 2048;
        private String authentication;
        private String user;
        private String password;
        private String token;
        private URI tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
        private boolean secretInBody;
        private boolean trustFilters;
        private final Map<String, Boolean> trust = new LinkedHashMap<>();
        private boolean rowCounts;
        private InstantSource clock = InstantSource.system();
        private ODataProfile profile = ODataProfile.standard();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /**
         * Adds a service as a schema, of the version its {@code $metadata}
         * says.
         *
         * @param root the URL of the root of the service, such as
         *        {@code https://host/sap/opu/odata/sap/API_SALES_ORDER_SRV/};
         *        query options in it, such as {@code sap-client=100}, go with
         *        every request
         * @throws IllegalArgumentException if the URL is not http or https,
         *         or the schema is taken
         */
        public Builder service(String schema, URI root) {
            return service(schema, root, null);
        }

        /**
         * Adds a service as a schema, of a given version, whatever its
         * {@code $metadata} says.
         *
         * @param version the version, or {@code null} for that of the {@code $metadata}
         * @throws IllegalArgumentException if the URL is not http or https,
         *         or the schema is taken
         */
        public Builder service(String schema, URI root, ODataVersion version) {
            Objects.requireNonNull(schema, "schema");
            String scheme = root.getScheme() == null ? "" : root.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || root.getHost() == null) {
                throw new IllegalArgumentException("The URL of the service " + schema + " of the OData source " + name
                        + " must be http or https with a host: " + ODataQuery.shown(root));
            }
            if (root.getRawFragment() != null) {
                throw new IllegalArgumentException("The URL of the service " + schema + " of the OData source " + name
                        + " must not have a fragment");
            }
            if (services.putIfAbsent(schema, new Service(root, version, false)) != null) {
                throw new IllegalArgumentException("The OData source " + name + " has the service " + schema
                        + " twice");
            }
            return this;
        }

        /**
         * Adds a service as a schema.
         *
         * @throws IllegalArgumentException if the URL is not one
         */
        public Builder service(String schema, String root) {
            return service(schema, URI.create(root));
        }

        /** Adds the one service of a source as the schema {@code main}. */
        public Builder url(URI root) {
            return service(TableRef.MAIN, root);
        }

        /** Adds the one service of a source as the schema {@code main}. */
        public Builder url(String root) {
            return url(URI.create(root));
        }

        /** Authenticates with HTTP Basic. */
        public Builder basic(String user, String password) {
            authentication("basic");
            this.user = Objects.requireNonNull(user, "user");
            this.password = Objects.requireNonNull(password, "password");
            return this;
        }

        /** Authenticates with a fixed bearer token. */
        public Builder bearer(String token) {
            authentication("bearer");
            this.token = Objects.requireNonNull(token, "token");
            return this;
        }

        /**
         * Authenticates with tokens of OAuth 2.0 from the client credentials
         * grant; the client proves itself to the token endpoint with HTTP
         * Basic.
         *
         * @param scope the scope to ask for, or {@code null}
         */
        public Builder oauth2(URI tokenUrl, String clientId, String clientSecret, String scope) {
            return oauth2(tokenUrl, clientId, clientSecret, scope, false);
        }

        /**
         * Authenticates with tokens of OAuth 2.0 from the client credentials
         * grant.
         *
         * @param scope the scope to ask for, or {@code null}
         * @param secretInBody whether the client sends its identifier and
         *        secret in the body of the request, rather than with HTTP Basic
         */
        public Builder oauth2(URI tokenUrl, String clientId, String clientSecret, String scope,
                boolean secretInBody) {
            authentication("oauth2");
            String scheme = tokenUrl.getScheme() == null ? "" : tokenUrl.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || tokenUrl.getHost() == null) {
                throw new IllegalArgumentException("The token URL of the OData source " + name
                        + " must be http or https with a host: " + ODataQuery.shown(tokenUrl));
            }
            this.tokenUrl = tokenUrl;
            this.clientId = Objects.requireNonNull(clientId, "clientId");
            this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
            this.scope = scope;
            this.secretInBody = secretInBody;
            return this;
        }

        private void authentication(String kind) {
            if (authentication != null) {
                throw new IllegalArgumentException("The OData source " + name + " has two authentications, "
                        + authentication + " and " + kind);
            }
            authentication = kind;
        }

        /**
         * Sets the profile of the kind of server of the services, which can
         * change the requests and the types of columns; by default the
         * standard one, which changes nothing.
         */
        public Builder profile(ODataProfile profile) {
            this.profile = Objects.requireNonNull(profile, "profile");
            return this;
        }

        /** Sets the clock against which tokens expire. */
        Builder clock(InstantSource clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Adds a header that every request to the services carries; a
         * header of the same name replaces the default {@code Accept} or
         * {@code Accept-Encoding}. It does not go to the token endpoint.
         *
         * @throws IllegalArgumentException if the HTTP client does not let a
         *         request set the header, such as {@code Host}
         */
        public Builder header(String header, String value) {
            HttpRequest.newBuilder().header(header, value);
            headers.put(header, value);
            return this;
        }

        /** Adds headers that every request to the services carries, see {@link #header}. */
        public Builder headers(Map<String, String> headers) {
            headers.forEach(this::header);
            return this;
        }

        /**
         * Sets the time to connect and to wait for the status of an answer;
         * reading the body has no time limit. 30 seconds by default.
         */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("The timeout must be positive: " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the HTTP client, for a proxy, TLS or authentication of its
         * own; the connector does not shut it down. By default the
         * connector creates one that follows redirects except from https to
         * http.
         */
        public Builder client(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        /**
         * Asks the services of version 4 for pages of at most this many
         * records, with {@code Prefer: odata.maxpagesize}; a service may
         * send smaller pages. By default the service chooses.
         */
        public Builder pageSize(int pageSize) {
            if (pageSize < 1) {
                throw new IllegalArgumentException("The page size must be positive: " + pageSize);
            }
            this.pageSize = pageSize;
            return this;
        }

        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("The batch size must be positive: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Trusts the services, unless one is set otherwise, to filter
         * exactly as FyrQL does, so that the filters with an exact
         * counterpart are taken over rather than sent as hints; off by
         * default. This is a promise about the services, which the
         * connector does not check; a query without pushdown shows whether
         * it holds.
         */
        public Builder trustFilters(boolean trust) {
            this.trustFilters = trust;
            return this;
        }

        /**
         * Trusts the service of a schema to filter exactly as FyrQL does, or
         * not, see {@link #trustFilters(boolean)}.
         */
        public Builder trustFilters(String schema, boolean trust) {
            this.trust.put(Objects.requireNonNull(schema, "schema"), trust);
            return this;
        }

        /**
         * Lets the catalog tell the number of rows of a table, which the
         * engine plans joins by, by a request of {@code /$count} for each
         * entity set, kept until {@link ODataConnector#refresh()}; off by
         * default, since tools that list tables, such as the JDBC driver,
         * ask for the number of every table.
         */
        public Builder rowCounts(boolean rowCounts) {
            this.rowCounts = rowCounts;
            return this;
        }

        /**
         * Builds the connector; it sends no request until a service is used.
         *
         * @throws IllegalArgumentException if there is no service, or a
         *         service is trusted with filters that the source does not have
         */
        public ODataConnector build() {
            if (services.isEmpty()) {
                throw new IllegalArgumentException("The OData source " + name + " has no service");
            }
            for (String schema : trust.keySet()) {
                if (!services.containsKey(schema)) {
                    throw new IllegalArgumentException("The OData source " + name + " has no service " + schema
                            + " to trust with filters");
                }
            }
            HttpTransport transport = transport(headers);
            HttpTransport tokenTransport = null;
            Authentication auth;
            if (authentication == null) {
                auth = Authentication.None.INSTANCE;
            } else {
                switch (authentication) {
                    case "basic" -> auth = new Authentication.Basic(user, password);
                    case "bearer" -> auth = new Authentication.Bearer(token);
                    default -> {
                        tokenTransport = transport(Map.of());
                        auth = new Authentication.ClientCredentials(name, tokenUrl, clientId, clientSecret, scope,
                                secretInBody, tokenTransport, clock);
                    }
                }
            }
            return new ODataConnector(this, new ODataClient(transport, auth, tokenTransport, profile));
        }

        private HttpTransport transport(Map<String, String> headers) {
            HttpTransport.Builder b = HttpTransport.builder().accept("application/json").headers(headers)
                    .timeout(timeout);
            if (client != null) {
                b.client(client);
            }
            return b.build();
        }
    }
}
