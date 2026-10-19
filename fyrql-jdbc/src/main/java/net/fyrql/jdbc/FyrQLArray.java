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

package net.fyrql.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A value of a {@code LIST} type. {@link #getArray()} returns a Java array
 * of the class {@code getObject} returns for the element type, a
 * {@code java.sql.Date[]} for a {@code DATE[]}, say; an element that is
 * {@code NULL} is {@code null}, and a nested element is a {@code FyrQLArray},
 * a {@link FyrQLStruct} or a {@code Map} in turn. {@link #getResultSet()}
 * has the columns {@code INDEX}, from 1, and {@code VALUE}.
 * {@link #toString()} is the text {@code getString} returns.
 */
final class FyrQLArray implements Array {

    private static final String INDEX = "INDEX";
    private static final String VALUE = "VALUE";

    private final DataType.ListType type;
    private final List<?> values;
    private boolean freed;

    /**
     * @param values the elements, as the engine holds them
     */
    FyrQLArray(DataType.ListType type, List<?> values) {
        this.type = type;
        this.values = values;
    }

    /** Returns the type of the array. */
    DataType.ListType type() {
        return type;
    }

    /** Returns the elements, as the engine holds them. */
    List<?> values() {
        return values;
    }

    private void checkNotFreed() throws SQLException {
        if (freed) {
            throw new SQLException("The array has been freed", SqlStates.FUNCTION_SEQUENCE);
        }
    }

    private static void checkNoTypeMap(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw SqlStates.unsupported("User-defined types");
        }
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        checkNotFreed();
        return type.element().sqlName();
    }

    @Override
    public int getBaseType() throws SQLException {
        checkNotFreed();
        return JdbcTypes.sqlType(type.element());
    }

    @Override
    public Object getArray() throws SQLException {
        return getArray(1, values.size());
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        checkNoTypeMap(map);
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        checkNotFreed();
        int from = checkRange(index, count);
        DataType element = type.element();
        Object[] result = (Object[]) java.lang.reflect.Array.newInstance(JdbcTypes.javaClass(element), count);
        for (int i = 0; i < count; i++) {
            result[i] = JdbcValues.toObject(values.get(from + i), element);
        }
        return result;
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        checkNoTypeMap(map);
        return getArray(index, count);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return getResultSet(1, values.size());
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        checkNoTypeMap(map);
        return getResultSet();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
        checkNotFreed();
        int from = checkRange(index, count);
        Schema schema = Schema.of(new Schema.Field(INDEX, DataType.BIGINT, false),
                new Schema.Field(VALUE, type.element(), true));
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[] {(long) (from + i + 1), values.get(from + i)});
        }
        return new FyrQLResultSet(null, BatchSource.of(schema, rows), 0, null, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        checkNoTypeMap(map);
        return getResultSet(index, count);
    }

    /** Checks that the elements from {@code index}, counted from 1, exist; returns the first one counted from 0. */
    private int checkRange(long index, int count) throws SQLException {
        if (index < 1 || count < 0 || index - 1 + count > values.size()) {
            throw SqlStates.invalidArgument(count + " elements from index " + index
                    + " are out of range; the array has " + values.size() + " elements");
        }
        return (int) (index - 1);
    }

    @Override
    public void free() {
        freed = true;
    }

    /** Returns the text of the array, as {@code getString} returns it. */
    @Override
    public String toString() {
        return Casts.format(values, type);
    }
}
