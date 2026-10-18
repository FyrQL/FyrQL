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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Events in a CSV file with a byte order mark and quoted delimiters,
 * quotes and line breaks, and users in a file of JSON lines, some of them
 * over several lines, with braces in their strings and line ends of LF
 * and of CR LF, read in parts of 16 KiB on four threads. Every query gives
 * what one thread gives that reads each file in one part, and what DuckDB
 * gives for the same files.
 */
class FileSplitFederationTest {

    private static final int EVENTS = 20_000;
    private static final int USERS = 5_000;
    private static final long SPLIT_SIZE = 16 << 10;

    private static final List<String> QUERIES = List.of(
            "SELECT count(*), sum(amount), min(day), max(id), count(kind), count(user_id) FROM events",
            "SELECT * FROM events WHERE id % 1000 = 7",
            "SELECT kind, count(*), sum(amount) FROM events GROUP BY kind",
            "SELECT id, kind FROM events WHERE kind LIKE 'two%' ORDER BY id DESC LIMIT 20",
            "SELECT id, user_id FROM events LIMIT 10",
            "SELECT id, row_number() OVER (PARTITION BY kind ORDER BY day, id) FROM events WHERE id < 3000",
            "SELECT name, addr.city, addr.zip, len(tags), note FROM users WHERE id % 500 = 3",
            "SELECT addr.city, count(*), count(note), max(id) FROM users GROUP BY addr.city",
            "SELECT id, note FROM users WHERE note LIKE '%}%' ORDER BY id LIMIT 30",
            "SELECT u.name, count(*), sum(e.amount) FROM events e JOIN users u ON e.user_id = u.id"
                    + " WHERE e.day >= DATE '2024-11-01' GROUP BY u.name",
            "SELECT u.addr.city, e.kind, count(*) FROM users u LEFT JOIN events e ON e.user_id = u.id"
                    + " AND e.amount > 9900 GROUP BY u.addr.city, e.kind");

    @TempDir
    static Path dir;

    private static Path events;
    private static Path users;
    private static Path bad;

    @BeforeAll
    static void writeFiles() throws IOException {
        StringBuilder csv = new StringBuilder("\uFEFFid,user_id,kind,amount,day\n");
        for (int i = 0; i < EVENTS; i++) {
            String kind = switch (i % 7) {
                case 0 -> "\"two\nlines\"";
                case 1 -> "\"a, b\"";
                case 2 -> "\"say \"\"hi\"\"\"";
                case 3 -> "";
                default -> "k" + i % 5;
            };
            csv.append(i).append(',').append(i % 11 == 0 ? "" : String.valueOf(i % 600)).append(',').append(kind)
                    .append(',').append(i % 13 == 0 ? "" : String.valueOf(i * 37 % 10_000)).append(',')
                    .append(LocalDate.of(2024, 1, 1).plusDays(i % 366)).append('\n');
        }
        events = dir.resolve("events.csv");
        Files.writeString(events, csv, StandardCharsets.UTF_8);

        StringBuilder json = new StringBuilder();
        for (int i = 0; i < USERS; i++) {
            String note = switch (i % 5) {
                case 0 -> "\"x}\\n{y\"";
                case 1 -> "\"]\\r\\n[ \\\"q\\\"\"";
                case 2 -> "null";
                default -> "\"plain " + i % 4 + "\"";
            };
            String record = "{\"id\": " + i + ", \"name\": \"u" + i % 50 + "\", \"addr\": {\"city\": \"c" + i % 7
                    + "\", \"zip\": " + (1000 + i % 90) + "}, \"tags\": [\"t" + i % 3 + "\", \"s" + i % 2
                    + "\"], \"note\": " + note + "}";
            if (i % 9 == 0) {
                record = record.replace(", ", ",\n  ").replace("{\"id\"", "{\n  \"id\"");
            }
            json.append(record).append(i % 3 == 0 ? "\r\n" : "\n");
        }
        users = dir.resolve("users.jsonl");
        Files.writeString(users, json, StandardCharsets.UTF_8);

        StringBuilder wrong = new StringBuilder("id,amount\n");
        for (int i = 0; i < EVENTS; i++) {
            wrong.append(i).append(',').append(i == 15_000 ? "x" : String.valueOf(i)).append('\n');
        }
        bad = dir.resolve("bad.csv");
        Files.writeString(bad, wrong, StandardCharsets.UTF_8);
    }

    private static FyrQL engine(int threads, long splitSize) {
        return FyrQL.builder()
                .register(CsvConnector.builder("files").file("events", events).file("bad", bad).batchSize(512)
                        .splitSize(splitSize).build())
                .register(JsonConnector.builder("json").file("users", users).splitSize(splitSize).build())
                .threads(threads).build();
    }

    @Test
    void filesReadInPartsOnSeveralThreadsGiveWhatOneThreadAndDuckDbGive() {
        try (FyrQL parts = engine(4, SPLIT_SIZE); FyrQL whole = engine(1, 0); DuckDbReference duck = new DuckDbReference()) {
            CsvConnector csv = (CsvConnector) parts.connector("files");
            JsonConnector json = (JsonConnector) parts.connector("json");
            int csvSplits = csv.splits(csv.plan(TableRef.of("files", "events"), ScanRequest.columns(List.of(0))))
                    .size();
            int jsonSplits = json.splits(json.plan(TableRef.of("json", "users"), ScanRequest.columns(List.of(0))))
                    .size();
            assertTrue(csvSplits > 10 && jsonSplits > 10, csvSplits + " and " + jsonSplits + " splits");
            duck.execute("CREATE VIEW events AS SELECT * FROM read_csv('" + path(events)
                    + "', delim = ',', quote = '\"', escape = '\"', header = true)");
            duck.execute("CREATE VIEW users AS SELECT * FROM read_json('" + path(users) + "')");
            List<String> failures = new ArrayList<>();
            for (String sql : QUERIES) {
                try {
                    PlanNode plan = parts.plan(sql);
                    Schema schema = plan.schema();
                    boolean ordered = Plans.ordered(plan);
                    List<Object[]> actual = parts.rows(plan);
                    ResultComparison.Outcome threads = ResultComparison.compare(schema, whole.rows(whole.plan(sql)),
                            actual, ordered);
                    if (!threads.same()) {
                        failures.add(sql + "\n  in parts and as a whole differ:\n" + threads.report());
                        continue;
                    }
                    ResultComparison.Outcome oracle = ResultComparison.compare(schema, duck.query(sql, schema), actual,
                            ordered);
                    if (!oracle.same()) {
                        failures.add(sql + "\n" + oracle.report() + "\n" + parts.explain(plan));
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
            if (!failures.isEmpty()) {
                fail(failures.size() + " of " + QUERIES.size() + " queries differ:\n\n"
                        + String.join("\n\n", failures));
            }
        }
    }

    @Test
    void aFieldThatDoesNotParseInAPartIsTheErrorThatOneThreadReports() {
        try (FyrQL parts = engine(4, SPLIT_SIZE); FyrQL whole = engine(1, 0)) {
            String expected = assertThrows(RuntimeException.class, () -> whole.rows("SELECT sum(amount) FROM bad"))
                    .getMessage();
            assertTrue(expected.contains("Line 15002 of"), expected);
            assertEquals(expected,
                    assertThrows(RuntimeException.class, () -> parts.rows("SELECT sum(amount) FROM bad")).getMessage());
        }
    }

    private static String path(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
