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

package net.fyrql.connector.json;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Capabilities;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.FileWriterProvider;
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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * need; see {@link JsonRecordReader} for how values are read. A file of
 * records larger than the split size is read in parts of about that size,
 * which the engine reads on several threads, see {@link #splits}.
 * <p>
 * The connector also offers the table functions {@code read_json},
 * {@code read_json_auto}, {@code read_ndjson} and {@code read_ndjson_auto}
 * for any file; built without files it serves only them. See
 * {@link #bind}. It writes JSON files for {@code COPY ... TO}, see
 * {@link #openFile}.
 */
public final class JsonConnector implements Connector, TableFunctionProvider, FileWriterProvider {

    /** The split size by default, see {@link Builder#splitSize}. */
    public static final long SPLIT_SIZE = 4L << 20;

    /** How far at least the start of a part is looked for, see {@link #splits}. */
    private static final long SEARCH = 1L << 20;

    private static final List<String> TABLE_FUNCTIONS = List.of("read_json", "read_json_auto", "read_ndjson",
            "read_ndjson_auto");

    private static final List<String> FILE_FORMATS = List.of("json");

    /** The files a directory adds, and the extensions their table names drop. */
    private static final Pattern JSON_FILE = Pattern.compile("(?i)(.*)\\.(json|jsonl|ndjson)(\\.gz)?");

    /** The prefix of the names of the tables of table function calls, which no registered file gets. */
    private static final String CALL_PREFIX = "#";

    private final String name;
    private final Map<String, Table> tables;
    private final JsonOptions options;
    private final int batchSize;
    private final long splitSize;
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

    /** The bytes of a file that a split reads, from {@code from} to before {@code to}. */
    private record Range(long from, long to) {
    }

    private JsonConnector(String name, Map<String, Table> tables, JsonOptions options, int batchSize,
            long splitSize) {
        this.name = name;
        this.tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.options = options;
        this.batchSize = batchSize;
        this.splitSize = splitSize;
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

    @Override
    public List<String> fileFormats() {
        return FILE_FORMATS;
    }

    /**
     * Opens a sink that writes a JSON file as DuckDB's {@code COPY ... TO}
     * writes one: UTF-8, an object for each row without whitespace, with
     * the columns as keys in their order. Numbers and booleans are written
     * as they are, a {@code FLOAT} or a {@code DOUBLE} in the shortest form
     * that reads back as the same value, {@code NaN} and the infinities as
     * the tokens {@code NaN}, {@code Infinity} and {@code -Infinity}, which
     * this connector reads too; a {@code STRUCT} or a {@code MAP} is an
     * object, with the keys of a map cast to {@code VARCHAR}, a
     * {@code LIST} an array, and any other value the string of its cast to
     * {@code VARCHAR}, such as {@code "2024-02-29"} or
     * {@code "\x00\xFF"} for a {@code BLOB}. The options, with
     * DuckDB's names:
     * <ul>
     * <li>{@code array}: {@code false}, the default, for an object on each
     * line (JSON Lines), {@code true} for an array of the objects;</li>
     * <li>{@code dateformat} and {@code timestampformat}: formats of
     * {@code strftime} for the columns of type {@code DATE} and
     * {@code TIMESTAMP};</li>
     * <li>{@code compression}: {@code gzip}, {@code none} or {@code auto},
     * the default, which compresses a file whose name ends in
     * {@code .gz}.</li>
     * </ul>
     */
    @Override
    public Sink openFile(String format, Path file, Schema schema, Map<String, String> options) {
        if (!FILE_FORMATS.contains(format)) {
            throw new IllegalArgumentException("The JSON source " + name + " writes no files of the format "
                    + format);
        }
        return JsonFileWriter.open(file, schema, options);
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

    /**
     * Returns one split for a file of at most the split size, and otherwise
     * a split for each part of about the split size if the file is a
     * sequence of records in UTF-8, uncompressed. A part starts after a line
     * break whose line ends with a closing brace or bracket and after which
     * the next character other than white space is an opening one. In JSON
     * that parses, no line break is inside a string, and a value follows
     * another one with nothing but white space between them only at the top
     * level, so a part starts where a record does. A file of one array of
     * records, a compressed file and a file in UTF-16 or UTF-32 are one
     * split; where no such line break follows within the split size or a
     * mebibyte, whichever is more, the rest of a file is one split.
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        List<Long> starts = splitSize > 0 ? starts(table(plan.table())) : List.of(0L);
        if (starts.size() < 2) {
            return List.of(new Split(plan, 0, null));
        }
        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            long to = i + 1 < starts.size() ? starts.get(i + 1) : Long.MAX_VALUE;
            splits.add(new Split(plan, i, new Range(starts.get(i), to)));
        }
        return splits;
    }

    /** Returns the offsets at which the parts of a file start, see {@link #splits}. */
    private List<Long> starts(Table table) {
        String fileName = table.file().getFileName().toString().toLowerCase(Locale.ROOT);
        if (table.options().format() == JsonFormat.ARRAY || fileName.endsWith(".gz") || fileName.endsWith(".zst")) {
            return List.of(0L);
        }
        List<Long> starts = new ArrayList<>(List.of(0L));
        try (FileChannel channel = FileChannel.open(table.file(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size <= splitSize || !sequence(channel, table.options().format())) {
                return starts;
            }
            for (long at = splitSize; at < size; ) {
                long start = boundary(channel, at, at + Math.max(splitSize, SEARCH));
                if (start < 0 || start >= size) {
                    break;
                }
                starts.add(start);
                at = start + splitSize;
            }
        } catch (IOException e) {
            // Opening the split reports it.
            return List.of(0L);
        }
        return starts;
    }

    /**
     * Tells whether a file is in UTF-8, as far as its first bytes tell, and
     * holds a sequence of records rather than one array of them.
     */
    private static boolean sequence(FileChannel channel, JsonFormat format) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(1 << 16);
        while (head.hasRemaining() && channel.read(head, head.position()) > 0) {
            // Reads up to the size of the buffer.
        }
        int n = head.position();
        int i = n >= 3 && head.get(0) == (byte) 0xEF && head.get(1) == (byte) 0xBB && head.get(2) == (byte) 0xBF
                ? 3 : 0;
        for (int k = 0; k < Math.min(n, 4); k++) {
            // UTF-16 and UTF-32 have zeros in their first four bytes, or a byte order mark of 0xFE and 0xFF.
            if (head.get(k) == 0 || head.get(k) == (byte) 0xFE || head.get(k) == (byte) 0xFF) {
                return false;
            }
        }
        while (i < n && whiteSpace(head.get(i))) {
            i++;
        }
        return i < n && (format == JsonFormat.NEWLINE_DELIMITED || head.get(i) != '[');
    }

    /**
     * Returns the offset after the first line break from {@code from} on
     * that starts a part, see {@link #splits}, or -1 if there is none before
     * {@code to} or the end of the file.
     */
    private static long boundary(FileChannel channel, long from, long to) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        // The last byte other than white space, and the offset after a line break that may start a part.
        byte last = 0;
        long candidate = -1;
        for (long position = from; ; ) {
            buffer.clear();
            int n = channel.read(buffer, position);
            if (n <= 0) {
                return -1;
            }
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                long at = position + i;
                if (b == '\n') {
                    if (candidate < 0) {
                        if (at >= to) {
                            return -1;
                        }
                        if (last == '}' || last == ']') {
                            candidate = at + 1;
                        }
                    }
                } else if (!whiteSpace(b)) {
                    if (candidate >= 0 && (b == '{' || b == '[')) {
                        return candidate;
                    }
                    candidate = -1;
                    last = b;
                    if (at >= to) {
                        return -1;
                    }
                }
            }
            position += n;
        }
    }

    private static boolean whiteSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    /**
     * Reads the requested columns in batches. A part of a file gives the
     * values and the errors, with their lines and records counted from the
     * start of the file, that reading the whole file gives for its records.
     */
    @Override
    public BatchReader open(Split split) {
        Table table = table(split.plan().table());
        if (split.state() instanceof Range range) {
            InputStream in;
            try {
                in = new RangeInputStream(table.file(), range.from(), range.to());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot open " + table.file(), e);
            }
            return JsonRecordReader.open(in, table.layout(), split.plan().columns(),
                    table.options().withFormat(JsonFormat.NEWLINE_DELIMITED), batchSize, table.file().toString(),
                    range.from() == 0 ? JsonInput.Origin.START : new Before(table.file(), range.from()));
        }
        return JsonRecordReader.open(open(table.file()), table.layout(), split.plan().columns(),
                table.options(), batchSize, table.file().toString());
    }

    /** The bytes of a file from one offset to before another one, or to its end. */
    private static final class RangeInputStream extends InputStream {
        private final FileChannel channel;
        private final long end;
        private long position;

        RangeInputStream(Path file, long from, long to) throws IOException {
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
            this.position = from;
            this.end = to;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position >= end) {
                return -1;
            }
            int n = channel.read(ByteBuffer.wrap(b, off, (int) Math.min(len, end - position)), position);
            if (n < 0) {
                return -1;
            }
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /**
     * The lines and the records of a file before an offset at which a
     * record starts, counted when a message first asks for them: each line
     * break is one, as the parser counts them, and so is each value at the
     * top level.
     */
    private static final class Before implements JsonInput.Origin {
        private final Path file;
        private final long offset;
        private long lines = -1;
        private long records;

        Before(Path file, long offset) {
            this.file = file;
            this.offset = offset;
        }

        @Override
        public long lines() {
            count();
            return lines;
        }

        @Override
        public long records() {
            count();
            return records;
        }

        private void count() {
            if (lines >= 0) {
                return;
            }
            long breaks = 0;
            try (InputStream in = new RangeInputStream(file, 0, offset)) {
                byte[] buffer = new byte[1 << 16];
                boolean afterReturn = false;
                for (int n; (n = in.read(buffer, 0, buffer.length)) > 0; ) {
                    for (int i = 0; i < n; i++) {
                        byte b = buffer[i];
                        if (b == '\r' || b == '\n' && !afterReturn) {
                            breaks++;
                        }
                        afterReturn = b == '\r';
                    }
                }
                try (JsonInput input = new JsonInput(new RangeInputStream(file, 0, offset),
                        JsonFormat.NEWLINE_DELIMITED, "", file.toString())) {
                    while (input.next()) {
                        input.parser().skipChildren();
                    }
                    records = input.record();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + file, e);
            }
            lines = breaks;
        }
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
        private long splitSize = SPLIT_SIZE;

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

        /**
         * Sets the size in bytes of the parts that a larger file of records
         * is read in, on several threads; 0 reads every file in one part.
         * 4 MiB by default, see {@link JsonConnector#splits}.
         */
        public Builder splitSize(long splitSize) {
            if (splitSize < 0) {
                throw new IllegalArgumentException("The split size must not be negative: " + splitSize);
            }
            this.splitSize = splitSize;
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
            return new JsonConnector(name, tables, options, batchSize, splitSize);
        }
    }
}
