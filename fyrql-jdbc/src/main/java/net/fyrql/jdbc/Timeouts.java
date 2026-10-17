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

package net.fyrql.jdbc;

import net.fyrql.exec.QueryContext;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The timeouts of queries: one daemon thread of the driver, started with
 * the first timeout, cancels the queries that run too long. A timeout ends
 * a query from another thread because the thread of the query may wait in
 * a source, where no deadline of the engine would reach it.
 */
final class Timeouts {

    private Timeouts() {
    }

    /** The timer, created on first use. */
    private static final class Timer {
        static final ScheduledThreadPoolExecutor EXECUTOR = create();

        private static ScheduledThreadPoolExecutor create() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "fyrql-jdbc-timeout");
                t.setDaemon(true);
                return t;
            });
            // A query that ends in time takes its timeout off the queue.
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }

    /**
     * Cancels a query with {@link QueryContext.Reason#TIMEOUT} after a
     * number of seconds; the caller cancels the returned timeout when the
     * query ends before.
     */
    static ScheduledFuture<?> schedule(int seconds, QueryContext context) {
        return Timer.EXECUTOR.schedule(() -> context.cancel(QueryContext.Reason.TIMEOUT), seconds, TimeUnit.SECONDS);
    }
}
