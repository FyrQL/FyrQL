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

package net.fyrql.exec;

import net.fyrql.core.vector.Batch;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hands the morsels of a segment to its workers, in their order, and the
 * batches of the workers to the consumer, the thread of the query.
 * <p>
 * In order, the consumer gets the batches morsel by morsel, in the order
 * of the morsels, as one thread would read them. A worker starts a morsel
 * only within a window of morsels after the first one the consumer has not
 * got completely, so that at most that many morsels wait for an earlier
 * one; the worker of that first morsel waits once the consumer has a few
 * of its batches to take. Out of order, the consumer gets the batches as
 * they come, and a worker waits while there are a few of them. To collect,
 * the workers keep what they make of their batches themselves, and the
 * consumer waits until they have finished.
 * <p>
 * A failure of a worker stops the handing out of the morsels that are no
 * longer needed: in order and to collect, those after the failed one, so
 * that the consumer gets the batches before the failure, also those of the
 * failed morsel, or fails with the failure of the first morsel that fails,
 * as one thread would; out of order, all of
 * them, and the consumer fails at once, with the failure of the smallest
 * morsel so far. No one waits once the exchange is closed, and the
 * consumer stops waiting once the query is canceled.
 */
final class Exchange implements MorselScan.Morsels {

    /** How the consumer gets the batches of the workers. */
    enum Mode {
        /** Batch by batch, in the order of the morsels. */
        ORDERED,
        /** Batch by batch, as they come. */
        UNORDERED,
        /** Not through the exchange: the workers keep them, and the consumer waits until they have finished. */
        COLLECT
    }

    /** The batches each worker may have in the exchange before it waits. */
    static final int BATCHES_PER_WORKER = 2;

    /** The morsels each worker may run ahead of the first one the consumer has not got completely. */
    static final int MORSELS_PER_WORKER = 2;

    /** A failure of a worker, at a morsel or, at {@code -1}, before one. */
    private record Failure(int index, Throwable error) {
    }

    /** The batches of a morsel in order, and whether its worker has finished it. */
    private static final class Slot {
        final ArrayDeque<Batch> batches = new ArrayDeque<>();
        boolean finished;
    }

    private final List<Morsel> morsels;
    private final Mode mode;
    private final int capacity;
    private final int window;
    private final QueryContext context;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();

    /** The next morsel to hand out, and the first one not to hand out. */
    private int handedOut;
    private int end;
    private int running;
    private boolean closed;
    private Failure failure;

    /** In order: the morsels by index, and the first one the consumer has not got completely. */
    private final Map<Integer, Slot> slots = new HashMap<>();
    private int passed;

    /** Out of order: the batches as they come. */
    private final ArrayDeque<Batch> batches = new ArrayDeque<>();

    /**
     * @param morsels the morsels of the segment, in their order
     * @param mode    how the consumer gets the batches
     * @param workers the workers that take the morsels
     * @param context the context of the query
     */
    Exchange(List<Morsel> morsels, Mode mode, int workers, QueryContext context) {
        this.morsels = List.copyOf(morsels);
        this.mode = mode;
        this.capacity = BATCHES_PER_WORKER * workers;
        this.window = MORSELS_PER_WORKER * workers;
        this.context = context;
        this.end = this.morsels.size();
        this.running = workers;
    }

    @Override
    public Morsel next(int chain, Morsel finished) {
        lock.lock();
        try {
            if (finished != null && mode == Mode.ORDERED) {
                slot(finished.index()).finished = true;
                changed.signalAll();
            }
            while (true) {
                if (closed || handedOut >= end) {
                    return null;
                }
                Morsel m = morsels.get(handedOut);
                if (m.chain() != chain) {
                    return null;
                }
                if (mode != Mode.ORDERED || m.index() < passed + window) {
                    handedOut++;
                    return m;
                }
                changed.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Puts a batch of a worker, which waits while the exchange is full.
     *
     * @return {@code false} if the batch is not needed, because the exchange is closed or failed
     */
    boolean put(Morsel morsel, Batch b) {
        lock.lock();
        try {
            while (true) {
                if (!wanted(morsel)) {
                    return false;
                }
                if (mode == Mode.ORDERED) {
                    Slot s = slot(morsel.index());
                    // Only the batches of the first morsel are bounded; the window bounds the others.
                    if (morsel.index() != passed || s.batches.size() < capacity) {
                        s.batches.add(b);
                        changed.signalAll();
                        return true;
                    }
                } else if (batches.size() < capacity) {
                    batches.add(b);
                    changed.signalAll();
                    return true;
                }
                changed.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tells whether the batches of a morsel are still needed, which they are
     * not once the exchange is closed or a morsel before it failed, or any
     * morsel out of order.
     */
    boolean wanted(Morsel morsel) {
        lock.lock();
        try {
            return !closed && (failure == null || mode != Mode.UNORDERED && failure.index() >= morsel.index());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Notes the failure of a worker at a morsel.
     *
     * @param morsel the morsel the worker read last, or {@code null} if it had none
     */
    void fail(Morsel morsel, Throwable error) {
        lock.lock();
        try {
            int index = morsel == null ? -1 : morsel.index();
            if (failure == null || index < failure.index()) {
                failure = new Failure(index, error);
            }
            end = mode == Mode.UNORDERED ? 0 : Math.min(end, index + 1);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Notes that a worker has finished. */
    void finished() {
        lock.lock();
        try {
            running--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the next batch for the consumer, which waits for it, or
     * {@code null} at the end or once the query is canceled.
     *
     * @throws RuntimeException the failure of a worker
     */
    Batch take() {
        lock.lock();
        try {
            while (true) {
                if (context.isCancelled()) {
                    return null;
                }
                if (mode == Mode.ORDERED) {
                    // The batches of the failed morsel before its failure come first, as on one thread.
                    Slot s = slots.get(passed);
                    if (s != null && !s.batches.isEmpty()) {
                        changed.signalAll();
                        return s.batches.poll();
                    }
                    if (failure != null && failure.index() <= passed) {
                        throw rethrow(failure.error());
                    }
                    if (passed == morsels.size()) {
                        return null;
                    }
                    if (s != null && s.finished) {
                        slots.remove(passed);
                        passed++;
                        changed.signalAll();
                        continue;
                    }
                } else {
                    if (failure != null) {
                        throw rethrow(failure.error());
                    }
                    Batch b = batches.poll();
                    if (b != null) {
                        changed.signalAll();
                        return b;
                    }
                }
                if (running == 0) {
                    if (mode == Mode.ORDERED) {
                        throw new IllegalStateException("The workers of the segment ended before morsel " + passed);
                    }
                    return null;
                }
                changed.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the workers have finished, for the consumer that collects.
     *
     * @return {@code false} if the query is canceled
     * @throws RuntimeException the failure of the first morsel that failed
     */
    boolean await() {
        lock.lock();
        try {
            while (true) {
                if (context.isCancelled()) {
                    return false;
                }
                if (running == 0) {
                    if (failure != null) {
                        throw rethrow(failure.error());
                    }
                    return true;
                }
                changed.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Wakes the consumer, which looks whether the query is canceled. */
    void wake() {
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Closes the exchange: no one waits any longer, and no morsel is handed out. */
    void close() {
        lock.lock();
        try {
            closed = true;
            slots.clear();
            batches.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private Slot slot(int index) {
        return slots.computeIfAbsent(index, i -> new Slot());
    }

    private static RuntimeException rethrow(Throwable error) {
        if (error instanceof Error e) {
            throw e;
        }
        return error instanceof RuntimeException e ? e : new IllegalStateException(error);
    }
}
