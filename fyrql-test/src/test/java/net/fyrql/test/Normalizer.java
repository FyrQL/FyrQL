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

package net.fyrql.test;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.ListType;
import net.fyrql.core.type.DataType.MapType;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.DataType.Struct;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.value.Interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the values that DuckDB's JDBC driver returns into the Java values
 * of FyrQL: {@code LocalDate} to days, {@code Timestamp} to microseconds,
 * {@code LocalTime} and {@code OffsetDateTime} to microseconds, the text of
 * an interval to an {@code Interval},
 * {@code Blob} to bytes, {@code Struct} and {@code Array} to lists, and
 * numbers to the box of the type.
 */
public final class Normalizer {

    private Normalizer() {
    }

    public static Object normalize(Object v, DataType type) {
        if (v == null) {
            return null;
        }
        try {
            if (type instanceof Decimal d) {
                return Values.toDecimal(v instanceof BigDecimal b ? b : new BigDecimal(v.toString()), d);
            }
            if (type instanceof Struct s) {
                Object[] attributes = v instanceof java.sql.Struct st ? st.getAttributes() : ((List<?>) v).toArray();
                List<Object> values = new ArrayList<>(attributes.length);
                for (int i = 0; i < attributes.length; i++) {
                    values.add(normalize(attributes[i], s.fields().get(i).type()));
                }
                return values;
            }
            if (type instanceof ListType l) {
                Object[] items = v instanceof Array a ? (Object[]) a.getArray() : ((List<?>) v).toArray();
                List<Object> values = new ArrayList<>(items.length);
                for (Object item : items) {
                    values.add(normalize(item, l.element()));
                }
                return values;
            }
            if (type instanceof MapType m) {
                Map<Object, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    values.put(normalize(e.getKey(), m.key()), normalize(e.getValue(), m.value()));
                }
                return values;
            }
            return switch ((Scalar) type) {
                case BOOLEAN -> (Boolean) v;
                case TINYINT -> ((Number) v).byteValue();
                case SMALLINT -> ((Number) v).shortValue();
                case INTEGER -> ((Number) v).intValue();
                case BIGINT -> ((Number) v).longValue();
                case HUGEINT -> v instanceof BigInteger b ? b : new BigInteger(v.toString());
                case FLOAT -> ((Number) v).floatValue();
                case DOUBLE -> ((Number) v).doubleValue();
                case VARCHAR -> v.toString();
                case BLOB -> v instanceof Blob b ? b.getBytes(1, (int) b.length()) : (byte[]) v;
                case DATE -> v instanceof LocalDate d ? Math.toIntExact(d.toEpochDay())
                        : Math.toIntExact(((java.sql.Date) v).toLocalDate().toEpochDay());
                case TIMESTAMP -> v instanceof Timestamp t ? Conversions.toMicros(t.toLocalDateTime())
                        : Conversions.toMicros((java.time.LocalDateTime) v);
                case UUID -> v instanceof UUID u ? u : UUID.fromString(v.toString());
                case TIME -> v instanceof java.time.LocalTime t ? t.toNanoOfDay() / 1000
                        : Conversions.parseTime(v.toString());
                case TIMESTAMPTZ -> v instanceof java.time.OffsetDateTime t ? Conversions.toMicros(
                        t.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime())
                        : Conversions.parseTimestampTz(v.toString(), java.time.ZoneOffset.UTC);
                case INTERVAL -> v instanceof Interval i ? i : Interval.parse(v.toString());
            };
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read a " + type + " from DuckDB", e);
        }
    }
}
