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
