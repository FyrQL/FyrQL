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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the records of JSON input in batches, streaming: only the columns
 * asked for are read, the values of the others are skipped unread, and of
 * a struct column of which only fields are asked for, only those fields.
 * <p>
 * A value is read as the type of its column, as DuckDB 1.5.6 reads JSON
 * into a type: a number into an integral type is rounded half to even and
 * must fit; a string into a number, boolean, {@code DATE} or
 * {@code TIMESTAMP} is cast, or read in the form inferred for the column;
 * a boolean into a number is 1 or 0, a number into a {@code BOOLEAN} is
 * whether it is not 0; any value into a {@code VARCHAR} is a string's text
 * or the JSON text of anything else; a missing key is {@code NULL}. A value
 * that does not fit, an object or array where the type has none, a key
 * that appears twice in an object, and a key that a layout inferred from a
 * sample does not know are errors that name the record; with
 * {@code ignore_errors} the value that does not fit is {@code NULL}, and a
 * second or unknown key is skipped. A layout that was given skips unknown
 * keys always.
 */
public final class JsonRecordReader implements BatchReader {

    private final JsonInput input;
    private final JsonLayout layout;
    private final boolean ignoreErrors;
    private final int batchSize;
    private final Schema output;
    /** What is read of each column, or {@code null} if nothing is. */
    private final Wanted[] wanted;
    /** For each output column, the column it comes from. */
    private final int[] sourceOf;
    /** For each output column, the positions of the fields to follow into its column. */
    private final int[][] stepsOf;
    /** The values read of the current record, by column. */
    private final Object[] record;
    private final boolean[] seen;
    /** The column whose value is being read, for messages. */
    private int column = -1;

    private JsonRecordReader(JsonInput input, JsonLayout layout, List<ColumnPath> columns, JsonOptions options,
            int batchSize) {
        this.input = input;
        this.layout = layout;
        this.ignoreErrors = options.ignoreErrors();
        this.batchSize = batchSize;
        Schema schema = layout.schema();
        this.wanted = new Wanted[schema.size()];
        this.sourceOf = new int[columns.size()];
        this.stepsOf = new int[columns.size()][];
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ColumnPath path = columns.get(i);
            int c = path.column();
            fields.add(path.field(schema));
            if (wanted[c] == null) {
                wanted[c] = new Wanted();
            }
            sourceOf[i] = c;
            stepsOf[i] = wanted[c].add(path.fields(), layout.shape(c));
        }
        this.output = new Schema(fields);
        this.record = new Object[schema.size()];
        this.seen = new boolean[schema.size()];
    }

    /**
     * Opens a reader of the records of an input, which it closes.
     *
     * @param columns the columns to read, by their index in the layout, or
     *        fields inside them
     * @param options the format and {@code ignore_errors}
     * @param source the file or URL, for messages
     */
    public static JsonRecordReader open(InputStream in, JsonLayout layout, List<ColumnPath> columns,
            JsonOptions options, int batchSize, String source) {
        return open(in, layout, columns, options, "", batchSize, source);
    }

    /**
     * Opens a reader of the records a JSON pointer points to: the elements of
     * the array at the pointer, or the one value there if it is no array.
     * What follows that value in the input is not read.
     *
     * @param columns the columns to read, by their index in the layout, or
     *        fields inside them
     * @param options the format and {@code ignore_errors}
     * @param pointer the JSON pointer to the records, see {@link JsonPointers},
     *        such as {@code /data}, or the empty string for the whole input
     * @param source the file or URL, for messages
     * @throws IllegalArgumentException if the pointer is not one, or a path
     *         names no field of its column
     */
    public static JsonRecordReader open(InputStream in, JsonLayout layout, List<ColumnPath> columns,
            JsonOptions options, String pointer, int batchSize, String source) {
        return open(in, layout, columns, options, pointer, batchSize, source, Set.of());
    }

    /**
     * Opens a reader of the records a JSON pointer points to, as
     * {@link #open(InputStream, JsonLayout, List, JsonOptions, String, int, String)}
     * does, that also keeps the scalar values of some keys next to the
     * records, in the objects along the pointer, such as the number of all
     * records and the link to the next page of
     * {@code {"count": 2, "items": [...], "next": "..."}}; see
     * {@link #siblings()}. To find the keys that follow the records, it
     * reads the input to the end of the outermost object along the pointer.
     *
     * @param siblingKeys the keys whose values to keep
     */
    public static JsonRecordReader open(InputStream in, JsonLayout layout, List<ColumnPath> columns,
            JsonOptions options, String pointer, int batchSize, String source, Set<String> siblingKeys) {
        return new JsonRecordReader(new JsonInput(in, options.format(), pointer, source, siblingKeys), layout,
                columns, options, batchSize);
    }

    /**
     * Opens a reader of the records of a part of a file that starts where a
     * record does; its messages count the lines and the records from the
     * start of the file.
     *
     * @param origin the lines and the records of the file before the part
     */
    static JsonRecordReader open(InputStream in, JsonLayout layout, List<ColumnPath> columns, JsonOptions options,
            int batchSize, String source, JsonInput.Origin origin) {
        return new JsonRecordReader(new JsonInput(in, options.format(), source, origin), layout, columns, options,
                batchSize);
    }

    /**
     * Returns the values of the keys asked for when the reader was opened
     * that the objects along the pointer hold next to the records, by key:
     * the text of a string, a number as written, {@code true} or
     * {@code false}; a key whose value is {@code null}, an object or an
     * array is left out, and of a key that appears more than once, the
     * first value is kept. The map is complete once {@link #next()} has
     * returned {@code null}; before that, it holds the keys that precede
     * the records.
     */
    public Map<String, String> siblings() {
        return input.siblings();
    }

    /** Returns the columns that are read, in order. */
    public Schema output() {
        return output;
    }

    @Override
    public Batch next() {
        Vector.Builder[] builders = null;
        int n = 0;
        while (n < batchSize && input.next()) {
            if (builders == null) {
                builders = new Vector.Builder[output.size()];
                for (int c = 0; c < builders.length; c++) {
                    builders[c] = Vectors.builder(output.type(c), batchSize);
                }
            }
            Arrays.fill(record, null);
            try {
                readRecord(record);
            } catch (JsonProcessingException e) {
                throw input.malformed(e);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + input.source(), e);
            } catch (ValueException e) {
                throw error(e.getMessage());
            }
            for (int c = 0; c < builders.length; c++) {
                builders[c].append(pick(c));
            }
            n++;
        }
        if (n == 0) {
            return null;
        }
        List<Vector> vectors = new ArrayList<>(builders.length);
        for (Vector.Builder b : builders) {
            vectors.add(b.build());
        }
        return new Batch(output, n, vectors);
    }

    /** The value of an output column: the value of its column, or the field it follows into it. */
    private Object pick(int output) {
        Object v = record[sourceOf[output]];
        for (int step : stepsOf[output]) {
            if (v == null) {
                return null;
            }
            v = ((List<?>) v).get(step);
        }
        return v;
    }

    /** Reads a record into the values of its columns. */
    private void readRecord(Object[] row) throws IOException {
        JsonParser p = input.parser();
        column = -1;
        if (!layout.records()) {
            column = 0;
            if (wanted[0] == null) {
                p.skipChildren();
            } else {
                row[0] = readOrNull(p, layout.shape(0), wanted[0]);
            }
            return;
        }
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL) {
            return;
        }
        if (t != JsonToken.START_OBJECT) {
            p.skipChildren();
            if (ignoreErrors) {
                return;
            }
            throw new ValueException("expected an OBJECT, but got " + JsonInput.describe(t));
        }
        Arrays.fill(seen, false);
        String key;
        while ((key = p.nextFieldName()) != null) {
            p.nextToken();
            int c = layout.column(key);
            if (c < 0 || seen[c]) {
                p.skipChildren();
                if (ignoreErrors || c < 0 && !layout.inferred()) {
                    continue;
                }
                throw new ValueException(c < 0 ? "the object has the key \"" + key + "\", which is not a column"
                        : "the object has the key \"" + key + "\" twice");
            }
            seen[c] = true;
            column = c;
            if (wanted[c] == null) {
                p.skipChildren();
            } else {
                row[c] = readOrNull(p, layout.shape(c), wanted[c]);
            }
            column = -1;
        }
    }

    /** Reads a value; one that does not fit is {@code NULL} with {@code ignore_errors}. */
    private Object readOrNull(JsonParser p, JsonShape shape) throws IOException {
        return readOrNull(p, shape, Wanted.WHOLE);
    }

    /** Reads what is wanted of a value; one that does not fit is {@code NULL} with {@code ignore_errors}. */
    private Object readOrNull(JsonParser p, JsonShape shape, Wanted want) throws IOException {
        try {
            return read(p, shape, want);
        } catch (ValueException e) {
            if (ignoreErrors) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Reads what is wanted of the value at the current token as a shape and
     * moves to its last token, also if it does not fit. The fields of a
     * struct that are not wanted are skipped unread and are {@code NULL};
     * its keys are checked all the same.
     *
     * @throws ValueException if it does not fit
     */
    private Object read(JsonParser p, JsonShape shape, Wanted want) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL) {
            return null;
        }
        switch (shape) {
            case JsonShape.Json json -> {
                return JsonValues.text(p);
            }
            case JsonShape.Scalar scalar -> {
                return scalar(p, t, scalar);
            }
            case JsonShape.Struct struct -> {
                if (t != JsonToken.START_OBJECT) {
                    throw mismatch(p, t, struct.type());
                }
                Object[] values = new Object[struct.fields().size()];
                boolean[] present = new boolean[values.length];
                String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken();
                    Integer f = struct.index().get(key);
                    if (f == null || present[f]) {
                        p.skipChildren();
                        if (ignoreErrors || f == null && !layout.inferred()) {
                            continue;
                        }
                        skipRest(p);
                        throw new ValueException(f == null ? "the object has the key \"" + key
                                + "\", which is not a field of " + struct.type().sqlName()
                                : "the object has the key \"" + key + "\" twice");
                    }
                    present[f] = true;
                    Wanted field = want.field(f);
                    if (field == null) {
                        p.skipChildren();
                    } else {
                        values[f] = readOrNull(p, struct.fields().get(f), field);
                    }
                }
                return Arrays.asList(values);
            }
            case JsonShape.ListOf list -> {
                if (t != JsonToken.START_ARRAY) {
                    throw mismatch(p, t, list.type());
                }
                List<Object> values = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    values.add(readOrNull(p, list.element()));
                }
                return values;
            }
            case JsonShape.MapOf map -> {
                if (t != JsonToken.START_OBJECT) {
                    throw mismatch(p, t, map.type());
                }
                Map<Object, Object> values = new LinkedHashMap<>();
                DataType keyType = map.type().key();
                String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken();
                    Object k;
                    try {
                        k = keyType == DataType.VARCHAR ? key : Casts.parse(key, keyType);
                    } catch (RuntimeException e) {
                        p.skipChildren();
                        if (ignoreErrors) {
                            continue;
                        }
                        skipRest(p);
                        throw new ValueException("cannot read the key \"" + key + "\" as " + keyType + ": "
                                + e.getMessage());
                    }
                    values.put(k, readOrNull(p, map.value()));
                }
                return values;
            }
        }
    }

    private Object scalar(JsonParser p, JsonToken t, JsonShape.Scalar shape) throws IOException {
        DataType type = shape.type();
        switch (t) {
            case START_OBJECT, START_ARRAY -> {
                if (type == DataType.VARCHAR) {
                    return JsonValues.text(p);
                }
                throw mismatch(p, t, type);
            }
            case VALUE_STRING -> {
                String s = p.getText();
                if (shape.kind() != null) {
                    Object v = shape.kind().parse(s);
                    if (v == null) {
                        throw new ValueException("cannot read \"" + s + "\" as " + type + " in the form "
                                + shape.kind().name());
                    }
                    return v;
                }
                if (shape.parser() != null) {
                    try {
                        return shape.parser().parse(s, type);
                    } catch (RuntimeException e) {
                        throw new ValueException("cannot read \"" + s + "\" as " + type + ": " + e.getMessage());
                    }
                }
                if (type == DataType.VARCHAR) {
                    return s;
                }
                try {
                    return Casts.parse(s, type);
                } catch (RuntimeException e) {
                    throw new ValueException("cannot read \"" + s + "\" as " + type + ": " + e.getMessage());
                }
            }
            case VALUE_TRUE, VALUE_FALSE -> {
                boolean b = t == JsonToken.VALUE_TRUE;
                if (type == DataType.BOOLEAN) {
                    return b;
                }
                if (type == DataType.VARCHAR) {
                    return Boolean.toString(b);
                }
                if (type.isIntegral()) {
                    return Values.box(b ? 1 : 0, type);
                }
                if (type == DataType.DOUBLE) {
                    return b ? 1.0 : 0.0;
                }
                if (type == DataType.FLOAT) {
                    return b ? 1.0f : 0.0f;
                }
                throw mismatch(p, t, type);
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                return number(p, t, type);
            }
            default -> throw mismatch(p, t, type);
        }
    }

    private Object number(JsonParser p, JsonToken t, DataType type) throws IOException {
        boolean integer = t == JsonToken.VALUE_NUMBER_INT;
        try {
            if (type == DataType.VARCHAR) {
                return JsonValues.number(p);
            }
            if (type == DataType.DOUBLE) {
                return p.getDoubleValue();
            }
            if (type == DataType.FLOAT) {
                return (float) p.getDoubleValue();
            }
            if (type == DataType.BOOLEAN) {
                return integer ? p.getNumberType() == JsonParser.NumberType.BIG_INTEGER
                        || p.getLongValue() != 0 : p.getDoubleValue() != 0;
            }
            if (type instanceof DataType.Decimal d) {
                return Values.toDecimal(p.getDecimalValue(), d);
            }
            if (type.isIntegral()) {
                if (integer && p.getNumberType() != JsonParser.NumberType.BIG_INTEGER) {
                    return Values.boxChecked(p.getLongValue(), type);
                }
                if (integer) {
                    return Values.boxChecked(p.getBigIntegerValue(), type);
                }
                double d = Math.rint(p.getDoubleValue());
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new ArithmeticException("Out of range for " + type);
                }
                BigInteger v = new BigDecimal(d).toBigInteger();
                return Values.boxChecked(v, type);
            }
        } catch (ArithmeticException e) {
            throw new ValueException("cannot read " + JsonValues.number(p) + " as " + type + ": " + e.getMessage());
        }
        throw mismatch(p, t, type);
    }

    /** Skips a value that does not fit and returns the error. */
    private static ValueException mismatch(JsonParser p, JsonToken t, DataType type) throws IOException {
        String what = JsonInput.describe(t);
        p.skipChildren();
        return new ValueException("expected " + type.sqlName() + ", but got " + what);
    }

    /** Moves to the end of the object or array the current value is in. */
    private static void skipRest(JsonParser p) throws IOException {
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY) {
            if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                p.skipChildren();
            }
        }
    }

    /** Returns the error for a value of the current record, with the column and a hint about the sample. */
    private ConversionException error(String reason) {
        StringBuilder b = new StringBuilder("Record ").append(input.number()).append(" of ").append(input.source());
        if (column >= 0) {
            b.append(", column ").append(layout.schema().name(column));
        }
        b.append(": ").append(reason);
        if (layout.inferred() && layout.sampled() >= 0) {
            b.append("; the schema was inferred from the first ").append(layout.sampled())
                    .append(" records, sample_size = -1 infers it from all of them");
        }
        return new ConversionException(b.toString());
    }

    @Override
    public void close() {
        input.close();
    }

    /** A value that does not fit its type; cheap, since with {@code ignore_errors} there may be many. */
    private static final class ValueException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ValueException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * What is read of a value: all of it, or of a struct only some fields,
     * each again with what is read of it.
     */
    private static final class Wanted {

        static final Wanted WHOLE = new Wanted(true);

        private boolean whole;
        private final Map<Integer, Wanted> fields = new HashMap<>();

        Wanted() {
            this(false);
        }

        private Wanted(boolean whole) {
            this.whole = whole;
        }

        /**
         * Adds the value at the end of a path of field names into a value of
         * a shape, and returns the positions of the fields on the way.
         */
        int[] add(List<String> path, JsonShape shape) {
            int[] steps = new int[path.size()];
            Wanted w = this;
            JsonShape s = shape;
            for (int i = 0; i < steps.length; i++) {
                if (!(s instanceof JsonShape.Struct struct) || struct.type().indexOf(path.get(i)) < 0) {
                    throw new IllegalArgumentException("No field " + path.get(i) + " in a " + s.type().sqlName());
                }
                steps[i] = struct.type().indexOf(path.get(i));
                w = w.fields.computeIfAbsent(steps[i], f -> new Wanted());
                s = struct.fields().get(steps[i]);
            }
            w.whole = true;
            return steps;
        }

        /** Returns what is read of a field of a struct, or {@code null} if nothing is. */
        Wanted field(int f) {
            return whole ? WHOLE : fields.get(f);
        }
    }
}
