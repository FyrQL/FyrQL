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

import net.fyrql.core.type.DataType;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.MapVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.util.List;

/**
 * Assembles the records of the record API of parquet-java straight into the
 * vectors of a batch, one column per field of the message, without objects
 * per row: a group is a {@code STRUCT}, a {@code LIST} in the three-level
 * form and in the older forms the specification allows a {@code LIST}, a
 * {@code MAP} a {@code MAP}, and a repeated field outside a {@code LIST} a
 * {@code LIST} of its type, as {@link ParquetSchemas} reads the types. A
 * field without a value is {@code NULL}, a missing group is {@code NULL} with
 * all its fields, and a repeated field without values is an empty list.
 * Values become those of the FyrQL type as in {@link ParquetLeaf}.
 * <p>
 * The converters stay the same for the whole row group, as the record
 * reader asks for them once; {@link #start} binds them to the builders of
 * the next batch.
 */
final class VectorMaterializer extends RecordMaterializer<Object> {

    /** What {@link #getCurrentRecord} returns: anything but {@code null}, which would skip the record. */
    private static final Object RECORD = Boolean.TRUE;

    private final List<DataType> types;
    private final Slot[] columns;
    private final Vector.Builder[] builders;
    private final GroupConverter root;

    /**
     * Creates the converters of a message.
     *
     * @param message the fields to read, each a column of a batch
     * @param types the FyrQL type of each field, see {@link ParquetSchemas#columnType}
     */
    VectorMaterializer(MessageType message, List<DataType> types) {
        this.types = List.copyOf(types);
        this.columns = new Slot[message.getFieldCount()];
        this.builders = new Vector.Builder[columns.length];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = slot(message.getType(i), types.get(i));
        }
        this.root = new GroupConverter() {
            @Override
            public Converter getConverter(int fieldIndex) {
                return columns[fieldIndex].converter();
            }

            @Override
            public void start() {
                for (Slot s : columns) {
                    s.begin();
                }
            }

            @Override
            public void end() {
                for (Slot s : columns) {
                    s.complete();
                }
            }
        };
    }

    /** Binds the converters to new builders for a batch of at most {@code rows} rows. */
    void start(int rows) {
        for (int i = 0; i < columns.length; i++) {
            builders[i] = Vectors.builder(types.get(i), rows);
            columns[i].bind(builders[i]);
        }
    }

    /** Returns the columns of the records read since {@link #start}. */
    Vector[] finish() {
        Vector[] built = new Vector[builders.length];
        for (int i = 0; i < built.length; i++) {
            built[i] = builders[i].build();
            builders[i] = null;
        }
        return built;
    }

    @Override
    public Object getCurrentRecord() {
        return RECORD;
    }

    @Override
    public GroupConverter getRootConverter() {
        return root;
    }

    /** Returns the slot of a field of a group: a list of its values if it is repeated. */
    private static Slot slot(Type t, DataType type) {
        if (t.isRepetition(Type.Repetition.REPEATED)) {
            return new Slot(value(t, ((DataType.ListType) type).element()), true);
        }
        return new Slot(value(t, type), false);
    }

    /** Returns the converter of one value of a field, regardless of its repetition. */
    private static Converter value(Type t, DataType type) {
        if (t.isPrimitive()) {
            return new Leaf(ParquetLeaf.of(t.asPrimitiveType(), type));
        }
        GroupType g = t.asGroupType();
        LogicalTypeAnnotation annotation = g.getLogicalTypeAnnotation();
        if (annotation instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
            return new ListGroup(g, (DataType.ListType) type);
        }
        if (annotation instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation
                || annotation instanceof LogicalTypeAnnotation.MapKeyValueTypeAnnotation) {
            return new MapGroup(g, (DataType.MapType) type);
        }
        return new StructGroup(g, (DataType.Struct) type);
    }

    /** Binds a converter of a value to the builder its values go to. */
    private static void bind(Converter c, Vector.Builder b) {
        switch (c) {
            case Leaf l -> l.builder = b;
            case StructGroup s -> s.bind((StructVector.Builder) b);
            case ListGroup l -> l.bind((ListVector.Builder) b);
            case MapGroup m -> m.bind((MapVector.Builder) b);
            default -> throw new IllegalStateException(c.toString());
        }
    }

    /**
     * A field of a group and the builder of its values: a field without a
     * value is {@code NULL} when the group ends, and a repeated field ends
     * its list.
     */
    private static final class Slot {
        private final Converter converter;
        private final boolean repeated;
        private Vector.Builder target;
        private int mark;

        Slot(Converter converter, boolean repeated) {
            this.converter = converter;
            this.repeated = repeated;
        }

        Converter converter() {
            return converter;
        }

        void bind(Vector.Builder b) {
            target = b;
            VectorMaterializer.bind(converter, repeated ? ((ListVector.Builder) b).elements() : b);
        }

        /** Notes where the value of the group that starts goes. */
        void begin() {
            mark = target.size();
        }

        /**
         * Completes the value of the group that ends.
         *
         * @return whether the field had a value; a repeated one always has
         */
        boolean complete() {
            if (repeated) {
                ((ListVector.Builder) target).endList();
                return true;
            }
            if (target.size() == mark) {
                target.appendNull();
                return false;
            }
            return true;
        }
    }

    /** A primitive value. */
    private static final class Leaf extends PrimitiveConverter {
        private final ParquetLeaf leaf;
        private Vector.Builder builder;

        Leaf(ParquetLeaf leaf) {
            this.leaf = leaf;
        }

        @Override
        public void addBoolean(boolean value) {
            leaf.addBoolean(builder, value);
        }

        @Override
        public void addInt(int value) {
            leaf.addInt(builder, value);
        }

        @Override
        public void addLong(long value) {
            leaf.addLong(builder, value);
        }

        @Override
        public void addFloat(float value) {
            leaf.addFloat(builder, value);
        }

        @Override
        public void addDouble(double value) {
            leaf.addDouble(builder, value);
        }

        @Override
        public void addBinary(Binary value) {
            leaf.addBinary(builder, value);
        }
    }

    /** A group as a {@code STRUCT}: a row per group, its fields from the slots. */
    private static final class StructGroup extends GroupConverter {
        private final Slot[] fields;
        private StructVector.Builder builder;

        StructGroup(GroupType g, DataType.Struct type) {
            fields = new Slot[g.getFieldCount()];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = slot(g.getType(i), type.fields().get(i).type());
            }
        }

        void bind(StructVector.Builder b) {
            builder = b;
            for (int i = 0; i < fields.length; i++) {
                fields[i].bind(b.field(i));
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return fields[fieldIndex].converter();
        }

        @Override
        public void start() {
            for (Slot f : fields) {
                f.begin();
            }
        }

        @Override
        public void end() {
            for (Slot f : fields) {
                f.complete();
            }
            builder.endRow();
        }
    }

    /**
     * A {@code LIST}: its repeated field is the element in the older forms,
     * see {@link ParquetSchemas#isElement}, otherwise a group around the one
     * field that is the element.
     */
    private static final class ListGroup extends GroupConverter {
        private final Converter repeated;
        private final Slot element;
        private ListVector.Builder builder;

        ListGroup(GroupType g, DataType.ListType type) {
            Type r = g.getType(0);
            if (ParquetSchemas.isElement(r, g.getName())) {
                repeated = value(r, type.element());
                element = null;
            } else {
                element = slot(r.asGroupType().getType(0), type.element());
                repeated = new GroupConverter() {
                    @Override
                    public Converter getConverter(int fieldIndex) {
                        return element.converter();
                    }

                    @Override
                    public void start() {
                        element.begin();
                    }

                    @Override
                    public void end() {
                        element.complete();
                    }
                };
            }
        }

        void bind(ListVector.Builder b) {
            builder = b;
            if (element == null) {
                VectorMaterializer.bind(repeated, b.elements());
            } else {
                element.bind(b.elements());
            }
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return repeated;
        }

        @Override
        public void start() {
        }

        @Override
        public void end() {
            builder.endList();
        }
    }

    /** A {@code MAP}: a repeated group of the key and the value; a key must not be {@code NULL}. */
    private static final class MapGroup extends GroupConverter {
        private final Slot key;
        private final Slot value;
        private final GroupConverter entry;
        private MapVector.Builder builder;

        MapGroup(GroupType g, DataType.MapType type) {
            GroupType e = g.getType(0).asGroupType();
            key = slot(e.getType(0), type.key());
            value = slot(e.getType(1), type.value());
            entry = new GroupConverter() {
                @Override
                public Converter getConverter(int fieldIndex) {
                    return fieldIndex == 0 ? key.converter() : value.converter();
                }

                @Override
                public void start() {
                    key.begin();
                    value.begin();
                }

                @Override
                public void end() {
                    if (!key.complete()) {
                        throw new IllegalArgumentException("A MAP key must not be NULL");
                    }
                    value.complete();
                }
            };
        }

        void bind(MapVector.Builder b) {
            builder = b;
            key.bind(b.keys());
            value.bind(b.values());
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            return entry;
        }

        @Override
        public void start() {
        }

        @Override
        public void end() {
            builder.endMap();
        }
    }
}
