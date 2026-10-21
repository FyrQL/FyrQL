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

package net.fyrql.arrow;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.extension.UuidType;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The types of FyrQL as Arrow types and back.
 * <p>
 * From FyrQL to Arrow: {@code BOOLEAN} is {@code Bool}, {@code TINYINT} to
 * {@code BIGINT} are signed {@code Int} of 8 to 64 bits, {@code HUGEINT} is
 * {@code Decimal128(38, 0)} marked with the metadata
 * {@value #TYPE_METADATA}{@code =HUGEINT} (a value of 39 digits does not
 * fit and fails), {@code FLOAT} and {@code DOUBLE} are {@code FloatingPoint},
 * {@code DECIMAL(p,s)} is {@code Decimal128(p, s)}, {@code VARCHAR} is
 * {@code Utf8}, {@code BLOB} is {@code Binary}, {@code DATE} is
 * {@code Date(DAY)}, {@code TIMESTAMP} is {@code Timestamp(MICROSECOND)}
 * without a time zone, {@code TIME} is {@code Time(MICROSECOND, 64)},
 * {@code TIMESTAMP WITH TIME ZONE} is {@code Timestamp(MICROSECOND, "UTC")},
 * {@code INTERVAL} is {@code Interval(MONTH_DAY_NANO)}, {@code UUID} is
 * the canonical extension type
 * {@code arrow.uuid} over {@code FixedSizeBinary(16)}, {@code LIST} is
 * {@code List} with the element {@code item}, {@code STRUCT} is
 * {@code Struct} and {@code MAP} is {@code Map} with the entries
 * {@code key} and {@code value}.
 * <p>
 * From Arrow to FyrQL, besides these: unsigned {@code Int} as the next
 * larger signed type ({@code UInt64} as {@code HUGEINT}), {@code Null} as
 * {@code INTEGER} (as FyrQL types a bare {@code NULL}), {@code Float16} as
 * {@code FLOAT}, {@code Decimal256} up to precision 38, {@code LargeUtf8},
 * {@code LargeBinary}, {@code Utf8View} and {@code BinaryView}, any other
 * {@code FixedSizeBinary} as {@code BLOB}, {@code Date(MILLISECOND)},
 * {@code Timestamp} in seconds, milliseconds or nanoseconds (nanoseconds
 * are cut to microseconds), with a time zone as
 * {@code TIMESTAMP WITH TIME ZONE}, {@code Time} in every unit as
 * {@code TIME}, {@code Interval} of every unit and {@code Duration} as
 * {@code INTERVAL}, {@code FixedSizeList} as {@code LIST}, and an extension
 * type FyrQL does not know as its storage type. {@code Union},
 * {@code LargeList}, list views, {@code RunEndEncoded} and
 * a dictionary inside a nested type fail with an {@link ArrowTypeException};
 * a dictionary at the top is decoded by {@link ArrowToBatch}.
 */
public final class ArrowTypes {

    /** The key of the field metadata that marks a {@code Decimal128(38, 0)} as a {@code HUGEINT}. */
    public static final String TYPE_METADATA = "fyrql.type";

    /** The name of the element of a list, as pyarrow and Arrow C++ name it. */
    static final String LIST_ELEMENT = "item";

    private static final String HUGEINT = "HUGEINT";

    private static final String EXTENSION_NAME = ArrowType.ExtensionType.EXTENSION_METADATA_KEY_NAME;

    private ArrowTypes() {
    }

    /** Returns the Arrow schema of a schema of FyrQL, with the names and nullability of its columns. */
    public static org.apache.arrow.vector.types.pojo.Schema toArrow(Schema schema) {
        List<Field> fields = new ArrayList<>(schema.size());
        for (Schema.Field f : schema.fields()) {
            fields.add(toArrow(f.name(), f.type(), f.nullable()));
        }
        return new org.apache.arrow.vector.types.pojo.Schema(fields);
    }

    /** Returns the Arrow field of a column of FyrQL. */
    public static Field toArrow(String name, DataType type, boolean nullable) {
        if (type == DataType.HUGEINT) {
            return new Field(name, new FieldType(nullable, new ArrowType.Decimal(DataType.MAX_DECIMAL_PRECISION, 0,
                    128), null, Map.of(TYPE_METADATA, HUGEINT)), null);
        }
        return switch (type) {
            case DataType.ListType l -> new Field(name, new FieldType(nullable, ArrowType.List.INSTANCE, null),
                    List.of(toArrow(LIST_ELEMENT, l.element(), true)));
            case DataType.Struct s -> {
                List<Field> children = new ArrayList<>(s.fields().size());
                for (DataType.Struct.Field f : s.fields()) {
                    children.add(toArrow(f.name(), f.type(), true));
                }
                yield new Field(name, new FieldType(nullable, ArrowType.Struct.INSTANCE, null), children);
            }
            case DataType.MapType m -> {
                Field entries = new Field(MapVector.DATA_VECTOR_NAME,
                        new FieldType(false, ArrowType.Struct.INSTANCE, null),
                        List.of(toArrow(MapVector.KEY_NAME, m.key(), false), toArrow(MapVector.VALUE_NAME, m.value(),
                                true)));
                yield new Field(name, new FieldType(nullable, new ArrowType.Map(false), null), List.of(entries));
            }
            default -> new Field(name, new FieldType(nullable, scalar(type), null), null);
        };
    }

    private static ArrowType scalar(DataType type) {
        if (type instanceof DataType.Decimal d) {
            return new ArrowType.Decimal(d.precision(), d.scale(), 128);
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> ArrowType.Bool.INSTANCE;
            case TINYINT -> new ArrowType.Int(8, true);
            case SMALLINT -> new ArrowType.Int(16, true);
            case INTEGER -> new ArrowType.Int(32, true);
            case BIGINT -> new ArrowType.Int(64, true);
            case FLOAT -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
            case DOUBLE -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case VARCHAR -> ArrowType.Utf8.INSTANCE;
            case BLOB -> ArrowType.Binary.INSTANCE;
            case DATE -> new ArrowType.Date(DateUnit.DAY);
            case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
            case UUID -> UuidType.INSTANCE;
            case TIME -> new ArrowType.Time(TimeUnit.MICROSECOND, 64);
            case TIMESTAMPTZ -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
            case INTERVAL -> new ArrowType.Interval(org.apache.arrow.vector.types.IntervalUnit.MONTH_DAY_NANO);
            case HUGEINT -> throw new AssertionError(type);
        };
    }

    /**
     * Returns the schema of FyrQL of an Arrow schema, with the names and
     * nullability of its fields.
     *
     * @throws ArrowTypeException if a field has a type FyrQL does not read
     */
    public static Schema toFyrQL(org.apache.arrow.vector.types.pojo.Schema schema) {
        List<Schema.Field> fields = new ArrayList<>(schema.getFields().size());
        for (Field f : schema.getFields()) {
            fields.add(new Schema.Field(f.getName(), toFyrQL(f), f.isNullable()));
        }
        return new Schema(fields);
    }

    /**
     * Returns the type of FyrQL of an Arrow field.
     *
     * @throws ArrowTypeException if the field has a type FyrQL does not read,
     *                            or is dictionary-encoded
     */
    public static DataType toFyrQL(Field field) {
        return toFyrQL(field, field.getName());
    }

    static DataType toFyrQL(Field field, String path) {
        if (field.getDictionary() != null) {
            throw new ArrowTypeException(path, "a dictionary-encoded field must be decoded first");
        }
        ArrowType type = field.getType();
        if (type instanceof UuidType) {
            return DataType.UUID;
        }
        return switch (type) {
            case ArrowType.Null n -> DataType.INTEGER;
            case ArrowType.Bool b -> DataType.BOOLEAN;
            case ArrowType.Int i -> integer(i, path);
            case ArrowType.FloatingPoint f -> f.getPrecision() == FloatingPointPrecision.DOUBLE ? DataType.DOUBLE
                    : DataType.FLOAT;
            case ArrowType.Decimal d -> decimal(d, field, path);
            case ArrowType.Utf8 u -> DataType.VARCHAR;
            case ArrowType.LargeUtf8 u -> DataType.VARCHAR;
            case ArrowType.Utf8View u -> DataType.VARCHAR;
            case ArrowType.Binary b -> DataType.BLOB;
            case ArrowType.LargeBinary b -> DataType.BLOB;
            case ArrowType.BinaryView b -> DataType.BLOB;
            case ArrowType.FixedSizeBinary b -> isUuid(field) ? DataType.UUID : DataType.BLOB;
            case ArrowType.Date d -> DataType.DATE;
            case ArrowType.Timestamp t -> t.getTimezone() != null ? DataType.TIMESTAMPTZ : DataType.TIMESTAMP;
            case ArrowType.Time t -> DataType.TIME;
            case ArrowType.Interval i -> DataType.INTERVAL;
            case ArrowType.Duration d -> DataType.INTERVAL;
            case ArrowType.List l -> DataType.listOf(toFyrQL(child(field, 0, path), path + "." + child(field, 0,
                    path).getName()));
            case ArrowType.FixedSizeList l -> DataType.listOf(toFyrQL(child(field, 0, path), path + "."
                    + child(field, 0, path).getName()));
            case ArrowType.Map m -> map(field, path);
            case ArrowType.Struct s -> struct(field, path);
            case ArrowType.ExtensionType e -> toFyrQL(new Field(field.getName(), new FieldType(field.isNullable(),
                    e.storageType(), null, field.getMetadata()), field.getChildren()), path);
            default -> throw new ArrowTypeException(path, "the Arrow type " + type + " has no type in FyrQL");
        };
    }

    private static DataType integer(ArrowType.Int i, String path) {
        return switch (i.getBitWidth()) {
            case 8 -> i.getIsSigned() ? DataType.TINYINT : DataType.SMALLINT;
            case 16 -> i.getIsSigned() ? DataType.SMALLINT : DataType.INTEGER;
            case 32 -> i.getIsSigned() ? DataType.INTEGER : DataType.BIGINT;
            case 64 -> i.getIsSigned() ? DataType.BIGINT : DataType.HUGEINT;
            default -> throw new ArrowTypeException(path, "an integer of " + i.getBitWidth() + " bits");
        };
    }

    private static DataType decimal(ArrowType.Decimal d, Field field, String path) {
        if (d.getBitWidth() != 128 && d.getBitWidth() != 256) {
            throw new ArrowTypeException(path, "a decimal of " + d.getBitWidth() + " bits has no type in FyrQL");
        }
        if (d.getPrecision() < 1 || d.getPrecision() > DataType.MAX_DECIMAL_PRECISION) {
            throw new ArrowTypeException(path, "the precision " + d.getPrecision() + " is more than "
                    + DataType.MAX_DECIMAL_PRECISION);
        }
        if (d.getScale() < 0 || d.getScale() > d.getPrecision()) {
            throw new ArrowTypeException(path, "the scale " + d.getScale() + " is not between 0 and the precision "
                    + d.getPrecision());
        }
        if (isHugeInt(field)) {
            return DataType.HUGEINT;
        }
        return DataType.decimal(d.getPrecision(), d.getScale());
    }

    /** Tells whether a field holds the {@code HUGEINT} of FyrQL. */
    static boolean isHugeInt(Field field) {
        return field.getType() instanceof ArrowType.Decimal d && d.getBitWidth() == 128
                && d.getPrecision() == DataType.MAX_DECIMAL_PRECISION && d.getScale() == 0
                && HUGEINT.equals(metadata(field, TYPE_METADATA));
    }

    /** Tells whether a field is a {@code FixedSizeBinary(16)} that the metadata marks as {@code arrow.uuid}. */
    static boolean isUuid(Field field) {
        return field.getType() instanceof ArrowType.FixedSizeBinary b && b.getByteWidth() == 16
                && UuidType.EXTENSION_NAME.equals(metadata(field, EXTENSION_NAME));
    }

    private static String metadata(Field field, String key) {
        Map<String, String> metadata = field.getMetadata();
        return metadata == null ? null : metadata.get(key);
    }

    private static DataType map(Field field, String path) {
        Field entries = child(field, 0, path);
        String entriesPath = path + "." + entries.getName();
        if (entries.getChildren().size() != 2) {
            throw new ArrowTypeException(entriesPath, "the entries of a map need a key and a value");
        }
        return DataType.mapOf(toFyrQL(entries.getChildren().get(0), entriesPath + "." + entries.getChildren().get(0)
                .getName()), toFyrQL(entries.getChildren().get(1), entriesPath + "." + entries.getChildren().get(1)
                .getName()));
    }

    private static DataType struct(Field field, String path) {
        if (field.getChildren().isEmpty()) {
            throw new ArrowTypeException(path, "a struct without fields has no type in FyrQL");
        }
        List<DataType.Struct.Field> fields = new ArrayList<>(field.getChildren().size());
        for (Field c : field.getChildren()) {
            fields.add(new DataType.Struct.Field(c.getName(), toFyrQL(c, path + "." + c.getName())));
        }
        return new DataType.Struct(fields);
    }

    private static Field child(Field field, int index, String path) {
        if (field.getChildren().size() <= index) {
            throw new ArrowTypeException(path, "the type " + field.getType() + " has no child field");
        }
        return field.getChildren().get(index);
    }
}
