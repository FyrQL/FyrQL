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

package net.fyrql.connector.parquet;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parquet files as tables in the schema {@code main}, read in pure Java
 * without Hadoop. A table is a file, a directory of {@code .parquet} files
 * or a pattern with {@code *} and {@code ?} in the name of the file; all
 * files of a table have the same columns. The columns and their types come
 * from the footers, see {@link ParquetSchemas}; the number of rows is exact.
 * <p>
 * The connector also offers the table functions {@code read_parquet} and
 * {@code parquet_scan} for any file; built without files it serves only
 * them. See {@link #bind}.
 * <p>
 * A scan reads each row group as a split of its own, see
 * {@link RowGroupReader}, of a {@code STRUCT} column only the fields in
 * use. It takes over a limit without filters, reading only the row groups
 * the limit needs, and {@code count(*)} without groups and filters, which
 * the footers answer. It takes over no filter, but skips by the filters
 * the row groups in which no row can pass, by their statistics,
 * dictionaries and Bloom filters, and the pages of a row group, by the
 * column index, see {@link FilterTranslation}; the engine evaluates the
 * filters on the rows read. The files of a table are looked up again for
 * each scan, so that it reads what is there; their columns must still be
 * those of the table. {@link Builder#recordReads} keeps what each scan
 * read.
 */
public final class ParquetConnector implements Connector, TableFunctionProvider {

    private static final List<String> TABLE_FUNCTIONS = List.of("read_parquet", "parquet_scan");

    /** The prefix of the names of the tables of table function calls, which no registered table gets. */
    private static final String CALL_PREFIX = "#";

    /** A limit without filters, {@code count(*)} without groups and filters, and fields inside columns. */
    private static final Capabilities CAPABILITIES = new Capabilities(f -> false, true, o -> false,
            ParquetConnector::countsRows).withFields(true);

    /**
     * A row group a scan read.
     *
     * @param file the file
     * @param rowGroup the index of the row group in the file
     * @param columns the paths of the primitive columns read, in the order of the file
     * @param rows the rows delivered
     */
    public record Read(Path file, int rowGroup, List<String> columns, long rows) {
        public Read {
            columns = List.copyOf(columns);
        }
    }

    /** What a scan remembers: the limit it took over and the filters to skip row groups and pages by. */
    private record State(OptionalLong limit, List<Expression> filters) {
    }

    /**
     * The split of a row group: its index in the file, the rows to deliver
     * of it, and the predicate to skip its pages by, or {@code null}.
     */
    private record RowGroup(ParquetFiles.Footer footer, int block, long rows, FilterPredicate predicate) {
    }

    /** The split of {@code count(*)}: the rows of all files. */
    private record Count(long rows) {
    }

    private final String name;
    private final ParquetFiles files;
    private final Map<String, ParquetFiles.Table> tables;
    private final int batchSize;
    private final Catalog catalog;
    /** The tables of table function calls by their names, and the names by the files of the calls. */
    private final Map<String, ParquetFiles.Table> callTables = new ConcurrentHashMap<>();
    private final Map<List<ParquetFiles.Version>, String> calls = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final List<Read> reads;

    private ParquetConnector(String name, ParquetFiles files, Map<String, ParquetFiles.Table> tables,
            int batchSize, boolean recordReads) {
        this.name = name;
        this.files = files;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.batchSize = batchSize;
        this.reads = recordReads ? new ArrayList<>() : null;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(ParquetConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                return OptionalLong.of(table(table).rows());
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

    /** Returns the number of rows per batch. */
    int batchSize() {
        return batchSize;
    }

    /**
     * Returns the row groups read so far, in the order their readers were
     * done, if the connector records them; otherwise an empty list.
     */
    public List<Read> reads() {
        if (reads == null) {
            return List.of();
        }
        synchronized (reads) {
            return List.copyOf(reads);
        }
    }

    /** Forgets the recorded reads. */
    public void clearReads() {
        if (reads != null) {
            synchronized (reads) {
                reads.clear();
            }
        }
    }

    private void record(Read read) {
        synchronized (reads) {
            reads.add(read);
        }
    }

    private ParquetFiles.Table table(TableRef ref) {
        ParquetFiles.Table t = null;
        if (ref.schema().equals(TableRef.MAIN)) {
            t = ref.table().startsWith(CALL_PREFIX) ? callTables.get(ref.table()) : tables.get(ref.table());
        }
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the Parquet source " + name);
        }
        return t;
    }

    @Override
    public List<String> tableFunctions() {
        return TABLE_FUNCTIONS;
    }

    /**
     * Binds {@code read_parquet(path)} and {@code parquet_scan(path)}, which
     * are the same, to a table of the files of the path in the schema
     * {@code main} under a generated name that starts with {@code #} and
     * does not appear among the tables of the catalog. The path is a file,
     * a directory or a pattern with {@code *} and {@code ?} in the name of
     * the file. The same call on unchanged files gives the same table; a
     * changed, added or removed file gives a new one.
     */
    @Override
    public TableRef bind(String function, List<Object> arguments, Map<String, Object> namedArguments) {
        if (!TABLE_FUNCTIONS.contains(function)) {
            throw new IllegalArgumentException("Unknown table function " + function);
        }
        if (arguments.size() != 1 || !(arguments.get(0) instanceof String path)) {
            throw new IllegalArgumentException(function + " takes the path of a file, a directory or a pattern"
                    + " as its argument");
        }
        if (!namedArguments.isEmpty()) {
            throw new IllegalArgumentException("Invalid named parameter \"" + namedArguments.keySet().iterator().next()
                    + "\" for function " + function + "; it has none");
        }
        String location = ParquetFiles.absolute(path, Path.of(""));
        List<ParquetFiles.Version> versions = new ArrayList<>();
        for (Path file : ParquetFiles.resolve(location)) {
            versions.add(ParquetFiles.Version.of(file));
        }
        String table = calls.computeIfAbsent(versions, v -> {
            String generated = CALL_PREFIX + callCount.incrementAndGet();
            callTables.put(generated, files.table(location));
            return generated;
        });
        return TableRef.of(name, table);
    }

    /**
     * Takes over a limit unless there are filters, and {@code count(*)},
     * once or more often, without groups and filters; reads the fields of
     * {@code STRUCT} columns a scan asks for instead of the whole columns.
     * Keeps the filters to skip row groups and pages by.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        // The filters refer to the paths of the request, which the plan reads as they are, fields included.
        return CAPABILITIES.plan(table, table(table).schema(), request, new State(request.limit(), request.filters()));
    }

    private static boolean countsRows(PlanNode.Aggregation aggregation) {
        if (!aggregation.groupColumns().isEmpty() || aggregation.calls().isEmpty()) {
            return false;
        }
        for (PlanNode.AggregateCall call : aggregation.calls()) {
            if (call.function() != PlanNode.AggregateFunction.COUNT_STAR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a split per row group of the files of the table as they are
     * now, in the order of the files, without empty row groups and without
     * those in which no row can pass the filters; under a limit only the
     * row groups it needs. {@code count(*)} is one split.
     *
     * @throws IllegalArgumentException if the files no longer exist or no
     *         longer have the columns of the table, or if a file cannot be
     *         read
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        ParquetFiles.Table table = current(plan.table());
        if (plan.aggregationAccepted()) {
            return List.of(new Split(plan, 0, new Count(table.rows())));
        }
        State state = (State) plan.state();
        long left = plan.limitAccepted() ? state.limit().getAsLong() : Long.MAX_VALUE;
        List<Split> splits = new ArrayList<>();
        for (ParquetFiles.Version version : table.files()) {
            ParquetFiles.Footer footer = files.footer(version.file());
            FilterPredicate predicate = state.filters().isEmpty() ? null
                    : FilterTranslation.translate(state.filters(), footer.schema(), plan.columns());
            List<BlockMetaData> blocks = footer.metadata().getBlocks();
            List<BlockMetaData> passing = predicate == null ? blocks : passing(footer, predicate);
            for (int b = 0; b < blocks.size() && left > 0; b++) {
                long rows = blocks.get(b).getRowCount();
                if (rows > 0 && contains(passing, blocks.get(b))) {
                    long take = Math.min(rows, left);
                    left -= take;
                    splits.add(new Split(plan, splits.size(), new RowGroup(footer, b, take, predicate)));
                }
            }
        }
        return splits;
    }

    /**
     * Returns the row groups of a file in which a row can satisfy a
     * predicate, by their statistics, dictionaries and Bloom filters.
     */
    private static List<BlockMetaData> passing(ParquetFiles.Footer footer, FilterPredicate predicate) {
        Path file = footer.version().file();
        InputFile input = new LocalInputFile(file);
        try (ParquetFileReader reader = new ParquetFileReader(input, footer.metadata(),
                ParquetFiles.options(FilterCompat.get(predicate), true), input.newStream())) {
            return reader.getRowGroups();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Parquet file " + file + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    /** Tells whether a list holds a row group itself, not one that equals it. */
    private static boolean contains(List<BlockMetaData> blocks, BlockMetaData block) {
        for (BlockMetaData b : blocks) {
            if (b == block) {
                return true;
            }
        }
        return false;
    }

    /** Returns the files of a table as they are now, with the columns of the table. */
    private ParquetFiles.Table current(TableRef ref) {
        ParquetFiles.Table known = table(ref);
        ParquetFiles.Table now = files.table(known.location());
        if (!now.schema().equals(known.schema())) {
            throw new IllegalArgumentException("The files of the table " + ref + " changed their columns since they"
                    + " were read: " + ParquetFiles.columns(known.schema()) + " and now "
                    + ParquetFiles.columns(now.schema()));
        }
        return now;
    }

    @Override
    public BatchReader open(Split split) {
        ScanPlan plan = split.plan();
        if (split.state() instanceof Count count) {
            List<Vector> counts = new ArrayList<>();
            for (int c = 0; c < plan.output().size(); c++) {
                counts.add(Vectors.of(DataType.BIGINT, count.rows()));
            }
            Batch batch = new Batch(plan.output(), 1, counts);
            return new BatchReader() {
                private boolean done;

                @Override
                public Batch next() {
                    if (done) {
                        return null;
                    }
                    done = true;
                    return batch;
                }
            };
        }
        if (!(split.state() instanceof RowGroup group)) {
            throw new IllegalArgumentException("Not a split of the Parquet source " + name + ": " + split);
        }
        return new RowGroupReader(group.footer(), group.block(), group.rows(), group.predicate(), plan.output(),
                plan.columns(), batchSize, reads == null ? null : this::record);
    }

    /** Collects the tables of a Parquet connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, String> locations = new LinkedHashMap<>();
        private int batchSize = 2048;
        private boolean recordReads;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("The batch size must be positive: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /** Keeps every row group read, see {@link ParquetConnector#reads()}; off by default. */
        public Builder recordReads(boolean record) {
            this.recordReads = record;
            return this;
        }

        /** Adds a file or a directory of {@code .parquet} files as a table; a name may not start with {@code #}. */
        public Builder path(String table, Path path) {
            return path(table, path.toString());
        }

        /**
         * Adds a file, a directory of {@code .parquet} files or a pattern with
         * {@code *} and {@code ?} in the name of the file as a table, relative
         * to the working directory unless absolute; a name may not start
         * with {@code #}.
         */
        public Builder path(String table, String location) {
            if (table.startsWith(CALL_PREFIX)) {
                throw new IllegalArgumentException("A table name may not start with " + CALL_PREFIX + ": " + table);
            }
            locations.put(table, location);
            return this;
        }

        /**
         * Adds every {@code .parquet} file of a directory as a table named
         * after the file, and every subdirectory with {@code .parquet} files
         * as a table of these files named after the subdirectory.
         */
        public Builder directory(Path directory) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                List<Path> sorted = new ArrayList<>();
                entries.forEach(sorted::add);
                sorted.sort(null);
                for (Path p : sorted) {
                    if (Files.isDirectory(p)) {
                        if (!ParquetFiles.parquetFiles(p).isEmpty()) {
                            path(p.getFileName().toString(), p);
                        }
                    } else if (ParquetFiles.isParquetFile(p)) {
                        path(ParquetFiles.tableName(p), p);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + directory, e);
            }
            return this;
        }

        /**
         * Builds the connector, reading the footers of the files of every
         * table.
         *
         * @throws IllegalArgumentException if a location names no file, a
         *         file is not a Parquet file, or the files of a table differ
         *         in their columns
         */
        public ParquetConnector build() {
            ParquetFiles files = new ParquetFiles();
            Map<String, ParquetFiles.Table> tables = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : locations.entrySet()) {
                try {
                    tables.put(e.getKey(), files.table(ParquetFiles.absolute(e.getValue(), Path.of(""))));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("The table " + e.getKey() + " of the Parquet source " + name
                            + ": " + ex.getMessage(), ex);
                }
            }
            return new ParquetConnector(name, files, tables, batchSize, recordReads);
        }
    }
}
