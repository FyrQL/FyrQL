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

package net.fyrql.connector;

import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;

import java.util.List;
import java.util.Optional;

/**
 * A registered source of tables. The engine asks the {@link #catalog()} for
 * schemas, negotiates what a scan can push down with {@link #plan}, splits
 * the scan into parts that can be read independently, and reads each part
 * through a {@link BatchReader}. A source that runs queries itself, such
 * as a database, may also take over a part of a plan as a whole with
 * {@link #planRemote}.
 * <p>
 * Whatever a connector accepts in a {@link ScanPlan} it must evaluate with
 * exactly the semantics of the engine; a filter that the source would
 * evaluate differently, e.g. under a collation that ignores case, must stay
 * in the plan of the engine.
 * <p>
 * A source that can be written to offers a {@link #writer()}.
 */
public interface Connector extends AutoCloseable {

    /** Returns the name under which the source was registered: the first part of a {@link TableRef}. */
    String name();

    /** Returns the catalog of the source. */
    Catalog catalog();

    /**
     * Decides which parts of a scan request the source takes over. When the
     * scan is opened, the engine asks once more with the same filters, those
     * not taken over included, and expects the same answer; a source may use
     * the filters it does not take over to skip data in which no row can
     * satisfy them, never to drop a row that does.
     */
    ScanPlan plan(TableRef table, ScanRequest request);

    /** Returns the parts of a scan that can be read independently; at least one. */
    List<Split> splits(ScanPlan plan);

    /**
     * Returns the parts of a scan that can be read independently, for a
     * reader of at most {@code threads} threads at a time; at least one. A
     * source whose parts cost something of their own, such as a statement
     * and a connection each, may make no more parts than there are threads,
     * and one part for one thread, which gains nothing from more. The
     * default returns {@link #splits(ScanPlan)}.
     */
    default List<Split> splits(ScanPlan plan, int threads) {
        return splits(plan);
    }

    /** Opens a reader over one part of a scan. */
    BatchReader open(Split split);

    /**
     * Decides whether the source runs a part of a plan as a whole, e.g. a
     * join of two of its tables as one statement of a database. The part
     * contains at least one join, and its leaves are scans of tables of
     * this source of whole columns, of which nothing is taken over yet; it
     * may also contain filters, projections, aggregations, sorts and
     * limits. A source that accepts it evaluates it with exactly the
     * semantics of the engine and delivers its rows, in the order of a sort
     * at its top. The default runs nothing.
     *
     * @return what the source runs, or empty if the engine runs the part
     */
    default Optional<RemotePlan> planRemote(PlanNode plan) {
        return Optional.empty();
    }

    /**
     * Opens a reader over the rows of a part of a plan the source has
     * accepted with {@link #planRemote}.
     *
     * @throws UnsupportedOperationException if the source runs no parts of plans
     */
    default BatchReader openRemote(RemotePlan plan) {
        throw new UnsupportedOperationException("The source " + name() + " runs no parts of plans");
    }

    /**
     * Returns what the source offers to create, append to and drop tables,
     * for {@code CREATE TABLE}, {@code INSERT INTO} and {@code DROP TABLE}.
     * The default writes nothing.
     */
    default Optional<Writer> writer() {
        return Optional.empty();
    }

    /**
     * Returns the store of the files of a scheme the source holds, such as
     * {@code s3}, which the engine gives to all sources through
     * {@link #fileStores}. The default holds none.
     */
    default Optional<FileStore> fileStore() {
        return Optional.empty();
    }

    /**
     * Takes the stores of files of the engine, which a source that reads or
     * writes files uses for locations with a scheme. The engine calls it
     * once all sources are created, before the first statement; the
     * default ignores them.
     */
    default void fileStores(FileStores stores) {
    }

    @Override
    default void close() {
    }
}
