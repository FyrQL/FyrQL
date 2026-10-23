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

package net.fyrql.test;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows of {@code rows()}, which have the dates and times of
 * {@code java.time}, in the form of the vectors of a batch, for tests that
 * compare them with the rows of a batch or write them as literals.
 */
public final class Rows {

    private Rows() {
    }

    /** Returns new rows with each value in the form of a vector of the type of its column. */
    public static List<Object[]> internal(Schema schema, List<Object[]> rows) {
        List<DataType> types = schema.types();
        List<Object[]> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Object[] copy = new Object[row.length];
            for (int c = 0; c < row.length; c++) {
                copy[c] = Values.fromJava(row[c], types.get(c));
            }
            result.add(copy);
        }
        return result;
    }
}
