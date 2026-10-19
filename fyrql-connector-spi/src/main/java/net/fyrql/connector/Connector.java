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

    @Override
    default void close() {
    }
}
