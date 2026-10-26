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

package net.fyrql.core.value;

import net.fyrql.core.type.DataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The names of the types as DuckDB 1.5.6 reads them, with all their
 * aliases, case-insensitive and optionally in double quotes:
 * {@code DECIMAL} without parameters is {@code DECIMAL(18,3)} and with one
 * {@code DECIMAL(p,0)}; {@code FLOAT(p)} is {@code FLOAT} up to 24 bits and
 * {@code DOUBLE} above; the length of a string type is ignored; other
 * scalar types take no parameters. The nested types nest to any depth:
 * {@code T[]} is a list of {@code T}, {@code STRUCT(name T, ...)} (or
 * {@code ROW(...)}) a struct, whose field names keep their spelling and may
 * be in double quotes, and {@code MAP(K, V)} a map. The DuckDB types FyrQL
 * does not have, such as {@code TIME WITH TIME ZONE}, {@code UNION},
 * the unsigned integers or the arrays of a fixed size {@code T[n]}, are
 * errors.
 */
public final class TypeNames {

    private static final Map<String, DataType> SIMPLE = Map.ofEntries(
            Map.entry("BOOLEAN", DataType.BOOLEAN), Map.entry("BOOL", DataType.BOOLEAN),
            Map.entry("LOGICAL", DataType.BOOLEAN),
            Map.entry("TINYINT", DataType.TINYINT), Map.entry("INT1", DataType.TINYINT),
            Map.entry("SMALLINT", DataType.SMALLINT), Map.entry("INT2", DataType.SMALLINT),
            Map.entry("SHORT", DataType.SMALLINT),
            Map.entry("INTEGER", DataType.INTEGER), Map.entry("INT4", DataType.INTEGER),
            Map.entry("INT", DataType.INTEGER), Map.entry("SIGNED", DataType.INTEGER),
            Map.entry("BIGINT", DataType.BIGINT), Map.entry("INT8", DataType.BIGINT),
            Map.entry("LONG", DataType.BIGINT),
            Map.entry("HUGEINT", DataType.HUGEINT), Map.entry("INT128", DataType.HUGEINT),
            Map.entry("FLOAT4", DataType.FLOAT), Map.entry("REAL", DataType.FLOAT),
            Map.entry("DOUBLE", DataType.DOUBLE), Map.entry("FLOAT8", DataType.DOUBLE),
            Map.entry("DOUBLE PRECISION", DataType.DOUBLE),
            Map.entry("BLOB", DataType.BLOB), Map.entry("BYTEA", DataType.BLOB), Map.entry("BINARY", DataType.BLOB),
            Map.entry("VARBINARY", DataType.BLOB),
            Map.entry("DATE", DataType.DATE),
            Map.entry("TIMESTAMP", DataType.TIMESTAMP), Map.entry("DATETIME", DataType.TIMESTAMP),
            Map.entry("TIMESTAMP_US", DataType.TIMESTAMP),
            Map.entry("TIMESTAMP WITHOUT TIME ZONE", DataType.TIMESTAMP),
            Map.entry("TIMESTAMPTZ", DataType.TIMESTAMPTZ),
            Map.entry("TIMESTAMP WITH TIME ZONE", DataType.TIMESTAMPTZ),
            Map.entry("TIME", DataType.TIME), Map.entry("TIME WITHOUT TIME ZONE", DataType.TIME),
            Map.entry("INTERVAL", DataType.INTERVAL),
            Map.entry("UUID", DataType.UUID), Map.entry("GUID", DataType.UUID));

    private static final List<String> STRINGS = List.of("VARCHAR", "CHAR", "BPCHAR", "TEXT", "STRING", "NVARCHAR",
            "CHARACTER", "CHARACTER VARYING");

    private static final List<String> DECIMALS = List.of("DECIMAL", "NUMERIC", "DEC");

    private TypeNames() {
    }

    /**
     * Returns the type of a name such as {@code integer}, {@code DECIMAL(10, 2)},
     * {@code "VARCHAR"(20)}, {@code INTEGER[][]} or
     * {@code STRUCT(a INTEGER, "b c" MAP(VARCHAR, DOUBLE[]))}.
     *
     * @throws IllegalArgumentException with a message for the user if the
     *         name is not a type of FyrQL
     */
    public static DataType parse(String text) {
        Reader reader = new Reader(text);
        DataType type = reader.type();
        reader.skipSpace();
        if (!reader.atEnd()) {
            throw reader.invalid();
        }
        return type;
    }

    /** Reads a type name from left to right. */
    private static final class Reader {

        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipSpace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        /** Tells whether the next character, after spaces, is the given one, and takes it if so. */
        boolean take(char c) {
            skipSpace();
            if (!atEnd() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!take(c)) {
                throw invalid();
            }
        }

        IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid type name: " + text.strip());
        }

        /** A type: a scalar or nested type, then {@code []} for each level of lists. */
        DataType type() {
            DataType type = base();
            while (take('[')) {
                if (!take(']')) {
                    throw new IllegalArgumentException("Arrays of a fixed size are not supported: " + text.strip());
                }
                type = DataType.listOf(type);
            }
            return type;
        }

        private DataType base() {
            List<String> words = new ArrayList<>();
            for (String w = word(); w != null; w = word()) {
                words.add(w);
            }
            if (words.isEmpty()) {
                throw invalid();
            }
            String name = String.join(" ", words);
            skipSpace();
            boolean parenthesized = !atEnd() && text.charAt(pos) == '(';
            if (parenthesized && (name.equals("STRUCT") || name.equals("ROW"))) {
                return struct();
            }
            if (parenthesized && name.equals("MAP")) {
                return map();
            }
            List<Integer> parameters = new ArrayList<>();
            if (take('(')) {
                do {
                    parameters.add(integer());
                } while (take(','));
                expect(')');
            }
            return resolve(name, parameters, parenthesized);
        }

        /** The next word of a type name, in upper case, without double quotes; {@code null} if none follows. */
        private String word() {
            skipSpace();
            if (atEnd()) {
                return null;
            }
            char c = text.charAt(pos);
            if (c == '"') {
                return quoted().toUpperCase(Locale.ROOT);
            }
            if (!Character.isLetter(c) && c != '_') {
                return null;
            }
            int start = pos;
            while (!atEnd() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            return text.substring(start, pos).toUpperCase(Locale.ROOT);
        }

        /** A name in double quotes, with a doubled quote for a quote. */
        private String quoted() {
            StringBuilder b = new StringBuilder();
            pos++;
            while (true) {
                int end = text.indexOf('"', pos);
                if (end < 0) {
                    throw invalid();
                }
                b.append(text, pos, end);
                pos = end + 1;
                if (atEnd() || text.charAt(pos) != '"') {
                    break;
                }
                b.append('"');
                pos++;
            }
            if (b.isEmpty()) {
                throw invalid();
            }
            return b.toString();
        }

        private int integer() {
            skipSpace();
            int start = pos;
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            try {
                return Integer.parseInt(text.substring(start, pos));
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        /** {@code STRUCT(name type, ...)}, after the keyword. */
        private DataType struct() {
            expect('(');
            List<DataType.Struct.Field> fields = new ArrayList<>();
            Set<String> names = new HashSet<>();
            do {
                String name = fieldName();
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate STRUCT type argument name \"" + name + "\"");
                }
                fields.add(new DataType.Struct.Field(name, type()));
            } while (take(','));
            expect(')');
            return new DataType.Struct(fields);
        }

        /** The name of a field: a plain identifier as written, or a name in double quotes. */
        private String fieldName() {
            skipSpace();
            if (!atEnd() && text.charAt(pos) == '"') {
                return quoted();
            }
            int start = pos;
            while (!atEnd() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos || Character.isDigit(text.charAt(start))) {
                throw invalid();
            }
            return text.substring(start, pos);
        }

        /** {@code MAP(key, value)}, after the keyword. */
        private DataType map() {
            expect('(');
            List<DataType> types = new ArrayList<>();
            do {
                types.add(type());
            } while (take(','));
            expect(')');
            if (types.size() != 2) {
                throw new IllegalArgumentException(
                        "MAP type requires exactly two type modifiers: key type and value type");
            }
            return DataType.mapOf(types.get(0), types.get(1));
        }
    }

    private static DataType resolve(String name, List<Integer> parameters, boolean parenthesized) {
        if (DECIMALS.contains(name)) {
            if (!parenthesized) {
                return DataType.decimal(18, 3);
            }
            if (parameters.size() > 2) {
                throw new IllegalArgumentException("A DECIMAL type takes at most two parameters");
            }
            int width = parameters.get(0);
            int scale = parameters.size() > 1 ? parameters.get(1) : 0;
            if (width < 1 || width > DataType.MAX_DECIMAL_PRECISION) {
                throw new IllegalArgumentException("DECIMAL type width must be between 1 and "
                        + DataType.MAX_DECIMAL_PRECISION);
            }
            if (scale < 0 || scale > width) {
                throw new IllegalArgumentException("DECIMAL type scale cannot be greater than width");
            }
            return DataType.decimal(width, scale);
        }
        if (STRINGS.contains(name)) {
            if (parameters.size() > 1) {
                throw new IllegalArgumentException("Type " + name + " takes at most one parameter");
            }
            return DataType.VARCHAR;
        }
        if (name.equals("FLOAT")) {
            if (!parenthesized) {
                return DataType.FLOAT;
            }
            int bits = parameters.size() == 1 ? parameters.get(0) : -1;
            if (bits < 1 || bits > 53) {
                throw new IllegalArgumentException("FLOAT takes one parameter between 1 and 53");
            }
            return bits <= 24 ? DataType.FLOAT : DataType.DOUBLE;
        }
        DataType type = SIMPLE.get(name);
        if (type == null) {
            throw new IllegalArgumentException("Type " + name + " is not supported");
        }
        if (parenthesized) {
            throw new IllegalArgumentException("Type " + name + " does not take any type parameters");
        }
        return type;
    }
}
