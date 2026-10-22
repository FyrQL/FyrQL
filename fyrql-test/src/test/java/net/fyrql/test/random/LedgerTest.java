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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The entries of the ledger, against reports in the form the random tests write them. */
class LedgerTest {

    private static final Ledger LEDGER = new Ledger("1.5.6");

    private static Outcome rowsDiffer(String sql, String expected, String actual) {
        String report = "row 0 differs at column 0 (s): expected [" + expected + "], actual [" + actual + "]\n"
                + "expected 1 rows, actual 1\n"
                + "  missing: [" + expected + "]\n"
                + "  extra:   [" + actual + "]\n";
        return new Outcome(Outcome.Kind.ROWS_DIFFER, sql, null, null, report);
    }

    private static String explained(Outcome o) {
        Ledger.Entry e = LEDGER.explain(o);
        return e == null ? null : e.id();
    }

    @Test
    void equalIntervalsThatStringAggJoinsInAnotherOrderAreTies() {
        String sql = "SELECT string_agg(eiv, ' | ' ORDER BY eiv DESC NULLS LAST) FROM e";
        assertEquals("interval-ties", explained(rowsDiffer(sql, "'2 days | 30 days | 720:00:00 | 1 month | 1 day'",
                "'2 days | 1 month | 720:00:00 | 30 days | 1 day'")));
        assertEquals("interval-ties", explained(rowsDiffer("SELECT string_agg(eiv, ',' ORDER BY eiv) FROM e",
                "'1 day,30 days,1 month', 3", "'1 day,1 month,30 days', 3")));
        // Another order of intervals that differ is no tie.
        assertNull(explained(rowsDiffer(sql, "'30 days | 1 day'", "'1 day | 30 days'")));
        // Neither is another text of the same interval.
        assertNull(explained(rowsDiffer(sql, "'30 days | 1 day'", "'1 month | 1 day'")));
        // Nor equal intervals in a string that no aggregate joined.
        assertNull(explained(rowsDiffer("SELECT CAST(eiv AS VARCHAR) FROM e", "'30 days'", "'1 month'")));
    }

    @Test
    void anArgumentOfAWindowFunctionWithoutACastFailsInFyrqlOnly() {
        for (String function : new String[] {"ntile", "nth_value", "lag", "lead"}) {
            Outcome o = new Outcome(Outcome.Kind.FYRQL_FAILS, "SELECT 1",
                    "SqlException: No function matches " + function + " with an argument of type DATE at line 1,"
                            + " column 30", null, "DuckDB gives rows: 0\n");
            assertEquals("window-argument-without-cast", explained(o));
        }
    }
}
