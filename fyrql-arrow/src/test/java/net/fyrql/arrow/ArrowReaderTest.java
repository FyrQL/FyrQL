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
import net.fyrql.catalog.SearchPath;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryCancelledException;
import net.fyrql.jdbc.AllTypesTable;
import net.fyrql.sql.SqlException;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Results of queries as {@link FyrQLArrowReader}: the schema, the batches,
 * an empty result, cancelling, failures, and that closing releases all
 * memory of Arrow (the allocator of each test fails on a leak when it is
 * closed).
 */
class ArrowReaderTest {

    private static final int NUMBERS = 1000;

    private FyrQL engine;
    private BufferAllocator allocator;

    @BeforeEach
    void open() {
        List<Object[]> numbers = new ArrayList<>();
        for (long i = 0; i < NUMBERS; i++) {
            numbers.add(new Object[] {i, "n" + i});
        }
        engine = FyrQL.builder()
                .register(MemoryConnector.builder("mem")
                        .table("t", AllTypesTable.SCHEMA, TestRows.allTypes())
                        .table("big", AllTypesTable.SCHEMA, AllTypesTable.ROWS)
                        .table("numbers", Schema.of(new Schema.Field("n", DataType.BIGINT),
                                new Schema.Field("s", DataType.VARCHAR)), numbers)
                        .batchSize(64)
                        .build())
                .build();
        allocator = new RootAllocator();
    }

    @AfterEach
    void close() {
        allocator.close();
        engine.close();
    }

    private static List<Batch> readAll(FyrQLArrowReader reader) throws IOException {
        List<Batch> batches = new ArrayList<>();
        while (reader.loadNextBatch()) {
            batches.add(ArrowToBatch.batch(reader.getVectorSchemaRoot()));
        }
        return batches;
    }

    @Test
    void theReaderGivesTheRowsOfTheEngineWithTheSchemaOfThePlan() throws IOException {
        String sql = "SELECT * FROM t";
        PlanNode plan = engine.plan(sql);
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, sql, allocator)) {
            assertEquals(ArrowTypes.toArrow(plan.schema()), reader.getVectorSchemaRoot().getSchema());
            List<Batch> batches = readAll(reader);
            assertEquals(TestRows.comparableRows(engine.rows(plan)), TestRows.comparable(batches));
            assertEquals(plan.schema(), batches.get(0).schema());
            assertEquals(3, reader.rowCount());
            assertTrue(reader.bytesRead() > 0);
        }
    }

    @Test
    void eachBatchOfTheQueryIsABatchOfTheReader() throws IOException {
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT n, s FROM numbers WHERE n % 2 = 0",
                allocator)) {
            long sum = 0;
            int batches = 0;
            while (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                BigIntVector n = (BigIntVector) root.getVector("n");
                for (int i = 0; i < root.getRowCount(); i++) {
                    sum += n.get(i);
                }
                batches++;
            }
            assertTrue(batches > 1, batches + " batches");
            assertEquals(NUMBERS / 2, reader.rowCount());
            assertEquals(249_500, sum);
        }
    }

    @Test
    void anEmptyResultHasItsSchemaAndNoBatch() throws IOException {
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT li, m FROM t WHERE i > 100",
                allocator)) {
            assertEquals(List.of("li", "m"), reader.getVectorSchemaRoot().getSchema().getFields().stream()
                    .map(f -> f.getName()).toList());
            assertFalse(reader.loadNextBatch());
            assertEquals(0, reader.getVectorSchemaRoot().getRowCount());
            assertEquals(0, reader.rowCount());
        }
    }

    @Test
    void aSearchPathResolvesTheNames() throws IOException {
        try (FyrQL two = FyrQL.builder()
                .register(MemoryConnector.builder("a").table("x", Schema.of(DataType.VARCHAR),
                        List.<Object[]>of(new Object[] {"from a"})).build())
                .register(MemoryConnector.builder("b").table("x", Schema.of(DataType.VARCHAR),
                        List.<Object[]>of(new Object[] {"from b"})).build())
                .build();
                FyrQLArrowReader reader = ArrowResults.reader(two, "SELECT * FROM x", new SearchPath("b", "main"),
                        allocator)) {
            assertEquals(List.of(List.of("from b")), TestRows.comparable(readAll(reader)));
        }
    }

    @Test
    void aPreparedQueryRunsWithItsValues() throws IOException {
        PreparedQuery query = engine.prepare("SELECT n, s FROM numbers WHERE n BETWEEN ? AND ? ORDER BY n");
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, query, List.of(10L, 12L), allocator)) {
            assertEquals(ArrowTypes.toArrow(query.schema()), reader.getVectorSchemaRoot().getSchema());
            assertEquals(TestRows.comparableRows(engine.rows("SELECT n, s FROM numbers WHERE n BETWEEN 10 AND 12 "
                    + "ORDER BY n")), TestRows.comparable(readAll(reader)));
        }
    }

    @Test
    void aRunningResultCanBeRead() throws IOException {
        QueryResult result = engine.sql("SELECT count(*) AS c FROM numbers");
        try (FyrQLArrowReader reader = ArrowResults.reader(result, allocator)) {
            assertEquals(List.of(List.of((long) NUMBERS)), TestRows.comparable(readAll(reader)));
        }
    }

    @Test
    void aCancelledQueryFailsOnTheNextBatch() throws IOException {
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT n FROM numbers", allocator)) {
            assertTrue(reader.loadNextBatch());
            reader.cancel();
            assertThrows(QueryCancelledException.class, () -> {
                while (reader.loadNextBatch()) {
                    // reads until the cancellation arrives
                }
            });
        }
    }

    @Test
    void failuresSurfaceFromLoadNextBatchAndTheReaderStillCloses() throws IOException {
        assertThrows(SqlException.class, () -> ArrowResults.reader(engine, "SELECT nope FROM t", allocator));
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT CAST(s AS INTEGER) FROM numbers",
                allocator)) {
            assertThrows(RuntimeException.class, reader::loadNextBatch);
        }
        try (FyrQLArrowReader reader = ArrowResults.reader(engine, "SELECT hi FROM big", allocator)) {
            ArrowTypeException e = assertThrows(ArrowTypeException.class, reader::loadNextBatch);
            assertEquals("hi", e.field());
        }
    }
}
