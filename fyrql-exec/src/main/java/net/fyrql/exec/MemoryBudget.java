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
