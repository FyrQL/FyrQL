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

package net.fyrql.examples.customconnector;

import net.fyrql.FyrQL;
import net.fyrql.config.FyrQLConfig;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Queries the source of the type {@code sequence}, which the configuration file names, and shows for each query what
 * the source took over and how many rows it computed.
 */
public final class CustomConnector {

    private CustomConnector() {
    }

    public static void main(String[] args) {
        // The engine finds the type sequence through the ServiceLoader, like those of FyrQL.
        try (FyrQL engine = FyrQLConfig.open(Path.of("fyrql.yaml"))) {
            SequenceConnector seq = (SequenceConnector) engine.connector("seq");
            // BETWEEN narrows the range: five rows are computed.
            run(engine, seq, "SELECT n, square FROM seq.numbers WHERE n BETWEEN 10 AND 14 ORDER BY n");
            // 1000 >= n narrows the range; the source declines even, which the engine evaluates.
            run(engine, seq, "SELECT count(*) AS evens, sum(square) AS total FROM seq.numbers"
                    + " WHERE even AND 1000 >= n");
            // The source declines OR, so all rows are computed, on several threads.
            run(engine, seq, "SELECT n, square FROM seq.numbers WHERE n = 7 OR n = 99999 ORDER BY n");
        }
    }

    private static void run(FyrQL engine, SequenceConnector seq, String sql) {
        System.out.println("== " + sql);
        System.out.print(engine.explain(sql));
        long before = seq.computedRows();
        for (Object[] row : engine.rows(sql)) {
            System.out.println(Arrays.toString(row));
        }
        System.out.println("Computed " + (seq.computedRows() - before) + " rows");
    }
}
