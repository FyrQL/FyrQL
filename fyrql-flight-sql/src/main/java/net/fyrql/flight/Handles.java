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

package net.fyrql.flight;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The statements or prepared statements the server gave out handles for,
 * each with the client it belongs to: the identity with which the client
 * signed in, or the empty text without sign-in. A handle is a random UUID;
 * a client finds only its own handles, and a handle that stays without use
 * longer than the timeout is forgotten, unless it is busy, such as a
 * statement whose query runs.
 *
 * @param <T> what a handle stands for
 */
final class Handles<T> {

    /** What a handle stands for, its client and when it was last used, in nanoseconds of the clock. */
    private static final class Entry<T> {
        final String owner;
        final T value;
        volatile long used;

        Entry(String owner, T value, long used) {
            this.owner = owner;
            this.value = value;
            this.used = used;
        }
    }

    private static final int UUID_BYTES = 16;

    private final Map<ByteString, Entry<T>> entries = new ConcurrentHashMap<>();
    private final String what;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final Predicate<T> busy;

    /**
     * @param what what the handles stand for, for messages, such as {@code prepared statement}
     * @param timeoutNanos how long a handle stays without use
     * @param clock the time in nanoseconds, such as {@link System#nanoTime}
     * @param busy tells whether a value is in use, so that its handle is not forgotten
     */
    Handles(String what, long timeoutNanos, LongSupplier clock, Predicate<T> busy) {
        this.what = what;
        this.timeoutNanos = timeoutNanos;
        this.clock = clock;
        this.busy = busy;
    }

    /** Gives out a new handle for a value of a client. */
    ByteString add(String owner, T value) {
        UUID id = UUID.randomUUID();
        ByteBuffer b = ByteBuffer.allocate(UUID_BYTES);
        b.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        ByteString handle = ByteString.copyFrom(b.array());
        entries.put(handle, new Entry<>(owner, value, clock.getAsLong()));
        return handle;
    }

    /**
     * Returns the value of a handle of a client and marks it as used.
     *
     * @throws org.apache.arrow.flight.FlightRuntimeException {@code NOT_FOUND}
     *         if the client has no such handle, or it was forgotten
     */
    T get(ByteString handle, String owner) {
        Entry<T> e = entries.get(handle);
        if (e == null || !e.owner.equals(owner)) {
            throw FlightErrors.notFound("The " + what + " is unknown or was closed or forgotten after "
                    + Math.max(1, timeoutNanos / 1_000_000_000L) + " s without use");
        }
        e.used = clock.getAsLong();
        return e.value;
    }

    /** Returns the value of a handle of a client, or {@code null}, without marking it as used. */
    T find(ByteString handle, String owner) {
        Entry<T> e = entries.get(handle);
        return e == null || !e.owner.equals(owner) ? null : e.value;
    }

    /** Forgets a handle of a client; returns its value, or {@code null} if the client has no such handle. */
    T remove(ByteString handle, String owner) {
        Entry<T> e = entries.get(handle);
        if (e == null || !e.owner.equals(owner) || !entries.remove(handle, e)) {
            return null;
        }
        return e.value;
    }

    /** Forgets the handles that stayed without use longer than the timeout and are not busy; returns how many. */
    int expire() {
        long now = clock.getAsLong();
        int n = 0;
        for (Iterator<Entry<T>> i = entries.values().iterator(); i.hasNext();) {
            Entry<T> e = i.next();
            if (busy.test(e.value)) {
                e.used = now;
            } else if (now - e.used > timeoutNanos) {
                i.remove();
                n++;
            }
        }
        return n;
    }

    /** Returns the number of handles. */
    int size() {
        return entries.size();
    }
}
