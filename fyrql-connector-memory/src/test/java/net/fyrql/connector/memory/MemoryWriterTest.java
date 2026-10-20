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

package net.fyrql.connector.memory;

import net.fyrql.connector.Batches;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.ServiceLoader;

import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tables that SQL writes, through the {@link Writer} of the connector. */
class MemoryWriterTest {

    private static final DataType.Struct POINT = DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
            new DataType.Struct.Field("y", DataType.VARCHAR));
    private static final Schema SCHEMA = Schema.of(new Schema.Field("n", DataType.INTEGER, false),
            new Schema.Field("p", POINT));
    private static final TableRef T = TableRef.of("mem", "t");

    private static Batch batch(int from, int to) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(new Object[] {i, i % 3 == 0 ? null : List.of(i * 10, "y" + i)});
        }
        // The names of the columns of a batch need not be those of the table.
        return Batch.ofRows(Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", POINT)), rows);
    }

    private static List<List<Object>> rows(Connector c, TableRef table) {
        ScanPlan plan = c.plan(table, ScanRequest.columns(List.of(0, 1)));
        List<List<Object>> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            for (Batch b : Batches.readAll(c.open(split))) {
                for (Object[] r : b.rows()) {
                    rows.add(java.util.Arrays.asList(r));
                }
            }
        }
        return rows;
    }

    @Test
    void aTableAppearsWithTheCommitOfItsCreation() {
        MemoryConnector c = MemoryConnector.builder("mem").build();
        Writer w = c.writer().orElseThrow();
        try (Sink sink = w.create(T, SCHEMA)) {
            sink.write(batch(0, 3));
            sink.write(batch(3, 3));
            sink.write(batch(3, 5));
            assertEquals(List.of(), c.catalog().tables("main"));
            assertEquals(5, sink.commit());
        }
        assertEquals(List.of("t"), c.catalog().tables("main"));
        assertEquals(SCHEMA, c.catalog().schema(T));
        assertEquals(OptionalLong.of(5), c.catalog().rowCount(T));
        assertEquals(List.of(java.util.Arrays.asList(0, null), List.of(1, List.of(10, "y1")),
                List.of(2, List.of(20, "y2")), java.util.Arrays.asList(3, null), List.of(4, List.of(40, "y4"))),
                rows(c, T));

        // A table of no rows is a table too.
        try (Sink sink = w.create(TableRef.of("mem", "empty"), SCHEMA)) {
            assertEquals(0, sink.commit());
        }
        assertEquals(List.of("t", "empty"), c.catalog().tables("main"));
        assertEquals(List.of(), rows(c, TableRef.of("mem", "empty")));
    }

    @Test
    void anAppendGrowsTheTableWithItsCommitAndAQueryKeepsWhatItSawFirst() {
        MemoryConnector c = MemoryConnector.builder("mem").splitRows(2).build();
        Writer w = c.writer().orElseThrow();
        try (Sink sink = w.create(T, SCHEMA)) {
            sink.write(batch(0, 2));
            sink.commit();
        }
        List<Split> before = c.splits(c.plan(T, ScanRequest.columns(List.of(0))));
        try (Sink sink = w.append(T)) {
            sink.write(batch(2, 3));
            sink.write(batch(3, 6));
            assertEquals(OptionalLong.of(2), c.catalog().rowCount(T));
            assertEquals(4, sink.commit());
        }
        assertEquals(OptionalLong.of(6), c.catalog().rowCount(T));
        assertEquals(List.of(0, 1), before.stream().flatMap(s -> Batches.readAll(c.open(s)).stream())
                .flatMap(b -> b.rows().stream()).map(r -> r[0]).toList());

        // Splits of whole batches of at least two rows: [0, 1], [2] + [3, 4, 5].
        List<Split> after = c.splits(c.plan(T, ScanRequest.columns(List.of(0))));
        assertEquals(2, after.size());
        assertEquals(List.of(List.of(0, 1), List.of(2, 3, 4, 5)), after.stream().map(s -> Batches.readAll(c.open(s))
                .stream().flatMap(b -> b.rows().stream()).map(r -> r[0]).toList()).toList());
    }

    @Test
    void closingWithoutACommitLeavesNoTrace() {
        MemoryConnector c = MemoryConnector.builder("mem").build();
        Writer w = c.writer().orElseThrow();
        try (Sink sink = w.create(T, SCHEMA)) {
            sink.write(batch(0, 4));
        }
        assertEquals(List.of(), c.catalog().tables("main"));
        try (Sink sink = w.create(T, SCHEMA)) {
            sink.write(batch(0, 1));
            sink.commit();
        }
        try (Sink sink = w.append(T)) {
            sink.write(batch(1, 4));
        }
        assertEquals(OptionalLong.of(1), c.catalog().rowCount(T));
        Sink closed = w.append(T);
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.write(batch(0, 1)));
        assertThrows(IllegalStateException.class, closed::commit);
    }

    @Test
    void aTableOfJavaObjectsCanBeDroppedButNotWritten() {
        Schema s = Schema.of(new Schema.Field("n", DataType.INTEGER));
        MemoryConnector c = MemoryConnector.builder("mem").table("o", s, List.<Object[]>of(new Object[] {1})).build();
        Writer w = c.writer().orElseThrow();
        TableRef o = TableRef.of("mem", "o");
        assertEquals("Table o of the memory source mem holds Java objects and cannot be written",
                assertThrows(IllegalArgumentException.class, () -> w.append(o)).getMessage());
        assertEquals("The memory source mem has only the schema main, not other",
                assertThrows(IllegalArgumentException.class, () -> w.create(new TableRef("mem", "other", "x"), s))
                        .getMessage());
        w.drop(o);
        assertEquals(List.of(), c.catalog().tables("main"));
        assertThrows(IllegalArgumentException.class, () -> w.drop(o));
    }

    @Test
    void writersThatRaceFindOutWithTheirCommit() {
        MemoryConnector c = MemoryConnector.builder("mem").build();
        Writer w = c.writer().orElseThrow();
        Sink first = w.create(T, SCHEMA);
        Sink second = w.create(T, SCHEMA);
        first.write(batch(0, 1));
        assertEquals(1, first.commit());
        assertEquals("Table with name t already exists in the memory source mem",
                assertThrows(IllegalArgumentException.class, second::commit).getMessage());
        second.close();

        Sink append = w.append(T);
        append.write(batch(1, 2));
        w.drop(T);
        assertEquals("Table t of the memory source mem was dropped or replaced while rows were written to it",
                assertThrows(IllegalArgumentException.class, append::commit).getMessage());
        append.close();
        assertEquals(List.of(), c.catalog().tables("main"));
    }

    @Test
    void aScanTakesFieldsFiltersAndALimitFromTheBatches() {
        MemoryConnector c = MemoryConnector.builder("mem")
                .pushdown(new Capabilities(e -> true, true, o -> false, a -> false, true)).build();
        try (Sink sink = c.writer().orElseThrow().create(T, SCHEMA)) {
            sink.write(batch(0, 4));
            sink.write(batch(4, 8));
            sink.commit();
        }
        // The scan reads (p.x, n): the filter n > 1 refers to the second position.
        Expression filter = gt(col(1, DataType.INTEGER), lit(1));
        ScanPlan plan = c.plan(T, new ScanRequest(List.of(new ColumnPath(1, List.of("x")), ColumnPath.whole(0)),
                List.of(filter), OptionalLong.of(4), List.of(), Optional.empty()));
        assertEquals(List.of(filter), plan.acceptedFilters());
        assertTrue(plan.limitAccepted());
        List<Split> splits = c.splits(plan);
        assertEquals(1, splits.size());
        assertEquals(List.of(java.util.Arrays.asList(20, 2), java.util.Arrays.asList(null, 3), List.of(40, 4),
                List.of(50, 5)), Batches.readAll(c.open(splits.get(0))).stream().flatMap(b -> b.rows().stream())
                .map(java.util.Arrays::asList).toList());
    }

    @Test
    void theFactoryOfTheTypeMemoryMakesAnEmptySource() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("memory")).findFirst().orElseThrow();
        try (Connector c = factory.create("scratch", Map.of("batchSize", "10", "splitRows", "100"))) {
            assertInstanceOf(MemoryConnector.class, c);
            assertEquals("scratch", c.name());
            assertEquals(List.of(), c.catalog().tables("main"));
            assertTrue(c.writer().isPresent());
        }
        assertEquals("The memory source scratch has an unknown option rows; known are batchSize, splitRows",
                assertThrows(IllegalArgumentException.class, () -> factory.create("scratch", Map.of("rows", "1")))
                        .getMessage());
    }
}
