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

import net.fyrql.core.type.DataType;

/**
 * A column of values of one type, stored column-wise: a primitive array per
 * scalar type, a byte buffer with offsets, or codes into a shared
 * dictionary, for {@code VARCHAR} and {@code BLOB}, child vectors for the
 * nested types, and a null mask. Vectors
 * are immutable once built; a {@link Builder} fills one value at a time.
 * Access to a value as a Java object goes through {@link #get}; the typed
 * accessors of the implementations avoid the boxing.
 */
public sealed interface Vector permits BooleanVector, TinyIntVector, SmallIntVector, IntVector, BigIntVector,
        HugeIntVector, FloatVector, DoubleVector, DecimalVector, BytesVector, UuidVector, IntervalVector,
        StructVector, ListVector, MapVector {

    /** Returns the type of the values. */
    DataType type();

    /** Returns the number of values, including {@code NULL}s. */
    int size();

    /** Tells whether the value at a row is {@code NULL}. */
    boolean isNull(int row);

    /** Returns the value at a row as a Java object, {@code null} for {@code NULL}. */
    Object get(int row);

    /** Returns a vector with the values at the given rows, in that order. */
    Vector select(int[] rows);

    /** Tells whether any value is {@code NULL}. */
    default boolean hasNulls() {
        for (int i = 0; i < size(); i++) {
            if (isNull(i)) {
                return true;
            }
        }
        return false;
    }

    /** Fills a vector value by value. */
    interface Builder {

        /** Returns the type of the vector being built. */
        DataType type();

        /** Appends a {@code NULL}. */
        void appendNull();

        /**
         * Appends a value as a Java object, {@code null} for {@code NULL}.
         *
         * @throws IllegalArgumentException if the value does not belong to the type
         */
        void append(Object value);

        /**
         * Makes room for a number of further values, so that appending a
         * long run of them grows the arrays once rather than step by step;
         * a hint, which builders without arrays of their own ignore.
         */
        default void reserve(int more) {
        }

        /** Returns the number of values appended so far. */
        int size();

        /** Returns the vector; the builder must not be used afterwards. */
        Vector build();
    }
}
