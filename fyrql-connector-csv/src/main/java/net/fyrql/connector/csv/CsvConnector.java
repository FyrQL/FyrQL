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

package net.fyrql.connector.csv;

import de.siegmar.fastcsv.reader.AbstractBaseCsvCallbackHandler;
import de.siegmar.fastcsv.reader.CommentStrategy;
import de.siegmar.fastcsv.reader.CsvCallbackHandler;
import de.siegmar.fastcsv.reader.CsvIndex;
import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.reader.CsvRecordHandler;
import de.siegmar.fastcsv.reader.IndexedCsvReader;
import de.siegmar.fastcsv.reader.RecordType;
import net.fyrql.connector.BatchReader;
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
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CSV and TSV files as tables in the schema {@code main}: every
 * {@code .csv} or {@code .tsv} file of a directory, or files added one by
 * one. The schema comes from the header and a sample of rows, as DuckDB
 * sniffs it ({@code BOOLEAN}, {@code BIGINT}, {@code DOUBLE}, {@code DATE},
 * {@code TIMESTAMP}, otherwise {@code VARCHAR}), unless it is given. Unless
 * set, whether the first line is a header is decided as DuckDB decides it:
 * a file of one line has one; otherwise it has one if the lines after the
 * first are all text, or if a value of the first line does not fit the type
 * of its column. An empty field is {@code NULL}. A file that does not parse, e.g. with an
 * unterminated quote or a field that is not a number, is an error.
 * <p>
 * A file larger than the split size is read in parts of about that size,
 * which the engine reads on several threads, see {@link #splits}.
 * <p>
 * The connector also offers the table functions {@code read_csv} and
 * {@code read_csv_auto} for any file; built without files it serves only
 * them. See {@link #bind}.
 */
public final class CsvConnector implements Connector, TableFunctionProvider {

    /** The split size by default, see {@link Builder#splitSize}. */
    public static final long SPLIT_SIZE = 4L << 20;

    /** The types a column is tried against, in order; the first that fits every sampled value wins. */
    private static final List<DataType> SNIFF_ORDER = List.of(DataType.BOOLEAN, DataType.BIGINT, DataType.DOUBLE,
            DataType.DATE, DataType.TIMESTAMP);

    private static final List<String> TABLE_FUNCTIONS = List.of("read_csv", "read_csv_auto");

    /** The prefix of the names of the tables of table function calls, which no registered file gets. */
    private static final String CALL_PREFIX = "#";

    private final String name;
    private final Map<String, Table> tables;
    private final int batchSize;
    private final int sampleRows;
    private final Charset charset;
    private final long splitSize;
    private final Catalog catalog;
    /** The tables of table function calls by their names, and the names by the calls. */
    private final Map<String, Table> callTables = new ConcurrentHashMap<>();
    private final Map<Call, String> calls = new ConcurrentHashMap<>();
    private final AtomicInteger callCount = new AtomicInteger();
    /** The index of each file read in splits, in the version it was built for. */
    private final Map<IndexKey, Pages> indexes = new ConcurrentHashMap<>();

    /**
     * A file and how to read it.
     *
     * @param rows the number of rows, exact if the sample read the whole
     *        file, otherwise estimated from the size of the file and the
     *        sample; -1 if unknown
     */
    record Table(Path file, char delimiter, char quote, boolean header, String nullString, Charset charset,
            Schema schema, long rows) {
    }

    /** A call of a table function: the file in its current version and how to read it. */
    private record Call(Path file, FileTime modified, long size, char delimiter, char quote, Boolean header,
            String nullString) {
    }

    /** What the index of a file depends on besides the file. */
    private record IndexKey(Path file, char delimiter, char quote, Charset charset) {
    }

    /** The index of a file in a version, or {@code null} if its bytes cannot be indexed. */
    private record Pages(long size, FileTime modified, CsvIndex index) {
    }

    /** The pages of an index that a split reads, from {@code from} to before {@code to}. */
    private record PageRange(CsvIndex index, int from, int to) {
    }

    private CsvConnector(String name, Map<String, Table> tables, int batchSize, int sampleRows, Charset charset,
            long splitSize) {
        this.name = name;
        this.tables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        this.batchSize = batchSize;
        this.sampleRows = sampleRows;
        this.charset = charset;
        this.splitSize = splitSize;
        this.catalog = new Catalog() {
            @Override
            public List<String> schemas() {
                return List.of(TableRef.MAIN);
            }

            @Override
            public List<String> tables(String schema) {
                return schema.equals(TableRef.MAIN) ? List.copyOf(CsvConnector.this.tables.keySet()) : List.of();
            }

            @Override
            public Schema schema(TableRef table) {
                return table(table).schema();
            }

            @Override
            public OptionalLong rowCount(TableRef table) {
                long rows = table(table).rows();
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

    private Table table(TableRef ref) {
        Table t = null;
        if (ref.schema().equals(TableRef.MAIN)) {
            t = ref.table().startsWith(CALL_PREFIX) ? callTables.get(ref.table()) : tables.get(ref.table());
        }
        if (t == null) {
            throw new IllegalArgumentException("No table " + ref + " in the CSV source " + name);
        }
        return t;
    }

    @Override
    public List<String> tableFunctions() {
        return TABLE_FUNCTIONS;
    }

    /**
     * Binds {@code read_csv(path, ...)} and {@code read_csv_auto}, which are
     * the same, to a table of the file in the schema {@code main} under a
     * generated name that starts with {@code #} and does not appear among
     * the tables of the catalog. The named arguments are those of DuckDB:
     * {@code header} (a boolean, or a value that casts to one; detected by
     * default), {@code delim} or {@code sep} (one character, {@code '\t'}
     * for a tab; a comma, or a tab for {@code .tsv} files, by default),
     * {@code quote} (one character, {@code "} by default) and
     * {@code nullstr} (empty by default). The schema is sniffed as for
     * registered files. The same call on an unchanged file gives the same
     * table; a changed file is sniffed again.
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
        char delimiter = path.toLowerCase(Locale.ROOT).endsWith(".tsv") ? '\t' : ',';
        char quote = '"';
        Boolean header = null;
        String nullString = "";
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            Object value = e.getValue();
            switch (e.getKey()) {
                case "header" -> header = bool(function, e.getKey(), value);
                case "delim", "sep" -> delimiter = character(function, e.getKey(), value);
                case "quote" -> quote = character(function, e.getKey(), value);
                case "nullstr" -> nullString = string(function, e.getKey(), value);
                default -> throw new IllegalArgumentException("Invalid named parameter \"" + e.getKey()
                        + "\" for function " + function + "; supported are header, delim, sep, quote, nullstr");
            }
        }
        Call call;
        try {
            call = new Call(file, Files.getLastModifiedTime(file), Files.size(file), delimiter, quote, header,
                    nullString);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + path + ": " + e.getMessage(), e);
        }
        String table = calls.computeIfAbsent(call, c -> {
            Table t = new Table(c.file(), c.delimiter(), c.quote(), false, c.nullString(), charset, null, -1);
            String generated = CALL_PREFIX + callCount.incrementAndGet();
            callTables.put(generated, sniff(t, c.header(), sampleRows));
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

    /** Reads a one-character argument; {@code \t} written out stands for a tab, as in DuckDB. */
    private static char character(String function, String key, Object value) {
        String s = string(function, key, value);
        if (s.equals("\\t")) {
            return '\t';
        }
        if (s.length() != 1) {
            throw new IllegalArgumentException("The argument " + key + " of " + function + " must be one character");
        }
        return s.charAt(0);
    }

    @Override
    public ScanPlan plan(TableRef table, ScanRequest request) {
        return ScanPlan.columnsOnly(table, table(table).schema(), request, null);
    }

    /**
     * Returns one split for a file of at most the split size, and otherwise
     * a split for each part of about the split size. The parts are cut
     * between the records that FastCSV's {@link IndexedCsvReader} finds in
     * the bytes of the file, quoted line breaks included, and grouped from
     * its pages of one batch each; the index is built once for each
     * version of a file. A file in a charset whose characters the index
     * cannot find by their bytes, which is any but UTF-8 and the charsets
     * of one byte per character that keep ASCII as it is, is one split.
     */
    @Override
    public List<Split> splits(ScanPlan plan) {
        CsvIndex index = splitSize > 0 ? index(table(plan.table())) : null;
        if (index == null) {
            return List.of(new Split(plan, 0, null));
        }
        List<CsvIndex.CsvPage> pages = index.pages();
        List<Split> splits = new ArrayList<>();
        int from = 0;
        for (int p = 1; p <= pages.size(); p++) {
            if (p == pages.size() || pages.get(p).offset() - pages.get(from).offset() >= splitSize) {
                splits.add(new Split(plan, splits.size(), new PageRange(index, from, p)));
                from = p;
            }
        }
        return splits.size() > 1 ? splits : List.of(new Split(plan, 0, null));
    }

    /** Returns the index of a file larger than the split size, or {@code null} for one split. */
    private CsvIndex index(Table table) {
        long size;
        FileTime modified;
        try {
            size = Files.size(table.file());
            modified = Files.getLastModifiedTime(table.file());
        } catch (IOException e) {
            // Opening the split reports it.
            return null;
        }
        if (size <= splitSize) {
            return null;
        }
        IndexKey key = new IndexKey(table.file(), table.delimiter(), table.quote(), table.charset());
        Pages known = indexes.get(key);
        if (known == null || known.size() != size || !known.modified().equals(modified)) {
            CsvIndex index;
            try (IndexedCsvReader<CsvRecord> reader = indexed(table, CsvRecordHandler.of(), null)) {
                index = reader.getIndex();
            } catch (IllegalArgumentException e) {
                // A charset or a delimiter that the index cannot find by its bytes.
                index = null;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + table.file(), e);
            }
            known = new Pages(size, modified, index);
            indexes.put(key, known);
        }
        return known.index();
    }

    /** Opens an indexed reader of a file with pages of one batch, over a built index or building one. */
    private <T> IndexedCsvReader<T> indexed(Table table, CsvCallbackHandler<T> handler, CsvIndex index)
            throws IOException {
        IndexedCsvReader.IndexedCsvReaderBuilder b = IndexedCsvReader.builder()
                .fieldSeparator(table.delimiter())
                .quoteCharacter(table.quote())
                .commentStrategy(CommentStrategy.NONE)
                .pageSize(batchSize);
        if (index != null) {
            b.index(index);
        }
        return b.build(handler, table.file(), table.charset());
    }

    /**
     * Reads the requested columns in batches. The fields go from the buffer
     * of the CSV reader straight into the vectors, see {@link FieldParser};
     * the values and the errors are those of {@link #parse}. A part of a
     * file gives the values and the errors, with their line numbers, that
     * reading the whole file gives for its records.
     */
    @Override
    public BatchReader open(Split split) {
        Table table = table(split.plan().table());
        RecordReader handler = new RecordReader(table, split.plan().wholeColumns(), split.plan().output(), batchSize);
        if (split.state() instanceof PageRange range) {
            return pages(table, handler, range);
        }
        CsvReader<Boolean> reader = reader(table, handler);
        Iterator<Boolean> records = reader.iterator();
        return new BatchReader() {
            @Override
            public Batch next() {
                // Each record is read into the vectors as it is fetched, so no record is fetched ahead.
                int n = 0;
                try {
                    while (n < batchSize && records.hasNext()) {
                        records.next();
                        n++;
                    }
                } catch (CsvParseException e) {
                    throw unwrap(e);
                }
                return n == 0 ? null : handler.batch(n);
            }

            @Override
            public void close() {
                try {
                    reader.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** Reads the pages of a part of a file, a batch from each; the header is in the first page only. */
    private BatchReader pages(Table table, RecordReader handler, PageRange range) {
        handler.header = table.header() && range.from() == 0;
        IndexedCsvReader<Boolean> reader;
        try {
            reader = indexed(table, handler, range.index());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + table.file(), e);
        }
        return new BatchReader() {
            private int page = range.from();

            @Override
            public Batch next() {
                while (page < range.to()) {
                    int n;
                    try {
                        n = reader.readPage(page++).size();
                    } catch (CsvParseException e) {
                        throw unwrap(e);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e.getMessage(), e);
                    }
                    if (n > 0) {
                        return handler.batch(n);
                    }
                }
                return null;
            }

            @Override
            public void close() {
                try {
                    reader.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    /** Returns the error of a record that the CSV reader wrapped, or else the error of the reader. */
    private static RuntimeException unwrap(CsvParseException e) {
        return e.getCause() instanceof ConversionException c ? c : e;
    }

    /**
     * Reads each record of a file into the vectors of the requested columns.
     * It skips the header, if there is one. A record with the wrong number
     * of fields, or else the first requested column whose field does not
     * parse, is an error, which the CSV reader wraps, see {@link #unwrap}.
     */
    private static final class RecordReader extends AbstractBaseCsvCallbackHandler<Boolean> {
        private final Table table;
        private final Schema output;
        private final FieldParser[] parsers;
        /** The positions in the output of each field of a record. */
        private final int[][] targets;
        boolean header;
        private int errorColumn;
        private RuntimeException error;

        RecordReader(Table table, List<Integer> columns, Schema output, int batchSize) {
            this.table = table;
            this.output = output;
            this.header = table.header();
            parsers = new FieldParser[columns.size()];
            List<List<Integer>> positions = new ArrayList<>();
            for (int f = 0; f < table.schema().size(); f++) {
                positions.add(new ArrayList<>());
            }
            for (int c = 0; c < columns.size(); c++) {
                parsers[c] = FieldParser.of(output.type(c), table.nullString(), batchSize);
                positions.get(columns.get(c)).add(c);
            }
            targets = new int[positions.size()][];
            for (int f = 0; f < targets.length; f++) {
                targets[f] = positions.get(f).stream().mapToInt(Integer::intValue).toArray();
            }
        }

        @Override
        protected void handleBegin(long startingLineNumber) {
            errorColumn = Integer.MAX_VALUE;
            error = null;
        }

        @Override
        protected void handleField(int index, char[] buf, int offset, int length, boolean quoted) {
            if (header || index >= targets.length) {
                return;
            }
            for (int c : targets[index]) {
                try {
                    parsers[c].parse(buf, offset, length);
                } catch (RuntimeException e) {
                    if (c < errorColumn) {
                        errorColumn = c;
                        error = e;
                    }
                }
            }
        }

        @Override
        protected Boolean buildRecord() {
            if (getRecordType() != RecordType.DATA) {
                return null;
            }
            if (header) {
                header = false;
                return null;
            }
            if (getFieldCount() != table.schema().size()) {
                throw new ConversionException("Line " + getStartingLineNumber() + " of " + table.file() + " has "
                        + getFieldCount() + " fields, the header " + table.schema().size());
            } else if (error != null) {
                throw new ConversionException("Line " + getStartingLineNumber() + " of " + table.file()
                        + ", column " + output.name(errorColumn) + ": " + error.getMessage(), error);
            }
            return Boolean.TRUE;
        }

        /** Returns the batch of the records read since the last one. */
        Batch batch(int rows) {
            List<Vector> vectors = new ArrayList<>(parsers.length);
            for (FieldParser p : parsers) {
                vectors.add(p.finish());
            }
            return new Batch(output, rows, vectors);
        }
    }

    private static CsvReader<CsvRecord> reader(Table table) {
        return reader(table, CsvRecordHandler.of());
    }

    @SuppressWarnings("removal")
    private static <T> CsvReader<T> reader(Table table, CsvCallbackHandler<T> handler) {
        try {
            return CsvReader.builder()
                    .fieldSeparator(table.delimiter())
                    .quoteCharacter(table.quote())
                    .skipEmptyLines(true)
                    .allowExtraFields(true)
                    .allowMissingFields(true)
                    .detectBomHeader(true)
                    .commentStrategy(CommentStrategy.NONE)
                    .build(handler, table.file(), table.charset());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + table.file(), e);
        }
    }

    /**
     * Parses a field as a string: the null string gives {@code NULL}, and a
     * value is read as the column type. A scan reads the fields with
     * {@link FieldParser}, which gives the same values and errors.
     */
    static Object parse(String field, DataType type, String nullString) {
        if (field.equals(nullString)) {
            return null;
        }
        return type == DataType.VARCHAR ? field : Casts.parse(field, type);
    }

    /**
     * Sniffs the schema of a file from a sample of rows and, if
     * {@code header} is {@code null}, whether the first line is a header, as
     * DuckDB 1.5.6 decides it: a file of one line has a header; otherwise
     * the types are sniffed from the lines after the first, and the first
     * line is a header if all of them are {@code VARCHAR}, or if one of its
     * values does not fit the type of its column. Without a header the
     * columns are {@code column0}, {@code column1} and so on, the numbers
     * padded to the same width; an empty name in a header is named so as
     * well, and a repeated name gets {@code _1}, {@code _2} and so on. The
     * number of rows is exact if the sample reached the end of the file,
     * otherwise it is estimated from the size of the file and the mean
     * length of the sampled rows.
     *
     * @return the table with its header decided, its schema and its rows
     */
    static Table sniff(Table table, Boolean header, int sampleRows) {
        List<List<String>> rows = new ArrayList<>();
        try (CsvReader<CsvRecord> reader = reader(table)) {
            for (CsvRecord record : reader) {
                rows.add(record.getFields());
                if (rows.size() > sampleRows) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + table.file(), e);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Empty file " + table.file());
        }
        List<String> first = rows.get(0);
        int width = first.size();
        boolean hasHeader;
        if (header != null) {
            hasHeader = header;
        } else if (rows.size() == 1) {
            hasHeader = true;
        } else {
            List<DataType> rest = types(rows.subList(1, rows.size()), width, table.nullString());
            hasHeader = rest.stream().allMatch(t -> t == DataType.VARCHAR);
            for (int c = 0; c < width && !hasHeader; c++) {
                String value = first.get(c);
                hasHeader = rest.get(c) != DataType.VARCHAR && !value.equals(table.nullString())
                        && !fits(rest.get(c), value);
            }
        }
        List<DataType> types = types(hasHeader ? rows.subList(1, rows.size()) : rows, width, table.nullString());
        List<Schema.Field> fields = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int c = 0; c < width; c++) {
            String name = hasHeader && !first.get(c).isEmpty() ? first.get(c) : positionalName(c, width);
            String unique = name;
            for (int k = 1; !used.add(unique.toLowerCase(Locale.ROOT)); k++) {
                unique = name + "_" + k;
            }
            fields.add(new Schema.Field(unique, types.get(c)));
        }
        return new Table(table.file(), table.delimiter(), table.quote(), hasHeader, table.nullString(),
                table.charset(), new Schema(fields), rowCount(table.file(), rows, hasHeader, sampleRows));
    }

    /** The data rows of the sample, or an estimate from the file size if the sample is not the whole file. */
    private static long rowCount(Path file, List<List<String>> rows, boolean hasHeader, int sampleRows) {
        long dataRows = rows.size() - (hasHeader ? 1 : 0);
        if (rows.size() <= sampleRows) {
            return dataRows;
        }
        long headerChars = hasHeader ? chars(rows.get(0)) : 0;
        long sampleChars = -headerChars;
        for (List<String> row : rows) {
            sampleChars += chars(row);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            return -1;
        }
        if (sampleChars <= 0 || dataRows <= 0) {
            return dataRows;
        }
        return Math.max(dataRows, Math.round((size - headerChars) / ((double) sampleChars / dataRows)));
    }

    /** The characters of a row as written: its fields, the separators between them and the line end. */
    private static long chars(List<String> row) {
        long n = row.size();
        for (String field : row) {
            n += field.length();
        }
        return n;
    }

    /** Returns {@code column} and the index, padded with zeros to the digits of the last index. */
    private static String positionalName(int column, int width) {
        String digits = String.valueOf(column);
        int pad = String.valueOf(width - 1).length() - digits.length();
        return "column" + "0".repeat(Math.max(0, pad)) + digits;
    }

    /** Returns the type of each column: the first of {@link #SNIFF_ORDER} that fits every value, else VARCHAR. */
    private static List<DataType> types(List<List<String>> sample, int width, String nullString) {
        List<DataType> types = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            DataType type = DataType.VARCHAR;
            for (DataType candidate : SNIFF_ORDER) {
                if (fits(candidate, c, sample, nullString)) {
                    type = candidate;
                    break;
                }
            }
            types.add(type);
        }
        return types;
    }

    private static boolean fits(DataType type, int column, List<List<String>> sample, String nullString) {
        boolean anyValue = false;
        for (List<String> row : sample) {
            if (column >= row.size()) {
                continue;
            }
            String field = row.get(column);
            if (field.equals(nullString)) {
                continue;
            }
            anyValue = true;
            if (!fits(type, field)) {
                return false;
            }
        }
        return anyValue;
    }

    private static boolean fits(DataType type, String field) {
        // The sniffer is stricter than a cast, which also reads '1.5' as an integer or ignores a time after a date.
        java.util.regex.Pattern shape = SNIFF_SHAPES.get(type);
        if (shape != null && !shape.matcher(field).matches()) {
            return false;
        }
        try {
            Casts.parse(field, type);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final Map<DataType, java.util.regex.Pattern> SNIFF_SHAPES = Map.of(
            DataType.BOOLEAN, java.util.regex.Pattern.compile("(?i)true|false"),
            DataType.BIGINT, java.util.regex.Pattern.compile("[+-]?\\d+"),
            DataType.DOUBLE, java.util.regex.Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?"),
            DataType.DATE, java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}"),
            DataType.TIMESTAMP, java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?"));

    /** Collects the files of a CSV connector. */
    public static final class Builder {
        private final String name;
        private final Map<String, Table> tables = new LinkedHashMap<>();
        private final Map<String, Schema> schemas = new LinkedHashMap<>();
        private final Map<String, Boolean> headers = new LinkedHashMap<>();
        private Character delimiter;
        private char quote = '"';
        private Boolean header;
        private String nullString = "";
        private Charset charset = StandardCharsets.UTF_8;
        private int sampleRows = 1000;
        private int batchSize = 2048;
        private long splitSize = SPLIT_SIZE;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Sets the field delimiter; by default a comma, or a tab for {@code .tsv} files. */
        public Builder delimiter(char delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        public Builder quote(char quote) {
            this.quote = quote;
            return this;
        }

        /** Tells whether the first line holds the column names; detected as DuckDB does by default. */
        public Builder header(boolean header) {
            this.header = header;
            return this;
        }

        /** Sets the field value that stands for {@code NULL}; empty by default. */
        public Builder nullString(String nullString) {
            this.nullString = Objects.requireNonNull(nullString);
            return this;
        }

        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset);
            return this;
        }

        /** Sets the number of rows the schema is sniffed from; 1000 by default. */
        public Builder sampleRows(int sampleRows) {
            this.sampleRows = sampleRows;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the size in bytes of the parts that a larger file is read in,
         * on several threads; 0 reads every file in one part. 4 MiB by
         * default, see {@link CsvConnector#splits}.
         */
        public Builder splitSize(long splitSize) {
            if (splitSize < 0) {
                throw new IllegalArgumentException("The split size must not be negative: " + splitSize);
            }
            this.splitSize = splitSize;
            return this;
        }

        /** Gives a table an explicit schema instead of the sniffed one; the names replace the header. */
        public Builder schema(String table, Schema schema) {
            schemas.put(table, schema);
            return this;
        }

        /** Adds a file as a table; a name may not start with {@code #}. */
        public Builder file(String table, Path file) {
            if (table.startsWith(CALL_PREFIX)) {
                throw new IllegalArgumentException("A table name may not start with " + CALL_PREFIX + ": " + table);
            }
            char d = delimiter != null ? delimiter : file.toString().toLowerCase(Locale.ROOT).endsWith(".tsv") ? '\t'
                    : ',';
            tables.put(table, new Table(file, d, quote, false, nullString, charset, null, -1));
            headers.put(table, header);
            return this;
        }

        /** Adds every {@code .csv} and {@code .tsv} file of a directory, named after the file. */
        public Builder directory(Path directory) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory,
                    p -> Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(csv|tsv)"))) {
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

        /** Builds the connector, sniffing the schema of every file without an explicit one. */
        public CsvConnector build() {
            Map<String, Table> withSchemas = new LinkedHashMap<>();
            for (Map.Entry<String, Table> e : tables.entrySet()) {
                Table t = sniff(e.getValue(), headers.get(e.getKey()), sampleRows);
                Schema schema = schemas.getOrDefault(e.getKey(), t.schema());
                withSchemas.put(e.getKey(), new Table(t.file(), t.delimiter(), t.quote(), t.header(), t.nullString(),
                        t.charset(), schema, t.rows()));
            }
            return new CsvConnector(name, withSchemas, batchSize, sampleRows, charset, splitSize);
        }
    }
}
