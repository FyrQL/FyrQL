/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.test;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Prints how DuckDB treats a few edge cases, for the record in the test
 * output: negative zero in storage and arithmetic, integer division of
 * negative numbers, and the remainder, and the nested values.
 */
class DuckDbSemanticsTest {

    @Test
    void probe() throws SQLException {
        List<String> queries = List.of(
                "SELECT CAST('-0.0' AS DOUBLE)",
                "SELECT -(0.0::DOUBLE)",
                "SELECT (-1.0)::DOUBLE * 0.0::DOUBLE",
                "SELECT CAST('-0.0' AS DOUBLE) = 0.0::DOUBLE",
                "SELECT CAST('-0.0' AS FLOAT)",
                "SELECT -7 // 2, 7 // -2, -7 % 2, 7 % -2",
                "SELECT (-7.5)::DOUBLE // 2.0::DOUBLE, 7.5::DOUBLE % (-2.0)::DOUBLE",
                "SELECT 7.5::DECIMAL(3,1) // 2::DECIMAL(3,0)",
                "SELECT round(2.5::DOUBLE), round(-2.5::DOUBLE), round(0.285::DOUBLE, 2)",
                "SELECT round(2.5::DECIMAL(3,1)), typeof(round(2.5::DECIMAL(3,1))), round(2.55::DECIMAL(4,2), 1), typeof(round(2.55::DECIMAL(4,2), 1))",
                "SELECT 'a' LIKE 'a\\%', 'a%' LIKE 'a\\%', '_' LIKE '\\_'",
                "SELECT substring('hello', 0, 2), substring('hello', -1, 3), substring('hello', 2)",
                "SELECT concat('a', NULL, 1), 'a' || NULL");
        print(queries);
    }

    /**
     * The nested values: the types of list and struct literals, positions
     * of lists, fields of structs in any case, the shape of a lookup in a
     * map, and the order of list_sort.
     */
    @Test
    void nestedValues() throws SQLException {
        print(List.of(
                "SELECT typeof([1, 2.5]), typeof([]), typeof([NULL]), typeof({'a': NULL}), typeof(['a', 1])",
                "SELECT typeof([1::TINYINT, 300]), typeof(['x', 'y', 1.5]), typeof([[1], [2.5]])",
                "SELECT ([10, 20, 30])[0], ([10, 20, 30])[-1], ([10, 20, 30])[4], ([10, 20, 30])['1'], 'abc'[0]",
                "SELECT ({'a': 1, 'B': 2}).b, ({'a': 1, 'B': 2})['A'], typeof({'Ab': 1}), typeof({'name': 1})",
                "SELECT map_extract(MAP {'k': 1}, 'k')::VARCHAR, map_extract(MAP {'k': 1}, 'x')::VARCHAR,"
                        + " (MAP {'k': 1})['k'], (MAP {'k': 1})['K'], (MAP {1: 'a'})['1']",
                "SELECT len([1, 2]), len(NULL), list_contains([1, NULL], NULL), list_contains([1, NULL], 3)",
                "SELECT list_sort([3, NULL, 1])::VARCHAR, list_sort([3, NULL, 1], 'DESC')::VARCHAR,"
                        + " list_sort([3, NULL, 1], 'ASC', 'NULLS FIRST')::VARCHAR",
                "SELECT typeof(CASE WHEN true THEN {'a': 1} ELSE {'b': 2} END), {'a': 1} = {'a': 1, 'b': NULL}",
                "SELECT t.a FROM (SELECT {'a': 1} AS t, 5 AS a) t",
                "SELECT ([1, 2, 3])[2:3]::VARCHAR, [1, 2] = '[1, 2]'"));
    }

    /** Prints each query with its first row, or its error. */
    private static void print(List<String> queries) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (String q : queries) {
                StringBuilder line = new StringBuilder(q).append(" -> ");
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
                    rs.next();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        line.append(i > 1 ? ", " : "").append(v);
                        if (v instanceof Double d) {
                            line.append(" [bits ").append(Long.toHexString(Double.doubleToRawLongBits(d))).append(']');
                        }
                        if (v instanceof Float f) {
                            line.append(" [bits ").append(Integer.toHexString(Float.floatToRawIntBits(f))).append(']');
                        }
                    }
                } catch (SQLException e) {
                    line.append("ERROR ").append(e.getMessage().split("\n")[0]);
                }
                System.out.println("PROBE " + line);
            }
        }
    }
}
