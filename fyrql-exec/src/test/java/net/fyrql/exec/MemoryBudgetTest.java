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

package net.fyrql.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBudgetTest {

    @Test
    void reserveFailsAndTryReserveDeclinesAboveTheLimit() {
        MemoryBudget budget = new MemoryBudget(100);
        budget.reserve(60, "Sort#1");
        assertEquals(60, budget.used());
        assertFalse(budget.tryReserve(41));
        assertEquals(60, budget.used(), "a declined reservation takes nothing");
        assertTrue(budget.tryReserve(40));
        assertEquals(100, budget.used());
        QueryException e = assertThrows(QueryException.class, () -> budget.reserve(1, "Aggregate#2"));
        assertEquals("Memory budget of 100 bytes exceeded: 101 bytes needed in Aggregate#2", e.getMessage());
        assertTrue(budget.tryReserve(0));
        budget.release(70);
        assertEquals(30, budget.used());
        budget.release(1000);
        assertEquals(0, budget.used());
        budget.reserveAnyway(130);
        assertEquals(130, budget.used(), "taken beyond the limit");
        assertFalse(budget.tryReserve(0) && budget.tryReserve(1));
        budget.release(130);
        assertThrows(IllegalArgumentException.class, () -> budget.reserveAnyway(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1, "Sort#1"));
        assertThrows(IllegalArgumentException.class, () -> new MemoryBudget(0));
    }

    @Test
    void anUnlimitedBudgetTakesEverything() {
        MemoryBudget budget = MemoryBudget.unlimited();
        assertTrue(budget.tryReserve(Long.MAX_VALUE / 2));
        assertTrue(budget.tryReserve(Long.MAX_VALUE / 2));
        assertEquals(Long.MAX_VALUE - 1, budget.used());
    }
}
