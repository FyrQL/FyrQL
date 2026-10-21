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

package net.fyrql.connector.iceberg;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A schema of an Iceberg table: its columns with their field ids, their
 * Iceberg types and the FyrQL types they are read as.
 * <ul>
 * <li>{@code boolean}, {@code int}, {@code long}, {@code float},
 * {@code double}, {@code decimal(P,S)}, {@code date}, {@code time},
 * {@code string} and {@code uuid} are the FyrQL types of the same name,
 * {@code INTEGER}, {@code BIGINT} and {@code VARCHAR} for {@code int},
 * {@code long} and {@code string};</li>
 * <li>{@code timestamp} and {@code timestamptz} are {@code TIMESTAMP} and
 * {@code TIMESTAMPTZ}, also in nanoseconds ({@code timestamp_ns},
 * {@code timestamptz_ns}), which are read in microseconds;</li>
 * <li>{@code fixed[L]} and {@code binary} are {@code BLOB};</li>
 * <li>{@code struct}, {@code list} and {@code map} are {@code STRUCT},
 * {@code LIST} and {@code MAP}.</li>
 * </ul>
 * {@code unknown}, {@code variant}, {@code geometry} and
 * {@code geography} are not supported.
 *
 * @param id the id of the schema
 * @param columns the columns, in order
 */
record IcebergSchema(int id, List<Column> columns) {

    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern FIXED = Pattern.compile("fixed\\[\\s*(\\d+)\\s*]");

    /**
     * A primitive Iceberg type, as transforms, bounds and partition values
     * need it.
     *
     * @param name the name: {@code boolean}, {@code int}, {@code long},
     *        {@code float}, {@code double}, {@code decimal}, {@code date},
     *        {@code time}, {@code timestamp}, {@code timestamptz},
     *        {@code timestamp_ns}, {@code timestamptz_ns}, {@code string},
     *        {@code uuid}, {@code fixed} or {@code binary}
     * @param precision the precision of a {@code decimal}
     * @param scale the scale of a {@code decimal}
     * @param length the length of a {@code fixed}
     */
    record Primitive(String name, int precision, int scale, int length) {

        static Primitive of(String name) {
            return new Primitive(name, 0, 0, 0);
        }
    }

    /**
     * A column of the table.
     *
     * @param fieldId the field id
     * @param name the name
     * @param type the FyrQL type
     * @param primitive the Iceberg type of a primitive column, or {@code null}
     * @param required whether the column has no {@code NULL}
     * @param initialDefault the JSON of the {@code initial-default}, the
     *        value of the column in files written before it was added, or
     *        {@code null}
     */
    record Column(int fieldId, String name, DataType type, Primitive primitive, boolean required,
            Object initialDefault) {
    }

    IcebergSchema {
        columns = List.copyOf(columns);
    }

    /** Returns the columns as a FyrQL schema; a required column is not nullable. */
    Schema schema() {
        List<Schema.Field> fields = new ArrayList<>();
        for (Column c : columns) {
            fields.add(new Schema.Field(c.name(), c.type(), !c.required()));
        }
        return new Schema(fields);
    }

    /** Returns the column with a field id, or {@code null}. */
    Column column(int fieldId) {
        for (Column c : columns) {
            if (c.fieldId() == fieldId) {
                return c;
            }
        }
        return null;
    }

    /**
     * Parses a schema from its JSON.
     *
     * @throws IllegalArgumentException naming the column if a type is not
     *         supported or the JSON is not a schema
     */
    static IcebergSchema parse(Map<?, ?> json) {
        int id = json.get("schema-id") instanceof Number n ? n.intValue() : 0;
        List<Column> columns = new ArrayList<>();
        for (Object f : list(json.get("fields"), "fields of a schema")) {
            Map<?, ?> field = map(f, "field of a schema");
            String name = string(field.get("name"), "name of a field");
            Object type = field.get("type");
            Primitive primitive = type instanceof String s ? primitive(s, name) : null;
            columns.add(new Column(integer(field.get("id"), "id of the field " + name), name, type(type, name),
                    primitive, Boolean.TRUE.equals(field.get("required")), field.get("initial-default")));
        }
        return new IcebergSchema(id, columns);
    }

    /** Returns the FyrQL type of an Iceberg type, given as JSON. */
    static DataType type(Object type, String column) {
        if (type instanceof String s) {
            return primitiveType(primitive(s, column));
        }
        Map<?, ?> nested = map(type, "type of " + column);
        String kind = string(nested.get("type"), "type of " + column);
        return switch (kind) {
            case "struct" -> {
                List<DataType.Struct.Field> fields = new ArrayList<>();
                for (Object f : list(nested.get("fields"), "fields of " + column)) {
                    Map<?, ?> field = map(f, "field of " + column);
                    String name = string(field.get("name"), "field of " + column);
                    fields.add(new DataType.Struct.Field(name, type(field.get("type"), column + "." + name)));
                }
                yield new DataType.Struct(fields);
            }
            case "list" -> DataType.listOf(type(nested.get("element"), column + ".element"));
            case "map" -> DataType.mapOf(type(nested.get("key"), column + ".key"),
                    type(nested.get("value"), column + ".value"));
            default -> throw new IllegalArgumentException("Unsupported Iceberg type " + kind + " of the column "
                    + column);
        };
    }

    /** Returns a primitive Iceberg type from its name. */
    static Primitive primitive(String type, String column) {
        switch (type) {
            case "boolean", "int", "long", "float", "double", "date", "time", "timestamp", "timestamptz",
                    "timestamp_ns", "timestamptz_ns", "string", "uuid", "binary":
                return Primitive.of(type);
            default:
                break;
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1));
            int scale = Integer.parseInt(decimal.group(2));
            if (precision < 1 || precision > 38 || scale > precision) {
                throw new IllegalArgumentException("Unsupported Iceberg type " + type + " of the column " + column);
            }
            return new Primitive("decimal", precision, scale, 0);
        }
        Matcher fixed = FIXED.matcher(type);
        if (fixed.matches()) {
            return new Primitive("fixed", 0, 0, Integer.parseInt(fixed.group(1)));
        }
        throw new IllegalArgumentException("Unsupported Iceberg type " + type + " of the column " + column);
    }

    /** Returns the FyrQL type of a primitive Iceberg type. */
    static DataType primitiveType(Primitive p) {
        return switch (p.name()) {
            case "boolean" -> DataType.BOOLEAN;
            case "int" -> DataType.INTEGER;
            case "long" -> DataType.BIGINT;
            case "float" -> DataType.FLOAT;
            case "double" -> DataType.DOUBLE;
            case "decimal" -> DataType.decimal(p.precision(), p.scale());
            case "date" -> DataType.DATE;
            case "time" -> DataType.TIME;
            case "timestamp", "timestamp_ns" -> DataType.TIMESTAMP;
            case "timestamptz", "timestamptz_ns" -> DataType.TIMESTAMPTZ;
            case "string" -> DataType.VARCHAR;
            case "uuid" -> DataType.UUID;
            case "fixed", "binary" -> DataType.BLOB;
            default -> throw new IllegalArgumentException("Unsupported Iceberg type " + p.name());
        };
    }

    static List<?> list(Object o, String what) {
        if (o instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException("Invalid " + what + ": " + o);
    }

    static Map<?, ?> map(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException("Invalid " + what + ": " + o);
    }

    static String string(Object o, String what) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Invalid " + what + ": " + o);
    }

    static int integer(Object o, String what) {
        if (o instanceof Long l && l == l.intValue()) {
            return l.intValue();
        }
        throw new IllegalArgumentException("Invalid " + what + ": " + o);
    }
}
