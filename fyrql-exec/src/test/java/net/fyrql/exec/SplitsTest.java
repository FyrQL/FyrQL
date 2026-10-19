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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Batches;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of a scan, read one after the other, and the threads of the workers. */
class SplitsTest {

    private static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.INTEGER));

    /** A reader of the numbers of a part, which notes when it is opened, closed and canceled. */
    private static final class Part implements BatchReader {
        private final BatchReader numbers;
        private final List<String> log;
        private final int index;

        Part(int index, List<String> log) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < index; i++) {
                rows.add(new Object[] {index * 10 + i});
            }
            this.numbers = Batches.reader(rows.isEmpty() ? List.of() : List.of(Batch.ofRows(NUMBERS, rows)));
            this.log = log;
            this.index = index;
            log.add("open " + index);
        }

        @Override
        public Batch next() {
            return numbers.next();
        }

        @Override
        public void close() {
            log.add("close " + index);
        }

        @Override
        public void cancel() {
            log.add("cancel " + index);
        }
    }

    private static List<Object> values(BatchReader reader) {
        List<Object> values = new ArrayList<>();
        for (Batch b : Batches.readAll(reader)) {
            for (Object[] row : b.rows()) {
                values.add(row[0]);
            }
        }
        return values;
    }

    @Test
    void thePartsAreReadOneAfterTheOtherAndEachIsClosed() {
        List<String> log = new ArrayList<>();
        Splits splits = Splits.of(List.of(2, 0, 3), i -> new Part(i, log));
        assertEquals(3, splits.count());
        assertEquals(List.of(20, 21, 30, 31, 32), values(Splits.sequential(splits, new QueryContext())));
        assertEquals(List.of("open 2", "close 2", "open 0", "close 0", "open 3", "close 3"), log);
        assertThrows(IndexOutOfBoundsException.class, () -> splits.open(3));
    }

    @Test
    void aCanceledQueryOpensNoFurtherPart() {
        List<String> log = new ArrayList<>();
        QueryContext context = new QueryContext();
        BatchReader reader = Splits.sequential(Splits.of(List.of(1, 2), i -> new Part(i, log)), context);
        assertEquals(1, reader.next().rowCount());
        reader.cancel();
        context.cancel();
        assertNull(reader.next());
        reader.close();
        assertEquals(List.of("open 1", "cancel 1", "close 1"), log);
    }

    @Test
    void aSourceHasOnePartByDefault() {
        PlanNode.Scan scan = PlanNode.Scan.all(TableRef.of("t", "numbers"), NUMBERS);
        List<String> log = new ArrayList<>();
        TableSource source = s -> new Part(4, log);
        Splits splits = source.splits(scan, QueryContext.NONE);
        assertEquals(1, splits.count());
        assertEquals(List.of(40, 41, 42, 43), values(splits.open(0)));
        assertThrows(IndexOutOfBoundsException.class, () -> splits.open(1));
    }

    @Test
    void workersAreDaemonThreadsOfTheirOwn() throws Exception {
        Future<Thread> worker = Workers.submit(Thread::currentThread);
        Thread t = worker.get(10, TimeUnit.SECONDS);
        assertTrue(t.isDaemon());
        assertTrue(t.getName().startsWith("fyrql-worker-"), t.getName());
    }
}
