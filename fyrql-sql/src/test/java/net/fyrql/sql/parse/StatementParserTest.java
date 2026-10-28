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
import net.fyrql.sql.ast.AlterTable;
import net.fyrql.sql.ast.CreateSchema;
import net.fyrql.sql.ast.CreateTable;
import net.fyrql.sql.ast.CreateTable.ColumnDefinition;
import net.fyrql.sql.ast.CopyTo;
import net.fyrql.sql.ast.Delete;
import net.fyrql.sql.ast.FromItem;
import net.fyrql.sql.ast.CreateView;
import net.fyrql.sql.ast.DropTable;
import net.fyrql.sql.ast.DropView;
import net.fyrql.sql.ast.Explain;
import net.fyrql.sql.ast.InsertInto;
import net.fyrql.sql.ast.Merge;
import net.fyrql.sql.Position;
import net.fyrql.sql.ast.Query;
import net.fyrql.sql.ast.QueryBody;
import net.fyrql.sql.ast.ResetVariable;
import net.fyrql.sql.ast.SetVariable;
import net.fyrql.sql.ast.ShowVariable;
import net.fyrql.sql.ast.SqlExpr;
import net.fyrql.sql.ast.SqlPrinter;
import net.fyrql.sql.ast.Statement;
import net.fyrql.sql.ast.Update;
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

/**
 * The statements besides queries: {@code CREATE TABLE}, {@code INSERT INTO},
 * {@code DROP TABLE}, and {@code SET}, {@code RESET}, {@code SHOW} and
 * {@code USE} of the settings of the session.
 */
class StatementParserTest {

    private static final String SUPPORTED = "Only queries, COPY, CREATE TABLE, CREATE VIEW, CREATE SCHEMA, INSERT"
            + " INTO, UPDATE, DELETE, MERGE, ALTER TABLE ... ADD COLUMN, DROP TABLE and DROP VIEW are supported";

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
    void createTableWithAPrimaryKey() {
        CreateTable c = assertInstanceOf(CreateTable.class, parse("CREATE TABLE t (a INTEGER NOT NULL PRIMARY KEY,"
                + " b VARCHAR)"));
        assertEquals(List.of("a"), c.primaryKey());
        assertEquals(new ColumnDefinition("a", DataType.INTEGER, false), c.columns().get(0));
        c = assertInstanceOf(CreateTable.class, parse("CREATE TABLE t (a INTEGER, \"B\" VARCHAR,"
                + " CONSTRAINT pk PRIMARY KEY (\"B\", a))"));
        assertEquals(List.of("B", "a"), c.primaryKey());
        assertEquals(List.of(), assertInstanceOf(CreateTable.class, parse("CREATE TABLE t (a INTEGER)")).primaryKey());
    }

    @Test
    void update() {
        Update u = assertInstanceOf(Update.class, parse("UPDATE s.t AS x SET a = a + 1, \"B\" = 'y' FROM u, v"
                + " WHERE x.k = u.k"));
        assertEquals(List.of("s", "t"), u.name());
        assertEquals("x", u.alias());
        assertEquals(List.of("a", "B"), u.assignments().stream().map(Update.Assignment::column).toList());
        assertEquals("(a + 1)", SqlPrinter.print(u.assignments().get(0).value()));
        assertInstanceOf(FromItem.Join.class, u.from());
        assertEquals("(x.k = u.k)", SqlPrinter.print(u.where()));
        assertEquals(new Position(1, 8), u.position());

        u = assertInstanceOf(Update.class, SqlParser.parseStatementWithParameters("update t set (a, b) = (1, ?)")
                .statement());
        assertNull(u.alias());
        assertNull(u.from());
        assertNull(u.where());
        assertEquals(List.of("a", "b"), u.assignments().stream().map(Update.Assignment::column).toList());
        assertInstanceOf(SqlExpr.Parameter.class, u.assignments().get(1).value());
    }

    @Test
    void delete() {
        Delete d = assertInstanceOf(Delete.class,
                parse("DELETE FROM s.t x USING u, (SELECT 1 AS k) w WHERE x.k = u.k"));
        assertEquals(List.of("s", "t"), d.name());
        assertEquals("x", d.alias());
        assertInstanceOf(FromItem.Join.class, d.using());
        assertEquals("(x.k = u.k)", SqlPrinter.print(d.where()));
        d = assertInstanceOf(Delete.class, parse("delete from t"));
        assertNull(d.alias());
        assertNull(d.using());
        assertNull(d.where());
    }

    @Test
    void merge() {
        Merge m = assertInstanceOf(Merge.class, parse("MERGE INTO s.t AS x\n"
                + "USING (SELECT k, CASE WHEN v > 1 THEN 'a' ELSE 'b' END AS v FROM u) AS y ON x.k = y.k\n"
                + "WHEN MATCHED AND y.v = 'a' THEN UPDATE SET v = CASE WHEN y.v = 'a' THEN 1 END, w = y.v\n"
                + "WHEN MATCHED THEN DELETE\n"
                + "WHEN NOT MATCHED BY SOURCE THEN DO NOTHING\n"
                + "WHEN NOT MATCHED BY TARGET THEN INSERT (k, v) VALUES (y.k, (y.v))\n"
                + "WHEN NOT MATCHED THEN INSERT BY NAME;"));
        assertEquals(List.of("s", "t"), m.name());
        assertEquals("x", m.alias());
        assertEquals(new Position(1, 12), m.position());
        FromItem.Subquery source = assertInstanceOf(FromItem.Subquery.class, m.source());
        assertEquals("y", source.alias().name());
        assertEquals(new Position(2, 7), source.position());
        assertEquals("(x.k = y.k)", SqlPrinter.print(m.on()));
        assertEquals(5, m.whens().size());
        Merge.When w = m.whens().get(0);
        assertEquals(Merge.Match.MATCHED, w.match());
        assertEquals(new Position(3, 1), w.position());
        assertEquals("(y.v = 'a')", SqlPrinter.print(w.condition()));
        Merge.UpdateAction u = assertInstanceOf(Merge.UpdateAction.class, w.action());
        assertEquals(List.of("v", "w"), u.assignments().stream().map(Update.Assignment::column).toList());
        assertEquals(new Position(3, 44), u.assignments().get(0).position());
        assertInstanceOf(SqlExpr.Case.class, u.assignments().get(0).value());
        assertInstanceOf(Merge.DeleteAction.class, m.whens().get(1).action());
        assertEquals(Merge.Match.NOT_MATCHED_BY_SOURCE, m.whens().get(2).match());
        assertInstanceOf(Merge.NothingAction.class, m.whens().get(2).action());
        Merge.InsertAction i = assertInstanceOf(Merge.InsertAction.class, m.whens().get(3).action());
        assertEquals(Merge.Match.NOT_MATCHED, m.whens().get(3).match());
        assertEquals(List.of("k", "v"), i.columns());
        assertEquals(2, i.values().size());
        i = assertInstanceOf(Merge.InsertAction.class, m.whens().get(4).action());
        assertTrue(i.byName());
        assertNull(i.values());

        m = assertInstanceOf(Merge.class, parse("merge into t using 'x.csv' s using (k, \"L\")"
                + " when matched then update when not matched then insert"));
        assertNull(m.alias());
        assertNull(m.on());
        assertEquals(List.of("k", "L"), m.using());
        assertInstanceOf(FromItem.FileName.class, m.source());
        assertNull(assertInstanceOf(Merge.UpdateAction.class, m.whens().get(0).action()).assignments());
        assertEquals(List.of(), assertInstanceOf(Merge.InsertAction.class, m.whens().get(1).action()).columns());

        SqlParser.ParameterizedStatement p = SqlParser.parseStatementWithParameters("MERGE INTO t USING"
                + " (SELECT ? AS k) s ON t.k = s.k AND t.v = ? WHEN NOT MATCHED THEN INSERT VALUES (s.k, ?)");
        assertEquals(List.of(1, 2, 3), p.parameters().stream().map(SqlExpr.Parameter::index).toList());
        assertEquals(new Position(1, 28), p.parameters().get(0).position());
        assertEquals(new Position(1, 61), p.parameters().get(1).position());
    }

    @Test
    void temporaryTablesSchemasAndColumns() {
        CreateTable c = assertInstanceOf(CreateTable.class, parse("CREATE TEMP TABLE t (a INTEGER)"));
        assertTrue(c.temporary());
        assertTrue(assertInstanceOf(CreateTable.class, parse("create or replace temporary table t as select 1"))
                .temporary());
        assertFalse(assertInstanceOf(CreateTable.class, parse("CREATE TABLE t (a INTEGER)")).temporary());

        assertEquals(new CreateSchema(List.of("sales"), false, new Position(1, 1)), parse("CREATE SCHEMA sales"));
        assertEquals(new CreateSchema(List.of("db", "My Sales"), true, new Position(1, 1)),
                parse("create schema if not exists db.\"My Sales\""));

        AlterTable a = assertInstanceOf(AlterTable.class, parse("ALTER TABLE s.t ADD COLUMN IF NOT EXISTS \"B\""
                + " DECIMAL(10, 2) DEFAULT -1.5"));
        assertEquals(List.of("s", "t"), a.name());
        assertEquals(new ColumnDefinition("B", DataType.decimal(10, 2), true), a.column());
        assertTrue(a.ifNotExists());
        assertEquals("(-1.5)", SqlPrinter.print(a.defaultValue()));
        a = assertInstanceOf(AlterTable.class, parse("alter table t add c varchar"));
        assertNull(a.defaultValue());
        assertFalse(a.ifNotExists());
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
    void createView() {
        CreateView v = assertInstanceOf(CreateView.class, parse("CREATE VIEW v AS SELECT a FROM t WHERE a > 1"));
        assertEquals(List.of("v"), v.name());
        assertEquals(List.of(), v.columns());
        assertEquals("SELECT a FROM t WHERE a > 1", v.text());
        assertEquals("SELECT a FROM t WHERE (a > 1)", SqlPrinter.print(v.query()));
        assertFalse(v.orReplace());
        assertFalse(v.ifNotExists());
        assertEquals(new Position(1, 13), v.position());

        v = assertInstanceOf(CreateView.class, parse("create or replace view s.\"My View\" (x, \"Y\") as\n"
                + "  select a, b\n  from t;  \n"));
        assertEquals(List.of("s", "My View"), v.name());
        assertEquals(List.of("x", "Y"), v.columns());
        assertEquals("select a, b\n  from t", v.text());
        assertTrue(v.orReplace());

        v = assertInstanceOf(CreateView.class, parse("CREATE VIEW IF NOT EXISTS a.b.c AS WITH c AS (SELECT 1 AS \"as\")"
                + " SELECT * FROM c -- the rows"));
        assertEquals(List.of("a", "b", "c"), v.name());
        assertTrue(v.ifNotExists());
        assertEquals("WITH c AS (SELECT 1 AS \"as\") SELECT * FROM c -- the rows", v.text());

        v = assertInstanceOf(CreateView.class, parse("CREATE VIEW v AS (SELECT 1 AS a) UNION ALL (SELECT 2)"));
        assertEquals("(SELECT 1 AS a) UNION ALL (SELECT 2)", v.text());

        Explain e = assertInstanceOf(Explain.class, parse("EXPLAIN CREATE VIEW v AS SELECT 1"));
        assertEquals("SELECT 1", assertInstanceOf(CreateView.class, e.statement()).text());

        SqlException x = assertThrows(SqlException.class,
                () -> SqlParser.parseStatementWithParameters("CREATE VIEW v AS SELECT a FROM t WHERE a = ?"));
        assertEquals("A view cannot have parameters", x.reason());
        assertEquals(new Position(1, 44), x.position());
        x = assertThrows(SqlException.class, () -> parse("CREATE OR REPLACE VIEW IF NOT EXISTS v AS SELECT 1"));
        assertEquals("OR REPLACE and IF NOT EXISTS cannot be used together", x.reason());
        x = assertThrows(SqlException.class, () -> SqlParser.parse("CREATE VIEW v AS SELECT 1"));
        assertEquals("Only queries are supported, not CREATE VIEW", x.reason());
    }

    @Test
    void dropView() {
        assertEquals(new DropView(List.of("v"), false, new Position(1, 11)), parse("DROP VIEW v"));
        assertEquals(new DropView(List.of("a", "b", "c"), true, new Position(1, 21)),
                parse("drop view if exists a.b.c;"));
    }

    @Test
    void errors() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("CREATE TABLE t (a INTEGER UNIQUE)",
                "Not supported: UNIQUE (column a); a column has a name, a type, NOT NULL and PRIMARY KEY@1:14");
        cases.put("CREATE TABLE t (a INTEGER DEFAULT 1)",
                "Not supported: DEFAULT 1 (column a); a column has a name, a type, NOT NULL and PRIMARY KEY@1:14");
        cases.put("CREATE TABLE t (a INTEGER, UNIQUE (a))",
                "Not supported: UNIQUE constraints; a table takes a PRIMARY KEY only@1:14");
        cases.put("CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER PRIMARY KEY)",
                "A table can have only one PRIMARY KEY@1:14");
        cases.put("CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER, PRIMARY KEY (b))",
                "A table can have only one PRIMARY KEY@1:14");
        cases.put("CREATE GLOBAL TEMPORARY TABLE t (a INTEGER)", "Not supported: GLOBAL TEMPORARY TABLE@1:31");
        cases.put("ALTER TABLE t ADD COLUMN a INTEGER, ADD COLUMN b INTEGER",
                "Not supported: more than one change in ALTER TABLE@1:13");
        cases.put("ALTER TABLE t ADD COLUMN a INTEGER UNIQUE", "Not supported: UNIQUE (column a)@1:13");
        cases.put("ALTER TABLE t RENAME TO u", "Not supported: ALTER TABLE ... RENAME_TABLE; only ADD COLUMN is@1:13");
        cases.put("CREATE SCHEMA s AUTHORIZATION bob", "Not supported: AUTHORIZATION@1:1");
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
        cases.put("INSERT OR REPLACE INTO t VALUES (1)", SUPPORTED + ", not UPSERT@0:0");
        cases.put("TRUNCATE t", SUPPORTED + ", not TRUNCATE@0:0");
        cases.put("UPDATE t SET a = 1 RETURNING a", "Not supported: RETURNING@1:8");
        cases.put("UPDATE t SET a = 1 ORDER BY a LIMIT 1", "Not supported: ORDER BY in UPDATE@1:8");
        cases.put("UPDATE t SET a = DEFAULT", "Not supported: DEFAULT; a table has no defaults@1:18");
        cases.put("UPDATE t SET t.a = 1", "Syntax error: expected a name without a qualifier@1:14");
        cases.put("UPDATE t SET (a, b) = (1)", "Syntax error: 2 columns but 1 values in SET@1:15");
        cases.put("WITH c AS (SELECT 1) UPDATE t SET a = 1", "Not supported: WITH before UPDATE@1:29");
        cases.put("UPDATE t x(a) SET a = 1", "Not supported: names of columns in the alias of the table@1:8");
        cases.put("DELETE FROM t RETURNING a", "Not supported: RETURNING@1:13");
        cases.put("MERGE INTO t USING s ON t.k = s.k", "Syntax error: MERGE needs a WHEN@1:34");
        cases.put("MERGE INTO t USING s WHEN MATCHED THEN DELETE",
                "Syntax error: MERGE needs ON or USING after its source@1:22");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN MATCHED THEN INSERT",
                "Syntax error: INSERT takes only WHEN NOT MATCHED [BY TARGET]@1:53");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN NOT MATCHED THEN DELETE",
                "Syntax error: DELETE takes only WHEN MATCHED and WHEN NOT MATCHED BY SOURCE@1:57");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN MATCHED THEN UPDATE SET v = 1 RETURNING *",
                "Not supported: RETURNING@1:70");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN MATCHED BY SOURCE THEN DELETE",
                "Syntax error at or near \"BY\"@1:48");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN MATCHED THEN UPDATE SET (a, b) = (1, 2)",
                "Not supported: SET (columns) = ... in MERGE@1:64");
        cases.put("MERGE INTO t USING s ON t.k = s.k WHEN MATCHED THEN UPDATE SET v = ) ",
                "Syntax error at or near \")\"@1:68");
        cases.put("MERGE INTO t USING s ON (t.k = WHEN MATCHED THEN DELETE", "Syntax error: MERGE needs a WHEN@1:56");
        cases.put("MERGE INTO t USING s ON t.k == s.k WHEN MATCHED THEN UPDATE SET v = s.v +",
                "Syntax error at end of input@1:74");
        cases.put("WITH c AS (SELECT 1) MERGE INTO t USING c ON t.k = c.k WHEN MATCHED THEN DELETE",
                "Not supported: WITH before MERGE@1:33");
        cases.put("DELETE FROM t WHERE a = 1 LIMIT 1", "Not supported: LIMIT in DELETE@1:13");
        cases.put("DROP TABLE t CASCADE", "Not supported: CASCADE@1:12");
        cases.put("DROP TABLE t, u", "Not supported: DROP TABLE of more than one table@1:15");
        cases.put("DROP INDEX i", SUPPORTED + ", not DROP INDEX@1:12");
        cases.put("CREATE TABLE t AS SELECT 1; DROP TABLE t", "Only one statement is supported@1:40");
        cases.put("CREATE TEMP VIEW v AS SELECT 1", "Not supported: TEMP VIEW@1:18");
        cases.put("CREATE TEMPORARY VIEW v AS SELECT 1", "Not supported: TEMPORARY VIEW@1:23");
        cases.put("CREATE MATERIALIZED VIEW v AS SELECT 1", "Not supported: MATERIALIZED VIEW@1:26");
        cases.put("CREATE RECURSIVE VIEW v (a) AS SELECT 1", "Not supported: RECURSIVE VIEW@1:23");
        cases.put("CREATE VIEW v AS SELECT 1 WITH CHECK OPTION", "Not supported: WITH CHECK OPTION@1:13");
        cases.put("CREATE VIEW v AS SELECT 1 WITH READ ONLY", "Not supported: WITH READ ONLY@1:13");
        cases.put("CREATE VIEW v (v.a) AS SELECT 1", "Syntax error: expected a name without a qualifier@1:16");
        cases.put("DROP VIEW v CASCADE", "Not supported: CASCADE@1:11");
        cases.put("DROP VIEW v, w", "Not supported: DROP VIEW of more than one view@1:14");
        cases.put("DROP MATERIALIZED VIEW v", "Not supported: DROP MATERIALIZED@1:24");
        cases.put("ALTER VIEW v AS SELECT 1", SUPPORTED + ", not ALTER VIEW@0:0");
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
    void settingsOfTheSession() {
        Position start = new Position(1, 1);
        Map<String, Statement> cases = new LinkedHashMap<>();
        cases.put("SET TimeZone = 'Europe/Berlin'", new SetVariable("TimeZone", "Europe/Berlin", start));
        cases.put("set timezone to 'UTC';", new SetVariable("timezone", "UTC", start));
        cases.put("SET TimeZone 'UTC'", new SetVariable("TimeZone", "UTC", start));
        cases.put("SET SESSION TimeZone = UTC", new SetVariable("TimeZone", "UTC", start));
        cases.put("SET TIME ZONE 'America/New_York'", new SetVariable("TimeZone", "America/New_York", start));
        cases.put("SET \"TimeZone\" = \"UTC\"", new SetVariable("TimeZone", "UTC", start));
        cases.put("SET statement_timeout = 5000", new SetVariable("statement_timeout", "5000", start));
        cases.put("SET statement_timeout TO '5min'", new SetVariable("statement_timeout", "5min", start));
        cases.put("SET search_path = mem.main", new SetVariable("search_path", "mem.main", start));
        cases.put("SET search_path = \"My.Source\".s", new SetVariable("search_path", "\"My.Source\".s", start));
        cases.put("SET search_path = ''", new SetVariable("search_path", "", start));
        cases.put("SET extra_float_digits = -3", new SetVariable("extra_float_digits", "-3", start));
        cases.put("SET TimeZone TO DEFAULT", new ResetVariable("TimeZone", start));
        cases.put("RESET TimeZone", new ResetVariable("TimeZone", start));
        cases.put("RESET ALL;", new ResetVariable("ALL", start));
        cases.put("SHOW search_path", new ShowVariable("search_path", start));
        cases.put("  show all", new ShowVariable("all", new Position(1, 3)));
        cases.put("USE mem", new SetVariable("search_path", "mem", start));
        cases.put("USE mem.main;", new SetVariable("search_path", "mem.main", start));
        cases.put("USE \"My\".\"S\"", new SetVariable("search_path", "\"My\".\"S\"", start));
        for (Map.Entry<String, Statement> c : cases.entrySet()) {
            assertEquals(c.getValue(), parse(c.getKey()), c.getKey());
        }
        Explain e = assertInstanceOf(Explain.class, parse("EXPLAIN SET TimeZone = 'UTC'"));
        assertEquals(new SetVariable("TimeZone", "UTC", new Position(1, 9)), e.statement());
    }

    @Test
    void settingErrors() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("SET LOCAL TimeZone = 'UTC'",
                "Not supported: SET LOCAL, as FyrQL has no transactions; SET sets a setting of the session@1:5");
        cases.put("SET a = 1, b = 2",
                "Not supported: more than one value in SET, which sets one setting to one value@1:10");
        cases.put("SET search_path = public, pg_catalog",
                "Not supported: more than one value in SET, which sets one setting to one value@1:25");
        cases.put("SET TimeZone = 'UTC'; SET TimeZone = 'UTC'", "Only one statement is supported@1:23");
        cases.put("SET TimeZone =", "Syntax error at end of input@1:15");
        cases.put("SET = 1", "Syntax error at or near \"=\"@1:5");
        cases.put("SET statement_timeout = 5s", "Syntax error at or near \"s\"@1:26");
        cases.put("SHOW TABLES FROM x", "Syntax error at or near \"FROM\"@1:13");
        cases.put("USE 'mem'", "Syntax error at or near \"'mem'\"@1:5");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException e = assertThrows(SqlException.class, () -> parse(c.getKey()), c.getKey());
            assertEquals(c.getValue(), e.reason() + "@" + e.position().line() + ":" + e.position().column(),
                    c.getKey());
        }
    }

    @Test
    void theQueryParserTakesQueriesOnly() {
        for (String sql : List.of("CREATE TABLE t AS SELECT 1", "INSERT INTO t VALUES (1)", "DROP TABLE t",
                "EXPLAIN SELECT 1", "SET TimeZone = 'UTC'", "SHOW ALL", "USE mem")) {
            SqlException e = assertThrows(SqlException.class, () -> SqlParser.parse(sql), sql);
            assertTrue(e.reason().startsWith("Only queries are supported, not "), e.reason());
        }
        assertInstanceOf(Query.class, SqlParser.parseStatementWithParameters("SELECT ?").statement());
    }
}
