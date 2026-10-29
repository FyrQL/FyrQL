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

package net.fyrql.jdbc;

import net.fyrql.FyrQL;
import net.fyrql.config.EngineConfig;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engines that connections of the driver share, one per configuration:
 * the first connection builds the engine, the others with an equal
 * {@link Key} take a {@link Lease} on it, and the last one to release its
 * lease closes the engine, at once or after the delay of the key. A
 * connection while the engine waits to be closed keeps it open. The engines
 * belong to the class loader of the driver, as tools that load each driver
 * in a class loader of their own expect.
 * <p>
 * An engine is built under a lock of its own, so that connections that come
 * at the same time build it once, while those of other configurations need
 * not wait for it. A failed build is not remembered: the next connection
 * builds anew. An engine that is about to close has left the table first,
 * so that a new connection builds a new one rather than get it.
 */
final class SharedEngines {

    private static final Logger LOG = Logger.getLogger("net.fyrql.jdbc");

    /**
     * What connections must have in common to share an engine.
     *
     * @param config the configuration of the engine, with all its settings
     * @param closeDelay how long the engine stays open after its last
     *        connection is closed, {@link UrlSettings#NEVER} for until the JVM ends
     */
    record Key(EngineConfig config, Duration closeDelay) {

        Key {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(closeDelay, "closeDelay");
        }
    }

    /** Builds the engine of a key. */
    @FunctionalInterface
    interface Opener {

        FyrQL open() throws SQLException;
    }

    /** The engines by their keys; the lock of all counts and delays. */
    private static final Map<Key, Entry> ENGINES = new HashMap<>();

    /** The thread that closes engines after their delay, made when first needed; guarded by {@link #ENGINES}. */
    private static ScheduledThreadPoolExecutor closer;

    private SharedEngines() {
    }

    /** An engine of the table, with its connections. */
    private static final class Entry {

        final Key key;
        /** The engine, once built; written under the lock of the entry. */
        volatile FyrQL engine;
        /** Whether building the engine failed, so that the entry is out of the table. */
        volatile boolean failed;
        /** The leases that are not released yet; guarded by {@link #ENGINES}. */
        int leases;
        /** The number of the latest planned close, so that a canceled one does nothing; guarded by {@link #ENGINES}. */
        long closes;
        /** The planned close, if any; guarded by {@link #ENGINES}. */
        ScheduledFuture<?> closing;

        Entry(Key key) {
            this.key = key;
        }

        /** Returns the engine, built now if it is not yet, or {@code null} if another connection failed to build it. */
        synchronized FyrQL engine(Opener opener) throws SQLException {
            if (failed) {
                return null;
            }
            if (engine == null) {
                try {
                    engine = Objects.requireNonNull(opener.open(), "engine");
                } catch (Throwable e) {
                    failed = true;
                    throw e;
                }
            }
            return engine;
        }
    }

    /** The share of a connection in an engine, released once. */
    static final class Lease {

        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Entry entry) {
            this.entry = entry;
        }

        /** Returns the engine. */
        FyrQL engine() {
            return entry.engine;
        }

        /**
         * Gives the share back; the last one closes the engine, at once or
         * after the delay of its key.
         *
         * @throws RuntimeException if the engine closes now and a source cannot be closed
         */
        void release() {
            if (released.compareAndSet(false, true)) {
                SharedEngines.release(entry);
            }
        }
    }

    /**
     * Takes a lease on the engine of a key, which the opener builds if
     * there is none.
     *
     * @throws SQLException if the opener fails; the next acquire builds anew
     */
    static Lease acquire(Key key, Opener opener) throws SQLException {
        while (true) {
            Entry entry = hold(key);
            FyrQL engine;
            try {
                engine = entry.engine(opener);
            } catch (Throwable e) {
                drop(entry);
                throw e;
            }
            if (engine != null) {
                return new Lease(entry);
            }
            drop(entry);
        }
    }

    /** Returns the entry of a key, a new one if there is none, with one more lease and no planned close. */
    private static Entry hold(Key key) {
        synchronized (ENGINES) {
            Entry entry = ENGINES.computeIfAbsent(key, Entry::new);
            entry.leases++;
            entry.closes++;
            if (entry.closing != null) {
                entry.closing.cancel(false);
                entry.closing = null;
            }
            return entry;
        }
    }

    /** Takes back the lease of an entry whose engine could not be had, and forgets the entry if it failed. */
    private static void drop(Entry entry) {
        synchronized (ENGINES) {
            entry.leases--;
            if (entry.failed) {
                ENGINES.remove(entry.key, entry);
            }
        }
    }

    private static void release(Entry entry) {
        synchronized (ENGINES) {
            if (--entry.leases > 0) {
                return;
            }
            Duration delay = entry.key.closeDelay();
            if (delay.isNegative()) {
                return;
            }
            if (!delay.isZero()) {
                long close = ++entry.closes;
                entry.closing = closer().schedule(() -> expire(entry, close), delay.toMillis(),
                        TimeUnit.MILLISECONDS);
                return;
            }
            ENGINES.remove(entry.key, entry);
        }
        entry.engine.close();
    }

    /** Closes the engine of an entry once its delay has passed, unless a connection came meanwhile. */
    private static void expire(Entry entry, long close) {
        synchronized (ENGINES) {
            if (entry.leases > 0 || entry.closes != close) {
                return;
            }
            entry.closing = null;
            ENGINES.remove(entry.key, entry);
        }
        try {
            entry.engine.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot close the sources of a shared engine", e);
        }
    }

    /** Returns the thread that closes engines after their delay; under the lock of {@link #ENGINES}. */
    private static ScheduledThreadPoolExecutor closer() {
        if (closer == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "fyrql-jdbc-close");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            closer = executor;
        }
        return closer;
    }

    /** Returns the number of engines in the table, for tests. */
    static int size() {
        synchronized (ENGINES) {
            return ENGINES.size();
        }
    }

    /**
     * Closes all engines of the table that no connection holds, also those
     * that are never closed or wait for their delay, for tests.
     */
    static void closeIdle() {
        List<Entry> idle = new ArrayList<>();
        synchronized (ENGINES) {
            for (Entry entry : List.copyOf(ENGINES.values())) {
                if (entry.leases == 0) {
                    entry.closes++;
                    if (entry.closing != null) {
                        entry.closing.cancel(false);
                        entry.closing = null;
                    }
                    ENGINES.remove(entry.key, entry);
                    idle.add(entry);
                }
            }
        }
        for (Entry entry : idle) {
            entry.engine.close();
        }
    }
}
