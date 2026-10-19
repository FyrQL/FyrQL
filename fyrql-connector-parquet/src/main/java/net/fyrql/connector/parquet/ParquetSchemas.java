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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The types of Parquet as types of FyrQL, as DuckDB reads them, except
 * where FyrQL lacks the type:
 * <ul>
 * <li>{@code BOOLEAN}, {@code INT32}, {@code INT64}, {@code FLOAT} and
 * {@code DOUBLE} are {@code BOOLEAN}, {@code INTEGER}, {@code BIGINT},
 * {@code FLOAT} and {@code DOUBLE}; {@code INT(8)} and {@code INT(16)} with a
 * sign are {@code TINYINT} and {@code SMALLINT}; an unsigned integer is the
 * next wider type with a sign, up to {@code HUGEINT} for
 * {@code INT(64,false)};</li>
 * <li>{@code DATE} is {@code DATE}; {@code TIMESTAMP} in any unit, adjusted
 * to UTC or not, and {@code INT96} are {@code TIMESTAMP}; {@code TIME} is
 * {@code VARCHAR};</li>
 * <li>{@code STRING}, {@code ENUM} and {@code JSON} are {@code VARCHAR};
 * other byte arrays, {@code BSON} among them, are {@code BLOB};
 * {@code UUID} is {@code UUID}; {@code FLOAT16} is {@code FLOAT};
 * {@code DECIMAL(p,s)} on any physical type is {@code DECIMAL(p,s)} up to a
 * precision of 38;</li>
 * <li>a group is a {@code STRUCT}, a {@code LIST} in the three-level form
 * and in the older forms the specification allows a {@code LIST}, a
 * {@code MAP} a {@code MAP}, and a repeated field outside a {@code LIST} a
 * {@code LIST} of its type.</li>
 * </ul>
 * Any other annotation, such as {@code INTERVAL} or {@code VARIANT}, falls
 * back to the physical type. A {@code required} column is not nullable.
 */
final class ParquetSchemas {

    private ParquetSchemas() {
    }

    /**
     * Returns the columns of a file.
     *
     * @throws IllegalArgumentException naming the column whose type FyrQL
     *         cannot read, such as a {@code DECIMAL} with more than 38 digits
     */
    static Schema schema(MessageType message) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Type t : message.getFields()) {
            fields.add(new Schema.Field(t.getName(), fieldType(t, t.getName()), t.isRepetition(Type.Repetition.OPTIONAL)));
        }
        return new Schema(fields);
    }

    /** Returns the type of a column of a file, or of the part of it a scan reads. */
    static DataType columnType(Type column) {
        return fieldType(column, column.getName());
    }

    /** Returns the type of a field: a list of its type if it is repeated. */
    private static DataType fieldType(Type t, String path) {
        DataType type = type(t, path);
        return t.isRepetition(Type.Repetition.REPEATED) ? DataType.listOf(type) : type;
    }

    /** Returns the type of a field, regardless of its repetition. */
    private static DataType type(Type t, String path) {
        return t.isPrimitive() ? primitive(t.asPrimitiveType(), path) : group(t.asGroupType(), path);
    }

    private static DataType group(GroupType g, String path) {
        LogicalTypeAnnotation annotation = g.getLogicalTypeAnnotation();
        if (annotation instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
            return list(g, path);
        }
        if (annotation instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation
                || annotation instanceof LogicalTypeAnnotation.MapKeyValueTypeAnnotation) {
            return map(g, path);
        }
        return struct(g, path);
    }

    private static DataType struct(GroupType g, String path) {
        if (g.getFieldCount() == 0) {
            throw new IllegalArgumentException("The column " + path + " is a group without fields");
        }
        List<DataType.Struct.Field> fields = new ArrayList<>();
        for (Type t : g.getFields()) {
            fields.add(new DataType.Struct.Field(t.getName(), fieldType(t, path + "." + t.getName())));
        }
        return new DataType.Struct(fields);
    }

    /**
     * Returns the type of a {@code LIST} under the rules of the
     * specification for the older forms: a repeated primitive is the
     * element; so is a repeated group with more than one field, or one named
     * {@code array} or after the list with {@code _tuple}; otherwise the one
     * field of the repeated group is the element.
     */
    private static DataType list(GroupType g, String path) {
        if (g.getFieldCount() != 1 || !g.getType(0).isRepetition(Type.Repetition.REPEATED)) {
            throw new IllegalArgumentException("The column " + path + " is a LIST without one repeated field");
        }
        Type repeated = g.getType(0);
        String inner = path + "." + repeated.getName();
        if (isElement(repeated, g.getName())) {
            return DataType.listOf(type(repeated, inner));
        }
        Type element = repeated.asGroupType().getType(0);
        return DataType.listOf(fieldType(element, inner + "." + element.getName()));
    }

    /** Tells whether the repeated field of a {@code LIST} is its element, as in the older forms. */
    static boolean isElement(Type repeated, String listName) {
        if (repeated.isPrimitive()) {
            return true;
        }
        GroupType g = repeated.asGroupType();
        return g.getFieldCount() != 1 || g.getName().equals("array") || g.getName().equals(listName + "_tuple");
    }

    /** Returns the type of a {@code MAP}: a repeated group of the key and the value. */
    private static DataType map(GroupType g, String path) {
        if (g.getFieldCount() != 1 || g.getType(0).isPrimitive()
                || !g.getType(0).isRepetition(Type.Repetition.REPEATED)
                || g.getType(0).asGroupType().getFieldCount() != 2) {
            throw new IllegalArgumentException("The column " + path
                    + " is a MAP without one repeated group of a key and a value");
        }
        GroupType entry = g.getType(0).asGroupType();
        String inner = path + "." + entry.getName();
        Type key = entry.getType(0);
        Type value = entry.getType(1);
        return DataType.mapOf(fieldType(key, inner + "." + key.getName()),
                fieldType(value, inner + "." + value.getName()));
    }

    private static DataType primitive(PrimitiveType p, String path) {
        LogicalTypeAnnotation annotation = p.getLogicalTypeAnnotation();
        if (annotation instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation d) {
            return decimal(d, path);
        }
        return switch (p.getPrimitiveTypeName()) {
            case BOOLEAN -> DataType.BOOLEAN;
            case INT32 -> switch (annotation) {
                case LogicalTypeAnnotation.IntLogicalTypeAnnotation i -> integer(i, DataType.INTEGER);
                case LogicalTypeAnnotation.DateLogicalTypeAnnotation d -> DataType.DATE;
                case LogicalTypeAnnotation.TimeLogicalTypeAnnotation t -> DataType.VARCHAR;
                case null, default -> DataType.INTEGER;
            };
            case INT64 -> switch (annotation) {
                case LogicalTypeAnnotation.IntLogicalTypeAnnotation i -> integer(i, DataType.BIGINT);
                case LogicalTypeAnnotation.TimestampLogicalTypeAnnotation t -> DataType.TIMESTAMP;
                case LogicalTypeAnnotation.TimeLogicalTypeAnnotation t -> DataType.VARCHAR;
                case null, default -> DataType.BIGINT;
            };
            case INT96 -> DataType.TIMESTAMP;
            case FLOAT -> DataType.FLOAT;
            case DOUBLE -> DataType.DOUBLE;
            case BINARY -> switch (annotation) {
                case LogicalTypeAnnotation.StringLogicalTypeAnnotation s -> DataType.VARCHAR;
                case LogicalTypeAnnotation.EnumLogicalTypeAnnotation e -> DataType.VARCHAR;
                case LogicalTypeAnnotation.JsonLogicalTypeAnnotation j -> DataType.VARCHAR;
                case null, default -> DataType.BLOB;
            };
            case FIXED_LEN_BYTE_ARRAY -> switch (annotation) {
                case LogicalTypeAnnotation.UUIDLogicalTypeAnnotation u when p.getTypeLength() == 16 -> DataType.UUID;
                case LogicalTypeAnnotation.Float16LogicalTypeAnnotation f when p.getTypeLength() == 2 ->
                        DataType.FLOAT;
                case null, default -> DataType.BLOB;
            };
        };
    }

    /**
     * Returns the type of an integer annotation on a physical type whose
     * own type is {@code physical}: with a sign the type of its width, but
     * never wider than the physical type; without a sign the next wider
     * type.
     */
    private static DataType integer(LogicalTypeAnnotation.IntLogicalTypeAnnotation i, DataType physical) {
        boolean wide = physical == DataType.BIGINT;
        if (i.isSigned()) {
            return switch (i.getBitWidth()) {
                case 8 -> DataType.TINYINT;
                case 16 -> DataType.SMALLINT;
                case 32 -> DataType.INTEGER;
                default -> physical;
            };
        }
        return switch (i.getBitWidth()) {
            case 8 -> DataType.SMALLINT;
            case 16 -> DataType.INTEGER;
            case 32 -> DataType.BIGINT;
            default -> wide ? DataType.HUGEINT : physical;
        };
    }

    private static DataType decimal(LogicalTypeAnnotation.DecimalLogicalTypeAnnotation d, String path) {
        if (d.getPrecision() > DataType.MAX_DECIMAL_PRECISION) {
            throw new IllegalArgumentException("The column " + path + " has the type DECIMAL(" + d.getPrecision()
                    + "," + d.getScale() + "), wider than the largest DECIMAL of " + DataType.MAX_DECIMAL_PRECISION
                    + " digits");
        }
        try {
            return DataType.decimal(d.getPrecision(), d.getScale());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The column " + path + " has an invalid type: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the part of the schema of a file that a scan reads: the
     * columns of the paths in the order of the file, a whole column for a
     * path without fields, and of a {@code STRUCT} column only the fields
     * that the paths name.
     *
     * @throws IllegalArgumentException if a column or a field does not exist
     */
    static MessageType requested(MessageType file, List<ColumnPath> paths) {
        Map<Integer, List<List<String>>> byColumn = new TreeMap<>();
        for (ColumnPath p : paths) {
            if (p.column() >= file.getFieldCount()) {
                throw new IllegalArgumentException("No column " + p + " in " + file.getName());
            }
            byColumn.computeIfAbsent(p.column(), c -> new ArrayList<>()).add(p.fields());
        }
        List<Type> fields = new ArrayList<>();
        for (Map.Entry<Integer, List<List<String>>> e : byColumn.entrySet()) {
            fields.add(keep(file.getType(e.getKey()), e.getValue()));
        }
        return new MessageType(file.getName(), fields);
    }

    /** Returns the primitive field a path ends at if it is outside of lists, or {@code null}. */
    static PrimitiveType leaf(MessageType file, ColumnPath p) {
        Type t = file.getType(p.column());
        for (String f : p.fields()) {
            if (t.isPrimitive() || t.isRepetition(Type.Repetition.REPEATED)) {
                return null;
            }
            t = t.asGroupType().getType(f);
        }
        return t.isPrimitive() && !t.isRepetition(Type.Repetition.REPEATED) ? t.asPrimitiveType() : null;
    }

    /** Returns the names along a path in a file. */
    static String[] path(MessageType file, ColumnPath p) {
        List<String> names = new ArrayList<>();
        names.add(file.getType(p.column()).getName());
        names.addAll(p.fields());
        return names.toArray(String[]::new);
    }

    /** Returns a type with only the fields on the paths, all of it if a path ends at it. */
    private static Type keep(Type t, List<List<String>> paths) {
        for (List<String> p : paths) {
            if (p.isEmpty()) {
                return t;
            }
        }
        if (t.isPrimitive() || t.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation
                || t.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation
                || t.isRepetition(Type.Repetition.REPEATED)) {
            throw new IllegalArgumentException("The column " + t.getName() + " is not a STRUCT: " + paths.get(0));
        }
        GroupType g = t.asGroupType();
        for (List<String> p : paths) {
            if (!g.containsField(p.get(0))) {
                throw new IllegalArgumentException("No field " + p.get(0) + " in " + g.getName());
            }
        }
        List<Type> kept = new ArrayList<>();
        for (Type child : g.getFields()) {
            List<List<String>> tails = new ArrayList<>();
            for (List<String> p : paths) {
                if (p.get(0).equals(child.getName())) {
                    tails.add(p.subList(1, p.size()));
                }
            }
            if (!tails.isEmpty()) {
                kept.add(keep(child, tails));
            }
        }
        return g.withNewFields(kept);
    }
}
