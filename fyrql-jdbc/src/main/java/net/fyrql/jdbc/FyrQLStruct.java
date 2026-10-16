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

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;

import java.sql.SQLException;
import java.sql.Struct;
import java.util.List;
import java.util.Map;

/**
 * A value of a {@code STRUCT} type. The attributes are in the order of the
 * fields, each of the class {@code getObject} returns for its type, and
 * {@code null} for a field that is {@code NULL}. {@link #toString()} is the
 * text {@code getString} returns.
 */
final class FyrQLStruct implements Struct {

    private final DataType.Struct type;
    private final List<?> values;

    /**
     * @param values the values of the fields, as the engine holds them
     */
    FyrQLStruct(DataType.Struct type, List<?> values) {
        this.type = type;
        this.values = values;
    }

    /** Returns the type of the struct. */
    DataType.Struct type() {
        return type;
    }

    /** Returns the values of the fields, as the engine holds them. */
    List<?> values() {
        return values;
    }

    @Override
    public String getSQLTypeName() {
        return type.sqlName();
    }

    @Override
    public Object[] getAttributes() {
        Object[] result = new Object[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = JdbcValues.toObject(values.get(i), type.fields().get(i).type());
        }
        return result;
    }

    @Override
    public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw SqlStates.unsupported("User-defined types");
        }
        return getAttributes();
    }

    /** Returns the text of the struct, as {@code getString} returns it. */
    @Override
    public String toString() {
        return Casts.format(values, type);
    }
}
