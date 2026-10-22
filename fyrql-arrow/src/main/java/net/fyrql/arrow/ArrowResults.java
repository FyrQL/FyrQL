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

package net.fyrql.arrow;

import net.fyrql.FyrQL;
import net.fyrql.PreparedQuery;
import net.fyrql.QueryResult;
import net.fyrql.Session;
import org.apache.arrow.memory.BufferAllocator;

import java.util.List;

/**
 * Runs queries of an engine with their results as Arrow vectors:
 * <pre>{@code
 * try (BufferAllocator allocator = new RootAllocator();
 *         FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT * FROM t", allocator)) {
 *     while (reader.loadNextBatch()) {
 *         VectorSchemaRoot root = reader.getVectorSchemaRoot();
 *         // use the batch in root
 *     }
 * }
 * }</pre>
 * Arrow needs the JVM option
 * {@code --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED}.
 */
public final class ArrowResults {

    private ArrowResults() {
    }

    /**
     * Runs a query; the caller closes the reader.
     *
     * @throws net.fyrql.sql.SqlException if the query cannot be parsed or bound
     */
    public static FyrQLArrowReader reader(FyrQL engine, String sql, BufferAllocator allocator) {
        return reader(engine.sql(sql), allocator);
    }

    /**
     * Runs a query in a session, with its time zone and search path, see
     * {@link Session#sql(String)}; the caller closes the reader.
     *
     * @throws net.fyrql.sql.SqlException if the query cannot be parsed or bound
     */
    public static FyrQLArrowReader reader(Session session, String sql, BufferAllocator allocator) {
        return reader(session.sql(sql), allocator);
    }

    /**
     * Runs a prepared query with values for its parameters, see
     * {@link FyrQL#execute(PreparedQuery, List)}; the caller closes the reader.
     *
     * @throws IllegalArgumentException if the values do not fit the parameters
     * @throws net.fyrql.sql.SqlException if the query does not bind any more
     */
    public static FyrQLArrowReader reader(FyrQL engine, PreparedQuery query, List<?> values,
            BufferAllocator allocator) {
        return reader(engine.execute(query, values), allocator);
    }

    /** Reads a result that is already running; closing the reader closes it. */
    public static FyrQLArrowReader reader(QueryResult result, BufferAllocator allocator) {
        return new FyrQLArrowReader(result, allocator);
    }
}
