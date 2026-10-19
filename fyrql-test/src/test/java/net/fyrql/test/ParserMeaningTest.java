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

import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks with DuckDB that the syntax tree keeps the meaning of the text:
 * each query, and the canonical text the {@link SqlPrinter} writes for its
 * tree, give the same rows in DuckDB. This covers what the parser decides
 * itself: the precedence of set operations, the grouping of joins around
 * commas, and the special forms it turns into function calls.
 */
class ParserMeaningTest {

    static final List<String> QUERIES = List.of(
            "SELECT 1 UNION SELECT 2 INTERSECT SELECT 3",
            "SELECT 1 UNION ALL SELECT 2 EXCEPT SELECT 2",
            "SELECT 1 EXCEPT SELECT 1 UNION SELECT 1",
            "SELECT 1 INTERSECT ALL SELECT 1 UNION ALL SELECT 1 INTERSECT SELECT 2",
            "(SELECT 1 AS x ORDER BY 1 LIMIT 1) UNION ALL (SELECT 2) ORDER BY 1",
            "SELECT * FROM (VALUES (1), (2)) a(x), (VALUES (1)) b(y) RIGHT JOIN (VALUES (1), (3)) c(z) ON b.y = c.z",
            "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) l(id, v) NATURAL JOIN (VALUES (1, 'c')) r(id, w)",
            "SELECT * FROM (VALUES (1, 'a')) l(id, v) FULL JOIN (VALUES (2, 'c')) r(id, w) USING (id)",
            "SELECT * FROM (VALUES (1)) a(x) LEFT JOIN ((VALUES (1)) b(y) JOIN (VALUES (2)) c(z) ON y < z) ON x = y",
            "SELECT x IS TRUE, x IS NOT TRUE, x IS FALSE, x IS NOT FALSE FROM (VALUES (true), (false), (NULL)) v(x)",
            "SELECT trim('xaxx', 'x'), trim(BOTH 'x' FROM 'xax'), trim(LEADING 'x' FROM 'xax'),"
                    + " trim(TRAILING 'x' FROM 'xax'), trim('  a  '), position('b' IN 'abc'),"
                    + " substring('abcdef' FROM 2 FOR 3), substring('abcdef' FROM 3)",
            "SELECT extract(year FROM DATE '2024-05-06'), EXTRACT(dow FROM DATE '2024-05-06'),"
                    + " extract(epoch FROM TIMESTAMP '2024-05-06 10:00:00')",
            "SELECT 7 // 2, -7 // 2, 7.5 // 2, 1 == 1, 'a//b', '-- x', 2 * 3 // 4 % 3 -- 5 // 6\n + 1",
            "SELECT div, div // 2 AS \"DIV\" FROM (SELECT 7 AS div)",
            "SELECT -5, - 2147483648, -(-3), +4, 2 * 3 + 4, 2 * (3 + 4), 10 - 2 - 3, 2 || 3 || 'x', 1 /* // */ + 2",
            "SELECT x BETWEEN 1 AND 2, x NOT BETWEEN 1 AND 2, x IN (1, 3), x NOT IN (1), x IS DISTINCT FROM NULL,"
                    + " x IS NOT DISTINCT FROM 1, x IS NULL, x IS NOT NULL FROM (VALUES (1), (NULL)) t(x)",
            "SELECT 'abc' LIKE 'a%', 'ABC' ILIKE 'a%', 'abc' NOT LIKE 'b%', 'abc' NOT ILIKE 'A_C'",
            "SELECT a, count(*), sum(b) FROM (VALUES (1, 2), (1, 3), (2, 4)) t(a, b) GROUP BY ALL ORDER BY ALL",
            "SELECT a % 2, count(*) FROM (VALUES (1), (2), (3)) t(a) GROUP BY 1 HAVING count(*) > 1 ORDER BY 2 DESC",
            "SELECT * EXCLUDE (b), t.* REPLACE (a + 1 AS a) FROM (VALUES (1, 2, 3)) t(a, b, c)",
            "WITH s(x) AS (SELECT 1), u AS (SELECT x + 1 AS y FROM s) SELECT * FROM s, u",
            "WITH s AS (SELECT 1 AS x) SELECT * FROM s UNION ALL SELECT x + 1 FROM s",
            "SELECT CASE WHEN x > 1 THEN 'big' WHEN x = 1 THEN 'one' ELSE 'small' END, CASE x WHEN 1 THEN 'a' END"
                    + " FROM (VALUES (0), (1), (2)) t(x)",
            "SELECT x::VARCHAR, TRY_CAST('a' AS INTEGER), CAST(x AS NUMERIC(5, 2)), CAST(x AS DOUBLE PRECISION),"
                    + " x::INT8, CAST('2024-01-02' AS DATETIME) FROM (VALUES (1)) t(x)",
            "SELECT string_agg(s, '-' ORDER BY s DESC), count(DISTINCT s), COUNT(*) FROM (VALUES ('a'), ('b'), ('a')) t(s)",
            "SELECT 'it''s', \"x y\", \"q\"\"r\" FROM (SELECT 1 AS \"x y\", 2 AS \"q\"\"r\")",
            "SELECT x FROM (VALUES (3), (1), (2), (NULL)) t(x) ORDER BY x DESC NULLS FIRST LIMIT 2 OFFSET 1",
            "SELECT DISTINCT x FROM (VALUES (1), (1), (2)) t(x)",
            "SELECT DATE '2024-02-29' + 1, TIMESTAMP '2024-01-01 10:00:00', $$a'b$$",
            "VALUES (1, 'a'), (2, 'b')",
            "SELECT * FROM (SELECT 1 AS a) WHERE a = 1 LIMIT ALL",
            "SELECT l[1], l[-1], (l)[2], [10, 20][1], ll[1][2], s.a, s['b'], ({'x': 1, 'y': [2]})['y'][1],"
                    + " struct_pack(a := 1, b), {c: 2}, MAP {'k': [1]}, map(['a'], [2]), 'abc'[2], l[b - 4], ARRAY[7]"
                    + " FROM (SELECT [1, 2] AS l, [[3, 4]] AS ll, {'a': 5, 'b': 'z'} AS s, 6 AS b)",
            "SELECT x, unnest(l) + x, unnest(s) FROM (SELECT [1, 2] AS l, {'a': 5} AS s) t,"
                    + " LATERAL unnest([3, 4]) AS u(x), unnest(t.l) ORDER BY 1, 2");

    @Test
    void printedTreesMeanWhatTheTextMeant() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (String sql : QUERIES) {
                String printed = SqlPrinter.print(SqlParser.parse(sql));
                assertEquals(rows(c, sql), rows(c, printed), sql + "\nprinted as\n" + printed);
            }
        }
    }

    /** Returns the rows as text, in the order of the result, with the number of columns first. */
    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            rows.add(columns + " columns");
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        // Without ORDER BY the order is not defined.
        if (!sql.contains("ORDER BY")) {
            rows.subList(1, rows.size()).sort(null);
        }
        return rows;
    }
}
