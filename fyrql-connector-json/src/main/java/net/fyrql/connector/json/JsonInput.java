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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import net.fyrql.core.value.ConversionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The records of a JSON input, one after the other: the elements of the
 * top-level array, or the top-level values. With a JSON pointer, the
 * records are the elements of the array it points to, or the one value it
 * points to if that is no array; what follows that value is not read.
 */
final class JsonInput implements AutoCloseable {

    private static final JsonFactory FACTORY = new JsonFactory();

    private final JsonParser parser;
    private final String source;
    private final JsonFormat format;
    private final String pointer;
    private boolean array;
    private boolean started;
    private boolean ended;
    private long record;

    /**
     * Opens an input; it is closed with the reader.
     *
     * @param pointer the JSON pointer to the records, see {@link JsonPointers},
     *        or the empty string for the whole input
     * @param source the file or URL, for messages
     * @throws IllegalArgumentException if the pointer is not one
     */
    JsonInput(InputStream in, JsonFormat format, String pointer, String source) {
        JsonPointers.check(pointer);
        this.source = source;
        this.format = format;
        this.pointer = pointer;
        try {
            this.parser = FACTORY.createParser(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }

    JsonParser parser() {
        return parser;
    }

    /** Returns the number of the current record, from 1. */
    long record() {
        return record;
    }

    String source() {
        return source;
    }

    /**
     * Moves to the first token of the next record.
     *
     * @return {@code false} after the last record
     */
    boolean next() {
        if (ended) {
            return false;
        }
        boolean pointed = !pointer.isEmpty();
        try {
            JsonToken t;
            if (!started) {
                started = true;
                t = pointed ? seek() : parser.nextToken();
                array = t == JsonToken.START_ARRAY && format != JsonFormat.NEWLINE_DELIMITED;
                if (format == JsonFormat.ARRAY && !array && t != null) {
                    throw new ConversionException("Expected a " + (pointed ? "JSON array at the pointer \"" + pointer
                            + "\"" : "top-level JSON array") + " with format 'array' in " + source + ", but "
                            + (pointed ? "it is " : "it starts with ") + describe(t) + "; format 'auto' or"
                            + " 'newline_delimited' reads " + (pointed ? "the value as one record"
                            : "a sequence of values"));
                }
                if (array) {
                    t = parser.nextToken();
                }
            } else if (pointed && !array) {
                // The one value the pointer points to was the only record.
                t = null;
            } else {
                t = parser.nextToken();
            }
            if (array && t == JsonToken.END_ARRAY) {
                if (!pointed && parser.nextToken() != null) {
                    throw new ConversionException("More follows the top-level JSON array in " + source
                            + " at " + where() + "; format 'newline_delimited' reads a sequence of values");
                }
                t = null;
            }
            if (t == null) {
                ended = true;
                return false;
            }
            record++;
            return true;
        } catch (JsonProcessingException e) {
            throw malformed(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }

    /**
     * Reads up to the value the pointer points to.
     *
     * @return the first token of the value
     */
    private JsonToken seek() throws IOException {
        JsonToken t = parser.nextToken();
        for (JsonPointer p = JsonPointer.compile(pointer); !p.matches(); p = p.tail()) {
            String key = p.getMatchingProperty();
            if (t == JsonToken.START_OBJECT) {
                String k;
                while ((k = parser.nextFieldName()) != null) {
                    t = parser.nextToken();
                    if (k.equals(key)) {
                        break;
                    }
                    parser.skipChildren();
                }
                if (k == null) {
                    throw notFound("the object has no key \"" + key + "\"");
                }
            } else if (t == JsonToken.START_ARRAY && p.getMatchingIndex() >= 0) {
                t = parser.nextToken();
                for (int i = 0; i < p.getMatchingIndex() && t != JsonToken.END_ARRAY; i++) {
                    parser.skipChildren();
                    t = parser.nextToken();
                }
                if (t == JsonToken.END_ARRAY) {
                    throw notFound("the array has no element " + key);
                }
            } else {
                throw notFound("where it looks for \"" + key + "\", it finds " + describe(t));
            }
        }
        return t;
    }

    private ConversionException notFound(String why) {
        return new ConversionException("The JSON pointer \"" + pointer + "\" points to nothing in " + source + ": "
                + why + (parser.currentToken() == null ? "" : " at " + where()));
    }

    /** Returns the error for JSON that does not parse, with its place. */
    ConversionException malformed(JsonProcessingException e) {
        JsonLocation l = e.getLocation();
        String at = l == null ? "" : " at line " + l.getLineNr() + ", column " + l.getColumnNr();
        return new ConversionException("Malformed JSON in " + source + at + ", in record " + Math.max(record, 1)
                + ": " + e.getOriginalMessage(), e);
    }

    /** Returns the line and column of the current token, for messages. */
    String where() {
        JsonLocation l = parser.currentTokenLocation();
        return "line " + l.getLineNr() + ", column " + l.getColumnNr();
    }

    /** Describes a token for a message, as DuckDB names the types of JSON values. */
    static String describe(JsonToken t) {
        if (t == null) {
            return "the end";
        }
        return switch (t) {
            case START_OBJECT -> "an OBJECT";
            case START_ARRAY -> "an ARRAY";
            case VALUE_STRING -> "a STRING";
            case VALUE_NUMBER_INT -> "an INTEGER";
            case VALUE_NUMBER_FLOAT -> "a DOUBLE";
            case VALUE_TRUE, VALUE_FALSE -> "a BOOLEAN";
            case VALUE_NULL -> "NULL";
            default -> t.toString();
        };
    }

    @Override
    public void close() {
        try {
            parser.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot close " + source, e);
        }
    }
}
