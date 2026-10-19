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

package net.fyrql.connector.odata;

import net.fyrql.connector.BatchReader;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Reads the one row of {@code count(*)}, once or more often, that the
 * service counts: it asks for the number when the first batch is wanted.
 */
final class CountReader implements BatchReader {

    private final Schema output;
    private final LongSupplier count;
    private boolean done;

    /**
     * @param output the columns of the row, each a {@code BIGINT}
     * @param count asks the service for the number
     */
    CountReader(Schema output, LongSupplier count) {
        this.output = Objects.requireNonNull(output, "output");
        this.count = Objects.requireNonNull(count, "count");
    }

    @Override
    public Batch next() {
        if (done) {
            return null;
        }
        done = true;
        Object[] row = new Object[output.size()];
        long n = count.getAsLong();
        for (int c = 0; c < row.length; c++) {
            row[c] = n;
        }
        return Batch.ofRows(output, List.<Object[]>of(row));
    }
}
