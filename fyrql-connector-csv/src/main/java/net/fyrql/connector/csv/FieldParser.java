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

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.DataType.Scalar;
import net.fyrql.core.type.Values;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.ToLongFunction;

/**
 * Reads the fields of one column into a vector, straight from the
 * characters of the CSV reader where it can. Only the plain form of a value
 * takes the fast path: for the integral types up to {@code BIGINT} an
 * optional {@code -} and at most 18 digits; for a {@code DECIMAL} of at most
 * 18 digits an optional {@code -}, digits, and a {@code .} with at most as
 * many digits as the scale; for a {@code DATE} exactly {@code yyyy-mm-dd};
 * for a {@code BOOLEAN} the words of {@link Casts#parse}; for a
 * {@code VARCHAR} ASCII text. Every other field, and every field of another
 * type, is made a string and read by {@link Casts#parse}, so that the value
 * and the error are always those of the string. A {@code DATE} or a
 * {@code TIMESTAMP} with a format of {@code strptime} is read in that format
 * only.
 */
abstract class FieldParser {

    private static final long[] POWERS_OF_TEN = new long[19];

    static {
        POWERS_OF_TEN[0] = 1;
        for (int i = 1; i < POWERS_OF_TEN.length; i++) {
            POWERS_OF_TEN[i] = POWERS_OF_TEN[i - 1] * 10;
        }
    }

    private final DataType type;
    private final char[] nullString;
    private final int capacity;
    private Vector.Builder current;
    private ZoneId zone = ZoneOffset.UTC;

    private FieldParser(DataType type, String nullString, int capacity) {
        this.type = type;
        this.nullString = nullString.toCharArray();
        this.capacity = capacity;
    }

    /** Returns a parser for a type that reads the given string as {@code NULL}, with a fresh builder. */
    static FieldParser of(DataType type, String nullString, int capacity) {
        FieldParser parser = create(type, nullString, capacity);
        parser.start();
        return parser;
    }

    /**
     * Returns a parser as {@link #of(DataType, String, int)} does, except
     * that a {@code DATE} or a {@code TIMESTAMP} is read in a format, if
     * one is given.
     *
     * @param dateFormat reads a date as the microseconds of its start, or {@code null}
     * @param timestampFormat reads a timestamp as microseconds, or {@code null}
     * @param zone the time zone in which a text without an offset is read
     *        as a {@code TIMESTAMP WITH TIME ZONE}
     */
    static FieldParser of(DataType type, String nullString, int capacity, ToLongFunction<String> dateFormat,
            ToLongFunction<String> timestampFormat, ZoneId zone) {
        ToLongFunction<String> format = type == DataType.DATE ? dateFormat
                : type == DataType.TIMESTAMP ? timestampFormat : null;
        FieldParser parser = format == null ? create(type, nullString, capacity)
                : new FormatParser(type, nullString, capacity, format);
        parser.zone = zone;
        parser.start();
        return parser;
    }

    private static FieldParser create(DataType type, String nullString, int capacity) {
        if (type instanceof Decimal d) {
            return d.fitsLong() ? new DecimalParser(d, nullString, capacity) : new StringParser(type, nullString,
                    capacity);
        }
        if (!(type instanceof Scalar s)) {
            return new StringParser(type, nullString, capacity);
        }
        return switch (s) {
            case TINYINT, SMALLINT, INTEGER, BIGINT -> new IntegralParser(s, nullString, capacity);
            case DATE -> new DateParser(nullString, capacity);
            case BOOLEAN -> new BooleanParser(nullString, capacity);
            case VARCHAR -> new VarcharParser(nullString, capacity);
            default -> new StringParser(type, nullString, capacity);
        };
    }

    /** Appends a field: {@code NULL} for the null string, otherwise its value. */
    final void parse(char[] buf, int offset, int length) {
        if (isNull(buf, offset, length)) {
            current.appendNull();
        } else if (!fast(buf, offset, length)) {
            current.append(Casts.parse(new String(buf, offset, length), type, zone));
        }
    }

    /** Returns the vector of the fields so far and starts a new one. */
    final Vector finish() {
        Vector v = current.build();
        start();
        return v;
    }

    private void start() {
        current = Vectors.builder(type, capacity);
        started(current);
    }

    /** Takes the new builder, typed. */
    abstract void started(Vector.Builder builder);

    /**
     * Appends a field in the plain form of the type.
     *
     * @return {@code false}, with nothing appended, if the field is in another form
     */
    abstract boolean fast(char[] buf, int offset, int length);

    private boolean isNull(char[] buf, int offset, int length) {
        if (length != nullString.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (buf[offset + i] != nullString[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** {@code TINYINT}, {@code SMALLINT}, {@code INTEGER} and {@code BIGINT}. */
    private static final class IntegralParser extends FieldParser {
        private final Scalar scalar;
        private final long min;
        private final long max;
        private Vector.Builder builder;

        IntegralParser(Scalar type, String nullString, int capacity) {
            super(type, nullString, capacity);
            this.scalar = type;
            this.min = Values.min(type);
            this.max = Values.max(type);
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            int i = offset;
            int end = offset + length;
            boolean negative = i < end && buf[i] == '-';
            if (negative) {
                i++;
            }
            if (end - i == 0 || end - i > 18) {
                return false;
            }
            long v = 0;
            for (; i < end; i++) {
                char c = buf[i];
                if (!isDigit(c)) {
                    return false;
                }
                v = v * 10 + (c - '0');
            }
            if (negative) {
                v = -v;
            }
            if (v < min || v > max) {
                return false;
            }
            switch (scalar) {
                case TINYINT -> ((TinyIntVector.Builder) builder).appendByte((byte) v);
                case SMALLINT -> ((SmallIntVector.Builder) builder).appendShort((short) v);
                case INTEGER -> ((IntVector.Builder) builder).appendInt((int) v);
                default -> ((BigIntVector.Builder) builder).appendLong(v);
            }
            return true;
        }
    }

    /** {@code DECIMAL} of at most 18 digits, appended unscaled. */
    private static final class DecimalParser extends FieldParser {
        private final int scale;
        private final int integerDigits;
        private DecimalVector.Builder builder;

        DecimalParser(Decimal type, String nullString, int capacity) {
            super(type, nullString, capacity);
            this.scale = type.scale();
            this.integerDigits = type.precision() - type.scale();
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = (DecimalVector.Builder) builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            int i = offset;
            int end = offset + length;
            boolean negative = i < end && buf[i] == '-';
            if (negative) {
                i++;
            }
            boolean anyDigit = false;
            long integer = 0;
            int significant = 0;
            for (; i < end && isDigit(buf[i]); i++) {
                int d = buf[i] - '0';
                anyDigit = true;
                if (significant > 0 || d != 0) {
                    if (++significant > integerDigits) {
                        return false;
                    }
                }
                integer = integer * 10 + d;
            }
            long fraction = 0;
            int fractionDigits = 0;
            if (i < end && buf[i] == '.') {
                for (i++; i < end && isDigit(buf[i]); i++) {
                    anyDigit = true;
                    if (++fractionDigits > scale) {
                        return false;
                    }
                    fraction = fraction * 10 + (buf[i] - '0');
                }
            }
            if (i != end || !anyDigit) {
                return false;
            }
            long unscaled = integer * POWERS_OF_TEN[scale] + fraction * POWERS_OF_TEN[scale - fractionDigits];
            builder.appendUnscaled(negative ? -unscaled : unscaled);
            return true;
        }
    }

    /** {@code DATE} as days since 1970-01-01. */
    private static final class DateParser extends FieldParser {
        private IntVector.Builder builder;

        DateParser(String nullString, int capacity) {
            super(DataType.DATE, nullString, capacity);
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = (IntVector.Builder) builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            if (length != 10 || buf[offset + 4] != '-' || buf[offset + 7] != '-') {
                return false;
            }
            int year = digits(buf, offset, 4);
            int month = digits(buf, offset + 5, 2);
            int day = digits(buf, offset + 8, 2);
            if (year < 0 || month < 1 || month > 12 || day < 1 || day > lengthOfMonth(year, month)) {
                return false;
            }
            builder.appendInt(epochDay(year, month, day));
            return true;
        }

        /** Returns the number written by the digits, or -1 if one is not a digit. */
        private static int digits(char[] buf, int offset, int count) {
            int v = 0;
            for (int i = offset; i < offset + count; i++) {
                if (!isDigit(buf[i])) {
                    return -1;
                }
                v = v * 10 + (buf[i] - '0');
            }
            return v;
        }

        private static int lengthOfMonth(int year, int month) {
            return switch (month) {
                case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
                case 4, 6, 9, 11 -> 30;
                default -> 31;
            };
        }

        /** The days since 1970-01-01 of a date of the proleptic Gregorian calendar with a year from 0 to 9999. */
        private static int epochDay(int year, int month, int day) {
            int y = month <= 2 ? year - 1 : year;
            int era = Math.floorDiv(y, 400);
            int yearOfEra = y - era * 400;
            int dayOfYear = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
            int dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
            return era * 146_097 + dayOfEra - 719_468;
        }
    }

    /** {@code BOOLEAN}: the words in any case of ASCII letters, and {@code 1} and {@code 0}. */
    private static final class BooleanParser extends FieldParser {
        private BooleanVector.Builder builder;

        BooleanParser(String nullString, int capacity) {
            super(DataType.BOOLEAN, nullString, capacity);
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = (BooleanVector.Builder) builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            if (is(buf, offset, length, "true") || is(buf, offset, length, "t") || is(buf, offset, length, "yes")
                    || is(buf, offset, length, "y") || is(buf, offset, length, "1")) {
                builder.appendBoolean(true);
                return true;
            }
            if (is(buf, offset, length, "false") || is(buf, offset, length, "f") || is(buf, offset, length, "no")
                    || is(buf, offset, length, "n") || is(buf, offset, length, "0")) {
                builder.appendBoolean(false);
                return true;
            }
            return false;
        }

        /** Tells whether the field is a word of lower-case letters or digits, in any case of ASCII letters. */
        private static boolean is(char[] buf, int offset, int length, String word) {
            if (length != word.length()) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                char w = word.charAt(i);
                char c = buf[offset + i];
                if (c != w && !(w >= 'a' && w <= 'z' && c == w - ('a' - 'A'))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** {@code VARCHAR}: ASCII text copied as bytes; other text is encoded as a string is. */
    private static final class VarcharParser extends FieldParser {
        private BytesVector.Builder builder;
        private byte[] bytes = new byte[64];

        VarcharParser(String nullString, int capacity) {
            super(DataType.VARCHAR, nullString, capacity);
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = (BytesVector.Builder) builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            if (length > bytes.length) {
                bytes = new byte[Math.max(length, bytes.length * 2)];
            }
            for (int i = 0; i < length; i++) {
                char c = buf[offset + i];
                if (c >= 0x80) {
                    return false;
                }
                bytes[i] = (byte) c;
            }
            builder.appendBytes(bytes, 0, length);
            return true;
        }
    }

    /** {@code DATE} or {@code TIMESTAMP} in a format of {@code strptime}; a field in another form is an error. */
    private static final class FormatParser extends FieldParser {
        private final ToLongFunction<String> format;
        private final boolean date;
        private Vector.Builder builder;

        FormatParser(DataType type, String nullString, int capacity, ToLongFunction<String> format) {
            super(type, nullString, capacity);
            this.format = format;
            this.date = type == DataType.DATE;
        }

        @Override
        void started(Vector.Builder builder) {
            this.builder = builder;
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            long micros = format.applyAsLong(new String(buf, offset, length));
            if (date) {
                ((IntVector.Builder) builder).appendInt(Conversions.timestampToDate(micros));
            } else {
                ((BigIntVector.Builder) builder).appendLong(micros);
            }
            return true;
        }
    }

    /** Every other type: always read from a string. */
    private static final class StringParser extends FieldParser {

        StringParser(DataType type, String nullString, int capacity) {
            super(type, nullString, capacity);
        }

        @Override
        void started(Vector.Builder builder) {
        }

        @Override
        boolean fast(char[] buf, int offset, int length) {
            return false;
        }
    }
}
