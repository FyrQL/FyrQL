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

/**
 * The hash and the equality of keys made of several columns, as hash joins
 * and aggregations use them. Every implementation must compute exactly the
 * hashes of {@link InterpretedKeys}, so that an operator may switch from one
 * to another while it runs: a hash table built with one is probed with the
 * other.
 * <p>
 * Equality is the one of grouping: {@code NULL} equals {@code NULL}, NaN
 * equals NaN, {@code -0.0} equals {@code 0.0}, and strings are equal when
 * their bytes are.
 */
public interface KeyFunctions {

    /**
     * Computes the hash of the key columns of every row of a batch into
     * {@code out}, which has room for the rows.
     */
    void hash(Batch b, int[] columns, int[] out);

    /** Tells whether the keys of two rows, which may be in different batches, are equal. */
    boolean equal(Batch a, int rowA, int[] columnsA, Batch b, int rowB, int[] columnsB);
}
