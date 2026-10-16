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

package net.fyrql.test.tpch;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.test.DuckDbReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Writes the files of the TPC-H tables at a scale factor into a
 * directory, once: a CSV file with a header for every table in a CSV file
 * or a database, a file of JSON lines for every table in JSON, and a
 * Parquet file for every table in Parquet, which DuckDB writes from a CSV
 * file that is deleted afterwards. The tables of the memory connector are
 * not written. A marker file records the scale factor; while it is there
 * and says the same, the files are kept.
 */
final class TpchData {

    /** The delimiter of the CSV files, as in the files of dbgen. */
    static final char DELIMITER = '|';

    /** The marker file, written last. */
    private static final String MARKER = "scale-factor";

    /** The version of the file format; a change makes old files be written again. */
    private static final String FORMAT = "2";

    /**
     * The rows of a row group of the Parquet files: those of DuckDB's
     * default, and fewer at the small scale factor, so that a scan reads
     * several row groups there as well.
     */
    private static final long ROW_GROUP_ROWS = 122_880;
    private static final long SMALL_ROW_GROUP_ROWS = 16_384;

    private TpchData() {
    }

    /** Returns the file of a table in a directory. */
    static Path file(Path dir, TpchTable table) {
        return dir.resolve(table.tableName() + switch (table.store()) {
            case JSON -> ".jsonl";
            case PARQUET -> ".parquet";
            case CSV, DATABASE, MEMORY -> ".csv";
        });
    }

    /**
     * Returns a call of DuckDB's {@code read_csv} that reads a CSV file of
     * a table as it is written here, with the types of the table.
     */
    static String readCsv(TpchTable table, Path file) {
        return "read_csv('" + path(file) + "', delim = '" + DELIMITER + "', header = true, quote = '\"', columns = "
                + columns(table.schema()) + ")";
    }

    /** Returns the absolute path of a file with forward slashes, for a string literal of DuckDB. */
    static String path(Path file) {
        return file.toAbsolutePath().toString().replace('\\', '/');
    }

    /** The columns of a schema as a struct literal of DuckDB: names to types. */
    static String columns(Schema schema) {
        StringBuilder b = new StringBuilder("{");
        for (int c = 0; c < schema.size(); c++) {
            b.append(c > 0 ? ", " : "").append('\'').append(schema.name(c)).append("': '")
                    .append(DuckDbDialect.INSTANCE.typeName(schema.type(c))).append('\'');
        }
        return b.append('}').toString();
    }

    /** Writes the files of a scale factor into a directory, unless they are there already. */
    static void generate(double scaleFactor, Path dir) {
        String marker = scaleFactor + " " + FORMAT;
        try {
            Path markerFile = dir.resolve(MARKER);
            if (Files.isRegularFile(markerFile) && Files.readString(markerFile).equals(marker)) {
                return;
            }
            Files.createDirectories(dir);
            Files.deleteIfExists(markerFile);
            for (TpchTable table : TpchTable.values()) {
                switch (table.store()) {
                    case CSV, DATABASE -> writeCsv(table, scaleFactor, file(dir, table));
                    case JSON -> writeJson(table, scaleFactor, file(dir, table));
                    case PARQUET -> writeParquet(table, scaleFactor, file(dir, table));
                    case MEMORY -> {
                    }
                }
            }
            Files.writeString(markerFile, marker);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the TPC-H files into " + dir, e);
        }
    }

    /** Writes a CSV file next to the Parquet file, lets DuckDB turn it into the Parquet file and deletes it. */
    private static void writeParquet(TpchTable table, double scaleFactor, Path file) throws IOException {
        Path csv = file.resolveSibling(table.tableName() + ".csv");
        writeCsv(table, scaleFactor, csv);
        long rowGroup = scaleFactor <= TpchEngine.SMALL ? SMALL_ROW_GROUP_ROWS : ROW_GROUP_ROWS;
        try (DuckDbReference duck = new DuckDbReference()) {
            duck.execute("COPY (SELECT * FROM " + readCsv(table, csv) + ") TO '" + path(file)
                    + "' (FORMAT parquet, ROW_GROUP_SIZE " + rowGroup + ")");
        }
        Files.delete(csv);
    }

    private static void writeCsv(TpchTable table, double scaleFactor, Path file) throws IOException {
        Schema schema = table.schema();
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < schema.size(); c++) {
                line.append(c > 0 ? String.valueOf(DELIMITER) : "").append(schema.name(c));
            }
            out.write(line.append('\n').toString());
            for (Object[] row : table.rows(scaleFactor)) {
                line.setLength(0);
                for (int c = 0; c < row.length; c++) {
                    if (c > 0) {
                        line.append(DELIMITER);
                    }
                    appendCsv(line, row[c], schema.type(c));
                }
                out.write(line.append('\n').toString());
            }
        }
    }

    /**
     * Appends a value: a date in ISO form, a text quoted if it is empty or
     * holds the delimiter, a quote or a line break.
     */
    private static void appendCsv(StringBuilder line, Object value, DataType type) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            boolean quote = s.isEmpty();
            for (int i = 0; i < s.length() && !quote; i++) {
                char ch = s.charAt(i);
                quote = ch == DELIMITER || ch == '"' || ch == '\n' || ch == '\r';
            }
            if (quote) {
                line.append('"').append(s.replace("\"", "\"\"")).append('"');
            } else {
                line.append(s);
            }
            return;
        }
        line.append(type == DataType.DATE ? LocalDate.ofEpochDay((Integer) value).toString()
                : value instanceof BigDecimal d ? d.toPlainString() : value.toString());
    }

    private static void writeJson(TpchTable table, double scaleFactor, Path file) throws IOException {
        Schema schema = table.schema();
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            for (Object[] row : table.rows(scaleFactor)) {
                line.setLength(0);
                line.append('{');
                for (int c = 0; c < row.length; c++) {
                    line.append(c > 0 ? "," : "");
                    appendJsonString(line, schema.name(c));
                    line.append(':');
                    Object value = row[c];
                    if (value == null) {
                        line.append("null");
                    } else if (value instanceof Number n) {
                        line.append(n instanceof BigDecimal d ? d.toPlainString() : n.toString());
                    } else {
                        appendJsonString(line, value.toString());
                    }
                }
                out.write(line.append("}\n").toString());
            }
        }
    }

    private static void appendJsonString(StringBuilder line, String s) {
        line.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> line.append("\\\"");
                case '\\' -> line.append("\\\\");
                case '\n' -> line.append("\\n");
                case '\r' -> line.append("\\r");
                case '\t' -> line.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        line.append(String.format("\\u%04x", (int) ch));
                    } else {
                        line.append(ch);
                    }
                }
            }
        }
        line.append('"');
    }
}
