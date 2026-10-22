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

import net.fyrql.connector.SourceStatistics;
import net.fyrql.core.plan.PlanNode;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a query did so far, or in all once its result is closed: the
 * operators and scans under their labels, such as {@code Filter#2}, in
 * the order their operators were built, the inputs first.
 *
 * @param elapsed      the time since the query began, or until its result was closed
 * @param plan         the plan the query ran, {@code null} if none was opened
 * @param operators    what each operator did, under its label
 * @param sources      what each scan or part of the plan that a source runs read, under its label
 * @param spill        what the operators spilled to disk, together
 * @param compiled     the compiled expressions, keys and pipelines the operators asked for and got
 * @param compileNanos the time it took to get them, compiled or taken from the cache, in nanoseconds
 * @param threads      the threads the operators worked on
 * <p>
 * Part of the API; covered by the compatibility promise.
 */
public record QueryStatistics(Duration elapsed, PlanNode plan, Map<String, OperatorStatistics> operators,
        Map<String, SourceStatistics> sources, SpillStatistics spill, long compiled, long compileNanos,
        int threads) {

    public QueryStatistics {
        operators = Collections.unmodifiableMap(new LinkedHashMap<>(operators));
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    /** Returns what the operator of a label did, {@link OperatorStatistics#NONE} if it did nothing. */
    public OperatorStatistics operator(String label) {
        return operators.getOrDefault(label, OperatorStatistics.NONE);
    }

    /** Returns what the scan of a label read, {@link SourceStatistics#NONE} if it read nothing. */
    public SourceStatistics source(String label) {
        return sources.getOrDefault(label, SourceStatistics.NONE);
    }
}
