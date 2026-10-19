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

import net.fyrql.core.vector.Batch;
import net.fyrql.jdbc.AllTypesTable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Rows of the tests and their comparison. */
final class TestRows {

    /** The largest {@code HUGEINT} that fits {@code Decimal128(38, 0)}. */
    static final BigInteger MAX_DECIMAL_38 = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);

    private TestRows() {
    }

    /**
     * Returns the rows of {@link AllTypesTable} with the largest
     * {@code HUGEINT}, which has 39 digits, replaced by the largest one of 38
     * digits.
     */
    static List<Object[]> allTypes() {
        int hi = AllTypesTable.SCHEMA.indexOf("hi");
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : AllTypesTable.ROWS) {
            Object[] copy = row.clone();
            if (copy[hi] != null && ((BigInteger) copy[hi]).bitLength() > 126) {
                copy[hi] = MAX_DECIMAL_38;
            }
            rows.add(copy);
        }
        return rows;
    }

    /** Returns the rows of batches with values that compare by content, such as the bytes of a {@code BLOB}. */
    static List<List<Object>> comparable(List<Batch> batches) {
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : batches) {
            rows.addAll(comparableRows(b.rows()));
        }
        return rows;
    }

    /** Returns rows with values that compare by content. */
    static List<List<Object>> comparableRows(List<Object[]> rows) {
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Object> values = new ArrayList<>(row.length);
            for (Object v : row) {
                values.add(comparable(v));
            }
            out.add(values);
        }
        return out;
    }

    /** Returns a value that compares by content: bytes as hex, a map as its list of entries in order. */
    static Object comparable(Object value) {
        return switch (value) {
            case null -> null;
            case byte[] bytes -> "0x" + HexFormat.of().formatHex(bytes);
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                for (Object v : list) {
                    out.add(comparable(v));
                }
                yield out;
            }
            case Map<?, ?> map -> {
                List<Object> out = new ArrayList<>(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    List<Object> entry = new ArrayList<>(2);
                    entry.add(comparable(e.getKey()));
                    entry.add(comparable(e.getValue()));
                    out.add(entry);
                }
                yield new MapValue(out);
            }
            default -> value;
        };
    }

    /** A map as its entries in order, each a list of key and value. */
    record MapValue(List<Object> entries) {
    }
}
