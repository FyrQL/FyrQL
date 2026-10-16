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

import com.fasterxml.jackson.core.JsonProcessingException;
import net.fyrql.core.type.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Infers the columns of JSON input from a sample of its records, as DuckDB
 * 1.5.6 does in {@code read_json} with its defaults: if the records are
 * objects, which become a {@code STRUCT} by the rules of
 * {@link JsonStructure}, their keys are the columns in the order they first
 * appear; otherwise each record is a value of the single column
 * {@code json}. Empty input gives the column {@code json} as raw JSON text.
 * A value that fits no single type, such as numbers and strings in one
 * column, is raw JSON text, FyrQL's stand-in for DuckDB's type
 * {@code JSON}: a {@code VARCHAR} that holds {@code "x"} with its quotes for
 * a string.
 * <p>
 * Where FyrQL differs: it has no {@code TIME}, so strings of a time of day
 * stay {@code VARCHAR}; the raw JSON text is a {@code VARCHAR}, so a column
 * of it compares as text; an input that starts with an array is that one
 * array, see {@link JsonFormat#AUTO}.
 */
public final class JsonSchemaInference {

    private JsonSchemaInference() {
    }

    /**
     * Infers the layout of the records of an input, which it reads up to the
     * sample size and closes.
     *
     * @param source the file or URL, for messages
     * @throws net.fyrql.core.value.ConversionException if the sample is not
     *         well-formed JSON
     * @throws IllegalArgumentException if a {@code STRUCT} would have two
     *         fields whose names differ only in case
     */
    public static JsonLayout infer(InputStream in, JsonOptions options, String source) {
        return infer(in, options, "", source);
    }

    /**
     * Infers the layout of the records a JSON pointer points to, as
     * {@link JsonRecordReader#open(InputStream, JsonLayout, List, JsonOptions, String, int, String)}
     * reads them: the elements of the array at the pointer, or the one value
     * there if it is no array.
     *
     * @param pointer the JSON pointer to the records, see {@link JsonPointers},
     *        such as {@code /data}, or the empty string for the whole input
     * @param source the file or URL, for messages
     * @throws net.fyrql.core.value.ConversionException if the sample is not
     *         well-formed JSON or the pointer points to nothing
     * @throws IllegalArgumentException if the pointer is not one, or if a
     *         {@code STRUCT} would have two fields whose names differ only in
     *         case
     */
    public static JsonLayout infer(InputStream in, JsonOptions options, String pointer, String source) {
        StringKinds kinds = StringKinds.of(options);
        JsonStructure root = new JsonStructure();
        boolean all = true;
        try (JsonInput input = new JsonInput(in, options.format(), pointer, source)) {
            while (input.next()) {
                if (options.sampleSize() >= 0 && input.record() > options.sampleSize()) {
                    all = false;
                    break;
                }
                try {
                    root.add(input.parser(), kinds);
                } catch (JsonProcessingException e) {
                    throw input.malformed(e);
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot read " + source, e);
                }
            }
        }
        long sampled = all ? -1 : options.sampleSize();
        JsonShape shape = root.recordShape(options, kinds);
        if (shape instanceof JsonShape.Struct struct) {
            List<Schema.Field> fields = new ArrayList<>();
            for (int c = 0; c < struct.keys().size(); c++) {
                fields.add(new Schema.Field(struct.type().fields().get(c).name(), struct.fields().get(c).type()));
            }
            return new JsonLayout(new Schema(fields), true, struct.keys(), struct.fields(), true, sampled);
        }
        return new JsonLayout(new Schema(List.of(new Schema.Field("json", shape.type()))), false, List.of(),
                List.of(shape), true, sampled);
    }
}
