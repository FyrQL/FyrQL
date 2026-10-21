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

package net.fyrql.connector.iceberg;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.iceberg.data.Record;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;

/**
 * Rows of the tests in the Java values of FyrQL: {@code Integer} days for a
 * {@code DATE}, {@code Long} microseconds for {@code TIME} and the
 * timestamps, {@code byte[]} for a {@code BLOB}, a {@code List} for a
 * {@code STRUCT}; and as sorted text, to compare rows as multisets.
 */
public final class IcebergRows {

    private IcebergRows() {
    }

    /** Returns the rows of Iceberg Java in the values of FyrQL. */
    public static List<List<Object>> of(List<Record> records) {
        List<List<Object>> rows = new ArrayList<>();
        for (Record r : records) {
            rows.add(IcebergTestTables.values(r).stream().map(IcebergRows::fyrql).toList());
        }
        return rows;
    }

    /** Returns a value of Iceberg Java as the value FyrQL reads. */
    public static Object fyrql(Object v) {
        return switch (v) {
            case null -> null;
            case LocalDate d -> Math.toIntExact(d.toEpochDay());
            case LocalTime t -> t.toNanoOfDay() / 1000;
            case LocalDateTime t -> micros(t.toInstant(ZoneOffset.UTC));
            case OffsetDateTime t -> micros(t.toInstant());
            case ByteBuffer b -> {
                byte[] bytes = new byte[b.remaining()];
                b.duplicate().get(bytes);
                yield bytes;
            }
            case Record r -> IcebergTestTables.values(r).stream().map(IcebergRows::fyrql).toList();
            case List<?> l -> l.stream().map(IcebergRows::fyrql).toList();
            case Map<?, ?> m -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                m.forEach((k, value) -> map.put(fyrql(k), fyrql(value)));
                yield map;
            }
            default -> v;
        };
    }

    private static long micros(Instant i) {
        return i.getEpochSecond() * 1_000_000L + i.getNano() / 1000;
    }

    /** Returns rows as sorted text, so that equal multisets of rows give equal lists. */
    public static List<String> sorted(List<List<Object>> rows) {
        List<String> text = new ArrayList<>();
        for (List<Object> row : rows) {
            text.add(String.join(" | ", row.stream().map(IcebergRows::text).toList()));
        }
        text.sort(null);
        return text;
    }

    /** Returns a value as text: bytes in hexadecimal, maps by their keys, decimals without exponent. */
    public static String text(Object v) {
        return switch (v) {
            case null -> "NULL";
            case byte[] b -> "x'" + HexFormat.of().formatHex(b) + "'";
            case BigDecimal d -> d.toPlainString();
            case List<?> l -> "[" + String.join(", ", l.stream().map(IcebergRows::text).toList()) + "]";
            case Map<?, ?> m -> {
                Map<String, String> sorted = new TreeMap<>();
                m.forEach((k, value) -> sorted.put(text(k), text(value)));
                yield sorted.toString();
            }
            default -> v.toString();
        };
    }

    /** Reads all columns of a table of a connector, with a split after another. */
    public static List<List<Object>> read(Connector connector, TableRef table) {
        int columns = connector.catalog().schema(table).size();
        List<Integer> all = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            all.add(c);
        }
        return read(connector, table, ScanRequest.columns(all));
    }

    /** Reads what a request asks for of a table of a connector. */
    public static List<List<Object>> read(Connector connector, TableRef table, ScanRequest request) {
        connector.plan(table, request);
        ScanPlan plan = connector.plan(table, request);
        List<List<Object>> rows = new ArrayList<>();
        for (Split split : connector.splits(plan)) {
            try (BatchReader reader = connector.open(split)) {
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    for (int r = 0; r < b.rowCount(); r++) {
                        List<Object> row = new ArrayList<>();
                        for (int c = 0; c < b.schema().size(); c++) {
                            row.add(b.column(c).get(r));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }
}
