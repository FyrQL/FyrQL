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

import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.parse.Parameters;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The types the binder gives the parameters of a prepared query, context by
 * context, and the schema of the result, which is that of the query with
 * a literal of the type in place of each parameter.
 */
class BinderParameterTest {

    /**
     * One table t(a INTEGER, b VARCHAR, e TINYINT, d DECIMAL(10,2), f DOUBLE,
     * dt DATE, ts TIMESTAMP, k BOOLEAN, l INTEGER[], s STRUCT(x INTEGER),
     * m MAP(VARCHAR, BIGINT)).
     */
    private static final Schema T = Schema.of(new Schema.Field("a", DataType.INTEGER),
            new Schema.Field("b", DataType.VARCHAR), new Schema.Field("e", DataType.TINYINT),
            new Schema.Field("d", DataType.decimal(10, 2)), new Schema.Field("f", DataType.DOUBLE),
            new Schema.Field("dt", DataType.DATE), new Schema.Field("ts", DataType.TIMESTAMP),
            new Schema.Field("k", DataType.BOOLEAN), new Schema.Field("l", DataType.listOf(DataType.INTEGER)),
            new Schema.Field("s", DataType.struct(new DataType.Struct.Field("x", DataType.INTEGER))),
            new Schema.Field("m", DataType.mapOf(DataType.VARCHAR, DataType.BIGINT)));

    private static final CatalogView CATALOG = new CatalogView() {
        @Override
        public TableRef resolve(List<String> name) {
            if (!name.get(name.size() - 1).equalsIgnoreCase("t")) {
                throw new SqlException("Table with name " + String.join(".", name) + " does not exist",
                        Position.UNKNOWN);
            }
            return TableRef.of("s", "t");
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

    private static Binder.Prepared prepare(String sql) {
        SqlParser.Parameterized parsed = SqlParser.parseWithParameters(sql);
        assertEquals(Parameters.parse(sql).count(), parsed.parameters().size(), sql);
        return BINDER.prepare(parsed.query(), parsed.parameters());
    }

    /** Returns the types of the parameters by their names in SQL. */
    private static List<String> types(String sql) {
        List<String> names = new ArrayList<>();
        for (DataType t : prepare(sql).parameterTypes()) {
            names.add(t.sqlName());
        }
        return names;
    }

    private static SqlException error(String sql) {
        return assertThrows(SqlException.class, () -> prepare(sql), sql);
    }

    @Test
    void aParameterTakesTheTypeItsContextGivesABareNull() {
        Map<String, List<String>> cases = Map.ofEntries(
                // Comparisons and arithmetic: the other side.
                Map.entry("SELECT a FROM t WHERE a = ?", List.of("INTEGER")),
                Map.entry("SELECT a FROM t WHERE ? <> b", List.of("VARCHAR")),
                Map.entry("SELECT a FROM t WHERE d >= ? AND ts < ?", List.of("DECIMAL(10,2)", "TIMESTAMP")),
                Map.entry("SELECT a FROM t WHERE a IS DISTINCT FROM ?", List.of("INTEGER")),
                Map.entry("SELECT a + ? FROM t", List.of("INTEGER")),
                Map.entry("SELECT e * ? FROM t", List.of("TINYINT")),
                Map.entry("SELECT dt + ? FROM t", List.of("INTEGER")),
                Map.entry("SELECT ? + ?", List.of("BIGINT", "BIGINT")),
                Map.entry("SELECT -? + a FROM t", List.of("BIGINT")),
                // BETWEEN, IN, CASE, COALESCE and the like: the other values.
                Map.entry("SELECT a FROM t WHERE a BETWEEN ? AND ?", List.of("INTEGER", "INTEGER")),
                Map.entry("SELECT a FROM t WHERE a IN (?, 3, ?)", List.of("INTEGER", "INTEGER")),
                Map.entry("SELECT a FROM t WHERE ? IN (a, e)", List.of("INTEGER")),
                Map.entry("SELECT CASE WHEN k THEN ? ELSE d END FROM t", List.of("DECIMAL(10,2)")),
                Map.entry("SELECT CASE a WHEN ? THEN 'x' END FROM t", List.of("INTEGER")),
                Map.entry("SELECT coalesce(b, ?) FROM t", List.of("VARCHAR")),
                Map.entry("SELECT nullif(f, ?) FROM t", List.of("DOUBLE")),
                Map.entry("SELECT greatest(?, ts) FROM t", List.of("TIMESTAMP")),
                // Conditions.
                Map.entry("SELECT a FROM t WHERE ?", List.of("BOOLEAN")),
                Map.entry("SELECT a FROM t WHERE k AND NOT ?", List.of("BOOLEAN")),
                Map.entry("SELECT a FROM t WHERE b LIKE ? OR b ILIKE ?", List.of("VARCHAR", "VARCHAR")),
                // Casts.
                Map.entry("SELECT CAST(? AS DATE), TRY_CAST(? AS UUID), ?::INTEGER[]",
                        List.of("DATE", "UUID", "INTEGER[]")),
                // Functions: the first type the function takes.
                Map.entry("SELECT abs(?)", List.of("BIGINT")),
                Map.entry("SELECT upper(?), length(?)", List.of("VARCHAR", "VARCHAR")),
                Map.entry("SELECT a FROM t WHERE starts_with(b, ?)", List.of("VARCHAR")),
                // Lists, structs and maps.
                Map.entry("SELECT list_contains(l, ?) FROM t", List.of("INTEGER")),
                Map.entry("SELECT l[?], m[?] FROM t", List.of("BIGINT", "VARCHAR")),
                Map.entry("SELECT a FROM t WHERE s = ?", List.of("STRUCT(x INTEGER)")),
                Map.entry("SELECT [a, ?] FROM t", List.of("INTEGER")),
                Map.entry("SELECT b || ?, ? || ? FROM t", List.of("VARCHAR", "VARCHAR", "VARCHAR")),
                // Window functions.
                Map.entry("SELECT lag(a, ?, ?) OVER (ORDER BY a) FROM t", List.of("BIGINT", "INTEGER")),
                // Other clauses and subqueries.
                Map.entry("SELECT b, count(*) FROM t GROUP BY b HAVING count(*) > ?", List.of("BIGINT")),
                Map.entry("SELECT sum(a + ?) FROM t", List.of("INTEGER")),
                Map.entry("SELECT a FROM t WHERE a > (SELECT max(a) FROM t WHERE b = ?)", List.of("VARCHAR")),
                Map.entry("SELECT a FROM t WHERE a IN (SELECT e FROM t WHERE f < ?) AND b = ?",
                        List.of("DOUBLE", "VARCHAR")),
                Map.entry("SELECT * FROM (VALUES (1, ?), (2, 'x')) v", List.of("VARCHAR")),
                Map.entry("SELECT a FROM t UNION ALL SELECT CAST(? AS INTEGER)", List.of("INTEGER")),
                Map.entry("WITH c AS (SELECT a FROM t WHERE a > ?) SELECT * FROM c, c AS c2", List.of("INTEGER")),
                Map.entry("SELECT ? AS x FROM t WHERE x = b", List.of("VARCHAR")));
        for (Map.Entry<String, List<String>> c : cases.entrySet()) {
            assertEquals(c.getValue(), types(c.getKey()), c.getKey());
        }
    }

    @Test
    void theColumnsAreThoseOfTheQueryWithTypedValues() {
        // A NULL would fold the sum into a bare NULL; a parameter is no constant and folds nothing.
        Binder.Prepared p = prepare("SELECT d + ? AS x, b || ? AS y, ? AS z FROM t WHERE z = a");
        assertEquals(List.of(DataType.decimal(11, 2), DataType.VARCHAR, DataType.INTEGER), p.plan().schema().types());
        assertEquals(List.of(DataType.decimal(10, 2), DataType.VARCHAR, DataType.INTEGER), p.parameterTypes());
        PlanNode literal = BINDER.bind(SqlParser.parse("SELECT d + CAST('1.5' AS DECIMAL(10,2)) AS x, "
                + "b || CAST('a' AS VARCHAR) AS y, CAST(3 AS INTEGER) AS z FROM t WHERE z = a"));
        assertEquals(literal.schema().types(), p.plan().schema().types());
        assertEquals("(a + ?)", prepare("SELECT a + ? FROM t").plan().schema().name(0));
    }

    @Test
    void aParameterWithoutAContextOrWhereAConstantIsNeededIsAnError() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("SELECT ?", "The type of parameter 1 cannot be inferred; write CAST(? AS type)@1:8"),
                Map.entry("SELECT a FROM t WHERE a = ? AND ? IS NULL",
                        "The type of parameter 2 cannot be inferred; write CAST(? AS type)@1:33"),
                Map.entry("SELECT a FROM t UNION SELECT ?",
                        "The type of parameter 1 cannot be inferred; write CAST(? AS type)@1:30"),
                Map.entry("SELECT coalesce(?, ?) = a FROM t",
                        "The type of parameter 1 cannot be inferred; write CAST(? AS type)@1:17"),
                Map.entry("SELECT a FROM t LIMIT ?", "Not supported: a parameter in LIMIT or OFFSET@1:23"),
                Map.entry("SELECT a FROM t OFFSET 1 + ?", "Not supported: a parameter in LIMIT or OFFSET@1:28"),
                Map.entry("SELECT a FROM t ORDER BY ?", "Not supported: a parameter as a term of ORDER BY@1:26"),
                Map.entry("SELECT b, count(*) FROM t GROUP BY ?",
                        "Not supported: a parameter as a term of GROUP BY@1:36"),
                Map.entry("SELECT * FROM read_csv(?)",
                        "Not supported: a parameter as an argument of a table function@1:24"),
                Map.entry("SELECT string_agg(b, ?) FROM t",
                        "Not supported: a parameter as the separator of string_agg@1:22"),
                Map.entry("SELECT sum(a) OVER (ORDER BY a ROWS ? PRECEDING) FROM t",
                        "Not supported: a parameter as the offset of a frame@1:37"),
                Map.entry("SELECT strftime(dt, ?) FROM t", "strftime format must be a constant@1:21"),
                Map.entry("SELECT round(d, ?) FROM t", "ROUND(DECIMAL, INTEGER) with non-constant precision is not supported@1:8"),
                Map.entry("SELECT s[CAST(? AS VARCHAR)] FROM t",
                        "Key name for struct_extract needs to be a constant string@1:8"),
                Map.entry("SELECT a FROM t WHERE ?1 = a", "Not supported: numbered parameters@1:23"),
                Map.entry("SELECT a FROM t WHERE :x = a", "Not supported: named parameters@1:23"));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            SqlException e = error(c.getKey());
            assertEquals(c.getValue(), e.reason() + "@" + e.position().line() + ":" + e.position().column(),
                    c.getKey());
        }
    }

    @Test
    void aParameterIsAnErrorOutsideOfAPreparedQuery() {
        SqlException e = assertThrows(SqlException.class, () -> SqlParser.parse("SELECT a FROM t WHERE a = ?"));
        assertEquals("Not supported: parameters outside a prepared query", e.reason());
        // Nor does the binder take a parameter in a tree for a query without parameters.
        SqlParser.Parameterized parsed = SqlParser.parseWithParameters("SELECT a FROM t WHERE a = ?");
        e = assertThrows(SqlException.class, () -> BINDER.bind(parsed.query()));
        assertEquals("Not supported: parameters outside a prepared query", e.reason());
    }

    @Test
    void theParametersAreNumberedInTheOrderOfTheText() {
        SqlParser.Parameterized parsed = SqlParser.parseWithParameters(
                "SELECT ? + a FROM t WHERE b = ? AND a IN (SELECT a FROM t WHERE e = ?) ORDER BY a + ?");
        assertEquals(List.of(1, 2, 3, 4), parsed.parameters().stream().map(p -> p.index()).toList());
        assertEquals(List.of(8, 31, 69, 85), parsed.parameters().stream().map(p -> p.position().column()).toList());
        assertEquals(List.of("INTEGER", "VARCHAR", "TINYINT", "INTEGER"),
                types("SELECT ? + a FROM t WHERE b = ? AND a IN (SELECT a FROM t WHERE e = ?) ORDER BY a + ?"));
    }
}
