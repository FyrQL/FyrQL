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
import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileStores;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One Parquet file for the readers of table formats on top of Parquet,
 * such as Iceberg, without the types of parquet-java, which are renamed
 * inside the JAR of this module: the columns of the file with their field
 * ids, its row groups with their first rows, the row groups in which a row
 * can pass filters, and a reader of a row group.
 * <p>
 * A {@link ColumnPath} here refers to a column of the file, and a filter
 * to the columns as a list of paths gives them. A column may be read as a
 * wider type than the file has: an integer as a wider integer, a
 * {@code FLOAT} as a {@code DOUBLE} and a {@code DECIMAL} as one with more
 * digits and the same scale, but only a primitive column or a field of a
 * {@code STRUCT} outside of lists, and a column read twice as the same
 * type.
 */
public final class ParquetFile {

    private final ParquetFiles files;
    private final ParquetFiles.Footer footer;
    private final Schema schema;
    private final int[] fieldIds;
    private final long[] firstRows;

    private ParquetFile(ParquetFiles files, ParquetFiles.Footer footer) {
        this.files = files;
        this.footer = footer;
        MessageType message = footer.schema();
        try {
            schema = ParquetSchemas.schema(message);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + " in " + location(), e);
        }
        fieldIds = new int[message.getFieldCount()];
        for (int c = 0; c < fieldIds.length; c++) {
            Type.ID id = message.getType(c).getId();
            fieldIds[c] = id == null ? -1 : id.intValue();
        }
        List<BlockMetaData> blocks = footer.metadata().getBlocks();
        firstRows = new long[blocks.size()];
        long row = 0;
        for (int b = 0; b < firstRows.length; b++) {
            firstRows[b] = row;
            row += blocks.get(b).getRowCount();
        }
    }

    /**
     * Reads the footer of a file.
     *
     * @param stores the stores in which the file is
     * @param file the file
     * @param readAhead the bytes a file of a store other than the local one is read ahead by
     * @throws IllegalArgumentException if the file is not a Parquet file, cannot
     *         be read or has a column whose type FyrQL cannot read
     */
    public static ParquetFile open(FileStores stores, FileObject file, int readAhead) {
        ParquetFiles files = new ParquetFiles(stores, readAhead);
        return new ParquetFile(files, files.footer(file));
    }

    /**
     * Reads the footer of the file at a location, see {@link #open(FileStores, FileObject, int)}.
     *
     * @throws IllegalArgumentException also if there is no file at the location
     */
    public static ParquetFile open(FileStores stores, String location, int readAhead) {
        ParquetFiles files = new ParquetFiles(stores, readAhead);
        return new ParquetFile(files, files.footer(files.object(location)));
    }

    /** Returns the location of the file. */
    public String location() {
        return footer.version().location();
    }

    /** Returns the size of the file in bytes. */
    public long size() {
        return footer.version().size();
    }

    /** Returns the columns of the file. */
    public Schema schema() {
        return schema;
    }

    /** Returns the field id of each column of the file, {@code -1} for a column without one. */
    public int[] fieldIds() {
        return fieldIds.clone();
    }

    /** Returns the number of row groups. */
    public int rowGroups() {
        return firstRows.length;
    }

    /** Returns the rows of a row group. */
    public long rows(int rowGroup) {
        return footer.metadata().getBlocks().get(rowGroup).getRowCount();
    }

    /** Returns the position in the file of the first row of a row group, counted from 0. */
    public long firstRow(int rowGroup) {
        return firstRows[rowGroup];
    }

    /** Returns the rows of the file. */
    public long rows() {
        return footer.rows();
    }

    /**
     * Returns the row groups with rows in which a row can satisfy all
     * filters, by their statistics, dictionaries and Bloom filters; filters
     * without a counterpart in Parquet keep every row group.
     *
     * @param filters conditions over the columns the paths give
     * @param columns the paths in the file of the columns of the filters
     */
    public List<Integer> rowGroups(List<Expression> filters, List<ColumnPath> columns) {
        FilterPredicate predicate = filters.isEmpty() ? null
                : FilterTranslation.translate(filters, footer.schema(), columns);
        List<BlockMetaData> blocks = footer.metadata().getBlocks();
        List<BlockMetaData> passing = predicate == null ? blocks : files.passing(footer, predicate);
        List<Integer> groups = new ArrayList<>();
        for (int b = 0; b < blocks.size(); b++) {
            if (blocks.get(b).getRowCount() > 0 && ParquetConnector.contains(passing, blocks.get(b))) {
                groups.add(b);
            }
        }
        return groups;
    }

    /**
     * Opens a reader of the first rows of a row group. Under page filters,
     * only the pages in which a row can satisfy them are read, so that the
     * rows delivered are no longer the consecutive rows of the row group;
     * without, they are.
     *
     * @param rowGroup the index of the row group
     * @param rows the rows to deliver, at most those of the row group
     * @param output the columns of the batches, of the types of the file or wider ones
     * @param columns the paths in the file, one per output column
     * @param pageFilters conditions over the output columns to skip pages by, or none
     * @param batchSize the most rows of a batch
     * @param reads takes what was read when the reader is done, or {@code null}
     * @throws IllegalArgumentException naming the file and the column if a
     *         column cannot be read as the output type, or if the file
     *         changed or cannot be read
     */
    public BatchReader read(int rowGroup, long rows, Schema output, List<ColumnPath> columns,
            List<Expression> pageFilters, int batchSize, Consumer<ParquetConnector.Read> reads) {
        if (output.size() != columns.size()) {
            throw new IllegalArgumentException(output.size() + " output columns for " + columns.size() + " paths");
        }
        MessageType message = footer.schema();
        for (int i = 0; i < columns.size(); i++) {
            ColumnPath p = columns.get(i);
            if (p.column() >= message.getFieldCount()) {
                throw new IllegalArgumentException("No column " + p + " in the Parquet file " + location());
            }
            DataType type = typeAt(p);
            DataType wanted = output.type(i);
            int first = columns.indexOf(p);
            if (first < i && !output.type(first).equals(wanted)) {
                throw new IllegalArgumentException("The column " + p + " of the Parquet file " + location()
                        + " is read as " + output.type(first).sqlName() + " and as " + wanted.sqlName());
            }
            if (!type.equals(wanted) && !(ParquetSchemas.leaf(message, p) != null && widens(type, wanted))) {
                throw new IllegalArgumentException("Cannot read the column " + String.join(".",
                        ParquetSchemas.path(message, p)) + " of type " + type.sqlName() + " of the Parquet file "
                        + location() + " as " + wanted.sqlName());
            }
        }
        FilterPredicate predicate = pageFilters.isEmpty() ? null
                : FilterTranslation.translate(pageFilters, message, columns);
        return new RowGroupReader(files, footer, rowGroup, rows, predicate, output, columns, batchSize, reads);
    }

    /** Returns the type of the column or field a path ends at. */
    private DataType typeAt(ColumnPath p) {
        DataType type = schema.type(p.column());
        for (String field : p.fields()) {
            if (!(type instanceof DataType.Struct struct)) {
                throw new IllegalArgumentException("The column " + p + " of the Parquet file " + location()
                        + " is not a STRUCT");
            }
            DataType next = null;
            for (DataType.Struct.Field f : struct.fields()) {
                if (f.name().equals(field)) {
                    next = f.type();
                }
            }
            if (next == null) {
                throw new IllegalArgumentException("No field " + field + " in " + p + " of the Parquet file "
                        + location());
            }
            type = next;
        }
        return type;
    }

    /** Tells whether the values of one type are read as another without loss. */
    static boolean widens(DataType from, DataType to) {
        if (from.isIntegral() && to.isIntegral() && from != DataType.HUGEINT && to != DataType.HUGEINT) {
            return rank(to) >= rank(from);
        }
        if (from == DataType.FLOAT && to == DataType.DOUBLE) {
            return true;
        }
        return from instanceof DataType.Decimal a && to instanceof DataType.Decimal b && a.scale() == b.scale()
                && b.precision() >= a.precision();
    }

    private static int rank(DataType integral) {
        return integral == DataType.TINYINT ? 1 : integral == DataType.SMALLINT ? 2 : integral == DataType.INTEGER
                ? 3 : 4;
    }
}
