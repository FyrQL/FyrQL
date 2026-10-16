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

import net.fyrql.core.value.Keys;
import net.fyrql.core.vector.Batch;

import java.util.List;

/**
 * A key of grouping, distinct and set operations: the canonical key values
 * with the equality of {@link Keys}, where {@code NULL} equals {@code NULL}.
 */
final class RowKey {

    private final Object[] values;
    private final int hash;

    RowKey(Object[] values) {
        this.values = values;
        int h = 1;
        for (Object v : values) {
            h = 31 * h + Keys.hash(v);
        }
        this.hash = h;
    }

    /** Returns the key of the given columns of a row. */
    static RowKey of(Batch batch, List<Integer> columns, int row) {
        Object[] key = new Object[columns.size()];
        for (int k = 0; k < key.length; k++) {
            key[k] = Keys.canonical(batch.column(columns.get(k)).get(row));
        }
        return new RowKey(key);
    }

    /** Returns the key of all columns of a row. */
    static RowKey of(Batch batch, int row) {
        Object[] key = new Object[batch.columns().size()];
        for (int k = 0; k < key.length; k++) {
            key[k] = Keys.canonical(batch.column(k).get(row));
        }
        return new RowKey(key);
    }

    /** Returns the canonical key values. */
    Object[] values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RowKey k) || k.values.length != values.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if (!Keys.equalObjects(values[i], k.values[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
