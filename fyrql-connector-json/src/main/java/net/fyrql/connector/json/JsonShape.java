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
        return of(type, null);
    }

    /**
     * The shape of every value of a type, as for a schema that is given,
     * whose strings, also those inside structs, lists and maps, a parser
     * reads.
     *
     * @param parser reads the strings, or {@code null} to cast them
     */
    static JsonShape of(DataType type, JsonStringParser parser) {
        if (type instanceof DataType.Struct s) {
            List<JsonShape> fields = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (DataType.Struct.Field f : s.fields()) {
                keys.add(f.name());
                fields.add(of(f.type(), parser));
            }
            return new Struct(s, keys, fields);
        }
        if (type instanceof DataType.ListType l) {
            return new ListOf(l, of(l.element(), parser));
        }
        if (type instanceof DataType.MapType m) {
            return new MapOf(m, of(m.value(), parser));
        }
        return new Scalar(type, null, parser);
    }

    /**
     * A scalar or decimal value; a string is read in its form, if it has
     * one, otherwise by the parser, if there is one, otherwise cast to the
     * type.
     *
     * @param parser reads the strings of a given layout, or {@code null}
     */
    record Scalar(DataType type, StringKinds.Kind kind, JsonStringParser parser) implements JsonShape {

        Scalar(DataType type, StringKinds.Kind kind) {
            this(type, kind, null);
        }
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
