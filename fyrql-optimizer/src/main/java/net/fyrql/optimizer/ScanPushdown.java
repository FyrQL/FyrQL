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

package net.fyrql.optimizer;

import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.Aggregation;
import net.fyrql.core.plan.PlanNode.Pushed;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.plan.Plans;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Negotiates every scan with its connector. Above a scan the pass looks
 * for, from the top, a limit, a sort, an aggregation, a projection and a
 * filter, each of them optional, and asks the connector to take over:
 * <ul>
 * <li>the conjuncts of the filter;</li>
 * <li>the aggregation, when the projection in between only picks columns,
 * and then neither sort nor limit;</li>
 * <li>otherwise the sort, when its keys are columns of the scan through the
 * projection, and the limit, as the count plus the offset, when there is
 * no sort or the sort is asked for too.</li>
 * </ul>
 * What the connector accepts moves into the scan as {@link Pushed}; the
 * rest of the nodes stay above it. A connector's answer is checked against
 * the rules of the negotiation.
 */
final class ScanPushdown {

    private ScanPushdown() {
    }

    static PlanNode push(PlanNode plan, Function<String, Connector> connectors) {
        Chain chain = Chain.match(plan);
        if (chain != null) {
            return chain.negotiate(connectors.apply(chain.scan().table().source()));
        }
        if (plan.children().isEmpty()) {
            return plan;
        }
        List<PlanNode> children = new ArrayList<>();
        for (PlanNode child : plan.children()) {
            children.add(push(child, connectors));
        }
        return Plans.withChildren(plan, children);
    }

    /** The nodes above a scan that the connector may take over, each possibly absent. */
    private record Chain(PlanNode.Limit limit, PlanNode.Sort sort, PlanNode.Aggregate aggregate,
            PlanNode.Project project, PlanNode.Filter filter, PlanNode.Scan scan) {

        /** Matches the chain at a node; {@code null} if the node is not the top of one, or there is nothing to do. */
        static Chain match(PlanNode node) {
            PlanNode.Limit limit = null;
            PlanNode.Sort sort = null;
            PlanNode.Aggregate aggregate = null;
            PlanNode.Project project = null;
            PlanNode.Filter filter = null;
            PlanNode n = node;
            if (n instanceof PlanNode.Limit l) {
                limit = l;
                n = l.input();
            }
            if (n instanceof PlanNode.Sort s) {
                sort = s;
                n = s.input();
            }
            if (n instanceof PlanNode.Aggregate a) {
                aggregate = a;
                n = a.input();
            }
            if (n instanceof PlanNode.Project p) {
                project = p;
                n = p.input();
            }
            if (n instanceof PlanNode.Filter f) {
                filter = f;
                n = f.input();
            }
            if (n instanceof PlanNode.Scan s && s.pushed().isEmpty()
                    && (limit != null || sort != null || aggregate != null || filter != null)) {
                return new Chain(limit, sort, aggregate, project, filter, s);
            }
            return null;
        }

        /** For each output of the projection, the scan column it picks, or -1 if it computes something. */
        private int[] projected() {
            int[] mapping = new int[project == null ? scan.schema().size() : project.columns().size()];
            for (int i = 0; i < mapping.length; i++) {
                if (project == null) {
                    mapping[i] = i;
                } else {
                    mapping[i] = project.columns().get(i).expression() instanceof Expression.Column c ? c.index() : -1;
                }
            }
            return mapping;
        }

        PlanNode negotiate(Connector connector) {
            List<Expression> filters = filter == null ? List.of() : Expressions.conjuncts(filter.condition());
            int[] mapping = projected();
            boolean columnsOnly = true;
            for (int m : mapping) {
                columnsOnly &= m >= 0;
            }
            Optional<Aggregation> aggregation = Optional.empty();
            List<SortKey> order = List.of();
            OptionalLong limitRows = OptionalLong.empty();
            if (aggregate != null) {
                if (columnsOnly) {
                    List<AggregateCall> calls = new ArrayList<>();
                    for (AggregateCall call : aggregate.aggregates()) {
                        calls.add(new AggregateCall(call.function(),
                                call.column() < 0 ? -1 : mapping[call.column()], call.distinct(), call.name(),
                                call.separator(), remapKeys(call.orderBy(), mapping)));
                    }
                    aggregation = Optional.of(new Aggregation(remap(aggregate.groupColumns(), mapping), calls));
                }
            } else {
                boolean sortable = sort != null;
                if (sort != null) {
                    for (SortKey k : sort.keys()) {
                        sortable &= mapping[k.column()] >= 0;
                    }
                    if (sortable) {
                        order = remapKeys(sort.keys(), mapping);
                    }
                }
                if (limit != null && (sort == null || sortable)) {
                    long rows = limit.count() + limit.offset();
                    limitRows = OptionalLong.of(rows < 0 ? Long.MAX_VALUE : rows);
                }
            }
            if (filters.isEmpty() && aggregation.isEmpty() && order.isEmpty() && limitRows.isEmpty()) {
                return rebuild(scan, filters, false, List.of(), false, false);
            }
            ScanRequest request = new ScanRequest(ColumnPath.of(scan), filters, limitRows, order, aggregation);
            ScanPlan plan = connector.plan(scan.table(), request);
            List<Expression> accepted = new ArrayList<>();
            List<Expression> remaining = new ArrayList<>();
            for (Expression f : filters) {
                (plan.acceptedFilters().contains(f) ? accepted : remaining).add(f);
            }
            String broken = null;
            if (!plan.columns().equals(request.columns())) {
                broken = "it reads " + plan.columns() + " instead of " + request.columns();
            } else if (accepted.size() != plan.acceptedFilters().size()) {
                broken = "it accepts a filter that was not requested";
            } else if (plan.limitAccepted() && (limitRows.isEmpty() || !remaining.isEmpty()
                    || (!order.isEmpty() && !plan.orderAccepted()))) {
                broken = "it accepts the limit without the filters or the order";
            } else if (plan.orderAccepted() && order.isEmpty()) {
                broken = "it accepts an order that was not requested";
            } else if (plan.aggregationAccepted() && (aggregation.isEmpty() || !remaining.isEmpty())) {
                broken = "it accepts the aggregation without the filters";
            } else {
                List<?> expected = plan.aggregationAccepted()
                        ? aggregation.get().output(scan.columnSchema()).types() : scan.columnSchema().types();
                if (!plan.output().types().equals(expected)) {
                    broken = "it plans the columns " + plan.output() + " instead of " + expected;
                }
            }
            if (broken != null) {
                throw new IllegalStateException("The connector " + connector.name() + " breaks the rules of the scan "
                        + "negotiation for " + scan.table() + ": " + broken);
            }
            Pushed pushed = new Pushed(accepted, plan.limitAccepted() ? limitRows : OptionalLong.empty(),
                    plan.orderAccepted() ? order : List.of(),
                    plan.aggregationAccepted() ? aggregation : Optional.empty());
            return rebuild(new PlanNode.Scan(scan.table(), scan.tableSchema(), scan.columns(), scan.fields(), pushed),
                    remaining, plan.aggregationAccepted(), order, plan.orderAccepted(), plan.limitAccepted());
        }

        /** The chain over the negotiated scan, without what the connector took over. */
        private PlanNode rebuild(PlanNode.Scan negotiated, List<Expression> remaining, boolean aggregationAccepted,
                List<SortKey> order, boolean orderAccepted, boolean limitAccepted) {
            PlanNode node = negotiated;
            if (!remaining.isEmpty()) {
                node = new PlanNode.Filter(node, Expressions.conjunction(remaining));
            }
            if (!aggregationAccepted) {
                if (project != null) {
                    node = new PlanNode.Project(node, project.columns());
                }
                if (aggregate != null) {
                    node = new PlanNode.Aggregate(node, aggregate.groupColumns(), aggregate.aggregates());
                }
            }
            if (sort != null && !orderAccepted) {
                node = new PlanNode.Sort(node, sort.keys());
            }
            if (limit != null && !(limitAccepted && limit.offset() == 0)) {
                node = new PlanNode.Limit(node, limit.count(), limit.offset());
            }
            return node;
        }
    }

    private static List<Integer> remap(List<Integer> columns, int[] mapping) {
        List<Integer> result = new ArrayList<>();
        for (int c : columns) {
            result.add(mapping[c]);
        }
        return result;
    }

    private static List<SortKey> remapKeys(List<SortKey> keys, int[] mapping) {
        List<SortKey> result = new ArrayList<>();
        for (SortKey k : keys) {
            result.add(new SortKey(mapping[k.column()], k.ascending(), k.nullsFirst()));
        }
        return result;
    }
}
