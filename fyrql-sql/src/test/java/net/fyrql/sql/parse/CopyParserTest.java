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

import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.CopyFrom;
import net.fyrql.sql.ast.CopyTo;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.SqlPrinter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code COPY ... TO} and {@code COPY ... FROM}, which {@link CopyParser} reads besides JSqlParser. */
class CopyParserTest {

    private static CopyTo copy(String sql) {
        return assertInstanceOf(CopyTo.class, SqlParser.parseStatement(sql));
    }

    @Test
    void aQueryInParentheses() {
        CopyTo c = copy("COPY (SELECT 1 AS a) TO 'out.csv'");
        assertEquals("SELECT 1 AS a", SqlPrinter.print(c.query()));
        assertEquals("out.csv", c.path());
        assertEquals(Map.of(), c.options());
        assertEquals(new Position(1, 25), c.position());
    }

    @Test
    void parenthesesInsideStringsNamesAndCommentsDoNotCount() {
        CopyTo c = copy("COPY (SELECT ')' AS \"(\" -- )\n FROM t /* ( */) TO 'o''k.csv' (HEADER, DELIMITER ';',"
                + " FORMAT csv, ROW_GROUP_SIZE 100, quote '\"', Escape -1, \"Null\" 'n/a', ratio 1.5e3, empty '')");
        assertEquals("SELECT ')' AS \"(\" FROM t", SqlPrinter.print(c.query()));
        assertEquals("o'k.csv", c.path());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("header", "true");
        expected.put("delimiter", ";");
        expected.put("format", "csv");
        expected.put("row_group_size", "100");
        expected.put("quote", "\"");
        expected.put("escape", "-1");
        expected.put("null", "n/a");
        expected.put("ratio", "1.5e3");
        expected.put("empty", "");
        assertEquals(List.copyOf(expected.entrySet()), List.copyOf(c.options().entrySet()));
    }

    @Test
    void aTableIsAllItsColumns() {
        CopyTo c = copy("copy t to 'x.parquet' with (format parquet);");
        assertEquals("SELECT * FROM t", SqlPrinter.print(c.query()));
        assertEquals(Map.of("format", "parquet"), c.options());
        c = copy("  /* a */ COPY \"my schema\".t TO 'x.json'");
        assertEquals("SELECT * FROM \"my schema\".t", SqlPrinter.print(c.query()));
        c = copy("COPY s.t (b, \"A\") TO 'x.csv'");
        assertEquals("SELECT b, A FROM s.t", SqlPrinter.print(c.query()));
    }

    @Test
    void copyFromTakesATableWithItsColumns() {
        CopyFrom c = assertInstanceOf(CopyFrom.class, SqlParser.parseStatement("COPY s.t FROM 'in.csv'"));
        assertEquals(List.of("s", "t"), c.name());
        assertEquals(List.of(), c.columns());
        assertEquals("in.csv", c.path());
        assertEquals(Map.of(), c.options());
        assertEquals(new Position(1, 6), c.position());
        assertEquals(new Position(1, 15), c.pathPosition());

        c = assertInstanceOf(CopyFrom.class, SqlParser.parseStatement(
                "copy t (b, \"A b\") from 'x.csv.gz' with (DELIMITER ';', HEADER false, FORMAT csv);"));
        assertEquals(List.of("t"), c.name());
        assertEquals(List.of("b", "A b"), c.columns());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("delimiter", ";");
        expected.put("header", "false");
        expected.put("format", "csv");
        assertEquals(List.copyOf(expected.entrySet()), List.copyOf(c.options().entrySet()));
        c = assertInstanceOf(CopyFrom.class, SqlParser.parseStatement("COPY t FROM 'x' (NULL 'n/a')"));
        assertEquals(Map.of("null", "n/a"), c.options());
        SqlParser.ParameterizedStatement p = SqlParser.parseStatementWithParameters("COPY t FROM 'x.csv'");
        assertInstanceOf(CopyFrom.class, p.statement());
        assertEquals(List.of(), p.parameters());
    }

    @Test
    void theQueryKeepsItsPositionsAndParameters() {
        SqlException e = assertThrows(SqlException.class,
                () -> SqlParser.parseStatement("COPY (\n  SELECT a FROM t WHERE WHERE) TO 'x.csv'"));
        assertEquals("Syntax error at or near \"WHERE\"", e.reason());
        assertEquals(new Position(2, 19), e.position());
        SqlParser.ParameterizedStatement p = SqlParser.parseStatementWithParameters(
                "COPY (SELECT * FROM t WHERE a = ? AND b = ?) TO 'x.csv'");
        assertInstanceOf(CopyTo.class, p.statement());
        assertEquals(List.of(new Position(1, 33), new Position(1, 43)),
                p.parameters().stream().map(x -> x.position()).toList());
        e = assertThrows(SqlException.class, () -> SqlParser.parseStatement("COPY (SELECT ?) TO 'x.csv'"));
        assertEquals("Not supported: parameters outside a prepared query", e.reason());
    }

    @Test
    void recognition() {
        assertTrue(CopyParser.isCopy("COPY t TO 'x'"));
        assertTrue(CopyParser.isCopy(" -- c\n/* d */copy(SELECT 1) TO 'x'"));
        assertFalse(CopyParser.isCopy("COPYING"));
        assertFalse(CopyParser.isCopy("SELECT * FROM copy"));
    }

    @Test
    void errors() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("COPY (SELECT 1) FROM 'x.csv'", "COPY ... FROM takes a table, not a query@1:17");
        cases.put("COPY t FROM x.csv",
                "Syntax error at or near \"x\": the file after FROM is a string in single quotes@1:13");
        cases.put("COPY t (a b) FROM 'x.csv'", "Syntax error at or near \"b\"@1:11");
        cases.put("COPY t () FROM 'x.csv'", "Syntax error at or near \")\"@1:9");
        cases.put("COPY t (a, 'b') FROM 'x.csv'", "Syntax error at or near \"'b'\"@1:12");
        cases.put("COPY t (a) INTO 'x.csv'", "Syntax error at or near \"INTO\"@1:12");
        cases.put("COPY t FROM 'x.csv' (HEADER", "Syntax error at end of input@1:28");
        cases.put("COPY (SELECT 1 TO 'x'",
                "Syntax error at end of input: the query after COPY has no closing parenthesis@1:22");
        cases.put("COPY t TO x.csv",
                "Syntax error at or near \"x\": the file after TO is a string in single quotes@1:11");
        cases.put("COPY t TO 'x.csv' (HEADER, header false)", "Option header is given more than once@1:28");
        cases.put("COPY t TO 'x.csv' (FORCE_QUOTE (a, b))",
                "Not supported: a list as the value of option force_quote@1:32");
        cases.put("COPY t TO 'x.csv' DELIMITER ','", "Syntax error at or near \"DELIMITER\"@1:19");
        cases.put("COPY t TO 'x.csv' WITH HEADER", "Syntax error at or near \"HEADER\"@1:24");
        cases.put("COPY t TO 'x.csv'; SELECT 1", "Only one statement is supported@1:20");
        cases.put("COPY t TO 'x.csv' (HEADER", "Syntax error at end of input@1:26");
        cases.put("COPY t TO", "Syntax error at end of input@1:10");
        cases.put("COPY t TO 'x.csv' (HEADER =)", "Syntax error at or near \"=\"@1:27");
        cases.put("COPY (SELECT 'a) TO 'x'", "Unterminated quoted string, quoted identifier or comment@1:23");
        cases.put("COPY (COPY t TO 'y') TO 'x'", "Only queries are supported, not COPY@1:7");
        cases.put("COPY (INSERT INTO t VALUES (1)) TO 'x'", "Only queries are supported, not INSERT@0:0");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException e = assertThrows(SqlException.class, () -> SqlParser.parseStatement(c.getKey()), c.getKey());
            assertEquals(c.getValue(), e.reason() + "@" + e.position().line() + ":" + e.position().column(),
                    c.getKey());
        }
        SqlException e = assertThrows(SqlException.class, () -> SqlParser.parse("\n  COPY t TO 'x.csv'"));
        assertEquals("Only queries are supported, not COPY", e.reason());
        assertEquals(new Position(2, 3), e.position());
    }
}
