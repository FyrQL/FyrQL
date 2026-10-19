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

package net.fyrql.exec.spi;

import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

/**
 * The key functions of the interpreter, for keys of any type: the hash
 * combines {@link Vectors#hash} of the columns as
 * {@code h = 31 * h + hash(column)}, starting from 17, and spreads the
 * result as {@code h ^ (h >>> 16)}; equality is
 * {@link Vectors#compareNullable} equal to zero.
 */
public final class InterpretedKeys implements KeyFunctions {

    /** The single instance. */
    public static final InterpretedKeys INSTANCE = new InterpretedKeys();

    private InterpretedKeys() {
    }

    @Override
    public void hash(Batch b, int[] columns, int[] out) {
        int n = b.rowCount();
        for (int i = 0; i < n; i++) {
            out[i] = 17;
        }
        for (int c : columns) {
            Vector v = b.column(c);
            for (int i = 0; i < n; i++) {
                out[i] = out[i] * 31 + Vectors.hash(v, i);
            }
        }
        for (int i = 0; i < n; i++) {
            out[i] ^= out[i] >>> 16;
        }
    }

    @Override
    public boolean equal(Batch a, int rowA, int[] columnsA, Batch b, int rowB, int[] columnsB) {
        for (int k = 0; k < columnsA.length; k++) {
            if (Vectors.compareNullable(a.column(columnsA[k]), rowA, b.column(columnsB[k]), rowB) != 0) {
                return false;
            }
        }
        return true;
    }
}
