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

class StylesTest {

    private static final Styles.Kind N = Styles.Kind.NUMBER;
    private static final Styles.Kind D = Styles.Kind.DATE;
    private static final Styles.Kind T = Styles.Kind.TIME;
    private static final Styles.Kind S = Styles.Kind.TIMESTAMP;

    /** Format codes of their own and the types DuckDB 1.5.6 gives a number with each. */
    @Test
    void formatCodesAsDuckDbJudgesThem() {
        Object[][] cases = {
            {"[h]:mm:ss", T}, {"[hh]:mm", T}, {"[=0]?;[hh]:mm", T}, {"[Red]0.00", N}, {"0.00;[Red]-0.00", N},
            {"[$-409]0.00", N}, {"[$-409]mmmm d, yyyy", D}, {"mm:ss", N}, {"\"d\"0", N}, {"\"h\"0", T},
            {"0 \\d", N}, {"0.00 \\h", T}, {"yyyy-mm-dd", D}, {"dd/mm/yyyy hh:mm", S}, {"hh:mm:ss", T},
            {"General", N}, {"@", N}, {"[Blue]General", N}, {"0;[Red]yyyy", D}, {"#,##0 \"days\"", N},
            {"[m]:ss", N}, {"[s]", N}, {"ss", N}, {"d", N}, {"y", N}, {"h", T}, {"AM/PM", N}, {"yyyy;@", D},
            {"m/d/yy h:mm;@", S}, {"[$-F400]h:mm:ss AM/PM", T}, {"[$-F800]dddd, mmmm dd, yyyy", D}, {"e", N},
            {"B2yyyy", D}, {"_(* #,##0_);_(* (#,##0);_(* \"-\"_);_(@_)", N}, {"0%", N}, {"0.00E+00", N},
            {"# ?/?", N}, {"[<1]0.0;0", N},
            {"dd", D}, {"ddd", D}, {"dddd", D}, {"d/m", N}, {"m/d", N}, {"mmm", N}, {"mmmm", N}, {"mmm-yy", D},
            {"d-mmm", N}, {"yy", D}, {"Dd", N}, {"YY", D}, {"DD", D}, {"YYYY", D}, {"\"dd\"0", D}, {"[dd]0", D},
            {"mm/dd", D}, {"dd mm", D}, {"d mmm", N}, {"m/d/yy", D}, {"m/yyyy", D}, {"mmmmm", N}, {"H", T},
            {"HH:MM", T}, {"[H]:MM", T}, {"ddmm", D}, {"yyyymmdd", D}, {"d.m.", N}, {"mm-dd", D}, {"gee", N},
            {"e/m/d", N}, {"\\d\\d0", N}, {"0 \"yy\"", D},
        };
        for (Object[] c : cases) {
            assertEquals(c[1], Styles.kind((String) c[0]), (String) c[0]);
        }
    }

    @Test
    void builtInFormats() {
        for (int id = 0; id < 200; id++) {
            Styles.Kind expected = id >= 14 && id <= 17 ? D : id >= 18 && id <= 21 ? T : id == 22 ? S : N;
            assertEquals(expected, Styles.kind(id, null), "numFmtId " + id);
        }
        assertEquals(D, Styles.kind(14, "General"));
        assertEquals(T, Styles.kind(164, "h:mm"));
        assertEquals(N, Styles.kind(46, null));
    }
}
