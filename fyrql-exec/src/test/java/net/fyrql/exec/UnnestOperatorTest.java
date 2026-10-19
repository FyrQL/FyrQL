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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.fyrql.core.expr.Expressions.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnnestOperatorTest {

    private static final Schema TAGS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("tags", DataType.listOf(DataType.VARCHAR)));

    private static Executor executor(int batchSize) {
        return new Executor(scan -> {
            throw new AssertionError("no scans here");
        }, MemoryBudget.unlimited(), batchSize);
    }

    private static List<List<Object>> rows(List<Batch> batches) {
        List<List<Object>> result = new ArrayList<>();
        for (Batch b : batches) {
            for (Object[] r : b.rows()) {
                result.add(Arrays.asList(r));
            }
        }
        return result;
    }

    @Test
    void eachRowOncePerElementAndNoneForEmptyOrNullLists() {
        PlanNode values = new PlanNode.Values(TAGS, List.of(
                new Object[] {1, List.of("a", "b")}, new Object[] {2, null}, new Object[] {3, List.of()},
                new Object[] {4, Arrays.asList((Object) null)}, new Object[] {5, List.of("c")}));
        PlanNode.Unnest unnest = new PlanNode.Unnest(values, col(TAGS, 1), "tag");
        assertEquals(Schema.of(new Schema.Field("id", DataType.INTEGER), TAGS.fields().get(1),
                new Schema.Field("tag", DataType.VARCHAR)), unnest.schema());
        List<Batch> batches = executor(2).execute(unnest);
        assertEquals(List.of(
                List.of(1, List.of("a", "b"), "a"),
                List.of(1, List.of("a", "b"), "b"),
                Arrays.asList(4, Arrays.asList((Object) null), null),
                List.of(5, List.of("c"), "c")), rows(batches));
        for (Batch b : batches) {
            assertTrue(b.rowCount() > 0 && b.rowCount() <= 2, "batch of " + b.rowCount());
        }
    }

    @Test
    void aListLongerThanABatchSpreadsOverSeveral() {
        List<Object> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("e" + i);
        }
        PlanNode values = new PlanNode.Values(TAGS, List.of(new Object[] {7, ten}, new Object[] {8, List.of("x")}));
        List<Batch> batches = executor(3).execute(new PlanNode.Unnest(values, col(TAGS, 1), "tag"));
        assertEquals(List.of(3, 3, 3, 2), batches.stream().map(Batch::rowCount).toList());
        List<List<Object>> rows = rows(batches);
        for (int i = 0; i < 10; i++) {
            assertEquals(List.of(7, ten, "e" + i), rows.get(i));
        }
        assertEquals(List.of(8, List.of("x"), "x"), rows.get(10));
    }

    @Test
    void theListIsAnyExpressionOfListType() {
        Schema one = new Schema(List.of());
        Expression constant = new Expression.Literal(DataType.listOf(DataType.listOf(DataType.INTEGER)),
                Arrays.asList(List.of(1, 2), null, List.of()));
        PlanNode.Unnest unnest = new PlanNode.Unnest(new PlanNode.Values(one, List.<Object[]>of(new Object[0])),
                constant, "x");
        assertEquals(Arrays.asList(Arrays.asList((Object) List.of(1, 2)), Arrays.asList((Object) null),
                Arrays.asList((Object) List.of())), rows(executor(4).execute(unnest)));
        assertThrows(IllegalArgumentException.class, () -> new PlanNode.Unnest(unnest, col(TAGS, 0), "y"));
    }
}
