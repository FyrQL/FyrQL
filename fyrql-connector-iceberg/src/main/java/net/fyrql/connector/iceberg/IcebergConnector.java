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

package net.fyrql.connector.iceberg;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileAccessException;
import net.fyrql.connector.FileAccessPolicy;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.SourceCounters;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.connector.parquet.ParquetFile;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Apache Iceberg tables as a source, read in pure Java without Hadoop and
 * without a catalog: a table is the directory of a table, the one with
 * {@code metadata/}, or a metadata file, local or in a store of the engine
 * such as {@code s3://bucket/warehouse/db/orders}.
 * <p>
 * Format versions 1 to 3 are read: the snapshot's manifest list and
 * manifests name the data files, the columns of a data file are found by
 * their field ids, columns a file lacks are its identity partition value,
 * the {@code initial-default} or {@code NULL}, and a promoted column is
 * read in its new type. Deletion vectors, position deletes and equality
 * deletes remove rows. Data files must be Parquet files.
 * <p>
 * Tables come from the builder or from the table functions
 * {@code iceberg_scan(location)} and {@code read_iceberg(location)}, with
 * the named parameters {@code snapshot_from_id}, {@code snapshot_from_timestamp}
 * and {@code ref} for a branch or tag; {@code iceberg_snapshots(location)}
 * gives the snapshots of a table as DuckDB does.
 * <p>
 * A scan takes over no filters, but skips row groups and, without
 * position deletes, pages by them; a limit and {@code count(*)} are taken
 * over only when the summary of the snapshot says that no delete file
 * applies.
 */
public final class IcebergConnector implements Connector, TableFunctionProvider {

    private static final List<String> SCANS = List.of("iceberg_scan", "read_iceberg");
    private static final String SNAPSHOTS = "iceberg_snapshots";
    private static final List<String> TABLE_FUNCTIONS = List.of("iceberg_scan", "read_iceberg", SNAPSHOTS);

    /** The prefix of the names of the tables of table function calls, which no registered table gets. */
    private static final String CALL_PREFIX = "#";

    /** The columns of {@code iceberg_snapshots}, as DuckDB names them. */
    private static final Schema SNAPSHOT_COLUMNS = new Schema(List.of(
            new Schema.Field("sequence_number", DataType.BIGINT, false),
            new Schema.Field("snapshot_id", DataType.BIGINT, false),
            new Schema.Field("timestamp_ms", DataType.TIMESTAMP, false),
            new Schema.Field("manifest_list", DataType.VARCHAR, true)));

    /**
     * A row group a scan read.
     *
     * @param file the location of the data file
     * @param rowGroup the index of the row group in the file
     * @param columns the paths of the primitive columns read, in the order of the file
     * @param rows the rows read, before deletes
     */
    public record Read(String file, int rowGroup, List<String> columns, long rows) {
        public Read {
            columns = List.copyOf(columns);
        }
    }

    /** A table a name refers to: a scan of a table, or its snapshots. */
    private record Table(IcebergFiles.TableSpec spec, IcebergFiles.Resolved resolved, boolean snapshots) {
    }

    /**
     * What a scan remembers: the table in its snapshot, the filters to skip
     * by, the limit and the deletes, and whether the table comes from a
     * call, whose files the policy of the engine must allow.
     */
    private record State(IcebergFiles.Resolved table, List<Expression> filters, OptionalLong limit,
            Deletes deletes, boolean call) {
    }

    /** The split of a row group of a data file. */
    private record RowGroup(SnapshotScan.FileTask task, ParquetFile file, int group, long rows,
            IcebergReader.Layout layout) {
    }

    /** The split of rows computed whole: {@code count(*)} or the snapshots. */
    private record Rows(Batch batch) {
    }

    private final String name;
    private final IcebergFiles files;
    private final Map<String, IcebergFiles.TableSpec> registered;
    private final int batchSize;
    private final Catalog catalog;
    private final Map<String, Table> callTables = new ConcurrentHashMap<>();
    private final Map<List<Object>, String> calls = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final List<Read> reads;

    private IcebergConnector(String name, IcebergFiles files, Map<String, IcebergFiles.TableSpec> registered,
            int batchSize, boolean recordReads) {
        this.name = name;
        this.files = files;
        this.registered = Collections.unmodifiableMap(new LinkedHashMap<>(registered));
        this.batchSize = batchSize;
        this.reads = recordReads ? new ArrayList<>() : null;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(registered.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                Table t = table(table);
                return t.snapshots() ? SNAPSHOT_COLUMNS : t.resolved().columns();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                Table t = table(table);
                return t.snapshots() ? OptionalLong.of(t.resolved().metadata().snapshots().size())
                        : t.resolved().rowsWithoutDeletes();
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

    /** Takes the stores of the engine, through which locations with a scheme are read. */
    @Override
    public void fileStores(FileStores stores) {
        files.stores(stores);
    }

    /**
     * Returns the row groups of data files read so far, in the order their
     * readers were done, if the connector records them; otherwise an empty
     * list.
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

    private void record(ParquetConnector.Read read) {
        synchronized (reads) {
            reads.add(new Read(read.file(), read.rowGroup(), read.columns(), read.rows()));
        }
    }

    /**
     * Returns a table: a call table as it was bound, a registered one in
     * its snapshot as it is now.
     */
    private Table table(TableRef ref) {
        if (ref.schema().equals(TableRef.MAIN)) {
            if (ref.table().startsWith(CALL_PREFIX)) {
                Table t = callTables.get(ref.table());
                if (t != null) {
                    return t;
                }
            } else {
                IcebergFiles.TableSpec spec = registered.get(ref.table());
                if (spec != null) {
                    try {
                        return new Table(spec, files.resolve(spec), false);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("The table " + ref.table() + " of the Iceberg source "
                                + name + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        throw new IllegalArgumentException("No table " + ref + " in the Iceberg source " + name);
    }

    @Override
    public List<String> tableFunctions() {
        return TABLE_FUNCTIONS;
    }

    /**
     * Binds {@code iceberg_scan(location)} and {@code read_iceberg(location)},
     * which are the same, to the table at a location in its current snapshot
     * or the one the named parameters select, and
     * {@code iceberg_snapshots(location)} to its snapshots, each under a
     * name of {@code #} and the text of the call. The location is the
     * directory of the table or a metadata file, local or in a store of the
     * engine. The named parameters:
     * <ul>
     * <li>{@code snapshot_from_id}: the id of a snapshot;</li>
     * <li>{@code snapshot_from_timestamp}: a time, a {@code TIMESTAMP} in
     * UTC, a {@code TIMESTAMP WITH TIME ZONE} or text with or without an
     * offset, the latter in UTC; the snapshot that was current then;</li>
     * <li>{@code ref}: the name of a branch or tag.</li>
     * </ul>
     * The same call on an unchanged table gives the same table.
     */
    @Override
    public TableRef bind(String function, List<Object> arguments, Map<String, Object> namedArguments) {
        if (!TABLE_FUNCTIONS.contains(function)) {
            throw new IllegalArgumentException("Unknown table function " + function);
        }
        if (arguments.size() != 1 || !(arguments.get(0) instanceof String location)) {
            throw new IllegalArgumentException(function + " takes the location of an Iceberg table, its directory"
                    + " or a metadata file, as its argument");
        }
        boolean snapshots = function.equals(SNAPSHOTS);
        Long snapshotId = null;
        Long asOf = null;
        String ref = null;
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case FileWriterProvider.TIME_ZONE -> {
                }
                case "snapshot_from_id" -> snapshotId = snapshots ? unknown(function, e.getKey()) : id(v);
                case "snapshot_from_timestamp" -> asOf = snapshots ? unknown(function, e.getKey()) : millis(v);
                case "ref" -> ref = snapshots ? unknown(function, e.getKey()) : text(v, "ref");
                default -> unknown(function, e.getKey());
            }
        }
        IcebergFiles.TableSpec spec = new IcebergFiles.TableSpec(IcebergFiles.absolute(location, Path.of("")),
                snapshotId, asOf, ref);
        IcebergFiles.Resolved resolved = files.resolve(spec, files.stores().policy());
        List<Object> key = List.of(snapshots, resolved.location().metadataFile().location(),
                resolved.location().metadataFile().version(), resolved.location().metadataFile().size(),
                resolved.snapshot() == null ? -1L : resolved.snapshot().id());
        String table = calls.computeIfAbsent(key, k -> {
            return callName(TableFunctionProvider.callText(function, arguments, namedArguments),
                    new Table(spec, resolved, snapshots));
        });
        return TableRef.of(name, table);
    }

    private static <T> T unknown(String function, String parameter) {
        throw new IllegalArgumentException("Invalid named parameter \"" + parameter + "\" for function " + function
                + "; it has " + (function.equals(SNAPSHOTS) ? "only timezone"
                        : "snapshot_from_id, snapshot_from_timestamp, ref and timezone"));
    }

    private static Long id(Object v) {
        if (v instanceof Number n && !(v instanceof Double) && !(v instanceof Float)) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                // Below.
            }
        }
        throw new IllegalArgumentException("snapshot_from_id must be the id of a snapshot, not " + v);
    }

    /**
     * Returns a time in milliseconds since 1970: a {@code TIMESTAMP} or
     * {@code TIMESTAMP WITH TIME ZONE} in microseconds, or text.
     */
    static Long millis(Object v) {
        if (v instanceof Long micros) {
            return Math.floorDiv(micros, 1000L);
        }
        if (v instanceof String s) {
            String t = s.trim().replace(' ', 'T');
            try {
                return OffsetDateTime.parse(t).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return Instant.parse(t).toEpochMilli();
                } catch (DateTimeParseException e2) {
                    try {
                        return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli();
                    } catch (DateTimeParseException e3) {
                        // Below.
                    }
                }
            }
        }
        throw new IllegalArgumentException("snapshot_from_timestamp must be a timestamp, not " + v);
    }

    private static String text(Object v, String parameter) {
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(parameter + " must be text, not " + v);
    }

    /**
     * Takes over a limit without filters, and {@code count(*)} without
     * groups and filters, both only when no delete file applies to the
     * snapshot; reads the fields of {@code STRUCT} columns a scan asks for.
     * Keeps the filters to skip row groups and pages by.
     */
    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        Table t = table(table);
        if (t.snapshots()) {
            return Capabilities.NONE.plan(table, SNAPSHOT_COLUMNS, request, new State(t.resolved(), List.of(),
                    OptionalLong.empty(), null, false));
        }
        boolean noDeletes = t.resolved().rowsWithoutDeletes().isPresent();
        Capabilities capabilities = new Capabilities(f -> false, noDeletes, o -> false,
                a -> noDeletes && countsRows(a), true);
        return capabilities.plan(table, t.resolved().columns(), request, new State(t.resolved(), request.filters(),
                request.limit(), new Deletes(files), table.table().startsWith(CALL_PREFIX)));
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
     * Returns a split per row group of the data files of the snapshot of the
     * plan, in the order of the manifests, without the manifests and data
     * files in which no row can pass the filters, see {@link FilePruning},
     * without empty row groups and without those in which no row can pass
     * the filters; under a limit only the row groups it needs. {@code count(*)} and the snapshots are
     * one split.
     *
     * @throws IllegalArgumentException if a file cannot be read, a data file
     *         is not a Parquet file or has no field ids
     * @throws FileAccessException if the table comes from a call and the
     *         policy of the engine does not allow to read one of its
     *         manifest lists, manifests, data or delete files
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        State state = (State) plan.state();
        IcebergFiles.Resolved table = state.table();
        if (state.deletes() == null) {
            List<Vector> columns = new ArrayList<>();
            List<Object[]> rows = IcebergFiles.snapshots(table.metadata());
            for (int c = 0; c < plan.output().size(); c++) {
                int source = plan.columns().get(c).column();
                List<Object> values = new ArrayList<>();
                rows.forEach(r -> values.add(r[source]));
                columns.add(Vectors.of(plan.output().type(c), values));
            }
            return List.of(new Split(plan, 0, new Rows(new Batch(plan.output(), rows.size(), columns))));
        }
        FilePruning pruning = new FilePruning(table.schema(), plan.columns(), state.filters(),
                table.metadata().specs());
        FileAccessPolicy policy = files.stores().policy();
        Function<String, AvroFile> read = !state.call() ? files::avro : location -> {
            policy.checkRead(location);
            return files.avro(location);
        };
        List<SnapshotScan.FileTask> tasks = table.snapshot() == null ? List.of()
                : SnapshotScan.plan(table.metadata(), table.location(), table.snapshot(), read,
                        pruning::mayMatch, pruning::mayMatch);
        if (state.call()) {
            for (SnapshotScan.FileTask t : tasks) {
                policy.checkRead(t.data().location());
                if (t.deletionVector() != null) {
                    policy.checkRead(t.deletionVector().location());
                }
                for (Manifests.ContentFile d : t.positionDeletes()) {
                    policy.checkRead(d.location());
                }
                for (Manifests.ContentFile d : t.equalityDeletes()) {
                    policy.checkRead(d.location());
                }
            }
        }
        if (plan.aggregationAccepted()) {
            long rows = 0;
            for (SnapshotScan.FileTask t : tasks) {
                rows += t.data().recordCount();
            }
            List<Vector> counts = new ArrayList<>();
            for (int c = 0; c < plan.output().size(); c++) {
                counts.add(Vectors.of(DataType.BIGINT, rows));
            }
            return List.of(new Split(plan, 0, new Rows(new Batch(plan.output(), 1, counts))));
        }
        long left = plan.limitAccepted() ? state.limit().getAsLong() : Long.MAX_VALUE;
        List<Split> splits = new ArrayList<>();
        for (SnapshotScan.FileTask task : tasks) {
            if (left <= 0) {
                break;
            }
            if (!task.data().format().equals("PARQUET")) {
                throw new IllegalArgumentException("The Iceberg table " + table.metadata().location()
                        + " has a data file of the format " + task.data().format() + ", which FyrQL does not read: "
                        + task.data().path());
            }
            ParquetFile file = files.parquet(task.data().location());
            IcebergReader.Layout layout = IcebergReader.layout(table.schema(), plan.columns(), plan.output(),
                    state.filters(), file, task, table.metadata().specs());
            int groups = 0;
            for (int g : file.rowGroups(layout.filters(), layout.read())) {
                if (left <= 0) {
                    break;
                }
                long take = Math.min(file.rows(g), left);
                left -= take;
                splits.add(new Split(plan, splits.size(), new RowGroup(task, file, g, take, layout)));
                groups++;
            }
            SourceCounters.current().rowGroups(groups, file.rowGroups() - groups);
        }
        return splits;
    }

    @Override
    public BatchReader open(Split split) {
        ScanPlan plan = split.plan();
        if (split.state() instanceof Rows rows) {
            return new BatchReader() {
                private boolean done;

                @Override
                public Batch next() {
                    if (done) {
                        return null;
                    }
                    done = true;
                    return rows.batch();
                }
            };
        }
        if (!(split.state() instanceof RowGroup group)) {
            throw new IllegalArgumentException("Not a split of the Iceberg source " + name + ": " + split);
        }
        State state = (State) plan.state();
        return new IcebergReader(group.file(), group.group(), group.rows(), group.layout(), plan.output(),
                group.task(), state.deletes(), !group.task().hasPositionDeletes(), batchSize,
                reads == null ? null : this::record);
    }

    /** Collects the tables of an Iceberg connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, IcebergFiles.TableSpec> tables = new LinkedHashMap<>();
        private int batchSize = 2048;
        private int readAhead = 1 << 20;
        private boolean recordReads;
        private Path directory = Path.of("");

        private Builder(String name) {
            this.name = name;
        }

        /** Sets the number of rows per batch; 2048 by default. */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("The batch size must be positive: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /** Sets the bytes by which a file in a store other than the local one is read ahead; 1 MiB by default. */
        public Builder readAhead(int bytes) {
            if (bytes < 8) {
                throw new IllegalArgumentException("The read-ahead must be at least 8 bytes: " + bytes);
            }
            this.readAhead = bytes;
            return this;
        }

        /** Keeps every row group read, see {@link IcebergConnector#reads()}; off by default. */
        public Builder recordReads(boolean record) {
            this.recordReads = record;
            return this;
        }

        /** Sets the directory relative local locations are resolved against; the working directory by default. */
        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        /** Adds a table in its current snapshot: the directory of the table or a metadata file. */
        public Builder table(String table, String location) {
            return add(table, location, null, null, null);
        }

        /** Adds a table in a snapshot given by its id. */
        public Builder table(String table, String location, long snapshotId) {
            return add(table, location, snapshotId, null, null);
        }

        /** Adds a table in the snapshot that was current at a time. */
        public Builder tableAsOf(String table, String location, Instant time) {
            return add(table, location, null, time.toEpochMilli(), null);
        }

        /** Adds a table in the snapshot of a branch or tag. */
        public Builder tableAtRef(String table, String location, String ref) {
            return add(table, location, null, null, ref);
        }

        private Builder add(String table, String location, Long snapshotId, Long asOf, String ref) {
            if (table.startsWith(CALL_PREFIX)) {
                throw new IllegalArgumentException("A table name may not start with " + CALL_PREFIX + ": " + table);
            }
            tables.put(table, new IcebergFiles.TableSpec(location, snapshotId, asOf, ref));
            return this;
        }

        /** Builds the connector; the tables are read when they are first used. */
        public IcebergConnector build() {
            Map<String, IcebergFiles.TableSpec> absolute = new LinkedHashMap<>();
            tables.forEach((t, s) -> absolute.put(t, new IcebergFiles.TableSpec(IcebergFiles.absolute(s.location(),
                    directory), s.snapshotId(), s.asOf(), s.ref())));
            return new IcebergConnector(name, new IcebergFiles(FileStores.local(Path.of("")), readAhead), absolute,
                    batchSize, recordReads);
        }
    }

    /**
     * Keeps the table of a call under {@code #} and the text of the call, the
     * name the plans show; a call whose text names another table already, as
     * after its file changed, gets a number as well.
     */
    private String callName(String call, Table table) {
        String generated = CALL_PREFIX + call;
        while (callTables.putIfAbsent(generated, table) != null) {
            generated = CALL_PREFIX + call + " #" + callCount.incrementAndGet();
        }
        return generated;
    }
}
