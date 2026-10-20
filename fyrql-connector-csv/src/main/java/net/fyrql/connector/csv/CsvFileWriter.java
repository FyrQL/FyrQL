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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Sink;
import net.fyrql.connector.TemporaryFile;
import net.fyrql.core.function.DateFormats;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Dates;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.zip.GZIPOutputStream;

/**
 * Writes rows into a CSV file as DuckDB's {@code COPY ... TO} writes them,
 * see {@link CsvConnector#openFile}: UTF-8 without a byte order mark, lines
 * ended by a line feed, a value as its cast to {@code VARCHAR} and
 * {@code NULL} as the null string, a value in quotes if it holds the
 * delimiter, the quote, the escape, a carriage return or a line feed, or if
 * it equals the null string, such as an empty string by default.
 */
final class CsvFileWriter implements Sink {

    private static final int BUFFER = 1 << 16;

    private final TemporaryFile file;
    private final Writer out;
    private final DataType[] types;
    private final char delimiter;
    private final char quote;
    private final char escape;
    private final String nullString;
    private final LongFunction<String> dateFormat;
    private final LongFunction<String> timestampFormat;
    private final StringBuilder line = new StringBuilder();
    private long rows;
    private boolean done;

    private CsvFileWriter(TemporaryFile file, Writer out, Schema schema, char delimiter, char quote, char escape,
            String nullString, LongFunction<String> dateFormat, LongFunction<String> timestampFormat) {
        this.file = file;
        this.out = out;
        this.types = schema.fields().stream().map(Schema.Field::type).toArray(DataType[]::new);
        this.delimiter = delimiter;
        this.quote = quote;
        this.escape = escape;
        this.nullString = nullString;
        this.dateFormat = dateFormat;
        this.timestampFormat = timestampFormat;
    }

    /**
     * Checks the options, creates the temporary file and writes the header.
     *
     * @throws IllegalArgumentException if an option is unknown or does not fit
     */
    static CsvFileWriter open(Path path, Schema schema, Map<String, String> options) {
        ConfigOptions o = new ConfigOptions("the CSV file " + path, options, Path.of(""));
        String name = String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT);
        boolean header = o.bool("header").orElse(true);
        char delimiter = first(o.character("delimiter"), o.character("delim"), o.character("sep"))
                .orElse(name.endsWith(".tsv") || name.endsWith(".tsv.gz") ? '\t' : ',');
        char quote = o.character("quote").orElse('"');
        char escape = o.character("escape").orElse(quote);
        String nullString = first(o.string("nullstr"), o.string("null")).orElse("");
        LongFunction<String> dateFormat = o.get("dateformat", null, DateFormats::writer).orElse(null);
        LongFunction<String> timestampFormat = o.get("timestampformat", null, DateFormats::writer).orElse(null);
        boolean gzip = gzip(o, name, "the CSV file " + path);
        o.rejectUnknown();
        if (delimiter == quote || delimiter == '\n' || delimiter == '\r' || quote == '\n' || quote == '\r') {
            throw new IllegalArgumentException("The CSV file " + path + " needs a delimiter and a quote that"
                    + " differ from each other and from line breaks");
        }

        TemporaryFile file = TemporaryFile.create(path);
        try {
            OutputStream stream = Files.newOutputStream(file.path());
            if (gzip) {
                stream = new GZIPOutputStream(stream, BUFFER);
            }
            Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), BUFFER);
            CsvFileWriter writer = new CsvFileWriter(file, out, schema, delimiter, quote, escape, nullString,
                    dateFormat, timestampFormat);
            if (header) {
                for (int c = 0; c < schema.size(); c++) {
                    if (c > 0) {
                        writer.line.append(delimiter);
                    }
                    writer.field(schema.fields().get(c).name());
                }
                writer.endLine();
            }
            return writer;
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("Cannot write " + file.path() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /**
     * Reads the option {@code compression}: {@code gzip}, {@code none} (or
     * {@code uncompressed}), or {@code auto}, the default, which is
     * {@code gzip} for a name that ends in {@code .gz}.
     */
    static boolean gzip(ConfigOptions o, String lowerName, String owner) {
        String compression = o.string("compression").map(c -> c.toLowerCase(Locale.ROOT)).orElse("auto");
        return switch (compression) {
            case "gzip" -> true;
            case "none", "uncompressed" -> false;
            case "auto" -> lowerName.endsWith(".gz");
            default -> throw new IllegalArgumentException("The option compression of " + owner
                    + " must be gzip, none or auto, not " + compression);
        };
    }

    @SafeVarargs
    private static <T> Optional<T> first(Optional<T>... values) {
        for (Optional<T> v : values) {
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    @Override
    public void write(Batch batch) {
        if (done) {
            throw new IllegalStateException("The CSV file " + file.target() + " is already closed");
        }
        int n = batch.rowCount();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < types.length; c++) {
                if (c > 0) {
                    line.append(delimiter);
                }
                Vector v = batch.column(c);
                if (v.isNull(r)) {
                    line.append(nullString);
                } else {
                    field(text(v, r, types[c]));
                }
            }
            endLine();
        }
        rows += n;
    }

    private String text(Vector v, int row, DataType type) {
        if (type == DataType.DATE && dateFormat != null) {
            return dateFormat.apply(((IntVector) v).getInt(row) * Dates.MICROS_PER_DAY);
        }
        if (type == DataType.TIMESTAMP && timestampFormat != null) {
            return timestampFormat.apply(((BigIntVector) v).getLong(row));
        }
        return Casts.format(v.get(row), type);
    }

    /** Appends a value to the line, in quotes if it needs them. */
    private void field(String s) {
        if (!needsQuotes(s)) {
            line.append(s);
            return;
        }
        line.append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == quote || c == escape) {
                line.append(escape);
            }
            line.append(c);
        }
        line.append(quote);
    }

    private boolean needsQuotes(String s) {
        if (s.equals(nullString)) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == delimiter || c == quote || c == escape || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }

    private void endLine() {
        line.append('\n');
        try {
            out.append(line);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file.path() + ": " + e.getMessage(), e);
        }
        line.setLength(0);
    }

    @Override
    public long commit() {
        if (done) {
            throw new IllegalStateException("The CSV file " + file.target() + " is already closed");
        }
        done = true;
        try {
            out.close();
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("Cannot write " + file.path() + ": " + e.getMessage(), e);
        }
        file.commit();
        return rows;
    }

    @Override
    public void close() {
        if (!done) {
            done = true;
            try {
                out.close();
            } catch (IOException e) {
                // The file is deleted anyway.
            }
        }
        file.close();
    }
}
