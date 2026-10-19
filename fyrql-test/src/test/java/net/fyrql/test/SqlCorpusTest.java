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

package net.fyrql.test;

import net.fyrql.FyrQL;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a corpus of SQL texts in FyrQL and in DuckDB, over the same tables:
 * FyrQL parses and binds each text, DuckDB runs the text as it is, and the
 * column types and rows must agree. Texts that must not bind fail in both
 * engines, FyrQL with the expected message; the known deviations fail in
 * FyrQL only.
 */
public class SqlCorpusTest {

    public static final List<String> QUERIES = List.of(
            // Columns, literals and operators
            "SELECT * FROM t",
            "SELECT a, b FROM t WHERE a > 2",
            "SELECT A, T.B, t.c FROM t",
            "SELECT main.t.a, memory.main.t.b FROM t",
            "SELECT * FROM memory.main.t WHERE a = 1",
            "SELECT * FROM main.t WHERE a = 1",
            "SELECT a + 1, a * 2 - e, c / 2, a // 2, a % 3, -a, +a FROM t",
            "SELECT e + 1, e * 2, e - 1000, typeof(e + 1), typeof(e + 1000) FROM t",
            "SELECT m + 1, m * 10, s + 1, h + 1, g + 1, fl + 1, m + 1.5, s // 2, typeof(m + 1) FROM w",
            "SELECT 1, 2147483648, -2147483648, 9223372036854775808, -9223372036854775808, 1.50, .5, 1e3, 'x',"
                    + " true, NULL, DATE '2024-02-29', TIMESTAMP '2024-01-01 10:00:00'",
            "SELECT typeof(1), typeof(-2147483648), typeof(1.50), typeof(1e3), typeof(NULL), typeof('a'),"
                    + " typeof(NULL + 1), typeof(-NULL)",
            "SELECT a FROM t WHERE b = 'x' OR c < 0 AND NOT e = 4",
            "SELECT a FROM t WHERE b IS NULL OR d IS NOT NULL AND a BETWEEN 2 AND 4",
            "SELECT a FROM t WHERE a NOT BETWEEN 2 AND 4",
            "SELECT a, a IN (1, 3, 5), a NOT IN (2), b IN ('x', 'Z'), a IN (1.5, 2), a IN (1, '2') FROM t",
            "SELECT a FROM t WHERE b LIKE 'x%' OR b ILIKE 'z' OR b NOT LIKE '_'",
            "SELECT a, a IS DISTINCT FROM e, b IS NOT DISTINCT FROM 'x', a IS NULL, b IS NOT NULL FROM t",
            "SELECT a FROM t WHERE 1",
            "SELECT a FROM t WHERE a",
            "SELECT a FROM t WHERE NULL",
            "SELECT CASE WHEN a > 3 THEN 'big' WHEN a = 1 THEN 'one' ELSE 'small' END,"
                    + " CASE b WHEN 'x' THEN 1 WHEN 'y' THEN 2 END, CASE a WHEN 1 THEN 'one' WHEN '2' THEN 'two' END"
                    + " FROM t",
            "SELECT CASE WHEN a > 3 THEN e ELSE 0 END, coalesce(e, 0), coalesce(b, 'none'), ifnull(c, 1),"
                    + " nullif(a, 2), nullif(e, 3), if(a > 2, 'y', 'n'), coalesce(a, '7'), coalesce(NULL, 1, 2.5) FROM t",
            "SELECT CASE WHEN s > 3 THEN m ELSE 0 END, coalesce(m, 0), greatest(s, 1), least(s, 1) FROM w",
            "SELECT CAST(a AS VARCHAR), a::DOUBLE, TRY_CAST(b AS INTEGER), CAST('2024-01-02' AS DATE),"
                    + " CAST(c AS DECIMAL(10,2)), CAST(NULL AS VARCHAR) FROM t",
            "SELECT a FROM t WHERE d = '2024-01-02'",
            "SELECT a FROM t WHERE d > '2024-01-01' AND a <> '3'",
            "SELECT a, b = 1 FROM t WHERE b IS NULL",
            "SELECT a || b, b || 1, d || '', upper(b) || lower(b), concat(a, b, NULL), 'a' || NULL FROM t",
            "SELECT length(b), substring(b, 1, 1), replace(b, 'x', 'q'), strpos(b, 'y'), trim('  a  ') FROM t",
            "SELECT d + 1, d - 1, d - DATE '2024-01-01', year(d), month(d), day(d), date_part('year', d),"
                    + " date_part('epoch', d), extract(month FROM d), date_trunc('month', d),"
                    + " strftime(d, '%Y/%m/%d') FROM t",
            "SELECT CAST(DATE '1998-12-01' - INTERVAL '90' DAY AS DATE), CAST(DATE '2024-01-31' + INTERVAL 1 MONTH"
                    + " AS DATE), TIMESTAMP '2024-01-31 10:00:00' + INTERVAL '36' HOUR, d < DATE '2024-01-01'"
                    + " + INTERVAL '1' YEAR, a FROM t WHERE d >= DATE '2024-03-01' - INTERVAL '2' MONTHS",
            "SELECT ts, hour(ts), date_diff('day', ts, TIMESTAMP '2024-03-01 00:00:00'), strftime(ts, '%H:%M'),"
                    + " CAST(ts AS DATE), ts > DATE '2024-01-01' FROM w",
            "SELECT count(*) FROM t, w WHERE d < ts",
            "SELECT greatest(a, e, 2), least(c, 1), greatest(a, '7') FROM t",
            "SELECT round(c, 1), abs(c), floor(c), sign(c) FROM t",
            // Aggregation
            "SELECT count(*), count(b), count(DISTINCT b), sum(a), avg(c), min(b), max(d), sum(e), count() FROM t",
            "SELECT b, count(*), sum(a) FROM t GROUP BY b",
            "SELECT b, count(*) FROM t GROUP BY ALL",
            "SELECT b AS g, sum(a) AS s FROM t GROUP BY g HAVING s > 3",
            "SELECT a % 2, count(*) FROM t GROUP BY a % 2",
            "SELECT a % 2 AS k, count(*) FROM t GROUP BY 1",
            // DuckDB folds the minus signs into the position.
            "SELECT k % 2 AS x, count(*) FROM t GROUP BY -(-1) ORDER BY -(-1) DESC",
            "SELECT a % 2 AS a, count(*) FROM t GROUP BY a",
            "SELECT k, e, count(*) FROM t GROUP BY k, e",
            "SELECT k + 1, max(a) - min(a), count(*) + 1, sum(a) * 2 FROM t GROUP BY k",
            "SELECT count(*), sum(a), max(b) FROM t WHERE a > 10",
            "SELECT b, string_agg(CAST(a AS VARCHAR), '-' ORDER BY a DESC), string_agg(b) FROM t GROUP BY b",
            "SELECT list(a ORDER BY a), string_agg(b, NULL) FROM t",
            "SELECT string_agg(b, CAST(NULL AS VARCHAR)), string_agg(b, '-' || '+'), string_agg(b, upper('x')) FROM t",
            // A constant NULL argument folds a call to NULL, and its other arguments are not evaluated.
            "SELECT NULL::INT + CAST('b' AS INT), log(-1) - NULL::DOUBLE, upper(CAST('b' AS INT)::VARCHAR) || NULL::VARCHAR,"
                    + " NULL::DECIMAL(10,2) + 1, -NULL::DECIMAL(10,2), abs(NULL::DECIMAL(10,2)),"
                    + " round(NULL::DECIMAL(10,2), 1), NULL::DECIMAL(10,2) // 2, nullif(1, 1) + CAST('b' AS INT),"
                    + " NULL::TINYINT * 2, length(NULL::VARCHAR), NULL::VARCHAR || 'x', NULL::DATE + 1",
            "SELECT a + NULL::INT + CAST('b' AS INT), (e + 2147483647::INT * 2) * NULL::INT FROM t",
            "SELECT NULL::INT BETWEEN 1 AND CAST('b' AS INT), NULL::DECIMAL(6,2) NOT BETWEEN NULL::BIGINT AND -a FROM t",
            "SELECT NULL + m, m + NULL::DECIMAL(10,2), (NULL::DECIMAL(10,2) + m) + 1, typeof(NULL + m) FROM w",
            "SELECT NULL::DECIMAL(10,2) + m AS x FROM w UNION ALL SELECT DATE '2024-01-01'",
            // A boolean equals a fractional number as that number, and unites with any number.
            "SELECT 1.0::DOUBLE = true, 1.5::DOUBLE <> true, 1.00::DECIMAL(5,2) = true, 1.0::FLOAT IN (true),"
                    + " true IS DISTINCT FROM 1.0::DOUBLE, 2::HUGEINT > true, c = true, 1 IN (true, 2.5::DOUBLE) FROM t",
            "SELECT round(m, 1), round(m, -1), round(m, 0), round(m, 3), round(m), round(m, CAST(1 AS INTEGER)),"
                    + " round(999.99::DECIMAL(5,2), 1), round(-1.25::DECIMAL(5,2), 1), round(55::DECIMAL(2,0), -1),"
                    + " round(m, NULL::INT) FROM w",
            "SELECT 1.5 AS x UNION ALL SELECT true",
            // GROUP BY ALL with nothing to group by and no aggregate does not group.
            "SELECT 1.0100000000 FROM t GROUP BY ALL",
            "SELECT 1 + 1 AS x FROM t WHERE a > 1 GROUP BY ALL ORDER BY 1",
            "SELECT DISTINCT 1 FROM t GROUP BY ALL",
            // VALUES types its columns row by row: a later string literal takes the type of the column.
            "SELECT * FROM (VALUES (DATE '2024-01-01'), ('2024-01-02')) v(a)",
            "SELECT * FROM (VALUES (1), ('2')) v(a)",
            "SELECT * FROM (VALUES (1.5), ('2')) v(a)",
            "SELECT * FROM (VALUES (true), ('false')) v(a)",
            "SELECT * FROM (VALUES (1), (NULL), ('3')) v(a)",
            "SELECT right(b, 9223372036854775807), right(b, -4294967296), left(b, -4294967296) FROM t",
            "SELECT string_agg(b ORDER BY 1), list(a ORDER BY true), list(a ORDER BY -1) FROM t",
            "SELECT list('x' ORDER BY 'x'), list(-1.5 ORDER BY -1.5) FROM t",
            // Decimal arithmetic stays within 18 digits for operands of at most 18, while the scale allows.
            "SELECT 27 + 10.0300000000, a + 10.0300000000, 1.00000000000000000 + 1, 1.000000000000000000 + 1,"
                    + " 6.1400000000 * 11.0200000000, 1.123456789 * 1.123456789 FROM t",
            "SELECT substring('abc', -5, 3), substring('abc', 0, 1), substring('abc', 0, 2), substring('abc', 5, -3),"
                    + " substring('abcdef', -2147483648, 5), substring('x y', 17, -20), substring('abc', -4, -1),"
                    + " substring('abc', -5)",
            "SELECT * FROM (VALUES (CAST(0 AS TINYINT)), (-3)) v(a)",
            "SELECT * FROM (VALUES (-3), (CAST(0 AS TINYINT))) v(a)",
            // The merged column of USING comes before other columns of its name.
            "SELECT a FROM t JOIN u USING (a) JOIN t AS t3 ON true",
            // A bare NULL of a subquery keeps its open type.
            "SELECT typeof(z), z >= '2024-07-29', z + 1 FROM (SELECT NULL AS z) s",
            "WITH c AS (SELECT NULL AS z) SELECT typeof(z) FROM c",
            "SELECT typeof(z) FROM (SELECT NULL AS z UNION ALL SELECT NULL) s",
            "SELECT typeof(z) FROM (SELECT CAST(NULL AS INTEGER) AS z) s",
            "SELECT typeof(z) FROM (SELECT NULL::DECIMAL(10,2) + 1 AS z) s",
            // A string literal converts only when a row reaches it.
            "SELECT a FROM (SELECT * FROM t WHERE false) s WHERE a = 'x' OR d = DATE 'abc' OR a IN (1, 'x')",
            "SELECT CASE WHEN a > 0 THEN 1 ELSE 'x' END, coalesce(a, 'x') FROM (SELECT * FROM t WHERE false) s",
            "SELECT * REPLACE (5 AS b) FROM (SELECT 1 AS B) s CROSS JOIN (SELECT 2 AS b, 3 AS c) p",
            // COALESCE and BETWEEN unite from left to right; CASE lets literals take the type of the others.
            "SELECT typeof(coalesce(1, 2, e)), typeof(coalesce(e, 1, 2)), typeof(CASE WHEN a > 0 THEN 1 WHEN a > 1"
                    + " THEN 2 ELSE e END), typeof(coalesce(d, '2024-01-01', '2024-01-02')) FROM t",
            "SELECT typeof(CASE WHEN a > 0 THEN 0 WHEN a > 1 THEN 18.64 ELSE e END), typeof(coalesce(e, 0, 1.5)),"
                    + " typeof(coalesce(0, e, 1.5)), typeof(coalesce(0, e, 70000, 1.5)), coalesce(0, e, 1.5) FROM t",
            "SELECT concat('', x), count(*) FROM (VALUES (CAST('-0.0' AS DOUBLE)), (0.0::DOUBLE)) v(x) GROUP BY x",
            "SELECT CAST(b AS INTEGER) FROM t INTERSECT SELECT a FROM t WHERE false",
            "SELECT typeof(CASE WHEN a > 0 THEN CAST(1 AS DECIMAL(30,0)) ELSE CAST(1 AS DECIMAL(20,15)) END),"
                    + " CASE WHEN a > 9 THEN CAST(10 AS DECIMAL(30,0)) ELSE CAST(1.123456789 AS DECIMAL(20,15)) END,"
                    + " typeof(coalesce(CAST(1 AS DECIMAL(38,2)), CAST(1 AS DECIMAL(38,5)))) FROM t",
            "SELECT '2024-01-02' BETWEEN d AND '2024-01-03', d BETWEEN '2024-01-01' AND '2024-01-03' FROM t",
            "SELECT typeof(CASE WHEN a > 0 THEN NULL END), typeof(CASE WHEN a > 0 THEN NULL END % 17.67),"
                    + " typeof(coalesce(NULL, NULL) + 1.5) FROM t",
            "SELECT typeof(x), typeof(x % 2) FROM (VALUES (NULL), (NULL)) v(x)",
            "SELECT typeof(round(NULL, 0)), typeof(abs(NULL)), typeof(sign(NULL)), typeof(trunc(NULL)),"
                    + " typeof(power(NULL, 2)), typeof(left(NULL, 2)), typeof(sqrt(NULL)), typeof(round(NULL, a)) FROM t",
            "SELECT CAST(4.75::FLOAT AS DECIMAL(18,4)), CAST(4.75::FLOAT AS DECIMAL(18,10)), CAST(3.75::FLOAT AS"
                    + " DECIMAL(38,10)), CAST(0.1::FLOAT AS DECIMAL(38,10)), CAST(0.1::DOUBLE AS DECIMAL(38,30)),"
                    + " CAST(1.1::FLOAT AS DECIMAL(18,9))",
            "SELECT 1.2600000000 % a, m * 2147483647, 1.2600000000 % 2147483647 FROM t, w",
            "SELECT typeof(CASE WHEN a > 0 THEN 10 WHEN a > 1 THEN s END), typeof(CASE WHEN true THEN 'a' ELSE 1 END),"
                    + " typeof(CASE WHEN true THEN 1 ELSE 'a' END), greatest(true, 2, 1.5), least(NULL::BOOLEAN, 3, 2.5)"
                    + " FROM t, w",
            "SELECT h FROM w WHERE h BETWEEN fl AND 8.100",
            "SELECT typeof(CASE WHEN a > 0 THEN -(-26) ELSE s END), typeof(greatest(17, NULL, s)),"
                    + " typeof(greatest(false, 1, CAST(9 AS TINYINT))) FROM t, w",
            "SELECT CAST(NULL AS INTEGER) AS x FROM t EXCEPT SELECT s FROM w",
            "SELECT if(NULL, 'Ab', total) FROM (SELECT NULL AS total, min(a) AS a2 FROM t) s0",
            "SELECT CAST(-5.125::DOUBLE AS DECIMAL(6,2)), CAST(2.625::DOUBLE AS DECIMAL(6,2)), CAST(-2.5::FLOAT AS"
                    + " DECIMAL(4,0))",
            "SELECT a FROM t ORDER BY true, DATE '2024-01-01', a",
            "SELECT true AS x UNION ALL SELECT 2.5::FLOAT",
            "SELECT bool_and(a > 0), bool_or(b = 'Z'), first(a ORDER BY a DESC), sum(1), avg(1), count(1) FROM t",
            "SELECT 1, count(*) FROM t GROUP BY ALL",
            "SELECT 1, count(*) FROM t WHERE false GROUP BY ALL",
            "SELECT 'x' AS k, count(*) FROM t WHERE false GROUP BY 1",
            "SELECT count(*) FROM t HAVING count(*) > 1",
            "SELECT * FROM t GROUP BY ALL",
            "SELECT b, count(*) FROM t GROUP BY b ORDER BY count(*) DESC, b NULLS FIRST",
            "SELECT sum(a) AS s FROM t GROUP BY b ORDER BY s, b",
            "SELECT b, e FROM t GROUP BY b, e HAVING e > 3 OR b = 'x'",
            "SELECT DISTINCT b FROM t",
            "SELECT DISTINCT b, e FROM t ORDER BY ALL",
            "SELECT DISTINCT a % 2 AS p FROM t ORDER BY p DESC",
            "SELECT DISTINCT a + 1 FROM t ORDER BY a + 1",
            "SELECT count(DISTINCT e), sum(DISTINCT e), avg(DISTINCT e), min(DISTINCT e) FROM t",
            "SELECT sum(ALL e), count(ALL b), count(ALL *) FROM t",
            "SELECT DISTINCT count(*) FROM t GROUP BY b",
            // Order and limit
            "SELECT a FROM t ORDER BY a DESC LIMIT 3",
            "SELECT a FROM t ORDER BY a LIMIT 2 OFFSET 2",
            "SELECT a FROM t ORDER BY c DESC NULLS FIRST, a",
            "SELECT -a AS a FROM t ORDER BY a",
            "SELECT -a AS a FROM t ORDER BY t.a",
            "SELECT -a AS b FROM t ORDER BY b",
            "SELECT a + 1 AS k FROM t ORDER BY k + 0 DESC",
            "SELECT b, a FROM t ORDER BY 2 DESC",
            "SELECT a, b FROM t ORDER BY ALL DESC",
            "SELECT a FROM t ORDER BY 1 + 1, a",
            "SELECT count(*) FROM (SELECT a FROM t LIMIT '2')",
            "SELECT count(*) FROM (SELECT a FROM t LIMIT NULL OFFSET 1)",
            "SELECT a FROM t ORDER BY a LIMIT 1 + 1",
            "SELECT a FROM t ORDER BY a LIMIT 1.5",
            // Joins
            "SELECT t.a, f FROM t JOIN u ON t.a = u.a",
            "SELECT * FROM t JOIN u USING (a)",
            "SELECT * FROM t LEFT JOIN u USING (a)",
            "SELECT * FROM t RIGHT JOIN u USING (a)",
            "SELECT * FROM t FULL JOIN u USING (a)",
            "SELECT a, t.a, u.a FROM t FULL JOIN u USING (A)",
            "SELECT t.*, u.* FROM t JOIN u USING (a)",
            "SELECT * FROM t NATURAL JOIN u",
            "SELECT * FROM (SELECT 1 AS x, 2 AS k, 5 AS j) l JOIN (SELECT 3 AS y, 5 AS j, 2 AS k) r USING (j, k)",
            "SELECT t.*, u.f FROM t LEFT JOIN u ON t.a = u.a AND u.f = 'p'",
            "SELECT * FROM t LEFT JOIN u ON t.a = u.a AND t.e > 3",
            "SELECT t.a, u.f FROM t FULL JOIN u ON t.a = u.a AND t.b = 'x'",
            "SELECT t.a, u.f FROM t FULL JOIN u ON u.a = t.a AND u.n > 5",
            "SELECT t.a, u.a FROM t JOIN u ON t.a < u.a",
            "SELECT t.a, u.a FROM t JOIN u ON t.e = u.a",
            "SELECT t.a, u.n FROM t JOIN u ON t.a + 1 = u.a",
            "SELECT t.a, u.n FROM t JOIN u ON t.a = u.a AND t.a <> u.n AND t.b IS NOT NULL",
            "SELECT count(*) FROM t, u",
            "SELECT count(*) FROM t CROSS JOIN u WHERE t.a = u.a",
            "SELECT x.a, y.a FROM t AS x JOIN t AS y ON x.a = y.a + 1",
            "SELECT * FROM t AS x(p, q) WHERE x.p < 3",
            "SELECT t.a, u.f, w.s FROM t JOIN u ON t.a = u.a JOIN w ON u.a = w.s",
            "SELECT t.a, u.f FROM t LEFT JOIN u ON true WHERE t.a = 1",
            "SELECT t.a, u.a FROM t LEFT JOIN u ON u.f = 'p'",
            "SELECT t.a, u.a FROM t LEFT JOIN u ON 1 = 0",
            "SELECT t.a, u.a FROM t RIGHT JOIN u ON t.a = u.a",
            // Conditions of outer joins that are neither keys nor filters of the side without the preserved rows.
            "SELECT t.a, u.a FROM t LEFT JOIN u ON t.a < u.a",
            "SELECT t.a, t.b, u.a, u.f FROM t LEFT JOIN u ON t.a = u.a AND t.b < u.f",
            "SELECT t.a, u.a, u.n FROM t RIGHT JOIN u ON t.a = u.a AND t.e < u.n OR u.n IS NULL",
            "SELECT t.a, t.e, u.a, u.n FROM t FULL JOIN u ON t.a = u.a AND t.e <> u.n",
            "SELECT t.a, u.a FROM t FULL JOIN u ON t.a + u.a = 4 AND t.e > 1",
            "SELECT t.a, u.f FROM t RIGHT JOIN u ON u.f = 'p' AND t.b IS NULL",
            "SELECT * FROM t JOIN u ON t.b = u.f",
            "SELECT t.a, u.f FROM t, u WHERE t.a = u.a AND u.f <> 'q'",
            // Conditions of the WHERE moved into the joins and to the tables
            "SELECT t.a, u.f, w.s FROM t, u, w WHERE t.a = u.a AND u.a = w.s AND t.b <> 'y'",
            "SELECT t.a, u.n FROM t, u WHERE t.a = u.n AND t.k > 10",
            "SELECT t.a, w.s FROM t, w WHERE t.e = w.s",
            "SELECT t.a, u.a FROM t, u WHERE t.a + 1 = u.a OR t.a = u.a",
            "SELECT x.a, y.b FROM t AS x, t AS y WHERE x.k = y.k AND x.a < y.a",
            "SELECT count(*) FROM t, u, w WHERE t.k = 10 AND w.g > 0",
            "SELECT t.a, u.f FROM t LEFT JOIN u ON t.a = u.a WHERE u.f IS NULL AND t.e > 3",
            "SELECT t.a, u.f FROM t RIGHT JOIN u ON t.a = u.a WHERE t.b = 'x' OR u.n > 5",
            "SELECT * FROM (SELECT b, count(*) AS c FROM t GROUP BY b) g, u WHERE g.b <> 'y' AND g.c = u.a",
            "SELECT a, x FROM (SELECT a, a * 2 AS x FROM t UNION ALL SELECT a, n FROM u) s WHERE x > 4",
            "SELECT * FROM (SELECT a, b FROM t ORDER BY a LIMIT 3) s, u WHERE s.a = u.a",
            "SELECT count(*) FROM t AS x JOIN u AS x ON true",
            // Subqueries, WITH
            "SELECT * FROM (SELECT a, b FROM t WHERE a > 2) s WHERE s.a < 6",
            "SELECT * FROM (SELECT 1 AS a, 2 AS a)",
            "SELECT * FROM (SELECT 1 AS a, 2 AS A, 3 AS a_1)",
            "SELECT x FROM (SELECT a AS x FROM t) ORDER BY x DESC",
            "SELECT unnamed_subquery.* FROM (SELECT a FROM t)",
            "SELECT a FROM (SELECT 1 AS a), (SELECT 2 AS b)",
            "WITH s AS (SELECT a, b FROM t WHERE a < 4) SELECT * FROM s JOIN s AS s2 USING (a)",
            "WITH s(x, y) AS (SELECT a, b, c FROM t) SELECT * FROM s",
            "WITH s AS (SELECT 1 AS v), r AS (SELECT v + 1 AS v FROM s) SELECT * FROM r UNION ALL SELECT * FROM s",
            "WITH t AS (SELECT 9 AS z) SELECT * FROM t",
            "SELECT * FROM (WITH q AS (SELECT a FROM t) SELECT max(a) FROM q) z",
            // Subqueries in expressions
            "SELECT a FROM t WHERE a IN (SELECT a FROM u)",
            "SELECT a FROM t WHERE a NOT IN (SELECT a FROM u)",
            "SELECT a FROM t WHERE a NOT IN (SELECT a FROM u WHERE a IS NOT NULL)",
            "SELECT a FROM t WHERE a IN (SELECT a FROM u WHERE a > 100)",
            "SELECT a FROM t WHERE a NOT IN (SELECT a FROM u WHERE a > 100)",
            "SELECT a, k IN (SELECT a FROM u), k NOT IN (SELECT n FROM u) FROM t",
            "SELECT a, e IN (SELECT n FROM u WHERE n IS NOT NULL) FROM t",
            "SELECT a FROM t WHERE a + 1 IN (SELECT n FROM u) OR b = 'Z'",
            "SELECT a FROM t WHERE b IN (SELECT f FROM u) AND a > 1",
            "SELECT a FROM t WHERE '3' IN (SELECT a FROM u)",
            "SELECT a FROM t WHERE c IN (SELECT a FROM u)",
            "SELECT a FROM t WHERE EXISTS (SELECT * FROM u WHERE n > 50)",
            "SELECT a FROM t WHERE NOT EXISTS (SELECT * FROM u WHERE n > 500)",
            "SELECT a FROM t WHERE NOT EXISTS (SELECT * FROM u)",
            "SELECT a, EXISTS (SELECT 1 FROM u WHERE a > 3) AS x FROM t",
            "SELECT a, CASE WHEN EXISTS (SELECT 1 FROM w) THEN b ELSE 'none' END FROM t",
            "SELECT a FROM t WHERE a > 4 OR NOT EXISTS (SELECT 1 FROM u WHERE a = 2)",
            "SELECT a FROM t WHERE a > (SELECT avg(a) FROM u)",
            "SELECT a FROM t WHERE a = (SELECT max(a) FROM u) OR a = (SELECT min(a) FROM u)",
            "SELECT a, (SELECT max(n) FROM u) - a AS d FROM t",
            "SELECT a, (SELECT n FROM u WHERE a > 3) FROM t",
            "SELECT (SELECT 1), (SELECT 'x' WHERE false)",
            "SELECT a, (SELECT f FROM u ORDER BY n DESC NULLS LAST LIMIT 1) FROM t ORDER BY a",
            "SELECT b, sum(a) FROM t GROUP BY b HAVING sum(a) > (SELECT avg(a) FROM u)",
            "SELECT b, count(*) FROM t GROUP BY b HAVING b IN (SELECT f FROM u) OR b IS NULL",
            "SELECT b, sum(a) / (SELECT sum(a) FROM t) AS share FROM t GROUP BY b ORDER BY b",
            "SELECT count(*), (SELECT count(*) FROM u) FROM t",
            "SELECT DISTINCT (SELECT max(a) FROM u) > a FROM t",
            "SELECT a FROM t WHERE (SELECT count(*) FROM u WHERE n > 1000) = 0",
            "SELECT a FROM t WHERE (a IN (SELECT a FROM u)) IS NULL",
            "SELECT a FROM t WHERE a IN (SELECT DISTINCT a + 0 FROM u UNION SELECT 6)",
            "WITH m AS (SELECT max(a) AS x FROM u) SELECT a FROM t WHERE a < (SELECT x FROM m)",
            "SELECT * FROM (SELECT a FROM t WHERE a IN (SELECT a FROM u)) s WHERE s.a > 1",
            "SELECT a FROM t WHERE a IN (SELECT a FROM u WHERE a IN (SELECT a FROM t WHERE e > 3))",
            // Correlated subqueries
            "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a)",
            "SELECT a FROM t WHERE NOT EXISTS (SELECT 1 FROM u WHERE u.a = t.a AND u.n > 5)",
            "SELECT a, EXISTS (SELECT 1 FROM u WHERE u.a = t.a AND u.f <> t.b) FROM t",
            "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a <> t.a AND u.n < t.k)",
            "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a + 1) OR EXISTS (SELECT 1 FROM u"
                    + " WHERE u.a + 1 = t.a)",
            "SELECT a FROM t WHERE EXISTS (SELECT 1 WHERE t.a > 3)",
            "SELECT a FROM t WHERE EXISTS (SELECT DISTINCT f FROM u WHERE u.a = t.a ORDER BY f)",
            "SELECT a, (SELECT count(*) FROM u WHERE u.a = t.a) FROM t",
            "SELECT a, (SELECT count(n) + 1 FROM u WHERE u.a = t.a), (SELECT sum(n) FROM u WHERE u.a = t.a),"
                    + " (SELECT coalesce(max(f), '-') FROM u WHERE u.a = t.a) FROM t",
            "SELECT a, (SELECT count(*) FROM u WHERE u.a = t.e) FROM t",
            "SELECT a FROM t WHERE e < (SELECT count(*) FROM u WHERE u.a = t.a AND t.k > 10)",
            "SELECT a, (SELECT f FROM u WHERE u.a = t.a AND u.f = 'p') FROM t",
            "SELECT a FROM t WHERE a IN (SELECT a FROM u WHERE u.n > t.e)",
            "SELECT a FROM t WHERE a NOT IN (SELECT a FROM u WHERE u.n > t.e)",
            "SELECT a, '3' IN (SELECT a FROM u), NULL IN (SELECT f FROM u), b IN (SELECT NULL) FROM t",
            "SELECT a, a IN (SELECT a FROM u WHERE u.n > t.e), a NOT IN (SELECT a FROM u WHERE u.n < t.k) FROM t",
            "SELECT a, k IN (SELECT n FROM u WHERE u.a = t.a), e NOT IN (SELECT n FROM u WHERE u.a = t.a) FROM t",
            "SELECT b, count(*) FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a) GROUP BY b",
            "SELECT k, count(*) FROM t GROUP BY k HAVING EXISTS (SELECT 1 FROM u WHERE u.n > t.k)",
            "SELECT k, (SELECT max(n) FROM u WHERE u.a * 10 = t.k) FROM t GROUP BY k",
            "SELECT a FROM t WHERE 1 IN (SELECT count(*) FROM u WHERE u.a = t.a GROUP BY f)",
            "SELECT a, (SELECT sum(n) FROM u WHERE u.a = t.a GROUP BY u.a HAVING sum(n) > 10) FROM t",
            "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a AND u.n > (SELECT avg(n) FROM u x"
                    + " WHERE x.f = u.f))",
            "SELECT a FROM t WHERE e = (SELECT max(e) FROM t x WHERE x.k = t.k)",
            "SELECT f FROM u, t WHERE u.a = t.a AND n >= (SELECT min(n) FROM u WHERE a = t.a)",
            "SELECT * FROM (SELECT a FROM t WHERE NOT EXISTS (SELECT 1 FROM u WHERE u.a = t.a)) s",
            // Set operations
            "SELECT a FROM t UNION SELECT a FROM u",
            "SELECT a FROM t UNION ALL SELECT a FROM u ORDER BY a NULLS FIRST",
            "SELECT a FROM t EXCEPT SELECT a FROM u",
            "SELECT a FROM t INTERSECT SELECT a FROM u",
            "SELECT e FROM t EXCEPT ALL SELECT 3",
            "SELECT b FROM t UNION ALL SELECT a FROM t",
            "SELECT 1 AS x UNION ALL SELECT NULL UNION ALL SELECT 2.5",
            "SELECT NULL AS x UNION ALL SELECT DATE '2024-01-01'",
            "SELECT s FROM w UNION ALL SELECT 1",
            "SELECT a AS z FROM t UNION SELECT a FROM u ORDER BY z DESC NULLS LAST",
            "SELECT a FROM t UNION SELECT a FROM u ORDER BY t.a",
            "SELECT 1 UNION SELECT 2 INTERSECT SELECT 2",
            "(SELECT a FROM t ORDER BY a LIMIT 2) UNION ALL (SELECT a FROM u ORDER BY a LIMIT 1)",
            "SELECT s, count(*) FROM (SELECT b AS s FROM t UNION ALL SELECT f FROM u) GROUP BY s",
            "SELECT 1 AS x, 2 AS y EXCEPT SELECT 1, 3",
            // VALUES, no FROM
            "VALUES (1, 'a'), (2, NULL)",
            "SELECT * FROM (VALUES (1), (2.5)) v(x)",
            "SELECT col0 + 1, v.col1 FROM (VALUES (1, 'q'), (NULL, 'r')) v",
            "VALUES (1, 2) ORDER BY 1",
            "SELECT 1 + 1, 'a' || 'b', current_date = today(), now() > TIMESTAMP '2020-01-01 00:00:00'",
            "SELECT count(*), sum(1)",
            // Stars and aliases
            "SELECT * EXCLUDE (b, c) FROM t",
            "SELECT * REPLACE (a * 10 AS a) FROM t",
            "SELECT t.* EXCLUDE (d), u.f FROM t JOIN u ON t.a = u.a",
            "SELECT * EXCLUDE (b, c, d, e, k), count(*) FROM t GROUP BY ALL",
            "SELECT a AS x, x + 1 AS y, y * 2 FROM t",
            "SELECT a AS x FROM t WHERE x > 2",
            "SELECT a AS b FROM t WHERE b = 'x'",
            "SELECT a + 1 AS a FROM t WHERE a = 2",
            "SELECT day, Year + 1, max(hour) FROM (SELECT 1 AS day, 2 AS year, 3 AS hour) GROUP BY ALL",
            // Nested values: fields of structs by name in any case, elements of lists from 1, values of keys.
            "SELECT * FROM n",
            "SELECT id, typeof(s), typeof(l), typeof(m), typeof(ll) FROM n",
            "SELECT id, s.a, s.b, n.s.a, s.name, s.c.x, s.c.tags, s.A, S.B, memory.main.n.s.c.x FROM n",
            "SELECT id, s['a'], s['B'], s['c']['tags'], s.c['tags'][1], struct_extract(s, 'name') FROM n",
            "SELECT id, l[1], l[2], l[-1], l[0], l[10], l[-10], l['1'], l[NULL], ll[1][2], ll[2][1] FROM n",
            "SELECT id, list_extract(l, 1), list_element(l, -2), array_extract(l, 2), l[id - 3] FROM n",
            "SELECT id, m['a'], m['z'], m.b, map_extract(m, 'a'), element_at(m, 'c'), map_extract_value(m, 'b'),"
                    + " map_extract(m, NULL), m[NULL] FROM n",
            "SELECT id, len(l), length(l), array_length(l), array_length(l, 1), len(ll), len(ll[1]) FROM n",
            "SELECT id, list_contains(l, 5), list_has(l, 1.0), array_contains(l, NULL), list_contains(l, '2'),"
                    + " array_has(s.c.tags, 'y'), list_contains(ll, [3]) FROM n",
            "SELECT id, list_sort(l), list_sort(l, 'DESC'), list_sort(l, 'asc', 'nulls first'),"
                    + " list_sort(l, 'DESCENDING', 'NULLS_LAST'), list_reverse_sort(l), array_sort(ll),"
                    + " array_reverse_sort(l, 'NULLS FIRST') FROM n",
            "SELECT [1, 2, 3], [1, 2.5], ['a', NULL], [[1], [2, 3]], [DATE '2024-01-01', '2024-01-02'], ARRAY[1, 2],"
                    + " [1::TINYINT, 300], [2.5, 1], list_value(4, 5)",
            "SELECT {'a': 1, 'b': 'x'}, {a: 1}, {\"x y\": [1]}, struct_pack(k := 1, v := 'z'), {'n': NULL::INT},"
                    + " {'Ab': 1}.ab, ({'a': {'b': 2}}).a.b",
            "SELECT MAP {'k': 1, 'j': 2}, MAP {1: 'a'}, map(['x', 'y'], [1.5, 2.5]), MAP {'a': [1]}",
            "SELECT typeof([1, 2.5]), typeof({'a': 1, 'select': 2}), typeof(MAP {'k': 1}), typeof([[1], [2.5]]),"
                    + " typeof({'a': 1, 'x y': 2}), typeof(['a', 1])",
            "SELECT ([10, 20, 30])[2], [10, 20][1], 'abc'[2], 'abc'[0], 'abc'[-1], 'abc'[5], ({'a': 1}).a,"
                    + " ({'a': 1})['a'], (MAP {'k': 2})['k'], (MAP {1: 'x'})['1']",
            "SELECT id FROM n WHERE s.a > 0 ORDER BY s.a DESC",
            "SELECT id FROM n WHERE list_contains(l, 5) OR m['a'] = 1 OR s.c.tags[1] = 'x'",
            "SELECT s.b, count(*), sum(s.a), list(id ORDER BY id) FROM n GROUP BY s.b",
            "SELECT s.c.x AS x, count(*) FROM n GROUP BY x",
            "SELECT l, count(*) FROM n GROUP BY l",
            "SELECT id, l FROM n ORDER BY l NULLS FIRST, id",
            "SELECT id, l = [3, 1, 2], l <> [7], l IN ([7], [3, 1, 2]), l < [5], m = MAP {'b': 9}, ll = [[7, 8, 9]],"
                    + " s.c = {'x': 1.5, 'tags': ['x', 'y']} FROM n",
            "SELECT id, CAST(l AS BIGINT[]), l::VARCHAR[], CAST(s AS STRUCT(a BIGINT, b VARCHAR)),"
                    + " CAST(s.c AS STRUCT(TAGS VARCHAR[], z INTEGER)), TRY_CAST(['1', 'x'] AS INTEGER[]),"
                    + " CAST([1.5] AS INTEGER[]), CAST(s AS VARCHAR) FROM n",
            "SELECT CAST([] AS DATE[]), TRY_CAST([] AS VARCHAR[][]), CAST([] AS STRUCT(a INTEGER, b DATE)[]), [] AS e",
            "SELECT id, coalesce(l, [0]), CASE WHEN id > 2 THEN l ELSE [1.5] END, if(id = 1, s.c, NULL),"
                    + " coalesce(s.c, {'x': 1, 'tags': ['z']}) FROM n",
            "SELECT CASE WHEN true THEN {'a': 1} ELSE {'b': 2} END, coalesce({'a': 1}, {'A': 2.5}),"
                    + " greatest([1, 2], [1, 3]), least({'a': 1}, {'a': 0}), {'a': 1} = {'a': 1, 'b': NULL}",
            "SELECT list(s.a ORDER BY id), list(l ORDER BY id), min(l), max(l), count(s), count(DISTINCT l) FROM n",
            "SELECT id, l FROM n UNION ALL SELECT 9, [2.5]",
            "SELECT * FROM (VALUES ([1], {'a': 1}), ([2.5], {'a': 2})) v(x, y)",
            "SELECT t.x.a, x.a, t.x['a'] FROM (SELECT {'a': 7} AS x) t",
            "SELECT s.a FROM (SELECT {'a': 1} AS s) s",
            "SELECT t.a FROM (SELECT {'a': 1} AS t, 5 AS a) t",
            "SELECT s.s, s.s.s FROM (SELECT {'s': 1} AS s) s",
            "SELECT x.b.c, x['b']['c'], x.b['c'], x['b'].c FROM (SELECT {'a': 1, 'b': {'c': 2}} AS x) t",
            "SELECT id, s.c.tags[1] || '!', upper(s.b), l[1] + l[2], s.a * 2 FROM n WHERE id <> 2",
            // unnest in the select list: one row per element, none for an empty or NULL list.
            "SELECT id, unnest(l) FROM n",
            "SELECT id, unnest(l) AS x FROM n ORDER BY id, x NULLS FIRST",
            "SELECT id, unnest(l) + 1, unnest(l) * 2 FROM n WHERE id > 1",
            "SELECT id, unnest(ll) FROM n",
            "SELECT id, unnest(ll[1]) FROM n",
            "SELECT id, unnest(s.c.tags) AS tag FROM n",
            "SELECT unnest(s) FROM n",
            "SELECT unnest(s) AS z, id FROM n ORDER BY 5",
            "SELECT id, unnest(s.c), unnest(l) FROM n",
            "SELECT unnest([1, 2, 3]) AS x, 'x'",
            "SELECT unnest(NULL)",
            "SELECT unnest([])",
            "SELECT unnest({'a': 1, 'name': 'x'})",
            "SELECT count(*), unnest([1, 2]) FROM n",
            "SELECT s.b, unnest(list(id ORDER BY id)) FROM n GROUP BY s.b",
            "SELECT DISTINCT unnest(l) FROM n",
            "SELECT unnest(l) AS x FROM n ORDER BY x DESC LIMIT 3",
            "SELECT id, unnest(l) FROM n ORDER BY unnest(l), id",
            "SELECT id FROM n ORDER BY unnest(l), id",
            "SELECT coalesce(unnest(l), 0) FROM n",
            "SELECT unnest(CASE WHEN id = 1 THEN l END) FROM n",
            "SELECT unnest(l::BIGINT[]) FROM n",
            "SELECT unnest(l) FROM n UNION ALL SELECT 9",
            // unnest in FROM: after other items, it reads their columns.
            "SELECT * FROM n, unnest(n.l) ORDER BY id",
            "SELECT id, x FROM n, unnest(n.l) AS u(x)",
            "SELECT id, u.x FROM n, unnest(l) AS u(x, y)",
            "SELECT id, u.unnest FROM n, unnest(l) AS u",
            "SELECT id, unnest.unnest FROM n, unnest(l)",
            "SELECT id, y FROM n CROSS JOIN unnest(n.ll) AS u(y)",
            "SELECT id, x FROM n JOIN unnest(n.l) AS u(x) ON x > n.id",
            "SELECT id, x FROM n, LATERAL unnest(n.l) AS u(x)",
            "SELECT id, x, y FROM n, unnest(n.l) AS u(x), unnest(n.ll) AS w(y)",
            "SELECT * FROM n, unnest(NULL::INTEGER[]) AS u(x)",
            "SELECT * FROM unnest([1, 2, 3])",
            "SELECT * FROM unnest([1, 2, 3]) AS u(x) WHERE x > 1",
            "SELECT * FROM unnest([{'a': 1}, {'a': 2}])",
            "SELECT * FROM unnest([])",
            "SELECT x, y FROM unnest([1, 2]) AS u(x), unnest([x, x * 10]) AS w(y)",
            "SELECT * FROM unnest([1, 2]) AS u(x) JOIN t ON t.a = u.x",
            "SELECT * FROM t RIGHT JOIN unnest([1, 9]) AS u(x) ON t.a = u.x",
            "SELECT x, count(*) FROM n, unnest(n.l) AS u(x) GROUP BY x",
            "SELECT tag, list(id ORDER BY id) FROM n, unnest(n.s.c.tags) AS u(tag) GROUP BY tag",
            "WITH c AS (SELECT [1, 2] AS l) SELECT * FROM c, unnest(c.l) AS u(x)",
            "SELECT * FROM (SELECT id, l FROM n) t, unnest(t.l)",
            // Window functions; an order with ties leaves only results that do not depend on the order of peers.
            "SELECT a, k, row_number() OVER (PARTITION BY k ORDER BY a DESC), rank() OVER (ORDER BY k),"
                    + " dense_rank() OVER (ORDER BY k), percent_rank() OVER (ORDER BY k),"
                    + " cume_dist() OVER (ORDER BY k), ntile(4) OVER (ORDER BY a) FROM t",
            "SELECT a, lag(a) OVER (ORDER BY a), lead(a, 2, -1) OVER (ORDER BY a), lag(b, -1) OVER (ORDER BY a),"
                    + " first_value(c) OVER (ORDER BY a), last_value(c) OVER (ORDER BY a),"
                    + " nth_value(b, 2) OVER (ORDER BY a) FROM t",
            "SELECT a, sum(a) OVER (ORDER BY a ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING), avg(c) OVER (PARTITION BY k),"
                    + " count(*) OVER (), count(c) OVER (ORDER BY a), min(b) OVER (ORDER BY a DESC),"
                    + " max(d) OVER (PARTITION BY k ORDER BY a) FROM t",
            "SELECT k, sum(a), sum(sum(a)) OVER (ORDER BY k), rank() OVER (ORDER BY count(*) DESC, k) FROM t"
                    + " GROUP BY k",
            "SELECT sum(count(*)) OVER () FROM t",
            "SELECT b, count(*), sum(count(*)) OVER (), row_number() OVER (ORDER BY b) FROM t GROUP BY b"
                    + " HAVING count(*) > 1 QUALIFY row_number() OVER (ORDER BY b) < 3",
            "SELECT a, b, row_number() OVER w, sum(a) OVER w FROM t WINDOW w AS (PARTITION BY b ORDER BY a)",
            "SELECT a, k FROM t QUALIFY row_number() OVER (PARTITION BY k ORDER BY a DESC) = 1",
            "SELECT a, row_number() OVER (ORDER BY a) AS rn, rn * 10 FROM t QUALIFY rn % 2 = 0 ORDER BY rn DESC",
            "SELECT a, d, count(*) OVER (ORDER BY d RANGE BETWEEN INTERVAL 1 YEAR PRECEDING AND CURRENT ROW),"
                    + " count(*) OVER (ORDER BY d RANGE BETWEEN 3 PRECEDING AND 60 FOLLOWING) FROM t",
            "SELECT a, e, sum(a) OVER (ORDER BY e RANGE BETWEEN 1 PRECEDING AND CURRENT ROW),"
                    + " sum(a) OVER (ORDER BY c DESC RANGE BETWEEN 1.5 PRECEDING AND 0.5 FOLLOWING),"
                    + " sum(a) OVER (ORDER BY e RANGE BETWEEN 200 PRECEDING AND '1' FOLLOWING) FROM t",
            "SELECT ts, max(m) OVER (ORDER BY ts RANGE BETWEEN INTERVAL 1 DAY PRECEDING AND INTERVAL 30 MINUTE"
                    + " FOLLOWING), count(*) OVER (ORDER BY ts DESC RANGE BETWEEN INTERVAL 3 MONTH PRECEDING AND"
                    + " CURRENT ROW) FROM w",
            "SELECT a, sum(a) OVER (ORDER BY k GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW EXCLUDE GROUP),"
                    + " sum(a) OVER (ORDER BY k ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE TIES),"
                    + " sum(a) OVER (ORDER BY a ROWS BETWEEN 1.5 PRECEDING AND CURRENT ROW EXCLUDE CURRENT ROW) FROM t",
            "SELECT a, sum(a) FILTER (WHERE a > 2) OVER (ORDER BY a), count(*) FILTER (WHERE b = 'x') OVER (),"
                    + " string_agg(b, '-' ORDER BY a DESC) OVER (PARTITION BY k), sum(DISTINCT k) OVER (),"
                    + " list(a ORDER BY a) OVER (PARTITION BY b) FROM t",
            "SELECT a, first_value(c IGNORE NULLS) OVER (ORDER BY a DESC), lag(c IGNORE NULLS) OVER (ORDER BY a),"
                    + " lag(a, 1.5) OVER (ORDER BY a), ntile(2.7) OVER (ORDER BY a), lag(a, '1') OVER (ORDER BY a),"
                    + " lag(e, 1, 0.5) OVER (ORDER BY a) FROM t",
            "SELECT h, g, sum(h) OVER (ORDER BY g), avg(m) OVER (ORDER BY g ROWS 1 PRECEDING), sum(m) OVER (),"
                    + " sum(s) OVER (ORDER BY ts RANGE BETWEEN INTERVAL 2 DAY PRECEDING AND CURRENT ROW) FROM w",
            // As in DuckDB, first and last over a window are first_value and last_value; arbitrary stays an aggregate.
            "SELECT a, first(c IGNORE NULLS) OVER (ORDER BY a), last(c) OVER (ORDER BY a ROWS BETWEEN CURRENT ROW AND"
                    + " 1 FOLLOWING), arbitrary(c) FILTER (WHERE a > 1) OVER (ORDER BY a) FROM t",
            "SELECT DISTINCT k, count(*) OVER (PARTITION BY k) FROM t ORDER BY 1",
            "SELECT a FROM t ORDER BY row_number() OVER (ORDER BY c DESC), a",
            "SELECT a, b AS x, row_number() OVER (PARTITION BY x ORDER BY a), (SELECT max(n) FROM u), unnest([1, 2])"
                    + " FROM t",
            "SELECT b, max(a) OVER (PARTITION BY b) FROM t WHERE a > 1 ORDER BY 1 LIMIT 3",
            // Over a window, DuckDB takes a literal in the order of an aggregate, which it rejects elsewhere.
            "SELECT a, count(a ORDER BY 'x') OVER (), max(a ORDER BY NULL) OVER (PARTITION BY k),"
                    + " sum(a ORDER BY -1.5) OVER () FROM t",
            "SELECT a, first_value(upper(b) IGNORE NULLS) OVER (ORDER BY a), lag(c, 1, abs(c) IGNORE NULLS)"
                    + " OVER (ORDER BY a) FROM t",
            "SELECT a, k, count(*) OVER (PARTITION BY ((a % 2)), k), count(*) OVER w FROM t"
                    + " WINDOW w AS (PARTITION BY (k, a > 2))");

    /** Texts that must not bind, with the start of FyrQL's message; DuckDB rejects them as well. */
    static final List<Map.Entry<String, String>> ERRORS = List.of(
            Map.entry("SELECT x FROM t", "Referenced column \"x\" not found in FROM clause!"),
            Map.entry("SELECT a FROM t JOIN u ON t.a = u.a", "Ambiguous reference to column name \"a\""),
            Map.entry("SELECT a FROM t LIMIT a", "Referenced column \"a\" was not found because the FROM clause"),
            Map.entry("SELECT b, count(*) FROM t GROUP BY a", "column \"b\" must appear in the GROUP BY clause"),
            Map.entry("SELECT a FROM t HAVING a > 1", "column \"a\" must appear in the GROUP BY clause"),
            Map.entry("SELECT a FROM t GROUP BY a ORDER BY b", "column \"b\" must appear in the GROUP BY clause"),
            Map.entry("SELECT * FROM t GROUP BY a", "column \"b\" must appear in the GROUP BY clause"),
            Map.entry("SELECT a FROM t WHERE count(*) > 1", "WHERE clause cannot contain aggregates!"),
            Map.entry("SELECT sum(count(*)) FROM t", "aggregate function calls cannot be nested"),
            Map.entry("SELECT a + count(*), b FROM t GROUP BY ALL", "Cannot mix aggregates with non-aggregated"),
            Map.entry("SELECT *", "SELECT * expression without FROM clause!"),
            Map.entry("SELECT * EXCLUDE (z) FROM t", "Column \"z\" in EXCLUDE list not found in FROM clause"),
            Map.entry("SELECT * REPLACE (1 AS z) FROM t", "Column \"z\" in REPLACE list not found in FROM clause"),
            Map.entry("SELECT t.a FROM t AS x", "Referenced table \"t\" not found! Candidate tables: \"x\""),
            Map.entry("SELECT * FROM t, t", "Ambiguous reference to table \"t\""),
            Map.entry("SELECT a FROM t ORDER BY 3", "ORDER term out of range - should be between 1 and 1"),
            Map.entry("SELECT a FROM t LIMIT -1", "LIMIT/OFFSET cannot be negative"),
            Map.entry("SELECT 1, 2 UNION ALL SELECT 3", "Set operations can only apply to expressions with the same"),
            Map.entry("SELECT a FROM t UNION SELECT a FROM u ORDER BY a + 1", "Could not ORDER BY column"),
            Map.entry("SELECT * FROM (VALUES (1, 2)) t(x, y, z)", "table \"t\" has 2 columns available but 3"),
            Map.entry("WITH x AS (SELECT 1 AS a), x AS (SELECT 2 AS a) SELECT * FROM x", "Duplicate CTE name \"x\""),
            Map.entry("WITH y AS (SELECT a + 1 AS b FROM x), x AS (SELECT 1 AS a) SELECT * FROM y",
                    "Table with name x does not exist"),
            Map.entry("SELECT * FROM t NATURAL JOIN (SELECT 1 AS z)", "No columns found to join on in NATURAL JOIN"),
            Map.entry("SELECT * FROM t JOIN u USING (z)", "Column \"z\" does not exist on left side of join!"),
            Map.entry("SELECT * FROM t JOIN u USING (b)", "Column \"b\" does not exist on right side of join!"),
            Map.entry("SELECT string_agg(b, b) FROM t", "Separator argument to StringAgg must be a constant"),
            Map.entry("SELECT string_agg(b, 1) FROM t", "No function matches string_agg(VARCHAR, INTEGER)"),
            Map.entry("SELECT 0.5::DOUBLE < true", "Cannot compare values of type DOUBLE and type BOOLEAN"),
            Map.entry("SELECT a FROM t ORDER BY 'x'", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT a FROM t ORDER BY 1.5", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT a FROM t ORDER BY NULL", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT a FROM t ORDER BY -1.5", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT list(a ORDER BY -1.5) FROM t", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT first(a ORDER BY -(-(-1.5))) FROM t", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT greatest(true, 1.5)", "Cannot mix values of type BOOLEAN and DECIMAL(2,1) in greatest"),
            Map.entry("SELECT log(1, 7)", "divison by zero in based logarithm"),
            Map.entry("SELECT e % CAST(-1 AS TINYINT) FROM (SELECT CAST(-128 AS TINYINT) AS e)",
                    "Overflow in division of -128 / -1"),
            Map.entry("SELECT * EXCLUDE (a) FROM (SELECT 1 AS a)", "SELECT list is empty after resolving * expressions!"),
            Map.entry("SELECT a FROM t ORDER BY -1", "ORDER term out of range - should be between 1 and 1"),
            Map.entry("SELECT 1 AS x FROM t GROUP BY -1", "GROUP BY term out of range - should be between 1 and 1"),
            // DuckDB folds the minus signs into the position.
            Map.entry("SELECT a FROM t ORDER BY -(-(-1))", "ORDER term out of range - should be between 1 and 1"),
            Map.entry("SELECT 1 AS x FROM t GROUP BY -(-2)", "GROUP BY term out of range - should be between 1 and 1"),
            Map.entry("SELECT coalesce('2024-01-01', '2024-01-02', d) FROM t",
                    "Cannot mix values of type VARCHAR and DATE in COALESCE operator"),
            Map.entry("SELECT '2024-01-02' BETWEEN '2024-01-01' AND d FROM t",
                    "Cannot mix values of type VARCHAR and DATE in BETWEEN clause"),
            Map.entry("SELECT CASE WHEN a > 0 THEN true WHEN a > 1 THEN a ELSE m END FROM t, w",
                    "Cannot mix values of type DECIMAL(5,2) and BOOLEAN in CASE expression"),
            Map.entry("SELECT string_agg(b ORDER BY 'x') FROM t", "ORDER BY non-integer literal has no effect"),
            Map.entry("SELECT * FROM (VALUES ('2024-01-02'), (DATE '2024-01-01')) v(a)",
                    "Cannot combine types VARCHAR and DATE"),
            Map.entry("SELECT * FROM (VALUES ('2'), (1)) v(a)", "Cannot combine types VARCHAR and INTEGER_LITERAL"),
            Map.entry("SELECT iff(true, 1, 2)", "Function with name iff does not exist"),
            Map.entry("SELECT round(m, s) FROM w", "ROUND(DECIMAL, INTEGER) with non-constant precision is not supported"),
            Map.entry("SELECT coalesce(1.5::DOUBLE, true)",
                    "Cannot mix values of type DOUBLE and BOOLEAN in COALESCE operator"),
            Map.entry("SELECT strftime(d, b) FROM t", "strftime format must be a constant"),
            Map.entry("SELECT strftime(d, '%Q') FROM t", "Failed to parse format specifier %Q"),
            Map.entry("SELECT upper(1)", "No function matches upper(INTEGER)"),
            Map.entry("SELECT nosuch(1)", "Function with name nosuch does not exist"),
            Map.entry("SELECT b > 1 FROM t", "Cannot compare values of type VARCHAR and type INTEGER"),
            Map.entry("SELECT b > a FROM t", "Cannot compare values of type VARCHAR and type INTEGER"),
            Map.entry("SELECT a + b FROM t", "No operator + for INTEGER and VARCHAR"),
            Map.entry("SELECT a + '1' FROM t", "No operator + for INTEGER and VARCHAR"),
            Map.entry("SELECT a LIKE 'x' FROM t", "LIKE needs VARCHAR operands, not INTEGER and VARCHAR"),
            Map.entry("SELECT coalesce(b, 1) FROM t", "Cannot mix values of type VARCHAR and INTEGER"),
            Map.entry("SELECT b BETWEEN 1 AND 2 FROM t", "Cannot mix values of type VARCHAR and INTEGER"),
            Map.entry("SELECT a = 'x' FROM t", "Could not convert string 'x' to an integer"),
            Map.entry("SELECT CAST(d AS INTEGER) FROM t", "Cannot cast DATE to INTEGER"),
            Map.entry("SELECT a FROM nosuch", "Table with name nosuch does not exist"),
            Map.entry("SELECT sum(b) FROM t", "No function matches sum(VARCHAR)"),
            Map.entry("SELECT year(b) FROM t", "No function matches year(VARCHAR)"),
            Map.entry("VALUES ('a'), (1)", "Cannot combine types VARCHAR and INTEGER_LITERAL"),
            Map.entry("VALUES (1), ('a')", "Could not convert string 'a' to INTEGER"),
            Map.entry("SELECT a FROM t UNION ALL SELECT d FROM t", "Cannot combine types INTEGER and DATE"),
            Map.entry("SELECT s.nope FROM n", "Could not find key \"nope\" in struct"),
            Map.entry("SELECT id.a FROM n", "Cannot extract field 'a' from expression \"id\""),
            Map.entry("SELECT l.a FROM n", "Cannot extract field 'a' from expression \"l\""),
            Map.entry("SELECT s.a.b FROM n", "Cannot extract field 'b' from expression \"struct_extract(s, 'a')\""),
            Map.entry("SELECT nope.a FROM n", "Referenced table \"nope\" not found!"),
            Map.entry("SELECT s[1] FROM n", "struct_extract with an integer key can only be used on unnamed structs"),
            Map.entry("SELECT s[b] FROM (SELECT {'a': 1} AS s, 'a' AS b)",
                    "Key name for struct_extract needs to be a constant string"),
            Map.entry("SELECT [true, 1.5]", "Cannot mix values of type BOOLEAN and DECIMAL(2,1) in list_value"),
            Map.entry("SELECT [1, 'a']", "Could not convert string 'a' to an integer"),
            Map.entry("SELECT ['x', 'y', 1.5]", "Could not convert string 'x' to a DECIMAL"),
            Map.entry("SELECT MAP {'a': 1, 2: 3}", "Could not convert string 'a' to an integer"),
            Map.entry("SELECT {'a': 1, 'A': 2}", "Duplicate struct entry name \"A\""),
            Map.entry("SELECT struct_pack(1)", "Need named argument for struct pack, e.g. STRUCT_PACK(a := b)"),
            Map.entry("SELECT MAP {'a': 1, 'a': 2}", "Map keys must be unique."),
            Map.entry("SELECT MAP {NULL: 1}", "Map keys can not be NULL."),
            Map.entry("SELECT map(['a'], [1, 2])", "The map key list does not align with the map value list."),
            Map.entry("SELECT list_sort([1], 'UP')", "Unrecognized sort order \"UP\""),
            Map.entry("SELECT list_sort(l, b) FROM (SELECT [1] AS l, 'ASC' AS b)", "Sorting order must be a constant"),
            Map.entry("SELECT CAST({'a': 1} AS STRUCT(b INTEGER))",
                    "Cannot cast STRUCT(a INTEGER) to STRUCT(b INTEGER)"),
            Map.entry("SELECT CAST(x AS STRUCT(a INTEGER, A VARCHAR)) FROM (SELECT 1 AS x)",
                    "Duplicate STRUCT type argument name \"A\""),
            Map.entry("SELECT list_contains(['a'], 1)", "No function matches list_contains(VARCHAR[], INTEGER)"),
            Map.entry("SELECT CASE WHEN true THEN [1] ELSE ['a'] END",
                    "Cannot mix values of type VARCHAR[] and INTEGER[] in CASE expression"),
            Map.entry("SELECT l[1.5] FROM n", "No function matches list_extract(INTEGER[], DECIMAL(2,1))"),
            Map.entry("SELECT array_length(l, 2) FROM n",
                    "array_length for lists with dimensions other than 1 not implemented"),
            Map.entry("SELECT len(s) FROM n", "No function matches len(STRUCT("),
            Map.entry("SELECT unnest(unnest(ll)) FROM n", "Nested UNNEST calls are not supported"),
            Map.entry("SELECT unnest(1)", "UNNEST() can only be applied to lists, structs and NULL, not INTEGER"),
            Map.entry("SELECT unnest(m) FROM n",
                    "UNNEST() can only be applied to lists, structs and NULL, not MAP(VARCHAR, INTEGER)"),
            Map.entry("SELECT unnest(s) + 1 FROM n", "UNNEST() on a struct column can only be applied as the root"),
            Map.entry("SELECT id FROM n WHERE unnest(l) > 1", "UNNEST not supported here"),
            Map.entry("SELECT count(*) FROM n GROUP BY unnest(l)", "UNNEST not supported here"),
            Map.entry("SELECT sum(unnest(l)) FROM n", "UNNEST not supported here"),
            Map.entry("SELECT id FROM n GROUP BY id HAVING unnest([1]) = 1", "UNNEST not supported here"),
            Map.entry("SELECT 1 FROM n JOIN n m ON unnest(n.l) = 1", "UNNEST not supported here"),
            Map.entry("SELECT * FROM (VALUES (unnest([1, 2])))", "UNNEST not supported here"),
            Map.entry("SELECT id, unnest(l) FROM n GROUP BY id", "column \"l\" must appear in the GROUP BY clause"),
            Map.entry("SELECT * FROM unnest(NULL)", "UNNEST requires a single list as input"),
            Map.entry("SELECT * FROM unnest(1)", "UNNEST requires a single list as input"),
            Map.entry("SELECT * FROM n, unnest(n.s)", "UNNEST requires a single list as input"),
            Map.entry("SELECT * FROM unnest(n.l) AS u(x), n", "Referenced table \"n\" not found!"),
            Map.entry("SELECT * FROM n RIGHT JOIN unnest(n.l) AS u(x) ON true",
                    "The combining JOIN type must be INNER or LEFT for a LATERAL reference"),
            Map.entry("SELECT a FROM t WHERE a = (SELECT a FROM u)", "More than one row returned by a subquery"),
            Map.entry("SELECT (SELECT a, f FROM u)", "Subquery returns 2 columns - expected 1"),
            Map.entry("SELECT a FROM t WHERE a IN (SELECT a, f FROM u)", "Subquery returns 2 columns - expected 1"),
            Map.entry("SELECT a FROM t WHERE b IN (SELECT a FROM u)",
                    "Cannot compare values of type VARCHAR and INTEGER in IN/ANY/ALL clause"),
            Map.entry("SELECT a FROM t WHERE 1 NOT IN (SELECT f FROM u)",
                    "Cannot compare values of type INTEGER_LITERAL and VARCHAR in IN/ANY/ALL clause"),
            Map.entry("SELECT a FROM t WHERE (a > 1) IN (SELECT c FROM t)",
                    "Cannot compare values of type BOOLEAN and DOUBLE in IN/ANY/ALL clause"),
            Map.entry("SELECT (-170141183460469231731687303715884105728)::HUGEINT % (s - 8) FROM w",
                    "Overflow in division of -170141183460469231731687303715884105728 / -1"),
            Map.entry("SELECT a, (SELECT f FROM u WHERE u.a = t.a) FROM t", "More than one row returned by a subquery"),
            Map.entry("SELECT a FROM t WHERE row_number() OVER () > 1",
                    "WHERE clause cannot contain window functions!"),
            Map.entry("SELECT t.a FROM t JOIN u ON row_number() OVER () = 1",
                    "WHERE clause cannot contain window functions!"),
            Map.entry("SELECT a FROM t GROUP BY row_number() OVER ()",
                    "GROUP BY clause cannot contain window functions!"),
            Map.entry("SELECT sum(row_number() OVER ()) FROM t",
                    "aggregate function calls cannot contain window function calls"),
            Map.entry("SELECT sum(a) OVER (ROWS BETWEEN CURRENT ROW AND 1 PRECEDING) FROM t",
                    "frame starting from current row cannot have preceding rows"),
            Map.entry("SELECT sum(a) OVER (ROWS 1 FOLLOWING) FROM t",
                    "frame starting from following row cannot end with current row"),
            Map.entry("SELECT sum(a) OVER (ROWS BETWEEN UNBOUNDED FOLLOWING AND CURRENT ROW) FROM t",
                    "frame start cannot be UNBOUNDED FOLLOWING"),
            Map.entry("SELECT a FROM t WINDOW w AS (ORDER BY a), w AS (ORDER BY b)",
                    "window \"w\" is already defined"),
            Map.entry("SELECT a FROM t GROUP BY a HAVING row_number() OVER () > 1",
                    "HAVING clause cannot contain window functions!"),
            Map.entry("SELECT row_number() OVER (ORDER BY row_number() OVER ()) FROM t",
                    "window functions are not allowed in window definitions"),
            Map.entry("SELECT lag(row_number() OVER ()) OVER () FROM t", "window function calls cannot be nested"),
            Map.entry("SELECT a FROM t QUALIFY a > 1",
                    "at least one window function must appear in the SELECT column or QUALIFY clause"),
            Map.entry("SELECT k, sum(a) OVER () FROM t GROUP BY ALL", "Cannot group on a window clause"),
            Map.entry("SELECT k, sum(a) OVER () FROM t GROUP BY k", "column \"a\" must appear in the GROUP BY clause"),
            Map.entry("SELECT ntile() OVER () FROM t", "NTILE needs 1 parameter, got 0"),
            Map.entry("SELECT upper(b) OVER () FROM t", "upper is not an aggregate function"),
            Map.entry("SELECT foo(a) OVER () FROM t", "Aggregate Function with name foo does not exist!"),
            Map.entry("SELECT row_number() FILTER (WHERE a > 1) OVER () FROM t",
                    "FILTER is not implemented for non-aggregate window functions!"),
            Map.entry("SELECT lag(DISTINCT a) OVER () FROM t",
                    "DISTINCT is not implemented for non-aggregate window functions!"),
            Map.entry("SELECT sum(a IGNORE NULLS) OVER () FROM t",
                    "RESPECT/IGNORE NULLS is not supported for windowed aggregates"),
            Map.entry("SELECT a, row_number() OVER w FROM t WINDOW v AS (ORDER BY a)", "window \"w\" does not exist"),
            Map.entry("SELECT sum(a) OVER (ORDER BY a, k RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t",
                    "RANGE frames must have only one ORDER BY expression"),
            Map.entry("SELECT sum(a) OVER (ORDER BY b RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) FROM t",
                    "Cannot use an offset of type INTEGER in a RANGE frame over an order key of type VARCHAR"),
            Map.entry("SELECT sum(a) OVER (ORDER BY a RANGE BETWEEN INTERVAL 1 DAY PRECEDING AND CURRENT ROW) FROM t",
                    "Cannot use an INTERVAL as the offset of a RANGE frame over an order key of type INTEGER"),
            Map.entry("SELECT sum(a) OVER (ORDER BY e RANGE BETWEEN CURRENT ROW AND 124 FOLLOWING) FROM t",
                    "Overflow in addition of TINYINT (4 + 124)"),
            Map.entry("SELECT sum(a) OVER (ORDER BY a RANGE BETWEEN -1 PRECEDING AND CURRENT ROW) FROM t",
                    "Invalid RANGE PRECEDING value"),
            Map.entry("SELECT first(c) FILTER (WHERE a > 1) OVER (ORDER BY a) FROM t",
                    "FILTER is not implemented for non-aggregate window functions!"),
            Map.entry("SELECT ntile(0) OVER () FROM t", "Argument for ntile must be greater than zero"),
            Map.entry("SELECT lag(a, 1, 'x') OVER (ORDER BY a) FROM t", "Could not convert string 'x'"));

    /** Texts DuckDB runs and FyrQL rejects on purpose, with the start of FyrQL's message. */
    static final List<Map.Entry<String, String>> DEVIATIONS = List.of(
            Map.entry("SELECT DISTINCT a FROM t ORDER BY b",
                    "For SELECT DISTINCT, ORDER BY expressions must appear in select list"),
            Map.entry("SELECT sqrt('4')", "No function matches sqrt(VARCHAR)"),
            Map.entry("SELECT avg(d) FROM t", "No function matches avg(DATE)"),
            // A string does not convert to a nested type, a slice is no index, and a list has no fixed size.
            Map.entry("SELECT [1, 2] = '[1, 2]'", "Cannot cast VARCHAR to INTEGER[]"),
            Map.entry("SELECT '[1, 2]'::INTEGER[]", "Cannot cast VARCHAR to INTEGER[]"),
            Map.entry("SELECT ([1, 2, 3])[2:3]", "Not supported: slices"),
            Map.entry("SELECT CAST(x AS INTEGER[2]) FROM (SELECT [1, 2] AS x)",
                    "Arrays of a fixed size are not supported"),
            Map.entry("SELECT row(1, 'a')", "Function with name row does not exist"),
            Map.entry("SELECT CAST(MAP {'a': 1} AS MAP(VARCHAR, BIGINT))", "Syntax error"),
            // DuckDB unnests lists side by side, keeps the rows without elements in a LEFT JOIN, and unnests a
            // struct that an aggregate gives.
            Map.entry("SELECT unnest(l), unnest(ll) FROM n",
                    "Not supported: UNNEST of more than one list in a select list"),
            Map.entry("SELECT * FROM n LEFT JOIN unnest(n.l) AS u(x) ON true",
                    "Not supported: a LEFT JOIN with a lateral UNNEST"),
            Map.entry("SELECT unnest(any_value(s)) FROM n",
                    "UNNEST() on a struct column can only be applied as the root element"),
            Map.entry("SELECT unnest(l, recursive := true) FROM n", "Not supported: named arguments"),
            // Subqueries go in WHERE, HAVING and the select list.
            Map.entry("SELECT a FROM t ORDER BY (SELECT max(a) FROM u) - a", "Not supported: a subquery here"),
            Map.entry("SELECT t.a FROM t JOIN u ON t.a = u.a AND u.a IN (SELECT 1)", "Not supported: a subquery here"),
            Map.entry("SELECT a IN (SELECT 1), count(*) FROM t GROUP BY 1", "Not supported: a subquery here"),
            Map.entry("SELECT sum((SELECT 1)) FROM t", "Not supported: a subquery here"),
            Map.entry("SELECT a FROM t WHERE a > ANY (SELECT a FROM u)", "Not supported: ANY and ALL"),
            Map.entry("SELECT a FROM t WHERE (a, b) IN (SELECT a, f FROM u)",
                    "Not supported: IN with more than one column"),
            Map.entry("SELECT (SELECT t.b FROM u LIMIT 1) FROM t",
                    "Not supported: a reference to the outer query outside the WHERE clause of a subquery"),
            Map.entry("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE EXISTS (SELECT 1 FROM u x WHERE x.a = t.a))",
                    "Not supported: a reference to a query more than one level out of a subquery"),
            Map.entry("SELECT a, (SELECT count(*) FROM u WHERE u.a < t.a) FROM t",
                    "Not supported: a correlated condition of an aggregating subquery that is not an equality"),
            Map.entry("SELECT a, (SELECT count(*) FROM u WHERE u.a = t.a HAVING count(*) > 1) FROM t",
                    "Not supported: HAVING in a correlated subquery without GROUP BY"),
            Map.entry("SELECT a, (SELECT f FROM u WHERE u.a = t.a ORDER BY f LIMIT 1) FROM t",
                    "Not supported: LIMIT or OFFSET in a correlated subquery"),
            Map.entry("SELECT a FROM t WHERE EXISTS (SELECT max(n) FROM u WHERE u.a = t.a)",
                    "Not supported: EXISTS or IN with a correlated subquery that aggregates without GROUP BY"),
            Map.entry("SELECT a, (SELECT DISTINCT f FROM u WHERE u.a = t.a AND f <> 's') FROM t",
                    "Not supported: DISTINCT in a correlated scalar subquery"),
            Map.entry("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a OR u.n > (SELECT 1))",
                    "Not supported: a subquery in a condition that references the outer query"),
            Map.entry("SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE t.a IN (SELECT a FROM u))",
                    "Not supported: IN with a subquery whose value references the outer query"),
            // The filter of an aggregate goes only with a window; JSqlParser reads a window that names another
            // and adds to it as a syntax error.
            Map.entry("SELECT sum(a) FILTER (WHERE a > 1) FROM t", "Not supported: FILTER without OVER"),
            Map.entry("SELECT sum(a) OVER (w ORDER BY a) FROM t WINDOW w AS (PARTITION BY b)", "Syntax error"),
            // JSqlParser reads a partition in parentheses as the list of all; ((a % 2)), k it reads.
            Map.entry("SELECT count(*) OVER (PARTITION BY (a % 2), k) FROM t", "Syntax error"),
            // DuckDB also takes an order in the arguments of a window function, frame offsets that are no
            // constants or NULL, and window functions in a correlated subquery, whose join sits above them.
            Map.entry("SELECT lag(a ORDER BY a) OVER () FROM t", "Not supported: ORDER BY in the arguments of lag"),
            Map.entry("SELECT sum(a) OVER (ORDER BY a ROWS BETWEEN a PRECEDING AND CURRENT ROW) FROM t",
                    "Not supported: a frame offset that is not a constant"),
            Map.entry("SELECT sum(a) OVER (ORDER BY a ROWS BETWEEN NULL PRECEDING AND CURRENT ROW) FROM t",
                    "Not supported: NULL as the offset of a frame"),
            Map.entry("SELECT a FROM t WHERE EXISTS (SELECT rank() OVER (ORDER BY f) FROM u WHERE u.a = t.a)",
                    "Not supported: a window function in a correlated subquery"),
            Map.entry("SELECT a, row_number() OVER (ORDER BY (SELECT 1)) FROM t", "Not supported: a subquery here"));

    private static FyrQL engine;
    private static DuckDbReference duck;
    private static Binder binder;

    @BeforeAll
    static void setUp() {
        engine = FyrQL.builder().register(CorpusTables.builder().build()).build();
        duck = new DuckDbReference();
        CorpusTables.load(engine, duck);
        binder = new Binder(engine.catalog(), FunctionRegistry.standard());
    }

    @AfterAll
    static void tearDown() {
        duck.close();
        engine.close();
    }

    private static PlanNode plan(String sql) {
        return binder.bind(SqlParser.parse(sql));
    }

    @Test
    void corpusAgreesWithDuckDb() {
        List<String> failures = new ArrayList<>();
        List<String> queries = new ArrayList<>(QUERIES);
        queries.addAll(ParserMeaningTest.QUERIES);
        for (String sql : queries) {
            try {
                PlanNode plan = plan(sql);
                Schema schema = plan.schema();
                Columns columns = duckColumns(sql);
                List<String> types = schema.types().stream().map(DataType::sqlName).toList();
                if (!columns.types().equals(types)) {
                    failures.add(sql + "\n  types: DuckDB " + columns.types() + ", FyrQL " + types);
                    continue;
                }
                List<String> names = schema.fields().stream().map(Schema.Field::name).toList();
                // The texts of the parser test fold constants in their names, as in -(-3), which FyrQL keeps.
                for (int i = 0; i < names.size() && QUERIES.contains(sql); i++) {
                    // DuckDB names other expressions by its own rendering of them, which FyrQL does not copy.
                    String name = columns.names().get(i);
                    if (name.indexOf('(') < 0 && name.indexOf('\'') < 0 && name.indexOf('"') < 0
                            && !name.equals(names.get(i))) {
                        failures.add(sql + "\n  names: DuckDB " + columns.names() + ", FyrQL " + names);
                        break;
                    }
                }
                ResultComparison.Outcome outcome = ResultComparison.compare(schema, duck.query(sql, schema),
                        engine.rows(plan), Plans.ordered(plan));
                if (!outcome.same()) {
                    failures.add(sql + "\n" + outcome.report() + "\n" + Plans.print(plan));
                }
            } catch (RuntimeException | SQLException e) {
                failures.add(sql + "\n  " + e);
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + queries.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    /**
     * Runs the texts over the table {@code n} again, with {@code n} a
     * Parquet file that DuckDB writes, in a Parquet source named
     * {@code memory} next to the other tables in a memory source.
     */
    @Test
    void textsOverNestedColumnsAgreeWithDuckDbWhenTheyAreReadFromParquet(@TempDir Path dir) {
        Path file = dir.resolve("n.parquet");
        duck.execute("COPY n TO '" + file.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT parquet)");
        Pattern overN = Pattern.compile("\\b(FROM|JOIN) n\\b");
        List<String> queries = new ArrayList<>(QUERIES);
        queries.addAll(ParserMeaningTest.QUERIES);
        queries.removeIf(sql -> !overN.matcher(sql).find());
        assertTrue(queries.size() > 50, queries::toString);
        List<String> failures = new ArrayList<>();
        try (FyrQL parquet = FyrQL.builder()
                .register(ParquetConnector.builder(CorpusTables.SOURCE).path("n", file).batchSize(2).build())
                .register(CorpusTables.builder("other", false).build()).build()) {
            TableRef n = TableRef.of(CorpusTables.SOURCE, "n");
            assertEquals(engine.scan(n).schema().types(), parquet.scan(n).schema().types());
            for (String sql : queries) {
                try {
                    PlanNode plan = parquet.plan(sql);
                    Schema schema = plan.schema();
                    ResultComparison.Outcome outcome = ResultComparison.compare(schema, duck.query(sql, schema),
                            parquet.rows(plan), Plans.ordered(plan));
                    if (!outcome.same()) {
                        failures.add(sql + "\n" + outcome.report() + "\n" + parquet.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  " + e);
                }
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + queries.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    @Test
    void errorsAgreeWithDuckDb() {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> error : ERRORS) {
            String sql = error.getKey();
            String message = fyrqlError(sql);
            if (message == null || !message.startsWith(error.getValue())) {
                failures.add(sql + "\n  FyrQL: " + message + "\n  expected: " + error.getValue());
            }
            if (duckError(sql) == null) {
                failures.add(sql + "\n  DuckDB runs it");
            }
        }
        for (Map.Entry<String, String> deviation : DEVIATIONS) {
            String sql = deviation.getKey();
            String message = fyrqlError(sql);
            if (message == null || !message.startsWith(deviation.getValue())) {
                failures.add(sql + "\n  FyrQL: " + message + "\n  expected: " + deviation.getValue());
            }
            String duckError = duckError(sql);
            if (duckError != null) {
                failures.add(sql + "\n  DuckDB rejects it too: " + duckError);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n\n", failures));
    }

    /** Returns FyrQL's message for a text that does not bind or run, or {@code null} if it runs. */
    private static String fyrqlError(String sql) {
        try {
            engine.rows(plan(sql));
            return null;
        } catch (SqlException e) {
            return e.reason();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** Returns DuckDB's message for a text it rejects, or {@code null} if it runs it. */
    private static String duckError(String sql) {
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                // Read every row, so that errors during the execution surface.
            }
            return null;
        } catch (SQLException e) {
            return e.getMessage();
        }
    }

    /** The names and types of the columns of a result. */
    private record Columns(List<String> names, List<String> types) {
    }

    /** The columns DuckDB gives the result of a query. */
    private static Columns duckColumns(String sql) throws SQLException {
        try (Statement s = duck.connection().createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                names.add(md.getColumnLabel(i));
                types.add(md.getColumnTypeName(i));
            }
            return new Columns(names, types);
        }
    }
}
