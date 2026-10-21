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
import net.fyrql.core.type.DataType.Decimal;
import net.fyrql.core.type.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Generates rows of Java values deterministically from a seed.
 * <p>
 * Each column starts with all {@linkplain EdgeValues edge values} of its type,
 * plus one {@code NULL} if the column is nullable and the null rate is above
 * zero, in an order that depends on the seed and the column; with fewer rows,
 * only as many of them as fit. The remaining rows are {@code NULL} with the
 * null rate, an edge value with the edge rate, and a random value otherwise.
 * The same seed, schema and row count always give the same data.
 *
 * @param seed the seed
 * @param nullRate the probability of {@code NULL} in the random rows, 0 to 1
 * @param edgeRate the probability of an edge value in the random rows, 0 to 1
 */
public record DataGenerator(long seed, double nullRate, double edgeRate) {

    /** The pieces of random strings; the pair is one character outside the Basic Multilingual Plane. */
    private static final List<String> STRING_PARTS = List.of(
            "a", "b", "c", "x", "y", "z", "A", "B", "Z", "0", "1", "9", " ", "%", "_", "-", ".",
            "\u00DF", "\u00E9", "\u0131", "\uD834\uDD1E");

    public DataGenerator {
        if (nullRate < 0 || edgeRate < 0 || nullRate + edgeRate > 1) {
            throw new IllegalArgumentException("nullRate " + nullRate + ", edgeRate " + edgeRate);
        }
    }

    /** Returns a generator with a null rate of 10 % and an edge rate of 10 %. */
    public static DataGenerator withSeed(long seed) {
        return new DataGenerator(seed, 0.1, 0.1);
    }

    /** Generates rows for a schema. */
    public List<Object[]> generate(Schema schema, int rows) {
        List<Object[]> data = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            data.add(new Object[schema.size()]);
        }
        for (int c = 0; c < schema.size(); c++) {
            fillColumn(schema.type(c), schema.nullable(c) ? nullRate : 0, c, data);
        }
        return data;
    }

    private void fillColumn(DataType type, double nulls, int column, List<Object[]> data) {
        SplittableRandom random = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + column);
        List<Object> edges = new ArrayList<>(EdgeValues.of(type));
        if (nulls > 0) {
            edges.add(null);
        }
        shuffle(edges, random);
        for (int r = 0; r < data.size(); r++) {
            Object value;
            if (r < edges.size()) {
                value = edges.get(r);
            } else {
                double p = random.nextDouble();
                if (p < nulls) {
                    value = null;
                } else if (p < nulls + edgeRate) {
                    value = edges.get(random.nextInt(edges.size()));
                } else {
                    value = randomValue(type, random, 0);
                }
            }
            data.get(r)[column] = value;
        }
    }

    /** A Fisher-Yates shuffle with the given random, independent of {@link Collections#shuffle}. */
    private static void shuffle(List<Object> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            Collections.swap(list, i, random.nextInt(i + 1));
        }
    }

    /** Returns a random value of a type; nested values get shorter with the depth. */
    public static Object randomValue(DataType type, SplittableRandom random, int depth) {
        boolean small = random.nextBoolean();
        if (type instanceof Decimal d) {
            int digits = Math.min(d.precision(), small ? 6 : 18);
            long bound = BigInteger.TEN.pow(digits).longValueExact();
            BigInteger unscaled = BigInteger.valueOf(random.nextLong(bound));
            return new BigDecimal(random.nextBoolean() ? unscaled : unscaled.negate(), d.scale());
        }
        if (type instanceof DataType.Struct s) {
            List<Object> values = new ArrayList<>();
            for (DataType.Struct.Field f : s.fields()) {
                values.add(random.nextInt(8) == 0 ? null : randomValue(f.type(), random, depth + 1));
            }
            return values;
        }
        if (type instanceof DataType.ListType l) {
            int n = random.nextInt(0, Math.max(1, 5 - depth));
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(random.nextInt(8) == 0 ? null : randomValue(l.element(), random, depth + 1));
            }
            return values;
        }
        if (type instanceof DataType.MapType m) {
            int n = random.nextInt(0, Math.max(1, 4 - depth));
            Map<Object, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Object key = randomValue(m.key(), random, depth + 1);
                if (key != null) {
                    values.put(key, random.nextInt(8) == 0 ? null : randomValue(m.value(), random, depth + 1));
                }
            }
            return values;
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> random.nextBoolean();
            case TINYINT -> (byte) random.nextInt();
            case SMALLINT -> (short) random.nextInt();
            case INTEGER -> small ? random.nextInt(-1000, 1001) : random.nextInt();
            case BIGINT -> small ? random.nextLong(-1000, 1001) : random.nextLong();
            case HUGEINT -> small ? BigInteger.valueOf(random.nextLong(-1000, 1001))
                    : BigInteger.valueOf(random.nextLong()).multiply(BigInteger.valueOf(random.nextLong()));
            case FLOAT -> small ? (float) (random.nextInt(-100_000, 100_001) / 100.0)
                    : Float.intBitsToFloat(random.nextInt());
            case DOUBLE -> small ? random.nextInt(-100_000, 100_001) / 100.0
                    : Double.longBitsToDouble(random.nextLong());
            case VARCHAR -> {
                StringBuilder sb = new StringBuilder();
                int length = random.nextInt(0, 9);
                for (int i = 0; i < length; i++) {
                    sb.append(STRING_PARTS.get(random.nextInt(STRING_PARTS.size())));
                }
                yield sb.toString();
            }
            case BLOB -> {
                byte[] bytes = new byte[random.nextInt(0, 9)];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) random.nextInt(256);
                }
                yield bytes;
            }
            case DATE -> random.nextInt(-50_000, 50_001);
            case TIMESTAMP -> small ? random.nextLong(-1_000_000_000L, 1_000_000_001L) * 1_000_000L
                    : random.nextLong(-62_000_000_000_000_000L, 253_000_000_000_000_000L);
            case UUID -> new UUID(random.nextLong(), random.nextLong());
            case TIME -> small ? random.nextLong(0, 86_400) * 1_000_000L : random.nextLong(0, 86_400_000_000L);
            case TIMESTAMPTZ -> small ? random.nextLong(-1_000_000_000L, 1_000_000_001L) * 1_000_000L
                    : random.nextLong(-62_000_000_000_000_000L, 253_000_000_000_000_000L);
            case INTERVAL -> small ? Interval.of(random.nextInt(-24, 25), random.nextInt(-40, 41),
                    random.nextLong(-100_000, 100_001) * 1_000_000L)
                    : Interval.of(random.nextInt(-100_000, 100_001), random.nextInt(-1_000_000, 1_000_001),
                            random.nextLong(-1_000_000_000_000_000L, 1_000_000_000_000_001L));
        };
    }
}
