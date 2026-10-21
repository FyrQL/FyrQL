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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.parquet.ParquetFile;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a row group of a data file as rows of the table: the columns by
 * their field ids, columns the file lacks as the identity partition value,
 * the {@code initial-default} or {@code NULL}, and without the rows that
 * deletes remove.
 */
final class IcebergReader implements BatchReader {

    /**
     * How the columns of a scan come from a data file.
     *
     * @param read the paths read from the file
     * @param readSchema the types they are read as
     * @param sources for each output column the index in {@code read}, or -1
     * @param constants for each output column without a source its value
     * @param equality for each group of equality delete files with the same
     *        ids the sources and constants of their columns
     * @param filters the filters of the scan over the columns read, those
     *        that read only columns of the file in their own types
     */
    record Layout(List<ColumnPath> read, Schema readSchema, int[] sources, Object[] constants,
            List<EqualityGroup> equality, List<Expression> filters) {
    }

    /** Equality delete files with the same ids and where the values of their columns come from. */
    record EqualityGroup(List<Manifests.ContentFile> files, int[] sources, Object[] constants) {
    }

    private final BatchReader parquet;
    private final Layout layout;
    private final Schema output;
    private final long[] deleted;
    private final List<Set<List<Object>>> equality = new ArrayList<>();
    private int cursor;
    private long position;

    /**
     * Opens a reader of a row group.
     *
     * @param pageFilters whether pages may be skipped by the filters of the
     *        layout; never with position deletes, whose positions count all rows
     */
    IcebergReader(ParquetFile file, int group, long rows, Layout layout, Schema output, SnapshotScan.FileTask task,
            Deletes deletes, boolean pageFilters, int batchSize,
            java.util.function.Consumer<net.fyrql.connector.parquet.ParquetConnector.Read> reads) {
        this.layout = layout;
        this.output = output;
        this.deleted = deletes.positions(task);
        for (EqualityGroup g : layout.equality()) {
            for (Manifests.ContentFile f : g.files()) {
                equality.add(deletes.equality(f));
            }
        }
        this.position = file.firstRow(group);
        this.parquet = file.read(group, rows, layout.readSchema(), layout.read(),
                pageFilters ? layout.filters() : List.of(), batchSize, reads);
    }

    /**
     * Works out how the columns of a scan come from a data file.
     *
     * @param schema the schema of the snapshot
     * @param columns the paths of the output in the table
     * @param output the columns of the output
     * @param filters the filters of the scan over the output, or none
     * @param task the data file and its deletes
     * @param specs the partition specs of the table
     * @throws IllegalArgumentException naming the file if it has no field ids
     */
    static Layout layout(IcebergSchema schema, List<ColumnPath> columns, Schema output, List<Expression> filters,
            ParquetFile file, SnapshotScan.FileTask task, Map<Integer, PartitionSpec> specs) {
        int[] ids = file.fieldIds();
        if (ids.length > 0 && java.util.Arrays.stream(ids).allMatch(id -> id < 0)) {
            throw new IllegalArgumentException("The Parquet file " + task.data().path() + " has no field ids;"
                    + " reading it by the names of its columns (name-mapping) is not supported");
        }
        List<ColumnPath> read = new ArrayList<>();
        List<Schema.Field> readFields = new ArrayList<>();
        int[] sources = new int[columns.size()];
        Object[] constants = new Object[columns.size()];
        boolean[] own = new boolean[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColumnPath p = columns.get(i);
            IcebergSchema.Column column = schema.columns().get(p.column());
            int c = fileColumn(ids, column.fieldId());
            if (c < 0) {
                sources[i] = -1;
                constants[i] = p.fields().isEmpty() ? missing(column, task, specs) : null;
                continue;
            }
            ColumnPath path = new ColumnPath(c, p.fields());
            DataType type = output.type(i);
            sources[i] = index(read, readFields, path, type, output.name(i));
            own[i] = type.equals(typeAt(file.schema(), path));
        }
        Map<List<Integer>, List<Manifests.ContentFile>> byIds = new LinkedHashMap<>();
        for (Manifests.ContentFile d : task.equalityDeletes()) {
            byIds.computeIfAbsent(d.equalityIds(), k -> new ArrayList<>()).add(d);
        }
        List<EqualityGroup> equality = new ArrayList<>();
        for (Map.Entry<List<Integer>, List<Manifests.ContentFile>> e : byIds.entrySet()) {
            int[] eqSources = new int[e.getKey().size()];
            Object[] eqConstants = new Object[eqSources.length];
            for (int k = 0; k < eqSources.length; k++) {
                int id = e.getKey().get(k);
                int c = fileColumn(ids, id);
                if (c < 0) {
                    IcebergSchema.Column column = schema.column(id);
                    eqSources[k] = -1;
                    eqConstants[k] = column == null ? null : Deletes.key(missing(column, task, specs));
                    continue;
                }
                ColumnPath path = new ColumnPath(c, List.of());
                int known = read.indexOf(path);
                eqSources[k] = known >= 0 ? known : index(read, readFields, path, file.schema().type(c),
                        file.schema().name(c));
            }
            equality.add(new EqualityGroup(e.getValue(), eqSources, eqConstants));
        }
        List<Expression> fileFilters = new ArrayList<>();
        for (Expression f : filters) {
            BitSet used = Expressions.columns(f);
            boolean usable = true;
            for (int i = used.nextSetBit(0); i >= 0; i = used.nextSetBit(i + 1)) {
                usable &= i < sources.length && sources[i] >= 0 && own[i];
            }
            if (usable) {
                fileFilters.add(Expressions.transform(f, e -> e instanceof Expression.Column col
                        ? new Expression.Column(sources[col.index()], col.type()) : e));
            }
        }
        return new Layout(List.copyOf(read), new Schema(readFields), sources, constants, List.copyOf(equality),
                List.copyOf(fileFilters));
    }

    /** Returns the index of a path read as a type, adding it if it is not read yet. */
    private static int index(List<ColumnPath> read, List<Schema.Field> fields, ColumnPath path, DataType type,
            String name) {
        for (int k = 0; k < read.size(); k++) {
            if (read.get(k).equals(path) && fields.get(k).type().equals(type)) {
                return k;
            }
        }
        read.add(path);
        fields.add(new Schema.Field(name, type, true));
        return read.size() - 1;
    }

    private static int fileColumn(int[] ids, int fieldId) {
        for (int c = 0; c < ids.length; c++) {
            if (ids[c] == fieldId) {
                return c;
            }
        }
        return -1;
    }

    private static DataType typeAt(Schema schema, ColumnPath path) {
        DataType type = schema.type(path.column());
        for (String field : path.fields()) {
            if (!(type instanceof DataType.Struct s) || s.indexOf(field) < 0) {
                return null;
            }
            type = s.fields().get(s.indexOf(field)).type();
        }
        return type;
    }

    /**
     * Returns the value of a column a data file lacks: the value of an
     * identity partition of the column, else its {@code initial-default},
     * else {@code NULL}.
     */
    static Object missing(IcebergSchema.Column column, SnapshotScan.FileTask task, Map<Integer, PartitionSpec> specs) {
        PartitionSpec spec = specs.get(task.data().specId());
        if (spec != null) {
            for (int k = 0; k < spec.fields().size(); k++) {
                PartitionSpec.Field f = spec.fields().get(k);
                if (f.sourceId() == column.fieldId() && f.transform() instanceof PartitionSpec.Identity
                        && task.data().partition()[k] != null) {
                    return value(column, task.data().partition()[k]);
                }
            }
        }
        if (column.initialDefault() != null && column.primitive() != null) {
            return value(column, SingleValues.json(column.primitive(), column.initialDefault()));
        }
        return null;
    }

    /** Returns a value of the Java form of {@link SingleValues} as a value of the FyrQL type of a column. */
    private static Object value(IcebergSchema.Column column, Object value) {
        if (column.primitive() != null && column.primitive().name().endsWith("_ns") && value instanceof Long nanos) {
            return Math.floorDiv(nanos, 1000L);
        }
        DataType type = column.type();
        if (type == DataType.BIGINT && value instanceof Integer i) {
            return i.longValue();
        }
        if (type == DataType.DOUBLE && value instanceof Float f) {
            return f.doubleValue();
        }
        return value;
    }

    @Override
    public Batch next() {
        while (true) {
            Batch b = parquet.next();
            if (b == null) {
                return null;
            }
            int n = b.rowCount();
            boolean[] drop = new boolean[n];
            int dropped = 0;
            for (int j = 0; j < n && cursor < deleted.length; j++) {
                long pos = position + j;
                while (cursor < deleted.length && deleted[cursor] < pos) {
                    cursor++;
                }
                if (cursor < deleted.length && deleted[cursor] == pos) {
                    drop[j] = true;
                    dropped++;
                }
            }
            position += n;
            int set = 0;
            for (EqualityGroup g : layout.equality()) {
                for (int f = 0; f < g.files().size(); f++, set++) {
                    Set<List<Object>> keys = equality.get(set);
                    if (keys.isEmpty()) {
                        continue;
                    }
                    for (int j = 0; j < n; j++) {
                        if (!drop[j] && keys.contains(key(b, g, j))) {
                            drop[j] = true;
                            dropped++;
                        }
                    }
                }
            }
            List<Vector> vectors = new ArrayList<>(output.size());
            for (int i = 0; i < output.size(); i++) {
                int source = layout.sources()[i];
                vectors.add(source >= 0 ? b.column(source) : constant(output.type(i), layout.constants()[i], n));
            }
            Batch out = new Batch(output, n, vectors);
            if (dropped == 0) {
                return out;
            }
            if (dropped == n) {
                continue;
            }
            int[] kept = new int[n - dropped];
            int k = 0;
            for (int j = 0; j < n; j++) {
                if (!drop[j]) {
                    kept[k++] = j;
                }
            }
            return out.select(kept);
        }
    }

    private static List<Object> key(Batch b, EqualityGroup g, int row) {
        List<Object> key = new ArrayList<>(g.sources().length);
        for (int k = 0; k < g.sources().length; k++) {
            int source = g.sources()[k];
            key.add(source >= 0 ? Deletes.key(b.column(source).get(row)) : g.constants()[k]);
        }
        return key;
    }

    private static Vector constant(DataType type, Object value, int n) {
        if (value == null) {
            return Vectors.nulls(type, n);
        }
        Vector.Builder b = Vectors.builder(type, n);
        for (int i = 0; i < n; i++) {
            b.append(value);
        }
        return b.build();
    }

    @Override
    public void close() {
        parquet.close();
    }

    @Override
    public void cancel() {
        parquet.cancel();
    }
}
