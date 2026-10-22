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
import net.fyrql.sql.ast.CreateTable;
import net.fyrql.sql.ast.CreateTable.ColumnDefinition;
import net.fyrql.sql.ast.CopyTo;
import net.fyrql.sql.ast.DropTable;
import net.fyrql.sql.ast.Explain;
import net.fyrql.sql.ast.InsertInto;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.Statement;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The statements besides queries: {@code CREATE TABLE}, {@code INSERT INTO} and {@code DROP TABLE}. */
class StatementParserTest {

    private static Statement parse(String sql) {
        return SqlParser.parseStatement(sql);
    }

    @Test
    void aQueryIsAStatement() {
        assertEquals(SqlParser.parse("SELECT a FROM t WHERE a > 1"), parse("SELECT a FROM t WHERE a > 1"));
    }

    @Test
    void createTableAs() {
        CreateTable c = assertInstanceOf(CreateTable.class,
                parse("CREATE OR REPLACE TABLE mem.main.t AS SELECT * FROM x WHERE a > 1;"));
        assertEquals(List.of("mem", "main", "t"), c.name());
        assertTrue(c.orReplace());
        assertFalse(c.ifNotExists());
        assertEquals(List.of(), c.columns());
        assertEquals("SELECT * FROM x WHERE (a > 1)", SqlPrinter.print(c.query()));
        assertEquals(new Position(1, 25), c.position());

        c = assertInstanceOf(CreateTable.class, parse("create table if not exists \"My Table\" as"
                + " (select 1 as a union all select 2)"));
        assertEquals(List.of("My Table"), c.name());
        assertTrue(c.ifNotExists());
        assertInstanceOf(QueryBody.SetOperation.class, c.query().body());

        c = assertInstanceOf(CreateTable.class, parse("CREATE TABLE t AS WITH c AS (SELECT 1 AS a) SELECT * FROM c"));
        assertEquals(1, c.query().with().size());
    }

    @Test
    void createTableWithColumns() {
        CreateTable c = assertInstanceOf(CreateTable.class, parse("CREATE TABLE IF NOT EXISTS s.t (a INTEGER NOT NULL,"
                + " \"B\" VARCHAR(20) NULL, c DECIMAL(10,2), d TIMESTAMP, l INTEGER[][], s STRUCT(x INTEGER, y TEXT),"
                + " u UUID, h HUGEINT, i INT)"));
        assertEquals(List.of("s", "t"), c.name());
        assertNull(c.query());
        assertEquals(List.of(new ColumnDefinition("a", DataType.INTEGER, false),
                new ColumnDefinition("B", DataType.VARCHAR, true),
                new ColumnDefinition("c", DataType.decimal(10, 2), true),
                new ColumnDefinition("d", DataType.TIMESTAMP, true),
                new ColumnDefinition("l", DataType.listOf(DataType.listOf(DataType.INTEGER)), true),
                new ColumnDefinition("s", DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER),
                        new DataType.Struct.Field("y", DataType.VARCHAR)), true),
                new ColumnDefinition("u", DataType.UUID, true),
                new ColumnDefinition("h", DataType.HUGEINT, true),
                new ColumnDefinition("i", DataType.INTEGER, true)), c.columns());
    }

    @Test
    void insertInto() {
        InsertInto i = assertInstanceOf(InsertInto.class,
                parse("INSERT INTO s.t (a, \"B\") VALUES (1, 'x'), (2, NULL)"));
        assertEquals(List.of("s", "t"), i.name());
        assertEquals(List.of("a", "B"), i.columns());
        assertEquals(2, assertInstanceOf(QueryBody.Values.class, i.query().body()).rows().size());
        assertEquals(new Position(1, 13), i.position());

        i = assertInstanceOf(InsertInto.class, parse("insert into t select * from read_csv('x.csv')"));
        assertEquals(List.of(), i.columns());
        assertEquals("SELECT * FROM read_csv('x.csv')", SqlPrinter.print(i.query()));

        i = assertInstanceOf(InsertInto.class, parse("INSERT INTO t (a) WITH c AS (SELECT 1 AS a) SELECT a FROM c"));
        assertEquals(1, i.query().with().size());

        i = assertInstanceOf(InsertInto.class, parse("INSERT INTO t (a) SELECT 1 UNION ALL SELECT 2"));
        assertInstanceOf(QueryBody.SetOperation.class, i.query().body());

        SqlParser.ParameterizedStatement p = SqlParser.parseStatementWithParameters(
                "INSERT INTO t (a, b) VALUES (?, 2), (3, ?)");
        assertInstanceOf(InsertInto.class, p.statement());
        assertEquals(2, p.parameters().size());
        assertEquals(new Position(1, 41), p.parameters().get(1).position());
    }

    @Test
    void dropTable() {
        assertEquals(new DropTable(List.of("t"), false, new Position(1, 12)), parse("DROP TABLE t"));
        assertEquals(new DropTable(List.of("a", "b", "c"), true, new Position(1, 22)),
                parse("drop table if exists a.b.c;"));
    }

    @Test
    void errors() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("CREATE TABLE t (a INTEGER PRIMARY KEY)",
                "Not supported: PRIMARY KEY (column a); a column has a name, a type and NOT NULL@1:14");
        cases.put("CREATE TABLE t (a INTEGER DEFAULT 1)",
                "Not supported: DEFAULT 1 (column a); a column has a name, a type and NOT NULL@1:14");
        cases.put("CREATE TABLE t (a INTEGER, CONSTRAINT pk PRIMARY KEY (a))", "Not supported: constraints@1:14");
        cases.put("CREATE TEMP TABLE t (a INTEGER)", "Not supported: TEMP TABLE@1:19");
        cases.put("CREATE TABLE t (a INTEGER) WITH (fillfactor = 70)", "Not supported: table options@1:14");
        cases.put("CREATE TABLE t (a, b) AS SELECT 1, 2", "Not supported: column names before AS@1:14");
        cases.put("CREATE TABLE t (a TIMETZ)", "Type TIMETZ is not supported (column a)@1:14");
        cases.put("CREATE TABLE t (a INTEGER[3])",
                "Arrays of a fixed size are not supported: INTEGER[3] (column a)@1:14");
        cases.put("INSERT INTO t DEFAULT VALUES", "Not supported: DEFAULT VALUES@1:13");
        cases.put("INSERT INTO t VALUES (1) ON CONFLICT DO NOTHING", "Not supported: ON CONFLICT@1:13");
        cases.put("INSERT INTO t VALUES (1) RETURNING a", "Not supported: RETURNING@1:13");
        cases.put("INSERT INTO t (t.a) VALUES (1)", "Syntax error: expected a name without a qualifier@1:16");
        cases.put("WITH c AS (SELECT 1) INSERT INTO t SELECT * FROM c", "Not supported: WITH before INSERT@1:34");
        cases.put("INSERT OR REPLACE INTO t VALUES (1)",
                "Only queries, COPY, CREATE TABLE, INSERT INTO and DROP TABLE are supported, not UPSERT@0:0");
        cases.put("UPDATE t SET a = 1",
                "Only queries, COPY, CREATE TABLE, INSERT INTO and DROP TABLE are supported, not UPDATE@0:0");
        cases.put("DROP TABLE t CASCADE", "Not supported: CASCADE@1:12");
        cases.put("DROP TABLE t, u", "Not supported: DROP TABLE of more than one table@1:15");
        cases.put("DROP VIEW v",
                "Only queries, COPY, CREATE TABLE, INSERT INTO and DROP TABLE are supported, not DROP VIEW@1:11");
        cases.put("CREATE TABLE t AS SELECT 1; DROP TABLE t", "Only one statement is supported@1:40");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException e = assertThrows(SqlException.class, () -> parse(c.getKey()), c.getKey());
            assertEquals(c.getValue(), e.reason() + "@" + e.position().line() + ":" + e.position().column(),
                    c.getKey());
        }
    }

    @Test
    void explainTakesAStatementWithItsPositions() {
        Explain e = assertInstanceOf(Explain.class, parse("EXPLAIN SELECT a FROM t WHERE a > 1"));
        assertFalse(e.analyze());
        assertEquals(new Position(1, 1), e.position());
        assertEquals("SELECT a FROM t WHERE (a > 1)", SqlPrinter.print(assertInstanceOf(Query.class, e.statement())));

        e = assertInstanceOf(Explain.class, parse("-- the plan\n  explain\n  Analyze INSERT INTO t VALUES (1);"));
        assertTrue(e.analyze());
        assertEquals(new Position(2, 3), e.position());
        assertEquals(new Position(3, 23), assertInstanceOf(InsertInto.class, e.statement()).position());

        e = assertInstanceOf(Explain.class, parse("EXPLAIN /* a copy */ COPY t TO 'x.csv'"));
        assertEquals("x.csv", assertInstanceOf(CopyTo.class, e.statement()).path());

        SqlParser.ParameterizedStatement p = SqlParser.parseStatementWithParameters(
                "EXPLAIN ANALYZE SELECT a FROM t WHERE a = ?");
        assertTrue(assertInstanceOf(Explain.class, p.statement()).analyze());
        assertEquals(1, p.parameters().size());
        assertEquals(new Position(1, 43), p.parameters().get(0).position());

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("EXPLAIN EXPLAIN SELECT 1", "EXPLAIN cannot explain EXPLAIN@1:9");
        cases.put("EXPLAIN ANALYZE explain SELECT 1", "EXPLAIN cannot explain EXPLAIN@1:17");
        cases.put("EXPLAIN", "Empty query@0:0");
        cases.put("EXPLAIN ANALYZE", "Empty query@0:0");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException x = assertThrows(SqlException.class, () -> parse(c.getKey()), c.getKey());
            assertEquals(c.getValue(), x.reason() + "@" + x.position().line() + ":" + x.position().column(),
                    c.getKey());
        }
    }

    @Test
    void theQueryParserTakesQueriesOnly() {
        for (String sql : List.of("CREATE TABLE t AS SELECT 1", "INSERT INTO t VALUES (1)", "DROP TABLE t",
                "EXPLAIN SELECT 1")) {
            SqlException e = assertThrows(SqlException.class, () -> SqlParser.parse(sql), sql);
            assertTrue(e.reason().startsWith("Only queries are supported, not "), e.reason());
        }
        assertInstanceOf(Query.class, SqlParser.parseStatementWithParameters("SELECT ?").statement());
    }
}
