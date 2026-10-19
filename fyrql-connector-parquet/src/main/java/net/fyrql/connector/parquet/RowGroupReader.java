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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.FloatVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.ColumnReader;
import org.apache.parquet.column.impl.ColumnReadStoreImpl;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Reads the rows of one row group in batches. A column whose paths all end
 * at primitive fields outside of lists, a whole primitive column or fields
 * of a {@code STRUCT} column, is read a field at a time through a
 * {@link ColumnReader} of parquet-java straight into a vector, without
 * objects per row; a {@code NULL} on the way to the field is a
 * {@code NULL} field. Any other column is read through the record API into
 * vectors of its type, see {@link VectorMaterializer}, of a
 * {@code STRUCT} only the fields the paths name, and the fields of the
 * paths are the children of those vectors. Both read the same number of
 * rows for each batch.
 * <p>
 * A batch never spans two row groups; the last one of a row group is
 * smaller. A path requested twice is read once. Without columns, as for
 * {@code count(*)} without pushdown, the file is not read at all. Under a
 * predicate, only the pages in which a row can satisfy it are read, by the
 * column index of the row group, and only their rows are delivered; the
 * readers of parquet-java keep the columns in step.
 */
final class RowGroupReader implements BatchReader {

    private final Path file;
    private final int block;
    private long rows;
    private final Schema output;
    private final int batchSize;
    private final Consumer<ParquetConnector.Read> reads;
    /** For each output column where its values come from. */
    private final Source[] sources;
    /** The paths of the primitive fields read through column readers, their leaves and readers. */
    private final List<ColumnPath> paths = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<ParquetLeaf> leaves = new ArrayList<>();
    private final List<ColumnReader> readers = new ArrayList<>();
    /** The names of the columns read through records. */
    private final List<String> nested = new ArrayList<>();
    private VectorMaterializer materializer;
    private RecordReader<Object> records;
    /** The paths of the primitive columns read, in the order of the file. */
    private List<String> read = List.of();
    private PageReadStore pages;
    private long delivered;
    private boolean recorded;

    /**
     * Opens a row group of a file and reads its pages of the columns into
     * memory.
     *
     * @param footer the footer of the file in the version the scan plans with
     * @param block the index of the row group in the file
     * @param rows the rows to deliver, at most those of the row group
     * @param predicate the predicate to skip pages by, or {@code null}
     * @param output the columns of the batches
     * @param columns the columns of the table to read, one per output column
     * @param reads takes what was read when the reader is done, or {@code null}
     * @throws IllegalArgumentException naming the file and the row group if
     *         the file changed or its pages cannot be read
     */
    RowGroupReader(ParquetFiles.Footer footer, int block, long rows, FilterPredicate predicate, Schema output,
            List<ColumnPath> columns, int batchSize, Consumer<ParquetConnector.Read> reads) {
        this.file = footer.version().file();
        this.block = block;
        this.rows = rows;
        this.output = output;
        this.batchSize = batchSize;
        this.reads = reads;
        this.sources = new Source[columns.size()];
        if (columns.isEmpty()) {
            return;
        }
        MessageType schema = footer.schema();
        MessageType requested = ParquetSchemas.requested(schema, columns);
        Map<Integer, Boolean> flat = new TreeMap<>();
        for (ColumnPath p : columns) {
            flat.merge(p.column(), ParquetSchemas.leaf(schema, p) != null, Boolean::logicalAnd);
        }
        List<Integer> recordColumns = new ArrayList<>();
        List<Type> recordFields = new ArrayList<>();
        List<DataType> recordTypes = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> e : flat.entrySet()) {
            if (!e.getValue()) {
                Type t = requested.getType(schema.getType(e.getKey()).getName());
                recordColumns.add(e.getKey());
                recordFields.add(t);
                recordTypes.add(ParquetSchemas.columnType(t));
                nested.add(t.getName());
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            ColumnPath p = columns.get(i);
            if (flat.get(p.column())) {
                int k = paths.indexOf(p);
                if (k < 0) {
                    k = paths.size();
                    paths.add(p);
                    names.add(String.join(".", ParquetSchemas.path(schema, p)));
                    leaves.add(ParquetLeaf.of(ParquetSchemas.leaf(schema, p), output.type(i)));
                }
                sources[i] = new Source(k, -1, null);
            } else {
                int m = recordColumns.indexOf(p.column());
                sources[i] = new Source(-1, m, children(recordTypes.get(m), p.fields()));
            }
        }
        if (!ParquetFiles.Version.of(file).equals(footer.version())) {
            throw new IllegalArgumentException("The Parquet file " + file + " changed since its footer was read");
        }
        read = requested.getColumns().stream().map(d -> String.join(".", d.getPath())).toList();
        InputFile input = new LocalFile(file);
        try (ParquetFileReader reader = new ParquetFileReader(input, footer.metadata(), predicate == null
                ? ParquetFiles.options() : ParquetFiles.options(FilterCompat.get(predicate), false),
                input.newStream())) {
            reader.setRequestedSchema(requested);
            pages = predicate == null ? reader.readRowGroup(block) : reader.readFilteredRowGroup(block);
        } catch (IOException | RuntimeException e) {
            throw error(where(), e);
        }
        if (pages == null) {
            this.rows = 0;
            return;
        }
        this.rows = Math.min(rows, pages.getRowCount());
        String createdBy = footer.metadata().getFileMetaData().getCreatedBy();
        ColumnReadStoreImpl store = new ColumnReadStoreImpl(pages, new NoConverters(requested), requested, createdBy);
        for (int k = 0; k < paths.size(); k++) {
            ColumnDescriptor descriptor = requested.getColumnDescription(ParquetSchemas.path(schema, paths.get(k)));
            try {
                readers.add(store.getColumnReader(descriptor));
            } catch (RuntimeException e) {
                throw error(where() + ", column " + names.get(k), e);
            }
        }
        if (!recordFields.isEmpty()) {
            MessageType message = new MessageType(schema.getName(), recordFields);
            materializer = new VectorMaterializer(message, recordTypes);
            try {
                records = new ColumnIOFactory(createdBy).getColumnIO(message).getRecordReader(pages, materializer);
            } catch (RuntimeException e) {
                throw error(where() + nestedColumns(), e);
            }
        }
    }

    /**
     * Where the values of an output column come from: a primitive field
     * read through a column reader, or a column read through records and
     * the fields to follow into it.
     */
    private record Source(int leaf, int record, int[] fields) {
    }

    /** Returns the indexes of the fields of a path in the {@code STRUCT} types along it. */
    private static int[] children(DataType type, List<String> fields) {
        int[] children = new int[fields.size()];
        for (int k = 0; k < children.length; k++) {
            List<DataType.Struct.Field> struct = ((DataType.Struct) type).fields();
            int j = 0;
            while (!struct.get(j).name().equals(fields.get(k))) {
                j++;
            }
            children[k] = j;
            type = struct.get(j).type();
        }
        return children;
    }

    private String nestedColumns() {
        return (nested.size() == 1 ? ", column " : ", columns ") + String.join(", ", nested);
    }

    private String where() {
        return "Row group " + block + " of the Parquet file " + file;
    }

    private static IllegalArgumentException error(String where, Exception e) {
        return new IllegalArgumentException(where + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()),
                e);
    }

    @Override
    public Batch next() {
        if (delivered == rows) {
            record();
            return null;
        }
        int n = (int) Math.min(batchSize, rows - delivered);
        Vector[] built = new Vector[leaves.size()];
        for (int k = 0; k < built.length; k++) {
            ParquetLeaf leaf = leaves.get(k);
            Vector.Builder b = Vectors.builder(leaf.type(), n);
            try {
                read(readers.get(k), leaf, b, n);
            } catch (RuntimeException e) {
                throw error(where() + ", column " + names.get(k), e);
            }
            built[k] = b.build();
        }
        Vector[] assembled = null;
        if (records != null) {
            materializer.start(n);
            try {
                for (int r = 0; r < n; r++) {
                    records.read();
                }
            } catch (RuntimeException e) {
                throw error(where() + nestedColumns(), e);
            }
            assembled = materializer.finish();
        }
        List<Vector> vectors = new ArrayList<>(sources.length);
        for (Source source : sources) {
            if (source.leaf() >= 0) {
                vectors.add(built[source.leaf()]);
            } else {
                Vector v = assembled[source.record()];
                for (int child : source.fields()) {
                    v = ((StructVector) v).child(child);
                }
                vectors.add(v);
            }
        }
        delivered += n;
        return new Batch(output, n, vectors);
    }

    /** Reads the next {@code n} values of a column into a builder. */
    private static void read(ColumnReader r, ParquetLeaf leaf, Vector.Builder b, int n) {
        int max = r.getDescriptor().getMaxDefinitionLevel();
        boolean plain = leaf.isPlain();
        switch (leaf.physical()) {
            case BOOLEAN -> {
                BooleanVector.Builder v = (BooleanVector.Builder) b;
                for (int i = 0; i < n; i++) {
                    if (r.getCurrentDefinitionLevel() < max) {
                        v.appendNull();
                    } else {
                        v.appendBoolean(r.getBoolean());
                    }
                    r.consume();
                }
            }
            case INT32 -> {
                if (plain) {
                    IntVector.Builder v = (IntVector.Builder) b;
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            v.appendNull();
                        } else {
                            v.appendInt(r.getInteger());
                        }
                        r.consume();
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            b.appendNull();
                        } else {
                            leaf.addInt(b, r.getInteger());
                        }
                        r.consume();
                    }
                }
            }
            case INT64 -> {
                if (plain) {
                    BigIntVector.Builder v = (BigIntVector.Builder) b;
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            v.appendNull();
                        } else {
                            v.appendLong(r.getLong());
                        }
                        r.consume();
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            b.appendNull();
                        } else {
                            leaf.addLong(b, r.getLong());
                        }
                        r.consume();
                    }
                }
            }
            case FLOAT -> {
                FloatVector.Builder v = (FloatVector.Builder) b;
                for (int i = 0; i < n; i++) {
                    if (r.getCurrentDefinitionLevel() < max) {
                        v.appendNull();
                    } else {
                        v.appendFloat(r.getFloat());
                    }
                    r.consume();
                }
            }
            case DOUBLE -> {
                DoubleVector.Builder v = (DoubleVector.Builder) b;
                for (int i = 0; i < n; i++) {
                    if (r.getCurrentDefinitionLevel() < max) {
                        v.appendNull();
                    } else {
                        v.appendDouble(r.getDouble());
                    }
                    r.consume();
                }
            }
            case BINARY, FIXED_LEN_BYTE_ARRAY, INT96 -> {
                if (plain) {
                    BytesVector.Builder v = (BytesVector.Builder) b;
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            v.appendNull();
                        } else {
                            Binary value = r.getBinary();
                            ByteBuffer bytes = value.toByteBuffer();
                            if (bytes.hasArray()) {
                                v.appendBytes(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
                            } else {
                                v.appendBytes(value.getBytesUnsafe());
                            }
                        }
                        r.consume();
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (r.getCurrentDefinitionLevel() < max) {
                            b.appendNull();
                        } else {
                            leaf.addBinary(b, r.getBinary());
                        }
                        r.consume();
                    }
                }
            }
        }
    }

    private void record() {
        if (!recorded && reads != null) {
            reads.accept(new ParquetConnector.Read(file, block, read, delivered));
        }
        recorded = true;
    }

    @Override
    public void close() {
        record();
        if (pages != null) {
            pages.close();
            pages = null;
        }
    }

    /**
     * The converters a {@link ColumnReadStoreImpl} asks for along the path
     * of a primitive field; the values are taken from the readers instead.
     */
    private static final class NoConverters extends GroupConverter {
        private static final PrimitiveConverter NONE = new PrimitiveConverter() {
        };
        private final GroupType group;

        NoConverters(GroupType group) {
            this.group = group;
        }

        @Override
        public Converter getConverter(int fieldIndex) {
            Type t = group.getType(fieldIndex);
            return t.isPrimitive() ? NONE : new NoConverters(t.asGroupType());
        }

        @Override
        public void start() {
        }

        @Override
        public void end() {
        }
    }
}
