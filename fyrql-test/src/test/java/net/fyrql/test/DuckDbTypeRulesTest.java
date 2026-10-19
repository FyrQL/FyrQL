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

import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.type.TypeRules;
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
 * Checks the typing rules of arithmetic against DuckDB: for every pair of
 * numeric column types, the type that DuckDB gives {@code a + b},
 * {@code a * b}, {@code a / b}, {@code a // b} and {@code a % b} must be the
 * one that {@link TypeRules} gives. Columns, not literals, so that DuckDB
 * cannot fold constants.
 */
class DuckDbTypeRulesTest {

    private static final List<DataType> TYPES = List.of(DataType.TINYINT, DataType.SMALLINT, DataType.INTEGER,
            DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE, DataType.decimal(10, 2),
            DataType.decimal(18, 6), DataType.decimal(38, 10), DataType.decimal(5, 0));

    @Test
    void arithmeticResultTypes() throws SQLException {
        List<String> mismatches = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            StringBuilder create = new StringBuilder("CREATE TABLE t (");
            for (int i = 0; i < TYPES.size(); i++) {
                create.append(i > 0 ? ", " : "").append("c").append(i).append(' ').append(TYPES.get(i).sqlName());
            }
            s.execute(create.append(")").toString());
            for (int i = 0; i < TYPES.size(); i++) {
                for (int j = 0; j < TYPES.size(); j++) {
                    DataType a = TYPES.get(i);
                    DataType b = TYPES.get(j);
                    check(s, mismatches, a, b, "+", expected(() -> TypeRules.numeric(a, b)));
                    check(s, mismatches, a, b, "*", expected(() -> TypeRules.multiply(a, b)));
                    check(s, mismatches, a, b, "/", expected(() -> TypeRules.divide(a, b)));
                    check(s, mismatches, a, b, "//", expected(() -> TypeRules.intDivide(a, b)));
                    check(s, mismatches, a, b, "%", expected(() -> TypeRules.remainder(a, b)));
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    /**
     * The arithmetic of dates and timestamps: for {@code DATE} or
     * {@code TIMESTAMP} on either side of every operator and every scalar
     * type on the other, DuckDB must accept the expression with the type
     * that {@link Expression.ArithmeticOp#resultType} gives, or both must
     * reject it; {@code TIMESTAMP - TIMESTAMP}, an {@code INTERVAL} in
     * DuckDB, is rejected.
     */
    @Test
    void dateArithmeticResultTypes() throws SQLException {
        List<DataType> types = List.of(DataType.TINYINT, DataType.SMALLINT, DataType.INTEGER, DataType.BIGINT,
                DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE, DataType.decimal(10, 2), DataType.VARCHAR,
                DataType.BOOLEAN, DataType.DATE, DataType.TIMESTAMP);
        List<String> mismatches = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            StringBuilder create = new StringBuilder("CREATE TABLE t (");
            for (int i = 0; i < types.size(); i++) {
                create.append(i > 0 ? ", " : "").append("c").append(i).append(' ').append(types.get(i).sqlName());
            }
            s.execute(create.append(")").toString());
            for (int i = 0; i < types.size(); i++) {
                for (int j = 0; j < types.size(); j++) {
                    DataType a = types.get(i);
                    DataType b = types.get(j);
                    if (a != DataType.DATE && a != DataType.TIMESTAMP && b != DataType.DATE && b != DataType.TIMESTAMP) {
                        continue;
                    }
                    for (Expression.ArithmeticOp op : Expression.ArithmeticOp.values()) {
                        String sql = "SELECT c" + i + " " + op.symbol() + " c" + j + " FROM t";
                        String actual;
                        try (Statement probe = c.createStatement(); ResultSet rs = probe.executeQuery(sql)) {
                            actual = rs.getMetaData().getColumnTypeName(1);
                        } catch (SQLException e) {
                            actual = "ERROR";
                        }
                        String expected = expected(() -> op.resultType(a, b));
                        boolean interval = actual.equals("INTERVAL") && expected.equals("ERROR");
                        if (!actual.equals(expected) && !interval) {
                            mismatches.add(a.sqlName() + " " + op.symbol() + " " + b.sqlName() + ": DuckDB " + actual
                                    + ", rules " + expected);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    /**
     * The common type of two columns, as a {@code CASE} unites them, must be
     * the one {@link TypeRules#common} gives, for every pair of scalar types.
     */
    @Test
    void commonTypes() throws SQLException {
        List<DataType> types = new ArrayList<>(TYPES);
        types.add(DataType.VARCHAR);
        types.add(DataType.BOOLEAN);
        types.add(DataType.DATE);
        types.add(DataType.TIMESTAMP);
        types.add(DataType.BLOB);
        types.add(DataType.UUID);
        List<String> mismatches = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            StringBuilder create = new StringBuilder("CREATE TABLE t (");
            for (int i = 0; i < types.size(); i++) {
                create.append(i > 0 ? ", " : "").append("c").append(i).append(' ').append(types.get(i).sqlName());
            }
            s.execute(create.append(")").toString());
            for (int i = 0; i < types.size(); i++) {
                for (int j = 0; j < types.size(); j++) {
                    DataType a = types.get(i);
                    DataType b = types.get(j);
                    String sql = "SELECT CASE WHEN c0 IS NULL THEN c" + i + " ELSE c" + j + " END FROM t";
                    String actual;
                    try (Statement probe = c.createStatement(); ResultSet rs = probe.executeQuery(sql)) {
                        actual = rs.getMetaData().getColumnTypeName(1);
                    } catch (SQLException e) {
                        actual = "ERROR";
                    }
                    String expected = expected(() -> TypeRules.common(a, b));
                    if (!actual.equals(expected)) {
                        mismatches.add(a.sqlName() + " with " + b.sqlName() + ": DuckDB " + actual + ", rules "
                                + expected);
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    /** The result types of the aggregates must be the ones {@link PlanNode.AggregateCall#type} gives. */
    @Test
    void aggregateResultTypes() throws SQLException {
        List<DataType> types = new ArrayList<>(TYPES);
        types.add(DataType.VARCHAR);
        types.add(DataType.DATE);
        types.add(DataType.BOOLEAN);
        List<String> mismatches = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            StringBuilder create = new StringBuilder("CREATE TABLE t (");
            for (int i = 0; i < types.size(); i++) {
                create.append(i > 0 ? ", " : "").append("c").append(i).append(' ').append(types.get(i).sqlName());
            }
            s.execute(create.append(")").toString());
            Schema schema = new Schema(types.stream().map(t -> new Schema.Field(t.sqlName(), t)).toList());
            for (int i = 0; i < types.size(); i++) {
                for (PlanNode.AggregateFunction f : PlanNode.AggregateFunction.values()) {
                    if (f == PlanNode.AggregateFunction.COUNT_STAR) {
                        continue;
                    }
                    // DuckDB also offers avg(DATE) as a TIMESTAMP and sum(BOOLEAN) as a count; FyrQL does not.
                    if (f == PlanNode.AggregateFunction.AVG && types.get(i) == DataType.DATE
                            || f == PlanNode.AggregateFunction.SUM && types.get(i) == DataType.BOOLEAN) {
                        continue;
                    }
                    String expected;
                    try {
                        expected = new PlanNode.AggregateCall(f, i, false, "x").type(schema).sqlName();
                        new PlanNode.AggregateCall(f, i, false, "x").check(schema);
                    } catch (IllegalArgumentException e) {
                        expected = "ERROR";
                    }
                    String sql = "SELECT " + f.sqlName() + "(c" + i + ") FROM t";
                    String actual;
                    String detail = "";
                    // A failed query closes its statement in the DuckDB driver, so every probe gets a fresh one.
                    try (Statement probe = c.createStatement(); ResultSet rs = probe.executeQuery(sql)) {
                        actual = rs.getMetaData().getColumnTypeName(1);
                    } catch (SQLException e) {
                        actual = "ERROR";
                        detail = " (" + e.getMessage().split("\n")[0] + ")";
                    }
                    if (!actual.equals(expected)) {
                        mismatches.add(f.sqlName() + "(" + types.get(i).sqlName() + "): DuckDB " + actual + detail
                                + ", rules " + expected);
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    private static String expected(java.util.function.Supplier<DataType> rule) {
        try {
            return rule.get().sqlName();
        } catch (IllegalArgumentException e) {
            return "ERROR";
        }
    }

    private static void check(Statement s, List<String> mismatches, DataType a, DataType b, String op,
            String expected) throws SQLException {
        // The table is empty; the type of the expression comes from the metadata of the result.
        String sql = "SELECT c" + TYPES.indexOf(a) + " " + op + " c" + TYPES.indexOf(b) + " FROM t";
        String actual;
        try (Statement probe = s.getConnection().createStatement(); ResultSet rs = probe.executeQuery(sql)) {
            actual = rs.getMetaData().getColumnTypeName(1);
        } catch (SQLException e) {
            actual = "ERROR";
        }
        if (!actual.equals(expected)) {
            mismatches.add(a.sqlName() + " " + op + " " + b.sqlName() + ": DuckDB " + actual + ", rules " + expected);
        }
    }
}
