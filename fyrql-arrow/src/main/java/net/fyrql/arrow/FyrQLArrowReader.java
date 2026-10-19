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

import net.fyrql.QueryResult;
import net.fyrql.core.vector.Batch;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.IOException;

/**
 * The result of a query as an {@link ArrowReader}: each
 * {@link #loadNextBatch()} runs the query on to its next batch and writes
 * it into the root of the reader, which holds one batch at a time.
 * Closing the reader closes the result and releases the vectors; a failure
 * of the query surfaces from {@code loadNextBatch} as the exception of
 * FyrQL.
 */
public final class FyrQLArrowReader extends ArrowReader {

    private final QueryResult result;
    private long rows;
    private long bytes;

    FyrQLArrowReader(QueryResult result, BufferAllocator allocator) {
        super(allocator);
        this.result = result;
    }

    @Override
    public boolean loadNextBatch() throws IOException {
        prepareLoadNextBatch();
        Batch batch = result.nextBatch();
        if (batch == null) {
            return false;
        }
        VectorSchemaRoot root = getVectorSchemaRoot();
        BatchToArrow.fill(batch, root);
        rows += batch.rowCount();
        for (FieldVector v : root.getFieldVectors()) {
            bytes += v.getBufferSize();
        }
        return true;
    }

    /** Returns the number of bytes of the Arrow buffers of all batches so far. */
    @Override
    public long bytesRead() {
        return bytes;
    }

    /** Returns the number of rows of all batches so far. */
    public long rowCount() {
        return rows;
    }

    /**
     * Cancels the query from any thread; the next {@link #loadNextBatch()},
     * or the one running, fails with a
     * {@link net.fyrql.exec.QueryCancelledException}. The reader still
     * needs to be closed.
     */
    public void cancel() {
        result.cancel();
    }

    @Override
    protected void closeReadSource() {
        result.close();
    }

    @Override
    protected Schema readSchema() {
        return ArrowTypes.toArrow(result.schema());
    }
}
