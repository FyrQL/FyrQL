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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.http.HttpTransport;
import net.fyrql.connector.json.JsonLayout;
import net.fyrql.connector.json.JsonOptions;
import net.fyrql.connector.json.JsonRecordReader;
import net.fyrql.core.vector.Batch;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Reads the records of a query of an entity set page after page: each
 * page is streamed, and the link to the next page, which follows its
 * records, is requested only when the records of the page are read, so
 * that a reader that is closed early asks for no more pages than it
 * needs. A link is absolute or relative to the root of the service, and
 * is followed as it is, with the headers of the first request; it must
 * stay on the host of the service.
 * <p>
 * If the first page tells the number of all records and the last page
 * ends before that many are read, or before as many as the query is
 * limited to, the answer was cut short, which is an error rather than a
 * result without the missing rows. So is an answer that is not JSON, such
 * as Atom, and an error of the service, with the message the service
 * sends. A reader with a limit stops after that many records, also when
 * the service sends more.
 * <p>
 * Some services fail on a {@code $select} of complex or collection
 * properties, with the status 400 or 5xx. A reader that has another query
 * for that case, the same without {@code $select}, asks it once instead
 * when the first page fails so, and tells when it was answered.
 */
final class PagedReader implements BatchReader {

    private final ODataClient client;
    private final RequestTarget target;
    private final URI root;
    private final ODataVersion version;
    private final Map<String, String> headers;
    private final JsonLayout layout;
    private final List<ColumnPath> columns;
    private final int batchSize;
    /** The most records to read, or -1 for all. */
    private final long limit;
    /** The URL of the next page to request, or {@code null} after the last one. */
    private URI next;
    /** The URL of the first page without {@code $select}, or {@code null}. */
    private URI fallback;
    private final Runnable onFallback;
    private URI current;
    private volatile HttpTransport.Response response;
    private JsonRecordReader page;
    private long read;
    /** The number of all records, as the first page tells it, or -1 if it does not. */
    private long count = -1;
    private volatile boolean canceled;

    /**
     * @param root the root of the service, against which relative links resolve
     * @param first the URL of the first page
     * @param fallback the URL of the first page without {@code $select},
     *        for a service that fails on the {@code $select} of the first,
     *        or {@code null}
     * @param onFallback what to do when the service answered the fallback
     * @param headers the headers of every request besides those of the source
     * @param layout the columns of the entity set and how their values are read
     * @param columns the columns to read
     * @param limit the most records to read, or -1 for all
     */
    PagedReader(ODataClient client, RequestTarget target, URI root, ODataVersion version, URI first, URI fallback,
            Runnable onFallback, Map<String, String> headers, JsonLayout layout, List<ColumnPath> columns,
            int batchSize, long limit) {
        this.client = Objects.requireNonNull(client, "client");
        this.target = Objects.requireNonNull(target, "target");
        this.root = root;
        this.version = version;
        this.next = first;
        this.fallback = fallback;
        this.onFallback = Objects.requireNonNull(onFallback, "onFallback");
        this.headers = Map.copyOf(headers);
        this.layout = layout;
        this.columns = List.copyOf(columns);
        this.batchSize = batchSize;
        this.limit = limit;
    }

    @Override
    public Batch next() {
        if (canceled) {
            throw canceled();
        }
        while (true) {
            if (page == null) {
                if (next == null || read == limit) {
                    return null;
                }
                boolean first = current == null;
                try {
                    open(next);
                } catch (ODataSourceException e) {
                    if (!first || fallback == null || e.status() != 400 && e.status() < 500) {
                        throw e;
                    }
                    URI plain = fallback;
                    fallback = null;
                    current = null;
                    next = plain;
                    open(plain);
                    onFallback.run();
                }
            }
            Batch batch = page.next();
            if (batch != null) {
                if (limit >= 0 && read + batch.rowCount() >= limit) {
                    int keep = (int) (limit - read);
                    if (keep < batch.rowCount()) {
                        int[] rows = new int[keep];
                        for (int r = 0; r < keep; r++) {
                            rows[r] = r;
                        }
                        batch = batch.select(rows);
                    }
                    close();
                }
                read += batch.rowCount();
                return batch;
            }
            Map<String, String> siblings = page.siblings();
            closePage();
            if (count < 0 && siblings.containsKey(version.countKey())) {
                count = count(siblings.get(version.countKey()));
            }
            String link = siblings.get(version.nextLinkKey());
            next = link == null ? null : link(link);
            long expected = limit >= 0 ? Math.min(count, limit) : count;
            if (next == null && count >= 0 && read < expected) {
                throw target.error("answered " + request() + " with " + read + " of " + expected
                        + " records and no link to the next page; the answer is incomplete", -1, null);
            }
        }
    }

    private CancellationException canceled() {
        return new CancellationException(target.subject() + ": " + request() + " was canceled");
    }

    private void open(URI uri) {
        RequestSpec.Kind kind = current == null ? RequestSpec.Kind.QUERY : RequestSpec.Kind.NEXT_PAGE;
        current = uri;
        HttpTransport.Response r = client.get(target, kind, uri, headers);
        response = r;
        if (!RequestTarget.isJson(r)) {
            String type = r.contentType();
            String excerpt = r.excerpt();
            r.close();
            throw target.error("answered " + request() + " at " + ODataQuery.shown(uri) + " with " + type
                    + " instead of JSON" + (type.contains("xml") ? "; the connector asks for JSON with the header"
                    + " Accept" + (version == ODataVersion.V2 ? " and $format=json" : "") + ", which the service"
                    + " ignores" : excerpt.isEmpty() ? "" : ": " + excerpt), r.status(), null);
        }
        try {
            page = JsonRecordReader.open(r.body(), layout, columns, JsonOptions.DEFAULT, version.recordsPointer(),
                    batchSize, ODataQuery.shown(uri), Set.of(version.nextLinkKey(), version.countKey()));
        } catch (RuntimeException e) {
            r.close();
            throw e;
        }
    }

    /** Returns the URL of the next page. */
    private URI link(String link) {
        URI uri;
        try {
            uri = ODataQuery.next(root, link);
        } catch (IllegalArgumentException e) {
            throw target.error("links to the next page of " + request() + " with something that is no URL: "
                    + e.getMessage(), -1, e);
        }
        if (!ODataQuery.sameOrigin(uri, root)) {
            throw target.error("links to the next page of " + request() + " at another host, "
                    + ODataQuery.shown(uri) + "; the connector follows links to next pages only on the host of the"
                    + " service", -1, null);
        }
        if (uri.equals(current)) {
            throw target.error("links to the same page of " + request() + " again, " + ODataQuery.shown(uri), -1,
                    null);
        }
        return uri;
    }

    private long count(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw target.error("answered " + request() + " with the count " + text + ", which is no number", -1,
                    null);
        }
    }

    private String request() {
        return target.request();
    }

    private void closePage() {
        if (page != null) {
            page.close();
            page = null;
        }
        HttpTransport.Response r = response;
        if (r != null) {
            r.close();
            response = null;
        }
    }

    @Override
    public void close() {
        next = null;
        closePage();
    }

    /**
     * Closes the body of the current page, so that a reader that waits on
     * it fails at once; the reader requests no more pages.
     */
    @Override
    public void cancel() {
        canceled = true;
        HttpTransport.Response r = response;
        if (r != null) {
            r.abort();
        }
    }
}
