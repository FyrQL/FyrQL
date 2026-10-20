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
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads files through table functions and file names in {@code FROM}: the
 * query is parsed and bound, which binds the call in the catalog of the
 * engine, and run. DuckDB runs the same text; columns, types and rows must
 * agree.
 */
class TableFunctionTest {

    @TempDir
    Path dir;

    private String file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p.toString().replace('\\', '/');
    }

    @Test
    void readCsvAndFileNamesAsInDuckDb() throws IOException, SQLException {
        String typed = file("typed.csv", "id,name,amount,day,ok,ts\n"
                + "1,alpha,1.5,2024-01-02,true,2024-01-02 03:04:05\n"
                + "2,,2.25,,false,\n"
                + "3,\"x, y\",-3,2024-12-31,TRUE,2024-12-31 23:59:59.5\n");
        String semi = file("semi.csv", "a;b\n1;x\n2;NA\n");
        String noHeader = file("noheader.csv", "1,x\n2,y\n");
        String textFirst = file("textfirst.csv", "x,1\na,2\n");
        String quoted = file("quoted.csv", "a,b\n1,'x, y'\n2,'it''s'\n");
        String tsv = file("t.tsv", "a\tb\n1\tx\n2\t\n");
        String strings = file("strings.csv", "s,n\nx,1\n\"\",2\n,3\n");
        List<String> queries = List.of(
                "SELECT * FROM read_csv('" + typed + "')",
                "SELECT * FROM '" + typed + "'",
                "SELECT * FROM read_csv_auto('" + typed + "') AS t",
                "SELECT * FROM read_csv('" + semi + "', delim = ';', nullstr = 'NA')",
                "SELECT * FROM read_csv('" + semi + "', SEP = ';')",
                "SELECT * FROM read_csv('" + semi + "', delim = ',', delim = ';')",
                "SELECT * FROM read_csv('" + noHeader + "')",
                "SELECT * FROM read_csv('" + noHeader + "', header = false)",
                "SELECT * FROM read_csv('" + noHeader + "', header = true)",
                "SELECT * FROM read_csv('" + noHeader + "', header = 'true')",
                "SELECT * FROM read_csv('" + textFirst + "')",
                "SELECT * FROM read_csv('" + quoted + "', quote = '''')",
                "SELECT * FROM '" + tsv + "'",
                "SELECT * FROM read_csv('" + tsv + "', delim = '\\t')",
                "SELECT * FROM read_csv('" + strings + "')",
                "SELECT typed.name, amount * 2 FROM '" + typed + "' WHERE ok ORDER BY id DESC",
                "SELECT r.id, s.b FROM read_csv('" + typed + "') AS r JOIN read_csv('" + semi + "', delim = ';') AS s"
                        + " ON r.id = s.a",
                "SELECT count(*), sum(amount), max(day) AS latest FROM read_csv('" + typed + "', header = 1)");
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder()
                        .register(MemoryConnector.builder("mem").build())
                        .register(CsvConnector.builder("files").build())
                        .implicitSources(false)
                        .build()) {
            Binder binder = new Binder(engine.catalog(), FunctionRegistry.standard());
            for (String sql : queries) {
                PlanNode plan = binder.bind(SqlParser.parse(sql));
                Schema schema = plan.schema();
                assertEquals(describe(duckdb, sql), columns(schema), sql);
                ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                        engine.rows(plan), Plans.ordered(plan));
                assertTrue(outcome.same(), sql + "\n" + outcome.report());
            }
            SqlException e = assertThrows(SqlException.class, () -> binder.bind(SqlParser.parse(
                    "SELECT * FROM read_csv('" + dir.toString().replace('\\', '/') + "/no.csv')")));
            assertTrue(e.reason().startsWith("No files found that match the pattern"), e.getMessage());
            assertEquals("Table function with name read_parquet does not exist; put fyrql-connector-parquet on the"
                    + " class path or register a source of the type parquet; there are read_csv, read_csv_auto",
                    assertThrows(SqlException.class, () -> binder.bind(SqlParser.parse("SELECT * FROM 'x.parquet'")))
                            .reason());
        }
    }

    private static String columns(Schema schema) {
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            DataType type = schema.type(c);
            columns.add(schema.name(c) + " " + type.sqlName());
        }
        return String.join(", ", columns);
    }

    /** The columns DuckDB gives a query, from DESCRIBE. */
    private static String describe(DuckDbReference duckdb, String sql) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement s = duckdb.connection().createStatement(); ResultSet rs = s.executeQuery("DESCRIBE " + sql)) {
            while (rs.next()) {
                columns.add(rs.getString("column_name") + " " + rs.getString("column_type"));
            }
        }
        return String.join(", ", columns);
    }
}
