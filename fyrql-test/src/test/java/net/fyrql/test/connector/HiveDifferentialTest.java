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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Parquet and CSV files in directories of the form {@code key=value}, which
 * DuckDB writes with {@code PARTITION_BY}, with {@code NULL} partitions and
 * values that need escapes in a directory name: queries over them with
 * filters on the partitions, on the files and on both, aggregates, a limit
 * and the options of the partitions give the rows of DuckDB, in the run
 * with pushdown, where filters on partitions skip files, and in the one
 * without.
 */
class HiveDifferentialTest {

    @TempDir
    Path dir;

    private String path(String name) {
        return dir.resolve(name).toString().replace('\\', '/');
    }

    @Test
    void queriesOverPartitionedFilesGiveTheRowsOfDuckDb() {
        List<String> failures = new ArrayList<>();
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder().register(ParquetConnector.builder("pq").batchSize(300).build())
                        .register(CsvConnector.builder("csv").batchSize(300).build()).build()) {
            duckdb.execute("CREATE TABLE s AS SELECT i AS id, CASE WHEN i % 17 = 0 THEN NULL ELSE 2020 + i % 4 END"
                    + " AS year, 1 + i % 3 AS month, ['north', 'south east', 'x/y', 'c=d'][1 + i % 4] AS region,"
                    + " CAST(round(i * 1.37, 2) AS DECIMAL(10, 2)) AS amount, DATE '2024-01-01' + CAST(i % 5 AS INTEGER) AS day,"
                    + " CASE WHEN i % 7 = 0 THEN NULL ELSE 'n' || (i % 11) END AS note FROM range(2000) t(i)");
            duckdb.execute("COPY s TO '" + path("pq") + "' (FORMAT parquet, PARTITION_BY (year, region))");
            duckdb.execute("COPY s TO '" + path("days") + "' (FORMAT parquet, PARTITION_BY (day))");
            duckdb.execute("COPY s TO '" + path("csv") + "' (FORMAT csv, PARTITION_BY (year, month))");
            String p = "read_parquet('" + path("pq") + "')";
            String c = "read_csv('" + path("csv") + "')";
            List<String> queries = List.of(
                    "SELECT * FROM " + p,
                    "SELECT id, year, region FROM " + p + " WHERE year = 2021",
                    "SELECT id, region FROM " + p + " WHERE year IS NULL",
                    "SELECT * FROM " + p + " WHERE region = 'x/y' AND year > 2021",
                    "SELECT id, amount FROM " + p + " WHERE year = 2022 OR amount > 2500",
                    "SELECT count(*) FROM " + p + " WHERE region IN ('north', 'c=d')",
                    "SELECT count(*) FROM " + p,
                    "SELECT year, region, count(*), sum(amount), min(note) FROM " + p + " GROUP BY ALL",
                    "SELECT * FROM " + p + " WHERE year = 2023 ORDER BY id LIMIT 5",
                    "SELECT id, year, region FROM " + p + " WHERE year = 2020 LIMIT 3000",
                    "SELECT * FROM read_parquet('" + path("pq") + "/**/*.parquet', hive_types={'year': 'SMALLINT'})"
                            + " WHERE year = 2020",
                    "SELECT * FROM read_parquet('" + path("pq") + "/year=2021/*/*.parquet')",
                    "SELECT * FROM read_parquet('" + path("pq") + "', hive_partitioning=false)",
                    "SELECT * FROM read_parquet('" + path("pq") + "', hive_types_autocast=false) WHERE year = '2021'",
                    "SELECT day, count(*) FROM read_parquet('" + path("days") + "') WHERE day >= DATE '2024-01-03'"
                            + " GROUP BY day",
                    "SELECT * FROM " + c,
                    "SELECT id, note FROM " + c + " WHERE month = 2 AND year = 2023",
                    "SELECT * FROM " + c + " WHERE year IS NULL AND month = 3",
                    "SELECT year, count(*), round(sum(amount), 4) FROM " + c + " GROUP BY year",
                    "SELECT * FROM read_csv('" + path("csv") + "/year=2021/**/*.csv')",
                    "SELECT * FROM read_csv('" + path("csv") + "', hive_partitioning=false)",
                    "SELECT * FROM read_csv('" + path("csv") + "', hive_types={'month': 'VARCHAR'}) WHERE month = '1'");
            for (String sql : queries) {
                try {
                    PlanNode plan = engine.plan(sql);
                    Schema schema = plan.schema();
                    ResultComparison.Outcome outcome = ResultComparison.compare(schema, duckdb.query(sql, schema),
                            engine.rows(plan), Plans.ordered(plan));
                    if (!outcome.same()) {
                        failures.add(sql + "\n" + outcome.report() + "\n" + engine.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
            if (engine.pushdown()) {
                String sql = "SELECT id FROM " + p + " WHERE year = 2021";
                String plan = engine.explain(sql);
                assertTrue(plan.contains("[pushed: WHERE (#1 = "), plan);
                String analyzed = engine.explainAnalyze(sql);
                // Year and region both follow i % 4: four files and those of the years that are NULL.
                assertTrue(analyzed.contains("1 of 8 files"), analyzed);
                analyzed = engine.explainAnalyze("SELECT id FROM " + c + " WHERE month = 2");
                assertTrue(analyzed.contains("5 of 15 files"), analyzed);
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " queries differ:\n\n" + String.join("\n\n", failures));
        }
    }
}
