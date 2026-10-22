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

package net.fyrql.connector.json;

import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The columns of a JSON input and how each value is read: either every
 * record is an object whose keys are the columns, or every record is one
 * value of the single column {@code json}. A layout comes from
 * {@link JsonSchemaInference#infer} or, for a schema that is given, from
 * {@link #of}.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class JsonLayout {

    private final Schema schema;
    private final boolean records;
    private final List<String> keys;
    private final Map<String, Integer> index;
    private final List<JsonShape> shapes;
    private final boolean inferred;
    private final long sampled;

    /**
     * @param records whether the records are objects whose keys are the columns
     * @param keys the key of each column, if they are
     * @param inferred whether the layout was inferred from the input rather than given
     * @param sampled the number of records it was inferred from, or -1 if from all of them
     */
    JsonLayout(Schema schema, boolean records, List<String> keys, List<JsonShape> shapes, boolean inferred,
            long sampled) {
        this.schema = schema;
        this.records = records;
        this.keys = List.copyOf(keys);
        this.shapes = List.copyOf(shapes);
        this.inferred = inferred;
        this.sampled = sampled;
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            index.putIfAbsent(keys.get(i), i);
        }
        this.index = Map.copyOf(index);
    }

    /**
     * Returns the layout of objects with a given schema: the key of a column
     * is its name, a key that is not a column is skipped, and a value is read
     * as its type, a string cast to it.
     */
    public static JsonLayout of(Schema schema) {
        return of(schema, null);
    }

    /**
     * Returns the layout of objects with a given schema, as {@link #of(Schema)}
     * does, except that a parser reads the strings, also those inside
     * structs, lists and maps, in place of the cast to their type.
     *
     * @param parser reads the strings, or {@code null} to cast them
     */
    public static JsonLayout of(Schema schema, JsonStringParser parser) {
        List<String> keys = new ArrayList<>();
        List<JsonShape> shapes = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            keys.add(f.name());
            shapes.add(JsonShape.of(f.type(), parser));
        }
        return new JsonLayout(schema, true, keys, shapes, false, -1);
    }

    /** Returns the columns. */
    public Schema schema() {
        return schema;
    }

    /** Tells whether the records are objects whose keys are the columns, rather than values of one column. */
    public boolean records() {
        return records;
    }

    /** Returns the key of each column; empty if the records are values of one column. */
    public List<String> keys() {
        return keys;
    }

    /** Returns the column of a key, or -1 if it is none. */
    int column(String key) {
        Integer c = index.get(key);
        return c == null ? -1 : c;
    }

    /** Returns how the values of a column are read. */
    JsonShape shape(int column) {
        return shapes.get(column);
    }

    /** Tells whether the layout was inferred from the input, so that a key that is not a column is an error. */
    boolean inferred() {
        return inferred;
    }

    /** Returns the number of records the layout was inferred from, or -1 if from all of them or given. */
    long sampled() {
        return sampled;
    }

    @Override
    public String toString() {
        return (records ? "records " : "values ") + schema;
    }
}
