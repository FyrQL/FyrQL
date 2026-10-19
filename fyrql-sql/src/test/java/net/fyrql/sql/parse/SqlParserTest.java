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

package net.fyrql.sql.parse;

import net.fyrql.core.type.DataType;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.FromItem.JoinKind;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.QueryBody.SetKind;
import net.fyrql.sql.ast.SelectItem;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlExpr.LiteralKind;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.WindowSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParserTest {

    /** Parses, prints, and checks that the printed text parses to the same tree. */
    private static String print(String sql) {
        String printed = SqlPrinter.print(SqlParser.parse(sql));
        assertEquals(printed, SqlPrinter.print(SqlParser.parse(printed)), "printing is stable for " + sql);
        return printed;
    }

    private static QueryBody.Select select(String sql) {
        return assertInstanceOf(QueryBody.Select.class, SqlParser.parse(sql).body());
    }

    /** The expressions of the SELECT list of a query. */
    private static List<SqlExpr> items(String sql) {
        return select(sql).items().stream().map(i -> ((SelectItem.ExpressionItem) i).expression()).toList();
    }

    private static SqlException error(String sql) {
        return assertThrows(SqlException.class, () -> SqlParser.parse(sql), sql);
    }

    @Test
    void selectClauses() {
        assertEquals("SELECT DISTINCT a, (b + 1) AS c FROM t WHERE (a > 1) GROUP BY a, (b + 1) HAVING (count(*) > 2)"
                + " ORDER BY a ASC NULLS LAST, c DESC NULLS FIRST LIMIT 10 OFFSET 5",
                print("select distinct a, b + 1 as c from t where a > 1 group by a, b + 1 having count(*) > 2"
                        + " order by a, c desc nulls first limit 10 offset 5"));
        assertEquals("SELECT 1", print("SELECT 1;"));
        assertEquals("SELECT a AS x, b AS \"y z\" FROM t", print("SELECT a x, b \"y z\" FROM t"));
        QueryBody.Select s = select("SELECT a FROM t GROUP BY 1");
        assertEquals(new SqlExpr.Literal(LiteralKind.NUMBER, "1", new Position(1, 26)),
                s.groupBy().expressions().get(0));
    }

    @Test
    void literalsKeepTheirText() {
        List<SqlExpr> items = items("SELECT 1, 1.50, .5, 1., 1e3, 123456789012345678901234567890, 'it''s', $$a//b's$$, ''"
                + ", TRUE, false, NULL, DATE '2020-01-02', TIMESTAMP '2020-01-02 03:04:05.5'");
        List<String> expected = List.of("NUMBER 1", "NUMBER 1.50", "NUMBER .5", "NUMBER 1.", "NUMBER 1e3",
                "NUMBER 123456789012345678901234567890", "STRING it's", "STRING a//b's", "STRING ",
                "BOOLEAN true", "BOOLEAN false", "NULL NULL", "DATE 2020-01-02", "TIMESTAMP 2020-01-02 03:04:05.5");
        for (int i = 0; i < expected.size(); i++) {
            SqlExpr.Literal l = assertInstanceOf(SqlExpr.Literal.class, items.get(i));
            assertEquals(expected.get(i), l.kind() + " " + l.text());
        }
        // The sign of a number is an operator; the binder decides how it types.
        assertEquals("SELECT (-5), (-(-5)), (+a)", print("SELECT -5, -(-5), +a"));
    }

    @Test
    void operatorsAndPredicates() {
        assertEquals("SELECT (((a + b) * c) - ((d / e) % f)), (a || b), ((a = b) OR ((a <> b) AND (NOT (a < b))))",
                print("SELECT (a + b) * c - d / e % f, a || b, a = b OR a != b AND NOT a < b"));
        assertEquals("SELECT ((a <= b) AND (a >= b)), (a > b), (a BETWEEN 1 AND 2), (a NOT BETWEEN 1 AND 2)",
                print("SELECT a <= b AND a >= b, a > b, a BETWEEN 1 AND 2, a NOT BETWEEN 1 AND 2"));
        assertEquals("SELECT (a IN (1, 2)), (a NOT IN (3)), (a LIKE 'x%'), (a NOT LIKE 'x'), (a ILIKE 'X'),"
                + " (a NOT ILIKE 'y')",
                print("SELECT a IN (1, 2), a NOT IN (3), a LIKE 'x%', a NOT LIKE 'x', a ILIKE 'X', a NOT ILIKE 'y'"));
        assertEquals("SELECT (a IS NULL), (a IS NOT NULL), (a IS DISTINCT FROM b), (a IS NOT DISTINCT FROM b)",
                print("SELECT a IS NULL, a IS NOT NULL, a IS DISTINCT FROM b, a IS NOT DISTINCT FROM b"));
        // IS TRUE and IS FALSE never give NULL, as IS NOT DISTINCT FROM.
        assertEquals("SELECT (a IS NOT DISTINCT FROM true), (a IS DISTINCT FROM true),"
                + " (a IS NOT DISTINCT FROM false), (a IS DISTINCT FROM false)",
                print("SELECT a IS TRUE, a IS NOT TRUE, a IS FALSE, a IS NOT FALSE"));
        assertEquals("SELECT CASE a WHEN 1 THEN 'x' WHEN 2 THEN 'y' ELSE NULL END, CASE WHEN (a > 0) THEN 1 END",
                print("SELECT CASE a WHEN 1 THEN 'x' WHEN 2 THEN 'y' ELSE NULL END, CASE WHEN a > 0 THEN 1 END"));
        assertEquals("SELECT CAST(a AS INTEGER), CAST(b AS DECIMAL(10,2)), TRY_CAST(c AS VARCHAR), CAST(d AS DATE)",
                print("SELECT a::INT, CAST(b AS NUMERIC(10, 2)), TRY_CAST(c AS TEXT), d::date"));
    }

    @Test
    void integerDivisionIsNoComment() {
        assertEquals("SELECT (7 // 2), ((a // b) // c), 'a//b', \"x//y\" FROM t",
                print("SELECT 7 // 2, a//b//c, 'a//b', \"x//y\" FROM t -- a // comment"));
        assertEquals("SELECT (a = b), ((a = b) = true) FROM t", print("SELECT a == b, (a = b) == true FROM t"));
        // A column may be called div, as in DuckDB, which has no operator DIV.
        assertEquals("SELECT \"div\", t.\"div\" AS \"DIV\" FROM t WHERE (\"div\" // 2)",
                print("SELECT div, t.div AS DIV FROM t WHERE div // 2"));
        assertEquals("Syntax error at or near \"b\"", error("SELECT a DIV b").reason());
    }

    @Test
    void namesKeepQuotedDotsAndQuotes() {
        SqlExpr.Identifier id = assertInstanceOf(SqlExpr.Identifier.class,
                items("SELECT \"a\"\"b\".\"c.d\" FROM t").get(0));
        assertEquals(List.of("a\"b", "c.d"), id.parts());
        assertEquals(List.of("q", "s", "t", "a"),
                ((SqlExpr.Identifier) items("SELECT q.s.t.a FROM t").get(0)).parts());
        assertEquals("SELECT \"Mixed Case\", \"select\", t.\"x\"\"y\" FROM \"x.y\".z",
                print("SELECT \"Mixed Case\", \"select\", t.\"x\"\"y\" FROM \"x.y\".z"));
        FromItem.TableName table = assertInstanceOf(FromItem.TableName.class,
                select("SELECT 1 FROM \"x.y\".z AS w").from());
        assertEquals(List.of("x.y", "z"), table.name());
        assertEquals("w", table.alias().name());
    }

    @Test
    void starWithModifiers() {
        assertEquals("SELECT *, t.* EXCLUDE (a, b), s.t.* REPLACE ((a + 1) AS a, 'x' AS b) FROM t",
                print("SELECT *, t.* EXCLUDE (a, b), s.t.* REPLACE (a + 1 AS a, 'x' AS b) FROM t"));
        SelectItem.Star star = assertInstanceOf(SelectItem.Star.class,
                select("SELECT s.t.* EXCLUDE (a) FROM t").items().get(0));
        assertEquals(List.of("s", "t"), star.qualifier());
        assertEquals(List.of("a"), star.exclude());
    }

    @Test
    void groupByAndOrderByAll() {
        QueryBody.Select s = select("SELECT a, sum(b) FROM t GROUP BY ALL ORDER BY ALL");
        assertTrue(s.groupBy().all());
        assertEquals("SELECT a, sum(b) FROM t GROUP BY ALL ORDER BY ALL DESC NULLS FIRST",
                print("SELECT a, sum(b) FROM t GROUP BY ALL ORDER BY ALL DESC NULLS FIRST"));
        assertTrue(SqlParser.parse("SELECT a FROM t ORDER BY ALL").orderBy().get(0).isAll());
    }

    @Test
    void functionCalls() {
        assertEquals("SELECT count(*), COUNT(DISTINCT a), string_agg(b, ', ' ORDER BY b DESC NULLS LAST, a ASC NULLS"
                + " FIRST), coalesce(a, b, 1), Upper(x) FROM t",
                print("SELECT count(*), COUNT(DISTINCT a), string_agg(b, ', ' ORDER BY b DESC, a NULLS FIRST),"
                        + " coalesce(a, b, 1), \"Upper\"(x) FROM t"));
        SqlExpr.FunctionCall count = assertInstanceOf(SqlExpr.FunctionCall.class, items("SELECT COUNT(*)").get(0));
        assertEquals("COUNT", count.name());
        assertTrue(count.star());
        assertTrue(count.arguments().isEmpty());
    }

    @Test
    void specialFunctionForms() {
        assertEquals("SELECT date_part('year', d), date_part('epoch', d), strpos(s, 'b'), substring(s, 2, 3),"
                + " substring(s, 2), trim(s), trim(s, 'x'), trim(s, 'x'), ltrim(s), rtrim(s, 'y') FROM t",
                print("SELECT extract(year FROM d), EXTRACT(epoch FROM d), position('b' IN s),"
                        + " substring(s FROM 2 FOR 3), substring(s FROM 2), trim(s), trim(s, 'x'),"
                        + " trim(BOTH 'x' FROM s), trim(LEADING FROM s), trim(TRAILING 'y' FROM s) FROM t"));
        assertEquals("SELECT CURRENT_DATE, CURRENT_TIMESTAMP, now(), today()",
                print("SELECT current_date, CURRENT_TIMESTAMP, now(), today()"));
        SqlExpr.FunctionCall today = assertInstanceOf(SqlExpr.FunctionCall.class,
                items("SELECT current_date").get(0));
        assertEquals("current_date", today.name());
    }

    @Test
    void fromItems() {
        assertEquals("SELECT * FROM t CROSS JOIN s.t AS a CROSS JOIN c.s.t AS b(x, y)",
                print("SELECT * FROM t, s.t a, c.s.t AS b(x, y)"));
        assertEquals("SELECT * FROM 'data/orders.csv' AS o", print("SELECT * FROM 'data/orders.csv' o"));
        FromItem.FileName file = assertInstanceOf(FromItem.FileName.class,
                select("SELECT * FROM 'it''s.csv'").from());
        assertEquals("it's.csv", file.path());
        FromItem.TableFunctionCall f = assertInstanceOf(FromItem.TableFunctionCall.class,
                select("SELECT * FROM read_csv('x.csv', header = true, delim = ';') AS r").from());
        assertEquals("read_csv", f.name());
        assertEquals(1, f.arguments().size());
        assertEquals(List.of("header", "delim"), List.copyOf(f.namedArguments().keySet()));
        assertEquals("r", f.alias().name());
        assertEquals("SELECT * FROM read_csv('x.csv', header = true, delim = ';') AS r",
                print("SELECT * FROM read_csv('x.csv', header = true, delim = ';') AS r"));
        assertEquals("SELECT * FROM (SELECT 1 AS a) AS s", print("SELECT * FROM (SELECT 1 AS a) s"));
        assertEquals("SELECT x FROM (SELECT 2 AS x) WHERE true", print("SELECT x FROM (SELECT 2 AS x) WHERE true"));
        assertEquals("SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS t(x, y)",
                print("SELECT * FROM (VALUES (1, 'a'), (2, 'b')) t(x, y)"));
        assertEquals("SELECT * FROM (SELECT 1 ORDER BY 1 ASC NULLS LAST LIMIT 1) AS s",
                print("SELECT * FROM (SELECT 1 ORDER BY 1 LIMIT 1) s"));
        // unnest reads the items before it with or without LATERAL; other table functions take no LATERAL.
        assertEquals("SELECT * FROM t CROSS JOIN unnest(t.l) AS u(x)", print("SELECT * FROM t, LATERAL unnest(t.l) u(x)"));
        assertEquals("SELECT unnest(l), unnest(list_value(1)) FROM t", print("SELECT unnest(l), unnest([1]) FROM t"));
        assertEquals("Not supported: LATERAL", assertThrows(SqlException.class,
                () -> SqlParser.parse("SELECT * FROM t, LATERAL read_csv('x.csv')")).reason());
    }

    @Test
    void joins() {
        assertEquals("SELECT * FROM a INNER JOIN b ON (a.x = b.x) LEFT JOIN c USING (x, y) RIGHT JOIN d ON true"
                + " FULL JOIN e ON (e.z = a.z) NATURAL JOIN f NATURAL LEFT JOIN g CROSS JOIN h",
                print("SELECT * FROM a JOIN b ON a.x = b.x LEFT OUTER JOIN c USING (x, y) RIGHT JOIN d ON true"
                        + " FULL OUTER JOIN e ON e.z = a.z NATURAL JOIN f NATURAL LEFT JOIN g CROSS JOIN h"));
        // A comma separates groups of joins: a, (b LEFT JOIN c).
        FromItem.Join comma = assertInstanceOf(FromItem.Join.class,
                select("SELECT * FROM a, b LEFT JOIN c ON b.x = c.x").from());
        assertEquals(JoinKind.CROSS, comma.kind());
        assertInstanceOf(FromItem.TableName.class, comma.left());
        assertEquals(JoinKind.LEFT, ((FromItem.Join) comma.right()).kind());
        // Explicit joins associate to the left, parentheses group.
        assertEquals("SELECT * FROM a INNER JOIN (b INNER JOIN c ON (b.y = c.y)) ON (a.x = b.x)",
                print("SELECT * FROM a JOIN (b JOIN c ON b.y = c.y) ON a.x = b.x"));
        assertEquals("SELECT * FROM a INNER JOIN b ON (a.x = b.x)", print("SELECT * FROM (a JOIN b ON a.x = b.x)"));
    }

    @Test
    void setOperationsFollowDuckDbPrecedence() {
        // INTERSECT binds more tightly; UNION and EXCEPT associate to the left.
        QueryBody.SetOperation union = assertInstanceOf(QueryBody.SetOperation.class,
                SqlParser.parse("SELECT 1 UNION SELECT 2 INTERSECT SELECT 3").body());
        assertEquals(SetKind.UNION, union.kind());
        assertEquals(SetKind.INTERSECT, ((QueryBody.SetOperation) union.right()).kind());
        QueryBody.SetOperation except = assertInstanceOf(QueryBody.SetOperation.class,
                SqlParser.parse("SELECT 1 UNION ALL SELECT 2 EXCEPT SELECT 3").body());
        assertEquals(SetKind.EXCEPT, except.kind());
        assertFalse(except.all());
        assertTrue(((QueryBody.SetOperation) except.left()).all());
        assertEquals("SELECT 1 UNION SELECT 2 EXCEPT ALL (SELECT 3 INTERSECT SELECT 4) ORDER BY 1 ASC NULLS LAST"
                + " LIMIT 3 OFFSET 1",
                print("SELECT 1 UNION DISTINCT SELECT 2 EXCEPT ALL SELECT 3 INTERSECT SELECT 4 ORDER BY 1 LIMIT 3"
                        + " OFFSET 1"));
        assertEquals("(SELECT 1 UNION SELECT 2) INTERSECT SELECT 3",
                print("(SELECT 1 UNION SELECT 2) INTERSECT SELECT 3"));
        assertEquals("SELECT 1 UNION (SELECT 2 UNION SELECT 3)", print("SELECT 1 UNION (SELECT 2 UNION SELECT 3)"));
        assertEquals("(SELECT 1 ORDER BY 1 ASC NULLS LAST LIMIT 1) UNION ALL SELECT 2",
                print("(SELECT 1 ORDER BY 1 LIMIT 1) UNION ALL (SELECT 2)"));
        assertEquals("VALUES (1) UNION VALUES (2)", print("VALUES (1) UNION VALUES (2)"));
    }

    @Test
    void withValuesAndParentheses() {
        Query q = SqlParser.parse("WITH s(x) AS (SELECT 1), u AS (SELECT 2) SELECT * FROM s UNION SELECT * FROM u");
        assertEquals(2, q.with().size());
        assertEquals("s", q.with().get(0).name());
        assertEquals(List.of("x"), q.with().get(0).columns());
        assertInstanceOf(QueryBody.SetOperation.class, q.body());
        assertEquals("WITH s(x) AS (SELECT 1), u AS (SELECT 2) SELECT * FROM s UNION SELECT * FROM u",
                print("WITH s(x) AS (SELECT 1), u AS MATERIALIZED (SELECT 2) SELECT * FROM s UNION SELECT * FROM u"));
        assertEquals("VALUES (1, 2), (3, 4)", print("VALUES (1, 2), (3, 4)"));
        assertEquals(List.of(2), ((QueryBody.Values) SqlParser.parse("VALUES (1, 2)").body()).rows().stream()
                .map(List::size).toList());
        assertEquals("SELECT 1 ORDER BY 1 ASC NULLS LAST", print("(SELECT 1) ORDER BY 1"));
        assertEquals("SELECT 1", print("((SELECT 1))"));
    }

    @Test
    void limitAndOffset() {
        Query q = SqlParser.parse("SELECT 1 LIMIT ALL");
        assertNull(q.limit());
        assertNull(SqlParser.parse("SELECT 1 LIMIT NULL").limit());
        assertEquals("SELECT 1 LIMIT (1 + 1) OFFSET 2", print("SELECT 1 LIMIT 1 + 1 OFFSET 2 ROWS"));
        assertEquals("SELECT 1 OFFSET 3", print("SELECT 1 OFFSET 3"));
    }

    @Test
    void castsReadDuckDbTypeNames() {
        SqlExpr.Cast cast = assertInstanceOf(SqlExpr.Cast.class, items("SELECT TRY_CAST(a AS DOUBLE PRECISION)").get(0));
        assertEquals(DataType.DOUBLE, cast.type());
        assertTrue(cast.safe());
        assertEquals(DataType.decimal(18, 3), ((SqlExpr.Cast) items("SELECT a::DECIMAL").get(0)).type());
        assertEquals(DataType.TIMESTAMP,
                ((SqlExpr.Cast) items("SELECT CAST(a AS TIMESTAMP WITHOUT TIME ZONE)").get(0)).type());
        assertEquals(DataType.VARCHAR, ((SqlExpr.Cast) items("SELECT a::VARCHAR(10)").get(0)).type());
        assertEquals(DataType.INTEGER, ((SqlExpr.Cast) items("SELECT CAST(a AS \"INTEGER\")").get(0)).type());
        assertEquals(DataType.listOf(DataType.listOf(DataType.INTEGER)),
                ((SqlExpr.Cast) items("SELECT a::INT[][]").get(0)).type());
        assertEquals(DataType.struct(new DataType.Struct.Field("x y", DataType.decimal(10, 2))),
                ((SqlExpr.Cast) items("SELECT CAST(a AS STRUCT(\"x y\" DECIMAL(10, 2)))").get(0)).type());
    }

    @Test
    void nestedValuesAreIndexesStructsAndCalls() {
        Position p = new Position(1, 8);
        SqlExpr l = new SqlExpr.Identifier(List.of("l"), p);
        SqlExpr one = new SqlExpr.Literal(LiteralKind.NUMBER, "1", new Position(1, 10));
        assertEquals(new SqlExpr.Index(l, one, p), items("SELECT l[1] FROM t").get(0));
        assertEquals("SELECT l[1], t.l[-1], l[1][2], l[1], (list_value(10, 20))[1], s.a.b, ({'a': 1})['a'],"
                + " s['a']['b'], l[i + 1], ('abc')[2] FROM t",
                print("SELECT l[1], t.l[-1], l[1][2], (l)[1], [10, 20][1], s.a.b, ({'a': 1}).a, s['a'].b, l[i + 1],"
                        + " 'abc'[2] FROM t"));
        assertEquals("SELECT list_value(1, 2), list_value(3), list_value(), list_value(list_value(1)),"
                + " map(list_value('k', 'j'), list_value(1, 2)), map(list_value(), list_value())",
                print("SELECT [1, 2], ARRAY[3], [], [[1]], MAP {'k': 1, 'j': 2}, MAP {}"));
        assertEquals("SELECT {'a': 1, 'b': 'x'}, {'a': 1}, {'x y': 1}, {'a': 1, 'x': x}, {'A b': 1}",
                print("SELECT {'a': 1, 'b': 'x'}, {a: 1}, {\"x y\": 1}, struct_pack(a := 1, x), struct_pack(\"A b\" := 1)"));
        SqlExpr.StructValue s = assertInstanceOf(SqlExpr.StructValue.class, items("SELECT {'it''s': t.x}").get(0));
        assertEquals(List.of("it's"), s.names());
        assertEquals(new SqlExpr.Identifier(List.of("t", "x"), new Position(1, 18)), s.values().get(0));
    }

    @Test
    void intervalsAreANumberAndAUnit() {
        assertEquals("SELECT (DATE '1998-12-01' - INTERVAL '90' DAY), (DATE '1994-01-01' + INTERVAL '1' YEAR),"
                + " (d + INTERVAL '3' MONTHS), (ts - INTERVAL '36' HOUR) FROM t",
                print("SELECT DATE '1998-12-01' - INTERVAL '90' DAY, DATE '1994-01-01' + INTERVAL 1 year,"
                        + " d + INTERVAL '3 months', ts - INTERVAL '36' HOUR FROM t"));
        SqlExpr.Interval i = assertInstanceOf(SqlExpr.Interval.class, items("SELECT INTERVAL '90' DAY (3)").get(0));
        assertEquals(new SqlExpr.Interval("90", "DAY", new Position(1, 8)), i);
    }

    @Test
    void parametersAreNumberedInTheOrderOfTheText() {
        String sql = "SELECT ? + a, '?' AS \"?\" FROM t -- ?\nWHERE b = ? AND c IN (SELECT ? FROM u) LIMIT 1";
        SqlParser.Parameterized parsed = SqlParser.parseWithParameters(sql);
        assertEquals(List.of(new SqlExpr.Parameter(1, new Position(1, 8)), new SqlExpr.Parameter(2, new Position(2, 11)),
                new SqlExpr.Parameter(3, new Position(2, 30))), parsed.parameters());
        assertEquals(Parameters.parse(sql).count(), parsed.parameters().size());
        SqlExpr.Binary first = assertInstanceOf(SqlExpr.Binary.class,
                ((SelectItem.ExpressionItem) ((QueryBody.Select) parsed.query().body()).items().get(0)).expression());
        assertEquals(parsed.parameters().get(0), first.left());
        assertEquals("SELECT (? + a), '?' AS \"?\" FROM t WHERE ((b = ?) AND (c IN (SELECT ? FROM u))) LIMIT 1",
                SqlPrinter.print(parsed.query()));
        assertEquals(List.of(), SqlParser.parseWithParameters("SELECT 1").parameters());
        for (String named : List.of("SELECT :x", "SELECT ?1", "SELECT $1")) {
            SqlException e = assertThrows(SqlException.class, () -> SqlParser.parseWithParameters(named), named);
            assertTrue(e.reason().startsWith("Not supported: ") && e.reason().endsWith(" parameters")
                    || e.reason().startsWith("Syntax error"), named + ": " + e.reason());
        }
    }

    @Test
    void subqueriesAreExistsInAndScalars() {
        assertEquals("SELECT a FROM t WHERE ((EXISTS (SELECT 1 FROM u WHERE (u.a = t.a))) AND (NOT EXISTS (SELECT * FROM w)))",
                print("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a) AND NOT EXISTS (SELECT * FROM w)"));
        assertEquals("SELECT a FROM t WHERE ((a IN (SELECT f FROM u)) OR ((b + 1) NOT IN (SELECT f FROM u ORDER BY f ASC"
                + " NULLS LAST LIMIT 2)))",
                print("SELECT a FROM t WHERE a IN (SELECT f FROM u) OR b + 1 NOT IN (SELECT f FROM u ORDER BY f LIMIT 2)"));
        assertEquals("SELECT (SELECT max(f) FROM u), ((SELECT 1) + 1) FROM t HAVING (count(*) > (SELECT 1 UNION SELECT 2))",
                print("SELECT (SELECT max(f) FROM u), (SELECT 1) + 1 FROM t HAVING count(*) > (SELECT 1 UNION SELECT 2)"));
        // NOT before EXISTS belongs to it, NOT before IN to the operator.
        assertEquals("SELECT (NOT EXISTS (SELECT 1)), (NOT (a IN (SELECT 1))), ((EXISTS (SELECT 1)) = true)",
                print("SELECT NOT EXISTS (SELECT 1), NOT a IN (SELECT 1), (EXISTS (SELECT 1)) = true"));
        SqlExpr.InQuery in = assertInstanceOf(SqlExpr.InQuery.class, items("SELECT a NOT IN (SELECT 1)").get(0));
        assertTrue(in.negated());
        assertEquals(new Position(1, 8), in.position());
        SqlExpr.ScalarSubquery scalar = assertInstanceOf(SqlExpr.ScalarSubquery.class, items("SELECT (SELECT 1)").get(0));
        assertEquals(new Position(1, 8), scalar.position());
        SqlExpr.Exists exists = assertInstanceOf(SqlExpr.Exists.class, items("SELECT NOT EXISTS (SELECT 1)").get(0));
        assertTrue(exists.negated());
        // JSqlParser keeps no position for EXISTS; the one of its query stands in.
        assertEquals(new Position(1, 19), exists.position());
    }

    @Test
    void windowFunctions() {
        assertEquals("SELECT row_number() OVER () FROM t", print("select row_number() over () from t"));
        assertEquals("SELECT rank() OVER (PARTITION BY a, (b + 1) ORDER BY c DESC NULLS LAST) FROM t",
                print("select rank() over (partition by a, b + 1 order by c desc) from t"));
        // A row of columns partitions as the columns; a first column in parentheses gets a second pair, which
        // JSqlParser needs to read on after it.
        assertEquals("SELECT rank() OVER (PARTITION BY a, (b + 1)) FROM t",
                print("select rank() over (partition by (a, b + 1)) from t"));
        // JSqlParser gives IGNORE NULLS after a last argument that is a call to that call.
        assertEquals("SELECT first_value(upper(b) IGNORE NULLS) OVER (), lag(a, 1, abs(c) IGNORE NULLS) OVER () FROM t",
                print("select first_value(upper(b) ignore nulls) over (), lag(a, 1, abs(c) ignore nulls) over () from t"));
        String parenthesized = "SELECT rank() OVER (PARTITION BY ((b + 1)), a) FROM t";
        assertEquals(parenthesized, print("select rank() over (partition by ((b + 1)), a) from t"));
        assertEquals(parenthesized, print(parenthesized));
        assertEquals("SELECT sum(a) OVER (ORDER BY b ASC NULLS LAST ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) FROM t",
                print("select sum(a) over (order by b rows 2 preceding) from t"));
        assertEquals("SELECT sum(a) OVER (ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM t",
                print("select sum(a) over (rows unbounded preceding) from t"));
        assertEquals("SELECT sum(a) FILTER (WHERE (a > 1)) OVER (PARTITION BY b ORDER BY c ASC NULLS FIRST"
                + " RANGE BETWEEN 1 PRECEDING AND (1 + 1) FOLLOWING EXCLUDE TIES) FROM t",
                print("select sum(a) filter (where a > 1) over (partition by b order by c nulls first"
                        + " range between 1 preceding and (1 + 1) following exclude ties) from t"));
        assertEquals("SELECT count(*) OVER (GROUPS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW),"
                + " count(*) OVER (GROUPS BETWEEN 1 FOLLOWING AND 2 FOLLOWING EXCLUDE GROUP),"
                + " count(*) OVER (ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING) FROM t",
                print("select count(*) over (groups between current row and unbounded following exclude current row),"
                        + " count(*) over (groups between 1 following and 2 following exclude group),"
                        + " count(*) over (rows between 1 preceding and 1 preceding exclude no others) from t"));
        assertEquals("SELECT first_value(a IGNORE NULLS) OVER (ORDER BY b ASC NULLS LAST),"
                + " last_value(a IGNORE NULLS) OVER (ORDER BY b ASC NULLS LAST),"
                + " nth_value(a, 2) OVER (ORDER BY b ASC NULLS LAST) FROM t",
                print("select first_value(a ignore nulls) over (order by b), last_value(a) ignore nulls over"
                        + " (order by b), nth_value(a, 2) respect nulls over (order by b) from t"));
        assertEquals("SELECT lag(a, 2, 0) OVER (ORDER BY b ASC NULLS LAST), lead(a) OVER () FROM t",
                print("select lag(a, 2, 0) over (order by b), lead(a) over () from t"));
        assertEquals("SELECT count(*) OVER (ORDER BY d ASC NULLS LAST RANGE BETWEEN INTERVAL '1' DAY PRECEDING"
                + " AND INTERVAL '2' DAYS FOLLOWING) FROM t",
                print("select count(*) over (order by d range between interval 1 day preceding"
                        + " and interval '2 days' following) from t"));
        assertEquals("SELECT string_agg(a, ',' ORDER BY a DESC NULLS LAST) OVER (), count(DISTINCT a) OVER"
                + " (PARTITION BY b) FROM t",
                print("select string_agg(a, ',' order by a desc) over (), count(distinct a) over (partition by b)"
                        + " from t"));
        assertEquals("SELECT \"my f\"(a) OVER \"my w\", (1 + sum(a) OVER ()) FROM t WINDOW \"my w\" AS ()",
                print("select \"my f\"(a) over \"my w\", 1 + sum(a) over () from t window \"my w\" as ()"));
        // DuckDB writes WINDOW before QUALIFY, JSqlParser reads them the other way round; both are accepted.
        String named = "SELECT a, row_number() OVER w AS rn FROM t WINDOW w AS (ORDER BY a ASC NULLS LAST),"
                + " v AS (PARTITION BY b) QUALIFY (rn > 1) ORDER BY a ASC NULLS LAST LIMIT 3";
        assertEquals(named, print("select a, row_number() over w as rn from t window w as (order by a),"
                + " v as (partition by b) qualify rn > 1 order by a limit 3"));
        assertEquals(named, print("select a, row_number() over w as rn from t qualify rn > 1"
                + " window w as (order by a), v as (partition by b) order by a limit 3"));
        assertEquals("SELECT a FROM t WINDOW w AS () QUALIFY (a IN (SELECT b FROM u WINDOW v AS () QUALIFY (b > 1)))"
                + " UNION SELECT 1 WINDOW x AS () QUALIFY true",
                print("select a from t window w as () qualify a in (select b from u window v as () qualify b > 1)"
                        + " union select 1 window x as () qualify true"));
        // ALL is the default quantifier of an aggregate.
        assertEquals("SELECT sum(a) OVER () FROM t", print("select sum(all a) over () from t"));
        assertEquals("SELECT sum(a), count(*) FROM t", print("select sum(all a), count(all *) from t"));
    }

    @Test
    void windowFunctionsKeepTheirParts() {
        SqlExpr.Window w = assertInstanceOf(SqlExpr.Window.class, items(
                "SELECT lag(a, 1) IGNORE NULLS FILTER (WHERE a > 0) OVER (PARTITION BY b ORDER BY c ROWS 3 PRECEDING)"
                        + " FROM t").get(0));
        assertEquals("lag", w.call().name());
        assertEquals(2, w.call().arguments().size());
        assertTrue(w.ignoreNulls());
        assertInstanceOf(SqlExpr.Binary.class, w.filter());
        assertNull(w.over().base());
        WindowSpec.Frame frame = w.over().frame();
        assertEquals(WindowSpec.FrameUnit.ROWS, frame.unit());
        assertEquals(WindowSpec.BoundKind.PRECEDING, frame.start().kind());
        assertEquals(new SqlExpr.Literal(LiteralKind.NUMBER, "3", new Position(1, 89)), frame.start().offset());
        assertEquals(WindowSpec.Bound.of(WindowSpec.BoundKind.CURRENT_ROW), frame.end());
        assertEquals(WindowSpec.Exclusion.NO_OTHERS, frame.exclusion());
        assertEquals(new Position(1, 8), w.position());
        assertEquals(List.of(w.call().arguments().get(0), w.call().arguments().get(1), w.over().partitionBy().get(0),
                w.over().orderBy().get(0).expression(), frame.start().offset(), w.filter()), w.operands());
        assertEquals(w, w.withOperands(w.operands()));
        SqlExpr.Window count = assertInstanceOf(SqlExpr.Window.class, items("SELECT count(*) OVER () FROM t").get(0));
        assertTrue(count.call().star());
        assertNull(count.over().frame());
        // The clauses keep their positions in the original text, also where QUALIFY moves before WINDOW.
        QueryBody.Select s = select("SELECT a FROM t WINDOW w AS (ORDER BY a) QUALIFY b = 1");
        assertEquals(new Position(1, 50), s.qualify().position());
        assertEquals(new Position(1, 39), s.windows().get(0).window().orderBy().get(0).expression().position());
        assertEquals("w", s.windows().get(0).name());
        SqlException e = error("SELECT a FROM t WINDOW w AS (ORDER BY a) QUALIFY b = = 1");
        assertEquals("Syntax error at or near \"=\"@1:54", e.reason() + "@" + e.position().line() + ":"
                + e.position().column());
    }

    @Test
    void unsupportedFormsAreRejectedWithTheirPosition() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("SELECT sum(a) FILTER (WHERE a > 1) FROM t", "Not supported: FILTER without OVER@1:8"),
                Map.entry("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY a) FROM t",
                        "Not supported: WITHIN GROUP@1:8"),
                Map.entry("SELECT position('a' IN a) OVER () FROM t",
                        "Not supported: this form of arguments of position@1:8"),
                Map.entry("SELECT s.f(a) OVER () FROM t", "Not supported: qualified function names@1:8"),
                Map.entry("SELECT f(a, b, c, d) OVER () FROM t",
                        "Not supported: a window function with more than three arguments@1:22"),
                Map.entry("SELECT sum(a) OVER (ORDER BY ALL) FROM t", "Not supported: ORDER BY ALL here@1:30"),
                Map.entry("SELECT sum(a) OVER (ROWS BETWEEN UNBOUNDED FOLLOWING AND CURRENT ROW) FROM t",
                        "frame start cannot be UNBOUNDED FOLLOWING@1:8"),
                Map.entry("SELECT sum(a) OVER (ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED PRECEDING) FROM t",
                        "frame end cannot be UNBOUNDED PRECEDING@1:8"),
                Map.entry("SELECT sum(a) OVER (ROWS BETWEEN CURRENT ROW AND 1 PRECEDING) FROM t",
                        "frame starting from current row cannot have preceding rows@1:8"),
                Map.entry("SELECT sum(a) OVER (ROWS 1 FOLLOWING) FROM t",
                        "frame starting from following row cannot end with current row@1:8"),
                Map.entry("SELECT sum(a) OVER (RANGE BETWEEN 1 FOLLOWING AND 1 PRECEDING) FROM t",
                        "frame starting from following row cannot have preceding rows@1:8"),
                Map.entry("SELECT a FROM t WINDOW w AS (), W AS ()", "window \"W\" is already defined@1:1"),
                Map.entry("SELECT a FROM t WHERE (a, b) IN (SELECT 1, 2)", "Not supported: IN with more than one column@1:23"),
                Map.entry("SELECT a FROM t WHERE a > ANY (SELECT 1)", "Not supported: ANY and ALL@1:31"),
                Map.entry("SELECT a FROM t WHERE a <= ALL (SELECT 1)", "Not supported: ANY and ALL@1:32"),
                Map.entry("SELECT INTERVAL '1' DAY TO SECOND", "Not supported: an interval with two fields@1:8"),
                Map.entry("SELECT l[1:2] FROM t", "Not supported: slices@1:10"),
                Map.entry("SELECT (l)[1:2] FROM t", "Not supported: slices@1:12"),
                Map.entry("SELECT struct_pack(1)", "Need named argument for struct pack, e.g. STRUCT_PACK(a := b)@1:20"),
                Map.entry("SELECT struct_pack()", "Can't pack nothing into a struct@1:8"),
                Map.entry("SELECT f(a := 1)", "Not supported: named arguments@1:8"),
                Map.entry("SELECT CAST(a AS INT[3])", "Arrays of a fixed size are not supported: INT[3]@1:8"),
                Map.entry("SELECT DISTINCT ON (a) a FROM t", "Not supported: DISTINCT ON@1:1"),
                Map.entry("SELECT * FROM a ASOF JOIN b ON a.t >= b.t", "Not supported: ASOF JOIN@1:17"),
                Map.entry("SELECT * FROM t, LATERAL (SELECT 1)", "Not supported: LATERAL@1:18"),
                Map.entry("WITH RECURSIVE r AS (SELECT 1) SELECT * FROM r", "WITH RECURSIVE is not supported@1:21"),
                Map.entry("SELECT a FROM t GROUP BY ROLLUP (a)", "Not supported: ROLLUP@1:26"),
                Map.entry("SELECT a FROM t GROUP BY GROUPING SETS ((a), ())", "Not supported: GROUPING SETS@1:1"),
                Map.entry("SELECT 1 MINUS SELECT 1", "Not supported: MINUS@1:10"),
                Map.entry("SELECT a LIKE 'x' ESCAPE '!'", "Not supported: ESCAPE@1:8"),
                Map.entry("SELECT a SIMILAR TO 'x'", "Not supported: SIMILAR_TO@1:8"),
                Map.entry("SELECT a ^ 2", "Not supported: the operator ^@1:8"),
                Map.entry("SELECT a & b", "Not supported: the operator &@1:8"),
                Map.entry("SELECT x = ?", "Not supported: parameters outside a prepared query@1:12"),
                Map.entry("SELECT (1, 2)", "Not supported: row values@1:8"),
                Map.entry("SELECT * EXCEPT (a) FROM t", "Not supported: * EXCEPT@1:8"),
                Map.entry("SELECT E'a'", "Not supported: a string with a prefix@1:8"),
                Map.entry("SELECT TIME '10:00'", "Not supported: TIME literals@1:8"),
                Map.entry("SELECT 1 LIMIT 2, 3", "Not supported: LIMIT with an offset before the count@1:10"),
                Map.entry("SELECT a.b(1)", "Not supported: qualified function names@1:8"),
                Map.entry("FROM t", "Not supported: FROM QUERY@1:1"),
                Map.entry("SELECT CAST(a AS TIME)", "Type TIME is not supported@1:8"),
                Map.entry("SELECT * FROM a JOIN b", "Syntax error: JOIN needs ON, USING or NATURAL@1:17"),
                Map.entry("SELECT * FROM a CROSS JOIN b ON true", "Syntax error: CROSS JOIN takes no join condition@1:17"),
                Map.entry("SELECT `a` FROM t", "Syntax error at or near \"`a`\"@1:8"),
                Map.entry("SELECT * FROM t x 'y'", "Syntax error at or near \"'y'\"@1:19"),
                Map.entry("SELECT 1; SELECT 2", "Only one statement is supported@1:11"),
                Map.entry("INSERT INTO t VALUES (1)", "Only queries are supported, not INSERT@0:0"));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException e = error(c.getKey());
            assertEquals(c.getValue(), e.reason() + "@" + e.position().line() + ":" + e.position().column(),
                    c.getKey());
        }
    }

    @Test
    void syntaxErrorsPointIntoTheOriginalText() {
        SqlException e = error("SELECT a // b FROM t WHERE");
        assertEquals("Syntax error at or near \"WHERE\"", e.reason());
        assertEquals(new Position(1, 22), e.position());
        assertEquals("Syntax error at or near \"WHERE\" at line 1, column 22", e.getMessage());
        e = error("SELECT a,\n\tb // 2,\r\n  c FROM t WHERE x == ==");
        assertEquals("Syntax error at or near \"==\"", e.reason());
        assertEquals(new Position(3, 23), e.position());
        e = error("SELECT a FROM\n t WHERE (a = 1");
        assertEquals("Syntax error at end of input", e.reason());
        assertEquals(new Position(2, 16), e.position());
        e = error("SELECT 'abc");
        assertEquals("Unterminated quoted string, quoted identifier or comment", e.reason());
        assertEquals("Empty query", error("  -- nothing").reason());
        assertEquals("Empty query", error("").reason());
    }
}
