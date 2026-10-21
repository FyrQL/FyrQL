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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A partition spec of an Iceberg table: the fields of the partition
 * values of its files, each a transform of a column.
 *
 * @param id the id of the spec
 * @param fields the partition fields, in the order of the partition values
 */
record PartitionSpec(int id, List<Field> fields) {

    private static final Pattern BUCKET = Pattern.compile("bucket\\[\\s*(\\d+)\\s*]");
    private static final Pattern TRUNCATE = Pattern.compile("truncate\\[\\s*(\\d+)\\s*]");

    /** The first id of the partition fields of a spec of format version 1, which has none. */
    private static final int FIRST_FIELD_ID = 1000;

    /** The spec without fields. */
    static final PartitionSpec UNPARTITIONED = new PartitionSpec(0, List.of());

    /**
     * A partition field.
     *
     * @param sourceId the field id of the column it transforms
     * @param fieldId the field id of the partition field
     * @param name the name, which the partition values in manifests use
     * @param transform the transform
     */
    record Field(int sourceId, int fieldId, String name, Transform transform) {
    }

    /** A transform of a value of a column into a partition value. */
    sealed interface Transform {
    }

    /** The value itself. */
    record Identity() implements Transform {
    }

    /** A hash of the value modulo a number of buckets. */
    record Bucket(int buckets) implements Transform {
    }

    /** The value truncated to a width. */
    record Truncate(int width) implements Transform {
    }

    /** Years, months, days or hours since 1970. */
    record Time(String unit) implements Transform {
    }

    /** Always {@code NULL}. */
    record VoidTransform() implements Transform {
    }

    PartitionSpec {
        fields = List.copyOf(fields);
    }

    /** Tells whether the spec has no fields other than {@code void} ones. */
    boolean unpartitioned() {
        for (Field f : fields) {
            if (!(f.transform() instanceof VoidTransform)) {
                return false;
            }
        }
        return true;
    }

    /** Parses a spec from its JSON in {@code partition-specs}. */
    static PartitionSpec parse(Map<?, ?> json) {
        int id = IcebergSchema.integer(json.get("spec-id"), "spec-id");
        return new PartitionSpec(id, fields(IcebergSchema.list(json.get("fields"), "fields of a partition spec")));
    }

    /** Parses the fields of a spec, for {@code partition-spec} of format version 1 without field ids. */
    static PartitionSpec parse(int id, List<?> fields) {
        return new PartitionSpec(id, fields(fields));
    }

    private static List<Field> fields(List<?> json) {
        List<Field> fields = new ArrayList<>();
        for (Object o : json) {
            Map<?, ?> f = IcebergSchema.map(o, "partition field");
            String name = IcebergSchema.string(f.get("name"), "name of a partition field");
            int fieldId = f.containsKey("field-id") ? IcebergSchema.integer(f.get("field-id"), "field-id of " + name)
                    : FIRST_FIELD_ID + fields.size();
            fields.add(new Field(IcebergSchema.integer(f.get("source-id"), "source-id of " + name), fieldId, name,
                    transform(IcebergSchema.string(f.get("transform"), "transform of " + name))));
        }
        return fields;
    }

    /**
     * Parses a transform.
     *
     * @throws IllegalArgumentException if it is not one of those of the
     *         specification
     */
    static Transform transform(String text) {
        switch (text) {
            case "identity":
                return new Identity();
            case "year", "month", "day", "hour":
                return new Time(text);
            case "void":
                return new VoidTransform();
            default:
                break;
        }
        Matcher bucket = BUCKET.matcher(text);
        if (bucket.matches()) {
            return new Bucket(Integer.parseInt(bucket.group(1)));
        }
        Matcher truncate = TRUNCATE.matcher(text);
        if (truncate.matches()) {
            return new Truncate(Integer.parseInt(truncate.group(1)));
        }
        throw new IllegalArgumentException("Unsupported partition transform " + text);
    }

    /** Returns the Iceberg type of the values of a transform of a column of a type. */
    static IcebergSchema.Primitive resultType(Transform transform, IcebergSchema.Primitive source) {
        return switch (transform) {
            case Identity i -> source;
            case Truncate t -> source;
            case VoidTransform v -> source;
            case Bucket b -> IcebergSchema.Primitive.of("int");
            case Time t -> IcebergSchema.Primitive.of(t.unit().equals("day") ? "date" : "int");
        };
    }
}
