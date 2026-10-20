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
package net.fyrql.connector;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobsTest {

    @Test
    void aStarIsAnyTextAndAQuestionMarkOneCharacter() {
        Pattern p = Globs.regex("orders-*.parquet");
        assertTrue(p.matcher("orders-.parquet").matches());
        assertTrue(p.matcher("orders-2026-10.parquet").matches());
        assertFalse(p.matcher("orders-1.parquetx").matches());
        assertFalse(p.matcher("Orders-1.parquet").matches());
        Pattern q = Globs.regex("p?rt[1].(x)+");
        assertTrue(q.matcher("part[1].(x)+").matches());
        assertFalse(q.matcher("prt[1].(x)+").matches());
        assertFalse(q.matcher("part1.xx").matches());
        assertTrue(Globs.regex("*").matcher("a\nb").matches());
    }

    @Test
    void aPatternIsOnlyAllowedInTheLastPart() {
        assertTrue(Globs.isPattern("a*"));
        assertTrue(Globs.isPattern("a?"));
        assertFalse(Globs.isPattern("a.csv"));
        assertEquals(6, Globs.lastSeparator("s3://b/k"));
        assertEquals(8, Globs.lastSeparator("s3://b/d/"));
        assertEquals(2, Globs.lastSeparator("C:\\x.csv"));
        assertEquals(-1, Globs.lastSeparator("x.csv"));
        assertDoesNotThrow(() -> Globs.checkPattern("s3://b/dir/*.parquet"));
        assertDoesNotThrow(() -> Globs.checkPattern("*.parquet"));
        assertEquals("A pattern is only supported in the name of a file: s3://b/d*/x.parquet",
                assertThrows(IllegalArgumentException.class, () -> Globs.checkPattern("s3://b/d*/x.parquet"))
                        .getMessage());
    }
}
