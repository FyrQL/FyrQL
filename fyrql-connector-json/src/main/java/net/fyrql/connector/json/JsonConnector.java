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

package net.fyrql.connector.json;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * JSON files as tables in the schema {@code main}: every {@code .json},
 * {@code .jsonl} and {@code .ndjson} file of a directory, also compressed
 * with gzip as {@code .gz}, or files added one by one. A file holds an
 * array of records or a sequence of them, such as JSON Lines; the columns
 * are inferred from a sample of the records as DuckDB does, see
 * {@link JsonSchemaInference}, unless a schema is given; objects and arrays
 * become {@code STRUCT}, {@code MAP} and {@code LIST} values. A scan reads
 * the file as a stream and skips the values of the columns it does not
 * need; see {@link JsonRecordReader} for how values are read.
 * <p>
 * The connector also offers the table functions {@code read_json},
 * {@code read_json_auto}, {@code read_ndjson} and {@code read_ndjson_auto}
 * for any file; built without files it serves only them. See
 * {@link #bind}.
 */
public final class JsonConnector implements Connector, TableFunctionProvider {

    private static final List<String> TABLE_FUNCTIONS = List.of("read_json", "read_json_auto", "read_ndjson",
            "read_ndjson_auto");

    /** The files a directory adds, and the extensions their table names drop. */
    private static final Pattern JSON_FILE = Pattern.compile("(?i)(.*)\\.(json|jsonl|ndjson)(\\.gz)?");

    /** The prefix of the names of the tables of table function calls, which no registered file gets. */
    private static final String CALL_PREFIX = "#";

    private final String name;
    private final Map<String, Table> tables;
    private final JsonOptions options;
    private final int batchSize;
    private final Catalog catalog;
    /** The tables of table function calls by their names, and the names by the calls. */
    private final Map<String, Table> callTables = new ConcurrentHashMap<>();
    private final Map<Call, String> calls = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();
    /** The rows of each table by its name, counted or estimated when first asked for; -1 if unknown. */
    private final Map<String, Long> rowCounts = new ConcurrentHashMap<>();

    /** A file, how to read it, and its columns. */
    record Table(Path file, JsonOptions options, JsonLayout layout) {
    }

    /** A call of a table function: the file in its current version and how to read it. */
    private record Call(Path file, FileTime modified, long size, JsonOptions options) {
    }

    private JsonConnector(String name, Map<String, Table> tables, JsonOptions options, int batchSize) {
        this.name = name;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.options = options;
        this.batchSize = batchSize;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(JsonConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).layout().schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                Table t = table(table);
                long rows = rowCounts.computeIfAbsent(table.table(), k -> estimateRows(t.file(), t.options()));
                return rows < 0 ? OptionalLong.empty() : OptionalLong.of(rows);
            }
        };
    }

    /**
     * Counts the records of a file, or estimates them from its size and
     * the bytes of the first {@code sampleSize} records; -1 for a
     * compressed file whose size says nothing.
     */
    static long estimateRows(Path file, JsonOptions options) {
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            return -1;
        }
        long sample = options.sampleSize();
        try (JsonInput input = new JsonInput(open(file), options.format(), "", file.toString())) {
            long size = Files.size(file);
            while (input.next()) {
                if (sample >= 0 && input.record() > sample) {
                    long bytes = input.parser().currentTokenLocation().getByteOffset();
                    return bytes <= 0 ? -1 : Math.max(sample, Math.round(size * ((double) sample / bytes)));
                }
                input.parser().skipChildren();
            }
            return input.record();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
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

    private Table table(TableRef ref) {
        Table t = null;
        if (ref.schema().equals(TableRef.MAIN)) {
            t = ref.table().startsWith(CALL_PREFIX) ? callTables.get(ref.table()) : tables.get(ref.table());
        }
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the JSON source " + name);
        }
        return t;
    }

    @Override
    public List<String> tableFunctions() {
        return TABLE_FUNCTIONS;
    }

    /**
     * Binds {@code read_json(path, ...)} and {@code read_json_auto}, which
     * are the same, and {@code read_ndjson} and {@code read_ndjson_auto},
     * which read a sequence of records by default, to a table of the file in
     * the schema {@code main} under a generated name that starts with
     * {@code #} and does not appear among the tables of the catalog. The
     * named arguments are those of DuckDB: {@code format} ({@code 'auto'},
     * {@code 'array'} or {@code 'newline_delimited'}), {@code sample_size}
     * (-1 for all records), {@code ignore_errors},
     * {@code map_inference_threshold}, {@code field_appearance_threshold},
     * {@code dateformat} or {@code date_format}, and
     * {@code timestampformat} or {@code timestamp_format}; they default to
     * the options of the connector. The same call on an unchanged file
     * gives the same table; a changed file is read again.
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
        Path file;
        try {
            file = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + path, e);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No files found that match the pattern \"" + path + "\"");
        }
        JsonOptions o = function.startsWith("read_ndjson") ? options.withFormat(JsonFormat.NEWLINE_DELIMITED)
                : options;
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            Object value = e.getValue();
            String key = e.getKey();
            o = switch (key) {
                case "format" -> o.withFormat(JsonFormat.of(string(function, key, value)));
                case "sample_size" -> o.withSampleSize(integer(function, key, value));
                case "ignore_errors" -> o.withIgnoreErrors(bool(function, key, value));
                case "map_inference_threshold" -> o.withMapInferenceThreshold(integer(function, key, value));
                case "field_appearance_threshold" -> o.withFieldAppearanceThreshold(
                        number(function, key, value));
                case "dateformat", "date_format" -> o.withDateFormat(string(function, key, value));
                case "timestampformat", "timestamp_format" -> o.withTimestampFormat(
                        string(function, key, value));
                default -> throw new IllegalArgumentException("Invalid named parameter \"" + key
                        + "\" for function " + function + "; supported are format, sample_size, ignore_errors,"
                        + " map_inference_threshold, field_appearance_threshold, dateformat, timestampformat");
            };
        }
        Call call;
        try {
            call = new Call(file, Files.getLastModifiedTime(file), Files.size(file), o);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + path + ": " + e.getMessage(), e);
        }
        String table = calls.computeIfAbsent(call, c -> {
            String generated = CALL_PREFIX + callCount.incrementAndGet();
            callTables.put(generated, new Table(c.file(), c.options(), infer(c.file(), c.options())));
            return generated;
        });
        return TableRef.of(name, table);
    }

    private static String string(String function, String key, Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("The argument " + key + " of " + function + " must be a VARCHAR");
        }
        return s;
    }

    /** Reads a whole number; a string is cast, as in DuckDB. */
    private static int integer(String function, String key, Object value) {
        try {
            if (value instanceof Number || value instanceof String) {
                return new BigDecimal(value.toString().strip()).intValueExact();
            }
        } catch (RuntimeException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("The argument " + key + " of " + function + " must be an INTEGER");
    }

    private static double number(String function, String key, Object value) {
        try {
            if (value instanceof Number || value instanceof String) {
                return Double.parseDouble(value.toString().strip());
            }
        } catch (RuntimeException e) {
            // Reported below.
        }
        throw new IllegalArgumentException("The argument " + key + " of " + function + " must be a DOUBLE");
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
        return Capabilities.NONE.withFields(true).plan(table, table(table).layout().schema(), request, null);
    }

    @Override
    public List<Split> splits(ScanPlan plan) {
        return List.of(new Split(plan, 0, null));
    }

    @Override
    public BatchReader open(Split split) {
        Table table = table(split.plan().table());
        return JsonRecordReader.open(open(table.file()), table.layout(), split.plan().columns(),
                table.options(), batchSize, table.file().toString());
    }

    /** Infers the columns of a file. */
    static JsonLayout infer(Path file, JsonOptions options) {
        return JsonSchemaInference.infer(open(file), options, file.toString());
    }

    /** Opens a file, uncompressing it if its name ends in {@code .gz}. */
    static InputStream open(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".zst")) {
            throw new IllegalArgumentException("Files compressed with zstd are not supported: " + file);
        }
        try {
            InputStream in = Files.newInputStream(file);
            return fileName.endsWith(".gz") ? new GZIPInputStream(in, 1 << 16) : in;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + file, e);
        }
    }

    /** Collects the files of a JSON connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, Path> files = new LinkedHashMap<>();
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private JsonOptions options = JsonOptions.DEFAULT;
        private int batchSize = 2048;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Sets all options at once; {@link JsonOptions#DEFAULT} by default. */
        public Builder options(JsonOptions options) {
            this.options = Objects.requireNonNull(options);
            return this;
        }

        public Builder format(JsonFormat format) {
            options = options.withFormat(format);
            return this;
        }

        /** Sets the number of records the columns are inferred from, -1 for all; 20480 by default. */
        public Builder sampleSize(int sampleSize) {
            options = options.withSampleSize(sampleSize);
            return this;
        }

        public Builder mapInferenceThreshold(int mapInferenceThreshold) {
            options = options.withMapInferenceThreshold(mapInferenceThreshold);
            return this;
        }

        public Builder fieldAppearanceThreshold(double fieldAppearanceThreshold) {
            options = options.withFieldAppearanceThreshold(fieldAppearanceThreshold);
            return this;
        }

        /** Tells whether a value that does not fit is {@code NULL} and an unknown key skipped. */
        public Builder ignoreErrors(boolean ignoreErrors) {
            options = options.withIgnoreErrors(ignoreErrors);
            return this;
        }

        /** Sets the only format of {@code strptime} in which a string is taken for a {@code DATE}. */
        public Builder dateFormat(String dateFormat) {
            options = options.withDateFormat(dateFormat);
            return this;
        }

        /** Sets the only format of {@code strptime} in which a string is taken for a {@code TIMESTAMP}. */
        public Builder timestampFormat(String timestampFormat) {
            options = options.withTimestampFormat(timestampFormat);
            return this;
        }

        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("The batch size must be positive: " + batchSize);
            }
            this.batchSize = batchSize;
            return this;
        }

        /** Gives a table an explicit schema instead of the inferred one; the names are the keys. */
        public Builder schema(String table, Schema schema) {
            schemas.put(table, schema);
            return this;
        }

        /** Adds a file as a table; a name may not start with {@code #}. */
        public Builder file(String table, Path file) {
            if (table.startsWith(CALL_PREFIX)) {
                throw new IllegalArgumentException("A table name may not start with " + CALL_PREFIX + ": " + table);
            }
            files.put(table, file);
            return this;
        }

        /**
         * Adds every {@code .json}, {@code .jsonl} and {@code .ndjson} file of
         * a directory, also with {@code .gz}, named after the file without
         * these extensions.
         */
        public Builder directory(Path directory) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                    p -> Files.isRegularFile(p) && JSON_FILE.matcher(p.getFileName().toString()).matches())) {
                List<Path> sorted = new ArrayList<>();
                stream.forEach(sorted::add);
                sorted.sort(null);
                for (Path p : sorted) {
                    var m = JSON_FILE.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        file(m.group(1), p);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + directory, e);
            }
            return this;
        }

        /** Builds the connector, inferring the columns of every file without an explicit schema. */
        public JsonConnector build() {
            Map<String, Table> tables = new LinkedHashMap<>();
            for (Map.Entry<String, Path> e : files.entrySet()) {
                Schema schema = schemas.get(e.getKey());
                JsonLayout layout = schema != null ? JsonLayout.of(schema) : infer(e.getValue(), options);
                tables.put(e.getKey(), new Table(e.getValue(), options, layout));
            }
            return new JsonConnector(name, tables, options, batchSize);
        }
    }
}
