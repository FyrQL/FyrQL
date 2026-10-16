/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.core.vector;

import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A batch of rows in columnar form: one vector per column of the schema,
 * all of the same size.
 *
 * @param schema the columns
 * @param rowCount the number of rows
 * @param columns the vector of each column, in the order of the schema
 */
public record Batch(Schema schema, int rowCount, List<Vector> columns) {

    public Batch {
        columns = List.copyOf(columns);
        if (columns.size() != schema.size()) {
            throw new IllegalArgumentException(columns.size() + " vectors for " + schema.size() + " columns");
        }
        for (int i = 0; i < columns.size(); i++) {
            Vector v = columns.get(i);
            if (v.size() != rowCount) {
                throw new IllegalArgumentException("Column " + i + " has " + v.size() + " rows, the batch " + rowCount);
            }
            if (!v.type().equals(schema.type(i))) {
                throw new IllegalArgumentException("Column " + i + " is a " + v.type() + ", the schema says "
                        + schema.type(i));
            }
        }
    }

    /** Creates a batch from vectors; the row count is theirs. */
    public static Batch of(Schema schema, List<Vector> columns) {
        return new Batch(schema, columns.isEmpty() ? 0 : columns.get(0).size(), columns);
    }

    /** Creates a batch from rows of Java values. */
    public static Batch ofRows(Schema schema, List<Object[]> rows) {
        List<Vector.Builder> builders = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            builders.add(Vectors.builder(schema.type(c), rows.size()));
        }
        for (Object[] row : rows) {
            if (row.length != schema.size()) {
                throw new IllegalArgumentException("A row has " + row.length + " values, the schema " + schema.size());
            }
            for (int c = 0; c < schema.size(); c++) {
                builders.get(c).append(row[c]);
            }
        }
        List<Vector> columns = new ArrayList<>();
        for (Vector.Builder b : builders) {
            columns.add(b.build());
        }
        return new Batch(schema, rows.size(), columns);
    }

    /** Returns an empty batch. */
    public static Batch empty(Schema schema) {
        return ofRows(schema, List.of());
    }

    /** Returns the vector of a column. */
    public Vector column(int index) {
        return columns.get(index);
    }

    /** Returns the batch with the given rows, in that order. */
    public Batch select(int[] rows) {
        List<Vector> selected = new ArrayList<>(columns.size());
        for (Vector v : columns) {
            selected.add(v.select(rows));
        }
        return new Batch(schema, rows.length, selected);
    }

    /** Returns the batch with the given columns, in that order. */
    public Batch project(List<Integer> indexes) {
        List<Vector> projected = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            projected.add(columns.get(i));
        }
        return new Batch(schema.select(indexes), rowCount, projected);
    }

    /** Returns the rows as Java values, {@code null} for {@code NULL}. */
    public List<Object[]> rows() {
        List<Object[]> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            Object[] row = new Object[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                row[c] = columns.get(c).get(r);
            }
            rows.add(row);
        }
        return rows;
    }

    /** Returns one row as Java values. */
    public Object[] row(int index) {
        Object[] row = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            row[c] = columns.get(c).get(index);
        }
        return row;
    }
}
