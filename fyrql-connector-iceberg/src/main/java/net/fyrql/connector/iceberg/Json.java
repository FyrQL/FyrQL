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

package net.fyrql.connector.iceberg;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON as a tree of plain values: an object is a {@code Map} in the order
 * of its keys, an array a {@code List}, a whole number a {@code Long} or,
 * beyond, a {@code BigInteger}, any other number a {@code Double}, and
 * text, booleans and {@code null} what they are.
 */
final class Json {

    private static final JsonFactory FACTORY = new JsonFactory();

    private Json() {
    }

    /**
     * Parses one JSON value.
     *
     * @throws IOException if the text is not one JSON value
     */
    static Object parse(byte[] json) throws IOException {
        try (JsonParser p = FACTORY.createParser(json)) {
            return parse(p);
        }
    }

    /** Parses one JSON value, see {@link #parse(byte[])}. */
    static Object parse(String json) throws IOException {
        try (JsonParser p = FACTORY.createParser(json)) {
            return parse(p);
        }
    }

    private static Object parse(JsonParser p) throws IOException {
        if (p.nextToken() == null) {
            throw new IOException("No JSON value");
        }
        Object value = value(p);
        if (p.nextToken() != null) {
            throw new IOException("More than one JSON value at " + p.currentLocation());
        }
        return value;
    }

    private static Object value(JsonParser p) throws IOException {
        JsonToken token = p.currentToken();
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String key = p.currentName();
                    p.nextToken();
                    map.put(key, value(p));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    list.add(value(p));
                }
                yield list;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> {
                BigInteger big = p.getBigIntegerValue();
                yield big.bitLength() < 64 ? (Object) big.longValue() : big;
            }
            case VALUE_NUMBER_FLOAT -> p.getDoubleValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new IOException("Unexpected " + token + " at " + p.currentLocation());
        };
    }
}
