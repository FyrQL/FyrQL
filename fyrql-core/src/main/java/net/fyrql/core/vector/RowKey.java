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

package net.fyrql.core.vector;

import net.fyrql.core.value.Casts;

/**
 * The values of key columns at a row, as a key of a hash map: equal as join
 * keys are equal, see {@link Vectors#equal}, with {@code NULL} equal to
 * {@code NULL}.
 * <p>
 * Internal; not covered by the compatibility promise.
 *
 * @param columns the key columns, of the same number of rows
 * @param row the row
 */
public record RowKey(Vector[] columns, int row) {

    /** Returns the key at a row of the given columns of a batch. */
    public static RowKey of(Batch batch, int[] columns, int row) {
        return new RowKey(columns(batch, columns), row);
    }

    /** Returns the given columns of a batch, to make keys of its rows. */
    public static Vector[] columns(Batch batch, int[] columns) {
        Vector[] result = new Vector[columns.length];
        for (int c = 0; c < columns.length; c++) {
            result[c] = batch.column(columns[c]);
        }
        return result;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (Vector c : columns) {
            h = 31 * h + Vectors.hash(c, row);
        }
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RowKey k) || k.columns.length != columns.length) {
            return false;
        }
        for (int c = 0; c < columns.length; c++) {
            if (!Vectors.equal(columns[c], row, k.columns[c], k.row)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the values as text, separated by commas. */
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < columns.length; c++) {
            b.append(c > 0 ? ", " : "").append(text(columns[c], row));
        }
        return b.toString();
    }

    /** Returns the value at a row as text, {@code NULL} for {@code NULL}. */
    public static String text(Vector v, int row) {
        return v.isNull(row) ? "NULL" : Casts.format(v.get(row), v.type());
    }
}
