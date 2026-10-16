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

import net.fyrql.core.type.DataType;

/**
 * A column of values of one type, stored column-wise: a primitive array per
 * scalar type, a byte buffer with offsets for {@code VARCHAR} and
 * {@code BLOB}, child vectors for the nested types, and a null mask. Vectors
 * are immutable once built; a {@link Builder} fills one value at a time.
 * Access to a value as a Java object goes through {@link #get}; the typed
 * accessors of the implementations avoid the boxing.
 */
public sealed interface Vector permits BooleanVector, TinyIntVector, SmallIntVector, IntVector, BigIntVector,
        HugeIntVector, FloatVector, DoubleVector, DecimalVector, BytesVector, UuidVector, StructVector, ListVector,
        MapVector {

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

        /** Returns the number of values appended so far. */
        int size();

        /** Returns the vector; the builder must not be used afterwards. */
        Vector build();
    }
}
