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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** The partition transforms and the single values of bounds, against Iceberg Java and the specification. */
class TransformsTest {

    private static final Random RANDOM = new Random(20261021);

    @Test
    void theHashesOfTheSpecification() {
        assertEquals(2017239379, Transforms.hash(type("int"), 34));
        assertEquals(2017239379, Transforms.hash(type("long"), 34L));
        assertEquals(-500754589, Transforms.hash(new IcebergSchema.Primitive("decimal", 9, 2, 0),
                new BigDecimal("14.20")));
        assertEquals(-653330422, Transforms.hash(type("date"), (int) LocalDate.of(2017, 11, 16).toEpochDay()));
        assertEquals(-662762989, Transforms.hash(type("time"), 81_068_000_000L));
        assertEquals(-2047944441, Transforms.hash(type("timestamp"), 1_510_871_468_000_000L));
        assertEquals(1210000089, Transforms.hash(type("string"), "iceberg"));
        assertEquals(1488055340, Transforms.hash(type("uuid"),
                UUID.fromString("f79c3e09-677c-4bbd-a479-3f349cb785e7")));
        assertEquals(-188683207, Transforms.hash(type("binary"), new byte[] {0, 1, 2, 3}));
    }

    @Test
    void bucketsAsIcebergJava() {
        PartitionSpec.Transform bucket = new PartitionSpec.Bucket(16);
        var iceberg = org.apache.iceberg.transforms.Transforms.bucket(16);
        compare(bucket, iceberg, type("int"), Types.IntegerType.get(), r -> r.nextInt(), v -> v);
        compare(bucket, iceberg, type("long"), Types.LongType.get(), r -> r.nextLong(), v -> v);
        compare(bucket, iceberg, type("date"), Types.DateType.get(), r -> r.nextInt(-50_000, 50_000), v -> v);
        compare(bucket, iceberg, type("timestamp"), Types.TimestampType.withoutZone(), r -> r.nextLong(), v -> v);
        compare(bucket, iceberg, type("timestamptz"), Types.TimestampType.withZone(), r -> r.nextLong(), v -> v);
        compare(bucket, iceberg, type("string"), Types.StringType.get(), TransformsTest::string, v -> v);
        compare(bucket, iceberg, type("uuid"), Types.UUIDType.get(), r -> new UUID(r.nextLong(), r.nextLong()),
                v -> v);
        compare(bucket, iceberg, type("binary"), Types.BinaryType.get(), TransformsTest::bytes,
                v -> ByteBuffer.wrap((byte[]) v));
        compare(bucket, iceberg, new IcebergSchema.Primitive("decimal", 20, 3, 0), Types.DecimalType.of(20, 3),
                r -> new BigDecimal(new java.math.BigInteger(64, r).subtract(java.math.BigInteger.ONE.shiftLeft(63)),
                        3), v -> v);
    }

    @Test
    void truncationsAsIcebergJava() {
        compare(new PartitionSpec.Truncate(10), org.apache.iceberg.transforms.Transforms.truncate(10), type("int"),
                Types.IntegerType.get(), r -> r.nextInt(), v -> v);
        compare(new PartitionSpec.Truncate(7), org.apache.iceberg.transforms.Transforms.truncate(7), type("long"),
                Types.LongType.get(), r -> r.nextLong(-1_000_000, 1_000_000), v -> v);
        compare(new PartitionSpec.Truncate(100), org.apache.iceberg.transforms.Transforms.truncate(100),
                new IcebergSchema.Primitive("decimal", 9, 2, 0), Types.DecimalType.of(9, 2),
                r -> BigDecimal.valueOf(r.nextLong(-99_999_999, 99_999_999), 2), v -> v);
        compare(new PartitionSpec.Truncate(3), org.apache.iceberg.transforms.Transforms.truncate(3), type("string"),
                Types.StringType.get(), TransformsTest::string, v -> v);
        compare(new PartitionSpec.Truncate(3), org.apache.iceberg.transforms.Transforms.truncate(3), type("binary"),
                Types.BinaryType.get(), TransformsTest::bytes, v -> ByteBuffer.wrap((byte[]) v));
    }

    @Test
    void timesAsIcebergJava() {
        for (String unit : List.of("year", "month", "day", "hour")) {
            var iceberg = switch (unit) {
                case "year" -> org.apache.iceberg.transforms.Transforms.year();
                case "month" -> org.apache.iceberg.transforms.Transforms.month();
                case "day" -> org.apache.iceberg.transforms.Transforms.day();
                default -> org.apache.iceberg.transforms.Transforms.hour();
            };
            long range = 200L * 365 * 86_400_000_000L;
            compare(new PartitionSpec.Time(unit), iceberg, type("timestamp"), Types.TimestampType.withoutZone(),
                    r -> r.nextLong(-range, range), v -> v);
            compare(new PartitionSpec.Time(unit), iceberg, type("timestamptz"), Types.TimestampType.withZone(),
                    r -> r.nextLong(-range, range), v -> v);
            if (!unit.equals("hour")) {
                compare(new PartitionSpec.Time(unit), iceberg, type("date"), Types.DateType.get(),
                        r -> r.nextInt(-80_000, 80_000), v -> v);
            }
        }
        assertEquals(-1, Transforms.apply(new PartitionSpec.Time("hour"), type("timestamp"), -1L));
        assertEquals(-1, Transforms.apply(new PartitionSpec.Time("year"), type("date"), -1));
        assertEquals(null, Transforms.apply(new PartitionSpec.Bucket(4), type("int"), null));
        assertEquals(null, Transforms.apply(new PartitionSpec.VoidTransform(), type("int"), 5));
        assertThrows(IllegalArgumentException.class, () -> Transforms.apply(new PartitionSpec.Bucket(4),
                type("double"), 1.0));
    }

    @Test
    void transformsAndTheirTypes() {
        assertEquals(new PartitionSpec.Bucket(16), PartitionSpec.transform("bucket[16]"));
        assertEquals(new PartitionSpec.Truncate(4), PartitionSpec.transform("truncate[4]"));
        assertEquals(new PartitionSpec.Time("day"), PartitionSpec.transform("day"));
        assertEquals(new PartitionSpec.Identity(), PartitionSpec.transform("identity"));
        assertThrows(IllegalArgumentException.class, () -> PartitionSpec.transform("zorder"));
        assertEquals(type("date"), PartitionSpec.resultType(new PartitionSpec.Time("day"), type("timestamp")));
        assertEquals(type("int"), PartitionSpec.resultType(new PartitionSpec.Time("hour"), type("timestamp")));
        assertEquals(type("int"), PartitionSpec.resultType(new PartitionSpec.Bucket(2), type("string")));
        assertEquals(type("string"), PartitionSpec.resultType(new PartitionSpec.Truncate(2), type("string")));
    }

    @Test
    void singleValuesOfBounds() {
        assertEquals(true, SingleValues.bound(type("boolean"), new byte[] {1}));
        assertEquals(-2, SingleValues.bound(type("int"), le(4, -2)));
        assertEquals(-2L, SingleValues.bound(type("long"), le(8, -2)));
        assertEquals(7L, SingleValues.bound(type("long"), le(4, 7)));
        assertEquals(1.5f, SingleValues.bound(type("float"), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(1.5f).array()));
        assertEquals(1.5, SingleValues.bound(type("double"), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(1.5f).array()));
        assertEquals(new BigDecimal("-1.25"), SingleValues.bound(new IcebergSchema.Primitive("decimal", 5, 2, 0),
                new byte[] {(byte) 0xff, (byte) 0x83}));
        assertEquals("\u00E4b", SingleValues.bound(type("string"), "\u00E4b".getBytes(StandardCharsets.UTF_8)));
        UUID u = UUID.fromString("f79c3e09-677c-4bbd-a479-3f349cb785e7");
        assertEquals(u, SingleValues.bound(type("uuid"), ByteBuffer.allocate(16).putLong(u.getMostSignificantBits())
                .putLong(u.getLeastSignificantBits()).array()));
        assertArrayEquals(new byte[] {1, 2}, (byte[]) SingleValues.bound(type("binary"), new byte[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> SingleValues.bound(type("int"), new byte[3]));
        assertEquals(5L, SingleValues.partition(type("long"), 5));
        assertEquals(new BigDecimal("1.00"), SingleValues.partition(new IcebergSchema.Primitive("decimal", 5, 2, 0),
                new byte[] {100}));
        assertEquals(null, SingleValues.partition(type("int"), null));
    }

    private static byte[] le(int bytes, long value) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value);
        byte[] out = new byte[bytes];
        System.arraycopy(b.array(), 0, out, 0, bytes);
        return out;
    }

    private static IcebergSchema.Primitive type(String name) {
        return IcebergSchema.Primitive.of(name);
    }

    private static String string(Random r) {
        StringBuilder s = new StringBuilder();
        int n = r.nextInt(8);
        for (int i = 0; i < n; i++) {
            int kind = r.nextInt(4);
            s.appendCodePoint(kind == 0 ? 0x1F600 + r.nextInt(50) : kind == 1 ? 0xE4 + r.nextInt(20)
                    : 'a' + r.nextInt(26));
        }
        return s.toString();
    }

    private static byte[] bytes(Random r) {
        byte[] b = new byte[r.nextInt(9)];
        r.nextBytes(b);
        return b;
    }

    /** Compares a transform with the one of Iceberg Java on random values. */
    @SuppressWarnings("unchecked")
    private static <T> void compare(PartitionSpec.Transform ours,
            org.apache.iceberg.transforms.Transform<?, ?> theirs, IcebergSchema.Primitive type, Type icebergType,
            Function<Random, T> values, Function<Object, Object> toIceberg) {
        var bound = ((org.apache.iceberg.transforms.Transform<Object, Object>) theirs).bind(icebergType);
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            T value = values.apply(RANDOM);
            Object expected = bound.apply(toIceberg.apply(value));
            Object actual = Transforms.apply(ours, type, value);
            if (expected instanceof ByteBuffer b) {
                byte[] copy = new byte[b.remaining()];
                b.duplicate().get(copy);
                expected = ByteBuffer.wrap(copy);
                actual = ByteBuffer.wrap((byte[]) actual);
            }
            if (expected instanceof CharSequence c) {
                expected = c.toString();
            }
            if (!expected.equals(actual)) {
                differences.add(value + ": " + expected + " <> " + actual);
            }
        }
        assertEquals(List.of(), differences, ours + " of " + type.name());
    }
}
