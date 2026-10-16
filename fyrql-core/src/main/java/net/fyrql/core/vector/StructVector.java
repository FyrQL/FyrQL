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
import net.fyrql.core.type.DataType.Struct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A {@code STRUCT} vector: one child vector per field, all of the same size,
 * plus a null mask for the struct values themselves. A {@code NULL} struct
 * has {@code NULL} in every child at that row.
 */
public final class StructVector implements Vector {

    private final Struct type;
    private final List<Vector> children;
    private final boolean[] nulls;
    private final int size;

    StructVector(Struct type, List<Vector> children, boolean[] nulls, int size) {
        this.type = type;
        this.children = List.copyOf(children);
        this.nulls = nulls;
        this.size = size;
    }

    @Override
    public Struct type() {
        return type;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isNull(int row) {
        return nulls != null && nulls[row];
    }

    /** Returns the vector of a field. */
    public Vector child(int field) {
        return children.get(field);
    }

    /** Returns the vectors of the fields, in order. */
    public List<Vector> children() {
        return children;
    }

    @Override
    public Object get(int row) {
        if (isNull(row)) {
            return null;
        }
        List<Object> values = new ArrayList<>(children.size());
        for (Vector child : children) {
            values.add(child.get(row));
        }
        return values;
    }

    @Override
    public StructVector select(int[] rows) {
        List<Vector> selected = new ArrayList<>(children.size());
        for (Vector child : children) {
            selected.add(child.select(rows));
        }
        return new StructVector(type, selected, Nulls.select(nulls, rows), rows.length);
    }

    /** Builds a {@code STRUCT} vector through one builder per field. */
    public static final class Builder implements Vector.Builder {
        private final Struct type;
        private final List<Vector.Builder> fields;
        private boolean[] nulls;
        private int size;

        public Builder(Struct type, int capacity) {
            this.type = type;
            fields = new ArrayList<>();
            for (Struct.Field f : type.fields()) {
                fields.add(Vectors.builder(f.type(), capacity));
            }
        }

        @Override
        public DataType type() {
            return type;
        }

        /** Returns the builder of a field, to append its value of the next row. */
        public Vector.Builder field(int index) {
            return fields.get(index);
        }

        /** Completes a row after every field builder has received its value. */
        public void endRow() {
            for (Vector.Builder f : fields) {
                if (f.size() != size + 1) {
                    throw new IllegalStateException("Field " + f.type() + " has " + f.size() + " values, expected "
                            + (size + 1));
                }
            }
            size++;
        }

        @Override
        public void appendNull() {
            for (Vector.Builder f : fields) {
                f.appendNull();
            }
            int capacity = nulls == null ? Math.max(16, size + 1)
                    : nulls.length > size ? nulls.length : Math.max(nulls.length * 2, size + 1);
            nulls = Nulls.mark(nulls, size, capacity);
            size++;
        }

        @Override
        public void append(Object value) {
            if (value == null) {
                appendNull();
                return;
            }
            List<?> values = (List<?>) value;
            if (values.size() != fields.size()) {
                throw new IllegalArgumentException("Not a " + type + ": " + values.size() + " values");
            }
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).append(values.get(i));
            }
            size++;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public StructVector build() {
            List<Vector> built = new ArrayList<>(fields.size());
            for (Vector.Builder f : fields) {
                built.add(f.build());
            }
            return new StructVector(type, built, Nulls.trim(nulls == null ? null : Arrays.copyOf(nulls, size), size),
                    size);
        }
    }
}
