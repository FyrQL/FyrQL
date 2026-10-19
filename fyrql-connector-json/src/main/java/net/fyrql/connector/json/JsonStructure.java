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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import net.fyrql.core.type.DataType;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the values at one place of the sample were: which kinds of JSON
 * values occurred, the forms of the strings, the keys of the objects with
 * how often each appeared, and the elements of the arrays. The shape of the
 * place follows the rules of DuckDB 1.5.6, measured against it:
 * <ul>
 * <li>only {@code null}, or nothing: raw JSON text;</li>
 * <li>booleans: {@code BOOLEAN};</li>
 * <li>integers: {@code BIGINT}, or {@code HUGEINT} if one lies from 2^63 to
 * 2^64 - 1; numbers with a fraction or exponent, or integers beyond these:
 * {@code DOUBLE};</li>
 * <li>strings: the first form of {@link StringKinds} that all of them have,
 * otherwise {@code VARCHAR};</li>
 * <li>arrays: a {@code LIST} of the shape of all their elements;</li>
 * <li>objects: a {@code STRUCT} of the keys in the order they first
 * appeared, each with the shape of its values; a {@code MAP} from
 * {@code VARCHAR} to the shape of all values instead if there are no keys,
 * if there are at least {@code map_inference_threshold} keys and their
 * values have a shape other than raw JSON text, or if a key appears on
 * average in fewer than {@code field_appearance_threshold} of the
 * objects;</li>
 * <li>two or more of these kinds: raw JSON text.</li>
 * </ul>
 */
final class JsonStructure {

    private static final BigInteger UNSIGNED_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private boolean booleans;
    private boolean integers;
    private boolean hugeIntegers;
    private boolean doubles;
    private boolean strings;
    /** The forms all strings had, while {@link #strings}. */
    private int kinds;
    private long objects;
    private Map<String, JsonStructure> fields;
    /** How many objects had the key of this place, if it is a field. */
    private long appearances;
    private boolean arrays;
    private JsonStructure element;

    /** Adds the value at the current token and moves to its last token. */
    void add(JsonParser p, StringKinds stringKinds) throws IOException {
        JsonToken t = p.currentToken();
        switch (t) {
            case VALUE_NULL -> {
                // Counts for no kind.
            }
            case VALUE_TRUE, VALUE_FALSE -> booleans = true;
            case VALUE_NUMBER_INT -> {
                if (p.getNumberType() != JsonParser.NumberType.BIG_INTEGER) {
                    integers = true;
                } else {
                    BigInteger v = p.getBigIntegerValue();
                    if (v.signum() > 0 && v.compareTo(UNSIGNED_MAX) <= 0) {
                        hugeIntegers = true;
                    } else {
                        doubles = true;
                    }
                }
            }
            case VALUE_NUMBER_FLOAT -> doubles = true;
            case VALUE_STRING -> {
                int alive = strings ? kinds : stringKinds.all();
                kinds = stringKinds.matching(p.getText(), alive);
                strings = true;
            }
            case START_OBJECT -> {
                objects++;
                if (fields == null) {
                    fields = new LinkedHashMap<>();
                }
                String name;
                while ((name = p.nextFieldName()) != null) {
                    JsonStructure field = fields.computeIfAbsent(name, k -> new JsonStructure());
                    field.appearances++;
                    p.nextToken();
                    field.add(p, stringKinds);
                }
            }
            case START_ARRAY -> {
                arrays = true;
                if (element == null) {
                    element = new JsonStructure();
                }
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    element.add(p, stringKinds);
                }
            }
            default -> throw new IllegalStateException("Not the start of a value: " + t);
        }
    }

    /** Adds what another place saw, as DuckDB merges the values of a {@code MAP}. */
    void merge(JsonStructure other) {
        booleans |= other.booleans;
        integers |= other.integers;
        hugeIntegers |= other.hugeIntegers;
        doubles |= other.doubles;
        if (other.strings) {
            kinds = strings ? kinds & other.kinds : other.kinds;
            strings = true;
        }
        objects += other.objects;
        if (other.fields != null) {
            if (fields == null) {
                fields = new LinkedHashMap<>();
            }
            for (Map.Entry<String, JsonStructure> e : other.fields.entrySet()) {
                JsonStructure field = fields.computeIfAbsent(e.getKey(), k -> new JsonStructure());
                field.appearances += e.getValue().appearances;
                field.merge(e.getValue());
            }
        }
        arrays |= other.arrays;
        if (other.element != null) {
            if (element == null) {
                element = new JsonStructure();
            }
            element.merge(other.element);
        }
    }

    /**
     * Returns the shape of the values.
     *
     * @param path where the values are, for messages
     * @throws IllegalArgumentException if a {@code STRUCT} would have two
     *         fields whose names differ only in case, which DuckDB rejects
     */
    JsonShape shape(JsonOptions options, StringKinds stringKinds, String path) {
        return shape(options, stringKinds, path, false);
    }

    /**
     * Returns the shape of the records, whose fields are the columns: as
     * DuckDB names them, an empty key gives the column {@code C} and its
     * index, and a name that differs from an earlier one only in case gets
     * {@code _1}, {@code _2} and so on.
     */
    JsonShape recordShape(JsonOptions options, StringKinds stringKinds) {
        return shape(options, stringKinds, "", true);
    }

    private JsonShape shape(JsonOptions options, StringKinds stringKinds, String path, boolean columns) {
        boolean numbers = integers || hugeIntegers || doubles;
        int seen = (booleans ? 1 : 0) + (numbers ? 1 : 0) + (strings ? 1 : 0) + (objects > 0 ? 1 : 0)
                + (arrays ? 1 : 0);
        if (seen != 1) {
            return JsonShape.Json.INSTANCE;
        }
        if (booleans) {
            return new JsonShape.Scalar(DataType.BOOLEAN, null);
        }
        if (numbers) {
            return new JsonShape.Scalar(doubles ? DataType.DOUBLE : hugeIntegers ? DataType.HUGEINT
                    : DataType.BIGINT, null);
        }
        if (strings) {
            if (kinds == 0) {
                return new JsonShape.Scalar(DataType.VARCHAR, null);
            }
            StringKinds.Kind kind = stringKinds.first(kinds);
            return new JsonShape.Scalar(kind.type(), kind);
        }
        if (arrays) {
            JsonShape e = element.shape(options, stringKinds, path + "[]");
            return new JsonShape.ListOf(DataType.listOf(e.type()), e);
        }
        if (fields.isEmpty()) {
            return map(JsonShape.Json.INSTANCE);
        }
        if (options.mapInferenceThreshold() >= 0 && fields.size() >= options.mapInferenceThreshold()) {
            JsonShape value = merged().shape(options, stringKinds, path + "[]");
            if (!(value instanceof JsonShape.Json)) {
                return map(value);
            }
        }
        if (averageAppearance() < options.fieldAppearanceThreshold()) {
            return map(merged().shape(options, stringKinds, path + "[]"));
        }
        List<String> keys = new ArrayList<>();
        List<JsonShape> shapes = new ArrayList<>();
        List<DataType.Struct.Field> structFields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, JsonStructure> e : fields.entrySet()) {
            String key = e.getKey();
            String name = key;
            if (columns) {
                String base = key.isEmpty() ? "C" + keys.size() : key;
                name = base;
                for (int k = 1; !names.add(name.toLowerCase(Locale.ROOT)); k++) {
                    name = base + "_" + k;
                }
            } else if (!names.add(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate name \"" + key + "\" in a STRUCT inferred from JSON"
                        + (path.isEmpty() ? "" : " at " + path) + "; an explicit schema reads it");
            }
            JsonShape s = e.getValue().shape(options, stringKinds, path.isEmpty() ? key : path + "." + key);
            keys.add(key);
            shapes.add(s);
            structFields.add(new DataType.Struct.Field(name, s.type()));
        }
        return new JsonShape.Struct(new DataType.Struct(structFields), keys, shapes);
    }

    private static JsonShape map(JsonShape value) {
        return new JsonShape.MapOf(DataType.mapOf(DataType.VARCHAR, value.type()), value);
    }

    /** Returns all values of all keys as one place. */
    private JsonStructure merged() {
        JsonStructure all = new JsonStructure();
        for (JsonStructure field : fields.values()) {
            all.merge(field);
        }
        return all;
    }

    /** Returns the share of the objects a key appears in, on average over the keys, summed as DuckDB sums it. */
    private double averageAppearance() {
        double total = 0;
        for (JsonStructure field : fields.values()) {
            total += (double) field.appearances / (double) objects;
        }
        return total / fields.size();
    }
}
