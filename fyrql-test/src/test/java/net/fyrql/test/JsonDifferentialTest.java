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
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.core.function.FunctionRegistry;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.Plans;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.Binder;
import net.fyrql.sql.parse.SqlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads JSON files with {@code read_json} and file names in {@code FROM},
 * and DuckDB reads the same files with its own {@code read_json}: the
 * inferred columns and the rows must agree. DuckDB's type {@code JSON} is
 * FyrQL's {@code VARCHAR} of raw JSON text, and its {@code TIME} a
 * {@code VARCHAR}; the comparison maps the names of these types. JSON is
 * written with single quotes for readability.
 */
class JsonDifferentialTest {

    @TempDir
    Path dir;

    private String file(String name, String json) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, json.replace('\'', '"'), StandardCharsets.UTF_8);
        return p.toString().replace('\\', '/');
    }

    @Test
    void readJsonInfersAndReadsAsDuckDb() throws IOException, SQLException {
        String orders = file("orders.json", """
                [
                  {'id': 1, 'customer': {'name': 'Ann', 'tags': ['a', 'b']}, 'items': [{'sku': 'x', 'qty': 2}],
                   'day': '2024-01-02', 'stamp': '2024-01-02T03:04:05+02:00', 'price': 1.5, 'big': 9223372036854775808,
                   'uid': '123e4567-e89b-12d3-a456-426614174000', 'any': 1, 'ok': true, 'nothing': null,
                   'clock': '12:34:56', 'empty': [], 'obj': {}},
                  {'id': 2, 'customer': null, 'items': [], 'day': null, 'stamp': '2024-01-02T00:00:00-01:00',
                   'price': 2, 'big': 1, 'any': 'x', 'ok': null, 'clock': null, 'empty': [], 'obj': {}},
                  {'id': 3, 'customer': {'name': null}, 'day': '2024-12-31', 'any': {'k': [1, 2.5, 'v']}, 'ok': false,
                   'items': [{'qty': 1}, {'sku': 'y', 'extra': true}], 'price': -0.0}
                ]""");
        String events = file("events.jsonl", "{'n': 1, 'kind': 'a', 'ts': '2024-05-06 07:08:09.5'}\n"
                + "{'n': 2, 'kind': 'b', 'ts': '2024-05-06 07:08:10.25'}\n"
                + "{'n': 3, 'kind': 'a', 'ts': null}\n");
        String mixed = file("mixed.json", "[{'a': 1, 'b': 2.5}, {'a': 'x', 'b': 3}, {'a': [1, 'y'], 'b': null},"
                + " {'a': {'z': [true, null]}, 'b': 1e2}]");
        String values = file("values.json", "[[1, 2], [3], [], null]");
        String scalars = file("scalars.ndjson", "1\n2\n3\n");
        String maps = file("maps.json", IntStream.range(0, 20)
                .mapToObj(i -> "{'id': " + i + ", 'm': {'k" + i + "': " + i + "}}")
                .collect(Collectors.joining("\n")));
        String wide = file("wide.json", "[{'m': {'k1': 1, 'k2': 2, 'k3': 3}}, {'m': {'k4': 2.5}}]");
        String dates = file("dates.json", "[{'d': '02.01.2020', 't': '02.01.2020 03:04'},"
                + " {'d': '31.12.1999', 't': '31.12.1999 23:59'}]");
        String names = file("names.json", "[{'A': 1, 'a': 2, '': 3}]");
        String sampled = file("sampled.json", "[{'a': 1}, {'a': 2.5}, {'a': 'x', 'b': 1}, {'a': 4}]");
        Path gz = dir.resolve("packed.json.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("[{'z': 'zipped', 'n': 7}]".replace('\'', '"').getBytes(StandardCharsets.UTF_8));
        }
        String packed = gz.toString().replace('\\', '/');
        String csv = file("customers.csv", "name,city\nAnn,Rome\nBob,Oslo\n");
        List<String> queries = List.of(
                "SELECT * FROM read_json('" + orders + "')",
                "SELECT * FROM '" + orders + "'",
                "SELECT * FROM read_json_auto('" + orders + "') AS o",
                "SELECT id, price FROM '" + orders + "' WHERE price > 1 ORDER BY id",
                "SELECT count(*), count(day), max(stamp), min(uid) FROM '" + orders + "'",
                "SELECT * FROM '" + events + "'",
                "SELECT kind, count(*), sum(n), max(ts) FROM read_ndjson('" + events + "') GROUP BY kind",
                "SELECT * FROM read_json('" + events + "', format = 'newline_delimited')",
                "SELECT * FROM '" + mixed + "'",
                "SELECT * FROM '" + values + "'",
                "SELECT * FROM read_json('" + values + "', format = 'array')",
                "SELECT * FROM '" + scalars + "'",
                "SELECT * FROM '" + maps + "'",
                "SELECT * FROM read_json('" + maps + "', field_appearance_threshold = 0.01)",
                "SELECT * FROM '" + wide + "'",
                "SELECT * FROM read_json('" + wide + "', map_inference_threshold = 2)",
                "SELECT * FROM '" + dates + "'",
                "SELECT * FROM read_json('" + dates + "', dateformat = '%d.%m.%Y', timestampformat = '%d.%m.%Y %H:%M')",
                "SELECT * FROM '" + names + "'",
                "SELECT * FROM read_json('" + sampled + "', sample_size = -1)",
                "SELECT * FROM read_json('" + sampled + "', sample_size = 2, ignore_errors = true)",
                "SELECT * FROM '" + packed + "'",
                "SELECT id, customer.name FROM '" + orders + "' ORDER BY id",
                "SELECT o.customer.name, o.customer.tags, len(o.items) FROM '" + orders + "' AS o"
                        + " WHERE o.customer.name IS NOT NULL",
                "SELECT customer['name'], customer FROM '" + orders + "' WHERE customer.name <> 'Bob'",
                "SELECT c.name, count(*) FROM (SELECT customer AS c FROM '" + orders + "') GROUP BY c.name",
                "SELECT o.id, c.city FROM '" + orders + "' AS o JOIN '" + csv + "' AS c ON o.customer IS NOT NULL"
                        + " AND c.name = 'Ann' ORDER BY o.id",
                "SELECT o.id, c.city FROM '" + orders + "' AS o JOIN '" + csv + "' AS c ON o.customer.name = c.name");
        try (DuckDbReference duckdb = new DuckDbReference();
                FyrQL engine = FyrQL.builder()
                        .register(JsonConnector.builder("json").build())
                        .register(CsvConnector.builder("csv").build())
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
        }
    }

    @Test
    void errorsNameTheRecordAndTheColumn() throws IOException {
        String sampled = file("sampled.json", "[{'a': 1, 'b': 1}, {'a': 2, 'b': 2}, {'a': 'x', 'b': 3}]");
        String unknown = file("unknown.json", "[{'a': 1}, {'b': 2}]");
        try (FyrQL engine = FyrQL.builder().register(JsonConnector.builder("json").build()).build()) {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> engine.rows("SELECT * FROM read_json('" + sampled + "', sample_size = 2)"));
            assertTrue(message(e).contains("Record 3 of ") && message(e).contains("column a: cannot read \"x\" as "
                    + "BIGINT") && message(e).contains("sample_size = -1 infers it from all of them"), message(e));
            // The query reads only the column b, so the value of a that does not fit is skipped unread.
            assertEquals(6L, ((Number) engine.rows("SELECT sum(b) FROM read_json('" + sampled
                    + "', sample_size = 2)").get(0)[0]).longValue());
            e = assertThrows(RuntimeException.class,
                    () -> engine.rows("SELECT * FROM read_json('" + unknown + "', sample_size = 1)"));
            assertTrue(message(e).contains("Record 2 of ") && message(e).contains("the object has the key \"b\", "
                    + "which is not a column"), message(e));
            SqlException bad = assertThrows(SqlException.class,
                    () -> engine.rows("SELECT * FROM read_json('" + unknown + "', header = true)"));
            assertTrue(bad.reason().startsWith("Invalid named parameter \"header\" for function read_json"),
                    bad.reason());
        }
    }

    @Test
    void ofAStructOnlyTheFieldsAQueryUsesAreRead() throws IOException {
        String people = file("people.json", "[{'id': 1, 'p': {'name': 'Ann', 'age': 30,"
                + " 'home': {'city': 'Rome', 'zip': 1}}},"
                + " {'id': 2, 'p': {'name': 'Bob', 'age': 'unknown', 'home': {'city': 'Oslo', 'zip': [2]}}}]");
        String sql = "SELECT id, p.name, p.home.city FROM read_json('" + people + "', sample_size = 1)"
                + " WHERE p.name <> 'Cid' ORDER BY id";
        try (FyrQL on = FyrQL.builder().register(JsonConnector.builder("json").build()).pushdown(true).build();
                FyrQL off = FyrQL.builder().register(JsonConnector.builder("json").build()).pushdown(false).build()) {
            String explain = on.explain(sql);
            assertTrue(explain.contains("Scan json.main.#1 (id BIGINT, p.name VARCHAR, p.home.city VARCHAR)\n"),
                    explain);
            // The age and the zip of Bob do not fit their types, but are never read.
            List<Object[]> rows = on.rows(sql);
            assertEquals("[[1, Ann, Rome], [2, Bob, Oslo]]", rows.stream().map(Arrays::toString).toList().toString());
            // Without pushdown, the engine reads the whole struct.
            RuntimeException e = assertThrows(RuntimeException.class, () -> off.rows(sql));
            assertTrue(message(e).contains("column p: cannot read \"unknown\" as BIGINT"), message(e));
            String first = sql.replace(people, file("ann.json", "[{'id': 1, 'p': {'name': 'Ann', 'age': 30,"
                    + " 'home': {'city': 'Rome', 'zip': 1}}}]"));
            assertEquals("[[1, Ann, Rome]]", off.rows(first).stream().map(Arrays::toString).toList().toString());
            assertEquals("[[1, Ann, Rome]]", on.rows(first).stream().map(Arrays::toString).toList().toString());
        }
    }

    private static String message(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            b.append(t.getMessage()).append(" | ");
        }
        return b.toString();
    }

    private static String columns(Schema schema) {
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            columns.add(schema.name(c) + " " + schema.type(c).sqlName());
        }
        return String.join(", ", columns);
    }

    /** The columns DuckDB gives a query, from DESCRIBE, with JSON and TIME named as in FyrQL. */
    private static String describe(DuckDbReference duckdb, String sql) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement s = duckdb.connection().createStatement(); ResultSet rs = s.executeQuery("DESCRIBE " + sql)) {
            while (rs.next()) {
                // Both quote a field name that is a keyword, such as "name", in a type; only DuckDB quotes one
                // such as "day" in the name of an expression, as in count("day").
                String type = rs.getString("column_type").replace("JSON", "VARCHAR").replaceAll("\\bTIME\\b",
                        "VARCHAR");
                columns.add(rs.getString("column_name").replace("\"", "") + " " + type);
            }
        }
        return String.join(", ", columns);
    }
}
