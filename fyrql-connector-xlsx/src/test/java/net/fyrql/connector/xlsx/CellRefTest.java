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

package net.fyrql.connector.xlsx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CellRefTest {

    @Test
    void references() {
        assertCell(0, 0, "A1");
        assertCell(1, 11, "B12");
        assertCell(26, 0, "aa1");
        assertCell(16_383, 1_048_575, "XFD1048576");
        for (String bad : new String[] {"", "A", "1", "A0", "XFE1", "A1048577", "1A", "A1B", "AAAA1", "A-1",
            "A99999999999"}) {
            assertEquals(-1, CellRef.parseCell(bad), bad);
        }
    }

    @Test
    void columnNames() {
        assertEquals("A", CellRef.columnName(0));
        assertEquals("Z", CellRef.columnName(25));
        assertEquals("AA", CellRef.columnName(26));
        assertEquals("AZ", CellRef.columnName(51));
        assertEquals("BA", CellRef.columnName(52));
        assertEquals("XFD", CellRef.columnName(16_383));
        for (int c = 0; c < CellRef.MAX_COLUMNS; c++) {
            String name = CellRef.columnName(c);
            assertEquals(c, CellRef.columnIndex(name, 0, name.length()));
        }
        assertEquals("C7", CellRef.name(2, 6));
    }

    @Test
    void ranges() {
        assertEquals(new CellRef.Range(2, 2, 3, 4), CellRef.Range.parse("C3:D5"));
        assertEquals(new CellRef.Range(0, 0, 0, 0), CellRef.Range.parse("A1:A1"));
        assertEquals(new CellRef.Range(2, 0, 3, CellRef.MAX_ROWS - 1), CellRef.Range.parse("C:D"));
        assertEquals(2, CellRef.Range.parse("C:D").width());
        for (String bad : new String[] {"D4", "Z9:A1", "B1:A1", "A2:A1", "Sheet1!A1:B2", "XFE1:XFE2", "nonsense",
            "A1:", ":B2", "D:C"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CellRef.Range.parse(bad));
            assertEquals("Invalid range '" + bad + "' specified", e.getMessage());
        }
    }

    private static void assertCell(int column, int row, String ref) {
        long cell = CellRef.parseCell(ref);
        assertEquals(column, CellRef.column(cell), ref);
        assertEquals(row, CellRef.row(cell), ref);
    }
}
