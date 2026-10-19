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
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.jdbc.FyrQLDriver;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the texts of {@link SqlCorpusTest} and {@link ParserMeaningTest}
 * through the JDBC driver, over the tables of {@link CorpusTables}: each
 * gives the rows of the engine, with the names and types of the columns of
 * its plan, and {@code getString} gives the text of each value. The texts
 * that fail in FyrQL fail through the driver with its message: those that
 * do not bind when they are executed, the others when they are executed,
 * which runs them until their first rows, or while their rows are read.
 */
class SqlCorpusJdbcTest {

    private static FyrQL engine;
    private static Connection connection;

    @BeforeAll
    static void setUp() {
        engine = FyrQL.builder().register(CorpusTables.builder().build()).build();
        connection = FyrQLDriver.connect(engine, false);
    }

    @AfterAll
    static void tearDown() throws SQLException {
        connection.close();
        engine.close();
    }

    @Test
    void corpusGivesTheRowsOfTheEngine() {
        List<String> queries = new ArrayList<>(SqlCorpusTest.QUERIES);
        queries.addAll(ParserMeaningTest.QUERIES);
        List<String> failures = new ArrayList<>();
        for (String sql : queries) {
            try {
                PlanNode plan = engine.plan(sql);
                String failure = compare(sql, plan);
                if (failure != null) {
                    failures.add(sql + "\n" + failure + "\n" + Plans.print(plan));
                }
            } catch (RuntimeException | SQLException e) {
                failures.add(sql + "\n  " + e);
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + queries.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }

    /** Runs a text through the driver and returns how it differs from the engine, or {@code null}. */
    private static String compare(String sql, PlanNode plan) throws SQLException {
        Schema schema = plan.schema();
        List<Object[]> rows = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            if (md.getColumnCount() != schema.size()) {
                return "  " + md.getColumnCount() + " columns, the plan has " + schema.size();
            }
            for (int c = 0; c < schema.size(); c++) {
                String name = md.getColumnLabel(c + 1);
                String type = md.getColumnTypeName(c + 1);
                if (!name.equals(schema.name(c)) || !type.equals(schema.type(c).sqlName())) {
                    return "  column " + (c + 1) + " is " + name + " " + type + ", in the plan " + schema.name(c)
                            + " " + schema.type(c).sqlName();
                }
            }
            while (rs.next()) {
                Object[] row = new Object[schema.size()];
                for (int c = 0; c < row.length; c++) {
                    row[c] = Normalizer.normalize(rs.getObject(c + 1), schema.type(c));
                    String text = row[c] == null ? null : Casts.format(row[c], schema.type(c));
                    if (!Objects.equals(text, rs.getString(c + 1))) {
                        return "  getString of column " + (c + 1) + " in row " + rows.size() + " is "
                                + rs.getString(c + 1) + ", the value is " + text;
                    }
                }
                rows.add(row);
            }
        }
        ResultComparison.Outcome outcome = ResultComparison.compare(schema, engine.rows(plan), rows,
                Plans.ordered(plan));
        return outcome.same() ? null : outcome.report();
    }

    @Test
    void errorsComeWithTheMessagesOfFyrQL() throws SQLException {
        List<Map.Entry<String, String>> errors = new ArrayList<>(SqlCorpusTest.ERRORS);
        errors.addAll(SqlCorpusTest.DEVIATIONS);
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> error : errors) {
            String sql = error.getKey();
            boolean binds = true;
            try {
                engine.plan(sql);
            } catch (SqlException e) {
                binds = false;
            }
            SQLException failure = null;
            boolean executed = false;
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                executed = true;
                while (rs.next()) {
                    // Read every row, so that errors during the execution surface.
                }
            } catch (SQLException e) {
                failure = e;
            }
            String problem = problem(failure, executed, binds, error.getValue());
            if (problem != null) {
                failures.add(sql + "\n  " + problem + (failure == null ? "" : " (" + failure.getSQLState() + ")")
                        + "\n  expected: " + error.getValue());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n\n", failures));
    }

    /** Tells what is wrong with the failure of a text through the driver, or {@code null}. */
    private static String problem(SQLException failure, boolean executed, boolean binds, String message) {
        if (failure == null) {
            return "runs";
        }
        if (!binds && executed) {
            return "fails when its rows are read";
        }
        if (!failure.getMessage().startsWith(message)) {
            return "fails with " + failure.getMessage();
        }
        if (!binds && failure.getSQLState().equals("HY000")) {
            return "has no state of its own";
        }
        if (message.startsWith("Not supported") && !(failure instanceof SQLFeatureNotSupportedException)) {
            return "is no SQLFeatureNotSupportedException";
        }
        return null;
    }
}
