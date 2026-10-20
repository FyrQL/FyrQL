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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.Sink;
import net.fyrql.connector.TemporaryFile;
import net.fyrql.core.function.DateFormats;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Dates;
import net.fyrql.core.vector.Batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.zip.GZIPOutputStream;

/**
 * Writes rows into a JSON file as DuckDB's {@code COPY ... TO} writes them,
 * see {@link JsonConnector#openFile}: an object for each row, on a line of
 * its own or as the elements of an array.
 */
final class JsonFileWriter implements Sink {

    private static final int BUFFER = 1 << 16;

    private final TemporaryFile file;
    private final Writer out;
    private final List<String> names;
    private final DataType[] types;
    private final boolean array;
    private final LongFunction<String> dateFormat;
    private final LongFunction<String> timestampFormat;
    private final StringBuilder line = new StringBuilder();
    private long rows;
    private boolean done;

    private JsonFileWriter(TemporaryFile file, Writer out, Schema schema, boolean array,
            LongFunction<String> dateFormat, LongFunction<String> timestampFormat) {
        this.file = file;
        this.out = out;
        this.names = schema.fields().stream().map(Schema.Field::name).toList();
        this.types = schema.fields().stream().map(Schema.Field::type).toArray(DataType[]::new);
        this.array = array;
        this.dateFormat = dateFormat;
        this.timestampFormat = timestampFormat;
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

    /**
     * Checks the options and creates the temporary file.
     *
     * @throws IllegalArgumentException if an option is unknown or does not fit
     */
    static JsonFileWriter open(Path path, Schema schema, Map<String, String> options) {
        String owner = "the JSON file " + path;
        ConfigOptions o = new ConfigOptions(owner, options, Path.of(""));
        boolean array = o.bool("array").orElse(false);
        LongFunction<String> dateFormat = o.get("dateformat", null, DateFormats::writer).orElse(null);
        LongFunction<String> timestampFormat = o.get("timestampformat", null, DateFormats::writer).orElse(null);
        boolean gzip = gzip(o, String.valueOf(path.getFileName()).toLowerCase(Locale.ROOT), owner);
        o.rejectUnknown();

        TemporaryFile file = TemporaryFile.create(path);
        try {
            OutputStream stream = Files.newOutputStream(file.path());
            if (gzip) {
                stream = new GZIPOutputStream(stream, BUFFER);
            }
            Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), BUFFER);
            if (array) {
                out.write("[\n");
            }
            return new JsonFileWriter(file, out, schema, array, dateFormat, timestampFormat);
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("Cannot write " + file.path() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void write(Batch batch) {
        if (done) {
            throw new IllegalStateException("The JSON file " + file.target() + " is already closed");
        }
        int n = batch.rowCount();
        for (int r = 0; r < n; r++) {
            if (array) {
                line.append(rows + r > 0 ? ",\n\t" : "\t");
            }
            line.append('{');
            for (int c = 0; c < types.length; c++) {
                if (c > 0) {
                    line.append(',');
                }
                JsonValues.string(names.get(c), line);
                line.append(':');
                Object v = batch.column(c).get(r);
                if (v != null && types[c] == DataType.DATE && dateFormat != null) {
                    JsonValues.string(dateFormat.apply((Integer) v * Dates.MICROS_PER_DAY), line);
                } else if (v != null && types[c] == DataType.TIMESTAMP && timestampFormat != null) {
                    JsonValues.string(timestampFormat.apply((Long) v), line);
                } else {
                    value(v, types[c]);
                }
            }
            line.append('}');
            if (!array) {
                line.append('\n');
            }
            flush();
        }
        rows += n;
    }

    /**
     * Appends a value: numbers and booleans as they are, a {@code FLOAT} in
     * the shortest form that reads back as the same float, {@code NaN} and
     * infinities as the tokens {@code NaN}, {@code Infinity} and
     * {@code -Infinity}; a {@code STRUCT} or a {@code MAP} as an object, with
     * the keys of a map cast to {@code VARCHAR}; a {@code LIST} as an
     * array; anything else as the string of its cast to {@code VARCHAR}.
     */
    private void value(Object v, DataType type) {
        if (v == null) {
            line.append("null");
            return;
        }
        switch (type) {
            case DataType.Struct s -> {
                List<?> values = (List<?>) v;
                line.append('{');
                for (int i = 0; i < s.fields().size(); i++) {
                    if (i > 0) {
                        line.append(',');
                    }
                    JsonValues.string(s.fields().get(i).name(), line);
                    line.append(':');
                    value(values.get(i), s.fields().get(i).type());
                }
                line.append('}');
            }
            case DataType.ListType l -> {
                line.append('[');
                boolean first = true;
                for (Object e : (List<?>) v) {
                    if (!first) {
                        line.append(',');
                    }
                    first = false;
                    value(e, l.element());
                }
                line.append(']');
            }
            case DataType.MapType m -> {
                line.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (!first) {
                        line.append(',');
                    }
                    first = false;
                    JsonValues.string(Casts.format(e.getKey(), m.key()), line);
                    line.append(':');
                    value(e.getValue(), m.value());
                }
                line.append('}');
            }
            case DataType.Decimal d -> line.append(((BigDecimal) v).toPlainString());
            case DataType.Scalar s -> {
                switch (s) {
                    case BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT -> line.append(v);
                    case FLOAT -> line.append(JsonValues.formatDouble(Double.parseDouble(Float.toString((Float) v))));
                    case DOUBLE -> line.append(JsonValues.formatDouble((Double) v));
                    default -> JsonValues.string(Casts.format(v, type), line);
                }
            }
        }
    }

    private void flush() {
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
            throw new IllegalStateException("The JSON file " + file.target() + " is already closed");
        }
        done = true;
        try {
            if (array) {
                out.write(rows > 0 ? "\n]\n" : "]\n");
            }
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
