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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * The threads on which the operators of all engines of a JVM run parts of
 * queries in parallel: daemon threads named {@code fyrql-worker-N}, created
 * when needed and ended after a minute without work. The number of threads
 * of an executor limits the tasks of one query, not this pool, which never
 * makes a task wait for a thread: no task waits for another one, so the
 * tasks of several queries, e.g. of several connections of the JDBC driver,
 * cannot block each other.
 */
final class Workers {

    private static final AtomicInteger NUMBER = new AtomicInteger();

    private Workers() {
    }

    /** The pool, created with the first task. */
    private static final class Pool {
        static final ExecutorService THREADS = Executors.newCachedThreadPool(task -> {
            Thread t = new Thread(task, "fyrql-worker-" + NUMBER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs a task on a thread of the pool. */
    static <T> Future<T> submit(Callable<T> task) {
        return Pool.THREADS.submit(task);
    }

    /**
     * Runs the tasks 0 to {@code tasks - 1} on up to {@code threads}
     * threads, the calling one among them, each thread taking the next task
     * until none is left, and waits for all of them. Once a task fails, no
     * thread takes a further one with a larger number, but every task with
     * a smaller number runs, so that the failure is the one of the first
     * task that fails, as if they ran one after the other.
     *
     * @throws RuntimeException the failure of the task with the smallest number that failed
     */
    static void run(int tasks, int threads, IntConsumer task) {
        AtomicInteger next = new AtomicInteger();
        Throwable[] failures = new Throwable[tasks];
        AtomicInteger firstFailed = new AtomicInteger(tasks);
        Runnable loop = () -> {
            for (int t = next.getAndIncrement(); t < tasks && t < firstFailed.get(); t = next.getAndIncrement()) {
                try {
                    task.accept(t);
                } catch (RuntimeException | Error e) {
                    failures[t] = e;
                    firstFailed.accumulateAndGet(t, Math::min);
                }
            }
        };
        List<Future<?>> others = new ArrayList<>();
        try {
            for (int i = 1; i < Math.min(threads, tasks); i++) {
                others.add(Pool.THREADS.submit(loop));
            }
        } catch (RuntimeException e) {
            // The calling thread does what the threads not started would have done.
        }
        loop.run();
        boolean interrupted = false;
        for (Future<?> f : others) {
            while (true) {
                try {
                    f.get();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        for (Throwable e : failures) {
            if (e instanceof RuntimeException r) {
                throw r;
            }
            if (e instanceof Error r) {
                throw r;
            }
        }
    }
}
