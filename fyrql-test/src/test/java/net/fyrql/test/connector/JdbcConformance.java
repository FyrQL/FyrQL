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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.jdbc.JdbcConnector;
import net.fyrql.connector.jdbc.JdbcDialect;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.db.TestDatabase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The conformance of the JDBC connector over one database: a table of
 * random values of every type the database stores, read through FyrQL
 * with the pushdown of the run and without any, must give the rows of
 * DuckDB over the same values, for a list of queries with filters of every
 * type, {@code IN}, {@code BETWEEN}, {@code IS NULL}, orders, limits and
 * offsets, aggregations over every type with and without groups, joins
 * with a second table of the database and with a table of another source.
 * With pushdown, the statements the database receives show the filters,
 * the order, the limit, the aggregation and the joins it took over, and
 * only the columns a query needs.
 */
public final class JdbcConformance {

    /** The name of the source of the database. */
    public static final String SOURCE = "db";

    private static final long SEED = 20261014L;
    private static final int ROWS = 300;
    private static final List<String> STRINGS = List.of("", "a", "ab", "Ab", "AB", "b", "x y", " a", "%", "_",
            "\u00E4", "\u00E9t\u00E9", "Zebra", "zebra", "\u65E5\u672C", "a b", "it's");

    private JdbcConformance() {
    }

    /** The table {@code items} with a column of every type the database stores. */
    public static Schema items(JdbcDialect dialect) {
        List<Schema.Field> fields = new ArrayList<>(List.of(new Schema.Field("id", DataType.INTEGER, false),
                field("b", DataType.BOOLEAN), field("ti", DataType.TINYINT), field("si", DataType.SMALLINT),
                field("i", DataType.INTEGER), field("bi", DataType.BIGINT), field("de", DataType.decimal(9, 2)),
                field("f", DataType.FLOAT), field("d", DataType.DOUBLE), field("s", DataType.VARCHAR),
                field("bl", DataType.BLOB), field("dt", DataType.DATE), field("ts", DataType.TIMESTAMP),
                field("u", DataType.UUID)));
        if (dialect != JdbcDialect.SQLITE) {
            fields.add(field("h", DataType.HUGEINT));
        }
        return new Schema(fields);
    }

    private static Schema.Field field(String name, DataType type) {
        return new Schema.Field(name, type, true);
    }

    /**
     * The table {@code items} as it comes back from the database: a type
     * the database does not have is stored as the type the dialect writes
     * for it, {@code TINYINT} in PostgreSQL as {@code SMALLINT}, {@code UUID}
     * in MySQL as {@code CHAR(36)}, which is read as {@code VARCHAR}.
     */
    public static Schema stored(JdbcDialect dialect) {
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : items(dialect).fields()) {
            fields.add(new Schema.Field(f.name(), stored(dialect, f.type()), f.nullable()));
        }
        return new Schema(fields);
    }

    private static DataType stored(JdbcDialect dialect, DataType type) {
        if (dialect == JdbcDialect.POSTGRESQL && type == DataType.TINYINT) {
            return DataType.SMALLINT;
        }
        if (dialect == JdbcDialect.MYSQL && type == DataType.UUID) {
            return DataType.VARCHAR;
        }
        return type;
    }

    /** The rows of {@link #itemRows} with the values of {@link #stored}. */
    public static List<Object[]> storedRows(JdbcDialect dialect) {
        List<Object[]> rows = new ArrayList<>();
        for (Object[] row : itemRows(dialect)) {
            Object[] copy = row.clone();
            for (int c = 0; c < copy.length; c++) {
                if (copy[c] instanceof Byte b && dialect == JdbcDialect.POSTGRESQL) {
                    copy[c] = (short) b;
                } else if (copy[c] instanceof UUID u && dialect == JdbcDialect.MYSQL) {
                    copy[c] = u.toString();
                }
            }
            rows.add(copy);
        }
        return rows;
    }

    /** Random rows of {@link #items}, the same for a dialect in every run. */
    public static List<Object[]> itemRows(JdbcDialect dialect) {
        Schema schema = items(dialect);
        SplittableRandom random = new SplittableRandom(SEED);
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            Object[] row = new Object[schema.size()];
            row[0] = r;
            for (int c = 1; c < schema.size(); c++) {
                row[c] = random.nextInt(100) < 15 ? null : value(schema.type(c), random);
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object value(DataType type, SplittableRandom random) {
        if (type instanceof DataType.Decimal) {
            return BigDecimal.valueOf(random.nextLong(-2_000_000, 2_000_000), 2);
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> random.nextBoolean();
            case TINYINT -> (byte) random.nextInt(-128, 128);
            case SMALLINT -> (short) random.nextInt(-1000, 1000);
            case INTEGER -> random.nextInt(-1000, 1000);
            case BIGINT -> random.nextBoolean() ? random.nextLong() : random.nextLong(-100, 100);
            case HUGEINT -> java.math.BigInteger.valueOf(random.nextLong()).shiftLeft(random.nextInt(60));
            case FLOAT -> random.nextInt(-1000, 1000) / 8f;
            case DOUBLE -> random.nextInt(-100_000, 100_000) / 64.0;
            case VARCHAR -> STRINGS.get(random.nextInt(STRINGS.size()));
            case BLOB -> {
                byte[] b = new byte[random.nextInt(4)];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) random.nextInt(256);
                }
                yield b;
            }
            // 1950-01-01 to 2049-12-31.
            case DATE -> random.nextInt(-7305, 29_220);
            case TIMESTAMP -> random.nextLong(-7305L * 86_400_000_000L, 29_220L * 86_400_000_000L);
            case UUID -> new UUID(random.nextLong(), random.nextLong());
        };
    }

    /** The table {@code items2} of the same database, whose {@code item} refers to an item. */
    static final Schema ITEMS2 = Schema.of(new Schema.Field("id", DataType.INTEGER, false),
            field("item", DataType.INTEGER), field("label", DataType.VARCHAR), field("dt", DataType.DATE));

    /** Random rows of {@code items2}, some without item and some with one that does not exist. */
    static List<Object[]> items2Rows() {
        SplittableRandom random = new SplittableRandom(SEED + 2);
        List<Object[]> rows = new ArrayList<>();
        for (int r = 0; r < ROWS / 2; r++) {
            rows.add(new Object[] {r, random.nextInt(100) < 10 ? null : random.nextInt(ROWS + ROWS / 10),
                    random.nextInt(100) < 10 ? null : STRINGS.get(random.nextInt(STRINGS.size())),
                    random.nextInt(100) < 10 ? null : random.nextInt(-7305, 29_220)});
        }
        return rows;
    }

    /** The table {@code tags} of a memory source, to join with. */
    static final Schema TAGS = Schema.of(new Schema.Field("id", DataType.INTEGER),
            new Schema.Field("tag", DataType.VARCHAR));

    static List<Object[]> tagRows() {
        List<Object[]> rows = new ArrayList<>();
        for (int id = 0; id < ROWS; id += 7) {
            rows.add(new Object[] {id, "tag" + id % 3});
        }
        rows.add(new Object[] {null, "none"});
        return rows;
    }

    /** A join of the two tables of the database. */
    private static final String JOIN = "SELECT items.id, items2.label FROM items JOIN items2 "
            + "ON items.id = items2.item WHERE items.i > 0 AND items2.item < 250";

    /** A semi join and an anti join with a condition besides the key, of the two tables of the database. */
    private static final String EXISTS = "SELECT id, s FROM items WHERE EXISTS (SELECT 1 FROM items2 "
            + "WHERE items2.item = items.id)";
    private static final String NOT_EXISTS = "SELECT id FROM items WHERE NOT EXISTS (SELECT 1 FROM items2 "
            + "WHERE items2.item = items.id AND items2.id > items.i)";

    /** A join on strings, which MySQL compares under a collation that ignores the case. */
    private static final String STRING_JOIN = "SELECT items.id, items2.id, items.s FROM items JOIN items2 "
            + "ON items.s = items2.label";

    /** The queries, with filters of every type and every form a source may take over. */
    static final List<String> QUERIES = List.of(
            "SELECT * FROM items",
            "SELECT id, s FROM items WHERE i > 0",
            "SELECT id FROM items WHERE b",
            "SELECT id FROM items WHERE NOT b",
            "SELECT id FROM items WHERE b = false OR b IS NULL",
            "SELECT id, ti FROM items WHERE ti < 0",
            "SELECT id, si FROM items WHERE si BETWEEN -100 AND 100",
            "SELECT id, bi FROM items WHERE bi >= 0 AND bi < 1000000",
            "SELECT id, de FROM items WHERE de > 10.5",
            "SELECT id, de FROM items WHERE de = 0.5 OR de < -19000",
            "SELECT id, f FROM items WHERE f > 0.5",
            "SELECT id, d FROM items WHERE d < 0",
            "SELECT id, s FROM items WHERE s = 'ab'",
            "SELECT id, s FROM items WHERE s <> 'ab'",
            "SELECT id, s FROM items WHERE s IN ('a', 'Ab', '\u00E4', 'it''s')",
            "SELECT id, s FROM items WHERE s < 'b'",
            "SELECT id, s FROM items WHERE s >= 'Zebra' AND s <= 'zebra'",
            "SELECT id, s FROM items WHERE s LIKE 'a%'",
            "SELECT id, dt FROM items WHERE dt > DATE '2000-01-01'",
            "SELECT id, dt FROM items WHERE dt BETWEEN DATE '1960-01-01' AND DATE '1970-12-31'",
            "SELECT id, ts FROM items WHERE ts < TIMESTAMP '2001-01-01 00:00:00'",
            "SELECT id, u FROM items WHERE u IS NULL",
            "SELECT id, bl FROM items WHERE bl IS NOT NULL",
            "SELECT id, i FROM items WHERE i IS NULL OR i > 500",
            "SELECT id, i FROM items WHERE i IN (1, 2, 3, -7) AND ti IS NOT NULL",
            "SELECT id FROM items WHERE id IS DISTINCT FROM 5 AND id < 10",
            "SELECT id FROM items WHERE i IS NOT DISTINCT FROM NULL",
            "SELECT id, i FROM items WHERE i > 0 AND d > 0",
            "SELECT id, i FROM items WHERE i + 1 > 0",
            "SELECT id, i FROM items ORDER BY i, id LIMIT 10",
            "SELECT id, i FROM items ORDER BY i DESC NULLS FIRST, id LIMIT 5 OFFSET 3",
            "SELECT id, s FROM items ORDER BY s, id LIMIT 7",
            "SELECT id, de FROM items ORDER BY de DESC, id LIMIT 4",
            "SELECT id, ts FROM items WHERE ts IS NOT NULL ORDER BY ts DESC, id LIMIT 3",
            "SELECT id, dt FROM items WHERE i > 0 ORDER BY dt, id LIMIT 6",
            "SELECT id, b FROM items ORDER BY b NULLS FIRST, id DESC",
            "SELECT count(*) FROM (SELECT id FROM items LIMIT 5) x",
            "SELECT count(*), count(i), sum(i), min(dt), max(ts) FROM items",
            "SELECT b, count(*), sum(i), min(dt), max(s) FROM items WHERE bi > 0 GROUP BY b",
            "SELECT ti % 3 AS k, count(*) FROM items WHERE ti IS NOT NULL GROUP BY ti % 3",
            "SELECT count(*) FROM items",
            "SELECT count(*), count(s), count(d), count(u), count(bl), min(i), max(bi), min(ti), max(si) FROM items",
            "SELECT count(i), min(de), max(de), min(dt), max(ts), sum(ti), sum(si), sum(bi) FROM items WHERE i > 0",
            "SELECT b, count(*), min(i), max(i), sum(i) FROM items GROUP BY b",
            "SELECT ti, sum(bi), sum(i), count(d) FROM items GROUP BY ti",
            "SELECT s, count(*), max(i) FROM items GROUP BY s",
            "SELECT s, count(*), min(de), max(dt), min(ts) FROM items WHERE id > 10 GROUP BY s",
            "SELECT dt, count(*), sum(i) FROM items WHERE dt IS NOT NULL GROUP BY dt",
            "SELECT de, ts, count(*) FROM items GROUP BY de, ts",
            "SELECT b, si, count(*) FROM items WHERE si BETWEEN -100 AND 100 GROUP BY b, si",
            "SELECT count(*), sum(i), min(i), max(dt) FROM items WHERE id < 0",
            "SELECT b, count(*) FROM items WHERE id < 0 GROUP BY b",
            "SELECT b, count(*) FROM items GROUP BY b HAVING count(*) > 10 ORDER BY b",
            "SELECT count(DISTINCT i), count(*) FROM items",
            "SELECT count(*), max(i) FROM items WHERE d > 0",
            "SELECT d, count(*) FROM items GROUP BY d",
            "SELECT ts, count(*) FROM items GROUP BY ts",
            "SELECT items.id, tags.tag FROM items JOIN tags ON items.id = tags.id WHERE items.i > 0",
            "SELECT tags.tag, count(*) FROM items JOIN tags ON items.id = tags.id GROUP BY tags.tag",
            "SELECT * FROM items WHERE id < 20",
            JOIN,
            "SELECT items.id, items2.id FROM items LEFT JOIN items2 ON items.id = items2.item "
                    + "AND items2.dt > DATE '2000-01-01'",
            "SELECT count(*), count(items.id) FROM items RIGHT JOIN items2 ON items.id = items2.item",
            "SELECT items2.label, count(*), sum(items.i), min(items.dt), max(items.de) FROM items "
                    + "JOIN items2 ON items.id = items2.item WHERE items.bi > 0 GROUP BY items2.label",
            "SELECT items.id, items2.id, items.ts FROM items JOIN items2 ON items.id = items2.item "
                    + "ORDER BY items.id, items2.id LIMIT 10",
            "SELECT items.id, items2.id FROM items, items2 WHERE items.id = items2.item AND items.d > 0",
            STRING_JOIN,
            "SELECT items.id, items2.label, tags.tag FROM items JOIN items2 ON items.id = items2.item "
                    + "JOIN tags ON items.id = tags.id",
            "SELECT items.id, items2.id FROM items LEFT JOIN items2 ON items.id = items2.item "
                    + "AND items2.dt > items.dt",
            EXISTS,
            NOT_EXISTS,
            "SELECT id FROM items WHERE EXISTS (SELECT 1 FROM items2 WHERE items2.item = items.id "
                    + "AND items2.dt > items.dt)",
            "SELECT id, i FROM items WHERE i > 0 AND NOT EXISTS (SELECT 1 FROM items2 WHERE items2.label = 'a')",
            "SELECT id FROM items WHERE id IN (SELECT item FROM items2 WHERE label IS NOT NULL)",
            "SELECT id FROM items WHERE id NOT IN (SELECT item FROM items2 WHERE item IS NOT NULL)",
            "SELECT id FROM items WHERE EXISTS (SELECT 1 FROM tags WHERE tags.id = items.id)");

    /**
     * Runs the conformance over a database: loads the tables, checks that
     * the catalog reads the table back with the types it stores, runs every query in
     * an engine with the pushdown of the run and in one without, compares
     * both with DuckDB, and checks the statements the database receives.
     */
    public static void check(TestDatabase db) {
        JdbcDialect dialect = db.dialect();
        db.load("items", items(dialect), itemRows(dialect));
        db.load("items2", ITEMS2, items2Rows());
        Schema stored = stored(dialect);
        JdbcConnector primary = db.connector(SOURCE);
        JdbcConnector plain = db.connector(SOURCE);
        assertEquals(stored, primary.catalog().schema(TableRef.of(SOURCE, "items")), dialect + " reads the table back");
        try (FyrQL engine = engine(primary, null); FyrQL off = engine(plain, false);
                DuckDbReference duck = new DuckDbReference()) {
            duck.load("items", stored, List.of(Batch.ofRows(stored, storedRows(dialect))));
            duck.load("items2", ITEMS2, List.of(Batch.ofRows(ITEMS2, items2Rows())));
            duck.load("tags", TAGS, List.of(Batch.ofRows(TAGS, tagRows())));
            List<String> failures = new ArrayList<>();
            int pushed = 0;
            for (String sql : QUERIES) {
                try {
                    PlanNode plan = engine.plan(sql);
                    String explain = engine.explain(plan);
                    if (explain.contains("[pushed:") || explain.contains("Remote " + SOURCE)) {
                        pushed++;
                    }
                    Schema schema = plan.schema();
                    boolean ordered = Plans.ordered(plan);
                    List<Object[]> actual = engine.rows(plan);
                    ResultComparison.Outcome modes = ResultComparison.compare(schema, off.rows(plan), actual, ordered);
                    if (!modes.same()) {
                        failures.add(sql + "\n  with and without pushdown differ:\n" + modes.report() + "\n"
                                + engine.explain(plan));
                        continue;
                    }
                    ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema), actual,
                            ordered);
                    if (!oracle.same()) {
                        failures.add(sql + "\n" + oracle.report() + "\n" + engine.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
            if (!failures.isEmpty()) {
                fail(dialect + ": " + failures.size() + " of " + QUERIES.size() + " queries differ:\n\n"
                        + String.join("\n\n", failures));
            }
            if (engine.pushdown()) {
                assertTrue(pushed >= QUERIES.size() / 2, dialect + " takes over parts of " + pushed + " queries");
            } else {
                assertEquals(0, pushed);
            }
            for (String sent : plain.statements()) {
                assertFalse(sent.contains(" WHERE ") || sent.contains(" ORDER BY ") || sent.contains(" LIMIT ")
                        || sent.contains(" GROUP BY ") || sent.contains("count(") || sent.contains(" JOIN "), sent);
            }
            checkStatements(engine, primary, dialect);
        }
    }

    private static FyrQL engine(JdbcConnector connector, Boolean pushdown) {
        FyrQL.Builder b = FyrQL.builder().register(connector)
                .register(MemoryConnector.builder("memory").table("tags", TAGS, tagRows()).build())
                .defaultSource(SOURCE);
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        return b.build();
    }

    /** The statement a query sends to the database. */
    private static String sent(FyrQL engine, JdbcConnector connector, String sql) {
        connector.clearStatements();
        engine.rows(sql);
        assertEquals(1, connector.statements().size(), sql);
        return connector.statements().get(0);
    }

    /**
     * The database receives the columns a query needs and, with pushdown,
     * the filters, the order, the limit and the aggregation its dialect
     * takes over.
     */
    private static void checkStatements(FyrQL engine, JdbcConnector connector, JdbcDialect dialect) {
        String q = dialect.sql().quoteIdentifier("x").substring(0, 1);
        String select = "SELECT t." + q + "id" + q + ", t." + q + "i" + q + ", t." + q + "s" + q + " FROM ";
        boolean on = engine.pushdown();
        String filtered = sent(engine, connector, "SELECT s FROM items WHERE i > 0 AND id < 100");
        assertTrue(filtered.startsWith(select) && filtered.contains("." + q + "items" + q + " t"), filtered);
        assertEquals(on, filtered.contains(" WHERE ((t." + q + "i" + q + " > "), filtered);

        String limited = sent(engine, connector, "SELECT id, i FROM items ORDER BY i, id LIMIT 5 OFFSET 3");
        assertEquals(on, limited.contains(" ORDER BY t.") && limited.endsWith(" LIMIT 8"), limited);

        // Strings are ordered by their bytes only in SQLite.
        String strings = sent(engine, connector, "SELECT id, s FROM items ORDER BY s, id LIMIT 7");
        assertEquals(on && dialect == JdbcDialect.SQLITE, strings.endsWith(" LIMIT 7"), strings);

        // A filter on a double stays with the engine, and so does the limit above it.
        String partly = sent(engine, connector, "SELECT id FROM items WHERE i > 0 AND d > 0 LIMIT 3");
        assertEquals(on, partly.contains(" WHERE "), partly);
        assertFalse(partly.contains(" LIMIT "), partly);
        assertFalse(partly.contains(q + "d" + q + " >"), partly);

        // Counts and extremes of integers are computed in the database, with the groups.
        String grouped = sent(engine, connector, "SELECT b, count(*), min(i), max(bi) FROM items WHERE i > 0 GROUP BY b");
        assertEquals(on, grouped.startsWith("SELECT t." + q + "b" + q + ", count(*), min(t." + q + "i" + q + "), max(t.")
                && grouped.contains(" WHERE ") && grouped.endsWith(" GROUP BY t." + q + "b" + q), grouped);

        // A sum is cast to the type FyrQL gives it, except in SQLite, which has none and leaves the sum to the engine.
        String summed = sent(engine, connector, "SELECT ti, sum(bi) FROM items GROUP BY ti");
        assertEquals(on && dialect != JdbcDialect.SQLITE,
                summed.contains(", CAST(sum(t." + q + "bi" + q + ") AS ") && summed.contains(" GROUP BY "), summed);

        // MySQL groups strings by a collation that ignores the case.
        String byString = sent(engine, connector, "SELECT s, count(*) FROM items GROUP BY s");
        assertEquals(on && dialect != JdbcDialect.MYSQL, byString.endsWith(" GROUP BY t." + q + "s" + q), byString);

        // Doubles are aggregated by the engine.
        String doubles = sent(engine, connector, "SELECT count(*), max(d) FROM items");
        assertFalse(doubles.contains("count("), doubles);

        // A join of two tables of the database is one statement with its filters.
        List<String> joined = statements(engine, connector, JOIN);
        if (on) {
            assertEquals(1, joined.size(), joined.toString());
            assertTrue(joined.get(0).contains(" INNER JOIN ") && joined.get(0).contains(" WHERE "), joined.get(0));
        } else {
            assertEquals(2, joined.size(), joined.toString());
        }

        // MySQL compares strings under a collation that ignores the case, so the engine joins on them.
        assertEquals(on && dialect != JdbcDialect.MYSQL ? 1 : 2, statements(engine, connector, STRING_JOIN).size());

        // A semi or anti join of two tables of the database is a filter with EXISTS or NOT EXISTS.
        List<String> semi = statements(engine, connector, EXISTS);
        assertEquals(on ? 1 : 2, semi.size(), semi.toString());
        assertEquals(on, semi.get(0).contains(" WHERE EXISTS (SELECT 1 FROM "), semi.get(0));
        List<String> anti = statements(engine, connector, NOT_EXISTS);
        assertEquals(on ? 1 : 2, anti.size(), anti.toString());
        assertEquals(on, anti.get(0).contains(" WHERE NOT EXISTS (SELECT 1 FROM ") && anti.get(0).contains(" > "),
                anti.get(0));
    }

    /** The statements a query sends to the database, none with a join unless it is the only one. */
    private static List<String> statements(FyrQL engine, JdbcConnector connector, String sql) {
        connector.clearStatements();
        engine.rows(sql);
        List<String> sent = connector.statements();
        for (String s : sent) {
            assertTrue(sent.size() == 1 || !s.contains(" JOIN "), s);
        }
        return sent;
    }
}
