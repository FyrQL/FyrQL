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

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tables {@code t}, {@code u} and {@code w} of the SQL corpus, and {@code n} with nested columns, in a
 * memory source named {@code memory}.
 */
final class CorpusTables {

    static final String SOURCE = "memory";
    static final List<String> NAMES = List.of("t", "u", "w", "n");

    private CorpusTables() {
    }

    /** The source with the four tables; the caller may add settings before building it. */
    static MemoryConnector.Builder builder() {
        return builder(SOURCE, true);
    }

    /** A source of a name with the tables, {@code n} only if asked for. */
    static MemoryConnector.Builder builder(String source, boolean nested) {
        Schema t = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.VARCHAR),
                new Schema.Field("c", DataType.DOUBLE), new Schema.Field("d", DataType.DATE),
                new Schema.Field("e", DataType.TINYINT), new Schema.Field("k", DataType.INTEGER));
        List<Object[]> tRows = List.of(
                row(1, "x", 1.5, date("2024-01-02"), (byte) 3, 10),
                row(2, null, null, null, null, 10),
                row(3, "y", -2.0, date("2023-05-06"), (byte) 4, 20),
                row(4, "x", 0.25, date("2024-02-29"), (byte) 3, 20),
                row(5, "Z", 7.0, date("2020-12-31"), (byte) -1, null),
                row(6, "y", null, date("2024-01-02"), (byte) 4, 30));
        Schema u = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("f", DataType.VARCHAR),
                new Schema.Field("n", DataType.BIGINT));
        List<Object[]> uRows = List.of(row(1, "p", 100L), row(4, "q", null), row(null, "r", 7L), row(3, "p", 5L),
                row(3, "s", 6L));
        Schema w = Schema.of(new Schema.Field("m", DataType.decimal(5, 2)), new Schema.Field("s", DataType.SMALLINT),
                new Schema.Field("h", DataType.HUGEINT), new Schema.Field("g", DataType.BIGINT),
                new Schema.Field("fl", DataType.FLOAT), new Schema.Field("ts", DataType.TIMESTAMP));
        List<Object[]> wRows = List.of(
                row(new BigDecimal("1.25"), (short) 7, BigInteger.valueOf(5), 6L, 1.5f, timestamp("2024-01-02T03:04:05")),
                row(new BigDecimal("-3.50"), (short) -2, null, 9_000_000_000L, null, null),
                row(null, null, new BigInteger("12345678901234567890"), -1L, 2.25f, timestamp("2023-12-31T23:59:59")),
                row(new BigDecimal("0.00"), (short) 3, BigInteger.ONE, 3L, -0.5f, timestamp("2024-05-06T07:08:09")));
        DataType.Struct inner = DataType.struct(new DataType.Struct.Field("x", DataType.DOUBLE),
                new DataType.Struct.Field("tags", DataType.listOf(DataType.VARCHAR)));
        Schema n = Schema.of(new Schema.Field("id", DataType.INTEGER),
                new Schema.Field("s", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
                        new DataType.Struct.Field("b", DataType.VARCHAR),
                        new DataType.Struct.Field("name", DataType.VARCHAR), new DataType.Struct.Field("c", inner))),
                new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
                new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.INTEGER)),
                new Schema.Field("ll", DataType.listOf(DataType.listOf(DataType.INTEGER))));
        List<Object[]> nRows = List.of(
                row(1, list(1, "p", "Ann", list(1.5, list("x", "y"))), list(3, 1, 2), map("a", 1, "b", 2),
                        list(list(1, 2), list(3))),
                row(2, null, null, null, null),
                row(3, list(null, "q", null, list(null, null)), list(), map(), list(list())),
                row(4, list(4, null, "Bob", null), list(null, 5, 5), map("a", null, "c", 3),
                        list(null, list(4, null))),
                row(5, list(-2, "p", "Cid", list(-0.5, list())), list(7), map("b", 9), list(list(7, 8, 9))));
        MemoryConnector.Builder builder = MemoryConnector.builder(source).table("t", t, tRows).table("u", u, uRows)
                .table("w", w, wRows);
        return nested ? builder.table("n", n, nRows) : builder;
    }

    private static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }

    private static Map<Object, Object> map(Object... keysAndValues) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** Copies the four tables of an engine into DuckDB under their plain names. */
    static void load(FyrQL engine, DuckDbReference duck) {
        for (String table : NAMES) {
            PlanNode.Scan scan = engine.scan(TableRef.of(SOURCE, table));
            duck.load(table, scan.schema(), engine.batches(scan));
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static Integer date(String s) {
        return (int) LocalDate.parse(s).toEpochDay();
    }

    private static Long timestamp(String s) {
        LocalDateTime t = LocalDateTime.parse(s);
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1_000;
    }
}
