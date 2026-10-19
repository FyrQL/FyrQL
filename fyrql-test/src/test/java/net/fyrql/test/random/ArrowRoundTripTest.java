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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.ArrowTypeException;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The results of the random plans of {@link SqlRoundTripTest}, each batch
 * written into Arrow vectors and read back: the batch comes back with its
 * schema and its values, bit for bit. A plan that fails in FyrQL is left
 * out, and so is a result with a {@code HUGEINT} of 39 digits, which Arrow
 * cannot hold; the report counts both.
 */
class ArrowRoundTripTest {

    @Test
    void randomResultsComeBackFromArrow() {
        int checked = 0;
        int failed = 0;
        int tooLarge = 0;
        long rows = 0;
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED));
                BufferAllocator allocator = new RootAllocator()) {
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                PlanNode plan = new PlanGenerator(RandomRun.random(i)).plan(3);
                List<Batch> batches;
                try {
                    batches = engine.batches(plan);
                } catch (RuntimeException | StackOverflowError e) {
                    failed++;
                    continue;
                }
                try (VectorSchemaRoot root = BatchToArrow.root(plan.schema(), allocator)) {
                    for (Batch b : batches) {
                        BatchToArrow.fill(b, root);
                        Batch back = ArrowToBatch.batch(root);
                        assertEquals(b.schema().types(), back.schema().types(), "query " + i);
                        assertEquals(comparable(b.rows()), comparable(back.rows()), "query " + i);
                        rows += b.rowCount();
                    }
                    checked++;
                } catch (ArrowTypeException e) {
                    assertTrue(e.getMessage().contains("has more than 38 digits"), "query " + i + ": " + e);
                    tooLarge++;
                }
            }
        }
        System.out.println("Random results through Arrow: " + checked + " plans with " + rows + " rows back, "
                + failed + " failed in FyrQL, " + tooLarge + " with a HUGEINT of 39 digits");
        assertTrue(checked > RandomRun.QUERIES / 2, checked + " of " + RandomRun.QUERIES + " plans checked");
    }

    /** Returns rows with values that compare by content: bytes as hex, a map as its list of entries in order. */
    private static List<Object> comparable(List<Object[]> rows) {
        List<Object> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(comparable(Arrays.asList(row)));
        }
        return out;
    }

    private static Object comparable(Object value) {
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
                    out.add(Arrays.asList(comparable(e.getKey()), comparable(e.getValue())));
                }
                yield Map.entry("map", out);
            }
            default -> value;
        };
    }
}
