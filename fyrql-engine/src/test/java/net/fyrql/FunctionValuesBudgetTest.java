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

package net.fyrql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lists of {@code range} and {@code generate_series} count against the memory budget of the query. */
class FunctionValuesBudgetTest {

    @Test
    void aSeriesBeyondTheBudgetFailsTheQueryRatherThanTheHeap() {
        try (FyrQL engine = FyrQL.builder().memoryBudget(64L << 20).threads(4).build()) {
            assertEquals(List.of(1000L, 2000L), engine.rows("SELECT len(range(i * 1000)) FROM range(1, 3) t(i)")
                    .stream().map(r -> r[0]).toList());
            for (String sql : List.of("SELECT len(range(i * 100000000)) FROM range(1, 4) t(i)",
                    "SELECT len(generate_series(1, i * 100000000, 1)) FROM range(1, 4) t(i)",
                    "SELECT len(generate_series(TIMESTAMP '2000-01-01', TIMESTAMP '2100-01-01', INTERVAL 1 SECOND))")) {
                RuntimeException e = assertThrows(RuntimeException.class, () -> engine.rows(sql));
                assertTrue(e.getMessage().contains("Memory budget of 67108864 bytes exceeded"), e.getMessage());
            }
            // The bytes are given back once the batch is computed.
            assertEquals(List.of(3000L), engine.rows("SELECT len(range(3000))").stream().map(r -> r[0]).toList());
        }
    }
}
