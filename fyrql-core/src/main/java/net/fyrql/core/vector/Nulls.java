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

package net.fyrql.core.vector;

import java.util.Arrays;

/**
 * The null mask of a vector: a {@code boolean[]} with {@code true} where the
 * value is {@code NULL}, or {@code null} when no value is. The
 * representation is private to this package; callers ask
 * {@link Vector#isNull}.
 */
final class Nulls {

    private Nulls() {
    }

    /** Returns the mask for the selected rows, or {@code null} if none of them is {@code NULL}. */
    static boolean[] select(boolean[] nulls, int[] rows) {
        if (nulls == null) {
            return null;
        }
        boolean[] selected = null;
        for (int i = 0; i < rows.length; i++) {
            if (nulls[rows[i]]) {
                if (selected == null) {
                    selected = new boolean[rows.length];
                }
                selected[i] = true;
            }
        }
        return selected;
    }

    /** Returns the mask trimmed to a size, or {@code null} if it marks nothing. */
    static boolean[] trim(boolean[] nulls, int size) {
        if (nulls == null) {
            return null;
        }
        boolean[] trimmed = nulls.length == size ? nulls : Arrays.copyOf(nulls, size);
        for (boolean b : trimmed) {
            if (b) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * Checks a mask from outside the package against the size of a vector;
     * returns it, or {@code null} if it marks nothing.
     */
    static boolean[] wrap(boolean[] nulls, int size) {
        if (nulls != null && nulls.length != size) {
            throw new IllegalArgumentException("Null mask of length " + nulls.length + " for " + size + " values");
        }
        return trim(nulls, size);
    }

    /** Marks a row in a mask, creating the mask if needed; returns the mask. */
    static boolean[] mark(boolean[] nulls, int row, int capacity) {
        if (nulls == null) {
            nulls = new boolean[capacity];
        } else if (nulls.length < capacity) {
            nulls = Arrays.copyOf(nulls, capacity);
        }
        nulls[row] = true;
        return nulls;
    }

    /** Returns the mask grown to a capacity, if it exists. */
    static boolean[] grow(boolean[] nulls, int capacity) {
        return nulls == null || nulls.length >= capacity ? nulls : Arrays.copyOf(nulls, capacity);
    }
}
