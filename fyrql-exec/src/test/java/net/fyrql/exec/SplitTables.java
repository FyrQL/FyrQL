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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tables in splits, for tests of segments: it notes the threads that open
 * splits, how many readers are open, and the tables whose splits a query
 * asked for, and it fails or waits in some splits.
 */
final class SplitTables implements TableSource {

    static final Schema NUMBERS = Schema.of(new Schema.Field("n", DataType.INTEGER),
            new Schema.Field("k", DataType.INTEGER));

    final Map<TableRef, List<List<Batch>>> tables = new HashMap<>();
    final Set<String> threads = ConcurrentHashMap.newKeySet();
    final AtomicInteger open = new AtomicInteger();
    final AtomicInteger opened = new AtomicInteger();
    final Set<Integer> failing = ConcurrentHashMap.newKeySet();
    /** The splits that fail after their first batch. */
    final Set<Integer> failingLate = ConcurrentHashMap.newKeySet();
    final Set<String> asked = ConcurrentHashMap.newKeySet();
    /** Counted down by a reader that waits until it is canceled, if set. */
    volatile CountDownLatch waiting;

    /** Adds a table with splits of the sizes, of the numbers from 0 on, with {@code k = n % 5}. */
    PlanNode.Scan table(String name, int... sizes) {
        List<Object[]> rows = new ArrayList<>();
        for (int n = 0; n < Arrays.stream(sizes).sum(); n++) {
            rows.add(new Object[] {n, n % 5});
        }
        return table(name, NUMBERS, rows, sizes);
    }

    /** Adds a table of rows in splits of the sizes, which add up to the rows, in batches of three rows. */
    PlanNode.Scan table(String name, Schema schema, List<Object[]> rows, int... sizes) {
        if (Arrays.stream(sizes).sum() != rows.size()) {
            throw new IllegalArgumentException(rows.size() + " rows for splits of " + Arrays.toString(sizes));
        }
        List<List<Batch>> splits = new ArrayList<>();
        int at = 0;
        for (int size : sizes) {
            List<Object[]> part = rows.subList(at, at + size);
            at += size;
            splits.add(part.isEmpty() ? List.of() : Batches.split(Batch.ofRows(schema, part), 3));
        }
        TableRef ref = TableRef.of("t", name);
        tables.put(ref, splits);
        return PlanNode.Scan.all(ref, schema);
    }

    @Override
    public BatchReader open(PlanNode.Scan scan) {
        return Splits.sequential(splits(scan, QueryContext.NONE), QueryContext.NONE);
    }

    @Override
    public Splits splits(PlanNode.Scan scan, QueryContext context) {
        asked.add(scan.table().table());
        List<List<Batch>> splits = tables.get(scan.table());
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < splits.size(); i++) {
            indexes.add(i);
        }
        return Splits.of(indexes, i -> reader(splits.get(i), i, scan.columns()));
    }

    private BatchReader reader(List<Batch> batches, int split, List<Integer> columns) {
        threads.add(Thread.currentThread().getName());
        open.incrementAndGet();
        opened.incrementAndGet();
        List<Batch> projected = new ArrayList<>();
        for (Batch b : batches) {
            projected.add(b.project(columns));
        }
        BatchReader rows = Batches.reader(projected);
        CountDownLatch canceled = new CountDownLatch(1);
        return new BatchReader() {
            private boolean closed;
            private int read;

            @Override
            public Batch next() {
                if (failing.contains(split) || failingLate.contains(split) && read > 0) {
                    throw new IllegalStateException("bad split " + split);
                }
                read++;
                CountDownLatch w = waiting;
                if (w != null) {
                    w.countDown();
                    try {
                        canceled.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("The source was asked to stop");
                }
                return rows.next();
            }

            @Override
            public void cancel() {
                canceled.countDown();
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    open.decrementAndGet();
                }
            }
        };
    }
}
