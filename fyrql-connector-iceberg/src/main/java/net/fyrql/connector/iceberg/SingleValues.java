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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The values of bounds and partitions in manifests, all as the same Java
 * values: {@code Boolean}, {@code Integer} for {@code int} and
 * {@code date}, {@code Long} for {@code long}, {@code time} and the
 * timestamps, {@code Float}, {@code Double}, {@code BigDecimal},
 * {@code String}, {@code UUID}, and {@code byte[]} for {@code fixed} and
 * {@code binary}.
 * <p>
 * A bound is the single-value serialization of the specification:
 * numbers little-endian, a decimal as its unscaled value in two's
 * complement big-endian, a {@code UUID} big-endian, a string in UTF-8. A
 * bound written before a column was promoted keeps its old width: four
 * bytes of an {@code int} in a {@code long} column, of a {@code float} in
 * a {@code double} one.
 */
final class SingleValues {

    private SingleValues() {
    }

    /**
     * Returns the value of a bound.
     *
     * @throws IllegalArgumentException if the bytes do not fit the type
     */
    static Object bound(IcebergSchema.Primitive type, byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type.name()) {
            case "boolean" -> {
                check(bytes, 1, type);
                yield bytes[0] != 0;
            }
            case "int", "date" -> {
                check(bytes, 4, type);
                yield b.getInt();
            }
            case "long", "time", "timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns" -> {
                if (bytes.length == 4) {
                    yield (long) b.getInt();
                }
                check(bytes, 8, type);
                yield b.getLong();
            }
            case "float" -> {
                check(bytes, 4, type);
                yield b.getFloat();
            }
            case "double" -> {
                if (bytes.length == 4) {
                    yield (double) b.getFloat();
                }
                check(bytes, 8, type);
                yield b.getDouble();
            }
            case "decimal" -> new BigDecimal(new BigInteger(bytes), type.scale());
            case "string" -> new String(bytes, StandardCharsets.UTF_8);
            case "uuid" -> {
                check(bytes, 16, type);
                ByteBuffer big = ByteBuffer.wrap(bytes);
                yield new UUID(big.getLong(), big.getLong());
            }
            case "fixed", "binary" -> bytes;
            default -> throw new IllegalArgumentException("No bounds of type " + type.name());
        };
    }

    /**
     * Returns a partition value as the Avro reader gives it, in the Java
     * value of its type.
     *
     * @throws IllegalArgumentException if the value does not fit the type
     */
    static Object partition(IcebergSchema.Primitive type, Object avro) {
        if (avro == null) {
            return null;
        }
        return switch (type.name()) {
            case "int", "date" -> (Integer) avro;
            case "long", "time", "timestamp", "timestamptz", "timestamp_ns", "timestamptz_ns" ->
                    avro instanceof Integer i ? Long.valueOf(i) : (Long) avro;
            case "double" -> avro instanceof Float f ? Double.valueOf(f) : (Double) avro;
            case "decimal" -> new BigDecimal(new BigInteger((byte[]) avro), type.scale());
            case "uuid" -> avro instanceof String s ? UUID.fromString(s) : bound(type, (byte[]) avro);
            case "boolean", "float", "string", "fixed", "binary" -> avro;
            default -> throw new IllegalArgumentException("No partition values of type " + type.name());
        };
    }

    /**
     * Returns a value in its JSON form, as {@code initial-default} gives it:
     * numbers as numbers, a decimal, a date, a time, a timestamp and a
     * {@code UUID} as text, {@code fixed} and {@code binary} as hexadecimal
     * text.
     *
     * @throws IllegalArgumentException if the JSON does not fit the type
     */
    static Object json(IcebergSchema.Primitive type, Object json) {
        if (json == null) {
            return null;
        }
        try {
            return switch (type.name()) {
                case "boolean" -> (Boolean) json;
                case "int" -> Math.toIntExact((Long) json);
                case "long" -> (Long) json;
                case "float" -> ((Number) json).floatValue();
                case "double" -> ((Number) json).doubleValue();
                case "decimal" -> new BigDecimal((String) json).setScale(type.scale());
                case "date" -> Math.toIntExact(LocalDate.parse((String) json).toEpochDay());
                case "time" -> LocalTime.parse((String) json).toNanoOfDay() / 1000;
                case "timestamp" -> micros(LocalDateTime.parse((String) json).toInstant(ZoneOffset.UTC));
                case "timestamptz" -> micros(OffsetDateTime.parse((String) json).toInstant());
                case "timestamp_ns" -> nanos(LocalDateTime.parse((String) json).toInstant(ZoneOffset.UTC));
                case "timestamptz_ns" -> nanos(OffsetDateTime.parse((String) json).toInstant());
                case "string" -> (String) json;
                case "uuid" -> UUID.fromString((String) json);
                case "fixed", "binary" -> HexFormat.of().parseHex((String) json);
                default -> throw new IllegalArgumentException("No default of type " + type.name());
            };
        } catch (ClassCastException | ArithmeticException | java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid default " + json + " of type " + type.name(), e);
        }
    }

    private static long micros(Instant i) {
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000L), i.getNano() / 1000);
    }

    private static long nanos(Instant i) {
        return Math.addExact(Math.multiplyExact(i.getEpochSecond(), 1_000_000_000L), i.getNano());
    }

    private static void check(byte[] bytes, int length, IcebergSchema.Primitive type) {
        if (bytes.length != length) {
            throw new IllegalArgumentException("A bound of type " + type.name() + " with " + bytes.length
                    + " bytes");
        }
    }
}
