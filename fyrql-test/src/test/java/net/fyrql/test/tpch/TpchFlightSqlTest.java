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
import net.fyrql.flight.FlightSqlServer;
import net.fyrql.flight.ServerConfig;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The TPC-H queries of {@link TpchQueries} at scale factor 0.01 through the
 * Flight SQL server, read with the Flight SQL JDBC driver of Arrow, over the
 * five sources of {@link TpchEngine}: each gives the rows of the engine, in
 * its order, with the names of the columns of its plan. The driver signs in
 * with a user and a password. It names the type of a column of a result
 * after its Arrow type, so that {@code DECIMAL(p,s)} and {@code HUGEINT}
 * are both {@code DECIMAL}, with the digits as precision and scale, and
 * returns them as {@code BigDecimal}, and a {@code DATE} as
 * {@code java.sql.Date}. Q6 runs once more as a prepared statement, whose
 * parameters the driver sends in the types the server gives them, and the
 * driver lists the tables in their sources, with the type names of FyrQL.
 */
class TpchFlightSqlTest {

    private static FyrQL engine;
    private static FlightSqlServer server;
    private static Connection connection;

    @BeforeAll
    static void setUp() throws Exception {
        TpchEngine tpch = TpchEngine.load(TpchEngine.SMALL, TpchTest.directory(TpchEngine.SMALL));
        engine = tpch.open(null, null);
        server = FlightSqlServer.builder(engine).config(ServerConfig.defaults().withPort(0)
                .withUsers(Map.of("tpch", "dbgen"))).build().start();
        connection = DriverManager.getConnection("jdbc:arrow-flight-sql://localhost:" + server.port()
                + "/?useEncryption=false&user=tpch&password=dbgen");
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
            if (server != null) {
                server.close();
            }
        } finally {
            if (engine != null) {
                engine.close();
            }
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
            DataType type = schema.type(c);
            assertEquals(schema.name(c), md.getColumnLabel(c + 1));
            if (type instanceof DataType.Decimal d) {
                assertEquals("DECIMAL", md.getColumnTypeName(c + 1), schema.name(c));
                assertEquals(d.precision(), md.getPrecision(c + 1), schema.name(c));
                assertEquals(d.scale(), md.getScale(c + 1), schema.name(c));
            } else if (type == DataType.HUGEINT) {
                assertEquals("DECIMAL", md.getColumnTypeName(c + 1), schema.name(c));
                assertEquals(0, md.getScale(c + 1), schema.name(c));
            } else {
                assertEquals(type.sqlName(), md.getColumnTypeName(c + 1), schema.name(c));
            }
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
            fail(name + " through the Flight SQL server differs from the engine:\n" + differences.get() + "\n"
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
                    and l_shipdate < ?
                    and l_discount between ? and ?
                    and l_quantity < ?""";
        try (PreparedStatement p = connection.prepareStatement(sql)) {
            p.setDate(1, Date.valueOf("1994-01-01"));
            p.setDate(2, Date.valueOf("1995-01-01"));
            p.setBigDecimal(3, new BigDecimal("0.05"));
            p.setBigDecimal(4, new BigDecimal("0.07"));
            p.setBigDecimal(5, new BigDecimal("24.00"));
            for (int run = 0; run < 2; run++) {
                try (ResultSet rs = p.executeQuery()) {
                    assertSameRows("q06", plan, engine.rows(plan), rows(rs, plan.schema()));
                }
            }
            p.setBigDecimal(5, new BigDecimal("0.00"));
            try (ResultSet rs = p.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(null, rs.getObject(1), "no line item has a quantity below 0");
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void theDriverListsTheTablesInTheirSources() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        assertEquals("FyrQL", md.getDatabaseProductName());
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
            assertEquals(table.schema().types().stream().map(DataType::sqlName).toList(), types, table.tableName());
        }
    }
}
