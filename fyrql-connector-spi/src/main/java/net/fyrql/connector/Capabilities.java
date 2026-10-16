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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * What a connector can take over, as predicates over the parts of a
 * {@link ScanRequest}; {@link #plan} turns them into a {@link ScanPlan}
 * under the rules of the request, so that a connector need not write the
 * negotiation itself:
 * <ul>
 * <li>each filter the predicate accepts is accepted;</li>
 * <li>the order is accepted when the predicate accepts it as a whole;</li>
 * <li>the limit is accepted when the connector supports one, every filter
 * is accepted, and the order, if any, is accepted;</li>
 * <li>the aggregation is accepted when the predicate accepts it, every
 * filter is accepted, and no order and no limit are requested;</li>
 * <li>fields inside columns are read when the connector reads them;
 * otherwise a request that names one is answered with the whole columns of
 * {@link ScanRequest#withWholeColumns()}, and nothing else.</li>
 * </ul>
 *
 * @param filter accepts one conjunct of the filters
 * @param limit whether the connector can stop after a number of rows
 * @param order accepts the requested order as a whole
 * @param aggregation accepts the requested aggregation as a whole
 * @param fields whether the connector reads fields inside {@code STRUCT}
 *        columns instead of the whole columns
 */
public record Capabilities(Predicate<Expression> filter, boolean limit, Predicate<List<PlanNode.SortKey>> order,
        Predicate<PlanNode.Aggregation> aggregation, boolean fields) {

    /** Takes over nothing and reads whole columns. */
    public static final Capabilities NONE = new Capabilities(e -> false, false, o -> false, a -> false);

    public Capabilities {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(aggregation, "aggregation");
    }

    /** Takes over what the predicates accept and reads whole columns. */
    public Capabilities(Predicate<Expression> filter, boolean limit, Predicate<List<PlanNode.SortKey>> order,
            Predicate<PlanNode.Aggregation> aggregation) {
        this(filter, limit, order, aggregation, false);
    }

    /** Returns the same capabilities, reading fields inside columns or not. */
    public Capabilities withFields(boolean readsFields) {
        return new Capabilities(filter, limit, order, aggregation, readsFields);
    }

    /** Takes over the filters the predicate accepts, and nothing else. */
    public static Capabilities filtersOnly(Predicate<Expression> filter) {
        return new Capabilities(filter, false, o -> false, a -> false);
    }

    /** Takes over the filters the predicate accepts and a limit, and nothing else. */
    public static Capabilities filtersAndLimit(Predicate<Expression> filter) {
        return new Capabilities(filter, true, o -> false, a -> false);
    }

    /** Decides a request under the rules above. */
    public ScanPlan plan(TableRef table, Schema tableSchema, ScanRequest asked, Object state) {
        ScanRequest request = !fields && asked.readsFields() ? asked.withWholeColumns() : asked;
        List<Expression> accepted = new ArrayList<>();
        for (Expression f : request.filters()) {
            if (filter.test(f)) {
                accepted.add(f);
            }
        }
        boolean allFilters = accepted.size() == request.filters().size();
        boolean orderAccepted = !request.order().isEmpty() && order.test(request.order());
        boolean limitAccepted = limit && request.limit().isPresent() && allFilters
                && (request.order().isEmpty() || orderAccepted);
        boolean aggregationAccepted = request.aggregation().isPresent() && allFilters && request.order().isEmpty()
                && request.limit().isEmpty() && aggregation.test(request.aggregation().get());
        Schema output = request.output(tableSchema);
        if (aggregationAccepted) {
            output = request.aggregation().get().output(output);
        }
        return new ScanPlan(table, output, request.columns(), accepted, limitAccepted, orderAccepted,
                aggregationAccepted, state);
    }
}
