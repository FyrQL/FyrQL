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
