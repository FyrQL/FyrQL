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

package net.fyrql.connector;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * A column of a table, or a field inside a {@code STRUCT} column, to read
 * instead of the whole column. A path with fields yields a nullable column
 * named after the column and the fields, joined by dots.
 *
 * @param column the index of the column in the table schema
 * @param fields the names of the fields to follow, outermost first; empty
 *        for the whole column
 */
public record ColumnPath(int column, List<String> fields) {

    public ColumnPath {
        if (column < 0) {
            throw new IllegalArgumentException("column " + column);
        }
        fields = List.copyOf(fields);
    }

    /** The whole column. */
    public static ColumnPath whole(int column) {
        return new ColumnPath(column, List.of());
    }

    /** The whole columns, in that order. */
    public static List<ColumnPath> whole(List<Integer> columns) {
        List<ColumnPath> paths = new ArrayList<>();
        for (int c : columns) {
            paths.add(whole(c));
        }
        return paths;
    }

    /** The paths a scan reads: its columns, with the fields it follows into them. */
    public static List<ColumnPath> of(PlanNode.Scan scan) {
        List<ColumnPath> paths = new ArrayList<>();
        for (int i = 0; i < scan.columns().size(); i++) {
            paths.add(new ColumnPath(scan.columns().get(i), scan.fields().get(i)));
        }
        return paths;
    }

    /**
     * Returns the column indexes of paths that are whole columns.
     *
     * @throws IllegalArgumentException if a path names a field
     */
    public static List<Integer> indexes(List<ColumnPath> paths) {
        List<Integer> indexes = new ArrayList<>();
        for (ColumnPath p : paths) {
            if (!p.isWhole()) {
                throw new IllegalArgumentException("Fields inside a column are not supported: " + p);
            }
            indexes.add(p.column());
        }
        return indexes;
    }

    /** Tells whether the path is a whole column. */
    public boolean isWhole() {
        return fields.isEmpty();
    }

    /**
     * Returns the output column the path yields: the column of the table,
     * or a nullable field named by the column and the fields.
     *
     * @throws IllegalArgumentException if the column or a field does not
     *         exist, or a field is taken of something that is not a struct
     */
    public Schema.Field field(Schema table) {
        return PlanNode.Scan.field(table, column, fields);
    }

    @Override
    public String toString() {
        return fields.isEmpty() ? "#" + column : "#" + column + "." + String.join(".", fields);
    }
}
