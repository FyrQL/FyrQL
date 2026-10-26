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
package net.fyrql.connector.xlsx;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileReaderProvider;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.LocalFileStore;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The sheets of Excel workbooks ({@code .xlsx} and {@code .xlsm}) as tables
 * in the schema {@code main}: the first worksheet of every workbook of a
 * directory, or a sheet or a range of a workbook added one by one. A table
 * is read as DuckDB's {@code read_xlsx} reads it, see {@link SheetSchema}:
 * the header, the names and the types of the columns come from the first
 * rows, and reading stops at the first empty row. A formula is read as the
 * result stored with it; a workbook in the binary format ({@code .xlsb}),
 * an old one ({@code .xls}) or an encrypted one is an error.
 * <p>
 * The connector also offers the table function {@code read_xlsx} for any
 * workbook; built without files it serves only it. See {@link #bind}. It
 * writes workbooks for {@code COPY ... TO}, see {@link #openFile}, and
 * reads them with the columns of a table for {@code COPY ... FROM}, see
 * {@link #fileTable}. Each table is read in one part, as a sheet cannot be
 * cut safely; several tables are read at the same time.
 * <p>
 * The shared strings of a workbook are held in memory while one of its
 * sheets is read, outside the memory budget of a query, as every reader of
 * the format holds them; the limits of {@link ZipArchive} bound them.
 */
public final class XlsxConnector implements Connector, TableFunctionProvider, FileReaderProvider,
        FileWriterProvider {

    static final List<String> FILE_FORMATS = List.of("xlsx");

    private static final List<String> TABLE_FUNCTIONS = List.of("read_xlsx");

    /** The prefix of the names of the tables of table function calls, which no registered file gets. */
    private static final String CALL_PREFIX = "#";

    private static final String READ_OPTIONS = "header, sheet, range, stop_at_empty, all_varchar, ignore_errors,"
            + " empty_as_varchar, normalize_names";

    private final String name;
    private volatile FileStores stores = FileStores.local(Path.of(""));
    private final Map<String, Table> tables;
    private final Map<String, Table> found = new ConcurrentHashMap<>();
    private final int batchSize;
    private final Catalog catalog;
    private final Map<String, Table> callTables = new ConcurrentHashMap<>();
    private final Map<Object, String> calls = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * A table: the workbook, how to read it and, once known, its layout.
     *
     * @param location the location of the workbook, an absolute local path or one with a scheme
     * @param schema the layout, or null for a workbook in another store not looked up yet
     * @param zone the time zone of a {@code TIMESTAMP WITH TIME ZONE} of given columns
     */
    record Table(String location, SheetSchema.Options options, SheetSchema schema, ZoneId zone) {
    }

    /** A call of the table function: the workbook in its current version and how to read it. */
    private record Call(String location, String version, long size, SheetSchema.Options options) {
    }

    /** A workbook in its current version read with given columns and options. */
    private record FileCall(String location, String version, long size, Schema schema, Map<String, String> options) {
    }

    private XlsxConnector(String name, Map<String, Table> tables, int batchSize) {
        this.name = name;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.batchSize = batchSize;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(XlsxConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).schema().schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                long rows = table(table).schema().rows();
                return rows < 0 ? OptionalLong.empty() : OptionalLong.of(rows);
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

    @Override
    public void fileStores(FileStores stores) {
        this.stores = stores;
    }

    private Table table(TableRef ref) {
        Table t = null;
        if (ref.schema().equals(TableRef.MAIN)) {
            t = ref.table().startsWith(CALL_PREFIX) ? callTables.get(ref.table()) : tables.get(ref.table());
        }
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the Excel source " + name);
        }
        if (t.schema() == null) {
            Table pending = t;
            t = found.computeIfAbsent(ref.table(), k -> {
                try {
                    return lookUp(pending, object(pending.location()));
                } catch (IllegalArgumentException | UncheckedIOException e) {
                    throw new IllegalArgumentException("The table " + k + " of the Excel source " + name + ": "
                            + e.getMessage(), e);
                }
            });
        }
        return t;
    }

    /** Reads the layout of the sheet of a table. */
    private static Table lookUp(Table table, FileObject file) {
        try {
            return new Table(table.location(), table.options(), SheetSchema.infer(Book.open(file), table.options()),
                    table.zone());
        } catch (IOException e) {
            throw failure(file, e);
        }
    }

    /**
     * Returns the error of a workbook that cannot be read, which is one of
     * its content as far as the message tells, with its location once.
     */
    private static IllegalArgumentException failure(FileObject file, IOException e) {
        String message = String.valueOf(e.getMessage());
        return new IllegalArgumentException(message.contains(file.location()) ? message
                : "Cannot read " + file.location() + ": " + message, e);
    }

    /**
     * Returns the file at a location of any store.
     *
     * @throws IllegalArgumentException if there is none or it cannot be read
     */
    private FileObject object(String location) {
        try {
            return stores.object(location);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("No files found that match the pattern \"" + location + "\"", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the absolute location of a file: a local path, also a URI of
     * the scheme {@code file}, as {@link LocalFileStore#resolve} resolves it,
     * so that the file read is the one the policy of the engine checked; a
     * location with another scheme as it is.
     */
    private static String absolute(String location) {
        if (!FileStores.isLocal(location)) {
            return location;
        }
        try {
            Path path = location.regionMatches(true, 0, "file:", 0, 5) ? LocalFileStore.resolve(location, Path.of(""))
                    : Path.of(location);
            return path.toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a file: " + location + " (" + e.getReason() + ")", e);
        }
    }

    @Override
    public List<String> tableFunctions() {
        return TABLE_FUNCTIONS;
    }

    /**
     * Binds {@code read_xlsx(path, ...)} to a table of the workbook in the
     * schema {@code main} under a name of {@code #} and the text of the call,
     * which does not appear among the tables of the catalog. The named
     * arguments are those of DuckDB: {@code header} (decided by default),
     * {@code sheet} (the first worksheet by default), {@code range} (such as
     * {@code A1:C10} or {@code A:C}), {@code stop_at_empty} (true by default,
     * false with a range), {@code all_varchar}, {@code ignore_errors},
     * {@code empty_as_varchar} and {@code normalize_names} (all false by
     * default); see {@link SheetSchema}. A boolean argument may also be a
     * value that casts to one. The same call on an unchanged workbook gives
     * the same table; a changed one is read again.
     */
    @Override
    public TableRef bind(String function, List<Object> arguments, Map<String, Object> namedArguments) {
        if (!TABLE_FUNCTIONS.contains(function)) {
            throw new IllegalArgumentException("Unknown table function " + function);
        }
        if (arguments.size() != 1 || !(arguments.get(0) instanceof String path)) {
            throw new IllegalArgumentException(function + " takes the path of one file as its argument");
        }
        if (path.indexOf('*') >= 0 || path.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Patterns of file names are not supported yet: " + path);
        }
        String sheet = null;
        Boolean header = null;
        CellRef.Range range = null;
        Boolean stopAtEmpty = null;
        boolean allVarchar = false;
        boolean ignoreErrors = false;
        boolean emptyAsVarchar = false;
        boolean normalizeNames = false;
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            switch (key) {
                case "header" -> header = bool(function, key, value);
                case "sheet" -> sheet = string(function, key, value);
                case "range" -> range = CellRef.Range.parse(string(function, key, value));
                case "stop_at_empty" -> stopAtEmpty = bool(function, key, value);
                case "all_varchar" -> allVarchar = bool(function, key, value);
                case "ignore_errors" -> ignoreErrors = bool(function, key, value);
                case "empty_as_varchar" -> emptyAsVarchar = bool(function, key, value);
                case "normalize_names" -> normalizeNames = bool(function, key, value);
                case FileWriterProvider.TIME_ZONE -> string(function, key, value);
                default -> throw new IllegalArgumentException("Invalid named parameter \"" + key + "\" for function "
                        + function + "; supported are " + READ_OPTIONS);
            }
        }
        SheetSchema.Options options = new SheetSchema.Options(sheet, header, range, stopAtEmpty, allVarchar,
                ignoreErrors, emptyAsVarchar, normalizeNames);
        FileObject file = object(absolute(path));
        Call call = new Call(file.location(), file.version(), file.size(), options);
        String table = calls.computeIfAbsent(call, k -> {
            Table t = lookUp(new Table(file.location(), options, null, ZoneOffset.UTC), file);
            return callName(TableFunctionProvider.callText(function, arguments, namedArguments), t);
        });
        return TableRef.of(name, table);
    }

    @Override
    public List<String> fileFormats() {
        return FILE_FORMATS;
    }

    /**
     * Returns a table that reads a workbook with the given columns, for
     * {@code COPY ... FROM}: the cells of a row are the columns in their
     * order from the first column of the table, read as their types. The
     * options are those of {@link #bind}, {@code header} decided as there,
     * as DuckDB does; an unknown one is an error. The same workbook in the
     * same version with the same columns and options gives the same table.
     */
    @Override
    public TableRef fileTable(String format, String location, Schema schema, Map<String, String> options) {
        if (!FILE_FORMATS.contains(format)) {
            throw new IllegalArgumentException("The Excel source " + name + " reads no files of the format " + format);
        }
        String file = absolute(location);
        ConfigOptions o = new ConfigOptions("the Excel file " + location, options, Path.of(""));
        SheetSchema.Options read = new SheetSchema.Options(o.string("sheet").orElse(null),
                o.bool("header").orElse(null), o.get("range", null, CellRef.Range::parse).orElse(null),
                o.bool("stop_at_empty").orElse(null), o.bool("all_varchar").orElse(false),
                o.bool("ignore_errors").orElse(false), o.bool("empty_as_varchar").orElse(false),
                o.bool("normalize_names").orElse(false));
        ZoneId zone = o.get(FileWriterProvider.TIME_ZONE, null, FileWriterProvider::timeZone).orElse(ZoneOffset.UTC);
        o.rejectUnknown();
        for (Schema.Field f : schema.fields()) {
            if (f.type().isNested()) {
                throw new IllegalArgumentException("An Excel workbook holds no values of the type "
                        + f.type().sqlName() + ", as the column " + f.name() + " has");
            }
        }
        FileObject object = object(file);
        FileCall call = new FileCall(object.location(), object.version(), object.size(), schema, Map.copyOf(options));
        String table = calls.computeIfAbsent(call, k -> {
            SheetSchema found = lookUp(new Table(object.location(), read, null, zone), object).schema();
            SheetSchema given = new SheetSchema(found.sheet(), schema, found.firstColumn(), found.dataRow(),
                    found.lastRow(), found.stopAtEmpty(), found.ignoreErrors(), found.rows());
            return callName(TableFunctionProvider.callText("read_" + format, List.of(location), options),
                    new Table(object.location(), read, given, zone));
        });
        return TableRef.of(name, table);
    }

    /**
     * Opens a sink that writes a workbook for {@code COPY ... TO}; see
     * {@link XlsxFileWriter}.
     */
    @Override
    public Sink openFile(String format, String location, Schema schema, Map<String, String> options) {
        if (!FILE_FORMATS.contains(format)) {
            throw new IllegalArgumentException("The Excel source " + name + " writes no files of the format " + format);
        }
        return XlsxFileWriter.open(location, stores, schema, options);
    }

    private static String string(String function, String key, Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("The argument " + key + " of " + function + " must be a VARCHAR");
        }
        return s;
    }

    /** Reads a boolean argument; as in DuckDB, a string or a number is cast to a boolean. */
    private static boolean bool(String function, String key, Object value) {
        try {
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                return (Boolean) Casts.parse(s, DataType.BOOLEAN);
            }
            if (value instanceof Number n) {
                return n.doubleValue() != 0;
            }
        } catch (RuntimeException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("The argument " + key + " of " + function + " must be a BOOLEAN");
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        return ScanPlan.columnsOnly(table, table(table).schema().schema(), request, null);
    }

    /** Returns one split: a sheet is read from its start to its end. */
    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, null));
    }

    /** Reads the requested columns in batches, see {@link SheetBatches}. */
    @Override
    public BatchReader open(Split split) {
        Table table = table(split.plan().table());
        FileObject file = object(table.location());
        try {
            return SheetBatches.open(Book.open(file), table.schema(), split.plan().wholeColumns(),
                    split.plan().output(), batchSize, table.zone());
        } catch (IOException e) {
            throw failure(file, e);
        }
    }

    /** Collects the workbooks of an Excel connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, String> locations = new LinkedHashMap<>();
        private final Map<String, SheetSchema.Options> perTable = new LinkedHashMap<>();
        private SheetSchema.Options options = SheetSchema.Options.DEFAULT;
        private int batchSize = 2048;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Tells whether the first row of each table holds the column names; decided as DuckDB does by default. */
        public Builder header(boolean header) {
            options = new SheetSchema.Options(null, header, null, options.stopAtEmpty(), options.allVarchar(),
                    options.ignoreErrors(), options.emptyAsVarchar(), options.normalizeNames());
            return this;
        }

        /** Tells whether reading stops at the first empty row; true by default, false for a table with a range. */
        public Builder stopAtEmpty(boolean stopAtEmpty) {
            options = new SheetSchema.Options(null, options.header(), null, stopAtEmpty, options.allVarchar(),
                    options.ignoreErrors(), options.emptyAsVarchar(), options.normalizeNames());
            return this;
        }

        /** Reads every column as a {@code VARCHAR}; false by default. */
        public Builder allVarchar(boolean allVarchar) {
            options = new SheetSchema.Options(null, options.header(), null, options.stopAtEmpty(), allVarchar,
                    options.ignoreErrors(), options.emptyAsVarchar(), options.normalizeNames());
            return this;
        }

        /** Reads a value that does not fit its column as {@code NULL} instead of failing; false by default. */
        public Builder ignoreErrors(boolean ignoreErrors) {
            options = new SheetSchema.Options(null, options.header(), null, options.stopAtEmpty(),
                    options.allVarchar(), ignoreErrors, options.emptyAsVarchar(), options.normalizeNames());
            return this;
        }

        /**
         * Makes a column whose first value is empty a {@code VARCHAR} instead
         * of a {@code DOUBLE}; false by default.
         */
        public Builder emptyAsVarchar(boolean emptyAsVarchar) {
            options = new SheetSchema.Options(null, options.header(), null, options.stopAtEmpty(),
                    options.allVarchar(), options.ignoreErrors(), emptyAsVarchar, options.normalizeNames());
            return this;
        }

        /** Normalizes the names of the columns as DuckDB's {@code normalize_names} does; false by default. */
        public Builder normalizeNames(boolean normalizeNames) {
            options = new SheetSchema.Options(null, options.header(), null, options.stopAtEmpty(),
                    options.allVarchar(), options.ignoreErrors(), options.emptyAsVarchar(), normalizeNames);
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Adds the first worksheet of a workbook as a table, a local one,
         * relative to the working directory unless absolute, or one in a
         * store of the engine such as {@code s3://bucket/key}, which is
         * looked up when the table is first used; a name may not start with
         * {@code #}.
         */
        public Builder file(String table, String location) {
            return file(table, location, null, null);
        }

        /** Adds a workbook given as a local path as a table, see {@link #file(String, String)}. */
        public Builder file(String table, Path file) {
            return file(table, file.toString(), null, null);
        }

        /**
         * Adds a sheet of a workbook as a table, see {@link #file(String, String)}.
         *
         * @param sheet the name of the sheet, or null for the first worksheet
         * @param range the cells to read, such as {@code A1:C10} or {@code A:C}, or null for those with values
         */
        public Builder file(String table, String location, String sheet, String range) {
            if (table.startsWith(CALL_PREFIX)) {
                throw new IllegalArgumentException("A table name may not start with " + CALL_PREFIX + ": " + table);
            }
            CellRef.Range parsed = range == null ? null : CellRef.Range.parse(range);
            locations.put(table, absolute(location));
            perTable.put(table, new SheetSchema.Options(sheet, null, parsed, null, false, false, false, false));
            return this;
        }

        /**
         * Adds the first worksheet of every {@code .xlsx} and {@code .xlsm}
         * workbook of a directory, named after the file.
         */
        public Builder directory(Path directory) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                    p -> Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).matches(".*\\.xls[xm]"))) {
                List<Path> sorted = new ArrayList<>();
                files.forEach(sorted::add);
                sorted.sort(null);
                for (Path p : sorted) {
                    String fileName = p.getFileName().toString();
                    file(fileName.substring(0, fileName.lastIndexOf('.')), p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + directory, e);
            }
            return this;
        }

        /**
         * Builds the connector, reading the layout of every local table; a
         * table in another store is read when it is first used.
         *
         * @throws IllegalArgumentException if a local workbook cannot be read
         *         as a table
         */
        public XlsxConnector build() {
            Map<String, Table> tables = new LinkedHashMap<>();
            LocalFileStore local = FileStores.local(Path.of("")).local();
            for (Map.Entry<String, String> e : locations.entrySet()) {
                SheetSchema.Options own = perTable.get(e.getKey());
                SheetSchema.Options merged = new SheetSchema.Options(own.sheet(), options.header(), own.range(),
                        options.stopAtEmpty(), options.allVarchar(), options.ignoreErrors(), options.emptyAsVarchar(),
                        options.normalizeNames());
                Table t = new Table(e.getValue(), merged, null, ZoneOffset.UTC);
                if (FileStores.isLocal(e.getValue())) {
                    try {
                        t = lookUp(t, local.object(e.getValue()));
                    } catch (IOException | IllegalArgumentException | UncheckedIOException ex) {
                        throw new IllegalArgumentException("The table " + e.getKey() + " of the Excel source " + name
                                + ": " + ex.getMessage(), ex);
                    }
                }
                tables.put(e.getKey(), t);
            }
            return new XlsxConnector(name, tables, batchSize);
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
