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

package net.fyrql;

import net.fyrql.catalog.EngineCatalog;
import net.fyrql.catalog.Views;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Batches;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.RowChanges;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.RowKey;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import net.fyrql.exec.MemoryBudget;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.sql.bind.BoundStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Runs the statements that write, on the thread of the caller, to their
 * end: checks the target against the catalog of its source, opens a
 * {@link Sink}, runs the plan of the rows as a query and hands its batches
 * to the sink, and commits. A failure, or a cancel of the context, closes
 * the sink without a commit, which abandons what was written. The input is
 * closed before the commit, so that a source that reads and writes the
 * same table has finished reading.
 * <p>
 * {@code COPY ... FROM} is an {@code INSERT INTO} whose rows are those of
 * the table of the file.
 * <p>
 * {@code CREATE OR REPLACE} drops the table first and then creates it; if
 * its query reads the table, the engine reads all rows of the query
 * before it drops the table.
 * <p>
 * {@code CREATE VIEW} and {@code DROP VIEW} change the {@link Views} of the
 * engine, not a source; a view may not take the name of a table that
 * exists, and once it exists it hides a table of its name that appears.
 * <p>
 * {@code UPDATE} and {@code DELETE} go to the source as a whole statement
 * if the source takes them over. Otherwise the engine reads the rows to
 * change completely first, within the memory budget, since they come from
 * the table it then changes, and hands them to {@link RowChanges} by key;
 * of the rows of one key, the last one counts. The count of {@code UPDATE}
 * is that of the rows the query of its rows gives, as in DuckDB, where a
 * row that {@code FROM} matches twice counts twice; that of {@code DELETE}
 * is the number of rows the source deleted.
 * <p>
 * {@code MERGE} reads its changes completely too and applies them for each
 * row of the table in their order, as DuckDB does: after a delete, an
 * update of the row does nothing; after an update, a delete deletes it; of
 * several updates, the last one counts. It hands the deletes to the source
 * first, then the updates, then the inserts. Its count is that of the
 * pairs of rows a branch acted on.
 */
final class Writes {

    /** The label of the writer for the checks of a cancel. */
    private static final String LABEL = "Write";

    /** The rows of a batch of changes that the engine builds. */
    private static final int CHUNK = 4096;

    private final FyrQL engine;

    Writes(FyrQL engine) {
        this.engine = engine;
    }

    /**
     * Runs a statement that writes.
     *
     * @return the number of rows written
     * @throws SqlException if the target does not fit, or a sink refuses what it is given
     */
    long run(BoundStatement statement, QueryContext context, Session session) {
        return switch (statement) {
            case BoundStatement.Query q -> throw new IllegalArgumentException("A query writes nothing");
            case BoundStatement.Explain e -> throw new IllegalArgumentException("EXPLAIN writes nothing");
            case BoundStatement.Copy c -> copy(c, context, session);
            case BoundStatement.CopyFrom c -> insert(new BoundStatement.Insert(c.table(), c.input()), context,
                    session);
            case BoundStatement.Create c -> create(c, context, session);
            case BoundStatement.Insert i -> insert(i, context, session);
            case BoundStatement.Update u -> update(u, context, session);
            case BoundStatement.Delete d -> delete(d, context, session);
            case BoundStatement.Merge m -> merge(m, context, session);
            case BoundStatement.CreateSchema c -> createSchema(c, session);
            case BoundStatement.AddColumn a -> addColumn(a, session);
            case BoundStatement.Drop d -> drop(d, session);
            case BoundStatement.CreateView v -> createView(v, session);
            case BoundStatement.DropView d -> dropView(d);
            case BoundStatement.Set s -> {
                session.apply(s);
                yield 0;
            }
        };
    }

    private long copy(BoundStatement.Copy copy, QueryContext context, Session session) {
        FileWriterProvider provider = engine.catalog().fileWriter(copy.format()).orElseThrow(() ->
                new SqlException("No source writes files of the format " + copy.format()
                        + EngineCatalog.moduleHint(copy.format())
                        + (engine.catalog().fileFormats().isEmpty() ? ""
                        : "; there are " + String.join(", ", engine.catalog().fileFormats())), Position.UNKNOWN));
        EngineCatalog.checkLocal(copy.path());
        engine.catalog().checkFileWrite(copy.path());
        Schema schema = copy.input().schema();
        Map<String, String> options = session.catalog().withTimeZone(copy.options());
        return write(() -> provider.openFile(copy.format(), copy.path(), schema, options),
                rows(copy.input(), session), null, null, context);
    }

    private long create(BoundStatement.Create create, QueryContext context, Session session) {
        TableRef table = create.table();
        Connector connector = session.connector(table.source());
        Writer writer = writer(connector);
        Rows rows = create.input() == null ? null : rows(create.input(), session);
        if (exists(connector.catalog(), table)) {
            switch (create.mode()) {
                case CREATE -> throw new SqlException("Table with name " + table.table() + " already exists",
                        Position.UNKNOWN);
                case IF_NOT_EXISTS -> {
                    return 0;
                }
                case OR_REPLACE -> {
                    if (rows != null && reads(create.input(), table)) {
                        rows = collected(create.input(), context, session);
                    }
                    perform(() -> writer.drop(table));
                }
            }
        }
        return write(() -> writer.create(table, create.columns(), create.primaryKey()), rows, null, null, context);
    }

    private long insert(BoundStatement.Insert insert, QueryContext context, Session session) {
        TableRef table = insert.table();
        Connector connector = session.connector(table.source());
        Writer writer = writer(connector);
        if (!exists(connector.catalog(), table)) {
            throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
        }
        return write(() -> writer.append(table), rows(insert.input(), session), connector.catalog().schema(table),
                table, context);
    }

    private long update(BoundStatement.Update update, QueryContext context, Session session) {
        TableRef table = update.table();
        Connector connector = session.connector(table.source());
        Writer writer = existing(connector, table);
        if (update.values() != null) {
            OptionalLong done = call(() -> writer.update(table, update.columns(), update.values(),
                    update.condition()));
            if (done.isPresent()) {
                return done.getAsLong();
            }
        }
        Schema target = connector.catalog().schema(table);
        int[] keys = update.keys();
        try (Collected rows = collect(update.keyed(), context, session)) {
            if (rows.count() == 0) {
                return 0;
            }
            List<Batch> latest = latest(rows, keys.length);
            for (Batch b : latest) {
                for (int k = 0; k < update.columns().length; k++) {
                    int c = update.columns()[k];
                    if (!target.nullable(c) && b.column(keys.length + k).hasNulls()) {
                        throw new SqlException("NOT NULL constraint failed: " + table.table() + "." + target.name(c),
                                Position.UNKNOWN);
                    }
                }
            }
            change(writer, table, keys, update.columns(), latest, RowChanges::update, context);
            return rows.count();
        }
    }

    private long delete(BoundStatement.Delete delete, QueryContext context, Session session) {
        TableRef table = delete.table();
        Connector connector = session.connector(table.source());
        Writer writer = existing(connector, table);
        if (delete.simple()) {
            OptionalLong done = call(() -> writer.delete(table, delete.condition()));
            if (done.isPresent()) {
                return done.getAsLong();
            }
        }
        try (Collected rows = collect(delete.keyed(), context, session)) {
            if (rows.count() == 0) {
                return 0;
            }
            return change(writer, table, delete.keys(), new int[0], latest(rows, delete.keys().length),
                    RowChanges::delete, context);
        }
    }

    private long merge(BoundStatement.Merge merge, QueryContext context, Session session) {
        TableRef table = merge.table();
        Connector connector = session.connector(table.source());
        Writer writer = existing(connector, table);
        Schema target = connector.catalog().schema(table);
        int keyCount = merge.keys().length;
        int[] key = new int[keyCount];
        Arrays.setAll(key, k -> 1 + k);
        try (Collected rows = collect(merge.changes(), context, session)) {
            if (rows.count() == 0) {
                return 0;
            }
            Places places = Places.of(rows);
            // The state of each row of the table by its key: deleted, or the place of its last update.
            Map<RowKey, Integer> updated = new LinkedHashMap<>();
            Map<RowKey, Integer> deleted = new LinkedHashMap<>();
            List<Integer> inserted = new ArrayList<>();
            int place = 0;
            for (Batch b : rows.batches()) {
                Vector branch = b.column(0);
                Vector[] keys = RowKey.columns(b, key);
                for (int r = 0; r < b.rowCount(); r++, place++) {
                    BoundStatement.MergeAction action = merge.actions().get(((Number) branch.get(r)).intValue() - 1);
                    switch (action) {
                        case DELETE -> {
                            RowKey k = new RowKey(keys, r);
                            updated.remove(k);
                            deleted.putIfAbsent(k, place);
                        }
                        case UPDATE -> {
                            RowKey k = new RowKey(keys, r);
                            if (!deleted.containsKey(k)) {
                                updated.put(k, place);
                            }
                        }
                        case INSERT -> inserted.add(place);
                        case NOTHING -> {
                        }
                    }
                }
            }
            int[] updates = updated.values().stream().mapToInt(Integer::intValue).toArray();
            int[] inserts = inserted.stream().mapToInt(Integer::intValue).toArray();
            int[] updateColumns = new int[keyCount + merge.updated().length];
            for (int c = 0; c < updateColumns.length; c++) {
                updateColumns[c] = c < keyCount ? 1 + c : 1 + keyCount + merge.updated()[c - keyCount];
            }
            int[] all = new int[target.size()];
            Arrays.setAll(all, c -> 1 + keyCount + c);
            List<Batch> deletes = places.gather(rows, deleted.values().stream().mapToInt(Integer::intValue)
                    .toArray(), key);
            List<Batch> updateBatches = places.gather(rows, updates, updateColumns);
            List<Batch> insertBatches = places.gather(rows, inserts, all);
            for (Batch b : updateBatches) {
                for (int k = 0; k < merge.updated().length; k++) {
                    checkNotNull(b.column(keyCount + k), target, merge.updated()[k], table);
                }
            }
            for (Batch b : insertBatches) {
                for (int c = 0; c < target.size(); c++) {
                    checkNotNull(b.column(c), target, c, table);
                }
            }
            RowChanges changes = call(() -> writer.change(table, merge.keys(), merge.updated()));
            try (changes) {
                for (Batch b : deletes) {
                    context.check(LABEL);
                    perform(() -> changes.delete(b));
                }
                for (Batch b : updateBatches) {
                    context.check(LABEL);
                    perform(() -> changes.update(b));
                }
                for (Batch b : insertBatches) {
                    context.check(LABEL);
                    perform(() -> changes.insert(b));
                }
                context.check(LABEL);
                call(changes::commit);
            }
            return rows.count();
        }
    }

    /** Fails if a column of the table that is {@code NOT NULL} gets a {@code NULL}. */
    private static void checkNotNull(Vector values, Schema target, int column, TableRef table) {
        if (!target.nullable(column) && values.hasNulls()) {
            throw new SqlException("NOT NULL constraint failed: " + table.table() + "." + target.name(column),
                    Position.UNKNOWN);
        }
    }

    /** The batch and the row in it of each row of collected batches, by the place of the row among all. */
    private record Places(int[] batch, int[] row) {

        static Places of(Collected rows) {
            int[] batch = new int[Math.toIntExact(rows.count())];
            int[] row = new int[batch.length];
            int place = 0;
            for (int b = 0; b < rows.batches().size(); b++) {
                for (int r = 0; r < rows.batches().get(b).rowCount(); r++, place++) {
                    batch[place] = b;
                    row[place] = r;
                }
            }
            return new Places(batch, row);
        }

        /** Returns the given columns of the rows at the given places, in that order, in batches. */
        List<Batch> gather(Collected rows, int[] places, int[] columns) {
            List<Batch> batches = rows.batches();
            Schema all = batches.get(0).schema();
            List<Schema.Field> fields = new ArrayList<>();
            for (int c : columns) {
                fields.add(all.fields().get(c));
            }
            Schema schema = new Schema(fields);
            int[] partOf = new int[places.length];
            int[] rowOf = new int[places.length];
            for (int i = 0; i < places.length; i++) {
                partOf[i] = batch[places[i]];
                rowOf[i] = row[places[i]];
            }
            List<Batch> result = new ArrayList<>();
            for (int from = 0; from < places.length; from += CHUNK) {
                int to = Math.min(places.length, from + CHUNK);
                List<Vector> vectors = new ArrayList<>();
                for (int c : columns) {
                    List<Vector> parts = new ArrayList<>();
                    for (Batch b : batches) {
                        parts.add(b.column(c));
                    }
                    vectors.add(Vectors.gather(all.type(c), parts, partOf, rowOf, from, to));
                }
                result.add(new Batch(schema, to - from, vectors));
            }
            return result;
        }
    }

    /** Hands batches to one kind of change of a table and commits. */
    private static long change(Writer writer, TableRef table, int[] keys, int[] updated, List<Batch> batches,
            java.util.function.BiConsumer<RowChanges, Batch> kind, QueryContext context) {
        RowChanges changes = call(() -> writer.change(table, keys, updated));
        try (changes) {
            for (Batch b : batches) {
                context.check(LABEL);
                perform(() -> kind.accept(changes, b));
            }
            context.check(LABEL);
            return call(changes::commit);
        }
    }

    /**
     * Returns the rows of each key once, the last of its rows, in batches;
     * the batches as they are if no key comes twice.
     *
     * @param keys the number of key columns, the first ones
     */
    private static List<Batch> latest(Collected rows, int keys) {
        int[] key = new int[keys];
        Arrays.setAll(key, k -> k);
        Map<RowKey, Integer> last = new HashMap<>();
        int place = 0;
        for (Batch batch : rows.batches()) {
            Vector[] columns = RowKey.columns(batch, key);
            for (int r = 0; r < batch.rowCount(); r++) {
                last.put(new RowKey(columns, r), place++);
            }
        }
        if (last.size() == place) {
            return rows.batches();
        }
        int[] all = new int[rows.batches().get(0).schema().size()];
        Arrays.setAll(all, c -> c);
        return Places.of(rows).gather(rows, last.values().stream().mapToInt(Integer::intValue).sorted().toArray(),
                all);
    }

    private long createSchema(BoundStatement.CreateSchema create, Session session) {
        Connector connector = session.connector(create.source());
        Writer writer = writer(connector);
        for (String s : connector.catalog().schemas()) {
            if (s.equalsIgnoreCase(create.name())) {
                if (create.ifNotExists()) {
                    return 0;
                }
                throw new SqlException("Schema with name \"" + create.name() + "\" already exists!",
                        Position.UNKNOWN);
            }
        }
        perform(() -> writer.createSchema(create.name()));
        return 0;
    }

    private long addColumn(BoundStatement.AddColumn add, Session session) {
        TableRef table = add.table();
        Connector connector = session.connector(table.source());
        Writer writer = existing(connector, table);
        for (Schema.Field f : connector.catalog().schema(table).fields()) {
            if (f.name().equalsIgnoreCase(add.column().name())) {
                if (add.ifNotExists()) {
                    return 0;
                }
                throw new SqlException("Column with name " + add.column().name() + " already exists!",
                        Position.UNKNOWN);
            }
        }
        perform(() -> writer.addColumn(table, add.column(), add.defaultValue()));
        return 0;
    }

    private long drop(BoundStatement.Drop drop, Session session) {
        TableRef table = drop.table();
        if (table == null) {
            return 0;
        }
        Connector connector = session.connector(table.source());
        Writer writer = writer(connector);
        if (!exists(connector.catalog(), table)) {
            if (drop.ifExists()) {
                return 0;
            }
            throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
        }
        perform(() -> writer.drop(table));
        return 0;
    }

    private long createView(BoundStatement.CreateView create, Session session) {
        TableRef view = create.view();
        Views views = engine.catalog().views();
        synchronized (views) {
            boolean table = views.get(view).isEmpty() && exists(session.connector(view.source()).catalog(), view);
            if (views.get(view).isPresent() || table) {
                switch (create.mode()) {
                    case CREATE -> throw new SqlException((table ? "Table" : "View") + " with name " + view.table()
                            + " already exists", Position.UNKNOWN);
                    case IF_NOT_EXISTS -> {
                        return 0;
                    }
                    case OR_REPLACE -> {
                        if (table) {
                            throw new SqlException("Table with name " + view.table() + " already exists; a view"
                                    + " replaces only a view", Position.UNKNOWN);
                        }
                    }
                }
            }
            views.put(view, create.definition());
        }
        return 0;
    }

    private long dropView(BoundStatement.DropView drop) {
        if (drop.view() != null && !engine.catalog().views().remove(drop.view()) && !drop.ifExists()) {
            throw new SqlException("View with name " + drop.view() + " does not exist", Position.UNKNOWN);
        }
        return 0;
    }

    /** The rows to write: batches on demand, closed after the last one. */
    private interface Rows {
        Operator open(QueryContext context);
    }

    /** The rows of a plan, which runs as a query once they are opened. */
    private Rows rows(PlanNode input, Session session) {
        return context -> engine.open(input, context, session);
    }

    /**
     * The rows of a plan, read to their end and held within the memory
     * budget until they are closed.
     */
    private record Collected(List<Batch> batches, long count, MemoryBudget budget, long reserved)
            implements AutoCloseable {
        @Override
        public void close() {
            budget.release(reserved);
        }
    }

    private Collected collect(PlanNode input, QueryContext context, Session session) {
        MemoryBudget budget = session.execution().executor().budget();
        List<Batch> batches = new ArrayList<>();
        long count = 0;
        long reserved = 0;
        try (Operator op = engine.open(input, context, session)) {
            for (Batch b = op.next(); b != null; b = op.next()) {
                context.check(LABEL);
                if (b.rowCount() > 0) {
                    long bytes = Batches.estimateBytes(b);
                    budget.reserve(bytes, LABEL);
                    reserved += bytes;
                    batches.add(b);
                    count += b.rowCount();
                }
            }
        } catch (RuntimeException e) {
            budget.release(reserved);
            throw e;
        }
        return new Collected(batches, count, budget, reserved);
    }

    /** Checks that a table exists and returns the writer of its source. */
    private static Writer existing(Connector connector, TableRef table) {
        Writer writer = writer(connector);
        if (!exists(connector.catalog(), table)) {
            throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
        }
        return writer;
    }

    /** The rows of a plan, read to their end at once. */
    private Rows collected(PlanNode input, QueryContext context, Session session) {
        List<Batch> batches = new ArrayList<>();
        Schema schema;
        try (Operator op = engine.open(input, context, session)) {
            schema = op.schema();
            for (Batch b = op.next(); b != null; b = op.next()) {
                context.check(LABEL);
                batches.add(b);
            }
        }
        return c -> new Operator() {
            private int next;

            @Override
            public Schema schema() {
                return schema;
            }

            @Override
            public Batch next() {
                return next < batches.size() ? batches.get(next++) : null;
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Writes rows into a sink and commits. The sink opens before the rows,
     * so that it refuses its options or the types of the columns before a
     * source is read.
     *
     * @param rows the rows, or {@code null} for none
     * @param target the columns of the table, whose {@code NOT NULL} the
     *        rows are checked against, or {@code null}
     * @param table the table of the target, for the message of a {@code NULL}
     */
    private long write(Supplier<Sink> open, Rows rows, Schema target, TableRef table, QueryContext context) {
        Sink sink = call(open);
        try (sink) {
            if (rows != null) {
                try (Operator op = rows.open(context)) {
                    for (Batch b = op.next(); b != null; b = op.next()) {
                        context.check(LABEL);
                        if (target != null) {
                            checkNotNull(b, target, table);
                        }
                        if (b.rowCount() > 0) {
                            Batch batch = b;
                            perform(() -> sink.write(batch));
                        }
                    }
                }
            }
            context.check(LABEL);
            return call(sink::commit);
        }
    }

    /** Fails if a column that is {@code NOT NULL} in the target has a {@code NULL} in a batch. */
    private static void checkNotNull(Batch batch, Schema target, TableRef table) {
        for (int c = 0; c < target.size(); c++) {
            if (!target.nullable(c) && batch.column(c).hasNulls()) {
                for (int r = 0; r < batch.rowCount(); r++) {
                    if (batch.column(c).isNull(r)) {
                        throw new SqlException("NOT NULL constraint failed: " + table.table() + "." + target.name(c),
                                Position.UNKNOWN);
                    }
                }
            }
        }
    }

    /** Tells whether a plan reads a table. */
    private static boolean reads(PlanNode plan, TableRef table) {
        for (PlanNode.Scan s : Plans.scans(plan)) {
            if (s.table().equals(table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exists(Catalog catalog, TableRef table) {
        return catalog.schemas().contains(table.schema()) && catalog.tables(table.schema()).contains(table.table());
    }

    private static Writer writer(Connector connector) {
        return connector.writer().orElseThrow(() -> new SqlException("Source " + connector.name()
                + " does not write tables", Position.UNKNOWN));
    }

    /** Calls a connector; its message for the user becomes the error of the statement. */
    private static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new SqlException(e.getMessage(), Position.UNKNOWN);
        }
    }

    private static void perform(Runnable call) {
        call(() -> {
            call.run();
            return null;
        });
    }
}
