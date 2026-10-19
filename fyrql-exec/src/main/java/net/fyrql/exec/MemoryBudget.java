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

/**
 * The memory a query may hold in its pipeline breakers (join build sides,
 * aggregation state, sorted input). Until there is spilling, a query that
 * exceeds the budget fails instead of filling the heap.
 */
public final class MemoryBudget {

    private final long limit;
    private long used;

    /** A budget of the given number of bytes. */
    public MemoryBudget(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit: " + limit);
        }
        this.limit = limit;
    }

    /** A budget that never runs out. */
    public static MemoryBudget unlimited() {
        return new MemoryBudget(Long.MAX_VALUE);
    }

    /**
     * Takes bytes from the budget.
     *
     * @throws QueryException if the budget is exceeded
     */
    public synchronized void reserve(long bytes, String operator) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes: " + bytes);
        }
        if (limit - used < bytes) {
            throw new QueryException(operator, "Memory budget of " + limit + " bytes exceeded: " + (used + bytes)
                    + " bytes needed");
        }
        used += bytes;
    }

    /** Gives bytes back. */
    public synchronized void release(long bytes) {
        used = Math.max(0, used - bytes);
    }

    /** Returns the bytes in use. */
    public synchronized long used() {
        return used;
    }

    /** Returns the limit. */
    public long limit() {
        return limit;
    }
}
