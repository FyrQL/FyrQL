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

    /** Tells whether a mask marks any of the first rows; without a mask, at once. */
    static boolean any(boolean[] nulls, int size) {
        if (nulls == null) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (nulls[i]) {
                return true;
            }
        }
        return false;
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
