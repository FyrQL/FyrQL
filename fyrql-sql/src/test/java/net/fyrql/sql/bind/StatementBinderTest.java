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

package net.fyrql.sql.bind;

import net.fyrql.core.expr.Expression;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The statements that write, bound: their targets, formats, and the plans of the rows they write. */
class StatementBinderTest {

    /** One table t(a INTEGER NOT NULL, b VARCHAR, e TINYINT, d DATE) in the schema main of a source s. */
    private static final Schema T = Schema.of(new Schema.Field("a", DataType.INTEGER, false),
            new Schema.Field("b", DataType.VARCHAR), new Schema.Field("e", DataType.TINYINT),
            new Schema.Field("d", DataType.DATE));

    private static final TableRef S_T = TableRef.of("s", "t");

    private static final CatalogView CATALOG = new CatalogView() {
        @Override
        public TableRef resolve(List<String> name) {
            return find(name).orElseThrow(() -> new SqlException("Table with name " + String.join(".", name)
                    + " does not exist", Position.UNKNOWN));
        }

        @Override
        public Optional<TableRef> find(List<String> name) {
            return name.get(name.size() - 1).equalsIgnoreCase("t") ? Optional.of(S_T) : Optional.empty();
        }

        @Override
        public TableRef resolveNew(List<String> name) {
            if (name.size() > 1 && !name.get(0).equals("s")) {
                throw new SqlException("Source " + name.get(0) + " is not registered", Position.UNKNOWN);
            }
            return TableRef.of("s", name.get(name.size() - 1));
        }

        @Override
        public Schema schema(TableRef table) {
            return T;
        }

        @Override
        public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
            return TableRef.of("s", name + arguments);
        }
    };

    private static final Binder BINDER = new Binder(CATALOG, FunctionRegistry.standard());

    private static BoundStatement bind(String sql) {
        return BINDER.bind(SqlParser.parseStatement(sql));
    }

    private static String error(String sql) {
        SqlException e = assertThrows(SqlException.class, () -> bind(sql), sql);
        return e.reason() + "@" + e.position().line() + ":" + e.position().column();
    }

    private static List<String> expressions(PlanNode plan) {
        return assertInstanceOf(PlanNode.Project.class, plan).columns().stream()
                .map(c -> c.name() + "=" + c.expression()).toList();
    }

    @Test
    void aQueryBindsToItsPlan() {
        BoundStatement.Query q = assertInstanceOf(BoundStatement.Query.class, bind("SELECT a FROM t"));
        assertEquals(List.of("a"), q.schema().fields().stream().map(Schema.Field::name).toList());
        assertEquals(BoundStatement.COUNT, bind("DROP TABLE t").schema());
    }

    @Test
    void copyTakesItsFormatFromTheExtension() {
        BoundStatement.Copy c = assertInstanceOf(BoundStatement.Copy.class, bind("COPY t TO 'out.CSV.gz'"));
        assertEquals("csv", c.format());
        assertEquals(Map.of("compression", "gzip"), c.options());
        assertEquals(T.fields().stream().map(Schema.Field::type).toList(),
                c.input().schema().fields().stream().map(Schema.Field::type).toList());

        c = assertInstanceOf(BoundStatement.Copy.class,
                bind("COPY (SELECT a FROM t) TO 'x.parquet' (COMPRESSION snappy)"));
        assertEquals("parquet", c.format());
        assertEquals(Map.of("compression", "snappy"), c.options());

        c = assertInstanceOf(BoundStatement.Copy.class,
                bind("COPY t TO 'x.json' (header false, FORMAT 'CSV', quote '|')"));
        assertEquals("csv", c.format());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("header", "false");
        expected.put("quote", "|");
        assertEquals(List.copyOf(expected.entrySet()), List.copyOf(c.options().entrySet()));

        c = assertInstanceOf(BoundStatement.Copy.class, bind("COPY t TO 'x.ndjson.zst' (COMPRESSION none)"));
        assertEquals("json", c.format());
        assertEquals(Map.of("compression", "none"), c.options());
        c = assertInstanceOf(BoundStatement.Copy.class, bind("COPY t TO 'x.jsonl.zst'"));
        assertEquals(Map.of("compression", "zstd"), c.options());

        assertEquals("No format for the file x.txt: end its name in .csv, .json or .parquet, or give one, as in"
                + " (FORMAT csv)@1:11", error("COPY t TO 'x.txt'"));
        assertEquals("Table with name nope does not exist@1:6", error("COPY nope TO 'x.csv'"));
    }

    @Test
    void createTableAsTakesTheColumnsOfTheQuery() {
        BoundStatement.Create c = assertInstanceOf(BoundStatement.Create.class,
                bind("CREATE TABLE n AS SELECT a, b AS c FROM t"));
        assertEquals(TableRef.of("s", "n"), c.table());
        assertEquals(Schema.of(new Schema.Field("a", DataType.INTEGER, true), new Schema.Field("c", DataType.VARCHAR)),
                c.schema());
        assertEquals(BoundStatement.CreateMode.CREATE, c.mode());
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR),
                c.input().schema().fields().stream().map(Schema.Field::type).toList());
        assertEquals(BoundStatement.CreateMode.OR_REPLACE,
                ((BoundStatement.Create) bind("CREATE OR REPLACE TABLE s.n AS SELECT 1")).mode());
        assertEquals(BoundStatement.CreateMode.IF_NOT_EXISTS,
                ((BoundStatement.Create) bind("CREATE TABLE IF NOT EXISTS n AS SELECT 1")).mode());

        c = assertInstanceOf(BoundStatement.Create.class, bind("CREATE TABLE n (x INTEGER NOT NULL, y DECIMAL(5,1))"));
        assertEquals(Schema.of(new Schema.Field("x", DataType.INTEGER, false),
                new Schema.Field("y", DataType.decimal(5, 1))), c.schema());
        assertNull(c.input());

        assertEquals("Column with name a appears more than once in table n@1:14",
                error("CREATE TABLE n AS SELECT a, a FROM t"));
        assertEquals("Column with name X appears more than once in table n@1:14",
                error("CREATE TABLE n (x INTEGER, X VARCHAR)"));
        assertEquals("Source nosuch is not registered@1:14", error("CREATE TABLE nosuch.n (x INTEGER)"));
    }

    @Test
    void insertWritesTheRowsInTheColumnsOfTheTable() {
        BoundStatement.Insert i = assertInstanceOf(BoundStatement.Insert.class, bind("INSERT INTO t SELECT * FROM t"));
        assertEquals(S_T, i.table());
        assertEquals(BINDER.bind(SqlParser.parse("SELECT * FROM t")), i.input());

        i = assertInstanceOf(BoundStatement.Insert.class, bind("INSERT INTO t (B, a) VALUES ('x', 1), ('y', 2)"));
        assertEquals(List.of("a=Column[index=1, type=INTEGER]", "b=Column[index=0, type=VARCHAR]",
                "e=Literal[type=TINYINT, value=null]", "d=Literal[type=DATE, value=null]"), expressions(i.input()));

        i = assertInstanceOf(BoundStatement.Insert.class,
                bind("INSERT INTO t (a, e, d) SELECT 1.5, 300, '2024-01-01'"));
        assertEquals(List.of("a=Cast[operand=Column[index=0, type=DECIMAL(2,1)], target=INTEGER, safe=false]",
                "b=Literal[type=VARCHAR, value=null]",
                "e=Cast[operand=Column[index=1, type=INTEGER], target=TINYINT, safe=false]",
                "d=Cast[operand=Column[index=2, type=VARCHAR], target=DATE, safe=false]"), expressions(i.input()));
    }

    @Test
    void aBareNullTakesTheTypeOfItsColumn() {
        BoundStatement.Insert i = assertInstanceOf(BoundStatement.Insert.class,
                bind("INSERT INTO t (a, d) VALUES (1, NULL), (2, NULL)"));
        assertEquals("d=Column[index=1, type=DATE]", expressions(i.input()).get(3));
        i = assertInstanceOf(BoundStatement.Insert.class, bind("INSERT INTO t (a, d) SELECT 1, NULL"));
        assertEquals("d=Literal[type=DATE, value=null]", expressions(i.input()).get(3));
    }

    @Test
    void insertErrors() {
        assertEquals("Table t has 4 columns but 2 values were supplied@1:13", error("INSERT INTO t VALUES (1, 'x')"));
        assertEquals("Column name/value mismatch for insert on t: expected 1 columns but 2 values were supplied@1:13",
                error("INSERT INTO t (a) VALUES (1, 2)"));
        assertEquals("NOT NULL constraint failed: t.a, which the INSERT gives no value@1:13",
                error("INSERT INTO t (b) VALUES ('x')"));
        assertEquals("Column a is given more than once in the INSERT into t@1:13",
                error("INSERT INTO t (a, A) VALUES (1, 2)"));
        assertEquals("Table t does not have a column with name \"zz\"@1:13", error("INSERT INTO t (zz) VALUES (1)"));
        assertEquals("Cannot insert a value of type BOOLEAN into column d of type DATE - an explicit cast is"
                + " required@1:13", error("INSERT INTO t (a, d) VALUES (1, true)"));
        assertEquals("Table with name nope does not exist@1:13", error("INSERT INTO nope VALUES (1)"));
    }

    @Test
    void parametersInValuesTakeTheTypesOfTheirColumns() {
        SqlParser.ParameterizedStatement parsed = SqlParser.parseStatementWithParameters(
                "INSERT INTO t (a, d, b) VALUES (?, ?, 'x'), (2, NULL, ?)");
        Binder.PreparedStatement p = BINDER.prepare(parsed.statement(), parsed.parameters());
        assertEquals(List.of(DataType.INTEGER, DataType.DATE, DataType.VARCHAR), p.parameterTypes());
        assertInstanceOf(BoundStatement.Insert.class, p.statement());

        parsed = SqlParser.parseStatementWithParameters("COPY (SELECT * FROM t WHERE e = ?) TO 'x.csv'");
        assertEquals(List.of(DataType.TINYINT), BINDER.prepare(parsed.statement(), parsed.parameters())
                .parameterTypes());

        SqlParser.ParameterizedStatement select = SqlParser.parseStatementWithParameters("INSERT INTO t (a) SELECT ?");
        SqlException e = assertThrows(SqlException.class,
                () -> BINDER.prepare(select.statement(), select.parameters()));
        assertEquals("The type of parameter 1 cannot be inferred; write CAST(? AS type)", e.reason());
    }

    @Test
    void dropTable() {
        BoundStatement.Drop d = assertInstanceOf(BoundStatement.Drop.class, bind("DROP TABLE t"));
        assertSame(S_T, d.table());
        d = assertInstanceOf(BoundStatement.Drop.class, bind("DROP TABLE IF EXISTS nope"));
        assertNull(d.table());
        assertEquals(List.of("nope"), d.name());
        assertEquals("Table with name nope does not exist@1:12", error("DROP TABLE nope"));
    }

    @Test
    void aCatalogThatWritesNothing() {
        Binder binder = new Binder(new CatalogView() {
            @Override
            public TableRef resolve(List<String> name) {
                return S_T;
            }

            @Override
            public Schema schema(TableRef table) {
                return T;
            }

            @Override
            public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> named) {
                return S_T;
            }
        }, FunctionRegistry.standard());
        SqlException e = assertThrows(SqlException.class,
                () -> binder.bind(SqlParser.parseStatement("CREATE TABLE n AS SELECT 1")));
        assertEquals("Not supported: writing tables", e.reason());
        assertEquals(new Position(1, 14), e.position());
        assertInstanceOf(Expression.Column.class, ((PlanNode.Project) ((BoundStatement.Insert) binder.bind(
                SqlParser.parseStatement("INSERT INTO t (a, b, d, e) SELECT a, b, d, e FROM t"))).input()).columns()
                .get(3).expression());
    }
}
