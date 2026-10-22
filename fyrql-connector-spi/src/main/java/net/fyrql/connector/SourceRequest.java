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

package net.fyrql.connector;

/**
 * One request of a connector to a remote store or service, such as a
 * {@code GET} of S3 or a query sent to a database: started before it is
 * sent and done once its answer comes, which counts it for the scan the
 * thread works for, see {@link SourceCounters#current()}. The bytes of the
 * answer count as they are read, see {@link SourceCounters#counted}.
 * <p>
 * While Java Flight Recorder records, each request is also an event
 * {@code net.fyrql.SourceRequest} with the source, the kind, the target,
 * the status, the bytes the answer announces and the duration; without a
 * recording, or without the module {@code jdk.jfr}, there is none.
 */
public final class SourceRequest {

    /** Whether the module {@code jdk.jfr} is there, as it may not be in a runtime made with jlink. */
    private static final boolean RECORDABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private final long start;
    private final SourceRequestEvent event;

    private SourceRequest(long start, SourceRequestEvent event) {
        this.start = start;
        this.event = event;
    }

    /** Starts a request now. */
    public static SourceRequest start() {
        return new SourceRequest(System.nanoTime(), RECORDABLE ? SourceRequestEvent.start() : null);
    }

    /**
     * Ends the request and counts it with the time since it started.
     *
     * @param source the name of the source, such as {@code lake}
     * @param kind   what was asked, such as {@code GET} or {@code query}
     * @param target what was asked for, such as a URL or a statement
     * @param status the status of the answer, such as 200, or 0 if it has none
     * @param bytes  the bytes of the answer as it announces them, 0 if none or not known
     */
    public void done(String source, String kind, String target, int status, long bytes) {
        SourceCounters.current().request(System.nanoTime() - start);
        if (event != null) {
            event.done(source, kind, target, status, bytes);
        }
    }
}
