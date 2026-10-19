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

package net.fyrql.arrow;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream that another implementation of Arrow wrote: {@code types.arrows}
 * holds a column of each type pyarrow 13 offers that FyrQL reads, in two
 * batches of two rows and one; the first row has values, the second
 * {@code NULL}s, the third the limits of the types and empty lists and
 * maps. The {@code uuid} column is a {@code FixedSizeBinary(16)} marked as
 * {@code arrow.uuid}, which pyarrow 13 cannot write as an extension type
 * of its own, and {@code dict} is dictionary-encoded.
 */
class PyArrowStreamTest {

    private static final List<String> TYPES = List.of("BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
            "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "FLOAT", "FLOAT", "DOUBLE", "DECIMAL(38,10)",
            "DECIMAL(10,2)", "DECIMAL(20,4)", "VARCHAR", "VARCHAR", "BLOB", "BLOB", "BLOB", "UUID", "DATE", "DATE",
            "TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "INTEGER[]", "SMALLINT[]",
            "STRUCT(a INTEGER, b VARCHAR)", "MAP(VARCHAR, BIGINT)", "VARCHAR", "INTEGER", "BIGINT[][]");

    private static int day(String date) {
        return (int) LocalDate.parse(date).toEpochDay();
    }

    private static long micros(String timestamp) {
        return Conversions.toMicros(LocalDateTime.parse(timestamp));
    }

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

    @Test
    void theStreamOfPyArrowReadsAsWritten() throws IOException {
        List<Object[]> expected = List.of(
                new Object[] {true, Byte.MIN_VALUE, Short.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE, (short) 0, 0,
                        0L, BigInteger.ZERO, 1.5f, 1.5f, 2.5, new BigDecimal("1234567890123456789012345678.0123456789"),
                        new BigDecimal("12345678.90"), new BigDecimal("1234567890123456.7890"), "abc", "x",
                        new byte[] {(byte) 0xAA, 0}, new byte[] {1}, new byte[] {'a', 'b', 'c'},
                        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), day("2024-02-29"),
                        day("2024-02-29"), micros("2024-02-29T12:34:56"), micros("2024-02-29T12:34:56.789"),
                        micros("2024-02-29T12:34:56.123456"), 1709210096123456L, list(1, null, 3),
                        list((short) 1, (short) 2), list(1, "x"), map("k1", 1L, "k2", null), "red", null,
                        list(list(1L), list((Object) null), list())},
                new Object[TYPES.size()],
                new Object[] {false, Byte.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, (short) 255,
                        65535, 4294967295L, new BigInteger("18446744073709551615"), -2.0f, -0.25f, 1e300,
                        new BigDecimal("-9999999999999999999999999999.9999999999"), new BigDecimal("-0.01"),
                        new BigDecimal("-0.0001"), "\u00E4\u00F6\u20AC\uD83D\uDE00", "", new byte[0],
                        new byte[] {(byte) 0xFF}, new byte[] {0, 1, 2}, new UUID(0, 0), -1, day("1900-01-01"),
                        -1_000_000L, micros("1900-01-01T00:00"), 0L, -1L, list(), list(null, (short) 4),
                        list(null, null), map(), "red", null, list((Object) null)});
        List<Batch> batches = new ArrayList<>();
        try (BufferAllocator allocator = new RootAllocator();
                InputStream in = PyArrowStreamTest.class.getResourceAsStream("types.arrows");
                ArrowStreamReader reader = new ArrowStreamReader(in, allocator)) {
            while (reader.loadNextBatch()) {
                batches.add(ArrowToBatch.batch(reader.getVectorSchemaRoot(), reader));
            }
        }
        assertEquals(List.of(2, 1), batches.stream().map(Batch::rowCount).toList());
        assertEquals(TYPES, batches.get(0).schema().types().stream().map(DataType::sqlName).toList());
        assertTrue(batches.get(0).schema().fields().stream().allMatch(f -> f.nullable()));
        assertEquals(TestRows.comparableRows(expected), TestRows.comparable(batches));
    }
}
