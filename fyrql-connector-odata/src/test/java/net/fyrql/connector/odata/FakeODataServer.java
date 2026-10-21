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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * OData services of version 2 or 4 on a server of the JDK on a free port
 * of the loopback address, for tests. Each service has entity sets made of
 * a schema and rows in the representation of FyrQL; it answers its
 * {@code $metadata}, made of the schemas, and queries of its entity sets in
 * JSON with {@code $select}, {@code $filter} (see {@link FakeFilter}),
 * {@code $orderby}, {@code $skip}, {@code $top}, the number of all records
 * and {@code /$count}, and pages linked by {@code $skiptoken}, with the
 * values in the forms of its version:
 * {@code /Date(ms)/}, numbers of 64 bits and decimals as strings in
 * version 2, ISO dates with an offset in version 4, Base64 for binary
 * values. A service can have the quirks of real ones, see
 * {@link Service}. The server can require HTTP Basic, a bearer token or a
 * token that its token endpoint issues for client credentials, and keeps
 * a log of the requests.
 */
public final class FakeODataServer implements AutoCloseable {

    private static final JsonFactory JSON = new JsonFactory();

    /** The offset with which the services of version 4 write {@code Edm.DateTimeOffset}. */
    static final int OFFSET_MINUTES = 120;

    private final HttpServer server;
    private final Map<String, Service> services = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final AtomicInteger issued = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch stalled = new CountDownLatch(1);
    private volatile String authorization;
    private volatile String clientId;
    private volatile String clientSecret;
    private volatile long expiresIn = -1;

    /**
     * A request: its method and path, its query options or form fields,
     * decoded, and its headers by lower-case name, of
     * {@code Authorization} only the scheme.
     */
    public record Request(String method, String path, Map<String, String> parameters, Map<String, String> headers) {

        /** Returns a header by name in any case, or {@code null}. */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private FakeODataServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                handle(exchange);
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "text/plain", e.getMessage().getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    /** Starts a server without services. */
    public static FakeODataServer start() {
        try {
            return new FakeODataServer();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Adds a service of a version under the path {@code /<name>/}. */
    public Service service(String name, ODataVersion version) {
        Service s = new Service(name, version);
        services.put(name, s);
        return s;
    }

    /** Returns the URL of the root of a service. */
    public URI url(String service) {
        return URI.create(origin() + "/" + service + "/");
    }

    /** Returns the URL of the token endpoint. */
    public URI tokenUrl() {
        return URI.create(origin() + "/oauth/token");
    }

    private String origin() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Returns the requests so far, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Returns the requests so far to a path. */
    public List<Request> requests(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    /** Requires HTTP Basic with a user and a password for the services. */
    FakeODataServer basic(String user, String password) {
        authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Requires a bearer token for the services. */
    FakeODataServer bearer(String token) {
        authorization = "Bearer " + token;
        return this;
    }

    /**
     * Requires a token of the token endpoint for the services, which issues
     * one for the client credentials of a client.
     *
     * @param expiresIn the lifetime of a token in seconds that the endpoint
     *        tells, or -1 to tell none; tokens do not expire unless
     *        {@link #expireTokens()} is called
     */
    FakeODataServer oauth(String clientId, String clientSecret, long expiresIn) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.expiresIn = expiresIn;
        return this;
    }

    /** Makes every token issued so far invalid. */
    void expireTokens() {
        tokens.clear();
    }

    /** Returns the number of tokens issued so far. */
    int issued() {
        return issued.get();
    }

    /** Waits until a service with {@link Service#stallAfter} has stalled an answer. */
    void awaitStall() throws InterruptedException {
        if (!stalled.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No answer stalled");
        }
    }

    /** Forgets the requests so far. */
    public void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> {
            String value = String.join(",", v);
            if (k.equalsIgnoreCase("Authorization")) {
                int space = value.indexOf(' ');
                value = space < 0 ? value : value.substring(0, space);
            }
            headers.put(k.toLowerCase(Locale.ROOT), value);
        });
        boolean form = exchange.getRequestMethod().equals("POST");
        String rawQuery = form ? new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                : exchange.getRequestURI().getRawQuery();
        Map<String, String> parameters = parameters(rawQuery, form);
        requests.add(new Request(exchange.getRequestMethod(), path, parameters, headers));
        if (path.equals("/oauth/token")) {
            token(exchange, parameters);
            return;
        }
        String[] segments = path.split("/", -1);
        boolean count = segments.length == 4 && segments[3].equals("$count");
        Service service = segments.length == 3 || count ? services.get(segments[1]) : null;
        if (service == null) {
            error(exchange, ODataVersion.V4, 404, "NotFound", "No resource " + path);
            return;
        }
        if (!authorized(exchange)) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fake\"");
            error(exchange, service.version, 401, "Unauthorized", "Not authorized");
            return;
        }
        for (Map.Entry<String, String> e : service.required.entrySet()) {
            if (!e.getValue().equals(parameters.get(e.getKey()))) {
                error(exchange, service.version, 400, "BadRequest", "The option " + e.getKey() + " must be "
                        + e.getValue());
                return;
            }
        }
        if (segments[2].equals("$metadata")) {
            send(exchange, 200, "application/xml", service.metadata().getBytes(StandardCharsets.UTF_8));
            return;
        }
        EntitySetData set = service.sets.get(segments[2]);
        if (set == null) {
            error(exchange, service.version, 404, "NotFound", "Resource not found for the segment '" + segments[2]
                    + "'");
            return;
        }
        if (count) {
            service.count(exchange, set, parameters);
        } else {
            service.query(exchange, set, parameters, rawQuery == null ? "" : rawQuery);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (clientId != null) {
            return header != null && header.startsWith("Bearer ") && tokens.contains(header.substring(7));
        }
        return authorization == null || authorization.equals(header);
    }

    private void token(HttpExchange exchange, Map<String, String> form) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String id = form.get("client_id");
        String secret = form.get("client_secret");
        if (header != null && header.startsWith("Basic ")) {
            String pair = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int colon = pair.indexOf(':');
            id = decode(pair.substring(0, colon), true);
            secret = decode(pair.substring(colon + 1), true);
        }
        if (!"client_credentials".equals(form.get("grant_type"))) {
            send(exchange, 400, "application/json", "{\"error\":\"unsupported_grant_type\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (clientId == null || !clientId.equals(id) || !clientSecret.equals(secret)) {
            send(exchange, 401, "application/json", ("{\"error\":\"invalid_client\",\"error_description\":"
                    + "\"Bad client credentials\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        String token = "token-" + issued.incrementAndGet();
        tokens.add(token);
        send(exchange, 200, "application/json", ("{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\""
                + (expiresIn >= 0 ? ",\"expires_in\":" + expiresIn : "") + ",\"scope\":\""
                + form.getOrDefault("scope", "") + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes a query or a form strictly: {@code +} is a space only in a form. */
    private static Map<String, String> parameters(String raw, boolean form) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq), form);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1), form);
            if (parameters.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("The option " + key + " appears twice");
            }
        }
        return parameters;
    }

    private static String decode(String s, boolean form) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else if (c == '+' && form) {
                out.write(' ');
            } else {
                if (c > 0x7e || c <= 0x20 || "\"<>\\^`{|}".indexOf(c) >= 0) {
                    throw new IllegalArgumentException("The character " + c + " is not encoded in " + s);
                }
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Answers an error object of a version. */
    private static void error(HttpExchange exchange, ODataVersion version, int status, String code, String message)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeObjectFieldStart("error");
            g.writeStringField("code", code);
            if (version == ODataVersion.V2) {
                g.writeObjectFieldStart("message");
                g.writeStringField("lang", "en");
                g.writeStringField("value", message);
                g.writeEndObject();
            } else {
                g.writeStringField("message", message);
            }
            g.writeEndObject();
            g.writeEndObject();
        }
        send(exchange, status, "application/json;charset=utf-8", out.toByteArray());
    }

    /** An entity set: its columns with their types in OData, its rows, and the columns of its key. */
    private record EntitySetData(String name, Schema schema, List<String> edmTypes, List<Object[]> rows,
            List<String> key, boolean countable) {
    }

    /**
     * A service of a version with entity sets. Its quirks: pages of a fixed
     * size, links to next pages that are relative, an answer cut short
     * without a link to the next page, no number of all records, Atom
     * instead of JSON, an answer that stalls after some records until the
     * server closes, comparisons of strings without case or without spaces
     * at the end, a {@code $filter} or {@code $top} that it ignores, numbers
     * of 64 bits and decimals as strings also in version 4, a query option
     * that every request must have, and a {@code $select} of complex or
     * collection properties that it fails on. Its {@code $metadata} can
     * restrict filtering, sorting and {@code $top}, which it then refuses.
     */
    public final class Service {

        private final String name;
        private final ODataVersion version;
        private final Map<String, EntitySetData> sets = new LinkedHashMap<>();
        private final Map<String, String> overrides = new HashMap<>();
        private final Map<String, String> precisions = new HashMap<>();
        private final Set<String> nonFilterable = ConcurrentHashMap.newKeySet();
        private final Set<String> nonSortable = ConcurrentHashMap.newKeySet();
        private final Map<String, String> required = new ConcurrentHashMap<>();
        private volatile boolean ieee754Compatible;
        private volatile boolean refusesStructuredSelect;
        private volatile boolean topUnsupported;
        private volatile boolean caseInsensitive;
        private volatile boolean ignoreTrailingSpaces;
        private volatile boolean ignoreFilter;
        private volatile boolean ignoreTop;
        private volatile int pageSize;
        private volatile boolean relativeLinks;
        private volatile boolean cutShort;
        private volatile boolean noCount;
        private volatile boolean atom;
        private volatile int stallAfter = -1;
        private volatile int failing;
        private volatile boolean foreignLinks;
        private volatile boolean sameLink;

        private Service(String name, ODataVersion version) {
            this.name = name;
            this.version = version;
        }

        /**
         * Gives a column of the next entity sets a type in OData other than
         * that of its type in FyrQL, such as {@code Edm.Byte} for a
         * {@code SMALLINT}, {@code Edm.DateTimeOffset} for a
         * {@code TIMESTAMP} of version 2, or {@code Edm.Time} for a
         * {@code VARCHAR}, whose values are written as they are.
         */
        public Service edmType(String column, String edmType) {
            overrides.put(column, edmType);
            return this;
        }

        /**
         * Adds an entity set.
         *
         * @param rows the rows in the representation of FyrQL, such as the
         *        microseconds of a {@code TIMESTAMP} and lists for structs
         */
        public Service entitySet(String name, Schema schema, List<Object[]> rows, String... key) {
            return entitySet(name, schema, rows, true, key);
        }

        /** Adds an entity set that says that it cannot count its records. */
        public Service uncountableEntitySet(String name, Schema schema, List<Object[]> rows, String... key) {
            return entitySet(name, schema, rows, false, key);
        }

        private Service entitySet(String name, Schema schema, List<Object[]> rows, boolean countable,
                String... key) {
            List<String> edm = new ArrayList<>();
            for (Schema.Field f : schema.fields()) {
                edm.add(overrides.getOrDefault(f.name(), edmType(f.type())));
            }
            sets.put(name, new EntitySetData(name, schema, edm, List.copyOf(rows), List.of(key), countable));
            return this;
        }

        /** Gives a column of the next entity sets of a type of date and time the facet {@code Precision}. */
        public Service precision(String column, int precision) {
            precisions.put(column, Integer.toString(precision));
            return this;
        }

        /**
         * Does not filter by these properties: refuses a {@code $filter}
         * that names one, and says so in its {@code $metadata}, by
         * {@code sap:filterable="false"} in version 2 and
         * {@code FilterRestrictions} in version 4.
         */
        public Service nonFilterable(String... properties) {
            nonFilterable.addAll(List.of(properties));
            return this;
        }

        /** Does not sort by these properties, see {@link #nonFilterable}. */
        public Service nonSortable(String... properties) {
            nonSortable.addAll(List.of(properties));
            return this;
        }

        /**
         * Refuses {@code $top}, and says so in its {@code $metadata}, by
         * {@code sap:pageable="false"} in version 2 and
         * {@code TopSupported} in version 4.
         */
        public Service topUnsupported(boolean unsupported) {
            this.topUnsupported = unsupported;
            return this;
        }

        /** Compares strings without case, also in functions. */
        public Service caseInsensitive(boolean caseInsensitive) {
            this.caseInsensitive = caseInsensitive;
            return this;
        }

        /** Ignores spaces at the end of strings when it compares them for equality. */
        public Service ignoreTrailingSpaces(boolean ignore) {
            this.ignoreTrailingSpaces = ignore;
            return this;
        }

        /** Ignores {@code $filter}, also in {@code /$count}. */
        public Service ignoreFilter(boolean ignore) {
            this.ignoreFilter = ignore;
            return this;
        }

        /** Ignores {@code $top}. */
        public Service ignoreTop(boolean ignore) {
            this.ignoreTop = ignore;
            return this;
        }

        /** Answers pages of at most this many records, 0 for all. */
        public Service pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Service relativeLinks(boolean relativeLinks) {
            this.relativeLinks = relativeLinks;
            return this;
        }

        /** Answers the first page without a link to the next one. */
        public Service cutShort(boolean cutShort) {
            this.cutShort = cutShort;
            return this;
        }

        /** Never tells the number of all records. */
        public Service noCount(boolean noCount) {
            this.noCount = noCount;
            return this;
        }

        /** Answers queries in Atom. */
        public Service atom(boolean atom) {
            this.atom = atom;
            return this;
        }

        /** Answers queries with an error object and this status, 0 for none. */
        public Service failing(int status) {
            this.failing = status;
            return this;
        }

        /** Links to next pages on another host. */
        public Service foreignLinks(boolean foreignLinks) {
            this.foreignLinks = foreignLinks;
            return this;
        }

        /** Links from a page to the same page again. */
        public Service sameLink(boolean sameLink) {
            this.sameLink = sameLink;
            return this;
        }

        /** Refuses every request without a query option of this value, such as the client of an SAP system. */
        public Service requireOption(String name, String value) {
            required.put(name, value);
            return this;
        }

        /**
         * Writes numbers of {@code Edm.Int64} and {@code Edm.Decimal} as
         * strings also in version 4, as a service does that is asked for
         * {@code IEEE754Compatible=true} or always does so.
         */
        public Service ieee754Compatible(boolean strings) {
            this.ieee754Compatible = strings;
            return this;
        }

        /** Fails with the status 500 on a {@code $select} of a complex or collection property. */
        public Service refusesStructuredSelect(boolean refuses) {
            this.refusesStructuredSelect = refuses;
            return this;
        }

        /** Stalls an answer after this many records until the server closes, -1 for never. */
        public Service stallAfter(int records) {
            this.stallAfter = records;
            return this;
        }

        private String edmType(DataType type) {
            if (type instanceof DataType.Decimal) {
                return "Edm.Decimal";
            }
            if (type instanceof DataType.ListType l) {
                return "Collection(" + edmType(l.element()) + ")";
            }
            if (type instanceof DataType.Struct) {
                return "complex";
            }
            return switch ((DataType.Scalar) type) {
                case BOOLEAN -> "Edm.Boolean";
                case TINYINT -> "Edm.SByte";
                case SMALLINT -> "Edm.Int16";
                case INTEGER -> "Edm.Int32";
                case BIGINT -> "Edm.Int64";
                case FLOAT -> "Edm.Single";
                case DOUBLE -> "Edm.Double";
                case VARCHAR -> "Edm.String";
                case UUID -> "Edm.Guid";
                case BLOB -> "Edm.Binary";
                case DATE -> "Edm.Date";
                case TIMESTAMP -> version == ODataVersion.V2 ? "Edm.DateTime" : "Edm.DateTimeOffset";
                case TIMESTAMPTZ -> "Edm.DateTimeOffset";
                case TIME -> version == ODataVersion.V2 ? "Edm.Time" : "Edm.TimeOfDay";
                case INTERVAL -> "Edm.Duration";
                default -> throw new IllegalArgumentException("No type of OData for " + type);
            };
        }

        /** Returns an interval of days and microseconds of one sign as a duration of ISO 8601. */
    static String duration(Interval i) {
        if (i.months() != 0 || i.days() < 0 != i.micros() < 0 && i.days() != 0 && i.micros() != 0) {
            throw new IllegalArgumentException("No duration of OData: " + i);
        }
        boolean negative = i.days() < 0 || i.micros() < 0;
        String time = Duration.ofNanos(Math.abs(i.micros()) * 1000).toString().substring(1);
        return (negative ? "-P" : "P") + (i.days() != 0 ? Math.abs(i.days()) + "D" : "")
                + (time.equals("T0S") && i.days() != 0 ? "" : time);
    }

    /** Returns the {@code $metadata} of the service. */
        String metadata() {
            StringBuilder types = new StringBuilder();
            StringBuilder container = new StringBuilder();
            for (EntitySetData set : sets.values()) {
                types.append("<EntityType Name=\"").append(set.name()).append("Type\"><Key>");
                for (String k : set.key()) {
                    types.append("<PropertyRef Name=\"").append(k).append("\"/>");
                }
                types.append("</Key>");
                for (int c = 0; c < set.schema().size(); c++) {
                    Schema.Field f = set.schema().fields().get(c);
                    int at = types.length();
                    property(types, f.name(), f.type(), set.edmTypes().get(c), set.name() + "_" + f.name(),
                            f.nullable());
                    String extra = (precisions.containsKey(f.name()) ? " Precision=\"" + precisions.get(f.name())
                            + "\"" : "") + (version == ODataVersion.V2 && nonFilterable.contains(f.name())
                            ? " sap:filterable=\"false\"" : "") + (version == ODataVersion.V2
                            && nonSortable.contains(f.name()) ? " sap:sortable=\"false\"" : "");
                    types.insert(types.indexOf("/>", at), extra);
                }
                types.append("</EntityType>");
                for (Schema.Field f : set.schema().fields()) {
                    complexTypes(types, set.name() + "_" + f.name(), f.type());
                }
                container.append("<EntitySet Name=\"").append(set.name()).append("\" EntityType=\"Fake.")
                        .append(set.name()).append("Type\"");
                if (version == ODataVersion.V2) {
                    container.append(set.countable() ? "" : " sap:countable=\"false\"")
                            .append(topUnsupported ? " sap:pageable=\"false\"" : "").append("/>");
                } else {
                    container.append(">");
                    if (!set.countable()) {
                        container.append("<Annotation Term=\"Org.OData.Capabilities.V1.CountRestrictions\"><Record>"
                                + "<PropertyValue Property=\"Countable\" Bool=\"false\"/></Record></Annotation>");
                    }
                    restriction(container, "FilterRestrictions", "NonFilterableProperties", nonFilterable);
                    restriction(container, "SortRestrictions", "NonSortableProperties", nonSortable);
                    if (topUnsupported) {
                        container.append("<Annotation Term=\"Org.OData.Capabilities.V1.TopSupported\""
                                + " Bool=\"false\"/>");
                    }
                    container.append("</EntitySet>");
                }
            }
            if (version == ODataVersion.V2) {
                return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                        + "<edmx:Edmx Version=\"1.0\" xmlns:edmx=\"http://schemas.microsoft.com/ado/2007/06/edmx\""
                        + " xmlns:m=\"http://schemas.microsoft.com/ado/2007/08/dataservices/metadata\""
                        + " xmlns:sap=\"" + ODataConnector.SAP + "\">"
                        + "<edmx:DataServices m:DataServiceVersion=\"2.0\">"
                        + "<Schema Namespace=\"Fake\" xmlns=\"http://schemas.microsoft.com/ado/2008/09/edm\">"
                        + types + "<EntityContainer Name=\"Container\" m:IsDefaultEntityContainer=\"true\">"
                        + container + "</EntityContainer></Schema></edmx:DataServices></edmx:Edmx>";
            }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<edmx:Edmx Version=\"4.0\" xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\">"
                    + "<edmx:DataServices><Schema Namespace=\"Fake\" xmlns=\"http://docs.oasis-open.org/odata/ns/edm\">"
                    + types + "<EntityContainer Name=\"Container\">" + container
                    + "</EntityContainer></Schema></edmx:DataServices></edmx:Edmx>";
        }

        private void restriction(StringBuilder b, String term, String property, Set<String> paths) {
            if (paths.isEmpty()) {
                return;
            }
            b.append("<Annotation Term=\"Org.OData.Capabilities.V1.").append(term).append("\"><Record>")
                    .append("<PropertyValue Property=\"").append(property).append("\"><Collection>");
            for (String path : paths) {
                b.append("<PropertyPath>").append(path).append("</PropertyPath>");
            }
            b.append("</Collection></PropertyValue></Record></Annotation>");
        }

        private void property(StringBuilder b, String name, DataType type, String edm, String complexName,
                boolean nullable) {
            String t = edm.replace("complex", "Fake." + complexName);
            b.append("<Property Name=\"").append(name).append("\" Type=\"").append(t).append('"');
            DataType element = type instanceof DataType.ListType l ? l.element() : type;
            if (element instanceof DataType.Decimal d) {
                b.append(" Precision=\"").append(d.precision()).append("\" Scale=\"").append(d.scale()).append('"');
            }
            if (!nullable) {
                b.append(" Nullable=\"false\"");
            }
            b.append("/>");
        }

        private void complexTypes(StringBuilder b, String name, DataType type) {
            DataType element = type instanceof DataType.ListType l ? l.element() : type;
            if (!(element instanceof DataType.Struct s)) {
                return;
            }
            b.append("<ComplexType Name=\"").append(name).append("\">");
            for (DataType.Struct.Field f : s.fields()) {
                property(b, f.name(), f.type(), edmType(f.type()), name + "_" + f.name(), true);
            }
            b.append("</ComplexType>");
            for (DataType.Struct.Field f : s.fields()) {
                complexTypes(b, name + "_" + f.name(), f.type());
            }
        }

        private void query(HttpExchange exchange, EntitySetData set, Map<String, String> options, String rawQuery)
                throws IOException {
            if (failing != 0) {
                error(exchange, version, failing, "Failed", "The query of " + set.name() + " failed");
                return;
            }
            if (atom) {
                send(exchange, 200, "application/atom+xml;type=feed;charset=utf-8",
                        "<feed xmlns=\"http://www.w3.org/2005/Atom\"/>".getBytes(StandardCharsets.UTF_8));
                return;
            }
            List<List<String>> select = new ArrayList<>();
            if (options.containsKey("$select")) {
                for (String path : options.get("$select").split(",")) {
                    List<String> steps = List.of(path.split("/"));
                    if (!valid(set.schema(), steps)) {
                        error(exchange, version, 400, "BadRequest", "Could not find a property named '" + path
                                + "' on type 'Fake." + set.name() + "Type'.");
                        return;
                    }
                    if (refusesStructuredSelect && structured(set.schema(), steps)) {
                        error(exchange, version, 500, "", "Value cannot be null.\r\nParameter name: source");
                        return;
                    }
                    select.add(steps);
                }
            }
            List<Integer> rows;
            try {
                rows = rows(set, options);
            } catch (IllegalArgumentException e) {
                error(exchange, version, 400, "BadRequest", e.getMessage());
                return;
            }
            int matching = rows.size();
            if (options.containsKey("$skip")) {
                rows = rows.subList(Math.min(rows.size(), nonNegative(options, "$skip")), rows.size());
            }
            if (options.containsKey("$top") && !ignoreTop) {
                if (topUnsupported) {
                    error(exchange, version, 400, "BadRequest", "The entity set " + set.name() + " takes no $top");
                    return;
                }
                rows = rows.subList(0, Math.min(rows.size(), nonNegative(options, "$top")));
            }
            boolean count = !noCount && (version == ODataVersion.V2 ? "allpages".equals(options.get("$inlinecount"))
                    : "true".equals(options.get("$count")));
            int skip = options.containsKey("$skiptoken") ? Integer.parseInt(options.get("$skiptoken")) : 0;
            int size = pageSize;
            String prefer = exchange.getRequestHeaders().getFirst("Prefer");
            if (version == ODataVersion.V4 && prefer != null && prefer.startsWith("odata.maxpagesize=")) {
                int max = Integer.parseInt(prefer.substring("odata.maxpagesize=".length()));
                size = size == 0 ? max : Math.min(size, max);
                exchange.getResponseHeaders().add("Preference-Applied", "odata.maxpagesize=" + size);
            }
            int end = size == 0 ? rows.size() : Math.min(rows.size(), skip + size);
            String next = null;
            if (end < rows.size() && !cutShort) {
                StringBuilder q = new StringBuilder();
                for (String pair : rawQuery.split("&")) {
                    if (!pair.isEmpty() && !pair.startsWith("$skiptoken=") && !pair.startsWith("%24skiptoken=")) {
                        q.append(pair).append('&');
                    }
                }
                q.append("%24skiptoken=").append(sameLink ? skip : end);
                next = (relativeLinks ? "" : foreignLinks ? "http://other.example/" + name + "/" : url(name).toString())
                        + set.name() + "?" + q;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int stallAt = -1;
            try (JsonGenerator g = JSON.createGenerator(out)) {
                g.writeStartObject();
                if (version == ODataVersion.V2) {
                    g.writeObjectFieldStart("d");
                    if (count) {
                        g.writeStringField("__count", Integer.toString(matching));
                    }
                    g.writeArrayFieldStart("results");
                } else {
                    g.writeStringField("@odata.context", url(name) + "$metadata#" + set.name());
                    if (count) {
                        g.writeNumberField("@odata.count", matching);
                    }
                    g.writeArrayFieldStart("value");
                }
                for (int r = skip; r < end; r++) {
                    if (r - skip == stallAfter) {
                        g.flush();
                        stallAt = out.size();
                    }
                    entity(g, set, set.rows().get(rows.get(r)), select, rows.get(r));
                }
                g.writeEndArray();
                if (version == ODataVersion.V2) {
                    if (next != null) {
                        g.writeStringField("__next", next);
                    }
                    g.writeEndObject();
                } else if (next != null) {
                    g.writeStringField("@odata.nextLink", next);
                }
                g.writeEndObject();
            }
            byte[] body = out.toByteArray();
            if (stallAt < 0) {
                send(exchange, 200, "application/json;charset=utf-8", body);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(body, 0, stallAt);
                o.flush();
                stalled.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                o.write(body, stallAt, body.length - stallAt);
            }
        }

        /** Answers {@code /$count}: the number of the records that the {@code $filter} selects, as text. */
        private void count(HttpExchange exchange, EntitySetData set, Map<String, String> options) throws IOException {
            if (failing != 0) {
                error(exchange, version, failing, "Failed", "The count of " + set.name() + " failed");
                return;
            }
            if (!set.countable()) {
                error(exchange, version, 400, "BadRequest", "The entity set " + set.name() + " cannot be counted");
                return;
            }
            List<Integer> rows;
            try {
                rows = rows(set, options);
            } catch (IllegalArgumentException e) {
                error(exchange, version, 400, "BadRequest", e.getMessage());
                return;
            }
            send(exchange, 200, "text/plain;charset=utf-8", Integer.toString(rows.size())
                    .getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Returns the indexes of the rows that the {@code $filter} selects, in
         * the order of the {@code $orderby}, whose {@code null}s come first
         * ascending and last descending.
         */
        private List<Integer> rows(EntitySetData set, Map<String, String> options) {
            List<Integer> rows = new ArrayList<>();
            Predicate<Object[]> filter = row -> true;
            String text = options.get("$filter");
            if (text != null && !ignoreFilter) {
                filter = FakeFilter.parse(text, version, path -> column(set, path, nonFilterable, "filter"),
                        new FakeFilter.Quirks(caseInsensitive, ignoreTrailingSpaces));
            }
            for (int r = 0; r < set.rows().size(); r++) {
                if (filter.test(set.rows().get(r))) {
                    rows.add(r);
                }
            }
            String orderBy = options.get("$orderby");
            if (orderBy != null) {
                Comparator<Integer> order = null;
                for (String item : orderBy.split(",")) {
                    String[] words = item.strip().split(" +");
                    if (words.length > 2 || words.length == 2 && !words[1].equals("asc") && !words[1].equals("desc")) {
                        throw new IllegalArgumentException("Not an item of $orderby: " + item);
                    }
                    FakeFilter.Column c = column(set, words[0], nonSortable, "sort");
                    boolean descending = words.length == 2 && words[1].equals("desc");
                    Comparator<Integer> key = (a, b) -> compareValues(c.value().apply(set.rows().get(a)),
                            c.value().apply(set.rows().get(b)));
                    key = descending ? key.reversed() : key;
                    order = order == null ? key : order.thenComparing(key);
                }
                rows.sort(order);
            }
            return rows;
        }

        /** Compares two values of a column, {@code null} first. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int compareValues(Object a, Object b) {
            if (a == null || b == null) {
                return a == null ? b == null ? 0 : -1 : 1;
            }
            return ((Comparable) a).compareTo(b);
        }

        /**
         * Returns the column of a path into the properties of an entity set.
         *
         * @throws IllegalArgumentException if there is none, or the service does not use its property so
         */
        private FakeFilter.Column column(EntitySetData set, String path, Set<String> restricted, String use) {
            List<String> steps = List.of(path.split("/"));
            if (!valid(set.schema(), steps)) {
                throw new IllegalArgumentException("Could not find a property named '" + path + "' on type 'Fake."
                        + set.name() + "Type'.");
            }
            if (restricted.contains(steps.get(0))) {
                throw new IllegalArgumentException("The property " + steps.get(0) + " does not allow to " + use);
            }
            int c = set.schema().indexOf(steps.get(0));
            DataType type = set.schema().type(c);
            String edm = set.edmTypes().get(c);
            List<Integer> fields = new ArrayList<>();
            for (String step : steps.subList(1, steps.size())) {
                DataType.Struct s = (DataType.Struct) type;
                fields.add(s.indexOf(step));
                type = s.fields().get(s.indexOf(step)).type();
                edm = edmType(type);
            }
            if (type instanceof DataType.Struct || type instanceof DataType.ListType) {
                throw new IllegalArgumentException("The property " + path + " is not primitive");
            }
            return new FakeFilter.Column(type, edm, row -> {
                Object v = row[c];
                for (int f : fields) {
                    v = v == null ? null : ((List<?>) v).get(f);
                }
                return v;
            });
        }

        private static int nonNegative(Map<String, String> options, String option) {
            try {
                int n = Integer.parseInt(options.get(option));
                if (n >= 0) {
                    return n;
                }
            } catch (NumberFormatException e) {
                // Reported below.
            }
            throw new IllegalArgumentException("The option " + option + " must be a number of records: "
                    + options.get(option));
        }

        /** Tells whether a valid path leads to a complex or collection value. */
        private static boolean structured(Schema schema, List<String> steps) {
            DataType t = schema.type(schema.indexOf(steps.get(0)));
            for (String step : steps.subList(1, steps.size())) {
                DataType.Struct s = (DataType.Struct) t;
                t = s.fields().get(s.indexOf(step)).type();
            }
            return t instanceof DataType.Struct || t instanceof DataType.ListType;
        }

        private boolean valid(Schema schema, List<String> steps) {
            int c = schema.indexOf(steps.get(0));
            if (c < 0 || steps.size() > 1 && version == ODataVersion.V2) {
                return false;
            }
            DataType t = schema.type(c);
            for (String step : steps.subList(1, steps.size())) {
                if (!(t instanceof DataType.Struct s) || s.indexOf(step) < 0) {
                    return false;
                }
                t = s.fields().get(s.indexOf(step)).type();
            }
            return true;
        }

        private void entity(JsonGenerator g, EntitySetData set, Object[] row, List<List<String>> select, int r)
                throws IOException {
            g.writeStartObject();
            String uri = url(name) + set.name() + "(" + r + ")";
            if (version == ODataVersion.V2) {
                g.writeObjectFieldStart("__metadata");
                g.writeStringField("uri", uri);
                g.writeStringField("type", "Fake." + set.name() + "Type");
                g.writeEndObject();
            } else {
                g.writeStringField("@odata.etag", "W/\"" + r + "\"");
            }
            for (int c = 0; c < set.schema().size(); c++) {
                Schema.Field f = set.schema().fields().get(c);
                List<List<String>> paths = new ArrayList<>();
                boolean whole = select.isEmpty();
                for (List<String> s : select) {
                    if (s.get(0).equals(f.name())) {
                        whole |= s.size() == 1;
                        paths.add(s.subList(1, s.size()));
                    }
                }
                if (!whole && paths.isEmpty()) {
                    continue;
                }
                g.writeFieldName(f.name());
                value(g, f.type(), set.edmTypes().get(c), row[c], whole ? List.of() : paths, set.name() + "_"
                        + f.name());
            }
            if (version == ODataVersion.V2 && select.isEmpty()) {
                g.writeObjectFieldStart("Related");
                g.writeObjectFieldStart("__deferred");
                g.writeStringField("uri", uri + "/Related");
                g.writeEndObject();
                g.writeEndObject();
            }
            g.writeEndObject();
        }

        /**
         * Writes a value of a type in the form of its type in OData.
         *
         * @param paths the paths into a struct to write, or empty for all of it
         */
        private void value(JsonGenerator g, DataType type, String edm, Object v, List<List<String>> paths,
                String complexName) throws IOException {
            if (v == null) {
                g.writeNull();
                return;
            }
            if (type instanceof DataType.Struct s) {
                List<?> values = (List<?>) v;
                g.writeStartObject();
                if (version == ODataVersion.V2) {
                    g.writeObjectFieldStart("__metadata");
                    g.writeStringField("type", "Fake." + complexName);
                    g.writeEndObject();
                }
                for (int i = 0; i < s.fields().size(); i++) {
                    DataType.Struct.Field f = s.fields().get(i);
                    List<List<String>> inner = new ArrayList<>();
                    boolean whole = paths.isEmpty();
                    for (List<String> p : paths) {
                        if (p.get(0).equals(f.name())) {
                            whole |= p.size() == 1;
                            inner.add(p.subList(1, p.size()));
                        }
                    }
                    if (whole || !inner.isEmpty()) {
                        g.writeFieldName(f.name());
                        value(g, f.type(), edmType(f.type()), values.get(i), whole ? List.of() : inner,
                                complexName + "_" + f.name());
                    }
                }
                g.writeEndObject();
                return;
            }
            if (type instanceof DataType.ListType l) {
                g.writeStartArray();
                String element = edm.substring("Collection(".length(), edm.length() - 1);
                for (Object e : (List<?>) v) {
                    value(g, l.element(), element, e, List.of(), complexName);
                }
                g.writeEndArray();
                return;
            }
            boolean v2 = version == ODataVersion.V2;
            boolean strings = v2 || ieee754Compatible;
            switch (edm) {
                case "Edm.Boolean" -> g.writeBoolean((Boolean) v);
                case "Edm.SByte", "Edm.Byte", "Edm.Int16", "Edm.Int32" -> g.writeNumber(((Number) v).intValue());
                case "Edm.Int64" -> {
                    if (strings) {
                        g.writeString(v.toString());
                    } else {
                        g.writeNumber((Long) v);
                    }
                }
                case "Edm.Single", "Edm.Double" -> {
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        g.writeString(Double.isNaN(d) ? "NaN" : d > 0 ? "INF" : "-INF");
                    } else if (v2) {
                        g.writeString(v.toString());
                    } else if (v instanceof Float f) {
                        g.writeNumber(f);
                    } else {
                        g.writeNumber(d);
                    }
                }
                case "Edm.Decimal" -> {
                    if (strings) {
                        g.writeString(((BigDecimal) v).toPlainString());
                    } else {
                        g.writeNumber((BigDecimal) v);
                    }
                }
                case "Edm.Guid" -> g.writeString(((UUID) v).toString());
                case "Edm.Binary" -> g.writeString(v2 ? Base64.getEncoder().encodeToString((byte[]) v)
                        : Base64.getUrlEncoder().withoutPadding().encodeToString((byte[]) v));
                case "Edm.DateTime" -> g.writeString("/Date(" + Math.floorDiv((Long) v, 1000L) + ")/");
                case "Edm.DateTimeOffset" -> {
                    long micros = (Long) v;
                    if (v2) {
                        g.writeString("/Date(" + Math.floorDiv(micros, 1000L) + "+" + String.format("%04d",
                                OFFSET_MINUTES) + ")/");
                    } else {
                        LocalDateTime local = Conversions.toLocalDateTime(micros + OFFSET_MINUTES * 60_000_000L);
                        String fraction = local.getNano() == 0 ? "" : String.format(".%06d0", local.getNano() / 1000);
                        g.writeString(String.format("%04d-%02d-%02dT%02d:%02d:%02d", local.getYear(),
                                local.getMonthValue(), local.getDayOfMonth(), local.getHour(), local.getMinute(),
                                local.getSecond()) + fraction + String.format("+%02d:%02d", OFFSET_MINUTES / 60,
                                OFFSET_MINUTES % 60));
                    }
                }
                case "Edm.Date" -> g.writeString(LocalDate.ofEpochDay((Integer) v).toString());
                case "Edm.Time" -> g.writeString(v instanceof Long micros ? Duration.ofNanos(micros * 1000).toString()
                        : v.toString());
                case "Edm.TimeOfDay" -> g.writeString(v instanceof Long micros
                        ? LocalTime.ofNanoOfDay(micros * 1000).toString() : v.toString());
                case "Edm.Duration" -> g.writeString(v instanceof Interval i ? duration(i) : v.toString());
                default -> g.writeString(v.toString());
            }
        }
    }
}
