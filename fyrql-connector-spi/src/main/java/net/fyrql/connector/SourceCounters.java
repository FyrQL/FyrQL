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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts what the scan of a query reads from its source, for
 * {@code EXPLAIN ANALYZE}: the splits, the requests to a remote store or
 * service, the bytes, the row groups and files read and skipped, and the
 * statements sent to a database.
 * <p>
 * The engine enters the counters of a scan on the thread that plans,
 * splits, opens and reads it, see {@link #enter(SourceCounters)}; a
 * connector reports to {@link #current()} where it reads, on that thread,
 * without knowing the query. A reader that read on a thread of its own would
 * report to nothing. Outside of a scan, {@link #current()} is {@link #NONE},
 * which counts nothing. The counters may be updated from several threads at
 * once, by the workers that read the splits of one scan.
 */
public final class SourceCounters {

    /** The counters outside of a scan, which count nothing. */
    public static final SourceCounters NONE = new SourceCounters();

    private static final ThreadLocal<SourceCounters[]> CURRENT = ThreadLocal.withInitial(
            () -> new SourceCounters[] {NONE});

    private final LongAdder splits = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder requestNanos = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder rowGroupsRead = new LongAdder();
    private final LongAdder rowGroupsSkipped = new LongAdder();
    private final LongAdder filesRead = new LongAdder();
    private final LongAdder filesSkipped = new LongAdder();
    private final List<String> statements = new ArrayList<>();

    /** Returns the counters of the scan the current thread works for, or {@link #NONE}. */
    public static SourceCounters current() {
        return CURRENT.get()[0];
    }

    /**
     * Makes counters the current ones of this thread until the returned
     * scope is closed, which makes the previous ones current again.
     */
    public static Scope enter(SourceCounters counters) {
        SourceCounters[] holder = CURRENT.get();
        Scope scope = new Scope(holder, holder[0]);
        holder[0] = counters;
        return scope;
    }

    /** Counts the splits of the scan. */
    public void splits(long count) {
        if (this != NONE) {
            splits.add(count);
        }
    }

    /**
     * Counts a request to a remote store or service, with the time until its
     * answer came; the bytes of the answer count as they are read, see
     * {@link #counted(InputStream)}.
     */
    public void request(long nanos) {
        if (this != NONE) {
            requests.increment();
            requestNanos.add(nanos);
        }
    }

    /** Counts bytes read, such as from a local file or the body of an answer. */
    public void bytes(long count) {
        if (this != NONE) {
            bytes.add(count);
        }
    }

    /** Returns a stream that counts the bytes read from another one as {@link #bytes(long)}. */
    public InputStream counted(InputStream in) {
        return this == NONE ? in : new Counted(in, this);
    }

    /** Counts the row groups of a file that are read and those that are skipped. */
    public void rowGroups(long read, long skipped) {
        if (this != NONE) {
            rowGroupsRead.add(read);
            rowGroupsSkipped.add(skipped);
        }
    }

    /** Counts the data files of a table that are read and those that are skipped. */
    public void files(long read, long skipped) {
        if (this != NONE) {
            filesRead.add(read);
            filesSkipped.add(skipped);
        }
    }

    /** Notes a statement sent to a database. */
    public void statement(String sql) {
        if (this != NONE) {
            synchronized (statements) {
                statements.add(sql);
            }
        }
    }

    /** Returns what was counted so far. */
    public SourceStatistics snapshot() {
        List<String> sent;
        synchronized (statements) {
            sent = List.copyOf(statements);
        }
        return new SourceStatistics(splits.sum(), requests.sum(), requestNanos.sum(), bytes.sum(),
                rowGroupsRead.sum(), rowGroupsSkipped.sum(), filesRead.sum(), filesSkipped.sum(), sent);
    }

    /** Counts the bytes read from a stream. */
    private static final class Counted extends FilterInputStream {
        private final SourceCounters counters;

        Counted(InputStream in, SourceCounters counters) {
            super(in);
            this.counters = counters;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                counters.bytes(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                counters.bytes(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(n);
            if (skipped > 0) {
                counters.bytes(skipped);
            }
            return skipped;
        }
    }

    /** The time in which counters are the current ones of a thread; closing it ends it. */
    public static final class Scope implements AutoCloseable {
        private final SourceCounters[] holder;
        private final SourceCounters previous;

        private Scope(SourceCounters[] holder, SourceCounters previous) {
            this.holder = holder;
            this.previous = previous;
        }

        @Override
        public void close() {
            holder[0] = previous;
        }
    }
}
