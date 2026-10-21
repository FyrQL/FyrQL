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
import net.fyrql.core.value.Casts;

import java.sql.SQLException;
import java.time.ZoneId;
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
    private final ZoneId zone;

    /**
     * @param values the values of the fields, as the engine holds them
     * @param zone the time zone of the session, in which an instant is shown
     */
    FyrQLStruct(DataType.Struct type, List<?> values, ZoneId zone) {
        this.type = type;
        this.values = values;
        this.zone = zone;
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
            result[i] = JdbcValues.toObject(values.get(i), type.fields().get(i).type(), zone);
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
