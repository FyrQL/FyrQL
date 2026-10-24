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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.iceberg.IcebergConnector;
import net.fyrql.connector.iceberg.IcebergTestTables;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.json.JsonFormat;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.test.db.TestDatabase;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The random tables in other sources, for the {@linkplain Metamorphic
 * metamorphic checks}: written by {@code COPY ... TO} from the memory
 * source as Parquet files with small row groups, ordered by the key
 * {@code k} so that their statistics leave some out, and as CSV and JSON
 * files read in small splits; loaded into a database and read in splits
 * over ranges of {@code k}; and the test tables of Iceberg, with
 * partitions, deletes and a changed schema. The file sources read batches
 * of 8 rows. Each source comes in an engine
 * whose default source it is, so that the queries name the tables without
 * it, with the columns it reads them with, and with a check that it did its
 * part of the work.
 */
final class RandomSources {

    /** The largest value of 38 digits, which a Parquet file holds of a {@code HUGEINT}. */
    private static final String MAX_38_DIGITS = "99999999999999999999999999999999999999";

    /** The Iceberg tables of the checks: those without nested columns and with more than a few rows. */
    static final List<String> ICEBERG = List.of("part", "pos_v2", "eq_v2", "dv_v3", "evo", "v1");

    /**
     * The memory budget of the engines, large enough that the queries
     * rarely spill, so that the checks test the sources: a query whose
     * result does not fit counts as out of the budget.
     */
    static final long BUDGET = 256L << 20;

    /**
     * A source of tables: its name, an engine over it, the tables with
     * their columns as the engine reads them, and the check that it did its
     * part of the work, to run after the queries.
     */
    record Source(String name, FyrQL engine, Map<String, Schema> tables, Runnable check) implements AutoCloseable {
        @Override
        public void close() {
            engine.close();
        }
    }

    private RandomSources() {
    }

    /**
     * An engine over a source as the random queries run: batches of 16
     * rows, the time zone of the tables, the {@linkplain #BUDGET budget}.
     */
    private static Source source(String name, Connector connector, List<String> names,
            Function<FyrQL, Runnable> check) {
        FyrQL engine = FyrQL.builder().register(connector).defaultSource(connector.name()).batchSize(16)
                .timeZone(RandomTables.ZONE).memoryBudget(BUDGET).build();
        Map<String, Schema> tables = new LinkedHashMap<>();
        for (String table : names) {
            tables.put(table, engine.plan("SELECT * FROM " + table).schema());
        }
        return new Source(name, engine, tables, check.apply(engine));
    }

    private static String path(Path file) {
        return file.toString().replace('\\', '/');
    }

    /**
     * Writes each random table with a query of the memory source into a
     * file of a directory.
     *
     * @param queries the query of each table
     * @param options the options of {@code COPY}, such as {@code " (ROW_GROUP_SIZE 8)"}, or empty
     */
    private static Map<String, Path> write(Path dir, String extension, Map<String, String> queries, String options) {
        Map<String, Path> files = new LinkedHashMap<>();
        try (FyrQL memory = RandomTables.engine(RandomTables.rows(RandomRun.SEED))) {
            for (Map.Entry<String, String> q : queries.entrySet()) {
                Path file = dir.resolve(q.getKey() + "." + extension);
                memory.update("COPY (" + q.getValue() + ") TO '" + path(file) + "'" + options);
                files.put(q.getKey(), file);
            }
        }
        return files;
    }

    /** The query of each random table that writes all of it. */
    private static Map<String, String> all() {
        Map<String, String> queries = new LinkedHashMap<>();
        for (String table : RandomTables.SCHEMAS.keySet()) {
            queries.put(table, "SELECT * FROM memory." + table);
        }
        return queries;
    }

    /**
     * The random tables as Parquet files with row groups of 8 rows, ordered
     * by {@code k}. As in DuckDB, a Parquet file holds no negative
     * intervals, which {@code p} and {@code e} have, and a {@code HUGEINT}
     * as a {@code DECIMAL(38,0)}, which some edge values of {@code e} do not
     * fit: those columns and rows stay out. The check counts the row groups
     * a condition on {@code k} reads, where the engine pushes filters down.
     */
    static Source parquet(Path dir) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("p", "SELECT * EXCLUDE (iv) FROM memory.p ORDER BY k");
        queries.put("q", "SELECT * FROM memory.q ORDER BY k");
        queries.put("e", "SELECT * EXCLUDE (eiv) FROM memory.e WHERE eh IS NULL OR eh BETWEEN -" + MAX_38_DIGITS
                + " AND " + MAX_38_DIGITS + " ORDER BY k");
        Map<String, Path> files = write(dir, "parquet", queries, " (ROW_GROUP_SIZE 8)");
        ParquetConnector.Builder b = ParquetConnector.builder("files").batchSize(8).recordReads(true);
        files.forEach(b::path);
        ParquetConnector parquet = b.build();
        return source("Parquet", parquet, List.copyOf(files.keySet()), engine -> () -> {
            if (!engine.pushdown()) {
                return;
            }
            int before = parquet.reads().size();
            engine.rows("SELECT count(*) FROM p WHERE k = 3");
            int read = parquet.reads().size() - before;
            // 40 rows in row groups of 8, of which those with a k of 3 are read.
            if (read == 0 || read >= 5) {
                throw new AssertionError("a condition on k reads " + read + " of 5 row groups");
            }
        });
    }

    /** The check of a file source: a table of 40 rows comes in several splits. */
    private static Runnable splits(Connector connector) {
        return () -> {
            TableRef p = TableRef.of(connector.name(), "p");
            int splits = connector.splits(connector.plan(p, ScanRequest.columns(List.of(0)))).size();
            if (splits < 2) {
                throw new AssertionError(connector.name() + " reads the table p in " + splits + " split");
            }
        };
    }

    /**
     * The random tables as CSV files, read with their schemas in splits of
     * 256 bytes, cut between the batches; an empty string comes back as
     * {@code NULL}.
     */
    static Source csv(Path dir) {
        Map<String, Path> files = write(dir, "csv", all(), "");
        CsvConnector.Builder b = CsvConnector.builder("files").batchSize(8).splitSize(256);
        files.forEach((table, file) -> b.schema(table, RandomTables.SCHEMAS.get(table)).file(table, file));
        CsvConnector csv = b.build();
        return source("CSV", csv, List.copyOf(files.keySet()), engine -> splits(csv));
    }

    /** The random tables as JSON files of records, read with their schemas in splits of 256 bytes. */
    static Source json(Path dir) {
        Map<String, Path> files = write(dir, "json", all(), "");
        JsonConnector.Builder b = JsonConnector.builder("files").format(JsonFormat.NEWLINE_DELIMITED).batchSize(8)
                .splitSize(256);
        files.forEach((table, file) -> b.schema(table, RandomTables.SCHEMAS.get(table)).file(table, file));
        JsonConnector json = b.build();
        return source("JSON", json, List.copyOf(files.keySet()), engine -> splits(json));
    }

    /**
     * The random tables in a database, read in splits of 8 rows over ranges
     * of {@code k}, and a split for its {@code NULL}s, over connections that
     * stay open for the next statement. H2 and SQLite hold no infinite and
     * no NaN floating-point values, which become {@code NULL}; SQLite has
     * no type for a {@code HUGEINT}, whose columns stay out, and holds a
     * decimal as a {@code REAL}, so that one of more than 15 digits becomes
     * {@code NULL}. The check looks for filters in the statements sent.
     */
    static Source database(String name, TestDatabase db) {
        return database(name, db, null, b -> {
        });
    }

    /**
     * The random tables in a database without a dialect of its own, such as
     * Derby or HSQLDB, as {@link #database(String, TestDatabase)} does, but
     * created with the names of its types and filled through a prepared
     * statement; a type without a name stays out, and a string may be
     * longer than the {@code VARCHAR} of the names.
     *
     * @param typeNames the names of the types, or {@code null} for those the dialect writes
     * @param settings further settings of the connector, such as the style of its limits
     */
    static Source database(String name, TestDatabase db, Function<DataType, String> typeNames,
            Consumer<JdbcConnector.Builder> settings) {
        Function<DataType, String> names = typeNames == null ? null
                : t -> t == DataType.VARCHAR ? "VARCHAR(4000)" : typeNames.apply(t);
        Map<String, List<Object[]>> rows = RandomTables.rows(RandomRun.SEED);
        for (String table : RandomTables.SCHEMAS.keySet()) {
            Schema schema = RandomTables.SCHEMAS.get(table);
            List<Integer> kept = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                DataType type = schema.type(c);
                if (names != null ? names.apply(type) != null
                        : db.dialect() != JdbcDialect.SQLITE || type != DataType.HUGEINT) {
                    kept.add(c);
                }
            }
            List<Object[]> loaded = new ArrayList<>();
            for (Object[] row : rows.get(table)) {
                Object[] values = new Object[kept.size()];
                for (int c = 0; c < kept.size(); c++) {
                    Object v = row[kept.get(c)];
                    boolean finite = !(v instanceof Double d && !Double.isFinite(d))
                            && !(v instanceof Float f && !Float.isFinite(f));
                    boolean exact = !(v instanceof BigDecimal d && d.precision() > 15);
                    values[c] = finite || db.dialect() == JdbcDialect.POSTGRESQL ? v : null;
                    values[c] = exact || db.dialect() != JdbcDialect.SQLITE ? values[c] : null;
                }
                loaded.add(values);
            }
            Schema loadedSchema = new Schema(kept.stream().map(schema.fields()::get).toList());
            if (names == null) {
                db.load(table, loadedSchema, loaded);
            } else {
                db.load(table, loadedSchema, names, loaded);
            }
        }
        JdbcConnector.Builder b = JdbcConnector.builder("db").dataSource(new Pool(db)).dialect(db.dialect())
                .recordStatements(true).splitRows(8);
        for (String table : RandomTables.SCHEMAS.keySet()) {
            b.splitColumn(table, "k");
        }
        settings.accept(b);
        JdbcConnector jdbc = b.build();
        return source(name, jdbc, List.copyOf(RandomTables.SCHEMAS.keySet()), engine -> () -> {
            if (jdbc.statements().stream().noneMatch(s -> s.contains(" WHERE "))) {
                throw new AssertionError(name + " got no filter in " + jdbc.statements().size() + " statements");
            }
        });
    }

    /**
     * Connections of a database that stay open: closing one gives it back
     * for the next statement, in auto-commit as it came, so that the many
     * small statements of the splits do not each connect anew.
     */
    private static final class Pool implements DataSource {

        private final TestDatabase db;
        private final Queue<Connection> idle = new ConcurrentLinkedQueue<>();

        Pool(TestDatabase db) {
            this.db = db;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = idle.poll();
            Connection connection = c != null ? c : db.connect();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            if (!connection.getAutoCommit()) {
                                connection.rollback();
                                connection.setAutoCommit(true);
                            }
                            idle.add(connection);
                            return null;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }

    /** The test tables of Iceberg; the check sees that files were read. */
    static Source iceberg() {
        IcebergTestTables tables;
        try {
            tables = IcebergTestTables.shared();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        IcebergConnector.Builder b = IcebergConnector.builder("lake").batchSize(8).recordReads(true);
        for (String table : ICEBERG) {
            b.table(table, tables.location(table).toString());
        }
        IcebergConnector ice = b.build();
        return source("Iceberg", ice, ICEBERG, engine -> () -> {
            if (ice.reads().isEmpty()) {
                throw new AssertionError("no data file of Iceberg was read");
            }
        });
    }
}
