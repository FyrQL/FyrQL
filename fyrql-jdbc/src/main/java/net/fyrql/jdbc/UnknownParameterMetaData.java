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

package net.fyrql.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * The parameters of a prepared statement, of which only the number is
 * known: the engine does not bind a query with parameters, so every
 * parameter is an input of the type {@link Types#OTHER}, whose nullability
 * is unknown.
 */
final class UnknownParameterMetaData implements ParameterMetaData {

    /** The name of the type of every parameter. */
    static final String TYPE_NAME = "UNKNOWN";

    private final int count;

    UnknownParameterMetaData(int count) {
        this.count = count;
    }

    private void check(int param) throws SQLException {
        if (param < 1 || param > count) {
            throw SqlStates.invalidArgument("The parameter index " + param + " is out of range 1 to " + count);
        }
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return TYPE_NAME;
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return Object.class.getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
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
