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

package net.fyrql.connector.jdbc;

import net.fyrql.connector.RowChanges;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Writer;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.sql.render.BoundSql;
import net.fyrql.sql.render.Dialect;
import net.fyrql.sql.render.SqlRenderer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Writes the tables of a JDBC source. A sink is one transaction over one
 * connection: {@code CREATE TABLE} with the type names of the dialect,
 * then {@code INSERT INTO t ("a", ...) VALUES (?, ...)} as a prepared
 * statement, its rows sent in batches of a number of rows, and the commit;
 * a sink closed before its commit rolls back. A database whose
 * {@code CREATE TABLE} commits at once, such as MySQL, keeps the empty
 * table after a rollback; the writer then drops it, as far as it can. The
 * catalog of the source reads the database again after a commit and after
 * a table is dropped.
 * <p>
 * An {@code UPDATE} or {@code DELETE} goes to the database as one statement
 * when the dialect renders all its expressions with the semantics of the
 * engine and none of them reads a column that holds instants, the rule of
 * the pushdown of filters. Otherwise the engine hands the rows by key: an
 * {@code UPDATE ... WHERE "k" = ?} or {@code DELETE ... WHERE "k" = ?} for
 * each, with {@code "k" IS NULL} for a key that is {@code NULL}, and
 * {@code INSERT}s, sent in batches in one transaction.
 */
final class JdbcWriter implements Writer {

    private final String source;
    private final Connections connections;
    private final JdbcDialect dialect;
    private final Dialect sql;
    private final JdbcCatalog catalog;
    private final int batchRows;
    /** Records a statement before it is sent. */
    private final Consumer<String> record;

    JdbcWriter(String source, Connections connections, JdbcDialect dialect, Dialect sql, JdbcCatalog catalog,
            int batchRows, Consumer<String> record) {
        this.source = source;
        this.connections = connections;
        this.dialect = dialect;
        this.sql = sql;
        this.catalog = catalog;
        this.batchRows = batchRows;
        this.record = record;
    }

    /**
     * Creates a table in the types the dialect writes, see
     * {@link Dialect#typeName}.
     *
     * @throws IllegalArgumentException if the dialect has no type for a
     *         column, such as a {@code STRUCT}, or {@code HUGEINT} in
     *         SQLite and in the generic dialect
     */
    @Override
    public Sink create(TableRef table, Schema schema) {
        return create(table, schema, List.of());
    }

    /** Creates a table with a primary key, which the database keeps. */
    @Override
    public Sink create(TableRef table, Schema schema, List<String> primaryKey) {
        for (int c = 0; c < schema.size(); c++) {
            try {
                if (primaryKey.contains(schema.name(c))) {
                    sql.keyTypeName(schema.type(c));
                } else {
                    sql.typeName(schema.type(c));
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The JDBC source " + source + " cannot create " + table + ": "
                        + e.getMessage() + " (column " + schema.name(c) + ")");
            }
        }
        String name = catalog.qualifiedName(table);
        return open(table, name, schema, new BitSet(), renderer().createTable(name, schema, primaryKey));
    }

    @Override
    public Sink append(TableRef table) {
        return open(table, catalog.qualifiedName(table), catalog.schema(table), catalog.withTimeZone(table), null);
    }

    @Override
    public OptionalLong update(TableRef table, int[] columns, List<Expression> values, Expression condition) {
        BitSet zoned = zoned(table);
        for (int k = 0; k < columns.length; k++) {
            Expression v = values.get(k);
            if (zoned.get(columns[k]) || !sql.supportsValue(v) || Expressions.columns(v).intersects(zoned)) {
                return OptionalLong.empty();
            }
        }
        if (condition != null && !pushable(condition, zoned)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(run(renderer().update(catalog.qualifiedName(table), catalog.schema(table), columns,
                values, condition)));
    }

    @Override
    public OptionalLong delete(TableRef table, Expression condition) {
        if (condition != null && !pushable(condition, zoned(table))) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(run(renderer().delete(catalog.qualifiedName(table), catalog.schema(table),
                condition)));
    }

    @Override
    public RowChanges change(TableRef table, int[] keys, int[] updated) {
        String name = catalog.qualifiedName(table);
        Schema schema = catalog.schema(table);
        Transaction t;
        try {
            t = new Transaction();
        } catch (SQLException e) {
            throw new JdbcSourceException("The JDBC source " + source + " cannot open a connection to change "
                    + table + ": " + e.getMessage(), e);
        }
        return new KeyedRows(table, name, schema, keys.clone(), updated.clone(), catalog.withTimeZone(table), t);
    }

    /** Creates a schema; SQLite has none but its databases. */
    @Override
    public void createSchema(String schema) {
        if (dialect == JdbcDialect.SQLITE) {
            throw new IllegalArgumentException("The JDBC source " + source + " is SQLite, which has no schemas");
        }
        String statement = "CREATE SCHEMA " + sql.quoteIdentifier(schema);
        try {
            Transaction t = new Transaction();
            try {
                t.execute(statement);
                t.commit();
            } catch (SQLException e) {
                try {
                    t.rollback();
                } catch (SQLException again) {
                    e.addSuppressed(again);
                }
                throw e;
            } finally {
                t.end();
                catalog.refresh();
            }
        } catch (SQLException e) {
            throw fails(statement, e);
        }
    }

    /**
     * Adds a column with {@code ALTER TABLE ... ADD COLUMN} and, for a value
     * other than {@code NULL}, sets it in every row with an {@code UPDATE}
     * of a parameter, in one transaction where the database allows; the
     * database keeps no default for later rows.
     */
    @Override
    public void addColumn(TableRef table, Schema.Field column, Object defaultValue) {
        String type;
        try {
            type = sql.typeName(column.type());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The JDBC source " + source + " cannot add the column "
                    + column.name() + " to " + table + ": " + e.getMessage());
        }
        String name = catalog.qualifiedName(table);
        String quoted = sql.quoteIdentifier(column.name());
        String alter = "ALTER TABLE " + name + " ADD COLUMN " + quoted + " " + type;
        String update = "UPDATE " + name + " SET " + quoted + " = ?";
        String current = alter;
        try {
            Transaction t = new Transaction();
            try {
                t.execute(alter);
                if (defaultValue != null) {
                    current = update;
                    record.accept(update);
                    try (PreparedStatement p = t.connection.prepareStatement(update)) {
                        JdbcBinding.setters(dialect, p, List.of(column.type()), new BitSet())[0].set(p, 1,
                                defaultValue);
                        p.executeUpdate();
                    }
                }
                t.commit();
            } catch (SQLException e) {
                try {
                    t.rollback();
                } catch (SQLException again) {
                    e.addSuppressed(again);
                }
                throw e;
            } finally {
                t.end();
                catalog.refresh();
            }
        } catch (SQLException e) {
            throw fails(current, e);
        }
    }

    /** The columns of a table that hold instants, which the database compares in the time zone of its session. */
    private BitSet zoned(TableRef table) {
        BitSet zoned = catalog.withTimeZone(table);
        if (JdbcTypes.comparesInstants(dialect)) {
            zoned.clear();
        }
        return zoned;
    }

    private boolean pushable(Expression e, BitSet zoned) {
        return sql.supports(e) && !Expressions.columns(e).intersects(zoned);
    }

    /** Runs one statement in a transaction of its own and returns the number of rows it changed. */
    private long run(BoundSql statement) {
        String shown = statement.parameters().isEmpty() ? statement.sql() : statement.toString();
        try {
            Transaction t = new Transaction();
            try {
                record.accept(shown);
                long count;
                try (PreparedStatement p = t.connection.prepareStatement(statement.sql())) {
                    JdbcReader.bind(p, statement.parameters());
                    count = p.executeUpdate();
                }
                t.commit();
                return count;
            } catch (SQLException | RuntimeException e) {
                try {
                    t.rollback();
                } catch (SQLException again) {
                    e.addSuppressed(again);
                }
                throw e;
            } finally {
                t.end();
                catalog.refresh();
            }
        } catch (SQLException e) {
            throw fails(shown, e);
        }
    }

    @Override
    public void drop(TableRef table) {
        String statement = "DROP TABLE " + catalog.qualifiedName(table);
        try {
            Transaction t = new Transaction();
            try {
                t.execute(statement);
                t.commit();
            } catch (SQLException e) {
                try {
                    t.rollback();
                } catch (SQLException again) {
                    e.addSuppressed(again);
                }
                throw e;
            } finally {
                t.end();
                catalog.refresh();
            }
        } catch (SQLException e) {
            throw fails(statement, e);
        }
    }

    private SqlRenderer renderer() {
        return new SqlRenderer(sql, catalog::qualifiedName);
    }

    /** Opens a sink, which runs the {@code CREATE TABLE} if there is one. */
    private Sink open(TableRef table, String name, Schema schema, BitSet withTimeZone, String create) {
        String insert = renderer().insertPrepared(name, schema);
        Transaction t;
        try {
            t = new Transaction();
        } catch (SQLException e) {
            throw fails(create != null ? create : insert, e);
        }
        RowSink sink = new RowSink(table, t, insert);
        try {
            if (create != null) {
                t.execute(create);
                sink.created = true;
            }
            record.accept(insert);
            sink.statement = t.connection.prepareStatement(insert);
            sink.setters = JdbcBinding.setters(dialect, sink.statement, schema.types(), withTimeZone);
            return sink;
        } catch (SQLException e) {
            JdbcSourceException failure = fails(sink.created || create == null ? insert : create, e);
            sink.abandon(failure);
            throw failure;
        } catch (RuntimeException e) {
            sink.abandon(e);
            throw e;
        }
    }

    private JdbcSourceException fails(String statement, SQLException e) {
        return new JdbcSourceException("The JDBC source " + source + " fails on " + statement + ": "
                + e.getMessage(), e);
    }

    /**
     * A connection whose auto-commit is off until {@link #end()}, which
     * gives it back.
     */
    private final class Transaction {
        final Connection connection;
        private final boolean autoCommitChanged;

        Transaction() throws SQLException {
            connection = connections.open();
            try {
                autoCommitChanged = connection.getAutoCommit();
                if (autoCommitChanged) {
                    connection.setAutoCommit(false);
                }
            } catch (SQLException | RuntimeException e) {
                connections.release(connection);
                throw e;
            }
        }

        void execute(String statement) throws SQLException {
            record.accept(statement);
            try (Statement s = connection.createStatement()) {
                s.execute(statement);
            }
        }

        void commit() throws SQLException {
            connection.commit();
        }

        void rollback() throws SQLException {
            connection.rollback();
        }

        /** Turns the auto-commit back on if it was on, and releases the connection. */
        void end() throws SQLException {
            SQLException failure = null;
            try {
                if (autoCommitChanged) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                failure = e;
            }
            try {
                connections.release(connection);
            } catch (SQLException e) {
                failure = failure == null ? e : failure;
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * Changes of rows by key in one transaction: a prepared statement for
     * each kind of change and each pattern of {@code NULL}s in the key, its
     * rows sent in batches of {@code batchRows}, in the order of the calls.
     */
    private final class KeyedRows implements RowChanges {
        private final TableRef table;
        private final String name;
        private final Schema schema;
        private final int[] keys;
        private final int[] updated;
        private final BitSet withTimeZone;
        private final Transaction transaction;
        private final Map<String, Prepared> statements = new LinkedHashMap<>();
        /** The kind of the last change: 'u', 'd' or 'i'. */
        private char kind;
        private long count;
        private boolean done;

        /** A prepared statement with the columns of the batch it binds and their setters. */
        private final class Prepared {
            final String sql;
            final PreparedStatement statement;
            final int[] columns;
            final JdbcBinding.Setter[] setters;
            int pending;

            Prepared(String sql, int[] columns, List<DataType> types, BitSet zoned) throws SQLException {
                this.sql = sql;
                record.accept(sql);
                this.statement = transaction.connection.prepareStatement(sql);
                this.columns = columns;
                this.setters = JdbcBinding.setters(dialect, statement, types, zoned);
            }

            void add(Batch batch, int row) throws SQLException {
                for (int c = 0; c < columns.length; c++) {
                    setters[c].set(statement, c + 1, batch.column(columns[c]).get(row));
                }
                statement.addBatch();
                if (++pending == batchRows) {
                    send();
                }
            }

            void send() throws SQLException {
                if (pending > 0) {
                    for (int n : statement.executeBatch()) {
                        count += n >= 0 ? n : n == Statement.SUCCESS_NO_INFO ? 1 : 0;
                    }
                    pending = 0;
                }
            }
        }

        KeyedRows(TableRef table, String name, Schema schema, int[] keys, int[] updated, BitSet withTimeZone,
                Transaction transaction) {
            this.table = table;
            this.name = name;
            this.schema = schema;
            this.keys = keys;
            this.updated = updated;
            this.withTimeZone = withTimeZone;
            this.transaction = transaction;
        }

        @Override
        public void update(Batch batch) {
            change('u', batch);
        }

        @Override
        public void delete(Batch batch) {
            change('d', batch);
        }

        @Override
        public void insert(Batch batch) {
            change('i', batch);
        }

        private void change(char what, Batch batch) {
            if (done) {
                throw new IllegalStateException("The changes are committed or closed");
            }
            String sql = null;
            try {
                if (what != kind) {
                    sendAll();
                    kind = what;
                }
                Vector[] key = new Vector[what == 'i' ? 0 : keys.length];
                for (int k = 0; k < key.length; k++) {
                    key[k] = batch.column(k);
                }
                for (int r = 0; r < batch.rowCount(); r++) {
                    BitSet nulls = new BitSet();
                    for (int k = 0; k < key.length; k++) {
                        nulls.set(k, key[k].isNull(r));
                    }
                    Prepared p = prepared(what, nulls);
                    sql = p.sql;
                    p.add(batch, r);
                }
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " fails on " + (sql == null ? "the"
                        + " changes of " + table : sql) + ": " + e.getMessage(), e);
            }
        }

        /** Returns the statement of a kind of change for a pattern of {@code NULL}s in the key, prepared once. */
        private Prepared prepared(char what, BitSet nulls) throws SQLException {
            SqlRenderer renderer = renderer();
            String text = switch (what) {
                case 'i' -> renderer.insertPrepared(name, schema);
                case 'd' -> "DELETE FROM " + name + " WHERE " + renderer.keyCondition(schema, keys, nulls);
                default -> {
                    StringBuilder b = new StringBuilder("UPDATE ").append(name).append(" SET ");
                    for (int k = 0; k < updated.length; k++) {
                        b.append(k > 0 ? ", " : "").append(sql.quoteIdentifier(schema.name(updated[k])))
                                .append(" = ?");
                    }
                    yield b.append(" WHERE ").append(renderer.keyCondition(schema, keys, nulls)).toString();
                }
            };
            Prepared p = statements.get(text);
            if (p == null) {
                // The columns of the batch the statement binds, with the columns of the table they are.
                List<Integer> columns = new ArrayList<>();
                List<Integer> tableColumns = new ArrayList<>();
                if (what == 'i') {
                    for (int c = 0; c < schema.size(); c++) {
                        columns.add(c);
                        tableColumns.add(c);
                    }
                }
                if (what == 'u') {
                    for (int k = 0; k < updated.length; k++) {
                        columns.add(keys.length + k);
                        tableColumns.add(updated[k]);
                    }
                }
                if (what != 'i') {
                    for (int k = 0; k < keys.length; k++) {
                        if (!nulls.get(k)) {
                            columns.add(k);
                            tableColumns.add(keys[k]);
                        }
                    }
                }
                BitSet zoned = new BitSet();
                List<DataType> types = new ArrayList<>();
                for (int i = 0; i < tableColumns.size(); i++) {
                    types.add(schema.type(tableColumns.get(i)));
                    zoned.set(i, withTimeZone.get(tableColumns.get(i)));
                }
                p = new Prepared(text, columns.stream().mapToInt(Integer::intValue).toArray(), types, zoned);
                statements.put(text, p);
            }
            return p;
        }

        private void sendAll() throws SQLException {
            for (Prepared p : statements.values()) {
                p.send();
            }
        }

        @Override
        public long commit() {
            if (done) {
                throw new IllegalStateException("The changes are committed or closed");
            }
            try {
                sendAll();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " fails on the changes of " + table
                        + ": " + e.getMessage(), e);
            }
            try {
                transaction.commit();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot commit the changes of " + table
                        + ": " + e.getMessage(), e);
            }
            done = true;
            try {
                release();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot release its connection after"
                        + " the changes of " + table + ": " + e.getMessage(), e);
            } finally {
                catalog.refresh();
            }
            return count;
        }

        @Override
        public void close() {
            if (done) {
                return;
            }
            done = true;
            JdbcSourceException failure = new JdbcSourceException("The JDBC source " + source
                    + " cannot roll back the changes of " + table);
            try {
                transaction.rollback();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            try {
                release();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            if (failure.getSuppressed().length > 0) {
                throw failure;
            }
        }

        private void release() throws SQLException {
            try {
                for (Prepared p : statements.values()) {
                    p.statement.close();
                }
            } finally {
                transaction.end();
            }
        }
    }

    /** The sink of one write: the rows of the batches, sent in batches of {@code batchRows}. */
    private final class RowSink implements Sink {
        private final TableRef table;
        private final Transaction transaction;
        private final String insert;
        PreparedStatement statement;
        JdbcBinding.Setter[] setters;
        /** Whether the sink ran a {@code CREATE TABLE}. */
        boolean created;
        private long rows;
        /** The rows added to the statement since it was last sent. */
        private int pending;
        private boolean done;

        RowSink(TableRef table, Transaction transaction, String insert) {
            this.table = table;
            this.transaction = transaction;
            this.insert = insert;
        }

        @Override
        public void write(Batch batch) {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            int column = -1;
            try {
                for (int r = 0; r < batch.rowCount(); r++) {
                    for (column = 0; column < setters.length; column++) {
                        setters[column].set(statement, column + 1, batch.column(column).get(r));
                    }
                    column = -1;
                    statement.addBatch();
                    rows++;
                    if (++pending == batchRows) {
                        send();
                    }
                }
            } catch (SQLException e) {
                String where = column >= 0 ? " at row " + (rows + 1) + ", column " + (column + 1)
                        : " in the rows " + (rows - pending + 1) + " to " + rows;
                throw new JdbcSourceException("The JDBC source " + source + " fails on " + insert + where + ": "
                        + e.getMessage(), e);
            }
        }

        /** Sends the rows added since the last time. */
        private void send() throws SQLException {
            if (pending > 0) {
                statement.executeBatch();
                pending = 0;
            }
        }

        @Override
        public long commit() {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            try {
                send();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " fails on " + insert + " in the rows "
                        + (rows - pending + 1) + " to " + rows + ": " + e.getMessage(), e);
            }
            try {
                transaction.commit();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot commit the rows of " + table
                        + ": " + e.getMessage(), e);
            }
            done = true;
            try {
                release();
            } catch (SQLException e) {
                throw new JdbcSourceException("The JDBC source " + source + " cannot release its connection after"
                        + " the rows of " + table + ": " + e.getMessage(), e);
            } finally {
                catalog.refresh();
            }
            return rows;
        }

        /** Rolls back unless committed; the first failure is thrown, see {@link #abandon}. */
        @Override
        public void close() {
            if (done) {
                return;
            }
            JdbcSourceException failure = new JdbcSourceException("The JDBC source " + source
                    + " cannot roll back the rows of " + table);
            abandon(failure);
            if (failure.getSuppressed().length > 0) {
                throw failure;
            }
        }

        /**
         * Rolls back, gives the connection back and drops a table that
         * stays after the rollback; a failure of it is added to the one
         * given. A table that cannot be dropped is left behind.
         */
        void abandon(Throwable failure) {
            done = true;
            try {
                transaction.rollback();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            try {
                release();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            if (created) {
                catalog.refresh();
                try {
                    if (catalog.tables(table.schema()).contains(table.table())) {
                        // The CREATE TABLE committed at once.
                        drop(table);
                    }
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
        }

        private void release() throws SQLException {
            try {
                if (statement != null) {
                    statement.close();
                }
            } finally {
                transaction.end();
            }
        }
    }
}
