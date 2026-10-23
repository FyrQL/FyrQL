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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Order;
import net.fyrql.test.ResultComparison;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The 22 TPC-H queries, in the text of the specification
 * with its validation parameters, and the comparison of their results.
 */
final class TpchQueries {

    /** A query: its name, such as {@code q01}, and its text. */
    record Query(String name, String sql) {

        @Override
        public String toString() {
            return name;
        }
    }

    /** The number of queries of the benchmark. */
    private static final int COUNT = 22;

    /** The queries, in the order of their numbers. */
    static final List<Query> ALL = load();

    /**
     * Not a query of the benchmark: one scan of {@code lineitem} that reads
     * two columns of every row, as a reference for the cost of reading the
     * largest file.
     */
    static final Query SCAN = new Query("scan", "SELECT sum(l_quantity), max(l_shipdate) FROM lineitem");

    private TpchQueries() {
    }

    private static List<Query> load() {
        List<Query> queries = new ArrayList<>();
        for (int n = 1; n <= COUNT; n++) {
            String name = String.format("q%02d", n);
            try (InputStream in = TpchQueries.class.getResourceAsStream("/tpch/" + name + ".sql")) {
                if (in == null) {
                    throw new IllegalStateException("No resource /tpch/" + name + ".sql");
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                // The comment lines at the top are the license and the title.
                String sql = text.lines().filter(l -> !l.startsWith("--")).collect(Collectors.joining("\n")).strip();
                queries.add(new Query(name, sql));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return List.copyOf(queries);
    }

    /**
     * Compares the rows of FyrQL with the expected ones: as multisets, and,
     * if the plan sorts its output, the rows of FyrQL must be in the order
     * of the sort keys. Rows with equal keys may come in any order, as they
     * may in DuckDB.
     *
     * @return the report of the differences, or empty if the results agree
     */
    static Optional<String> compare(PlanNode plan, List<Object[]> expected, List<Object[]> actual) {
        Schema schema = plan.schema();
        ResultComparison.Outcome rows = ResultComparison.compare(schema, expected, actual, false);
        if (!rows.same()) {
            return Optional.of(rows.report());
        }
        List<SortKey> keys = outputOrder(plan);
        if (keys == null) {
            return Optional.empty();
        }
        for (int r = 1; r < actual.size(); r++) {
            if (compare(schema, keys, actual.get(r - 1), actual.get(r)) > 0) {
                return Optional.of("row " + r + " is out of order: " + ResultComparison.canonical(schema,
                        List.of(actual.get(r - 1), actual.get(r))));
            }
        }
        return Optional.empty();
    }

    private static int compare(Schema schema, List<SortKey> keys, Object[] a, Object[] b) {
        for (SortKey k : keys) {
            // The rows of rows() have the dates and times of java.time, those of a batch the form of its vectors.
            DataType type = schema.type(k.column());
            Object x = Values.fromJava(a[k.column()], type);
            Object y = Values.fromJava(b[k.column()], type);
            int c;
            if (x == null || y == null) {
                c = x == null ? y == null ? 0 : k.nullsFirst() ? -1 : 1 : k.nullsFirst() ? 1 : -1;
            } else {
                c = Order.compare(x, y, type);
                c = k.ascending() ? c : -c;
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Returns the sort keys of a plan in terms of its output columns: the
     * keys of the sort below limits, filters and projections, each through
     * a projection that passes its column on; {@code null} if the plan does
     * not sort or a key is not an output column.
     */
    static List<SortKey> outputOrder(PlanNode plan) {
        return switch (plan) {
            case PlanNode.Sort s -> s.keys();
            case PlanNode.Limit l -> outputOrder(l.input());
            case PlanNode.Filter f -> outputOrder(f.input());
            case PlanNode.Project p -> {
                List<SortKey> inner = outputOrder(p.input());
                if (inner == null) {
                    yield null;
                }
                List<SortKey> keys = new ArrayList<>();
                for (SortKey k : inner) {
                    int output = -1;
                    for (int i = 0; i < p.columns().size() && output < 0; i++) {
                        if (p.columns().get(i).expression() instanceof Expression.Column c && c.index() == k.column()) {
                            output = i;
                        }
                    }
                    if (output < 0) {
                        yield null;
                    }
                    keys.add(new SortKey(output, k.ascending(), k.nullsFirst()));
                }
                yield keys;
            }
            default -> null;
        };
    }
}
