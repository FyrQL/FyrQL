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

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.Connector;
import net.fyrql.connector.RowChanges;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.Writer;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.RowKey;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.RandomAccess;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java collections as tables: a collection of records or beans, whose
 * properties become the columns, or rows of FyrQL values with an explicit
 * schema. All tables live in the schema {@code main}. The connector reads
 * the collection when a scan runs, so later changes to the collection are
 * visible to later queries.
 * <p>
 * SQL creates tables with {@code CREATE TABLE}, fills them with
 * {@code INSERT INTO} and drops them with {@code DROP TABLE}, through the
 * {@link #writer()}: such a table holds the batches written to it, which
 * a scan reads as they are. A table appears, or grows, with the commit of
 * a statement; a query reads the batches its table had when the query
 * started. A table of Java objects can be dropped but not written. A
 * table created with a primary key checks it with every write.
 * <p>
 * {@code UPDATE} and {@code DELETE} run here as whole statements, with the
 * evaluator of the engine; their changes, and those of {@code MERGE} by
 * key, replace the batches of the table at once, so that a query that
 * runs reads the rows as they were.
 * <p>
 * By default the connector takes over nothing of a scan. For tests of the
 * engine it can take over filters and a limit and read fields inside
 * struct columns, see
 * {@link Builder#pushdown}; it evaluates the filters with the evaluator of
 * the engine, so the results are the same either way.
 * <p>
 * A table of a list with random access, such as an {@code ArrayList}, has a
 * split for every {@link Builder#splitRows} rows, which the engine may read
 * in parallel; the list must then not change while a query reads it. So
 * has a table that SQL wrote, in whole batches.
 */
public final class MemoryConnector implements Connector {

    /** The default number of rows of a split of a list. */
    public static final int DEFAULT_SPLIT_ROWS = 65_536;

    private final String name;
    private final int batchSize;
    private final int splitRows;
    private final Capabilities capabilities;
    private final Catalog catalog;
    private final Writer writer = new TableWriter();

    /** The tables by name, in the order of their creation; replaced as a whole on each change. */
    private volatile Map<String, Table> tables;

    /** A table: rows of Java objects, or batches written by SQL. */
    private sealed interface Table {
        Schema schema();

        long rowCount();
    }

    /** A collection of Java objects, each of which becomes a row. */
    private record ObjectTable(Schema schema, Collection<?> rows, Function<Object, Object[]> toRow)
            implements Table {
        @Override
        public long rowCount() {
            return rows.size();
        }
    }

    /**
     * Batches written by SQL, none of them empty, in the columns of the
     * schema; {@code key} holds the positions of the primary key columns,
     * empty for a table without one.
     */
    private record BatchTable(Schema schema, List<Batch> batches, long rowCount, List<Integer> key)
            implements Table {
    }

    /** The rows of a split of a list, from {@code from} to before {@code to}, or to its end in the last split. */
    private record Range(int from, int to) {
    }

    /** The batches of a split of a table that SQL wrote. */
    private record Batches(List<Batch> batches) {
    }

    private MemoryConnector(String name, Map<String, Table> tables, int batchSize, int splitRows,
            Capabilities capabilities) {
        this.name = name;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.batchSize = batchSize;
        this.splitRows = splitRows;
        this.capabilities = capabilities;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(MemoryConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                return OptionalLong.of(table(table).rowCount());
            }

            @Override
            public List<String> primaryKey(TableRef table) {
                if (!(table(table) instanceof BatchTable t)) {
                    return List.of();
                }
                List<String> names = new ArrayList<>();
                for (int c : t.key()) {
                    names.add(t.schema().name(c));
                }
                return List.copyOf(names);
            }
        };
    }

    /** Starts a connector with the name under which it will be registered. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Catalog catalog() {
        return catalog;
    }

    /** Returns the writer, which creates tables of batches, appends to them and drops any table. */
    @Override
    public Optional<Writer> writer() {
        return Optional.of(writer);
    }

    private Table table(TableRef ref) {
        Table t = ref.schema().equals(TableRef.MAIN) ? tables.get(ref.table()) : null;
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the memory source " + name);
        }
        return t;
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        return capabilities.plan(table, table(table).schema(), request, request);
    }

    /**
     * Returns a split for every {@link Builder#splitRows} rows of a list with
     * random access, the last one up to the end of the list, and one split
     * for any other collection and for a scan with a limit. A table that
     * SQL wrote has a split for every run of whole batches of at least
     * that many rows; the splits hold the batches of the table as it is now.
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        Table table = table(plan.table());
        if (table instanceof BatchTable t) {
            return splits(plan, t);
        }
        Collection<?> rows = ((ObjectTable) table).rows();
        if (!(rows instanceof List<?> && rows instanceof RandomAccess) || plan.limitAccepted()
                || rows.size() <= splitRows) {
            return List.of(new Split(plan, 0, null));
        }
        List<Split> splits = new ArrayList<>();
        int size = rows.size();
        for (int from = 0; from < size; from += splitRows) {
            int to = size - from <= splitRows ? Integer.MAX_VALUE : from + splitRows;
            splits.add(new Split(plan, splits.size(), new Range(from, to)));
        }
        return splits;
    }

    private List<Split> splits(ScanPlan plan, BatchTable table) {
        List<Batch> batches = table.batches();
        if (plan.limitAccepted() || table.rowCount() <= splitRows) {
            return List.of(new Split(plan, 0, new Batches(batches)));
        }
        List<Split> splits = new ArrayList<>();
        int from = 0;
        long rows = 0;
        for (int i = 0; i < batches.size(); i++) {
            rows += batches.get(i).rowCount();
            if (rows >= splitRows || i == batches.size() - 1) {
                splits.add(new Split(plan, splits.size(), new Batches(batches.subList(from, i + 1))));
                from = i + 1;
                rows = 0;
            }
        }
        return splits;
    }

    @Override
    public BatchReader open(Split split) {
        ScanPlan plan = split.plan();
        List<ColumnPath> columns = plan.columns();
        Schema output = plan.output();
        Expression condition = plan.acceptedFilters().isEmpty() ? null
                : Expressions.conjunction(plan.acceptedFilters());
        long limit = plan.limitAccepted() ? ((ScanRequest) plan.state()).limit().getAsLong() : Long.MAX_VALUE;
        if (split.state() instanceof Batches b) {
            Iterator<Batch> batches = b.batches().iterator();
            return reader(() -> {
                if (!batches.hasNext()) {
                    return null;
                }
                Batch stored = batches.next();
                List<Vector> vectors = new ArrayList<>(columns.size());
                for (ColumnPath path : columns) {
                    vectors.add(vector(stored, path));
                }
                return new Batch(output, stored.rowCount(), vectors);
            }, condition, limit);
        }
        ObjectTable table = (ObjectTable) table(plan.table());
        Iterator<?> rows = split.state() instanceof Range r ? range((List<?>) table.rows(), r)
                : table.rows().iterator();
        return reader(() -> {
            if (!rows.hasNext()) {
                return null;
            }
            List<Vector.Builder> builders = new ArrayList<>();
            for (int c = 0; c < output.size(); c++) {
                builders.add(Vectors.builder(output.type(c), batchSize));
            }
            int n = 0;
            while (n < batchSize && rows.hasNext()) {
                Object[] row = table.toRow().apply(rows.next());
                for (int c = 0; c < columns.size(); c++) {
                    builders.get(c).append(value(row, columns.get(c), table.schema()));
                }
                n++;
            }
            List<Vector> vectors = new ArrayList<>();
            for (Vector.Builder vb : builders) {
                vectors.add(vb.build());
            }
            return new Batch(output, n, vectors);
        }, condition, limit);
    }

    /**
     * Reads the batches of a source, {@code null} at its end, with the rows
     * that meet a condition, if there is one, up to a limit.
     */
    private static BatchReader reader(Supplier<Batch> source, Expression condition, long limit) {
        return new BatchReader() {
            private long delivered;

            @Override
            public Batch next() {
                while (delivered < limit) {
                    Batch b = source.get();
                    if (b == null) {
                        return null;
                    }
                    if (condition != null) {
                        b = b.select(Evaluator.select(condition, b));
                    }
                    if (b.rowCount() > limit - delivered) {
                        int[] first = new int[(int) (limit - delivered)];
                        for (int i = 0; i < first.length; i++) {
                            first[i] = i;
                        }
                        b = b.select(first);
                    }
                    if (b.rowCount() > 0) {
                        delivered += b.rowCount();
                        return b;
                    }
                }
                return null;
            }
        };
    }

    /** The rows of a split of a list, as many of them as the list still has. */
    private static Iterator<?> range(List<?> rows, Range range) {
        int size = rows.size();
        int to = Math.min(range.to(), size);
        return rows.subList(Math.min(range.from(), to), to).iterator();
    }

    /** The value of a column of a row, or of a field inside it. */
    private static Object value(Object[] row, ColumnPath path, Schema schema) {
        Object v = row[path.column()];
        DataType type = schema.type(path.column());
        for (String field : path.fields()) {
            if (v == null) {
                return null;
            }
            DataType.Struct s = (DataType.Struct) type;
            int i = s.indexOf(field);
            v = ((List<?>) v).get(i);
            type = s.fields().get(i).type();
        }
        return v;
    }

    /** The vector of a column of a batch, or of a field inside it, which is {@code NULL} where its struct is. */
    private static Vector vector(Batch batch, ColumnPath path) {
        Vector v = batch.column(path.column());
        for (String field : path.fields()) {
            StructVector s = (StructVector) v;
            v = s.child(s.type().indexOf(field));
        }
        return v;
    }

    private static long count(List<Batch> batches) {
        long n = 0;
        for (Batch b : batches) {
            n += b.rowCount();
        }
        return n;
    }

    /**
     * Creates tables of batches, appends to them and drops tables; a
     * change is one new map of the tables, so that a query sees the tables
     * as they were before or after it.
     */
    private final class TableWriter implements Writer {

        @Override
        public Sink create(TableRef table, Schema schema) {
            return create(table, schema, List.of());
        }

        @Override
        public Sink create(TableRef table, Schema schema, List<String> primaryKey) {
            main(table);
            List<Integer> key = new ArrayList<>();
            for (String column : primaryKey) {
                int c = schema.indexOf(column);
                if (c < 0 || key.contains(c)) {
                    throw new IllegalArgumentException(c < 0 ? "Key column " + column + " not found in table "
                            + table.table() : "Key column " + column + " named twice");
                }
                key.add(c);
            }
            return new BatchSink(schema, batches -> {
                checkKey(table, schema, key, List.of(), batches);
                synchronized (MemoryConnector.this) {
                    if (tables.containsKey(table.table())) {
                        throw new IllegalArgumentException("Table with name " + table.table()
                                + " already exists in the memory source " + name);
                    }
                    put(table.table(), new BatchTable(schema, List.copyOf(batches), count(batches),
                            List.copyOf(key)));
                }
            });
        }

        @Override
        public Sink append(TableRef table) {
            main(table);
            if (!(table(table) instanceof BatchTable t)) {
                throw new IllegalArgumentException("Table " + table.table() + " of the memory source " + name
                        + " holds Java objects and cannot be written");
            }
            return new BatchSink(t.schema(), batches -> {
                synchronized (MemoryConnector.this) {
                    if (!(tables.get(table.table()) instanceof BatchTable now) || !now.schema().equals(t.schema())) {
                        throw new IllegalArgumentException("Table " + table.table() + " of the memory source "
                                + name + " was dropped or replaced while rows were written to it");
                    }
                    checkKey(table, now.schema(), now.key(), now.batches(), batches);
                    List<Batch> all = new ArrayList<>(now.batches());
                    all.addAll(batches);
                    put(table.table(), new BatchTable(now.schema(), List.copyOf(all),
                            now.rowCount() + count(batches), now.key()));
                }
            });
        }

        @Override
        public OptionalLong delete(TableRef table, Expression condition) {
            main(table);
            synchronized (MemoryConnector.this) {
                BatchTable t = written(table);
                List<Batch> kept = new ArrayList<>();
                long deleted = 0;
                for (Batch b : t.batches()) {
                    int[] rows = condition == null ? null : Evaluator.select(condition, b);
                    int n = rows == null ? b.rowCount() : rows.length;
                    deleted += n;
                    if (n < b.rowCount()) {
                        kept.add(n == 0 ? b : b.select(complement(rows, b.rowCount())));
                    }
                }
                put(table.table(), new BatchTable(t.schema(), List.copyOf(kept), t.rowCount() - deleted, t.key()));
                return OptionalLong.of(deleted);
            }
        }

        @Override
        public OptionalLong update(TableRef table, int[] columns, List<Expression> values, Expression condition) {
            main(table);
            synchronized (MemoryConnector.this) {
                BatchTable t = written(table);
                List<Batch> changed = new ArrayList<>();
                long updated = 0;
                for (Batch b : t.batches()) {
                    int[] rows = condition == null ? null : Evaluator.select(condition, b);
                    if (rows != null && rows.length == 0) {
                        changed.add(b);
                        continue;
                    }
                    // The new values come from the rows to update alone, before any of them changes.
                    Batch selected = rows == null ? b : b.select(rows);
                    List<Vector> vectors = new ArrayList<>(b.columns());
                    for (int k = 0; k < columns.length; k++) {
                        Vector v = Evaluator.eval(values.get(k), selected);
                        vectors.set(columns[k], rows == null ? v : patched(b.column(columns[k]), v, rows));
                    }
                    changed.add(new Batch(t.schema(), b.rowCount(), vectors));
                    updated += selected.rowCount();
                }
                checkNotNull(table, t.schema(), changed);
                checkKey(table, t.schema(), t.key(), List.of(), changed);
                put(table.table(), new BatchTable(t.schema(), List.copyOf(changed), t.rowCount(), t.key()));
                return OptionalLong.of(updated);
            }
        }

        @Override
        public RowChanges change(TableRef table, int[] keys, int[] updated) {
            main(table);
            Schema schema = written(table).schema();
            return new KeyedChanges(table, schema, keys.clone(), updated.clone());
        }

        @Override
        public void addColumn(TableRef table, Schema.Field column, Object defaultValue) {
            main(table);
            synchronized (MemoryConnector.this) {
                BatchTable t = written(table);
                List<Schema.Field> fields = new ArrayList<>(t.schema().fields());
                fields.add(column);
                Schema schema = new Schema(fields);
                List<Batch> batches = new ArrayList<>();
                for (Batch b : t.batches()) {
                    List<Vector> columns = new ArrayList<>(b.columns());
                    columns.add(Evaluator.constant(column.type(), defaultValue, b.rowCount()));
                    batches.add(new Batch(schema, b.rowCount(), columns));
                }
                put(table.table(), new BatchTable(schema, List.copyOf(batches), t.rowCount(), t.key()));
            }
        }

        @Override
        public void createSchema(String schema) {
            throw new IllegalArgumentException("The memory source " + name + " has only the schema "
                    + TableRef.MAIN + " and creates no other");
        }

        /** Returns a table that SQL wrote, which can be changed. */
        private BatchTable written(TableRef table) {
            if (!(table(table) instanceof BatchTable t)) {
                throw new IllegalArgumentException("Table " + table.table() + " of the memory source " + name
                        + " holds Java objects and cannot be written");
            }
            return t;
        }

        @Override
        public void drop(TableRef table) {
            main(table);
            synchronized (MemoryConnector.this) {
                table(table);
                Map<String, Table> changed = new LinkedHashMap<>(tables);
                changed.remove(table.table());
                tables = Collections.unmodifiableMap(changed);
            }
        }

        private void main(TableRef table) {
            if (!table.schema().equals(TableRef.MAIN)) {
                throw new IllegalArgumentException("The memory source " + name + " has only the schema "
                        + TableRef.MAIN + ", not " + table.schema());
            }
        }

        /**
         * Fails if the key columns of rows added to a table hold a
         * {@code NULL}, or the same values as other rows; the messages are
         * those of DuckDB.
         */
        private static void checkKey(TableRef table, Schema schema, List<Integer> key, List<Batch> existing,
                List<Batch> added) {
            if (key.isEmpty()) {
                return;
            }
            Map<RowKey, Boolean> seen = new HashMap<>();
            for (Batch b : existing) {
                for (int r = 0; r < b.rowCount(); r++) {
                    seen.put(new RowKey(columns(b, key), r), Boolean.TRUE);
                }
            }
            for (Batch b : added) {
                Vector[] columns = columns(b, key);
                for (int c = 0; c < columns.length; c++) {
                    if (columns[c].hasNulls()) {
                        throw new IllegalArgumentException("NOT NULL constraint failed: " + table.table() + "."
                                + schema.name(key.get(c)));
                    }
                }
                for (int r = 0; r < b.rowCount(); r++) {
                    RowKey k = new RowKey(columns, r);
                    Boolean old = seen.put(k, Boolean.FALSE);
                    if (old == Boolean.TRUE) {
                        StringBuilder text = new StringBuilder();
                        for (int c = 0; c < columns.length; c++) {
                            text.append(c > 0 ? ", " : "").append(schema.name(key.get(c))).append(": ")
                                    .append(RowKey.text(columns[c], r));
                        }
                        throw new IllegalArgumentException("Duplicate key \"" + text
                                + "\" violates primary key constraint.");
                    } else if (old != null) {
                        throw new IllegalArgumentException(
                                "PRIMARY KEY or UNIQUE constraint violation: duplicate key \"" + k + "\"");
                    }
                }
            }
        }

        /** Fails if a column that is {@code NOT NULL} holds a {@code NULL} in one of the batches. */
        private static void checkNotNull(TableRef table, Schema schema, List<Batch> batches) {
            for (Batch b : batches) {
                for (int c = 0; c < schema.size(); c++) {
                    if (!schema.nullable(c) && b.column(c).hasNulls()) {
                        throw new IllegalArgumentException("NOT NULL constraint failed: " + table.table() + "."
                                + schema.name(c));
                    }
                }
            }
        }

        private static Vector[] columns(Batch batch, List<Integer> key) {
            Vector[] columns = new Vector[key.size()];
            for (int c = 0; c < columns.length; c++) {
                columns[c] = batch.column(key.get(c));
            }
            return columns;
        }

        /** Sets a table; the caller holds the lock of the connector. */
        private void put(String table, Table t) {
            Map<String, Table> changed = new LinkedHashMap<>(tables);
            changed.put(table, t);
            tables = Collections.unmodifiableMap(changed);
        }
    }

    /** Returns the rows from 0 to before {@code size} that are not among the given ascending rows. */
    private static int[] complement(int[] rows, int size) {
        int[] rest = new int[size - rows.length];
        int n = 0;
        int next = 0;
        for (int r = 0; r < size; r++) {
            if (next < rows.length && rows[next] == r) {
                next++;
            } else {
                rest[n++] = r;
            }
        }
        return rest;
    }

    /** Returns a vector with the values of another at the given ascending rows, one for each of them. */
    private static Vector patched(Vector old, Vector values, int[] rows) {
        int[] partOf = new int[old.size()];
        int[] rowOf = new int[old.size()];
        for (int r = 0; r < rowOf.length; r++) {
            rowOf[r] = r;
        }
        for (int i = 0; i < rows.length; i++) {
            partOf[rows[i]] = 1;
            rowOf[rows[i]] = i;
        }
        return Vectors.gather(old.type(), List.of(old, values), partOf, rowOf, 0, old.size());
    }

    /**
     * Changes to the rows of a table by key, held until the commit, which
     * finds the rows of the keys in the batches the table has then and
     * replaces them at once.
     */
    private final class KeyedChanges implements RowChanges {
        private final TableRef table;
        private final Schema schema;
        private final int[] keys;
        private final int[] updated;
        private final List<Batch> updates = new ArrayList<>();
        private final List<Batch> deletes = new ArrayList<>();
        private final List<Batch> inserts = new ArrayList<>();
        private boolean done;

        KeyedChanges(TableRef table, Schema schema, int[] keys, int[] updated) {
            this.table = table;
            this.schema = schema;
            this.keys = keys;
            this.updated = updated;
        }

        @Override
        public void update(Batch batch) {
            add(updates, batch);
        }

        @Override
        public void delete(Batch batch) {
            add(deletes, batch);
        }

        @Override
        public void insert(Batch batch) {
            add(inserts, batch.rowCount() == 0 ? batch : new Batch(schema, batch.rowCount(), batch.columns()));
        }

        private void add(List<Batch> to, Batch batch) {
            if (done) {
                throw new IllegalStateException("The changes are committed or closed");
            }
            if (batch.rowCount() > 0) {
                to.add(batch);
            }
        }

        @Override
        public long commit() {
            if (done) {
                throw new IllegalStateException("The changes are committed or closed");
            }
            done = true;
            try {
                return apply();
            } finally {
                updates.clear();
                deletes.clear();
                inserts.clear();
            }
        }

        private long apply() {
            synchronized (MemoryConnector.this) {
                if (!(tables.get(table.table()) instanceof BatchTable t) || !t.schema().equals(schema)) {
                    throw new IllegalArgumentException("Table " + table.table() + " of the memory source " + name
                            + " was dropped or replaced while its rows were changed");
                }
                // The rows of each key: batch and row, packed in a long.
                Map<RowKey, List<Long>> index = new HashMap<>();
                List<Batch> batches = t.batches();
                for (int b = 0; b < batches.size(); b++) {
                    Vector[] columns = RowKey.columns(batches.get(b), keys);
                    for (int r = 0; r < batches.get(b).rowCount(); r++) {
                        index.computeIfAbsent(new RowKey(columns, r), k -> new ArrayList<>(1))
                                .add((long) b << 32 | r);
                    }
                }
                int[] all = new int[keys.length];
                java.util.Arrays.setAll(all, k -> k);
                long count = 0;
                Map<Long, Integer> deleted = new HashMap<>();
                for (Batch d : deletes) {
                    Vector[] columns = RowKey.columns(d, all);
                    for (int r = 0; r < d.rowCount(); r++) {
                        for (long at : index.getOrDefault(new RowKey(columns, r), List.of())) {
                            deleted.put(at, 0);
                            count++;
                        }
                    }
                }
                // For each updated row of the table, the batch of updates and the row in it.
                Map<Long, Long> newValues = new HashMap<>();
                for (int u = 0; u < updates.size(); u++) {
                    Vector[] columns = RowKey.columns(updates.get(u), all);
                    for (int r = 0; r < updates.get(u).rowCount(); r++) {
                        for (long at : index.getOrDefault(new RowKey(columns, r), List.of())) {
                            newValues.put(at, (long) u << 32 | r);
                            count++;
                        }
                    }
                }
                List<Batch> changed = new ArrayList<>();
                for (int b = 0; b < batches.size(); b++) {
                    changed.addAll(changed(batches.get(b), b, deleted, newValues));
                }
                for (Batch i : inserts) {
                    changed.add(i);
                    count += i.rowCount();
                }
                TableWriter.checkNotNull(table, schema, changed);
                TableWriter.checkKey(table, schema, t.key(), List.of(), changed);
                ((TableWriter) writer).put(table.table(), new BatchTable(schema, List.copyOf(changed),
                        count(changed), t.key()));
                return count;
            }
        }

        /** Returns a batch of the table without its deleted rows and with the new values of its updated ones. */
        private List<Batch> changed(Batch b, int index, Map<Long, Integer> deleted, Map<Long, Long> newValues) {
            List<Integer> kept = new ArrayList<>();
            boolean touched = false;
            for (int r = 0; r < b.rowCount(); r++) {
                long at = (long) index << 32 | r;
                if (deleted.containsKey(at)) {
                    touched = true;
                } else {
                    kept.add(r);
                    touched |= newValues.containsKey(at);
                }
            }
            if (!touched) {
                return List.of(b);
            }
            if (kept.isEmpty()) {
                return List.of();
            }
            int n = kept.size();
            int[] partOf = new int[n];
            int[] rowOf = new int[n];
            List<Vector> columns = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                int u = -1;
                for (int k = 0; k < updated.length; k++) {
                    u = updated[k] == c ? k : u;
                }
                List<Vector> parts = new ArrayList<>();
                parts.add(b.column(c));
                if (u >= 0) {
                    for (Batch update : updates) {
                        parts.add(update.column(keys.length + u));
                    }
                }
                for (int i = 0; i < n; i++) {
                    int r = kept.get(i);
                    Long from = u >= 0 ? newValues.get((long) index << 32 | r) : null;
                    partOf[i] = from == null ? 0 : 1 + (int) (from >>> 32);
                    rowOf[i] = from == null ? r : (int) (long) from;
                }
                columns.add(Vectors.gather(schema.type(c), parts, partOf, rowOf, 0, n));
            }
            return List.of(new Batch(schema, n, columns));
        }

        @Override
        public void close() {
            done = true;
            updates.clear();
            deletes.clear();
            inserts.clear();
        }
    }

    /** Collects the batches of a write until its commit hands them on. */
    private static final class BatchSink implements Sink {
        private final Schema schema;
        private final Consumer<List<Batch>> publish;
        private final List<Batch> batches = new ArrayList<>();
        private long rows;
        private boolean done;

        BatchSink(Schema schema, Consumer<List<Batch>> publish) {
            this.schema = schema;
            this.publish = publish;
        }

        @Override
        public void write(Batch batch) {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            if (batch.rowCount() > 0) {
                batches.add(new Batch(schema, batch.rowCount(), batch.columns()));
                rows += batch.rowCount();
            }
        }

        @Override
        public long commit() {
            if (done) {
                throw new IllegalStateException("The sink is committed or closed");
            }
            publish.accept(batches);
            done = true;
            batches.clear();
            return rows;
        }

        @Override
        public void close() {
            done = true;
            batches.clear();
        }
    }

    /** Collects the tables of a memory connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, Table> tables = new LinkedHashMap<>();
        private int batchSize = 2048;
        private int splitRows = DEFAULT_SPLIT_ROWS;
        private Capabilities capabilities = Capabilities.NONE;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Sets the number of rows per batch. */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the rows of a split of a table of a list with random access;
         * {@value MemoryConnector#DEFAULT_SPLIT_ROWS} by default.
         */
        public Builder splitRows(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("splitRows: " + rows);
            }
            this.splitRows = rows;
            return this;
        }

        /**
         * Lets the connector take over the filters the capabilities accept
         * and, if they allow one, a limit, and read only the fields of struct
         * columns a query uses if they allow that; an order or an aggregation
         * it never takes over. Meant for tests of the engine's pushdown.
         */
        public Builder pushdown(Capabilities capabilities) {
            this.capabilities = new Capabilities(capabilities.filter(), capabilities.limit(), o -> false, a -> false,
                    capabilities.fields());
            return this;
        }

        /** Adds a collection of records or beans as a table; the columns are their properties. */
        public <T> Builder table(String table, Class<T> type, Collection<? extends T> rows) {
            return table(table, type, rows, Map.of());
        }

        /**
         * Adds a collection of records or beans as a table, with column
         * types that replace the derived ones, e.g. a {@code DECIMAL(10,2)}
         * for a {@code BigDecimal} property.
         */
        public <T> Builder table(String table, Class<T> type, Collection<? extends T> rows,
                Map<String, DataType> types) {
            List<JavaTypes.Property> properties = JavaTypes.properties(type, types);
            Schema schema = JavaTypes.schema(type, types);
            Function<Object, Object[]> toRow = o -> {
                Object[] row = new Object[properties.size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = properties.get(i).read(o);
                }
                return row;
            };
            tables.put(table, new ObjectTable(schema, rows, toRow));
            return this;
        }

        /** Adds rows of FyrQL values with an explicit schema as a table. */
        public Builder table(String table, Schema schema, Collection<Object[]> rows) {
            tables.put(table, new ObjectTable(schema, rows, o -> (Object[]) o));
            return this;
        }

        public MemoryConnector build() {
            return new MemoryConnector(name, tables, batchSize, splitRows, capabilities);
        }
    }
}
