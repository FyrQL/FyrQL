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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.arrow.ArrowResults;
import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.ArrowTypes;
import net.fyrql.arrow.FyrQLArrowReader;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TPC-H queries of {@link TpchQueries} at scale factor 0.01 as Arrow
 * vectors, over the five sources of {@link TpchEngine}: each result read
 * through {@link ArrowResults#reader} and back into batches gives the rows
 * of the engine, in its order, with the schema of its plan.
 */
class TpchArrowTest {

    private static FyrQL engine;
    private static BufferAllocator allocator;

    @BeforeAll
    static void setUp() {
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, TpchTest.directory(TpchEngine.SMALL));
        engine = tpch.open(null, null);
        allocator = new RootAllocator();
    }

    @AfterAll
    static void tearDown() {
        if (allocator != null) {
            allocator.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    static List<TpchQueries.Query> queries() {
        return TpchQueries.ALL;
    }

    @ParameterizedTest
    @MethodSource("queries")
    void theQueryGivesTheRowsOfTheEngine(TpchQueries.Query query) throws IOException {
        PlanNode plan = engine.plan(query.sql());
        List<Object[]> expected = engine.rows(plan);
        assertFalse(expected.isEmpty(), query.name() + " gives no rows");
        List<Object[]> actual = new ArrayList<>();
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, query.sql(), allocator)) {
            assertEquals(ArrowTypes.toArrow(plan.schema()), reader.getVectorSchemaRoot().getSchema());
            while (reader.loadNextBatch()) {
                Batch batch = ArrowToBatch.batch(reader.getVectorSchemaRoot());
                assertEquals(plan.schema(), batch.schema(), query.name());
                actual.addAll(batch.rows());
            }
        }
        Optional<String> differences = TpchQueries.compare(plan, expected, actual);
        if (differences.isPresent()) {
            fail(query.name() + " through Arrow differs from the engine:\n" + differences.get() + "\n"
                    + engine.explain(plan));
        }
        assertEquals(0, allocator.getAllocatedMemory(), "the reader released its vectors");
    }
}
