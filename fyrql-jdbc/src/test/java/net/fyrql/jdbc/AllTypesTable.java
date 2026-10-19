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

import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Conversions;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A table {@code t} with a column of every type of FyrQL, nested ones
 * included, as the engine holds the values. The first row has a value in
 * every column and {@code NULL} at every level inside the nested ones, the
 * second row is {@code NULL} in every column, the third has empty lists and
 * maps and a struct of {@code NULL}s.
 */
final class AllTypesTable {

    static final DataType.Struct AB = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
            new DataType.Struct.Field("b", DataType.VARCHAR));
    static final DataType.Struct XYZ = DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
            new DataType.Struct.Field("y", DataType.VARCHAR),
            new DataType.Struct.Field("z", DataType.listOf(DataType.DATE)));
    static final DataType.Struct X = DataType.struct(new DataType.Struct.Field("x", DataType.DATE));

    static final Schema SCHEMA = Schema.of(
            field("b", DataType.BOOLEAN),
            field("ti", DataType.TINYINT),
            field("si", DataType.SMALLINT),
            field("i", DataType.INTEGER),
            field("bi", DataType.BIGINT),
            field("hi", DataType.HUGEINT),
            field("f", DataType.FLOAT),
            field("d", DataType.DOUBLE),
            field("dec", DataType.decimal(38, 10)),
            field("v", DataType.VARCHAR),
            field("bl", DataType.BLOB),
            field("dt", DataType.DATE),
            field("ts", DataType.TIMESTAMP),
            field("u", DataType.UUID),
            field("li", DataType.listOf(DataType.INTEGER)),
            field("ld", DataType.listOf(DataType.DATE)),
            field("lb", DataType.listOf(DataType.BLOB)),
            field("sa", DataType.listOf(AB)),
            field("st", XYZ),
            field("m", DataType.mapOf(DataType.VARCHAR, X)),
            field("ll", DataType.listOf(DataType.listOf(DataType.INTEGER))));

    static final List<Object[]> ROWS = List.of(
            new Object[] {true, (byte) 1, (short) 2, 3, 4L, new BigInteger("170141183460469231731687303715884105727"),
                    1.5f, 2.5, new BigDecimal("1234567890123456789012345678.0123456789"), "abc",
                    new byte[] {(byte) 0xAA, 0}, day("2024-02-29"), micros("2024-02-29T12:34:56.123456"),
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    list(1, null, 3),
                    list(day("2024-02-29"), null),
                    list(new byte[] {1}, null),
                    list(list(1, "x"), null, list(null, "y")),
                    list(7, null, list(day("2024-01-01"))),
                    map("k1", list(day("2024-02-29")), "k2", null, "k3", list((Object) null)),
                    list(list(1, 2), null, list(), list((Object) null))},
            new Object[SCHEMA.size()],
            new Object[] {false, (byte) 0, (short) 0, 0, 0L, BigInteger.ZERO, 0f, 0.0, new BigDecimal("0E-10"), "",
                    new byte[0], 0, 0L, new UUID(0, 0),
                    list(), list(), list(), list(), list(null, null, null), map(), list()});

    private AllTypesTable() {
    }

    /** Returns a source of the name with the table {@code t}. */
    static MemoryConnector connector(String name) {
        return MemoryConnector.builder(name).table("t", SCHEMA, ROWS).build();
    }

    private static Schema.Field field(String name, DataType type) {
        return new Schema.Field(name, type, true);
    }

    private static int day(String date) {
        return (int) LocalDate.parse(date).toEpochDay();
    }

    private static long micros(String timestamp) {
        return Conversions.toMicros(LocalDateTime.parse(timestamp));
    }

    /** A list that may hold {@code null}. */
    private static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static Map<Object, Object> map(Object... keysAndValues) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }
}
