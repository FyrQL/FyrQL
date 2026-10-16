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

package net.fyrql.sql.render;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.AggregateCall;
import net.fyrql.core.plan.PlanNode.AggregateFunction;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.plan.PlanNode.SortKey;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static net.fyrql.core.expr.Expressions.add;
import static net.fyrql.core.expr.Expressions.and;
import static net.fyrql.core.expr.Expressions.between;
import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.in;
import static net.fyrql.core.expr.Expressions.isDistinct;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.like;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.lt;
import static net.fyrql.core.expr.Expressions.ne;
import static net.fyrql.core.expr.Expressions.not;
import static net.fyrql.core.expr.Expressions.or;
import static net.fyrql.core.expr.Expressions.tryCast;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialects: every one renders the full plan as its golden text, writes
 * its literals and type names, refuses the forms it has no spelling for,
 * and answers conservatively what a source may evaluate.
 */
class DialectsTest {

    private static final List<Dialect> ALL = List.of(DuckDbDialect.INSTANCE, H2Dialect.INSTANCE,
            SqliteDialect.INSTANCE, PostgresDialect.INSTANCE, MySqlDialect.INSTANCE);

    private static final List<Dialect> DATABASES = ALL.subList(1, ALL.size());

    private static String golden(Dialect dialect) throws IOException {
        try (InputStream in = DialectsTest.class.getResourceAsStream("full-plan." + dialect.name() + ".sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        }
    }

    @Test
    void everyDialectRendersTheFullPlanAsItsGoldenText() throws IOException {
        for (Dialect d : ALL) {
            SqlRenderer renderer = new SqlRenderer(d, FullPlan::tableName);
            assertEquals(golden(d), renderer.render(FullPlan.plan()), d.name());
        }
        // The text of DuckDB is the text of FyrQL, which its own parser reads.
        SqlParser.parse(golden(DuckDbDialect.INSTANCE));
    }

    @Test
    void theTypeNamesAreThoseOfTheDatabase() {
        assertEquals("CREATE TABLE o (\"id\" INTEGER, \"amount\" NUMERIC(10,2), \"note\" VARCHAR, \"paid\" BOOLEAN, "
                + "\"day\" DATE, \"at\" TIMESTAMP(6), \"weight\" DOUBLE PRECISION, \"tiny\" TINYINT, \"small\" SMALLINT, "
                + "\"big\" BIGINT, \"ratio\" REAL, \"raw\" VARBINARY, \"key\" UUID)",
                new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).createTable("o", FullPlan.ORDERS));
        assertEquals("CREATE TABLE o (\"id\" INT4, \"amount\" DECIMAL(10,2), \"note\" VARCHAR, \"paid\" BOOLEAN, "
                + "\"day\" DATE, \"at\" TIMESTAMP, \"weight\" DOUBLE, \"tiny\" TINYINT, \"small\" SMALLINT, "
                + "\"big\" BIGINT, \"ratio\" FLOAT, \"raw\" BLOB, \"key\" UUID)",
                new SqlRenderer(SqliteDialect.INSTANCE, FullPlan::tableName).createTable("o", FullPlan.ORDERS));
        assertEquals("CREATE TABLE o (\"id\" INTEGER, \"amount\" NUMERIC(10,2), \"note\" VARCHAR, \"paid\" BOOLEAN, "
                + "\"day\" DATE, \"at\" TIMESTAMP, \"weight\" DOUBLE PRECISION, \"tiny\" SMALLINT, \"small\" SMALLINT, "
                + "\"big\" BIGINT, \"ratio\" REAL, \"raw\" BYTEA, \"key\" UUID)",
                new SqlRenderer(PostgresDialect.INSTANCE, FullPlan::tableName).createTable("o", FullPlan.ORDERS));
        assertEquals("CREATE TABLE o (`id` INT, `amount` DECIMAL(10,2), `note` TEXT, `paid` BOOLEAN, `day` DATE, "
                + "`at` DATETIME(6), `weight` DOUBLE, `tiny` TINYINT, `small` SMALLINT, `big` BIGINT, `ratio` FLOAT, "
                + "`raw` BLOB, `key` CHAR(36))",
                new SqlRenderer(MySqlDialect.INSTANCE, FullPlan::tableName).createTable("o", FullPlan.ORDERS));

        assertEquals("NUMERIC(39)", H2Dialect.INSTANCE.typeName(DataType.HUGEINT));
        assertEquals("NUMERIC(39)", PostgresDialect.INSTANCE.typeName(DataType.HUGEINT));
        assertEquals("DECIMAL(39,0)", MySqlDialect.INSTANCE.typeName(DataType.HUGEINT));
        assertThrows(IllegalArgumentException.class, () -> SqliteDialect.INSTANCE.typeName(DataType.HUGEINT));
        for (Dialect d : DATABASES) {
            assertThrows(IllegalArgumentException.class, () -> d.typeName(DataType.listOf(DataType.INTEGER)), d.name());
            assertThrows(IllegalArgumentException.class, () -> d.literal(List.of(1), DataType.listOf(DataType.INTEGER)),
                    d.name());
        }
    }

    @Test
    void theLiteralsAreThoseOfTheDatabase() {
        byte[] blob = {1, 'a', (byte) 0xFF};
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        BigInteger huge = new BigInteger("123456789012345678901234567890");

        Dialect h2 = H2Dialect.INSTANCE;
        assertEquals("CAST(NULL AS NUMERIC(10,2))", h2.literal(null, DataType.decimal(10, 2)));
        assertEquals("TRUE", h2.literal(true, DataType.BOOLEAN));
        assertEquals("CAST(1 AS TINYINT)", h2.literal((byte) 1, DataType.TINYINT));
        assertEquals("CAST(123456789012345678901234567890 AS NUMERIC(39))", h2.literal(huge, DataType.HUGEINT));
        assertEquals("CAST('1.50' AS NUMERIC(10,2))", h2.literal(new BigDecimal("1.50"), DataType.decimal(10, 2)));
        assertEquals("CAST('2.5' AS DOUBLE PRECISION)", h2.literal(2.5, DataType.DOUBLE));
        assertEquals("'it''s'", h2.literal("it's", DataType.VARCHAR));
        assertEquals("'a\\b'", h2.literal("a\\b", DataType.VARCHAR));
        assertEquals("X'0161FF'", h2.literal(blob, DataType.BLOB));
        assertEquals("DATE '1970-01-02'", h2.literal(1, DataType.DATE));
        assertEquals("TIMESTAMP '1970-01-01 00:00:01'", h2.literal(1_000_000L, DataType.TIMESTAMP));
        assertEquals("CAST('123e4567-e89b-12d3-a456-426614174000' AS UUID)", h2.literal(uuid, DataType.UUID));
        assertThrows(IllegalArgumentException.class, () -> h2.literal(Double.NaN, DataType.DOUBLE));

        Dialect sqlite = SqliteDialect.INSTANCE;
        assertEquals("NULL", sqlite.literal(null, DataType.decimal(10, 2)));
        assertEquals("0", sqlite.literal(false, DataType.BOOLEAN));
        assertEquals("1", sqlite.literal((byte) 1, DataType.TINYINT));
        assertEquals("1.50", sqlite.literal(new BigDecimal("1.50"), DataType.decimal(10, 2)));
        assertEquals("2.5", sqlite.literal(2.5, DataType.DOUBLE));
        assertEquals("1.5", sqlite.literal(1.5f, DataType.FLOAT));
        assertEquals("'a\\b'", sqlite.literal("a\\b", DataType.VARCHAR));
        assertEquals("X'0161FF'", sqlite.literal(blob, DataType.BLOB));
        assertEquals("'1970-01-02'", sqlite.literal(1, DataType.DATE));
        assertEquals("'1970-01-01 00:00:01'", sqlite.literal(1_000_000L, DataType.TIMESTAMP));
        assertEquals("'123e4567-e89b-12d3-a456-426614174000'", sqlite.literal(uuid, DataType.UUID));
        assertThrows(IllegalArgumentException.class, () -> sqlite.literal(huge, DataType.HUGEINT));
        assertThrows(IllegalArgumentException.class, () -> sqlite.literal(Double.POSITIVE_INFINITY, DataType.DOUBLE));

        Dialect pg = PostgresDialect.INSTANCE;
        assertEquals("CAST(NULL AS SMALLINT)", pg.literal(null, DataType.TINYINT));
        assertEquals("CAST(1 AS SMALLINT)", pg.literal((byte) 1, DataType.TINYINT));
        assertEquals("CAST('NaN' AS DOUBLE PRECISION)", pg.literal(Double.NaN, DataType.DOUBLE));
        assertEquals("CAST('Infinity' AS REAL)", pg.literal(Float.POSITIVE_INFINITY, DataType.FLOAT));
        assertEquals("CAST('-Infinity' AS DOUBLE PRECISION)", pg.literal(Double.NEGATIVE_INFINITY, DataType.DOUBLE));
        assertEquals("CAST('\\x0161FF' AS BYTEA)", pg.literal(blob, DataType.BLOB));
        assertEquals("'a\\b'", pg.literal("a\\b", DataType.VARCHAR));
        assertEquals("CAST('123e4567-e89b-12d3-a456-426614174000' AS UUID)", pg.literal(uuid, DataType.UUID));

        Dialect mysql = MySqlDialect.INSTANCE;
        assertEquals("NULL", mysql.literal(null, DataType.INTEGER));
        assertEquals("-7", mysql.literal(-7, DataType.INTEGER));
        assertEquals("CAST(123456789012345678901234567890 AS DECIMAL(39,0))", mysql.literal(huge, DataType.HUGEINT));
        assertEquals("CAST('1.50' AS DECIMAL(10,2))", mysql.literal(new BigDecimal("1.50"), DataType.decimal(10, 2)));
        assertEquals("CAST('2.5' AS DOUBLE)", mysql.literal(2.5, DataType.DOUBLE));
        assertEquals("'it''s a\\\\b'", mysql.literal("it's a\\b", DataType.VARCHAR));
        assertEquals("X'0161FF'", mysql.literal(blob, DataType.BLOB));
        assertEquals("TIMESTAMP '1970-01-01 00:00:01'", mysql.literal(1_000_000L, DataType.TIMESTAMP));
        assertEquals("'123e4567-e89b-12d3-a456-426614174000'", mysql.literal(uuid, DataType.UUID));
        assertThrows(IllegalArgumentException.class, () -> mysql.literal(Double.NaN, DataType.DOUBLE));
        assertEquals("`a``b`", mysql.quoteIdentifier("a`b"));
    }

    @Test
    void theDatabasesWriteSemiAndAntiJoinsWithExists() {
        Schema s = FullPlan.CUSTOMERS;
        PlanNode scan = PlanNode.Scan.all(FullPlan.CUSTOMERS_REF, s);
        Schema pair = s.concat(s);
        for (Dialect d : DATABASES) {
            SqlRenderer r = new SqlRenderer(d, FullPlan::tableName);
            String customers = "SELECT " + d.quoteIdentifier("id") + " AS c0, " + d.quoteIdentifier("name")
                    + " AS c1 FROM db_main_customers";
            assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1 FROM (" + customers + ") t0 WHERE NOT EXISTS (SELECT 1 FROM ("
                    + customers + ") t1 WHERE t0.c0 = t1.c0 AND (t0.c1 <> t1.c1))", r.render(new PlanNode.Join(scan,
                    scan, List.of(0), List.of(0), JoinType.ANTI, ne(col(pair, 1), col(pair, 3)))), d.name());
            assertEquals("SELECT t0.c0 AS c0, t0.c1 AS c1 FROM (" + customers + ") t0 WHERE EXISTS (SELECT 1 FROM ("
                    + customers + ") t1)", r.render(new PlanNode.Join(scan, scan, List.of(), List.of(), JoinType.SEMI)),
                    d.name());
            assertThrows(IllegalArgumentException.class, () -> d.joinKeyword(JoinType.SEMI), d.name());
        }
    }

    @Test
    void theFormsADialectHasNoSpellingForAreRefused() {
        Schema s = FullPlan.CUSTOMERS;
        PlanNode scan = PlanNode.Scan.all(FullPlan.CUSTOMERS_REF, s);
        for (Dialect d : DATABASES) {
            SqlRenderer r = new SqlRenderer(d, FullPlan::tableName);
            assertThrows(IllegalArgumentException.class,
                    () -> r.expression(tryCast(col(s, 1), DataType.INTEGER), "t"), d.name() + " TRY_CAST");
            for (JoinType type : List.of(JoinType.MARK, JoinType.SINGLE)) {
                assertThrows(IllegalArgumentException.class,
                        () -> r.render(new PlanNode.Join(scan, scan, List.of(0), List.of(0), type)),
                        d.name() + " " + type + " JOIN");
            }
            assertThrows(IllegalArgumentException.class, () -> r.render(new PlanNode.Aggregate(scan, List.of(),
                    List.of(new AggregateCall(AggregateFunction.LIST, 1, false, "l")))), d.name() + " list");
            assertEquals("CAST(t.c1 AS " + (d == MySqlDialect.INSTANCE ? "SIGNED"
                    : d == SqliteDialect.INSTANCE ? "INT4" : "INTEGER") + ")",
                    r.expression(cast(col(s, 1), DataType.INTEGER), "t"), d.name());
        }
        for (Dialect d : List.of(H2Dialect.INSTANCE, SqliteDialect.INSTANCE)) {
            SqlRenderer r = new SqlRenderer(d, FullPlan::tableName);
            assertThrows(IllegalArgumentException.class,
                    () -> r.render(new PlanNode.SetOperation(scan, scan, PlanNode.SetOperationKind.EXCEPT, true)),
                    d.name() + " EXCEPT ALL");
        }
        for (Dialect d : List.of(H2Dialect.INSTANCE, MySqlDialect.INSTANCE)) {
            SqlRenderer r = new SqlRenderer(d, FullPlan::tableName);
            assertThrows(IllegalArgumentException.class,
                    () -> r.render(new PlanNode.Join(scan, scan, List.of(0), List.of(0), JoinType.FULL)),
                    d.name() + " FULL JOIN");
        }
        SqlRenderer mysql = new SqlRenderer(MySqlDialect.INSTANCE, FullPlan::tableName);
        assertEquals("SELECT * FROM (SELECT `id` AS c0, `name` AS c1 FROM db_main_customers EXCEPT ALL SELECT `id` AS c0, "
                + "`name` AS c1 FROM db_main_customers) t0",
                mysql.render(new PlanNode.SetOperation(scan, scan, PlanNode.SetOperationKind.EXCEPT, true)));
        SqlRenderer sqlite = new SqlRenderer(SqliteDialect.INSTANCE, FullPlan::tableName);
        assertThrows(IllegalArgumentException.class, () -> sqlite.render(new PlanNode.Aggregate(scan, List.of(),
                List.of(new AggregateCall(AggregateFunction.STRING_AGG, 1, true, "a", ", ", List.of())))));
        assertEquals("SELECT group_concat(DISTINCT t0.c1) AS c0 FROM (SELECT \"id\" AS c0, \"name\" AS c1 FROM "
                + "db_main_customers) t0", sqlite.render(new PlanNode.Aggregate(scan, List.of(),
                        List.of(new AggregateCall(AggregateFunction.STRING_AGG, 1, true, "a", ",", List.of())))));
        assertEquals("SELECT LISTAGG(DISTINCT t0.c1, ',') WITHIN GROUP (ORDER BY t0.c1 ASC NULLS LAST) AS c0 FROM "
                + "(SELECT \"id\" AS c0, \"name\" AS c1 FROM db_main_customers) t0",
                new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).render(new PlanNode.Aggregate(scan, List.of(),
                        List.of(new AggregateCall(AggregateFunction.STRING_AGG, 1, true, "a", ",",
                                List.of(SortKey.asc(1)))))));
    }

    /** What a source may evaluate: the table of the conservative answers. */
    @Test
    void whatASourceMayEvaluate() {
        Schema s = FullPlan.ORDERS;
        Expression integers = and(gt(col(s, 0), lit(1)), or(not(isNull(col(s, 7))), lt(col(s, 9), lit(5L))));
        Expression integerForms = and(between(col(s, 8), lit((short) 1), lit((short) 2)),
                and(in(col(s, 0), lit(1), lit(2)), isDistinct(col(s, 0), lit(3))));
        Expression booleans = and(col(s, 3), eq(col(s, 3), lit(false)));
        Expression stringEquality = and(eq(col(s, 2), lit("a")), in(col(s, 2), lit("b"), lit("c")));
        Expression stringOrder = lt(col(s, 2), lit("m"));
        Expression decimal = gt(col(s, 1), lit(new BigDecimal("1.50")));
        Expression date = and(gt(col(s, 4), lit(DataType.DATE, 1)), lt(col(s, 5), lit(DataType.TIMESTAMP, 1L)));
        Expression floating = and(gt(col(s, 6), lit(1.5)), eq(col(s, 10), lit(1.5f)));
        Expression blob = eq(col(s, 11), lit(DataType.BLOB, new byte[] {1}));
        Expression uuid = isNull(col(s, 12));
        List<Expression> computed = List.of(gt(add(col(s, 0), lit(1)), lit(2)), eq(cast(col(s, 0), DataType.BIGINT),
                lit(1L)), like(col(s, 2), lit("a%")), eq(call("upper", col(s, 2)), lit("A")),
                in(col(s, 0), lit(1), col(s, 7)));

        for (Dialect d : ALL) {
            assertTrue(d.supports(integers), d.name());
            assertTrue(d.supports(integerForms), d.name());
            assertTrue(d.supports(booleans), d.name());
            assertTrue(d.supports(uuid) == (d == DuckDbDialect.INSTANCE), d.name());
            assertTrue(d.supports(floating) == (d == DuckDbDialect.INSTANCE), d.name());
            assertTrue(d.supports(blob) == (d == DuckDbDialect.INSTANCE), d.name());
            for (Expression e : computed) {
                assertTrue(d.supports(e) == (d == DuckDbDialect.INSTANCE), d.name() + ": " + e);
            }
            assertTrue(d.supports(stringEquality) == (d != MySqlDialect.INSTANCE), d.name());
            assertTrue(d.supports(stringOrder) == (d == DuckDbDialect.INSTANCE || d == SqliteDialect.INSTANCE),
                    d.name());
            assertTrue(d.supports(decimal) == (d != SqliteDialect.INSTANCE), d.name());
            assertTrue(d.supports(date) == (d != SqliteDialect.INSTANCE), d.name());

            assertTrue(d.supportsOrder(List.of(SortKey.desc(0), new SortKey(9, true, true)), s), d.name());
            assertTrue(d.supportsOrder(List.of(SortKey.asc(2)), s)
                    == (d == DuckDbDialect.INSTANCE || d == SqliteDialect.INSTANCE), d.name());
            assertTrue(d.supportsOrder(List.of(SortKey.asc(1)), s) == (d != SqliteDialect.INSTANCE), d.name());
            assertTrue(d.supportsOrder(List.of(SortKey.asc(6)), s) == (d == DuckDbDialect.INSTANCE), d.name());
        }
        assertFalse(SqliteDialect.INSTANCE.supports(eq(col(s, 1), lit(new BigDecimal("1.50")))));
    }

    @Test
    void aPushedScanIsOneFlatStatement() {
        PlanNode.Scan plain = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(2, 0, 4));
        Schema s = plain.schema();
        PlanNode.Pushed pushed = new PlanNode.Pushed(List.of(gt(col(s, 1), lit(5)), isNull(col(s, 2))),
                java.util.OptionalLong.of(10), List.of(new SortKey(1, false, true)), java.util.Optional.empty());
        PlanNode.Scan scan = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(2, 0, 4), pushed);
        String table = FullPlan.tableName(FullPlan.ORDERS_REF);
        String standard = "SELECT t.\"note\", t.\"id\", t.\"day\" FROM " + table + " t WHERE ((t.\"id\" > "
                + "CAST(5 AS INTEGER)) AND (t.\"day\" IS NULL)) ORDER BY t.\"id\" DESC NULLS FIRST LIMIT 10";
        assertEquals(standard, new SqlRenderer(DuckDbDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals(standard, new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals(standard, new SqlRenderer(PostgresDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals(standard.replace("CAST(5 AS INTEGER)", "5"),
                new SqlRenderer(SqliteDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals("SELECT t.`note`, t.`id`, t.`day` FROM " + table + " t WHERE ((t.`id` > 5) AND (t.`day` IS NULL)) "
                + "ORDER BY (t.`id` IS NULL) DESC, t.`id` DESC LIMIT 10",
                new SqlRenderer(MySqlDialect.INSTANCE, FullPlan::tableName).renderScan(scan));

        assertEquals("SELECT t.\"note\" FROM " + table + " t",
                new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).renderScan(
                        new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(2))));
        assertEquals("SELECT 1 FROM " + table + " t", new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName)
                .renderScan(new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of())));
    }

    /** What a source may aggregate: counts, extremes and sums of integers, grouped by what it equates exactly. */
    @Test
    void whatASourceMayAggregate() {
        Schema s = FullPlan.ORDERS;
        PlanNode.Aggregation counts = aggregation(List.of(0, 3), new AggregateCall(AggregateFunction.COUNT_STAR, -1,
                false, "n"), new AggregateCall(AggregateFunction.COUNT, 12, false, "k"),
                new AggregateCall(AggregateFunction.MIN, 7, false, "lo"),
                new AggregateCall(AggregateFunction.MAX, 9, false, "hi"));
        PlanNode.Aggregation sum = aggregation(List.of(), new AggregateCall(AggregateFunction.SUM, 8, false, "s"));
        PlanNode.Aggregation extremes = aggregation(List.of(7), new AggregateCall(AggregateFunction.MIN, 1, false,
                "a"), new AggregateCall(AggregateFunction.MAX, 4, false, "d"),
                new AggregateCall(AggregateFunction.MIN, 5, false, "t"));
        PlanNode.Aggregation byString = aggregation(List.of(2), new AggregateCall(AggregateFunction.COUNT_STAR, -1,
                false, "n"));
        List<PlanNode.Aggregation> never = List.of(
                aggregation(List.of(6), new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.MIN, 2, false, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.MAX, 3, false, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.MIN, 6, false, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.AVG, 0, false, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.SUM, 1, false, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.COUNT, 0, true, "m")),
                aggregation(List.of(), new AggregateCall(AggregateFunction.STRING_AGG, 2, false, "m")));

        for (Dialect d : ALL) {
            boolean duck = d == DuckDbDialect.INSTANCE;
            assertTrue(d.supportsAggregation(counts, s), d.name());
            assertTrue(d.supportsAggregation(sum, s) == (d != SqliteDialect.INSTANCE), d.name());
            assertTrue(d.supportsAggregation(extremes, s) == (d != SqliteDialect.INSTANCE), d.name());
            assertTrue(d.supportsAggregation(byString, s) == (d != MySqlDialect.INSTANCE), d.name());
            for (PlanNode.Aggregation a : never) {
                assertTrue(d.supportsAggregation(a, s) == duck, d.name() + ": " + a);
            }
        }
        assertTrue(H2Dialect.INSTANCE.supportsAggregation(aggregation(List.of(0)), s));
        assertFalse(H2Dialect.INSTANCE.supportsAggregation(aggregation(List.of()), s));
    }

    private static PlanNode.Aggregation aggregation(List<Integer> groups, AggregateCall... calls) {
        return new PlanNode.Aggregation(groups, List.of(calls));
    }

    @Test
    void aPushedAggregationIsOneFlatStatement() {
        // note, id, big, paid
        PlanNode.Scan plain = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(2, 0, 9, 3));
        PlanNode.Aggregation aggregation = aggregation(List.of(0, 3),
                new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n"),
                new AggregateCall(AggregateFunction.COUNT, 1, false, "c"),
                new AggregateCall(AggregateFunction.SUM, 2, false, "s"),
                new AggregateCall(AggregateFunction.MIN, 1, false, "lo"),
                new AggregateCall(AggregateFunction.MAX, 1, false, "hi"));
        PlanNode.Scan scan = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, plain.columns(),
                new PlanNode.Pushed(List.of(gt(col(plain.schema(), 1), lit(5))), java.util.OptionalLong.empty(),
                        List.of(), java.util.Optional.of(aggregation)));
        String table = FullPlan.tableName(FullPlan.ORDERS_REF);
        String standard = "SELECT t.\"note\", t.\"paid\", count(*), count(t.\"id\"), CAST(sum(t.\"big\") AS "
                + "NUMERIC(39)), min(t.\"id\"), max(t.\"id\") FROM " + table + " t WHERE (t.\"id\" > CAST(5 AS "
                + "INTEGER)) GROUP BY t.\"note\", t.\"paid\"";
        assertEquals(standard, new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals(standard, new SqlRenderer(PostgresDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals(standard.replace("NUMERIC(39)", "HUGEINT"),
                new SqlRenderer(DuckDbDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        assertEquals("SELECT t.`note`, t.`paid`, count(*), count(t.`id`), CAST(sum(t.`big`) AS DECIMAL(39,0)), "
                + "min(t.`id`), max(t.`id`) FROM " + table + " t WHERE (t.`id` > 5) GROUP BY t.`note`, t.`paid`",
                new SqlRenderer(MySqlDialect.INSTANCE, FullPlan::tableName).renderScan(scan));
        // SQLite has no type to cast the sum to.
        assertThrows(IllegalArgumentException.class,
                () -> new SqlRenderer(SqliteDialect.INSTANCE, FullPlan::tableName).renderScan(scan));

        PlanNode.Scan count = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(),
                new PlanNode.Pushed(List.of(), java.util.OptionalLong.empty(), List.of(), java.util.Optional.of(
                        aggregation(List.of(), new AggregateCall(AggregateFunction.COUNT_STAR, -1, false, "n")))));
        assertEquals("SELECT count(*) FROM " + table + " t",
                new SqlRenderer(SqliteDialect.INSTANCE, FullPlan::tableName).renderScan(count));
        PlanNode.Scan limited = new PlanNode.Scan(FullPlan.ORDERS_REF, FullPlan.ORDERS, List.of(),
                new PlanNode.Pushed(List.of(), java.util.OptionalLong.of(1), List.of(), count.pushed().aggregation()));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlRenderer(H2Dialect.INSTANCE, FullPlan::tableName).renderScan(limited));
    }
}
