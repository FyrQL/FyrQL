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
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import net.fyrql.sql.render.GenericDialect;
import net.fyrql.sql.render.H2Dialect;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
 * only the columns a query needs. A third engine reads {@code items} in
 * ranges of {@code id}, see {@link JdbcConnector.Builder#splitRows}, and
 * must give the same rows.
 * <p>
 * A database without a dialect of its own is read through the generic
 * dialect, see {@link GenericDialect}: H2 in its modes of compatibility,
 * Derby and HSQLDB. It takes over fewer parts, binds its literals and
 * sends conditions on strings as hints, and its tables are created with
 * type names of its own.
 */
public final class JdbcConformance {

    /** The name of the source of the database. */
    public static final String SOURCE = "db";

    private static final long SEED = 20261014L;
    private static final int ROWS = 300;
    /** The rows of the parts {@code items} is read in by the third engine: four, one per thread, rather than five. */
    private static final long SPLIT_ROWS = 64;
    /** The threads of the third engine, which reads no more parts at a time. */
    private static final int SPLIT_THREADS = 4;
    private static final List<String> STRINGS = List.of("", "a", "ab", "Ab", "AB", "b", "x y", " a", "%", "_",
            "\u00E4", "\u00E9t\u00E9", "Zebra", "zebra", "\u65E5\u672C", "a b", "it's");

    /**
     * The queries of which the generic dialect takes over a part: those
     * with a filter on an integer or a date, as counted on 20 October 2026;
     * the other dialects take over half of them at least.
     */
    private static final int GENERIC_PUSHED = 27;
    /** As {@link #GENERIC_PUSHED}, but a date of H2 in the mode Oracle is a timestamp, which is not taken over. */
    private static final int ORACLE_PUSHED = 23;

    private JdbcConformance() {
    }

    /**
     * A database with what the conformance needs to know about it.
     *
     * @param name the name of the database in a message
     * @param items the table {@code items} as it is written
     * @param typeNames the names of the types the tables are created with,
     *        or {@code null} for those the dialect of the database writes
     * @param storedType the type a column comes back as, see {@link #stored(JdbcDialect)}
     * @param storedValue the value a value of a type comes back as
     * @param minPushed the queries of which the database takes over a part, at least, with pushdown
     * @param settings further settings of the connectors
     */
    public record Target(TestDatabase db, String name, Schema items, Function<DataType, String> typeNames,
            UnaryOperator<DataType> storedType, BiFunction<DataType, Object, Object> storedValue, int minPushed,
            Consumer<JdbcConnector.Builder> settings) {

        /** A database of a dialect of its own. */
        public static Target of(TestDatabase db) {
            JdbcDialect dialect = db.dialect();
            // The names are qualified: the components of the record hide the methods of the class.
            return new Target(db, dialect.toString(), JdbcConformance.items(dialect), null,
                    t -> JdbcConformance.stored(dialect, t), (t, v) -> JdbcConformance.storedValue(dialect, v),
                    QUERIES.size() / 2, b -> {
                    });
        }

        boolean generic() {
            return db.dialect() == JdbcDialect.GENERIC;
        }

        /** A table as it comes back from the database. */
        Schema stored(Schema written) {
            List<Schema.Field> fields = new ArrayList<>();
            for (Schema.Field f : written.fields()) {
                fields.add(new Schema.Field(f.name(), storedType.apply(f.type()), f.nullable()));
            }
            return new Schema(fields);
        }

        /** Rows of a table as they come back from the database. */
        List<Object[]> storedRows(Schema written, List<Object[]> rows) {
            List<Object[]> stored = new ArrayList<>();
            for (Object[] row : rows) {
                Object[] copy = row.clone();
                for (int c = 0; c < copy.length; c++) {
                    copy[c] = copy[c] == null ? null : storedValue.apply(written.type(c), copy[c]);
                }
                stored.add(copy);
            }
            return stored;
        }

        void load(String table, Schema schema, List<Object[]> rows) {
            if (typeNames == null) {
                db.load(table, schema, rows);
            } else {
                db.load(table, schema, typeNames, rows);
            }
        }
    }

    /**
     * H2 in a mode of compatibility, read through the generic dialect. In
     * the mode {@code Oracle} a {@code DATE} is a {@code TIMESTAMP(0)} and
     * an empty string is {@code NULL}.
     */
    public static Target h2(String mode, Consumer<JdbcConnector.Builder> settings) {
        boolean oracle = mode.equals("Oracle");
        return new Target(TestDatabases.h2Mode(mode), "H2 in the mode " + mode, items(true),
                H2Dialect.INSTANCE::typeName, t -> oracle && t == DataType.DATE ? DataType.TIMESTAMP
                        : t == DataType.INTERVAL ? DataType.VARCHAR : t,
                (t, v) -> v instanceof Interval i ? i.toString() : !oracle ? v
                        : t == DataType.DATE ? (Integer) v * 86_400_000_000L : "".equals(v) ? null : v,
                oracle ? ORACLE_PUSHED : GENERIC_PUSHED, settings);
    }

    /**
     * Derby, read through the generic dialect with {@code FETCH FIRST}: no
     * {@code TINYINT}, which is a {@code SMALLINT}, no {@code UUID}, which
     * is a {@code CHAR(36)}, no decimal as wide as a {@code HUGEINT}, and no
     * timestamp with a time zone, which is one in UTC.
     */
    public static Target derby() {
        return new Target(TestDatabases.derby(), "Derby", items(false), t -> sql92(t, "SMALLINT", "BLOB", "TIMESTAMP",
                "CHAR(36)"), t -> t == DataType.TINYINT ? DataType.SMALLINT
                        : t == DataType.UUID || t == DataType.INTERVAL ? DataType.VARCHAR
                        : t == DataType.TIMESTAMPTZ ? DataType.TIMESTAMP : t,
                (t, v) -> v instanceof Byte b ? (Object) (short) b : v instanceof UUID || v instanceof Interval
                        ? v.toString() : v,
                GENERIC_PUSHED, b -> b.limitStyle(GenericDialect.LimitStyle.FETCH_FIRST));
    }

    /**
     * HSQLDB, read through the generic dialect, whose {@code REAL} is a
     * {@code DOUBLE}, with a timestamp in UTC for one with a time zone.
     */
    public static Target hsqldb() {
        return new Target(TestDatabases.hsqldb(), "HSQLDB", items(true),
                t -> t == DataType.HUGEINT ? "DECIMAL(39,0)" : sql92(t, "TINYINT", "VARBINARY(100)", "TIMESTAMP(6)",
                        "UUID"), t -> t == DataType.FLOAT ? DataType.DOUBLE : t == DataType.INTERVAL ? DataType.VARCHAR
                        : t == DataType.TIMESTAMPTZ ? DataType.TIMESTAMP : t,
                (t, v) -> v instanceof Float f ? (Object) (double) f : v instanceof Interval i ? i.toString() : v,
                GENERIC_PUSHED, b -> {
                });
    }

    /** The name of a type in SQL-92, with the names of a database for the types that differ. */
    private static String sql92(DataType type, String tinyint, String blob, String timestamp, String uuid) {
        if (type instanceof DataType.Decimal d) {
            return "DECIMAL(" + d.precision() + "," + d.scale() + ")";
        }
        return switch ((DataType.Scalar) type) {
            case BOOLEAN -> "BOOLEAN";
            case TINYINT -> tinyint;
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE";
            case VARCHAR -> "VARCHAR(100)";
            case BLOB -> blob;
            case DATE -> "DATE";
            case TIMESTAMP -> timestamp;
            case UUID -> uuid;
            case TIME -> "TIME";
            case TIMESTAMPTZ -> timestamp;
            case INTERVAL -> "VARCHAR(64)";
            case HUGEINT -> throw new IllegalArgumentException("No type for HUGEINT");
        };
    }

    /** The table {@code items} with a column of every type the database stores. */
    public static Schema items(JdbcDialect dialect) {
        return items(dialect != JdbcDialect.SQLITE);
    }

    private static Schema items(boolean hugeint) {
        List<Schema.Field> fields = new ArrayList<>(List.of(new Schema.Field("id", DataType.INTEGER, false),
                field("b", DataType.BOOLEAN), field("ti", DataType.TINYINT), field("si", DataType.SMALLINT),
                field("i", DataType.INTEGER), field("bi", DataType.BIGINT), field("de", DataType.decimal(9, 2)),
                field("f", DataType.FLOAT), field("d", DataType.DOUBLE), field("s", DataType.VARCHAR),
                field("bl", DataType.BLOB), field("dt", DataType.DATE), field("ts", DataType.TIMESTAMP),
                field("u", DataType.UUID), field("tm", DataType.TIME), field("tz", DataType.TIMESTAMPTZ),
                field("iv", DataType.INTERVAL)));
        if (hugeint) {
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
     * in MySQL as {@code CHAR(36)}, which is read as {@code VARCHAR}, a
     * timestamp with a time zone where only PostgreSQL and H2 have one as
     * one in UTC, an interval where only PostgreSQL has one as its text.
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
        boolean instants = dialect == JdbcDialect.POSTGRESQL || dialect == JdbcDialect.H2;
        if (type == DataType.TIMESTAMPTZ && !instants) {
            return DataType.TIMESTAMP;
        }
        if (type == DataType.INTERVAL && dialect != JdbcDialect.POSTGRESQL) {
            return DataType.VARCHAR;
        }
        return type;
    }

    /** A value as it comes back from the database: the values of {@link #stored}. */
    private static Object storedValue(JdbcDialect dialect, Object value) {
        if (value instanceof Byte b && dialect == JdbcDialect.POSTGRESQL) {
            return (short) b;
        }
        if (value instanceof UUID u && dialect == JdbcDialect.MYSQL) {
            return u.toString();
        }
        if (value instanceof Interval i && dialect != JdbcDialect.POSTGRESQL) {
            return i.toString();
        }
        return value;
    }

    /** Random rows of {@link #items}, the same for a dialect in every run. */
    public static List<Object[]> itemRows(JdbcDialect dialect) {
        return itemRows(items(dialect));
    }

    /** Random rows of a table like {@code items}, the same for a schema in every run. */
    private static List<Object[]> itemRows(Schema schema) {
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
            case TIMESTAMP, TIMESTAMPTZ -> random.nextLong(-7305L * 86_400_000_000L, 29_220L * 86_400_000_000L);
            case UUID -> new UUID(random.nextLong(), random.nextLong());
            case TIME -> random.nextLong(86_400) * 1_000_000L;
            case INTERVAL -> net.fyrql.core.value.Interval.of(random.nextInt(-24, 25), random.nextInt(-40, 41),
                    random.nextLong(-100_000, 100_001) * 1_000_000L);
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
            // A boolean compared with a number, which PostgreSQL has no operator for, stays in the engine.
            "SELECT id, i FROM items WHERE i = false OR i IN (true, 2) OR b = 1",
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
            "SELECT id, tm FROM items WHERE tm < TIME '12:00:00'",
            "SELECT id, tz FROM items WHERE tz >= TIMESTAMPTZ '2000-01-01 00:00:00+00'",
            "SELECT id, tz, tm FROM items WHERE tz IS NOT NULL ORDER BY tz DESC, id LIMIT 3",
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
     * both with DuckDB and with an engine that reads in parts, and checks
     * the statements the database receives.
     */
    public static void check(TestDatabase db) {
        check(Target.of(db));
    }

    /** Runs the conformance over a database as {@link #check(TestDatabase)} does. */
    public static void check(Target target) {
        TestDatabase db = target.db();
        String name = target.name();
        List<Object[]> rows = itemRows(target.items());
        target.load("items", target.items(), rows);
        target.load("items2", ITEMS2, items2Rows());
        Schema stored = target.stored(target.items());
        Schema stored2 = target.stored(ITEMS2);
        JdbcConnector primary = db.connector(SOURCE, target.settings());
        JdbcConnector plain = db.connector(SOURCE, target.settings());
        JdbcConnector split = db.connector(SOURCE, target.settings().andThen(b -> b.splitRows(SPLIT_ROWS)
                .splitColumn("items", "id")));
        assertEquals(stored, primary.catalog().schema(TableRef.of(SOURCE, "items")), name + " reads the table back");
        try (FyrQL engine = engine(primary, null); FyrQL off = engine(plain, false); FyrQL parts = engine(split, null, SPLIT_THREADS);
                DuckDbReference duck = new DuckDbReference()) {
            duck.load("items", stored, List.of(Batch.ofRows(stored, target.storedRows(target.items(), rows))));
            duck.load("items2", stored2, List.of(Batch.ofRows(stored2, target.storedRows(ITEMS2, items2Rows()))));
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
                    ResultComparison.Outcome inParts = ResultComparison.compare(schema, parts.rows(plan), actual,
                            ordered);
                    if (!inParts.same()) {
                        failures.add(sql + "\n  read in parts and in one differ:\n" + inParts.report() + "\n"
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
                fail(name + ": " + failures.size() + " of " + QUERIES.size() + " queries differ:\n\n"
                        + String.join("\n\n", failures));
            }
            if (engine.pushdown()) {
                assertTrue(pushed >= target.minPushed(), name + " takes over parts of " + pushed + " queries");
            } else {
                assertEquals(0, pushed);
            }
            assertTrue(split.statements().stream().filter(s -> s.startsWith("SELECT min(")).count() >= 10,
                    name + " reads items in parts: " + split.statements());
            for (String sent : plain.statements()) {
                assertFalse(sent.contains(" WHERE ") || sent.contains(" ORDER BY ") || sent.contains(" LIMIT ")
                        || sent.contains(" GROUP BY ") || sent.contains("count(") || sent.contains(" JOIN ")
                        || sent.contains(" TOP ") || sent.contains(" FETCH FIRST ") || sent.contains(" -- "), sent);
            }
            checkStatements(engine, primary, target);
        }
    }

    private static FyrQL engine(JdbcConnector connector, Boolean pushdown) {
        return engine(connector, pushdown, 0);
    }

    /** An engine over the connector and the tags in memory, on some threads, or as many as set if 0. */
    private static FyrQL engine(JdbcConnector connector, Boolean pushdown, int threads) {
        FyrQL.Builder b = FyrQL.builder().register(connector)
                .register(MemoryConnector.builder("memory").table("tags", TAGS, tagRows()).build())
                .defaultSource(SOURCE);
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        if (threads > 0) {
            b.threads(threads);
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
    private static void checkStatements(FyrQL engine, JdbcConnector connector, Target target) {
        JdbcDialect dialect = target.db().dialect();
        boolean generic = target.generic();
        UnaryOperator<String> q = connector.sql()::quoteIdentifier;
        String select = "SELECT t." + q.apply("id") + ", t." + q.apply("i") + ", t." + q.apply("s") + " FROM ";
        boolean on = engine.pushdown();
        String filtered = sent(engine, connector, "SELECT s FROM items WHERE i > 0 AND id < 100");
        assertTrue(filtered.startsWith(select) && filtered.contains("." + q.apply("items") + " t"), filtered);
        assertEquals(on, filtered.contains(" WHERE ((t." + q.apply("i") + " > "), filtered);
        // The generic dialect binds the literals.
        assertEquals(on && generic, filtered.endsWith(" -- parameters: 0, 100"), filtered);

        // The generic dialect orders nothing, so the limit above the order stays with the engine as well.
        String limited = sent(engine, connector, "SELECT id, i FROM items ORDER BY i, id LIMIT 5 OFFSET 3");
        assertEquals(on && !generic, limited.contains(" ORDER BY t.") && limited.endsWith(" LIMIT 8"), limited);

        // Strings are ordered by their bytes only in SQLite.
        String strings = sent(engine, connector, "SELECT id, s FROM items ORDER BY s, id LIMIT 7");
        assertEquals(on && dialect == JdbcDialect.SQLITE, strings.endsWith(" LIMIT 7"), strings);

        // A filter on a double stays with the engine, and so does the limit above it.
        String partly = sent(engine, connector, "SELECT id FROM items WHERE i > 0 AND d > 0 LIMIT 3");
        assertEquals(on, partly.contains(" WHERE "), partly);
        assertFalse(partly.contains(" LIMIT ") || partly.contains(" TOP ") || partly.contains(" FETCH FIRST ")
                || partly.contains("max rows"), partly);
        assertFalse(partly.contains(q.apply("d") + " >"), partly);

        // Counts and extremes of integers are computed in the database, with the groups; not in the generic dialect.
        String grouped = sent(engine, connector, "SELECT b, count(*), min(i), max(bi) FROM items WHERE i > 0 GROUP BY b");
        assertEquals(on && !generic, grouped.startsWith("SELECT t." + q.apply("b") + ", count(*), min(t." + q.apply("i")
                + "), max(t.") && grouped.contains(" WHERE ") && grouped.endsWith(" GROUP BY t." + q.apply("b")), grouped);

        // A sum is cast to the type FyrQL gives it, except in SQLite, which has none and leaves the sum to the engine.
        String summed = sent(engine, connector, "SELECT ti, sum(bi) FROM items GROUP BY ti");
        assertEquals(on && dialect != JdbcDialect.SQLITE && !generic,
                summed.contains(", CAST(sum(t." + q.apply("bi") + ") AS ") && summed.contains(" GROUP BY "), summed);

        // MySQL groups strings by a collation that ignores the case.
        String byString = sent(engine, connector, "SELECT s, count(*) FROM items GROUP BY s");
        assertEquals(on && dialect != JdbcDialect.MYSQL && !generic, byString.endsWith(" GROUP BY t." + q.apply("s")),
                byString);

        // Doubles are aggregated by the engine.
        String doubles = sent(engine, connector, "SELECT count(*), max(d) FROM items");
        assertFalse(doubles.contains("count("), doubles);

        // A join of two tables of the database is one statement with its filters; the generic dialect joins nothing.
        List<String> joined = statements(engine, connector, JOIN);
        if (on && !generic) {
            assertEquals(1, joined.size(), joined.toString());
            assertTrue(joined.get(0).contains(" INNER JOIN ") && joined.get(0).contains(" WHERE "), joined.get(0));
        } else {
            assertEquals(2, joined.size(), joined.toString());
        }

        // MySQL compares strings under a collation that ignores the case, so the engine joins on them.
        assertEquals(on && dialect != JdbcDialect.MYSQL && !generic ? 1 : 2,
                statements(engine, connector, STRING_JOIN).size());

        // A semi or anti join of two tables of the database is a filter with EXISTS or NOT EXISTS.
        List<String> semi = statements(engine, connector, EXISTS);
        assertEquals(on && !generic ? 1 : 2, semi.size(), semi.toString());
        assertEquals(on && !generic, semi.get(0).contains(" WHERE EXISTS (SELECT 1 FROM "), semi.get(0));
        List<String> anti = statements(engine, connector, NOT_EXISTS);
        assertEquals(on && !generic ? 1 : 2, anti.size(), anti.toString());
        assertEquals(on && !generic, anti.get(0).contains(" WHERE NOT EXISTS (SELECT 1 FROM ")
                && anti.get(0).contains(" > "), anti.get(0));

        if (generic) {
            checkGenericStatements(engine, connector, q);
        }
    }

    /** The generic dialect keeps the first rows as its settings say and sends conditions on strings as hints. */
    private static void checkGenericStatements(FyrQL engine, JdbcConnector connector, UnaryOperator<String> q) {
        boolean on = engine.pushdown();
        GenericDialect.Settings settings = ((GenericDialect) connector.sql()).settings();
        String first = sent(engine, connector, "SELECT id, i FROM items LIMIT 5");
        boolean kept = switch (settings.limit()) {
            case MAX_ROWS -> first.endsWith(" -- max rows: 5");
            case LIMIT -> first.endsWith(" LIMIT 5");
            case FETCH_FIRST -> first.endsWith(" FETCH FIRST 5 ROWS ONLY");
            case TOP -> first.startsWith("SELECT TOP 5 ");
        };
        assertEquals(on, kept, first);

        String hint = sent(engine, connector, "SELECT id FROM items WHERE s = 'ab'");
        assertEquals(on, hint.contains(" WHERE (t." + q.apply("s") + " = ?)") && hint.endsWith(" -- parameters: 'ab'"),
                hint);
        String unequal = sent(engine, connector, "SELECT id FROM items WHERE s <> 'ab'");
        assertFalse(unequal.contains(" WHERE "), unequal);
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
