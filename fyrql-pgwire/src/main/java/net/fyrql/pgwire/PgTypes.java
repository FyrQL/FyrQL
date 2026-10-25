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

package net.fyrql.pgwire;

import net.fyrql.core.type.DataType;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Interval;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The types of PostgreSQL that the server gives the types of FyrQL, and
 * their text and binary formats, for results and for parameters. A
 * {@code VARCHAR} is a {@code text}, a {@code TINYINT} an {@code int2}, a
 * {@code HUGEINT} and a {@code DECIMAL} a {@code numeric}, a {@code BLOB} a
 * {@code bytea}, a {@code LIST} of a scalar type an array; nested lists, a
 * {@code STRUCT} and a {@code MAP} are a {@code text} in the text of FyrQL.
 */
final class PgTypes {

    static final int BOOL = 16;
    static final int BYTEA = 17;
    static final int CHAR = 18;
    static final int NAME = 19;
    static final int INT8 = 20;
    static final int INT2 = 21;
    static final int INT2VECTOR = 22;
    static final int INT4 = 23;
    static final int REGPROC = 24;
    static final int TEXT = 25;
    static final int OID = 26;
    static final int XID = 28;
    static final int OIDVECTOR = 30;
    static final int JSON = 114;
    static final int PG_NODE_TREE = 194;
    static final int FLOAT4 = 700;
    static final int FLOAT8 = 701;
    static final int UNKNOWN = 705;
    static final int ACLITEM = 1033;
    static final int BPCHAR = 1042;
    static final int VARCHAR = 1043;
    static final int DATE = 1082;
    static final int TIME = 1083;
    static final int TIMESTAMP = 1114;
    static final int TIMESTAMPTZ = 1184;
    static final int INTERVAL = 1186;
    static final int NUMERIC = 1700;
    static final int REGCLASS = 2205;
    static final int REGTYPE = 2206;
    static final int UUID_OID = 2950;
    static final int JSONB = 3802;

    /** The array types of the element types, by the element type. */
    static final Map<Integer, Integer> ARRAYS = Map.ofEntries(Map.entry(BOOL, 1000), Map.entry(BYTEA, 1001),
            Map.entry(CHAR, 1002), Map.entry(NAME, 1003), Map.entry(INT2, 1005), Map.entry(INT4, 1007),
            Map.entry(TEXT, 1009), Map.entry(BPCHAR, 1014), Map.entry(VARCHAR, 1015), Map.entry(INT8, 1016),
            Map.entry(FLOAT4, 1021), Map.entry(FLOAT8, 1022), Map.entry(OID, 1028), Map.entry(ACLITEM, 1034),
            Map.entry(TIMESTAMP, 1115), Map.entry(DATE, 1182), Map.entry(TIME, 1183), Map.entry(TIMESTAMPTZ, 1185),
            Map.entry(INTERVAL, 1187), Map.entry(NUMERIC, 1231), Map.entry(UUID_OID, 2951), Map.entry(JSON, 199),
            Map.entry(JSONB, 3807));

    /** Microseconds from 1970-01-01, where FyrQL counts from, to 2000-01-01, where PostgreSQL counts from. */
    private static final long MICROS_TO_2000 = 946_684_800_000_000L;
    /** Days from 1970-01-01 to 2000-01-01. */
    private static final int DAYS_TO_2000 = 10_957;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_DAY = 86_400L * MICROS_PER_SECOND;

    /** An offset or zone after a timestamp, which a timestamp without time zone drops. */
    private static final Pattern TRAILING_OFFSET = Pattern.compile(
            "\\s*(?:[+-]\\d{1,2}(?::?\\d{2}(?::?\\d{2})?)?|Z|UTC)$");

    private PgTypes() {
    }

    /**
     * A type of PostgreSQL as {@code RowDescription} names it.
     *
     * @param oid the OID of the type
     * @param length the length of its values, or -1 for a variable length
     * @param modifier its modifier, such as the precision and scale of a {@code numeric}, or -1
     */
    record PgType(int oid, short length, int modifier) {
    }

    /** Returns the type of PostgreSQL of a type of FyrQL. */
    static PgType of(DataType type) {
        return switch (type) {
            case DataType.Decimal d -> new PgType(NUMERIC, (short) -1, ((d.precision() << 16) | d.scale()) + 4);
            case DataType.ListType l when !l.element().isNested() -> new PgType(ARRAYS.get(of(l.element()).oid()),
                    (short) -1, -1);
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> new PgType(BOOL, (short) 1, -1);
                case TINYINT, SMALLINT -> new PgType(INT2, (short) 2, -1);
                case INTEGER -> new PgType(INT4, (short) 4, -1);
                case BIGINT -> new PgType(INT8, (short) 8, -1);
                case HUGEINT -> new PgType(NUMERIC, (short) -1, -1);
                case FLOAT -> new PgType(FLOAT4, (short) 4, -1);
                case DOUBLE -> new PgType(FLOAT8, (short) 8, -1);
                case VARCHAR -> new PgType(TEXT, (short) -1, -1);
                case BLOB -> new PgType(BYTEA, (short) -1, -1);
                case DATE -> new PgType(DATE, (short) 4, -1);
                case TIME -> new PgType(TIME, (short) 8, -1);
                case TIMESTAMP -> new PgType(TIMESTAMP, (short) 8, -1);
                case TIMESTAMPTZ -> new PgType(TIMESTAMPTZ, (short) 8, -1);
                case INTERVAL -> new PgType(INTERVAL, (short) 16, -1);
                case UUID -> new PgType(UUID_OID, (short) 16, -1);
            };
            default -> new PgType(TEXT, (short) -1, -1);
        };
    }

    /** Returns the OID of the type of PostgreSQL of a type of FyrQL. */
    static int oid(DataType type) {
        return of(type).oid();
    }

    /**
     * Returns the type of FyrQL that a parameter of a type of PostgreSQL
     * takes when the client names the type, or {@code null} if the server
     * leaves the type to the context, as for {@code numeric}, whose
     * precision the client does not say, and for {@code unknown}.
     */
    static DataType fyrqlType(int oid) {
        return switch (oid) {
            case BOOL -> DataType.BOOLEAN;
            case INT2 -> DataType.SMALLINT;
            case INT4 -> DataType.INTEGER;
            case INT8 -> DataType.BIGINT;
            case FLOAT4 -> DataType.FLOAT;
            case FLOAT8 -> DataType.DOUBLE;
            case TEXT, VARCHAR, BPCHAR, NAME -> DataType.VARCHAR;
            case BYTEA -> DataType.BLOB;
            case DATE -> DataType.DATE;
            case TIME -> DataType.TIME;
            case TIMESTAMP -> DataType.TIMESTAMP;
            case TIMESTAMPTZ -> DataType.TIMESTAMPTZ;
            case INTERVAL -> DataType.INTERVAL;
            case UUID_OID -> DataType.UUID;
            default -> {
                for (Map.Entry<Integer, Integer> e : ARRAYS.entrySet()) {
                    if (e.getValue() == oid) {
                        DataType element = fyrqlType(e.getKey());
                        yield element == null ? null : DataType.listOf(element);
                    }
                }
                yield null;
            }
        };
    }

    // ---------------------------------------------------------------- text

    /** Returns a value of a type of FyrQL in the text format of PostgreSQL. */
    static String text(Object value, DataType type, ZoneId zone) {
        return switch (type) {
            case DataType.Decimal d -> ((BigDecimal) value).toPlainString();
            case DataType.ListType l when !l.element().isNested() -> array((List<?>) value, l.element(), zone);
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> (Boolean) value ? "t" : "f";
                case TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT, VARCHAR -> value.toString();
                case FLOAT -> floating((Float) value, true);
                case DOUBLE -> floating((Double) value, false);
                case BLOB -> "\\x" + HexFormat.of().formatHex((byte[]) value);
                case DATE -> date((Integer) value);
                case TIME -> time((Long) value);
                case TIMESTAMP -> timestamp((Long) value);
                case TIMESTAMPTZ -> timestamptz((Long) value, zone);
                case INTERVAL -> interval((Interval) value);
                case UUID -> value.toString();
            };
            default -> Casts.format(value, type, zone);
        };
    }

    /**
     * A floating-point number as PostgreSQL writes it: the shortest digits
     * that read back as the same number, in exponent form below 1e-4 and
     * from 1e15 on (1e6 for a {@code float4}).
     */
    static String floating(double d, boolean float4) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0" : "0";
        }
        BigDecimal v = new BigDecimal(float4 ? Float.toString((float) d) : Double.toString(d)).stripTrailingZeros();
        int exponent = v.precision() - v.scale() - 1;
        if (exponent < -4 || exponent >= (float4 ? 6 : 15)) {
            String digits = v.unscaledValue().abs().toString();
            StringBuilder b = new StringBuilder();
            if (v.signum() < 0) {
                b.append('-');
            }
            b.append(digits.charAt(0));
            if (digits.length() > 1) {
                b.append('.').append(digits, 1, digits.length());
            }
            b.append('e').append(exponent < 0 ? '-' : '+');
            int e = Math.abs(exponent);
            if (e < 10) {
                b.append('0');
            }
            return b.append(e).toString();
        }
        return v.toPlainString();
    }

    /** A date as {@code 2024-01-02}, or {@code 0044-03-15 BC} before the year 1. */
    static String date(int days) {
        LocalDate d = LocalDate.ofEpochDay(days);
        return year(d.getYear()) + String.format(Locale.ROOT, "-%02d-%02d", d.getMonthValue(), d.getDayOfMonth())
                + era(d.getYear());
    }

    private static String year(int year) {
        int y = year > 0 ? year : 1 - year;
        return y < 1000 ? String.format(Locale.ROOT, "%04d", y) : Integer.toString(y);
    }

    private static String era(int year) {
        return year > 0 ? "" : " BC";
    }

    /** A time of day as {@code 04:05:06} with up to six digits of fractions. */
    static String time(long micros) {
        long seconds = micros / MICROS_PER_SECOND;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60));
        return fraction(b, micros % MICROS_PER_SECOND).toString();
    }

    private static StringBuilder fraction(StringBuilder b, long micros) {
        if (micros != 0) {
            String f = String.format(Locale.ROOT, "%06d", micros);
            int end = f.length();
            while (f.charAt(end - 1) == '0') {
                end--;
            }
            b.append('.').append(f, 0, end);
        }
        return b;
    }

    /** A timestamp as {@code 2024-01-02 03:04:05.5}. */
    static String timestamp(long micros) {
        long days = Math.floorDiv(micros, MICROS_PER_DAY);
        long rest = Math.floorMod(micros, MICROS_PER_DAY);
        LocalDate d = LocalDate.ofEpochDay(days);
        return dateWithoutEra(d) + " " + time(rest) + era(d.getYear());
    }

    private static String dateWithoutEra(LocalDate d) {
        return year(d.getYear()) + String.format(Locale.ROOT, "-%02d-%02d", d.getMonthValue(), d.getDayOfMonth());
    }

    /** A timestamp with time zone in a zone as {@code 2024-01-02 03:04:05+01}. */
    static String timestamptz(long micros, ZoneId zone) {
        Instant instant = Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                Math.floorMod(micros, MICROS_PER_SECOND) * 1000);
        ZoneOffset offset = zone.getRules().getOffset(instant);
        LocalDateTime local = LocalDateTime.ofInstant(instant, offset);
        long localMicros = local.toLocalTime().toNanoOfDay() / 1000;
        StringBuilder b = new StringBuilder(dateWithoutEra(local.toLocalDate())).append(' ').append(time(localMicros));
        int total = offset.getTotalSeconds();
        b.append(total < 0 ? '-' : '+');
        int abs = Math.abs(total);
        b.append(String.format(Locale.ROOT, "%02d", abs / 3600));
        if (abs % 3600 != 0) {
            b.append(String.format(Locale.ROOT, ":%02d", abs / 60 % 60));
            if (abs % 60 != 0) {
                b.append(String.format(Locale.ROOT, ":%02d", abs % 60));
            }
        }
        return b.append(era(local.getYear())).toString();
    }

    /** An interval in the style {@code postgres}, as {@code 1 year 2 mons 3 days 04:05:06.5}. */
    static String interval(Interval interval) {
        StringBuilder b = new StringBuilder();
        int years = interval.months() / 12;
        int months = interval.months() % 12;
        boolean negative = false;
        negative |= part(b, years, "year");
        negative |= part(b, months, "mon");
        negative |= part(b, interval.days(), "day");
        long micros = interval.micros();
        if (micros != 0 || b.isEmpty()) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            if (micros < 0) {
                b.append('-');
            } else if (negative) {
                b.append('+');
            }
            long abs = Math.abs(micros);
            long seconds = abs / MICROS_PER_SECOND;
            b.append(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
            fraction(b, abs % MICROS_PER_SECOND);
        }
        return b.toString();
    }

    /** Appends a part of an interval and tells whether it is negative. */
    private static boolean part(StringBuilder b, long n, String unit) {
        if (n == 0) {
            return false;
        }
        if (!b.isEmpty()) {
            b.append(' ');
        }
        b.append(n).append(' ').append(unit);
        if (n != 1) {
            b.append('s');
        }
        return n < 0;
    }

    /** An array as {@code {1,2,NULL}}, its elements quoted where PostgreSQL quotes them. */
    static String array(List<?> list, DataType element, ZoneId zone) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Object e = list.get(i);
            if (e == null) {
                b.append("NULL");
                continue;
            }
            String t = text(e, element, zone);
            if (needsQuotes(t)) {
                b.append('"');
                for (int k = 0; k < t.length(); k++) {
                    char c = t.charAt(k);
                    if (c == '"' || c == '\\') {
                        b.append('\\');
                    }
                    b.append(c);
                }
                b.append('"');
            } else {
                b.append(t);
            }
        }
        return b.append('}').toString();
    }

    private static boolean needsQuotes(String t) {
        if (t.isEmpty() || t.equalsIgnoreCase("NULL")) {
            return true;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' || c == '}' || c == ',' || c == '"' || c == '\\' || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a parameter in the text format of PostgreSQL as a value of a type
     * of FyrQL: {@code t}, {@code true}, {@code yes}, {@code on} and
     * {@code 1} and their opposites for a {@code BOOLEAN}, {@code \x} and hex
     * digits or the escape format for a {@code BLOB}, an array literal for a
     * {@code LIST}; a timestamp, date or time without time zone drops an
     * offset after it, as PostgreSQL does; everything else as a cast from
     * {@code VARCHAR}.
     *
     * @throws IllegalArgumentException if the text is no value of the type
     */
    static Object parseText(String s, DataType type, ZoneId zone) {
        return switch (type) {
            case DataType.ListType l -> parseArray(s, l.element(), zone);
            case DataType.Scalar sc when sc == DataType.BOOLEAN -> parseBoolean(s);
            case DataType.Scalar sc when sc == DataType.BLOB -> parseBytea(s);
            case DataType.Scalar sc when sc == DataType.TIMESTAMP || sc == DataType.TIME ->
                    Casts.parse(withoutOffset(s), type, zone);
            case DataType.Scalar sc when sc == DataType.DATE -> Casts.parse(dateOnly(s), type, zone);
            default -> Casts.parse(s, type, zone);
        };
    }

    private static String withoutOffset(String s) {
        String t = s.strip();
        // A date alone has no offset, though its last part looks like one.
        return t.length() <= 10 ? t : TRAILING_OFFSET.matcher(t).replaceFirst("");
    }

    private static String dateOnly(String s) {
        String t = s.strip();
        int space = t.indexOf(' ');
        return space > 0 && !t.endsWith(" BC") ? t.substring(0, space) : t;
    }

    static Boolean parseBoolean(String s) {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "t", "true", "y", "yes", "on", "1" -> true;
            case "f", "false", "n", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("invalid input syntax for type boolean: \"" + s + "\"");
        };
    }

    /** Reads a {@code bytea} as {@code \x0102} or in the escape format with {@code \\} and {@code \ooo}. */
    static byte[] parseBytea(String s) {
        if (s.startsWith("\\x") || s.startsWith("\\X")) {
            String hex = s.substring(2).replaceAll("\\s", "");
            try {
                return HexFormat.of().parseHex(hex);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid hexadecimal data in bytea: \"" + s + "\"", e);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte c = bytes[i];
            if (c != '\\') {
                out.write(c);
            } else if (i + 1 < bytes.length && bytes[i + 1] == '\\') {
                out.write('\\');
                i++;
            } else if (i + 3 < bytes.length && isOctal(bytes[i + 1]) && isOctal(bytes[i + 2])
                    && isOctal(bytes[i + 3])) {
                out.write((bytes[i + 1] - '0') * 64 + (bytes[i + 2] - '0') * 8 + (bytes[i + 3] - '0'));
                i += 3;
            } else {
                throw new IllegalArgumentException("invalid input syntax for type bytea");
            }
        }
        return out.toByteArray();
    }

    private static boolean isOctal(byte b) {
        return b >= '0' && b <= '7';
    }

    /** Reads an array literal of one dimension, such as {@code {1,"a b",NULL}}. */
    static List<Object> parseArray(String s, DataType element, ZoneId zone) {
        String t = s.strip();
        if (t.length() < 2 || t.charAt(0) != '{' || t.charAt(t.length() - 1) != '}') {
            throw new IllegalArgumentException("malformed array literal: \"" + s + "\"");
        }
        List<Object> result = new ArrayList<>();
        int i = 1;
        int end = t.length() - 1;
        if (t.substring(1, end).isBlank()) {
            return result;
        }
        while (i <= end) {
            while (i < end && Character.isWhitespace(t.charAt(i))) {
                i++;
            }
            StringBuilder b = new StringBuilder();
            boolean quoted = i < end && t.charAt(i) == '"';
            if (quoted) {
                i++;
                while (i < end && t.charAt(i) != '"') {
                    if (t.charAt(i) == '\\' && i + 1 < end) {
                        i++;
                    }
                    b.append(t.charAt(i++));
                }
                i++;
                while (i < end && Character.isWhitespace(t.charAt(i))) {
                    i++;
                }
            } else {
                while (i < end && t.charAt(i) != ',') {
                    if (t.charAt(i) == '{') {
                        throw new IllegalArgumentException("Not supported: arrays of more than one dimension");
                    }
                    b.append(t.charAt(i++));
                }
            }
            String value = quoted ? b.toString() : b.toString().strip();
            result.add(!quoted && value.equalsIgnoreCase("NULL") ? null : parseText(value, element, zone));
            if (i < end && t.charAt(i) != ',') {
                throw new IllegalArgumentException("malformed array literal: \"" + s + "\"");
            }
            i++;
        }
        return result;
    }

    // -------------------------------------------------------------- binary

    /** Returns a value of a type of FyrQL in the binary format of its type of PostgreSQL. */
    static byte[] binary(Object value, DataType type, ZoneId zone, Charset charset) {
        return switch (type) {
            case DataType.Decimal d -> numeric((BigDecimal) value);
            case DataType.ListType l when !l.element().isNested() -> binaryArray((List<?>) value, l.element(), zone,
                    charset);
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> new byte[] {(byte) ((Boolean) value ? 1 : 0)};
                case TINYINT -> ByteBuffer.allocate(2).putShort((Byte) value).array();
                case SMALLINT -> ByteBuffer.allocate(2).putShort((Short) value).array();
                case INTEGER -> ByteBuffer.allocate(4).putInt((Integer) value).array();
                case BIGINT -> ByteBuffer.allocate(8).putLong((Long) value).array();
                case HUGEINT -> numeric(new BigDecimal((BigInteger) value));
                case FLOAT -> ByteBuffer.allocate(4).putFloat((Float) value).array();
                case DOUBLE -> ByteBuffer.allocate(8).putDouble((Double) value).array();
                case VARCHAR -> ((String) value).getBytes(charset);
                case BLOB -> (byte[]) value;
                case DATE -> ByteBuffer.allocate(4).putInt((Integer) value - DAYS_TO_2000).array();
                case TIME -> ByteBuffer.allocate(8).putLong((Long) value).array();
                case TIMESTAMP, TIMESTAMPTZ -> ByteBuffer.allocate(8).putLong((Long) value - MICROS_TO_2000).array();
                case INTERVAL -> {
                    Interval i = (Interval) value;
                    yield ByteBuffer.allocate(16).putLong(i.micros()).putInt(i.days()).putInt(i.months()).array();
                }
                case UUID -> {
                    UUID u = (UUID) value;
                    yield ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                            .putLong(u.getLeastSignificantBits()).array();
                }
            };
            default -> Casts.format(value, type, zone).getBytes(charset);
        };
    }

    /** A {@code numeric} in binary: digits of base 10000 with the weight of the first, the sign and the scale. */
    static byte[] numeric(BigDecimal value) {
        int scale = Math.max(0, value.scale());
        String plain = value.abs().setScale(scale).toPlainString();
        int dot = plain.indexOf('.');
        String integral = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot + 1);
        integral = "0".repeat((4 - integral.length() % 4) % 4) + integral;
        fraction = fraction + "0".repeat((4 - fraction.length() % 4) % 4);
        List<Short> digits = new ArrayList<>();
        for (int i = 0; i < integral.length(); i += 4) {
            digits.add(Short.parseShort(integral.substring(i, i + 4)));
        }
        int weight = digits.size() - 1;
        for (int i = 0; i < fraction.length(); i += 4) {
            digits.add(Short.parseShort(fraction.substring(i, i + 4)));
        }
        int first = 0;
        while (first < digits.size() && digits.get(first) == 0) {
            first++;
            weight--;
        }
        int last = digits.size();
        while (last > first && digits.get(last - 1) == 0) {
            last--;
        }
        int n = last - first;
        ByteBuffer b = ByteBuffer.allocate(8 + 2 * n);
        b.putShort((short) n).putShort((short) (n == 0 ? 0 : weight))
                .putShort((short) (value.signum() < 0 ? 0x4000 : 0)).putShort((short) scale);
        for (int i = first; i < last; i++) {
            b.putShort(digits.get(i));
        }
        return b.array();
    }

    /** Reads a {@code numeric} in binary. */
    static BigDecimal numeric(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int n = b.getShort();
        int weight = b.getShort();
        int sign = b.getShort() & 0xFFFF;
        int scale = b.getShort();
        if (sign == 0xC000) {
            throw new IllegalArgumentException("FyrQL has no numeric NaN");
        }
        if (sign == 0xD000 || sign == 0xF000) {
            throw new IllegalArgumentException("FyrQL has no numeric infinity");
        }
        BigDecimal v = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            v = v.add(BigDecimal.valueOf(b.getShort()).scaleByPowerOfTen(4 * (weight - i)));
        }
        v = v.setScale(scale, RoundingMode.UNNECESSARY);
        return sign == 0x4000 ? v.negate() : v;
    }

    private static byte[] binaryArray(List<?> list, DataType element, ZoneId zone, Charset charset) {
        int oid = oid(element);
        if (list.isEmpty()) {
            return ByteBuffer.allocate(12).putInt(0).putInt(0).putInt(oid).array();
        }
        List<byte[]> elements = new ArrayList<>();
        boolean nulls = false;
        int size = 20;
        for (Object e : list) {
            byte[] bytes = e == null ? null : binary(e, element, zone, charset);
            nulls |= e == null;
            size += 4 + (bytes == null ? 0 : bytes.length);
            elements.add(bytes);
        }
        ByteBuffer b = ByteBuffer.allocate(size).putInt(1).putInt(nulls ? 1 : 0).putInt(oid).putInt(list.size())
                .putInt(1);
        for (byte[] e : elements) {
            if (e == null) {
                b.putInt(-1);
            } else {
                b.putInt(e.length).put(e);
            }
        }
        return b.array();
    }

    /**
     * Reads a parameter in the binary format of a type of PostgreSQL as a
     * value of a type of FyrQL: as the value of the type of PostgreSQL,
     * converted to the type of FyrQL through its text if they differ.
     *
     * @throws IllegalArgumentException if the bytes are no value of the type,
     *         or the server reads no binary format of it
     */
    static Object parseBinary(byte[] bytes, int oid, DataType type, ZoneId zone, Charset charset) {
        Object value;
        DataType own;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        switch (oid) {
            case BOOL -> {
                value = bytes.length == 1 && bytes[0] != 0;
                own = DataType.BOOLEAN;
            }
            case INT2 -> {
                value = b.getShort();
                own = DataType.SMALLINT;
            }
            case INT4, OID -> {
                value = b.getInt();
                own = DataType.INTEGER;
            }
            case INT8 -> {
                value = b.getLong();
                own = DataType.BIGINT;
            }
            case FLOAT4 -> {
                value = b.getFloat();
                own = DataType.FLOAT;
            }
            case FLOAT8 -> {
                value = b.getDouble();
                own = DataType.DOUBLE;
            }
            case NUMERIC -> {
                BigDecimal d = numeric(bytes);
                int scale = Math.max(0, d.scale());
                int precision = Math.max(1, Math.max(d.precision(), scale));
                if (precision > DataType.MAX_DECIMAL_PRECISION) {
                    return parseText(d.toPlainString(), type, zone);
                }
                value = d.setScale(scale);
                own = DataType.decimal(precision, scale);
            }
            case TEXT, VARCHAR, BPCHAR, NAME, UNKNOWN, JSON, CHAR -> {
                value = new String(bytes, charset);
                own = DataType.VARCHAR;
            }
            case JSONB -> {
                value = new String(bytes, 1, bytes.length - 1, charset);
                own = DataType.VARCHAR;
            }
            case BYTEA -> {
                value = bytes;
                own = DataType.BLOB;
            }
            case DATE -> {
                value = b.getInt() + DAYS_TO_2000;
                own = DataType.DATE;
            }
            case TIME -> {
                value = b.getLong();
                own = DataType.TIME;
            }
            case TIMESTAMP -> {
                value = b.getLong() + MICROS_TO_2000;
                own = DataType.TIMESTAMP;
            }
            case TIMESTAMPTZ -> {
                value = b.getLong() + MICROS_TO_2000;
                own = DataType.TIMESTAMPTZ;
            }
            case INTERVAL -> {
                long micros = b.getLong();
                int days = b.getInt();
                value = Interval.of(b.getInt(), days, micros);
                own = DataType.INTERVAL;
            }
            case UUID_OID -> {
                value = new UUID(b.getLong(), b.getLong());
                own = DataType.UUID;
            }
            default -> {
                DataType listType = fyrqlType(oid);
                if (!(listType instanceof DataType.ListType)) {
                    throw new IllegalArgumentException("Not supported: the binary format of the type with the OID "
                            + oid);
                }
                return parseBinaryArray(b, type instanceof DataType.ListType l ? l.element() : null, zone, charset);
            }
        }
        if (own.equals(type)) {
            return value;
        }
        if (type == DataType.TINYINT && value instanceof Short s && s >= Byte.MIN_VALUE && s <= Byte.MAX_VALUE) {
            return (byte) (short) s;
        }
        return parseText(text(value, own, zone), type, zone);
    }

    private static List<Object> parseBinaryArray(ByteBuffer b, DataType element, ZoneId zone, Charset charset) {
        int dimensions = b.getInt();
        b.getInt();
        int oid = b.getInt();
        List<Object> result = new ArrayList<>();
        if (dimensions == 0) {
            return result;
        }
        if (dimensions != 1) {
            throw new IllegalArgumentException("Not supported: arrays of more than one dimension");
        }
        int n = b.getInt();
        b.getInt();
        DataType target = element != null ? element : fyrqlType(oid);
        if (target == null) {
            throw new IllegalArgumentException("Not supported: arrays of the type with the OID " + oid);
        }
        for (int i = 0; i < n; i++) {
            int length = b.getInt();
            if (length < 0) {
                result.add(null);
            } else {
                byte[] bytes = new byte[length];
                b.get(bytes);
                result.add(parseBinary(bytes, oid, target, zone, charset));
            }
        }
        return result;
    }
}
