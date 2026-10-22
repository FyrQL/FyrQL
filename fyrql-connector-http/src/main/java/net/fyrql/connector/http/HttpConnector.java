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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.json.JsonFormat;
import net.fyrql.connector.json.JsonLayout;
import net.fyrql.connector.json.JsonOptions;
import net.fyrql.connector.json.JsonPointers;
import net.fyrql.connector.json.JsonRecordReader;
import net.fyrql.connector.json.JsonSchemaInference;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON that HTTP endpoints answer to a {@code GET}, as tables in the schema
 * {@code main}. The answer is read as a JSON file is, see
 * {@link net.fyrql.connector.json.JsonConnector}: an array of records or a
 * sequence of them, or, with a JSON pointer such as {@code /data}, the
 * array inside the answer that the pointer points to. Objects and arrays
 * become {@code STRUCT}, {@code MAP} and {@code LIST} values.
 * <p>
 * Unless a table has a schema, its columns are inferred from a sample of
 * its records with a {@code GET} when the table is first used, as DuckDB
 * infers them; the connector keeps them until {@link #refresh()}. Every
 * scan is a {@code GET} of its own that streams the answer and skips the
 * values of the columns the query does not need; nothing else is pushed to
 * the endpoint. A record of a later answer with a key the inferred columns
 * do not know is an error, as in a JSON file; {@code ignore_errors} skips
 * the key, and a table with a given schema skips such keys always.
 * <p>
 * Every request carries the headers of the connector, such as
 * {@code Authorization}, asks for JSON, and accepts gzip; an answer
 * compressed with gzip, also a {@code .gz} file without
 * {@code Content-Encoding}, is uncompressed. A status other than 2xx, a
 * refused connection and a timeout are an {@link HttpSourceException} that
 * names the URL and the status; the values of the headers never appear in
 * messages.
 */
public final class HttpConnector implements Connector {

    /** The time to connect and to wait for the status of an answer, by default. */
    public static final Duration DEFAULT_TIMEOUT = HttpTransport.DEFAULT_TIMEOUT;

    private final String name;
    private final Map<String, Table> tables;
    private final HttpTransport transport;
    private final JsonOptions options;
    private final int batchSize;
    private final Catalog catalog;
    /** The inferred layouts of the tables without a schema, by table name. */
    private final Map<String, JsonLayout> inferred = new ConcurrentHashMap<>();

    /** An endpoint, where in its answer the records are, and its columns if they are given. */
    private record Table(URI uri, String pointer, JsonLayout given) {
    }

    private HttpConnector(Builder b, Map<String, Table> tables, HttpTransport transport) {
        this.name = b.name;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.transport = transport;
        this.options = b.options;
        this.batchSize = b.batchSize;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(HttpConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return layout(table).schema();
            }
        };
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

    private Table table(TableRef ref) {
        Table t = ref.schema().equals(TableRef.MAIN) ? tables.get(ref.table()) : null;
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the HTTP source " + name);
        }
        return t;
    }

    /** Returns the columns of a table: the given ones, or those inferred on first use. */
    private JsonLayout layout(TableRef ref) {
        Table t = table(ref);
        if (t.given() != null) {
            return t.given();
        }
        return inferred.computeIfAbsent(ref.table(), n -> {
            try (HttpTransport.Response response = get(t.uri())) {
                return JsonSchemaInference.infer(response.body(), options, t.pointer(), t.uri().toString());
            }
        });
    }

    /** Forgets the inferred columns of every table, so that the next use infers them again. */
    public void refresh() {
        inferred.clear();
    }

    /** Forgets the inferred columns of a table, so that its next use infers them again. */
    public void refresh(String table) {
        inferred.remove(table);
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        return Capabilities.NONE.withFields(true).plan(table, layout(table).schema(), request, null);
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, null));
    }

    /**
     * Sends the {@code GET} of the table and returns a reader of the
     * records of the answer; canceling the reader closes the body of the
     * answer, so that a reader that waits on it fails at once.
     */
    @Override
    public BatchReader open(Split split) {
        TableRef ref = split.plan().table();
        Table t = table(ref);
        JsonLayout layout = layout(ref);
        HttpTransport.Response response = get(t.uri());
        JsonRecordReader records = JsonRecordReader.open(response.body(), layout, split.plan().columns(), options,
                t.pointer(), batchSize, t.uri().toString());
        return new BatchReader() {
            @Override
            public Batch next() {
                return records.next();
            }

            @Override
            public void close() {
                records.close();
            }

            @Override
            public void cancel() {
                response.abort();
            }
        };
    }

    /**
     * Sends a {@code GET} and returns the answer.
     *
     * @throws HttpSourceException if the status is not 2xx or there is no answer
     */
    private HttpTransport.Response get(URI uri) {
        return transport.get(uri, Map.of());
    }

    /** Shuts the HTTP client down if the connector created it; a client that was given stays open. */
    @Override
    public void close() {
        transport.close();
    }

    /** Collects the endpoints and settings of an HTTP connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, URI> uris = new LinkedHashMap<>();
        private final Map<String, String> pointers = new LinkedHashMap<>();
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private JsonOptions options = JsonOptions.DEFAULT;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpClient client;
        private int batchSize = 2048;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Adds an endpoint as a table; its answer holds the records, see {@link #pointer}. */
        public Builder table(String table, URI uri) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("The URL of the table " + table + " must be http or https: "
                        + uri);
            }
            uris.put(Objects.requireNonNull(table, "table"), uri);
            return this;
        }

        /**
         * Adds an endpoint as a table.
         *
         * @throws IllegalArgumentException if the URL is not one
         */
        public Builder table(String table, String uri) {
            return table(table, URI.create(uri));
        }

        /**
         * Says where in the answer of a table its records are, as a JSON
         * pointer such as {@code /data} or {@code /result/items}; see
         * {@link JsonPointers}. The records are the elements of the array it
         * points to, or the one value there if it is no array; what follows
         * that value is not read. By default the whole answer holds them.
         */
        public Builder pointer(String table, String pointer) {
            pointers.put(table, JsonPointers.check(pointer));
            return this;
        }

        /** Gives a table an explicit schema instead of the inferred one; the names are the keys. */
        public Builder schema(String table, Schema schema) {
            schemas.put(table, Objects.requireNonNull(schema, "schema"));
            return this;
        }

        /**
         * Adds a header that every request carries, such as
         * {@code Authorization}; a header of the same name replaces the
         * default {@code Accept} or {@code Accept-Encoding}.
         *
         * @throws IllegalArgumentException if the HTTP client does not let a
         *         request set the header, such as {@code Host}
         */
        public Builder header(String header, String value) {
            HttpRequest.newBuilder().header(header, value);
            headers.put(header, value);
            return this;
        }

        /** Adds headers that every request carries, see {@link #header}. */
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

        /** Sets all options of reading JSON at once; {@link JsonOptions#DEFAULT} by default. */
        public Builder options(JsonOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder format(JsonFormat format) {
            options = options.withFormat(format);
            return this;
        }

        /** Sets the number of records the columns are inferred from, -1 for all; 20480 by default. */
        public Builder sampleSize(int sampleSize) {
            options = options.withSampleSize(sampleSize);
            return this;
        }

        /** Tells whether a value that does not fit is {@code NULL} and an unknown key skipped. */
        public Builder ignoreErrors(boolean ignoreErrors) {
            options = options.withIgnoreErrors(ignoreErrors);
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
         * Builds the connector; it sends no request until a table is used.
         *
         * @throws IllegalArgumentException if a pointer or schema names no table
         */
        public HttpConnector build() {
            List<String> unknown = new ArrayList<>();
            for (String t : pointers.keySet()) {
                if (!uris.containsKey(t)) {
                    unknown.add(t);
                }
            }
            for (String t : schemas.keySet()) {
                if (!uris.containsKey(t) && !unknown.contains(t)) {
                    unknown.add(t);
                }
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("A pointer or schema is set for tables without a URL: " + unknown
                        + "; the tables are " + uris.keySet());
            }
            Map<String, Table> tables = new LinkedHashMap<>();
            for (Map.Entry<String, URI> e : uris.entrySet()) {
                Schema schema = schemas.get(e.getKey());
                tables.put(e.getKey(), new Table(e.getValue(), pointers.getOrDefault(e.getKey(), ""),
                        schema == null ? null : JsonLayout.of(schema)));
            }
            HttpTransport.Builder transport = HttpTransport.builder()
                    .accept("application/json, application/x-ndjson;q=0.9, */*;q=0.1").headers(headers)
                    .timeout(timeout).source(name);
            if (client != null) {
                transport.client(client);
            }
            return new HttpConnector(this, tables, transport.build());
        }
    }
}
