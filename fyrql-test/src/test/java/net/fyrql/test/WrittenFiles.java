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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DuckDB reads a file that {@code COPY ... TO} wrote: a CSV or a JSON file
 * with the column types given, since neither format keeps them, and a
 * Parquet file as it is; each column is cast to its type in FyrQL. An Excel
 * workbook keeps few types, so that FyrQL and DuckDB both read it with
 * {@code read_xlsx}, see {@link #xlsx}.
 */
public final class WrittenFiles {

    /**
     * The rows that DuckDB is expected to read and the rows it reads, both
     * in the types of {@code schema}.
     */
    public record ReadBack(Schema schema, List<Object[]> expected, List<Object[]> actual) {

        /** Compares the rows; the report of the outcome tells what differs. */
        public ResultComparison.Outcome compare(boolean ordered) {
            return ResultComparison.compare(schema, expected, actual, ordered);
        }

        /**
         * Compares the rows, doubles within a relative tolerance.
         *
         * @param relative the tolerance, see {@link ResultComparison#tolerance}
         */
        public ResultComparison.Outcome compare(boolean ordered, double relative) {
            return ResultComparison.compare(schema, expected, actual, ordered, 0, relative);
        }
    }

    private WrittenFiles() {
    }

    /** Returns the path of a file as a string literal of SQL takes it. */
    public static String path(Path file) {
        return file.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    /**
     * DuckDB reads a file that FyrQL wrote from the rows of a query.
     *
     * @param format {@code csv}, {@code json} or {@code parquet}
     * @param schema the columns of the query
     * @param rows the rows of the query
     */
    public static ReadBack duckDb(DuckDbReference duck, Path file, String format, Schema schema, List<Object[]> rows) {
        boolean json = format.equals("json");
        List<Schema.Field> duckFields = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            duckFields.add(json ? new Schema.Field(f.name(), jsonType(f.type()), f.nullable()) : f);
        }
        Schema duckSchema = new Schema(duckFields);
        List<Object[]> expected = new ArrayList<>();
        for (Object[] row : rows) {
            Object[] d = row.clone();
            for (int c = 0; json && c < d.length; c++) {
                d[c] = jsonValue(d[c], schema.type(c));
            }
            expected.add(d);
        }
        List<String> casts = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        for (Schema.Field f : duckSchema.fields()) {
            String name = f.name().replace("\"", "\"\"");
            casts.add("CAST(\"" + name + "\" AS " + f.type().sqlName() + ")");
            columns.add("'" + f.name().replace("'", "''") + "': '" + f.type().sqlName().replace("'", "''") + "'");
        }
        String types = "{" + String.join(", ", columns) + "}";
        String reader = switch (format) {
            // The sniffer of DuckDB may take another character for the quote, as for values such as ' and \.
            case "csv" -> "read_csv('" + path(file) + "', delim = ',', quote = '\"', escape = '\"', header = true,"
                    + " allow_quoted_nulls = false, columns = " + types + ")";
            case "json" -> "read_json('" + path(file) + "', columns = " + types + ")";
            case "parquet" -> "read_parquet('" + path(file) + "')";
            default -> throw new IllegalArgumentException("No format " + format);
        };
        return new ReadBack(duckSchema, expected, duck.query("SELECT " + String.join(", ", casts) + " FROM "
                + reader, duckSchema));
    }

    /**
     * The type in which DuckDB reads a value from JSON: a number with a
     * fraction is a double, which a {@code DECIMAL} of more than 15 digits
     * loses, also inside a struct, a list or a map.
     */
    private static DataType jsonType(DataType type) {
        return switch (type) {
            case DataType.Decimal d when d.precision() > 15 -> DataType.DOUBLE;
            case DataType.Struct s -> new DataType.Struct(s.fields().stream()
                    .map(f -> new DataType.Struct.Field(f.name(), jsonType(f.type()))).toList());
            case DataType.ListType l -> new DataType.ListType(jsonType(l.element()));
            case DataType.MapType m -> new DataType.MapType(jsonType(m.key()), jsonType(m.value()));
            default -> type;
        };
    }

    /** A value of a type as DuckDB reads it from JSON, see {@link #jsonType}. */
    private static Object jsonValue(Object value, DataType type) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case DataType.Decimal d when d.precision() > 15 -> ((BigDecimal) value).doubleValue();
            case DataType.Struct s -> {
                List<?> fields = (List<?>) value;
                List<Object> out = new ArrayList<>(fields.size());
                for (int i = 0; i < fields.size(); i++) {
                    out.add(jsonValue(fields.get(i), s.fields().get(i).type()));
                }
                yield out;
            }
            case DataType.ListType l -> {
                List<Object> out = new ArrayList<>();
                for (Object e : (List<?>) value) {
                    out.add(jsonValue(e, l.element()));
                }
                yield out;
            }
            case DataType.MapType m -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    out.put(jsonValue(e.getKey(), m.key()), jsonValue(e.getValue(), m.value()));
                }
                yield out;
            }
            default -> value;
        };
    }

    /** The first and the last day DuckDB reads one day early: the serial numbers from 0 to below 61. */
    private static final int EARLY_FROM = (int) LocalDate.of(1899, 12, 30).toEpochDay();
    private static final int EARLY_TO = (int) LocalDate.of(1900, 2, 28).toEpochDay();
    private static final long MICROS_PER_DAY = 86_400_000_000L;

    /**
     * Loads the extension {@code excel} of DuckDB from its cache, never from
     * the network; false if it is not there, and the comparisons of
     * workbooks are skipped.
     */
    public static boolean loadExcel(DuckDbReference duck) {
        try {
            duck.execute("SET autoinstall_known_extensions = false");
            duck.execute("LOAD excel");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * FyrQL and DuckDB read a workbook with {@code read_xlsx} and the same
     * arguments, with {@code ignore_errors}, so that a value that does not
     * fit the type its column took from the first row is a {@code NULL} in
     * both. The expected rows are FyrQL's, the actual ones DuckDB's in the
     * types of FyrQL's columns, which must be DuckDB's types as well. DuckDB
     * reads the serial numbers below 61 one day early, as it counts them all
     * from 1899-12-30; FyrQL reads them as Excel does, so DuckDB's dates and
     * timestamps of those days are moved by one day.
     *
     * @param arguments further arguments of {@code read_xlsx}, such as {@code ", header = true"}, or empty
     * @throws IllegalStateException if the types of the columns differ
     */
    public static ReadBack xlsx(DuckDbReference duck, FyrQL engine, Path file, String arguments) {
        String sql = "SELECT * FROM read_xlsx('" + path(file) + "', ignore_errors = true" + arguments + ")";
        Schema schema;
        List<Object[]> fyrql;
        try (QueryResult r = engine.sql(sql)) {
            schema = r.schema();
            fyrql = Rows.internal(schema, r.rows());
        }
        Schema text = Schema.of(new Schema.Field("name", DataType.VARCHAR), new Schema.Field("type",
                DataType.VARCHAR));
        List<String> duckTypes = new ArrayList<>();
        for (Object[] row : duck.query("SELECT column_name, column_type FROM (DESCRIBE " + sql + ")", text)) {
            duckTypes.add(row[0] + " " + row[1]);
        }
        List<String> types = new ArrayList<>();
        for (Schema.Field f : schema.fields()) {
            types.add(f.name() + " " + f.type().sqlName());
        }
        if (!types.equals(duckTypes)) {
            throw new IllegalStateException("The columns of " + file.getFileName() + " differ: FyrQL " + types
                    + ", DuckDB " + duckTypes);
        }
        List<Object[]> duckRows = duck.query(sql, schema);
        for (Object[] row : duckRows) {
            for (int c = 0; c < row.length; c++) {
                if (row[c] instanceof Integer d && schema.type(c) == DataType.DATE && d >= EARLY_FROM
                        && d <= EARLY_TO) {
                    row[c] = d + 1;
                } else if (row[c] instanceof Long m && schema.type(c) == DataType.TIMESTAMP
                        && m >= EARLY_FROM * MICROS_PER_DAY && m < (EARLY_TO + 1L) * MICROS_PER_DAY) {
                    row[c] = m + MICROS_PER_DAY;
                }
            }
        }
        return new ReadBack(schema, fyrql, duckRows);
    }
}
