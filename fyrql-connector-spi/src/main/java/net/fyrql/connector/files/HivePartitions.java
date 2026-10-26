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

package net.fyrql.connector.files;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.core.expr.Evaluator;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.expr.Expressions;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.TypeNames;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The partitions of a table of files in the layout of Hive, as DuckDB
 * 1.5.6 reads them: the directories of the form {@code key=value} on the
 * path of a file give the file columns of constant values, such as
 * {@code year} and {@code month} for {@code sales/year=2024/month=3/a.parquet}.
 * Other directories do not count. {@code %xx} in a key or a value stands
 * for the byte {@code xx} of UTF-8, and the value
 * {@value #DEFAULT_PARTITION} for {@code NULL}.
 * <p>
 * The partition columns follow the columns of the files, ordered by their
 * keys; a column of the files with the name of a key, in any case, takes
 * the value of the partition instead and is not read. Their type is given
 * by {@code hive_types}, else, with {@code hive_types_autocast},
 * {@code DATE} or {@code BIGINT} if all values of all files are dates or
 * integers that read back as they are written, such as {@code 2024-03-01}
 * or {@code 42} but not {@code 007}, else {@code VARCHAR}.
 * <p>
 * A connector takes over the filters that read partition columns only and
 * evaluates them for each file, see {@link #filter} and {@link #match}, so
 * that it skips the files no row of which passes; if they fail on the
 * values of a file, the scan fails once it reads a row of the file.
 */
public final class HivePartitions {

    /** The value of a directory for {@code NULL}, as Hive and DuckDB write it. */
    public static final String DEFAULT_PARTITION = "__HIVE_DEFAULT_PARTITION__";

    /** The named arguments of the table functions of files that {@link #options} reads. */
    public static final List<String> ARGUMENTS = List.of("hive_partitioning", "hive_types_autocast", "hive_types");

    private static final List<DataType> AUTOCAST = List.of(DataType.DATE, DataType.BIGINT);

    private HivePartitions() {
    }

    /**
     * How a table finds its partitions.
     *
     * @param partitioning {@code true} if the files must have the same keys,
     *        {@code false} if directories are no partitions, {@code null} to
     *        take the partitions if all files have the same keys
     * @param autocast whether a column without a type in {@code types} takes
     *        the first of {@code BIGINT}, {@code DATE} and {@code TIMESTAMP}
     *        that all its values have, rather than {@code VARCHAR}
     * @param types the types of partition columns by their keys
     */
    public record Options(Boolean partitioning, boolean autocast, Map<String, DataType> types) {

        /** Partitions if all files have the same keys, with their types found from the values. */
        public static final Options AUTO = new Options(null, true, Map.of());

        public Options {
            types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        }

        /** Returns the options with other types of partition columns. */
        public Options withTypes(Map<String, DataType> other) {
            return new Options(partitioning, autocast, other);
        }
    }

    /**
     * The partition columns of a table, ordered by their keys: named by
     * them, nullable, of their types.
     */
    public record Layout(List<Schema.Field> columns) {

        /** No partitions. */
        public static final Layout NONE = new Layout(List.of());

        public Layout {
            columns = List.copyOf(columns);
        }

        /** Tells whether the table has no partitions. */
        public boolean isEmpty() {
            return columns.isEmpty();
        }

        /** Returns the keys, in order. */
        public List<String> keys() {
            return columns.stream().map(Schema.Field::name).toList();
        }

        @Override
        public String toString() {
            return columns.toString();
        }
    }

    /**
     * The partitions of the files of a table.
     *
     * @param layout the partition columns
     * @param values for each file, in the order of the files, the values of
     *        its partition columns as Java values of their types
     */
    public record Partitions(Layout layout, List<Object[]> values) {

        public Partitions {
            values = List.copyOf(values);
        }
    }

    /**
     * The columns of a table of files with partitions: those of the files,
     * in their order, then the partition columns whose keys name none of them.
     */
    public static final class Columns {
        private final Schema schema;
        private final int[] fileColumns;
        private final int[] partitions;

        private Columns(Schema schema, int[] fileColumns, int[] partitions) {
            this.schema = schema;
            this.fileColumns = fileColumns;
            this.partitions = partitions;
        }

        /** Returns the columns of the table. */
        public Schema schema() {
            return schema;
        }

        /** Returns the column of the files of a column of the table, or {@code -1} for a partition column. */
        public int fileColumn(int column) {
            return fileColumns[column];
        }

        /** Returns the index of the partition of a column of the table, or {@code -1} for a column of the files. */
        public int partition(int column) {
            return partitions[column];
        }

        /**
         * Returns the paths of the files for paths of the table, in the same
         * order, with {@code null} for a partition column.
         */
        public List<ColumnPath> filePaths(List<ColumnPath> columns) {
            List<ColumnPath> paths = new ArrayList<>(columns.size());
            for (ColumnPath p : columns) {
                int c = fileColumns[p.column()];
                paths.add(c < 0 ? null : new ColumnPath(c, p.fields()));
            }
            return paths;
        }
    }

    /**
     * How a scan reads the files and adds the values of the partitions.
     *
     * @param fileColumns the paths to read from the files
     * @param fileOutput the columns the files deliver for them
     * @param sources for each column of the output of the scan, its index
     *        in the file output, or {@code -1} for a partition column
     * @param constants for each column of the output, the value of its
     *        partition, or {@code null}
     */
    public record Projection(List<ColumnPath> fileColumns, Schema fileOutput, int[] sources, Object[] constants) {

        /** Tells whether the output has a partition column. */
        public boolean hasPartitions() {
            for (int s : sources) {
                if (s < 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** What the filters of the partition columns say about a file. */
    public enum Match {
        /** Every row of the file passes. */
        ALL,
        /** No row passes, the file need not be read. */
        NONE,
        /**
         * The filters fail on the values of the file, and so on each of its
         * rows; a scan that reads a row of it fails, see {@link #passes}.
         */
        FAILS
    }

    /**
     * Returns the partitions of files.
     *
     * @param locations the locations of the files
     * @throws IllegalArgumentException with a message for the user if
     *         partitions are required and the files differ in their keys, a
     *         file repeats a key, a type names no key, or a value does not
     *         have the type given for it
     */
    public static Partitions partitions(List<String> locations, Options options) {
        List<Object[]> none = Collections.nCopies(locations.size(), new Object[0]);
        if (Boolean.FALSE.equals(options.partitioning()) || locations.isEmpty()) {
            checkTypes(options, List.of());
            return new Partitions(Layout.NONE, none);
        }
        List<List<String[]>> segments = new ArrayList<>();
        for (String location : locations) {
            List<String[]> s = new ArrayList<>(segments(location));
            s.sort((a, b) -> a[0].toLowerCase(Locale.ROOT).compareTo(b[0].toLowerCase(Locale.ROOT)));
            segments.add(s);
        }
        List<String> keys = keys(segments.get(0));
        for (int f = 0; f < locations.size(); f++) {
            List<String> other = keys(segments.get(f));
            if (!lowerCase(other).equals(lowerCase(keys))) {
                if (Boolean.TRUE.equals(options.partitioning())) {
                    throw new IllegalArgumentException("The files have different partitions: " + locations.get(0)
                            + " has " + keys + ", " + locations.get(f) + " has " + other);
                }
                keys = List.of();
                break;
            }
        }
        checkTypes(options, keys);
        if (keys.isEmpty()) {
            return new Partitions(Layout.NONE, none);
        }
        // The columns are in the order of their keys as written, the segments in that of the keys in lower case.
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            order.add(k);
        }
        List<String> sortedKeys = keys;
        order.sort((a, b) -> sortedKeys.get(a).compareTo(sortedKeys.get(b)));
        List<Schema.Field> columns = new ArrayList<>();
        for (int k : order) {
            DataType type = type(options, keys.get(k));
            if (type == null) {
                type = autocast(segments, k, options.autocast());
            }
            columns.add(new Schema.Field(keys.get(k), type, true));
        }
        List<Object[]> values = new ArrayList<>();
        for (int f = 0; f < locations.size(); f++) {
            Object[] row = new Object[keys.size()];
            for (int c = 0; c < keys.size(); c++) {
                int k = order.get(c);
                String text = segments.get(f).get(k)[1];
                DataType type = columns.get(c).type();
                try {
                    row[c] = text == null ? null : Casts.parse(text, type);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("The partition " + keys.get(k) + "=" + text + " of "
                            + locations.get(f) + " is no " + type.sqlName(), e);
                }
            }
            values.add(row);
        }
        return new Partitions(new Layout(columns), values);
    }

    /**
     * Returns the keys and the values of the directories of the form
     * {@code key=value} on the path of a file, in order, decoded, with
     * {@code null} for {@value #DEFAULT_PARTITION}.
     *
     * @throws IllegalArgumentException if the path repeats a key
     */
    static List<String[]> segments(String location) {
        String[] parts = location.split("[/\\\\]");
        List<String[]> segments = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                String key = decode(parts[i].substring(0, eq));
                String value = decode(parts[i].substring(eq + 1));
                if (seen.contains(key.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("The directories of " + location + " repeat the partition "
                            + key);
                }
                seen.add(key.toLowerCase(Locale.ROOT));
                segments.add(new String[] {key, value.equals(DEFAULT_PARTITION) ? null : value});
            }
        }
        return segments;
    }

    /** Decodes {@code %xx} as a byte of UTF-8; a {@code %} without two hexadecimal digits stays. */
    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder decoded = new StringBuilder(s.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bytes.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
            } else {
                decoded.append(bytes.toString(StandardCharsets.UTF_8)).append(c);
                bytes.reset();
            }
        }
        return decoded.append(bytes.toString(StandardCharsets.UTF_8)).toString();
    }

    private static List<String> keys(List<String[]> segments) {
        return segments.stream().map(s -> s[0]).toList();
    }

    private static List<String> lowerCase(List<String> keys) {
        return keys.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
    }

    private static void checkTypes(Options options, List<String> keys) {
        List<String> lower = lowerCase(keys);
        for (String key : options.types().keySet()) {
            if (!lower.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("hive_types names " + key + ", which is no partition"
                        + (keys.isEmpty() ? " of the files" : "; the partitions are " + String.join(", ", keys)));
            }
        }
    }

    private static DataType type(Options options, String key) {
        for (Map.Entry<String, DataType> e : options.types().entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Returns the first type of {@link #AUTOCAST} that all values of a key
     * have and read back as they are written, else {@code VARCHAR}.
     */
    private static DataType autocast(List<List<String[]>> segments, int key, boolean autocast) {
        if (!autocast) {
            return DataType.VARCHAR;
        }
        boolean any = false;
        for (List<String[]> s : segments) {
            any |= s.get(key)[1] != null;
        }
        if (!any) {
            return DataType.VARCHAR;
        }
        candidates:
        for (DataType type : AUTOCAST) {
            for (List<String[]> s : segments) {
                String text = s.get(key)[1];
                if (text != null) {
                    try {
                        if (!text.equals(Casts.cast(Casts.parse(text, type), type, DataType.VARCHAR))) {
                            continue candidates;
                        }
                    } catch (RuntimeException e) {
                        continue candidates;
                    }
                }
            }
            return type;
        }
        return DataType.VARCHAR;
    }

    /**
     * Returns the columns of a table: those of its files, with the partition
     * columns.
     */
    public static Columns columns(Schema fileSchema, Layout layout) {
        List<Schema.Field> fields = new ArrayList<>();
        List<Integer> fileColumns = new ArrayList<>();
        List<Integer> partitions = new ArrayList<>();
        List<String> keys = lowerCase(layout.keys());
        boolean[] placed = new boolean[keys.size()];
        for (int c = 0; c < fileSchema.size(); c++) {
            Schema.Field f = fileSchema.fields().get(c);
            int k = keys.indexOf(f.name().toLowerCase(Locale.ROOT));
            if (k >= 0) {
                fields.add(new Schema.Field(f.name(), layout.columns().get(k).type(), true));
                fileColumns.add(-1);
                partitions.add(k);
                placed[k] = true;
            } else {
                fields.add(f);
                fileColumns.add(c);
                partitions.add(-1);
            }
        }
        for (int k = 0; k < keys.size(); k++) {
            if (!placed[k]) {
                fields.add(layout.columns().get(k));
                fileColumns.add(-1);
                partitions.add(k);
            }
        }
        return new Columns(new Schema(fields), fileColumns.stream().mapToInt(Integer::intValue).toArray(),
                partitions.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Returns which filters of a scan a connector takes over: those that
     * read partition columns only, at least one, and are deterministic.
     *
     * @param columns the columns the scan reads; the filters refer to positions in it
     */
    public static Predicate<Expression> filter(List<ColumnPath> columns, Columns table) {
        return f -> {
            BitSet read = Expressions.columns(f);
            if (read.isEmpty() || !Expressions.isDeterministic(f)) {
                return false;
            }
            for (int i = read.nextSetBit(0); i >= 0; i = read.nextSetBit(i + 1)) {
                ColumnPath p = columns.get(i);
                if (!p.isWhole() || table.partition(p.column()) < 0) {
                    return false;
                }
            }
            return true;
        };
    }

    /**
     * Evaluates the filters a connector took over, see {@link #filter}, on
     * the values of the partitions of a file; as they read nothing else,
     * the answer holds for each row of the file.
     *
     * @param columns the columns the scan reads; the filters refer to positions in it
     * @param values the values of the partitions of the file
     */
    public static Match match(List<Expression> filters, List<ColumnPath> columns, Columns table, Object[] values) {
        try {
            return passes(filters, columns, table, values) ? Match.ALL : Match.NONE;
        } catch (RuntimeException e) {
            return Match.FAILS;
        }
    }

    /**
     * Tells whether the rows of a file pass the filters a connector took
     * over, as {@link #match} does.
     *
     * @throws RuntimeException the error of the evaluation of the filters
     */
    public static boolean passes(List<Expression> filters, List<ColumnPath> columns, Columns table,
            Object[] values) {
        if (filters.isEmpty()) {
            return true;
        }
        List<Schema.Field> fields = new ArrayList<>();
        List<Vector> vectors = new ArrayList<>();
        for (ColumnPath p : columns) {
            Schema.Field field = p.field(table.schema());
            int k = p.isWhole() ? table.partition(p.column()) : -1;
            fields.add(field);
            vectors.add(k < 0 || values[k] == null ? Vectors.nulls(field.type(), 1)
                    : Evaluator.constant(field.type(), values[k], 1));
        }
        Batch row = new Batch(new Schema(fields), 1, vectors);
        return Evaluator.select(Expressions.conjunction(filters), row).length > 0;
    }

    /**
     * Returns how a scan reads a file: the paths of the files for the
     * columns of the files among those of the scan.
     *
     * @param columns the columns the scan reads
     * @param output the output of the scan, one column for each path
     * @param values the values of the partitions of the file
     */
    public static Projection project(List<ColumnPath> columns, Schema output, Columns table, Object[] values) {
        if (output.size() != columns.size()) {
            throw new IllegalArgumentException(output.size() + " output columns for " + columns.size() + " paths");
        }
        List<ColumnPath> fileColumns = new ArrayList<>();
        List<Schema.Field> fileFields = new ArrayList<>();
        int[] sources = new int[columns.size()];
        Object[] constants = new Object[columns.size()];
        List<ColumnPath> paths = table.filePaths(columns);
        for (int i = 0; i < columns.size(); i++) {
            ColumnPath p = paths.get(i);
            if (p == null) {
                sources[i] = -1;
                constants[i] = values[table.partition(columns.get(i).column())];
            } else {
                sources[i] = fileColumns.size();
                fileColumns.add(p);
                fileFields.add(output.fields().get(i));
            }
        }
        return new Projection(fileColumns, new Schema(fileFields), sources, constants);
    }

    /**
     * Returns a reader of the output of a scan over a reader of the columns
     * of a file: the columns of the file, with the values of its partitions.
     */
    public static BatchReader withPartitions(BatchReader file, Schema output, Projection projection) {
        return new BatchReader() {
            @Override
            public Batch next() {
                Batch b = file.next();
                if (b == null) {
                    return null;
                }
                int n = b.rowCount();
                List<Vector> vectors = new ArrayList<>(output.size());
                for (int i = 0; i < output.size(); i++) {
                    int s = projection.sources()[i];
                    Object value = projection.constants()[i];
                    DataType type = output.type(i);
                    vectors.add(s >= 0 ? b.column(s)
                            : value == null ? Vectors.nulls(type, n) : Evaluator.constant(type, value, n));
                }
                return new Batch(output, n, vectors);
            }

            @Override
            public void close() {
                file.close();
            }

            @Override
            public void cancel() {
                file.cancel();
            }
        };
    }

    /**
     * Returns the options of a call of a table function from its named
     * arguments {@link #ARGUMENTS}; the others it leaves alone.
     *
     * @throws IllegalArgumentException with a message for the user if an
     *         argument has a value of the wrong kind or names no type
     */
    public static Options options(String function, Map<String, ?> namedArguments, Options defaults) {
        Boolean partitioning = defaults.partitioning();
        boolean autocast = defaults.autocast();
        Map<String, DataType> types = defaults.types();
        Object value = namedArguments.get("hive_partitioning");
        if (value != null) {
            if (!(value instanceof Boolean b)) {
                throw new IllegalArgumentException("hive_partitioning of " + function + " takes true or false");
            }
            partitioning = b;
        }
        value = namedArguments.get("hive_types_autocast");
        if (value != null) {
            if (!(value instanceof Boolean b)) {
                throw new IllegalArgumentException("hive_types_autocast of " + function + " takes true or false");
            }
            autocast = b;
        }
        value = namedArguments.get("hive_types");
        if (value != null) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("hive_types of " + function + " takes the types of the"
                        + " partitions as text, such as {'year': 'INTEGER'}");
            }
            types = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getValue() instanceof String name)) {
                    throw new IllegalArgumentException("hive_types of " + function + " takes the types of the"
                            + " partitions as text, such as {'year': 'INTEGER'}, not " + e.getValue());
                }
                types.put(String.valueOf(e.getKey()), type(name));
            }
        }
        return new Options(partitioning, autocast, types);
    }

    /**
     * Returns the types of partition columns from a text such as
     * {@code year INTEGER, region VARCHAR}.
     *
     * @throws IllegalArgumentException with a message for the user if a
     *         part is not a name followed by a type
     */
    public static Map<String, DataType> types(String text) {
        Map<String, DataType> types = new LinkedHashMap<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ',';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String part = text.substring(start, i).strip();
                int space = part.indexOf(' ');
                if (space < 0) {
                    throw new IllegalArgumentException("Expected a name and a type, such as year INTEGER, not '"
                            + part + "'");
                }
                types.put(part.substring(0, space), type(part.substring(space + 1).strip()));
                start = i + 1;
            }
        }
        return types;
    }

    /**
     * Returns the type of a partition column by its name, such as
     * {@code INTEGER} or {@code DECIMAL(10,2)}.
     *
     * @throws IllegalArgumentException if it names no type or a nested one
     */
    public static DataType type(String name) {
        DataType type = TypeNames.parse(name);
        if (type.isNested()) {
            throw new IllegalArgumentException("A partition cannot be a " + type.sqlName());
        }
        return type;
    }
}
