/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.connector;

import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.MapVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Helpers over batches that connectors and operators share. */
public final class Batches {

    private Batches() {
    }

    /** Returns a reader over fixed batches. */
    public static BatchReader reader(List<Batch> batches) {
        Iterator<Batch> it = batches.iterator();
        return () -> it.hasNext() ? it.next() : null;
    }

    /** Reads every batch of a reader and closes it. */
    public static List<Batch> readAll(BatchReader reader) {
        List<Batch> batches = new ArrayList<>();
        try (reader) {
            for (Batch b = reader.next(); b != null; b = reader.next()) {
                batches.add(b);
            }
        }
        return batches;
    }

    /** Concatenates batches of one schema into one, without boxing. */
    public static Batch concat(Schema schema, List<Batch> batches) {
        if (batches.size() == 1) {
            return batches.get(0);
        }
        int rows = 0;
        for (Batch b : batches) {
            rows += b.rowCount();
        }
        List<Vector> columns = new ArrayList<>(schema.size());
        for (int c = 0; c < schema.size(); c++) {
            List<Vector> parts = new ArrayList<>(batches.size());
            for (Batch b : batches) {
                parts.add(b.column(c));
            }
            columns.add(Vectors.concat(schema.type(c), parts));
        }
        return new Batch(schema, rows, columns);
    }

    /** Splits a batch into batches of at most the given size. */
    public static List<Batch> split(Batch batch, int size) {
        if (batch.rowCount() <= size) {
            return List.of(batch);
        }
        List<Batch> parts = new ArrayList<>();
        for (int from = 0; from < batch.rowCount(); from += size) {
            int n = Math.min(size, batch.rowCount() - from);
            int[] rows = new int[n];
            for (int i = 0; i < n; i++) {
                rows[i] = from + i;
            }
            parts.add(batch.select(rows));
        }
        return parts;
    }

    /** Selects rows, where an index of -1 gives a row of {@code NULL}s; nothing is boxed. */
    public static Batch selectOrNull(Batch batch, int[] rows) {
        List<Vector> columns = new ArrayList<>(batch.columns().size());
        for (Vector v : batch.columns()) {
            columns.add(Vectors.gather(v, rows));
        }
        return new Batch(batch.schema(), rows.length, columns);
    }

    /** Estimates the bytes a batch occupies on the heap. */
    public static long estimateBytes(Batch batch) {
        long bytes = 0;
        for (Vector v : batch.columns()) {
            bytes += estimateBytes(v);
        }
        return bytes;
    }

    /** Estimates the bytes a vector occupies on the heap. */
    public static long estimateBytes(Vector v) {
        long n = v.size();
        return switch (v) {
            case net.fyrql.core.vector.BooleanVector x -> n + 16;
            case net.fyrql.core.vector.TinyIntVector x -> n + 16;
            case net.fyrql.core.vector.SmallIntVector x -> 2 * n + 16;
            case net.fyrql.core.vector.IntVector x -> 4 * n + 16;
            case net.fyrql.core.vector.FloatVector x -> 4 * n + 16;
            case net.fyrql.core.vector.BigIntVector x -> 8 * n + 16;
            case net.fyrql.core.vector.DoubleVector x -> 8 * n + 16;
            case net.fyrql.core.vector.HugeIntVector x -> 16 * n + 32;
            case net.fyrql.core.vector.UuidVector x -> 16 * n + 32;
            case DecimalVector x -> (x.isSmall() ? 8 : 40) * n + 16;
            case BytesVector x -> x.data().length + 4 * n + 32;
            case StructVector x -> {
                long sum = n + 16;
                for (Vector child : x.children()) {
                    sum += estimateBytes(child);
                }
                yield sum;
            }
            case ListVector x -> 4 * n + 16 + estimateBytes(x.child());
            case MapVector x -> 4 * n + 16 + estimateBytes(x.keys()) + estimateBytes(x.values());
        };
    }
}
