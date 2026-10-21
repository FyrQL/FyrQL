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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * The partition transforms of Iceberg on values as {@link SingleValues}
 * gives them: {@code Integer} for {@code int} and {@code date},
 * {@code Long} for {@code long}, {@code time} and the timestamps,
 * {@code BigDecimal}, {@code String}, {@code UUID} and {@code byte[]}.
 * A bucket is the 32-bit Murmur3 hash (x86, seed 0) of the value, without
 * its sign bit, modulo the number of buckets; an {@code int} and a
 * {@code date} are hashed as a {@code long}, a decimal as the bytes of its
 * unscaled value, a string as UTF-8, a {@code UUID} as 16 bytes
 * big-endian. Time transforms count years, months, days or hours since
 * 1970-01-01 in UTC, rounded down.
 */
final class Transforms {

    private static final long MICROS_PER_HOUR = 3_600_000_000L;
    private static final long MICROS_PER_DAY = 86_400_000_000L;

    private Transforms() {
    }

    /**
     * Applies a transform to a value of a type.
     *
     * @return the partition value, {@code null} for {@code null}
     * @throws IllegalArgumentException if the transform does not apply to the type
     */
    static Object apply(PartitionSpec.Transform transform, IcebergSchema.Primitive type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (transform) {
            case PartitionSpec.Identity i -> value;
            case PartitionSpec.VoidTransform v -> null;
            case PartitionSpec.Bucket b -> (hash(type, value) & Integer.MAX_VALUE) % b.buckets();
            case PartitionSpec.Truncate t -> truncate(type, value, t.width());
            case PartitionSpec.Time t -> time(type, value, t.unit());
        };
    }

    /** Returns the hash of a value for {@code bucket}. */
    static int hash(IcebergSchema.Primitive type, Object value) {
        return switch (type.name()) {
            case "int", "date" -> hashLong((Integer) value);
            case "long", "time", "timestamp", "timestamptz" -> hashLong((Long) value);
            case "timestamp_ns", "timestamptz_ns" -> hashLong(Math.floorDiv((Long) value, 1000L));
            case "decimal" -> hashBytes(((BigDecimal) value).unscaledValue().toByteArray());
            case "string" -> hashBytes(((String) value).getBytes(StandardCharsets.UTF_8));
            case "uuid" -> {
                UUID u = (UUID) value;
                yield hashBytes(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                        .putLong(u.getLeastSignificantBits()).array());
            }
            case "fixed", "binary" -> hashBytes((byte[]) value);
            default -> throw new IllegalArgumentException("Cannot bucket a value of type " + type.name());
        };
    }

    private static Object truncate(IcebergSchema.Primitive type, Object value, int width) {
        return switch (type.name()) {
            case "int" -> {
                int v = (Integer) value;
                yield v - Math.floorMod(v, width);
            }
            case "long" -> {
                long v = (Long) value;
                yield v - Math.floorMod(v, (long) width);
            }
            case "decimal" -> {
                BigDecimal d = (BigDecimal) value;
                BigInteger unscaled = d.unscaledValue();
                BigInteger w = BigInteger.valueOf(width);
                yield new BigDecimal(unscaled.subtract(unscaled.mod(w)), d.scale());
            }
            case "string" -> {
                String s = (String) value;
                yield s.codePointCount(0, s.length()) <= width ? s : s.substring(0, s.offsetByCodePoints(0, width));
            }
            case "binary" -> {
                byte[] b = (byte[]) value;
                yield b.length <= width ? b : Arrays.copyOf(b, width);
            }
            default -> throw new IllegalArgumentException("Cannot truncate a value of type " + type.name());
        };
    }

    private static Object time(IcebergSchema.Primitive type, Object value, String unit) {
        long micros;
        switch (type.name()) {
            case "date" -> {
                int days = (Integer) value;
                return switch (unit) {
                    case "day" -> days;
                    case "hour" -> throw new IllegalArgumentException("Cannot take the hour of a date");
                    default -> calendar(LocalDate.ofEpochDay(days), unit);
                };
            }
            case "timestamp", "timestamptz" -> micros = (Long) value;
            case "timestamp_ns", "timestamptz_ns" -> micros = Math.floorDiv((Long) value, 1000L);
            default -> throw new IllegalArgumentException("Cannot take the " + unit + " of a value of type "
                    + type.name());
        }
        return switch (unit) {
            case "hour" -> Math.toIntExact(Math.floorDiv(micros, MICROS_PER_HOUR));
            case "day" -> Math.toIntExact(Math.floorDiv(micros, MICROS_PER_DAY));
            default -> calendar(LocalDate.ofEpochDay(Math.floorDiv(micros, MICROS_PER_DAY)), unit);
        };
    }

    private static int calendar(LocalDate date, String unit) {
        int years = date.getYear() - 1970;
        return unit.equals("year") ? years : years * 12 + date.getMonthValue() - 1;
    }

    /** The 32-bit Murmur3 hash (x86, seed 0) of a {@code long} as eight bytes little-endian. */
    static int hashLong(long value) {
        int h = mix(0, (int) value);
        h = mix(h, (int) (value >>> 32));
        return finish(h, 8);
    }

    /** The 32-bit Murmur3 hash (x86, seed 0) of bytes. */
    static int hashBytes(byte[] bytes) {
        int h = 0;
        int blocks = bytes.length / 4;
        for (int i = 0; i < blocks; i++) {
            int k = bytes[4 * i] & 0xff | (bytes[4 * i + 1] & 0xff) << 8 | (bytes[4 * i + 2] & 0xff) << 16
                    | (bytes[4 * i + 3] & 0xff) << 24;
            h = mix(h, k);
        }
        int rest = bytes.length & 3;
        if (rest > 0) {
            int k = 0;
            for (int i = blocks * 4 + rest - 1; i >= blocks * 4; i--) {
                k = k << 8 | bytes[i] & 0xff;
            }
            h ^= mixK(k);
        }
        return finish(h, bytes.length);
    }

    private static int mixK(int k) {
        k *= 0xcc9e2d51;
        k = Integer.rotateLeft(k, 15);
        return k * 0x1b873593;
    }

    private static int mix(int h, int k) {
        h ^= mixK(k);
        h = Integer.rotateLeft(h, 13);
        return h * 5 + 0xe6546b64;
    }

    private static int finish(int h, int length) {
        h ^= length;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        return h ^ h >>> 16;
    }
}
