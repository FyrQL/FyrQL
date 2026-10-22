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
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.Operator;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.bind.BoundStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 */
final class Writes {

    /** The label of the writer for the checks of a cancel. */
    private static final String LABEL = "Write";

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
    long run(BoundStatement statement, QueryContext context) {
        return switch (statement) {
            case BoundStatement.Query q -> throw new IllegalArgumentException("A query writes nothing");
            case BoundStatement.Explain e -> throw new IllegalArgumentException("EXPLAIN writes nothing");
            case BoundStatement.Copy c -> copy(c, context);
            case BoundStatement.CopyFrom c -> insert(new BoundStatement.Insert(c.table(), c.input()), context);
            case BoundStatement.Create c -> create(c, context);
            case BoundStatement.Insert i -> insert(i, context);
            case BoundStatement.Drop d -> drop(d);
            case BoundStatement.Set s -> {
                engine.apply(s);
                yield 0;
            }
        };
    }

    private long copy(BoundStatement.Copy copy, QueryContext context) {
        FileWriterProvider provider = engine.catalog().fileWriter(copy.format()).orElseThrow(() ->
                new SqlException("No source writes files of the format " + copy.format()
                        + EngineCatalog.moduleHint(copy.format())
                        + (engine.catalog().fileFormats().isEmpty() ? ""
                        : "; there are " + String.join(", ", engine.catalog().fileFormats())), Position.UNKNOWN));
        EngineCatalog.checkLocal(copy.path());
        engine.catalog().checkFileWrite(copy.path());
        Schema schema = copy.input().schema();
        Map<String, String> options = engine.catalog().withTimeZone(copy.options());
        return write(() -> provider.openFile(copy.format(), copy.path(), schema, options), rows(copy.input()),
                null, null, context);
    }

    private long create(BoundStatement.Create create, QueryContext context) {
        TableRef table = create.table();
        Connector connector = engine.connector(table.source());
        Writer writer = writer(connector);
        Rows rows = create.input() == null ? null : rows(create.input());
        if (exists(connector.catalog(), table)) {
            switch (create.mode()) {
                case CREATE -> throw new SqlException("Table with name " + table.table() + " already exists",
                        Position.UNKNOWN);
                case IF_NOT_EXISTS -> {
                    return 0;
                }
                case OR_REPLACE -> {
                    if (rows != null && reads(create.input(), table)) {
                        rows = collect(create.input(), context);
                    }
                    perform(() -> writer.drop(table));
                }
            }
        }
        return write(() -> writer.create(table, create.schema()), rows, null, null, context);
    }

    private long insert(BoundStatement.Insert insert, QueryContext context) {
        TableRef table = insert.table();
        Connector connector = engine.connector(table.source());
        Writer writer = writer(connector);
        if (!exists(connector.catalog(), table)) {
            throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
        }
        return write(() -> writer.append(table), rows(insert.input()), connector.catalog().schema(table), table,
                context);
    }

    private long drop(BoundStatement.Drop drop) {
        TableRef table = drop.table();
        if (table == null) {
            return 0;
        }
        Connector connector = engine.connector(table.source());
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

    /** The rows to write: batches on demand, closed after the last one. */
    private interface Rows {
        Operator open(QueryContext context);
    }

    /** The rows of a plan, which runs as a query once they are opened. */
    private Rows rows(PlanNode input) {
        return context -> engine.open(input, context);
    }

    /** The rows of a plan, read to their end at once. */
    private Rows collect(PlanNode input, QueryContext context) {
        List<Batch> batches = new ArrayList<>();
        Schema schema;
        try (Operator op = engine.open(input, context)) {
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
