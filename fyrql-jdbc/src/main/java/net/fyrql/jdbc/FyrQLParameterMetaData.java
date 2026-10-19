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

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

/**
 * The parameters of a prepared statement: inputs whose nullability is
 * unknown, with the types the engine gives them when it prepares the query,
 * see {@link net.fyrql.FyrQL#prepare(String)}. Where it cannot, as for
 * {@code SELECT ?} or {@code LIMIT ?}, only the number of the parameters is
 * known, and each is of the type {@link Types#OTHER}, named
 * {@value #UNKNOWN}. The value of a setter keeps its own type either way.
 */
final class FyrQLParameterMetaData implements ParameterMetaData {

    /** The name of the type of a parameter whose type is unknown. */
    static final String UNKNOWN = "UNKNOWN";

    /** The type of each parameter, {@code null} where it is unknown. */
    private final List<DataType> types;

    private FyrQLParameterMetaData(List<DataType> types) {
        this.types = types;
    }

    /** Parameters of known types. */
    static FyrQLParameterMetaData of(List<DataType> types) {
        return new FyrQLParameterMetaData(List.copyOf(types));
    }

    /** A number of parameters of unknown types. */
    static FyrQLParameterMetaData unknown(int count) {
        return new FyrQLParameterMetaData(Arrays.asList(new DataType[count]));
    }

    /** Returns the type of a parameter, {@code null} if it is unknown. */
    private DataType type(int param) throws SQLException {
        if (param < 1 || param > types.size()) {
            throw SqlStates.invalidArgument("The parameter index " + param + " is out of range 1 to " + types.size());
        }
        return types.get(param - 1);
    }

    @Override
    public int getParameterCount() {
        return types.size();
    }

    @Override
    public int isNullable(int param) throws SQLException {
        type(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        DataType type = type(param);
        return type != null && JdbcTypes.signed(type);
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        DataType type = type(param);
        return type == null ? 0 : JdbcTypes.precision(type);
    }

    @Override
    public int getScale(int param) throws SQLException {
        DataType type = type(param);
        return type == null ? 0 : JdbcTypes.scale(type);
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        DataType type = type(param);
        return type == null ? Types.OTHER : JdbcTypes.sqlType(type);
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        DataType type = type(param);
        return type == null ? UNKNOWN : type.sqlName();
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        DataType type = type(param);
        return (type == null ? Object.class : JdbcTypes.javaClass(type)).getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        type(param);
        return parameterModeIn;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("The parameter metadata does not wrap a " + iface.getName(), SqlStates.GENERAL);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
