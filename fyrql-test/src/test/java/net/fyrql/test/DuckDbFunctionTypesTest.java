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

import net.fyrql.core.expr.Expressions;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.function.ScalarFunction;
import net.fyrql.core.type.DataType;
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
 * Checks the signatures of the function catalog against DuckDB: for every
 * function of the registry and every combination of argument types from a
 * sample, DuckDB must accept the call with the result type that the
 * registry gives, or both must reject it. Columns, not literals, so that
 * DuckDB cannot pick a type from a value.
 */
class DuckDbFunctionTypesTest {

    private static final List<DataType> TYPES = List.of(DataType.TINYINT, DataType.SMALLINT, DataType.INTEGER,
            DataType.BIGINT, DataType.HUGEINT, DataType.FLOAT, DataType.DOUBLE, DataType.decimal(5, 2),
            DataType.decimal(20, 2), DataType.VARCHAR, DataType.BOOLEAN, DataType.DATE, DataType.TIMESTAMP);

    /** A smaller sample for three arguments. */
    private static final List<DataType> TYPES3 = List.of(DataType.INTEGER, DataType.BIGINT, DataType.DOUBLE,
            DataType.decimal(5, 2), DataType.VARCHAR, DataType.DATE, DataType.TIMESTAMP);

    /**
     * Calls that differ on purpose: {@code trunc(x, n)} to a number of
     * places, which DuckDB offers for a count up to {@code INTEGER} and
     * FyrQL leaves out; and {@code strftime}, {@code strptime} and
     * {@code try_strptime}, which DuckDB rejects for a format that is not a
     * constant, as every column is; and {@code round} of a decimal (the
     * columns {@code c7} and {@code c8}) to a number of places, which both
     * engines take for a constant count only, FyrQL in
     * {@code ScalarFunction.specialize}, which a probe of types does not
     * reach.
     */
    private static boolean known(String call) {
        return call.startsWith("trunc(") && call.contains(",")
                || call.startsWith("strftime(") || call.startsWith("strptime(") || call.startsWith("try_strptime(")
                || call.startsWith("round(c7, ") || call.startsWith("round(c8, ");
    }

    @Test
    void functionResultTypes() throws SQLException {
        FunctionRegistry registry = Expressions.functions();
        List<String> mismatches = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            StringBuilder create = new StringBuilder("CREATE TABLE t (");
            for (int i = 0; i < TYPES.size(); i++) {
                create.append(i > 0 ? ", " : "").append("c").append(i).append(' ').append(TYPES.get(i).sqlName());
            }
            s.execute(create.append(")").toString());
            for (String name : registry.names()) {
                if (name.equals("||")) {
                    for (DataType a : TYPES) {
                        for (DataType b : TYPES) {
                            check(c, registry, mismatches, name, List.of(a, b), column(a) + " || " + column(b));
                        }
                    }
                    continue;
                }
                check(c, registry, mismatches, name, List.of(), name + "()");
                for (DataType a : TYPES) {
                    check(c, registry, mismatches, name, List.of(a), name + "(" + column(a) + ")");
                    for (DataType b : TYPES) {
                        check(c, registry, mismatches, name, List.of(a, b),
                                name + "(" + column(a) + ", " + column(b) + ")");
                    }
                }
                for (DataType a : TYPES3) {
                    for (DataType b : TYPES3) {
                        for (DataType d : TYPES3) {
                            check(c, registry, mismatches, name, List.of(a, b, d),
                                    name + "(" + column(a) + ", " + column(b) + ", " + column(d) + ")");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }

    private static String column(DataType type) {
        return "c" + TYPES.indexOf(type);
    }

    private static void check(Connection c, FunctionRegistry registry, List<String> mismatches, String name,
            List<DataType> types, String call) throws SQLException {
        String expected;
        try {
            ScalarFunction f = registry.resolve(name, types);
            expected = f.resultType(types).sqlName();
        } catch (IllegalArgumentException e) {
            expected = "ERROR";
        }
        String actual;
        // A failed query closes its statement in the DuckDB driver, so every probe gets a fresh one.
        try (Statement probe = c.createStatement(); ResultSet rs = probe.executeQuery("SELECT " + call + " FROM t")) {
            actual = rs.getMetaData().getColumnTypeName(1);
        } catch (SQLException e) {
            actual = "ERROR";
        }
        if (!actual.equals(expected) && !known(call)) {
            mismatches.add(call + ": DuckDB " + actual + ", registry " + expected);
        }
    }
}
