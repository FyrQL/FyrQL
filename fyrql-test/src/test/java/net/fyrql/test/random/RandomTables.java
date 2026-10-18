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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.DataGenerator;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.test.DuckDbReference;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The tables of the random queries, in a memory source {@code memory} of
 * FyrQL and under the same plain names in DuckDB. Two tables {@code p} and
 * {@code q} hold small values, so that arithmetic rarely overflows, and
 * share some column names for {@code USING} and {@code NATURAL} joins; the
 * table {@code e} holds the edge values of every type. The column
 * {@code k} of all three is a join key between 0 and 9.
 */
final class RandomTables {

    static final String SOURCE = "memory";

    /** The tables by name. */
    static final Map<String, Schema> SCHEMAS = new LinkedHashMap<>();

    static {
        SCHEMAS.put("p", Schema.of(field("k", DataType.INTEGER), field("i", DataType.INTEGER),
                field("l", DataType.BIGINT), field("t", DataType.TINYINT), field("s", DataType.SMALLINT),
                field("h", DataType.HUGEINT), field("f", DataType.FLOAT), field("d", DataType.DOUBLE),
                field("m", DataType.decimal(6, 2)), field("v", DataType.VARCHAR), field("b", DataType.BOOLEAN),
                field("dt", DataType.DATE), field("ts", DataType.TIMESTAMP)));
        SCHEMAS.put("q", Schema.of(field("k", DataType.INTEGER), field("i", DataType.BIGINT),
                field("m", DataType.decimal(10, 3)), field("v", DataType.VARCHAR), field("dt", DataType.DATE),
                field("x", DataType.VARCHAR), field("n", DataType.SMALLINT)));
        SCHEMAS.put("e", Schema.of(field("k", DataType.INTEGER), field("ei", DataType.INTEGER),
                field("el", DataType.BIGINT), field("et", DataType.TINYINT), field("eh", DataType.HUGEINT),
                field("ef", DataType.FLOAT), field("ed", DataType.DOUBLE), field("em", DataType.decimal(18, 4)),
                field("ev", DataType.VARCHAR), field("eb", DataType.BOOLEAN), field("edt", DataType.DATE),
                field("ets", DataType.TIMESTAMP)));
    }

    /** Strings of the small tables: letters in both cases, digits, pattern characters, and non-ASCII. */
    static final List<String> STRINGS = List.of("", "a", "b", "ab", "abc", "A", "Ab", "x y", " a ", "%", "_", "1",
            "12", "2.5", "-3", "2024-01-02", "true", "\u00DF", "\u00E9t\u00E9", "\u0130", "z\uD834\uDD1E");

    private RandomTables() {
    }

    private static Schema.Field field(String name, DataType type) {
        return new Schema.Field(name, type);
    }

    /** Generates the rows of the tables from a seed. */
    static Map<String, List<Object[]>> rows(long seed) {
        Map<String, List<Object[]>> rows = new LinkedHashMap<>();
        rows.put("p", small(SCHEMAS.get("p"), 40, new SplittableRandom(seed)));
        rows.put("q", small(SCHEMAS.get("q"), 30, new SplittableRandom(seed + 1)));
        List<Object[]> e = DataGenerator.withSeed(seed + 2).generate(SCHEMAS.get("e"), 30);
        SplittableRandom keys = new SplittableRandom(seed + 3);
        for (Object[] row : e) {
            row[0] = keys.nextInt(12) == 0 ? null : keys.nextInt(10);
        }
        rows.put("e", e);
        return rows;
    }

    private static List<Object[]> small(Schema schema, int count, SplittableRandom random) {
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            Object[] row = new Object[schema.size()];
            for (int c = 0; c < schema.size(); c++) {
                row[c] = random.nextInt(10) == 0 ? null
                        : c == 0 ? (Object) random.nextInt(10) : smallValue(schema.type(c), random);
            }
            rows.add(row);
        }
        return rows;
    }

    /** A small value of a type: integers up to a few dozen, binary fractions, short strings, recent dates. */
    static Object smallValue(DataType type, SplittableRandom random) {
        if (type instanceof DataType.Decimal d) {
            BigInteger unscaled = BigInteger.valueOf(random.nextLong(-2000, 2001));
            return new BigDecimal(unscaled, Math.min(d.scale(), 2)).setScale(d.scale());
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> random.nextBoolean();
            case TINYINT -> (byte) random.nextInt(-9, 10);
            case SMALLINT -> (short) random.nextInt(-30, 31);
            case INTEGER -> random.nextInt(-30, 31);
            case BIGINT -> random.nextLong(-30, 31);
            case HUGEINT -> BigInteger.valueOf(random.nextLong(-30, 31));
            case FLOAT -> random.nextInt(-40, 41) / 4.0f;
            case DOUBLE -> random.nextInt(-80, 81) / 8.0;
            case VARCHAR -> STRINGS.get(random.nextInt(STRINGS.size()));
            case DATE -> 19_700 + random.nextInt(-400, 400);
            case TIMESTAMP -> (19_700L + random.nextInt(-400, 400)) * 86_400_000_000L
                    + random.nextLong(0, 86_400L) * 1_000_000L + (random.nextBoolean() ? 0 : random.nextInt(1000) * 1000L);
            default -> throw new IllegalArgumentException("No small values of " + type);
        };
    }

    /** Builds an engine over the tables that interprets everything, on the threads of the default. */
    static FyrQL engine(Map<String, List<Object[]>> rows) {
        return engine(rows, CodegenMode.OFF);
    }

    /** Builds an engine over the tables with a mode of code generation, on the threads of the default. */
    static FyrQL engine(Map<String, List<Object[]>> rows, CodegenMode codegen) {
        return engine(rows, codegen, Executor.threadsFromSystemProperty());
    }

    /**
     * Builds an engine over the tables with a mode of code generation and a
     * number of threads; a table has a split for every 16 rows, so that the
     * segments of a query run on several threads.
     */
    static FyrQL engine(Map<String, List<Object[]>> rows, CodegenMode codegen, int threads) {
        MemoryConnector.Builder memory = MemoryConnector.builder(SOURCE).batchSize(16).splitRows(16);
        for (Map.Entry<String, List<Object[]>> table : rows.entrySet()) {
            memory.table(table.getKey(), SCHEMAS.get(table.getKey()), table.getValue());
        }
        return FyrQL.builder().register(memory.build()).batchSize(16).codegen(codegen).threads(threads).build();
    }

    /** Copies the tables of an engine into DuckDB under their plain names. */
    static void load(FyrQL engine, DuckDbReference duck) {
        for (String table : SCHEMAS.keySet()) {
            PlanNode.Scan scan = engine.scan(TableRef.of(SOURCE, table));
            duck.load(table, scan.schema(), engine.batches(scan));
        }
    }
}
