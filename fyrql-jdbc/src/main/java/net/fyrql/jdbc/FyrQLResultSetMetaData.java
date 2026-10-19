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

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * The columns of a result, see {@link JdbcTypes} for the types. A column
 * has the same name and label; its table, schema and catalog are not known
 * and empty.
 */
final class FyrQLResultSetMetaData implements ResultSetMetaData {

    private final Schema schema;

    FyrQLResultSetMetaData(Schema schema) {
        this.schema = schema;
    }

    private Schema.Field field(int column) throws SQLException {
        if (column < 1 || column > schema.size()) {
            throw SqlStates.invalidArgument("The column index " + column + " is out of range; the result has "
                    + schema.size() + " columns");
        }
        return schema.fields().get(column - 1);
    }

    private DataType type(int column) throws SQLException {
        return field(column).type();
    }

    @Override
    public int getColumnCount() {
        return schema.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        field(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return JdbcTypes.caseSensitive(type(column));
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        field(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        field(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return field(column).nullable() ? columnNullable : columnNoNulls;
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return JdbcTypes.signed(type(column));
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return JdbcTypes.displaySize(type(column));
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return field(column).name();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return field(column).name();
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        field(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return JdbcTypes.precision(type(column));
    }

    @Override
    public int getScale(int column) throws SQLException {
        return JdbcTypes.scale(type(column));
    }

    @Override
    public String getTableName(int column) throws SQLException {
        field(column);
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        field(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return JdbcTypes.sqlType(type(column));
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return type(column).sqlName();
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        field(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        field(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        field(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return JdbcTypes.javaClass(type(column)).getName();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The metadata does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
