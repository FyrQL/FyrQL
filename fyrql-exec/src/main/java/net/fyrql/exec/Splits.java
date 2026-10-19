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
import net.fyrql.core.vector.Batch;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The parts of a scan that can be read independently of each other, such as
 * the row groups of a Parquet file: read one after the other, in the order
 * of their indexes, they give the rows of the scan.
 */
public interface Splits {

    /** Returns the number of parts. */
    int count();

    /**
     * Opens the reader of a part; the caller closes it.
     *
     * @param index the part, from 0 to {@link #count()} - 1
     */
    BatchReader open(int index);

    /** Returns one part, which the supplier opens. */
    static Splits single(Supplier<BatchReader> opener) {
        Objects.requireNonNull(opener, "opener");
        return new Splits() {
            @Override
            public int count() {
                return 1;
            }

            @Override
            public BatchReader open(int index) {
                Objects.checkIndex(index, 1);
                return opener.get();
            }
        };
    }

    /** Returns the parts of a list, each of which the function opens. */
    static <T> Splits of(List<T> parts, Function<? super T, BatchReader> opener) {
        List<T> copy = List.copyOf(parts);
        Objects.requireNonNull(opener, "opener");
        return new Splits() {
            @Override
            public int count() {
                return copy.size();
            }

            @Override
            public BatchReader open(int index) {
                return opener.apply(copy.get(index));
            }
        };
    }

    /**
     * Returns a reader of all parts, one after the other. Once the query is
     * canceled, no further part is opened, and canceling the reader cancels
     * the reader of the current part.
     */
    static BatchReader sequential(Splits splits, QueryContext context) {
        Objects.requireNonNull(splits, "splits");
        Objects.requireNonNull(context, "context");
        return new BatchReader() {
            private final int count = splits.count();
            private int next;
            private volatile BatchReader current;

            @Override
            public Batch next() {
                while (true) {
                    if (current == null) {
                        // The end of a canceled query, which its scan turns into the failure.
                        if (context.isCancelled() || next == count) {
                            return null;
                        }
                        current = splits.open(next++);
                        if (context.isCancelled()) {
                            close();
                            return null;
                        }
                    }
                    Batch b = current.next();
                    if (b != null) {
                        return b;
                    }
                    current.close();
                    current = null;
                }
            }

            @Override
            public void close() {
                BatchReader r = current;
                if (r != null) {
                    current = null;
                    r.close();
                }
            }

            @Override
            public void cancel() {
                BatchReader r = current;
                if (r != null) {
                    r.cancel();
                }
            }
        };
    }
}
