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
import net.fyrql.connector.files.HivePartitions;
import net.fyrql.connector.http.HttpConnector;
import net.fyrql.connector.iceberg.IcebergConnector;
import net.fyrql.connector.iceberg.IcebergTestTables;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.json.JsonFormat;
import net.fyrql.connector.odata.FakeODataServer;
import net.fyrql.connector.odata.ODataConnector;
import net.fyrql.connector.odata.ODataVersion;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.connector.s3.FakeS3Server;
import net.fyrql.connector.s3.S3Connector;
import net.fyrql.connector.xlsx.XlsxConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.test.db.TestDatabase;

import com.sun.net.httpserver.HttpServer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The random tables in other sources, for the {@linkplain Metamorphic
 * metamorphic checks}: written by {@code COPY ... TO} from the memory
 * source as Parquet files with small row groups, ordered by the key
 * {@code k} so that their statistics leave some out, and as CSV and JSON
 * files read in small splits; loaded into a database and read in splits
 * over ranges of {@code k}; and the test tables of Iceberg, with
 * partitions, deletes and a changed schema. The file sources read batches
 * of 8 rows. The same files lie in a bucket of the fake S3 of the tests,
 * the tables are entity sets of a fake OData service read in pages, and
 * JSON that a small HTTP server answers. Each source comes in an engine
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
     * their columns as the engine reads them, the check that it did its
     * part of the work, to run after the queries, and a server it reads
     * from, closed after the engine.
     */
    record Source(String name, FyrQL engine, Map<String, Schema> tables, Runnable check, AutoCloseable server)
            implements AutoCloseable {
        @Override
        public void close() {
            engine.close();
            try {
                server.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
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
        return source(name, connector, List.of(), names, check, () -> {
        });
    }

    /**
     * An engine over a source as {@link #source(String, Connector, List, Function)} makes it, with further
     * sources, such as the one of S3 that holds its files, and a server to close after it.
     */
    private static Source source(String name, Connector connector, List<Connector> others, List<String> names,
            Function<FyrQL, Runnable> check, AutoCloseable server) {
        FyrQL.Builder b = FyrQL.builder().register(connector);
        others.forEach(b::register);
        FyrQL engine = b.defaultSource(connector.name()).batchSize(16).timeZone(RandomTables.ZONE)
                .memoryBudget(BUDGET).build();
        Map<String, Schema> tables = new LinkedHashMap<>();
        for (String table : names) {
            tables.put(table, engine.plan("SELECT * FROM " + table).schema());
        }
        return new Source(name, engine, tables, check.apply(engine), server);
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
        Map<String, Path> files = write(dir, "parquet", parquetQueries(), " (ROW_GROUP_SIZE 8)");
        ParquetConnector.Builder b = ParquetConnector.builder("files").batchSize(8).recordReads(true);
        files.forEach(b::path);
        ParquetConnector parquet = b.build();
        return source("Parquet", parquet, List.copyOf(files.keySet()), engine -> rowGroups(engine, parquet));
    }

    /** The queries of the tables of the Parquet files. */
    private static Map<String, String> parquetQueries() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("p", "SELECT * EXCLUDE (iv) FROM memory.p ORDER BY k");
        queries.put("q", "SELECT * FROM memory.q ORDER BY k");
        queries.put("e", "SELECT * EXCLUDE (eiv) FROM memory.e WHERE eh IS NULL OR eh BETWEEN -" + MAX_38_DIGITS
                + " AND " + MAX_38_DIGITS + " ORDER BY k");
        return queries;
    }

    /** The check of the Parquet files: a condition on {@code k} reads some of the row groups. */
    private static Runnable rowGroups(FyrQL engine, ParquetConnector parquet) {
        return () -> {
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
        };
    }

    /** The bucket of the random tables in the fake S3. */
    private static final String BUCKET = "random";

    /** Puts files into a bucket of a new fake S3 under their names, and returns their locations by table. */
    private static Map<String, String> upload(FakeS3Server server, Map<String, Path> files) {
        server.createBucket(BUCKET);
        Map<String, String> locations = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Path> f : files.entrySet()) {
                String key = f.getValue().getFileName().toString();
                server.put(BUCKET, key, Files.readAllBytes(f.getValue()));
                locations.put(f.getKey(), "s3://" + BUCKET + "/" + key);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return locations;
    }

    private static S3Connector s3(FakeS3Server server) {
        return S3Connector.builder("s3").endpoint(server.endpoint()).useEnvironment(false).build();
    }

    /**
     * The Parquet files of {@link #parquet} in a bucket of the fake S3 of the
     * tests, read through a source of S3 in ranges of the objects; the check
     * is that of the local files.
     */
    static Source s3Parquet(Path dir) {
        Map<String, Path> files = write(dir, "parquet", parquetQueries(), " (ROW_GROUP_SIZE 8)");
        FakeS3Server server = FakeS3Server.anonymous();
        ParquetConnector.Builder b = ParquetConnector.builder("files").batchSize(8).recordReads(true);
        upload(server, files).forEach(b::path);
        ParquetConnector parquet = b.build();
        return source("Parquet in S3", parquet, List.of(s3(server)), List.copyOf(files.keySet()),
                engine -> rowGroups(engine, parquet), server);
    }

    /**
     * The CSV files of {@link #csv} in a bucket of the fake S3 of the tests,
     * read with their schemas, each object in one part; the check: the
     * fake answered requests for the objects.
     */
    static Source s3Csv(Path dir) {
        Map<String, Path> files = write(dir, "csv", all(), "");
        FakeS3Server server = FakeS3Server.anonymous();
        CsvConnector.Builder b = CsvConnector.builder("files").batchSize(8).splitSize(256);
        upload(server, files).forEach((table, location) -> b.schema(table, RandomTables.SCHEMAS.get(table))
                .file(table, location));
        CsvConnector csv = b.build();
        return source("CSV in S3", csv, List.of(s3(server)), List.copyOf(files.keySet()), engine -> () -> {
            if (server.requests().stream().noneMatch(r -> r.startsWith("GET /" + BUCKET + "/"))) {
                throw new AssertionError("S3 got no GET of an object in " + server.requests().size() + " requests");
            }
        }, server);
    }

    /**
     * The random tables as the entity sets of a fake OData service of
     * version 4, keyed by {@code k}, answered in pages of 16 entities. OData
     * has no type for a {@code HUGEINT} and no duration of months, so that
     * the columns of these types stay out. The check
     * looks for a {@code $filter} among the requests.
     */
    static Source odata() {
        FakeODataServer server = FakeODataServer.start();
        FakeODataServer.Service service = server.service("random", ODataVersion.V4).pageSize(16);
        Map<String, List<Object[]>> rows = RandomTables.rows(RandomRun.SEED);
        for (String table : RandomTables.SCHEMAS.keySet()) {
            Schema schema = RandomTables.SCHEMAS.get(table);
            List<Integer> kept = new ArrayList<>();
            for (int c = 0; c < schema.size(); c++) {
                if (schema.type(c) != DataType.HUGEINT && schema.type(c) != DataType.INTERVAL) {
                    kept.add(c);
                }
            }
            List<Object[]> values = new ArrayList<>();
            for (Object[] row : rows.get(table)) {
                values.add(kept.stream().map(c -> row[c]).toArray());
            }
            service.entitySet(table, new Schema(kept.stream().map(schema.fields()::get).toList()), values, "k");
        }
        ODataConnector od = ODataConnector.builder("od").service(TableRef.MAIN, server.url("random")).pageSize(16)
                .batchSize(8).build();
        return source("OData", od, List.of(), List.copyOf(RandomTables.SCHEMAS.keySet()), engine -> () -> {
            if (engine.pushdown() && server.requests().stream().noneMatch(r -> r.parameters().containsKey("$filter"))) {
                throw new AssertionError("OData got no $filter in " + server.requests().size() + " requests");
            }
        }, server);
    }

    /**
     * The random tables as JSON that a server on the loopback interface
     * answers, a record per line, read with their schemas; nothing is
     * pushed to an endpoint. The check counts the requests.
     */
    static Source http(Path dir) {
        Map<String, Path> files = write(dir, "json", all(), "");
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AtomicInteger requests = new AtomicInteger();
        for (Map.Entry<String, Path> f : files.entrySet()) {
            server.createContext("/" + f.getKey(), exchange -> {
                requests.incrementAndGet();
                byte[] body = Files.readAllBytes(f.getValue());
                exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }
        ExecutorService threads = Executors.newFixedThreadPool(4);
        server.setExecutor(threads);
        server.start();
        HttpConnector.Builder b = HttpConnector.builder("web").format(JsonFormat.NEWLINE_DELIMITED).batchSize(8);
        for (String table : files.keySet()) {
            b.table(table, "http://127.0.0.1:" + server.getAddress().getPort() + "/" + table)
                    .schema(table, RandomTables.SCHEMAS.get(table));
        }
        return source("HTTP", b.build(), List.of(), List.copyOf(files.keySet()), engine -> () -> {
            if (requests.get() == 0) {
                throw new AssertionError("the HTTP server got no request");
            }
        }, () -> {
            server.stop(0);
            threads.shutdownNow();
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

    /** The columns by which the tables of the sources of partitioned files are partitioned. */
    private static final Map<String, String> PARTITIONS = Map.of("p", "t", "q", "n");

    /**
     * Writes each random table with a query of the memory source: a table
     * of {@link #PARTITIONS} into a directory of it, with a file for each
     * value of its column in a directory of the form {@code column=value},
     * without the column, as DuckDB writes a table with
     * {@code PARTITION_BY}; any other table into a file.
     *
     * @return the directory or the file of each table
     */
    private static Map<String, Path> partitioned(Path dir, String extension, Map<String, String> queries,
            String options) {
        Map<String, Path> locations = new LinkedHashMap<>();
        try (FyrQL memory = RandomTables.engine(RandomTables.rows(RandomRun.SEED))) {
            for (Map.Entry<String, String> q : queries.entrySet()) {
                String column = PARTITIONS.get(q.getKey());
                if (column == null) {
                    Path file = dir.resolve(q.getKey() + "." + extension);
                    memory.update("COPY (" + q.getValue() + ") TO '" + path(file) + "'" + options);
                    locations.put(q.getKey(), file);
                    continue;
                }
                Path table = dir.resolve(q.getKey());
                for (Object[] value : memory.rows("SELECT DISTINCT " + column + " FROM (" + q.getValue() + ") v")) {
                    Path partition = table.resolve(column + "="
                            + (value[0] == null ? HivePartitions.DEFAULT_PARTITION : value[0]));
                    try {
                        Files.createDirectories(partition);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    String where = value[0] == null ? column + " IS NULL" : column + " = " + value[0];
                    memory.update("COPY (SELECT * EXCLUDE (" + column + ") FROM (" + q.getValue() + ") v WHERE "
                            + where + ") TO '" + path(partition.resolve("part." + extension)) + "'" + options);
                }
                locations.put(q.getKey(), table);
            }
        }
        return locations;
    }

    /**
     * The check of partitioned files: where the engine pushes filters down,
     * a condition on {@code t} reads the file of one value of it at most.
     */
    private static Runnable prunes(FyrQL engine) {
        return () -> {
            if (!engine.pushdown()) {
                return;
            }
            String analyzed = engine.explainAnalyze("SELECT k FROM p WHERE t = 3");
            Matcher m = Pattern.compile("(\\d+) of (\\d+) files").matcher(analyzed);
            if (!m.find() || Integer.parseInt(m.group(1)) > 1 || Integer.parseInt(m.group(2)) < 3) {
                throw new AssertionError("a condition on t reads more than the file of its value:\n" + analyzed);
            }
        };
    }

    /**
     * The random tables as Parquet files as {@link #parquet} writes them,
     * {@code p} and {@code q} in directories by the values of {@code t} and
     * {@code n}, see {@link #partitioned}: {@code t} takes its type from
     * {@code hive_types}, {@code n} from its values, and both follow the
     * other columns. The check: a condition on {@code t} skips files.
     */
    static Source hiveParquet(Path dir) {
        Map<String, Path> files = partitioned(dir, "parquet", parquetQueries(), " (ROW_GROUP_SIZE 8)");
        ParquetConnector.Builder b = ParquetConnector.builder("files").batchSize(8)
                .hiveTypes("p", Map.of("t", DataType.TINYINT));
        files.forEach(b::path);
        return source("Hive Parquet", b.build(), List.copyOf(files.keySet()), RandomSources::prunes);
    }

    /**
     * The random tables as CSV files as {@link #csv} reads them, {@code p}
     * and {@code q} in directories by the values of {@code t} and {@code n},
     * as {@link #hiveParquet} has them.
     */
    static Source hiveCsv(Path dir) {
        Map<String, Path> files = partitioned(dir, "csv", all(), "");
        CsvConnector.Builder b = CsvConnector.builder("files").batchSize(8).splitSize(256)
                .hiveTypes("p", Map.of("t", DataType.TINYINT));
        files.forEach((table, file) -> {
            Schema schema = RandomTables.SCHEMAS.get(table);
            String column = PARTITIONS.get(table);
            if (column != null) {
                List<Schema.Field> fields = new ArrayList<>(schema.fields());
                fields.removeIf(f -> f.name().equals(column));
                schema = new Schema(fields);
            }
            b.schema(table, schema).file(table, file);
        });
        return source("Hive CSV", b.build(), List.copyOf(files.keySet()), RandomSources::prunes);
    }

    /**
     * The random tables as Excel workbooks with a header, read as
     * {@code read_xlsx} reads them: each column takes its type from its
     * first value, so that most of them are a {@code DOUBLE} or a
     * {@code VARCHAR}, and a value that does not fit is a {@code NULL}. A
     * sheet is read in one part; the check: every row of a table comes back.
     */
    static Source xlsx(Path dir) {
        Map<String, Path> files = write(dir, "xlsx", all(), " (HEADER true)");
        XlsxConnector.Builder b = XlsxConnector.builder("files").batchSize(8).ignoreErrors(true);
        files.forEach((table, file) -> b.file(table, file));
        XlsxConnector xlsx = b.build();
        Map<String, List<Object[]>> rows = RandomTables.rows(RandomRun.SEED);
        return source("Excel", xlsx, List.copyOf(files.keySet()), engine -> () -> {
            for (String table : files.keySet()) {
                Object count = engine.rows("SELECT count(*) FROM " + table).get(0)[0];
                if (((Number) count).intValue() != rows.get(table).size()) {
                    throw new AssertionError("the workbook of " + table + " has " + count + " of "
                            + rows.get(table).size() + " rows");
                }
            }
        });
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
