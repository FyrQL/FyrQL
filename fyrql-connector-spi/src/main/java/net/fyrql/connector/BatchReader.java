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

package net.fyrql.connector;

import net.fyrql.core.vector.Batch;

/** Delivers the batches of one split, with the schema of its scan plan. */
public interface BatchReader extends AutoCloseable {

    /** Returns the next batch, or {@code null} after the last one. */
    Batch next();

    @Override
    default void close() {
    }

    /**
     * Asks the reader to stop, from another thread, while {@link #next()}
     * may be waiting on its source: a reader of a remote source cancels the
     * request, so that {@code next()} returns early, usually with an
     * exception. It is a request at best effort, which a reader may ignore,
     * as the default does; it must be safe to call at any time, also more
     * than once and after {@link #close()}, and it does not close the
     * reader.
     */
    default void cancel() {
    }
}
