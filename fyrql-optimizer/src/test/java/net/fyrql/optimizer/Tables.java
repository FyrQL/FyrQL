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

package net.fyrql.optimizer;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Executor;
import net.fyrql.exec.Operator;
import net.fyrql.exec.TableSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Three small tables in a memory source, and an executor over a connector, for the tests of the rewrites. */
final class Tables {

    static final String SOURCE = "mem";
    static final Schema T = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.VARCHAR),
            new Schema.Field("c", DataType.DOUBLE), new Schema.Field("k", DataType.INTEGER));
    static final Schema U = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("n", DataType.BIGINT));
    /**
     * A table with a struct column: an id, and
     * {@code s STRUCT(a INTEGER, b VARCHAR, c STRUCT(x DOUBLE, y VARCHAR))}.
     */
    static final Schema P = Schema.of(new Schema.Field("id", DataType.INTEGER), new Schema.Field("s", DataType.struct(
            new DataType.Struct.Field("a", DataType.INTEGER), new DataType.Struct.Field("b", DataType.VARCHAR),
            new DataType.Struct.Field("c", DataType.struct(new DataType.Struct.Field("x", DataType.DOUBLE),
                    new DataType.Struct.Field("y", DataType.VARCHAR))))));
    static final TableRef T_REF = TableRef.of(SOURCE, "t");
    static final TableRef U_REF = TableRef.of(SOURCE, "u");
    static final TableRef P_REF = TableRef.of(SOURCE, "p");

    private Tables() {
    }

    static MemoryConnector.Builder builder() {
        List<Object[]> t = List.of(row(1, "x", 1.5, 10), row(2, null, null, 10), row(3, "y", -2.0, 20),
                row(4, "x", 0.25, 20), row(5, "z", 7.0, null), row(6, "y", null, 30), row(null, "x", 0.0, 30));
        List<Object[]> u = List.of(row(1, 100L), row(4, null), row(null, 7L), row(3, 5L), row(3, 6L));
        List<Object[]> p = List.of(row(1, Arrays.asList(10, "p", Arrays.asList(1.5, "u"))), row(2, null),
                row(3, Arrays.asList(null, "q", null)), row(4, Arrays.asList(40, "p", Arrays.asList(null, "v"))));
        return MemoryConnector.builder(SOURCE).batchSize(3).table("t", T, t).table("u", U, u).table("p", P, p);
    }

    private static Object[] row(Object... values) {
        return values;
    }

    /** An executor that opens scans, and the parts of plans a source runs, at the connector as the engine does. */
    static Executor executor(Connector connector) {
        return new Executor(new TableSource() {
            @Override
            public BatchReader open(PlanNode.Scan scan) {
                PlanNode.Pushed p = scan.pushed();
                ScanPlan plan = connector.plan(scan.table(), new ScanRequest(ColumnPath.of(scan),
                        p.filters(), p.limit(), p.order(), p.aggregation()));
                return connector.open(connector.splits(plan).get(0));
            }

            @Override
            public BatchReader open(PlanNode.Remote remote) {
                return connector.openRemote(connector.planRemote(remote.logical()).orElseThrow());
            }
        });
    }

    static List<Object[]> rows(Executor executor, PlanNode plan) {
        List<Object[]> rows = new ArrayList<>();
        try (Operator op = executor.open(plan)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                rows.addAll(b.rows());
            }
        }
        return rows;
    }

    /** Rows as text, sorted, for comparison without order. */
    static List<String> sorted(List<Object[]> rows) {
        List<String> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(java.util.Arrays.toString(row));
        }
        java.util.Collections.sort(result);
        return result;
    }
}
