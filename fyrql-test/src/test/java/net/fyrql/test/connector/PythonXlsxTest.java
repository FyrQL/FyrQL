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
import net.fyrql.QueryResult;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.test.Rows;
import net.fyrql.test.WrittenFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Workbooks that openpyxl and xlsxwriter write, with the Python the system
 * property {@code fyrql.test.python} names, which has both installed, such
 * as {@code -Dfyrql.test.python=/usr/bin/python3}: the script
 * {@code xlsx_writers.py} writes them and tells the columns and the values
 * {@code read_xlsx} must give, from what it wrote, not from another reader.
 * So it checks what DuckDB reads otherwise as well: dates before
 * 1900-03-01 and of 1904, and the escapes {@code _xHHHH_} of xlsxwriter.
 */
@EnabledIfSystemProperty(named = "fyrql.test.python", matches = ".+")
class PythonXlsxTest {

    @Test
    void workbooksOfPythonReadAsTheirWritersSay(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("xlsx_writers.py");
        try (InputStream in = PythonXlsxTest.class.getResourceAsStream("xlsx_writers.py")) {
            Files.copy(in, script);
        }
        Path books = Files.createDirectories(dir.resolve("books"));
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("fyrql.test.python"), "-I", script.toString(),
                books.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        assumeFalse(out.startsWith("SKIP"), out);
        assertTrue(out.strip().endsWith("DONE"), out);

        List<String> failures = new ArrayList<>();
        int cases = 0;
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("mem").build()).build()) {
            List<String> lines = out.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith("CASE\t")) {
                    continue;
                }
                cases++;
                String[] head = lines.get(i).split("\t", -1);
                String sql = "SELECT * FROM read_xlsx('" + WrittenFiles.path(Path.of(head[1])) + "'" + head[2] + ")";
                List<String> columns = Arrays.asList(lines.get(i + 1).split("\t", -1)).subList(1,
                        lines.get(i + 1).split("\t", -1).length);
                List<List<String>> expected = new ArrayList<>();
                for (int j = i + 2; j < lines.size() && lines.get(j).startsWith("ROW\t"); j++) {
                    List<String> row = new ArrayList<>();
                    for (String v : lines.get(j).substring(4).split("\t", -1)) {
                        row.add(v.equals("NULL") ? null : unescape(v));
                    }
                    expected.add(row);
                }
                try (QueryResult r = engine.sql(sql)) {
                    Schema schema = r.schema();
                    List<String> actualColumns = new ArrayList<>();
                    for (Schema.Field f : schema.fields()) {
                        actualColumns.add(f.name() + " " + f.type().sqlName());
                    }
                    List<List<String>> actual = new ArrayList<>();
                    for (Object[] row : Rows.internal(schema, r.rows())) {
                        List<String> texts = new ArrayList<>();
                        for (int c = 0; c < row.length; c++) {
                            texts.add(row[c] == null ? null : Casts.format(row[c], schema.type(c)));
                        }
                        actual.add(texts);
                    }
                    if (!actualColumns.equals(columns) || !actual.equals(expected)) {
                        failures.add(sql + "\n  expected " + columns + " " + expected + "\n  actual   " + actualColumns
                                + " " + actual);
                    }
                } catch (RuntimeException e) {
                    failures.add(sql + "\n  fails: " + e);
                }
            }
        }
        assertTrue(cases >= 4, out);
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + cases + " workbooks differ:\n\n" + String.join("\n\n", failures));
        }
    }

    /** Reads a value as Python's {@code unicode_escape} writes it. */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case '\\' -> b.append('\\');
                case 'x' -> {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                case 'U' -> {
                    b.appendCodePoint(Integer.parseInt(s.substring(i + 1, i + 9), 16));
                    i += 8;
                }
                default -> b.append('\\').append(e);
            }
        }
        return b.toString();
    }
}
