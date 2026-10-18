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

package net.fyrql.connector.odata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;

/**
 * The error a service answers in JSON, as version 4 writes it,
 * {@code {"error": {"code": "...", "message": "..."}}}, and version 2,
 * whose message is {@code {"lang": "en", "value": "..."}}.
 *
 * @param code the code, or the empty string if there is none
 * @param message the message on one line, or the empty string if there is none
 */
record ODataError(String code, String message) {

    private static final JsonFactory FACTORY = new JsonFactory();

    /** The most bytes of a body that are read for an error. */
    private static final int LIMIT = 1 << 16;

    /**
     * Reads the error from a body, which it closes.
     *
     * @return the error, or {@code null} if the body holds none
     */
    static ODataError read(InputStream body) {
        byte[] bytes;
        try (InputStream in = body) {
            bytes = in.readNBytes(LIMIT);
        } catch (IOException e) {
            return null;
        }
        return parse(bytes);
    }

    /** Reads the error from JSON, or returns {@code null} if it holds none. */
    static ODataError parse(byte[] json) {
        try (JsonParser p = FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            String key;
            while ((key = p.nextFieldName()) != null) {
                JsonToken t = p.nextToken();
                if (key.equals("error") && t == JsonToken.START_OBJECT) {
                    return error(p);
                }
                p.skipChildren();
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static ODataError error(JsonParser p) throws IOException {
        String code = "";
        String message = "";
        String key;
        while ((key = p.nextFieldName()) != null) {
            JsonToken t = p.nextToken();
            if (key.equals("code") && t.isScalarValue() && t != JsonToken.VALUE_NULL) {
                code = oneLine(p.getText());
            } else if (key.equals("message") && t == JsonToken.VALUE_STRING) {
                message = oneLine(p.getText());
            } else if (key.equals("message") && t == JsonToken.START_OBJECT) {
                String k;
                while ((k = p.nextFieldName()) != null) {
                    JsonToken v = p.nextToken();
                    if (k.equals("value") && v == JsonToken.VALUE_STRING) {
                        message = oneLine(p.getText());
                    } else {
                        p.skipChildren();
                    }
                }
            } else {
                p.skipChildren();
            }
        }
        return code.isEmpty() && message.isEmpty() ? null : new ODataError(code, message);
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }

    /** Returns the message with the code in parentheses, or the code alone if there is no message. */
    @Override
    public String toString() {
        if (message.isEmpty()) {
            return code;
        }
        return code.isEmpty() ? message : message + " (" + code + ")";
    }
}
