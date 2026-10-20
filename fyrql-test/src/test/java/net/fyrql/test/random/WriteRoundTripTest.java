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

package net.fyrql.test.random;

import net.fyrql.FyrQL;
import net.fyrql.QueryResult;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.exec.CodegenMode;
import net.fyrql.exec.Executor;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.sql.render.SqlRenderer;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.WrittenFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The results of random plans, written with {@code COPY ... TO} into a CSV,
 * a JSON and a Parquet file: DuckDB reads each file and gets the rows of
 * the query, in their order if the plan sorts its output. The columns are
 * renamed {@code c0}, {@code c1} and so on, since a result may have two
 * columns of the same name. A plan that fails in FyrQL is left out, and so
 * is a Parquet file of a result with a {@code HUGEINT} of 39 digits, which
 * a {@code DECIMAL(38,0)} does not hold; the report counts both. No
 * temporary file is left behind.
 */
class WriteRoundTripTest {

    private static final List<String> FORMATS = List.of("csv", "json", "parquet");

    private final SqlRenderer renderer = new SqlRenderer(DuckDbDialect.INSTANCE,
            t -> DuckDbDialect.INSTANCE.quoteIdentifier(t.table()));

    @TempDir
    Path dir;

    @Test
    void duckDbReadsTheRowsOfRandomQueriesFromTheirFiles() throws IOException {
        int checked = 0;
        int failed = 0;
        int tooLarge = 0;
        long rowsWritten = 0;
        List<String> unexplained = new ArrayList<>();
        try (FyrQL engine = RandomTables.engine(RandomTables.rows(RandomRun.SEED), CodegenMode.OFF,
                Executor.threadsFromSystemProperty(), List.of(CsvConnector.builder("csv").build(),
                        JsonConnector.builder("json").build(), ParquetConnector.builder("parquet").build()));
                DuckDbReference duck = new DuckDbReference()) {
            for (int i = 0; i < RandomRun.QUERIES; i++) {
                PlanNode plan = renamed(new PlanGenerator(RandomRun.random(i)).plan(3));
                boolean ordered = Plans.ordered(plan);
                String sql = renderer.render(plan);
                Schema schema;
                List<Object[]> rows;
                try (QueryResult r = engine.sql(sql)) {
                    schema = r.schema();
                    rows = r.rows();
                } catch (RuntimeException | StackOverflowError e) {
                    failed++;
                    continue;
                }
                for (String format : FORMATS) {
                    Path file = dir.resolve("result." + format);
                    long count;
                    try {
                        count = engine.update("COPY (" + sql + ") TO '" + WrittenFiles.path(file) + "'");
                    } catch (SqlException e) {
                        if (format.equals("parquet") && e.getMessage().contains(" has more than 38 digits; a Parquet"
                                + " file holds a HUGEINT as a DECIMAL(38,0)")) {
                            tooLarge++;
                            continue;
                        }
                        unexplained.add("query " + i + " to " + format + ": " + e.getMessage() + "\n  sql: " + sql);
                        continue;
                    } catch (RuntimeException e) {
                        unexplained.add("query " + i + " to " + format + ": " + e + "\n  sql: " + sql);
                        continue;
                    }
                    if (count != rows.size()) {
                        unexplained.add("query " + i + " to " + format + ": " + count + " rows written, the query"
                                + " gives " + rows.size() + "\n  sql: " + sql);
                        continue;
                    }
                    String report;
                    try {
                        ResultComparison.Outcome same = WrittenFiles.duckDb(duck, file, format, schema, rows)
                                .compare(ordered);
                        report = same.same() ? null : same.report();
                    } catch (RuntimeException e) {
                        report = e.getMessage() + (e.getCause() == null ? "" : ": " + e.getCause().getMessage());
                    }
                    if (report != null) {
                        unexplained.add("query " + i + " to " + format + ":\n" + report.indent(2) + "  sql: " + sql);
                        continue;
                    }
                    rowsWritten += count;
                }
                checked++;
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(), files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".tmp"))
                    .toList(), "temporary files");
        }
        String report = "Random results written: " + RandomRun.QUERIES + " queries, seed " + RandomRun.SEED + ", "
                + checked + " results in " + FORMATS.size() + " formats with " + rowsWritten + " rows read back, "
                + failed + " failed in FyrQL, " + tooLarge + " not in Parquet with a HUGEINT of 39 digits\n"
                + "  unexplained: " + unexplained.size() + "\n" + String.join("\n", unexplained.stream()
                        .limit(20).toList());
        System.out.println(report);
        assertTrue(unexplained.isEmpty(), report);
        assertTrue(checked > RandomRun.QUERIES / 2, checked + " of " + RandomRun.QUERIES + " results checked");
    }

    /** Returns the plan with its columns named {@code c0}, {@code c1} and so on. */
    private static PlanNode renamed(PlanNode plan) {
        List<PlanNode.NamedExpression> columns = new ArrayList<>();
        for (int c = 0; c < plan.schema().size(); c++) {
            columns.add(new PlanNode.NamedExpression("c" + c, new Expression.Column(c, plan.schema().type(c))));
        }
        return new PlanNode.Project(plan, columns);
    }
}
