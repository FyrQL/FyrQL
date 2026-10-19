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

package net.fyrql.sql.parse;

import net.fyrql.sql.ast.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceTextTest {

    @Test
    void rewritesOnlyOutsideLiteralsAndComments() {
        assertEquals("SELECT a DIV b, 'x//y', \"p//q\", $$r//s$$ -- t//u\n/* v//w */ = ",
                SourceText.of("SELECT a//b, 'x//y', \"p//q\", $$r//s$$ -- t//u\n/* v//w */ == ").text());
        assertEquals("SELECT 'it''s//' DIV 2, \"a\"\"//\" DIV 3",
                SourceText.of("SELECT 'it''s//'//2, \"a\"\"//\"//3").text());
        assertEquals("a <= b, a >= b, a != b, a === b", SourceText.of("a <= b, a >= b, a != b, a === b").text());
        assertEquals("SELECT \"div\", \"DIV\", divide, t.\"Div\", xdiv, div_x, \"div\"",
                SourceText.of("SELECT div, DIV, divide, t.Div, xdiv, div_x, \"div\"").text());
    }

    @Test
    void mapsPositionsBack() {
        SourceText s = SourceText.of("SELECT a//b,\n  div, x == y\r\n\tWHERE");
        // The rewritten text is "SELECT a DIV b,\n  \"div\", x = y\r\n\tWHERE".
        assertEquals(new Position(1, 9), s.position(1, 10));
        assertEquals(new Position(1, 11), s.position(1, 14));
        assertEquals(new Position(2, 3), s.position(2, 3));
        assertEquals(new Position(2, 8), s.position(2, 10));
        assertEquals(new Position(2, 10), s.position(2, 12));
        assertEquals(new Position(2, 13), s.position(2, 14));
        assertEquals(new Position(3, 2), s.position(3, 2));
        assertEquals(new Position(3, 7), s.end());
    }
}
