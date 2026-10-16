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

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

/**
 * A physical operator in the Volcano style over batches: {@link #next()}
 * pulls the next batch, {@code null} after the last one. An operator never
 * returns an empty batch.
 */
public interface Operator extends AutoCloseable {

    /** Returns the columns of the batches. */
    Schema schema();

    /** Returns the next batch, or {@code null} at the end. */
    Batch next();

    @Override
    void close();
}
