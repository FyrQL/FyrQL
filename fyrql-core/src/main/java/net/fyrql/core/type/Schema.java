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

package net.fyrql.core.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The columns of a table or of the output of an operator.
 *
 * @param fields the columns, in order
 */
public record Schema(List<Schema.Field> fields) {

    /**
     * A column.
     *
     * @param name the name
     * @param type the type
     * @param nullable whether the column may contain {@code NULL}
     */
    public record Field(String name, DataType type, boolean nullable) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        /** Creates a nullable column. */
        public Field(String name, DataType type) {
            this(name, type, true);
        }

        @Override
        public String toString() {
            return name + " " + type.sqlName() + (nullable ? "" : " NOT NULL");
        }
    }

    public Schema {
        fields = List.copyOf(fields);
    }

    /** Returns a schema of nullable columns with the given types and the names {@code c0}, {@code c1}, ... */
    public static Schema of(DataType... types) {
        return build(true, types);
    }

    /** Returns a schema of columns without {@code NULL}, named {@code c0}, {@code c1}, ... */
    public static Schema notNull(DataType... types) {
        return build(false, types);
    }

    /** Returns a schema of the given fields. */
    public static Schema of(Field... fields) {
        return new Schema(List.of(fields));
    }

    private static Schema build(boolean nullable, DataType... types) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            fields.add(new Field("c" + i, types[i], nullable));
        }
        return new Schema(fields);
    }

    /** Tells whether a column may contain {@code NULL}. */
    public boolean nullable(int index) {
        return fields.get(index).nullable();
    }

    /** Returns the number of columns. */
    public int size() {
        return fields.size();
    }

    /** Returns the type of a column. */
    public DataType type(int index) {
        return fields.get(index).type();
    }

    /** Returns the name of a column. */
    public String name(int index) {
        return fields.get(index).name();
    }

    /** Returns the index of the column with the name, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the types of the columns, in order. */
    public List<DataType> types() {
        return fields.stream().map(Field::type).toList();
    }

    /** Returns a schema with the given columns of this one, in that order. */
    public Schema select(List<Integer> indexes) {
        List<Field> selected = new ArrayList<>();
        for (int i : indexes) {
            selected.add(fields.get(i));
        }
        return new Schema(selected);
    }

    /** Returns the columns of this schema followed by the columns of another. */
    public Schema concat(Schema other) {
        List<Field> all = new ArrayList<>(fields);
        all.addAll(other.fields);
        return new Schema(all);
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
