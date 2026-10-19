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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Writes JSON values as text, as DuckDB writes its type {@code JSON}:
 * without whitespace, strings with only {@code "}, {@code \} and control
 * characters escaped, numbers with a fraction in the shortest form that
 * reads back as the same double, in decimal notation if the exponent lies
 * from -6 to 20 and with a {@code .0} if they are whole, otherwise as
 * {@code 1.5e-7}.
 */
final class JsonValues {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonValues() {
    }

    /** Returns the text of the value at the current token and moves to its last token. */
    static String text(JsonParser p) throws IOException {
        StringBuilder b = new StringBuilder();
        write(p, b);
        return b.toString();
    }

    private static void write(JsonParser p, StringBuilder b) throws IOException {
        JsonToken t = p.currentToken();
        switch (t) {
            case START_OBJECT -> {
                b.append('{');
                boolean first = true;
                String name;
                while ((name = p.nextFieldName()) != null) {
                    if (!first) {
                        b.append(',');
                    }
                    first = false;
                    string(name, b);
                    b.append(':');
                    p.nextToken();
                    write(p, b);
                }
                b.append('}');
            }
            case START_ARRAY -> {
                b.append('[');
                boolean first = true;
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    if (!first) {
                        b.append(',');
                    }
                    first = false;
                    write(p, b);
                }
                b.append(']');
            }
            case VALUE_STRING -> string(p.getText(), b);
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> b.append(number(p));
            case VALUE_TRUE -> b.append("true");
            case VALUE_FALSE -> b.append("false");
            case VALUE_NULL -> b.append("null");
            default -> throw new IllegalStateException("Not the start of a value: " + t);
        }
    }

    /** Returns the text of the number at the current token. */
    static String number(JsonParser p) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return p.getText();
        }
        return formatDouble(p.getDoubleValue());
    }

    /** Formats a double as DuckDB writes it in JSON text. */
    static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return Double.toString(d);
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        BigDecimal v = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = v.unscaledValue().toString();
        int exponent = digits.length() - 1 - v.scale();
        String sign = d < 0 ? "-" : "";
        if (exponent >= -6 && exponent <= 20) {
            String plain = v.toPlainString();
            return sign + (plain.indexOf('.') < 0 ? plain + ".0" : plain);
        }
        return sign + digits.charAt(0) + (digits.length() > 1 ? "." + digits.substring(1) : "") + "e" + exponent;
    }

    /** Writes a string as a JSON string. */
    static void string(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 0xf]);
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
