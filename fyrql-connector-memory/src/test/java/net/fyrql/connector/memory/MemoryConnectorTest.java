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

package net.fyrql.connector.memory;

import net.fyrql.connector.Batches;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.rem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConnectorTest {

    record Address(String city, int zip) {
    }

    record Order(long id, Integer customer, BigDecimal amount, LocalDate created, Instant shipped, Address address,
            List<String> tags, Map<String, Integer> counts, boolean paid) {
    }

    public static class Bean {
        private final String name;
        private final double score;

        Bean(String name, double score) {
            this.name = name;
            this.score = score;
        }

        public String getName() {
            return name;
        }

        public double getScore() {
            return score;
        }

        public boolean isActive() {
            return score > 0;
        }
    }

    @Test
    void recordsBecomeTablesWithNestedTypes() {
        List<Order> orders = List.of(
                new Order(1, 10, new BigDecimal("5.5"), LocalDate.of(2026, 1, 2), Instant.EPOCH,
                        new Address("Bonn", 53111), List.of("a", "b"), Map.of("x", 1), true),
                new Order(2, null, null, null, null, null, null, null, false));
        MemoryConnector c = MemoryConnector.builder("mem")
                .table("orders", Order.class, orders, Map.of("amount", DataType.decimal(10, 2)))
                .batchSize(1)
                .build();
        TableRef ref = TableRef.of("mem", "orders");
        Schema schema = c.catalog().schema(ref);
        assertEquals("id BIGINT NOT NULL", schema.fields().get(0).toString());
        assertEquals(DataType.decimal(10, 2), schema.type(2));
        assertEquals(DataType.DATE, schema.type(3));
        assertEquals(DataType.TIMESTAMP, schema.type(4));
        assertEquals("STRUCT(city VARCHAR, zip INTEGER)", schema.type(5).sqlName());
        assertEquals("VARCHAR[]", schema.type(6).sqlName());
        assertEquals("MAP(VARCHAR, INTEGER)", schema.type(7).sqlName());
        assertFalse(schema.nullable(8));
        assertEquals(List.of("orders"), c.catalog().tables("main"));
        assertEquals(2, c.catalog().rowCount(ref).getAsLong());

        ScanPlan plan = c.plan(ref, ScanRequest.columns(List.of(0, 2, 3, 5, 6, 7)));
        List<Batch> batches = Batches.readAll(c.open(c.splits(plan).get(0)));
        assertEquals(2, batches.size(), "one row per batch");
        Object[] first = batches.get(0).row(0);
        assertEquals(Arrays.asList(1L, new BigDecimal("5.50"), (int) LocalDate.of(2026, 1, 2).toEpochDay(),
                List.of("Bonn", 53111), List.of("a", "b"), Map.of("x", 1)), Arrays.asList(first));
        Object[] second = batches.get(1).row(0);
        assertEquals(2L, second[0]);
        assertNull(second[1]);
        assertNull(second[3]);
    }

    @Test
    void beansAndExplicitRows() {
        MemoryConnector c = MemoryConnector.builder("mem")
                .table("beans", Bean.class, List.of(new Bean("a", 1.5), new Bean("b", -1)))
                .table("rows", Schema.of(DataType.INTEGER, DataType.VARCHAR),
                        List.of(new Object[] {1, "x"}, new Object[] {null, null}))
                .build();
        Schema beans = c.catalog().schema(TableRef.of("mem", "beans"));
        assertTrue(beans.indexOf("name") >= 0 && beans.indexOf("score") >= 0 && beans.indexOf("active") >= 0);
        assertEquals(DataType.BOOLEAN, beans.type(beans.indexOf("active")));
        ScanPlan plan = c.plan(TableRef.of("mem", "beans"), ScanRequest.columns(List.of(beans.indexOf("active"))));
        Batch b = Batches.readAll(c.open(c.splits(plan).get(0))).get(0);
        assertEquals(List.of(true, false), List.of(b.column(0).get(0), b.column(0).get(1)));
        ScanPlan rows = c.plan(TableRef.of("mem", "rows"), ScanRequest.columns(List.of(1, 0)));
        Batch r = Batches.readAll(c.open(c.splits(rows).get(0))).get(0);
        assertEquals("x", r.column(0).get(0));
        assertEquals(1, r.column(1).get(0));
        assertTrue(r.column(1).isNull(1));
    }

    @Test
    void acceptedFiltersAndLimitAreAppliedByTheConnector() {
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.VARCHAR));
        List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new Object[] {i, "r" + i});
        }
        MemoryConnector c = MemoryConnector.builder("mem").batchSize(3).table("t", schema, rows)
                .pushdown(Capabilities.filtersAndLimit(
                        e -> Expressions.allMatch(e, n -> !(n instanceof Expression.Call))))
                .build();
        TableRef t = TableRef.of("mem", "t");
        // The scan reads (b, a): the filters refer to these positions.
        Expression even = eq(rem(col(1, DataType.INTEGER), lit(2)), lit(0));
        Expression declined = gt(call("abs", col(1, DataType.INTEGER)), lit(1));

        ScanPlan plan = c.plan(t, new ScanRequest(ColumnPath.whole(List.of(1, 0)), List.of(even, declined),
                OptionalLong.of(3), List.of(), Optional.empty()));
        assertEquals(List.of(even), plan.acceptedFilters());
        assertFalse(plan.limitAccepted());
        List<Batch> batches = Batches.readAll(c.open(c.splits(plan).get(0)));
        assertEquals(List.of("r0", "r2", "r4", "r6", "r8"), batches.stream().flatMap(b -> b.rows().stream())
                .map(r -> r[0]).toList());
        assertTrue(batches.stream().allMatch(b -> b.rowCount() > 0), "no empty batches");

        plan = c.plan(t, new ScanRequest(ColumnPath.whole(List.of(1, 0)), List.of(even), OptionalLong.of(3),
                List.of(), Optional.empty()));
        assertTrue(plan.limitAccepted());
        batches = Batches.readAll(c.open(c.splits(plan).get(0)));
        assertEquals(List.of("r0", "r2", "r4"), batches.stream().flatMap(b -> b.rows().stream())
                .map(r -> r[0]).toList());

        ScanPlan none = MemoryConnector.builder("mem").table("t", schema, rows).build().plan(t,
                new ScanRequest(ColumnPath.whole(List.of(0)), List.of(even), OptionalLong.of(3), List.of(),
                        Optional.empty()));
        assertEquals(List.of(), none.acceptedFilters());
        assertFalse(none.limitAccepted());
    }
}
