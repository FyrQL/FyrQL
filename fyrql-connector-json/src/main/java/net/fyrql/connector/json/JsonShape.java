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

package net.fyrql.connector.json;

import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The type of a JSON value in a column and how it is read: the FyrQL type,
 * the form of its strings, which keys of an object are which fields, and
 * whether it is raw JSON text.
 */
sealed interface JsonShape {

    /** Returns the FyrQL type of the values. */
    DataType type();

    /** The shape of every value of a type, as for a schema that is given. */
    static JsonShape of(DataType type) {
        if (type instanceof DataType.Struct s) {
            List<JsonShape> fields = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (DataType.Struct.Field f : s.fields()) {
                keys.add(f.name());
                fields.add(of(f.type()));
            }
            return new Struct(s, keys, fields);
        }
        if (type instanceof DataType.ListType l) {
            return new ListOf(l, of(l.element()));
        }
        if (type instanceof DataType.MapType m) {
            return new MapOf(m, of(m.value()));
        }
        return new Scalar(type, null);
    }

    /**
     * A scalar or decimal value; a string is read in its form, if it has
     * one, otherwise cast to the type.
     */
    record Scalar(DataType type, StringKinds.Kind kind) implements JsonShape {
    }

    /**
     * A value of no single type, read as its JSON text, which DuckDB reads
     * as its type {@code JSON}: {@code "x"} with its quotes, {@code 1},
     * {@code {"a":1}}.
     */
    record Json() implements JsonShape {

        static final Json INSTANCE = new Json();

        @Override
        public DataType type() {
            return DataType.VARCHAR;
        }
    }

    /** An object with known keys, each a field. */
    record Struct(DataType.Struct type, List<String> keys, List<JsonShape> fields, Map<String, Integer> index)
            implements JsonShape {

        Struct(DataType.Struct type, List<String> keys, List<JsonShape> fields) {
            this(type, List.copyOf(keys), List.copyOf(fields), indexOf(keys));
        }

        private static Map<String, Integer> indexOf(List<String> keys) {
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                index.putIfAbsent(keys.get(i), i);
            }
            return Map.copyOf(index);
        }
    }

    /** An array. */
    record ListOf(DataType.ListType type, JsonShape element) implements JsonShape {
    }

    /** An object with any keys, all values of one shape. */
    record MapOf(DataType.MapType type, JsonShape value) implements JsonShape {
    }
}
