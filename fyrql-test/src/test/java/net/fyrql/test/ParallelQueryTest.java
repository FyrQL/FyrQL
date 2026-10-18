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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queries over tables in memory with many splits, on several threads and
 * on one: in the order of the splits, the results are the same row by row;
 * without it, the same rows.
 */
class ParallelQueryTest {

    private static final Schema ORDERS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("customer", DataType.INTEGER), new Schema.Field("amount", DataType.DOUBLE),
            new Schema.Field("items", DataType.listOf(DataType.INTEGER)));
    private static final Schema CUSTOMERS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("name", DataType.VARCHAR));

    private static final List<String> QUERIES = List.of(
            "SELECT * FROM orders",
            "SELECT id, amount * 2 AS twice FROM orders WHERE customer % 3 = 1",
            "SELECT id, x FROM orders, unnest(orders.items) AS u(x) WHERE x > 1",
            "SELECT id FROM orders WHERE amount > 50 LIMIT 7 OFFSET 5",
            "SELECT id FROM orders WHERE customer = 1 UNION ALL SELECT id FROM customers "
                    + "UNION ALL SELECT customer FROM orders",
            "SELECT customer, count(*), sum(amount), min(id), list(id) FROM orders WHERE id % 2 = 0 GROUP BY customer",
            "SELECT DISTINCT customer FROM orders",
            "SELECT count(*), sum(amount), avg(amount) FROM orders",
            "SELECT id, amount FROM orders ORDER BY customer DESC",
            "SELECT o.id, c.name FROM orders o JOIN customers c ON o.customer = c.id WHERE o.amount < 30",
            "SELECT id, sum(amount) OVER (PARTITION BY customer ORDER BY id) FROM orders");

    private static FyrQL engine(int threads, CodegenMode codegen, boolean preserveOrder) {
        List<Object[]> orders = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            orders.add(new Object[] {i, i % 13 == 0 ? null : i % 7, (i * 37 % 100) + 0.25,
                    i % 11 == 0 ? null : List.of(i % 5, i % 3)});
        }
        List<Object[]> customers = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            customers.add(new Object[] {i % 9, "c" + i});
        }
        MemoryConnector memory = MemoryConnector.builder("mem").table("orders", ORDERS, orders)
                .table("customers", CUSTOMERS, customers).batchSize(8).splitRows(32).build();
        return FyrQL.builder().register(memory).batchSize(8).codegen(codegen).threads(threads)
                .preserveOrder(preserveOrder).build();
    }

    private static List<List<Object>> rows(FyrQL engine, String sql) {
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : engine.rows(engine.plan(sql))) {
            rows.add(Arrays.asList(row));
        }
        return rows;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Object::toString));
        return copy;
    }

    @Test
    void inTheOrderOfTheSplitsTheResultsAreTheSameRowByRow() {
        for (CodegenMode mode : List.of(CodegenMode.OFF, CodegenMode.ALWAYS)) {
            try (FyrQL one = engine(1, mode, true); FyrQL four = engine(4, mode, true)) {
                for (String sql : QUERIES) {
                    List<List<Object>> expected = rows(one, sql);
                    assertFalse(expected.isEmpty(), sql);
                    for (int run = 0; run < 3; run++) {
                        assertEquals(expected, rows(four, sql), () -> mode + ": " + sql);
                    }
                }
            }
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("fyrql-worker-")), "the queries ran on workers");
    }

    @Test
    void withoutTheOrderTheRowsAreTheSame() {
        try (FyrQL one = engine(1, CodegenMode.OFF, true); FyrQL four = engine(4, CodegenMode.OFF, false)) {
            for (String sql : QUERIES) {
                List<List<Object>> expected = rows(one, sql);
                List<List<Object>> actual = rows(four, sql);
                if (sql.contains("LIMIT")) {
                    assertEquals(expected.size(), actual.size(), sql);
                } else {
                    assertEquals(sorted(expected), sorted(actual), sql);
                }
            }
        }
    }

    @Test
    void explainMarksTheTopsOfTheSegments() {
        String sql = "SELECT o.id, c.name FROM orders o JOIN customers c ON o.customer = c.id WHERE o.amount < 30";
        try (FyrQL one = engine(1, CodegenMode.OFF, true); FyrQL four = engine(4, CodegenMode.OFF, true)) {
            assertFalse(one.explain(sql).contains("[parallel]"), one.explain(sql));
            List<String> lines = List.of(four.explain(sql).split("\n"));
            List<String> marked = lines.stream().filter(l -> l.endsWith(" [parallel]")).toList();
            assertEquals(2, marked.size(), four.explain(sql));
            assertTrue(marked.stream().anyMatch(l -> l.strip().startsWith("Scan mem.main.customers")),
                    four.explain(sql));
        }
    }
}
