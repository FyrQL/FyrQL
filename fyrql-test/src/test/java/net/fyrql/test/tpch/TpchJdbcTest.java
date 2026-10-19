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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.jdbc.FyrQLDriver;
import net.fyrql.test.Normalizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TPC-H queries of {@link TpchQueries} at scale factor 0.01 through
 * the JDBC driver, over the five sources of {@link TpchEngine}: each gives
 * the rows of the engine, in its order, with the names and types of the
 * columns of its plan. Q6 runs once more as a prepared statement, and the
 * driver lists the tables in their sources.
 */
class TpchJdbcTest {

    private static FyrQL engine;
    private static Connection connection;

    @BeforeAll
    static void setUp() {
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, TpchTest.directory(TpchEngine.SMALL));
        engine = tpch.open(null, null);
        connection = FyrQLDriver.connect(engine, false);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    static List<TpchQueries.Query> queries() {
        return TpchQueries.ALL;
    }

    /** Reads the rows of a result set as values of the engine, after checking its columns against a schema. */
    private static List<Object[]> rows(ResultSet rs, Schema schema) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        assertEquals(schema.size(), md.getColumnCount());
        for (int c = 0; c < schema.size(); c++) {
            assertEquals(schema.name(c), md.getColumnLabel(c + 1));
            assertEquals(schema.type(c).sqlName(), md.getColumnTypeName(c + 1), schema.name(c));
        }
        List<Object[]> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] row = new Object[schema.size()];
            for (int c = 0; c < row.length; c++) {
                row[c] = Normalizer.normalize(rs.getObject(c + 1), schema.type(c));
            }
            rows.add(row);
        }
        return rows;
    }

    private static void assertSameRows(String name, PlanNode plan, List<Object[]> expected, List<Object[]> actual) {
        Optional<String> differences = TpchQueries.compare(plan, expected, actual);
        if (differences.isPresent()) {
            fail(name + " through the driver differs from the engine:\n" + differences.get() + "\n"
                    + engine.explain(plan));
        }
    }

    @ParameterizedTest
    @MethodSource("queries")
    void theQueryGivesTheRowsOfTheEngine(TpchQueries.Query query) throws SQLException {
        PlanNode plan = engine.plan(query.sql());
        List<Object[]> expected = engine.rows(plan);
        assertFalse(expected.isEmpty(), query.name() + " gives no rows");
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(query.sql())) {
            assertSameRows(query.name(), plan, expected, rows(rs, plan.schema()));
        }
    }

    @Test
    void q6RunsAsAPreparedStatement() throws SQLException {
        TpchQueries.Query q6 = queries().stream().filter(q -> q.name().equals("q06")).findFirst().orElseThrow();
        PlanNode plan = engine.plan(q6.sql());
        String sql = """
                select sum(l_extendedprice * l_discount) as revenue
                from lineitem
                where l_shipdate >= ?
                    and l_shipdate < ? + interval '1' year
                    and l_discount between ? - 0.01 and ? + 0.01
                    and l_quantity < ?""";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setDate(1, Date.valueOf("1994-01-01"));
            p.setDate(2, Date.valueOf("1994-01-01"));
            p.setBigDecimal(3, new BigDecimal("0.06"));
            p.setBigDecimal(4, new BigDecimal("0.06"));
            p.setInt(5, 24);
            for (int run = 0; run < 2; run++) {
                try (ResultSet rs = p.executeQuery()) {
                    assertSameRows("q06", plan, engine.rows(plan), rows(rs, plan.schema()));
                }
            }
            p.setInt(5, 0);
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertNull(rs.getObject(1), "no line item has a quantity below 0");
                assertFalse(rs.next());
            }
        }
    }

    /** The text in which DBeaver reads the rows of a table it shows. */
    @Test
    void aTableReadsWithItsFullNameAndAnOffset() throws SQLException {
        String sql = "SELECT * FROM \"files\".\"main\".\"partsupp\" LIMIT 10 OFFSET 5";
        PlanNode plan = engine.plan(sql);
        List<Object[]> all = engine.rows("SELECT * FROM partsupp LIMIT 15");
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<Object[]> rows = rows(rs, plan.schema());
            assertEquals(10, rows.size());
            assertSameRows("partsupp", plan, all.subList(5, 15), rows);
        }
    }

    @Test
    void theDriverListsTheTablesInTheirSources() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        for (TpchTable table : TpchTable.values()) {
            String source = switch (table.store()) {
                case CSV -> "files";
                case JSON -> "json";
                case MEMORY -> "mem";
                case PARQUET -> "parquet";
                case DATABASE -> "db";
            };
            try (ResultSet rs = md.getTables(null, null, table.tableName(), null)) {
                assertTrue(rs.next(), table.tableName());
                assertEquals(source, rs.getString("TABLE_CAT"));
                assertTrue(table.tableName().equalsIgnoreCase(rs.getString("TABLE_NAME")));
                assertFalse(rs.next(), "the table is in one source");
            }
            List<String> columns = new ArrayList<>();
            List<String> types = new ArrayList<>();
            try (ResultSet rs = md.getColumns(source, null, table.tableName(), null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    types.add(rs.getString("TYPE_NAME"));
                }
            }
            assertEquals(table.schema().fields().stream().map(Schema.Field::name).toList(), columns);
            assertEquals(table.schema().types().stream().map(DataType::sqlName).toList(), types,
                    table.tableName());
        }
    }
}
