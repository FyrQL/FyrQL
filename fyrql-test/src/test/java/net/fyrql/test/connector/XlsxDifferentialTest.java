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
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.WrittenFiles;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FyrQL's {@code read_xlsx} gives the columns and rows of DuckDB's, with the
 * extension {@code excel}, on workbooks written by Apache POI, by DuckDB's
 * {@code COPY ... (FORMAT xlsx)} and by FyrQL's, under the named arguments
 * of the function. The workbooks hold no dates before 1900-03-01 and no
 * dates of 1904, which DuckDB reads otherwise than Excel, and no escapes
 * {@code _xHHHH_}, which DuckDB does not decode; see
 * {@link WrittenFiles#xlsx}; and no workbook as POI streams it, in which
 * DuckDB 1.5.6 finds no sheets. Skipped when DuckDB has no extension
 * {@code excel} in its cache.
 */
class XlsxDifferentialTest {

    @TempDir
    static Path dir;

    private static DuckDbReference duck;
    private static FyrQL engine;

    @BeforeAll
    static void start() {
        duck = new DuckDbReference();
        engine = FyrQL.builder().register(MemoryConnector.builder("mem").build()).build();
    }

    @AfterAll
    static void stop() {
        duck.close();
        engine.close();
    }

    @Test
    void readXlsxGivesTheColumnsAndRowsOfDuckDb() throws IOException {
        assumeTrue(WrittenFiles.loadExcel(duck), "DuckDB has no extension excel in its cache");
        List<String[]> cases = new ArrayList<>();
        Path basic = basic();
        for (String args : new String[] {"", ", header = false", ", all_varchar = true", ", range = 'B3:D40'",
            ", range = 'A1:E10', header = true", ", normalize_names = true", ", stop_at_empty = false"}) {
            cases.add(new String[] {basic.toString(), args});
        }
        Path dates = dates();
        for (String args : new String[] {"", ", all_varchar = true", ", header = false"}) {
            cases.add(new String[] {dates.toString(), args});
        }
        Path shapes = shapes();
        for (String args : new String[] {"", ", sheet = 'numbers'", ", sheet = 'numbers', header = true",
            ", sheet = 'mixed'", ", sheet = 'gaps'", ", sheet = 'gaps', stop_at_empty = false",
            ", sheet = 'gaps', range = 'A1:C30'", ", sheet = 'gaps', range = 'A1:C30', stop_at_empty = true",
            ", sheet = 'offset'", ", sheet = 'offset', range = 'C4:D9'", ", sheet = 'offset', range = 'B:E'",
            ", sheet = 'formulas'", ", sheet = 'names'", ", sheet = 'names', normalize_names = true",
            ", sheet = 'names', header = true", ", sheet = 'empty first'",
            ", sheet = 'empty first', empty_as_varchar = true", ", sheet = 'merged'",
            ", sheet = 'merged', stop_at_empty = false", ", sheet = 'errors'", ", sheet = 'only header'",
            ", sheet = 'late start', range = 'A1:B5'", ", sheet = 'late start', range = 'A1:B5', header = true",
            ", sheet = 'late start', range = 'A1:B5', header = false", ", sheet = 'gap after header'",
            ", sheet = 'gap after header', stop_at_empty = false", ", sheet = 'gap after header', range = 'A1:B6'",
            ", sheet = 'gap after header', range = 'A2:B6'", ", sheet = 'empty row', stop_at_empty = false",
            ", sheet = 'empty row', range = 'A2:B4'", ", sheet = 'empty texts'",
            ", sheet = 'empty texts', stop_at_empty = false", ", sheet = 'empty texts', normalize_names = true",
            ", sheet = 'boolean texts'"}) {
            cases.add(new String[] {shapes.toString(), args});
        }
        String select = "SELECT range AS i, range * 1.5 AS d, 'v' || range AS s, range % 2 = 0 AS b,"
                + " DATE '2024-01-01' + CAST(range AS INTEGER) AS dt,"
                + " TIMESTAMP '2024-01-01 10:20:30' + INTERVAL (range) HOUR AS ts,"
                + " TIME '10:00:00' + INTERVAL (range) MINUTE AS t, CAST(range / 3 AS DECIMAL(10, 3)) AS m,"
                + " CASE WHEN range % 5 = 3 THEN NULL ELSE 'x' END AS n,"
                + " CASE range % 4 WHEN 0 THEN '' WHEN 1 THEN 'true' ELSE 'v' END AS e FROM range(1, 60)";
        Path source = dir.resolve("source.parquet");
        duck.execute("COPY (" + select + ") TO '" + WrittenFiles.path(source) + "' (FORMAT parquet)");
        String rows = "SELECT * FROM '" + WrittenFiles.path(source) + "'";
        for (String header : new String[] {"true", "false"}) {
            Path byDuck = dir.resolve("duckdb-" + header + ".xlsx");
            duck.execute("COPY (" + rows + ") TO '" + WrittenFiles.path(byDuck) + "' (FORMAT xlsx, HEADER "
                    + header + ")");
            cases.add(new String[] {byDuck.toString(), ""});
            Path byFyrql = dir.resolve("fyrql-" + header + ".xlsx");
            engine.update("COPY (" + rows + ") TO '" + WrittenFiles.path(byFyrql) + "' (HEADER " + header + ")");
            cases.add(new String[] {byFyrql.toString(), ""});
            cases.add(new String[] {byFyrql.toString(), ", all_varchar = true"});
        }
        List<String> failures = new ArrayList<>();
        for (String[] c : cases) {
            Path file = Path.of(c[0]);
            String what = "read_xlsx('" + file.getFileName() + "'" + c[1] + ")";
            try {
                ResultComparison.Outcome outcome = WrittenFiles.xlsx(duck, engine, file, c[1]).compare(true);
                if (!outcome.same()) {
                    failures.add(what + "\n" + outcome.report());
                }
            } catch (RuntimeException e) {
                failures.add(what + "\n  fails: " + e + (e.getCause() == null ? "" : "\n  cause: " + e.getCause()));
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + cases.size() + " reads differ:\n\n" + String.join("\n\n", failures));
        }
    }

    /** A header and 200 rows of numbers, texts and booleans, with an empty cell now and then. */
    private static Path basic() throws IOException {
        Random random = new Random(20261025);
        String[] texts = {"a", "b c", " pad ", "%", "_", "123", "2.5", "true", "\u00e9t\u00e9", "z\uD834\uDD1E", ""};
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("data");
            values(s.createRow(0), "id", "amount", "name", "flag", "count");
            for (int r = 1; r <= 200; r++) {
                Row row = s.createRow(r);
                row.createCell(0).setCellValue(r);
                if (random.nextInt(10) > 0) {
                    row.createCell(1).setCellValue(random.nextInt(100_000) / 100.0 - 300);
                }
                if (random.nextInt(10) > 0) {
                    row.createCell(2).setCellValue(texts[random.nextInt(texts.length)]);
                }
                row.createCell(3).setCellValue(random.nextBoolean());
                row.createCell(4).setCellValue(random.nextInt(1000));
            }
            return save(wb, "basic.xlsx");
        }
    }

    /** Numbers with the formats of dates, timestamps, times and others, all from 1900-03-01 on. */
    private static Path dates() throws IOException {
        String[] formats = {"m/d/yy", "m/d/yy h:mm", "h:mm:ss", "h:mm", "yyyy-mm-dd", "dd/mm/yyyy hh:mm",
            "yyyy-mm-dd hh:mm:ss.000", "d-mmm-yy", "mmm-yy", "h:mm AM/PM", "[h]:mm:ss", "mm:ss", "0.00%", "0.00"};
        Random random = new Random(7);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("dates");
            Row header = s.createRow(0);
            CellStyle[] styles = new CellStyle[formats.length];
            for (int c = 0; c < formats.length; c++) {
                header.createCell(c).setCellValue("c" + c);
                styles[c] = wb.createCellStyle();
                styles[c].setDataFormat(wb.createDataFormat().getFormat(formats[c]));
            }
            for (int r = 1; r <= 100; r++) {
                Row row = s.createRow(r);
                for (int c = 0; c < formats.length; c++) {
                    boolean time = formats[c].startsWith("h") || formats[c].startsWith("[h]")
                            || formats[c].startsWith("mm:");
                    double v = time ? random.nextDouble() * (random.nextInt(4) == 0 ? 3 : 1)
                            : 61 + random.nextDouble() * 60_000;
                    if (r % 7 == 0) {
                        // Whole days, whole seconds and the edges of a day.
                        v = r % 2 == 0 ? Math.floor(v) : Math.round(v * 86400) / 86400.0;
                    }
                    Cell cell = row.createCell(c);
                    cell.setCellValue(v);
                    cell.setCellStyle(styles[c]);
                }
            }
            return save(wb, "dates.xlsx");
        }
    }

    /** Sheets of the shapes DuckDB decides on: header, types, gaps, ranges, formulas, names, merged cells. */
    private static Path shapes() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet hidden = wb.createSheet("hidden first");
            values(hidden.createRow(0), "h");
            values(hidden.createRow(1), 1);
            wb.setSheetHidden(0, true);

            Sheet numbers = wb.createSheet("numbers");
            values(numbers.createRow(0), 1, 2.5, "x");
            values(numbers.createRow(1), 3, 4.5, "y");

            Sheet mixed = wb.createSheet("mixed");
            values(mixed.createRow(0), "m", "n", "b");
            values(mixed.createRow(1), 1, "a", true);
            values(mixed.createRow(2), "b", 2, "yes");
            values(mixed.createRow(3), 3.5, 3, "maybe");
            values(mixed.createRow(4), "4", "c", false);

            Sheet gaps = wb.createSheet("gaps");
            values(gaps.createRow(0), "a", "b", "c");
            values(gaps.createRow(1), 1, "x", 2);
            values(gaps.createRow(2), 2, "y", 3);
            values(gaps.createRow(4), 5, null, 6);
            gaps.createRow(5);
            values(gaps.createRow(6), 7, "z", null);
            gaps.createRow(7).createCell(5).setCellValue("outside");
            values(gaps.createRow(9), 10, "w", 11);

            Sheet offset = wb.createSheet("offset");
            Row o3 = offset.createRow(2);
            o3.createCell(2).setCellValue("c");
            o3.createCell(3).setCellValue("d");
            for (int r = 3; r < 9; r++) {
                Row row = offset.createRow(r);
                row.createCell(2).setCellValue(r);
                row.createCell(3).setCellValue("t" + r);
            }
            offset.createRow(10).createCell(4).setCellValue("stray");

            Sheet formulas = wb.createSheet("formulas");
            values(formulas.createRow(0), "num", "str", "bool", "err", "plain");
            Row f1 = formulas.createRow(1);
            f1.createCell(0).setCellFormula("1+2");
            f1.createCell(1).setCellFormula("\"a\"&\"b\"");
            f1.createCell(2).setCellFormula("TRUE()");
            f1.createCell(3).setCellFormula("NA()");
            f1.createCell(4).setCellValue(5);
            Row f2 = formulas.createRow(2);
            f2.createCell(0).setCellFormula("A2*2");
            f2.createCell(1).setCellFormula("LOWER(\"XY\")");
            f2.createCell(4).setCellValue(6);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();

            Sheet names = wb.createSheet("names");
            values(names.createRow(0), "a", "a", "A", " x ", "First Name", "Select", "order", "1abc", "name", "a-b");
            values(names.createRow(1), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

            Sheet emptyFirst = wb.createSheet("empty first");
            values(emptyFirst.createRow(0), "a", "b", "c");
            values(emptyFirst.createRow(1), null, "x", null);
            values(emptyFirst.createRow(2), 2, "y", "late");

            Sheet merged = wb.createSheet("merged");
            values(merged.createRow(0), "a", "b");
            values(merged.createRow(1), 1, null);
            merged.createRow(2);
            values(merged.createRow(3), 4, 5);
            merged.addMergedRegion(new CellRangeAddress(1, 2, 0, 1));

            Sheet errors = wb.createSheet("errors");
            values(errors.createRow(0), "v", "w");
            values(errors.createRow(1), 1, "text");
            Row e2 = errors.createRow(2);
            e2.createCell(0).setCellErrorValue(FormulaError.DIV0.getCode());
            e2.createCell(1).setCellErrorValue(FormulaError.NA.getCode());
            values(errors.createRow(3), 3, "more");

            Sheet onlyHeader = wb.createSheet("only header");
            values(onlyHeader.createRow(0), "a", "b");

            Sheet lateStart = wb.createSheet("late start");
            values(lateStart.createRow(1), 1, "x");
            values(lateStart.createRow(2), 2, "y");

            Sheet gapAfterHeader = wb.createSheet("gap after header");
            values(gapAfterHeader.createRow(0), "h", "g");
            values(gapAfterHeader.createRow(2), "text", 5);
            values(gapAfterHeader.createRow(3), "more", 6);

            // A row the sheet writes without cells, which counts as the first row after the header.
            Sheet emptyRow = wb.createSheet("empty row");
            values(emptyRow.createRow(0), "h", "g");
            emptyRow.createRow(1);
            values(emptyRow.createRow(2), "text", 5);

            // Shared strings without text: values, which a header names C and the position of the column.
            Sheet emptyTexts = wb.createSheet("empty texts");
            values(emptyTexts.createRow(0), "", "b", "", "d", " ");
            values(emptyTexts.createRow(1), 1, "", 3, "", 5);
            values(emptyTexts.createRow(2), "", "", "", "", "");
            values(emptyTexts.createRow(3), 6, "x", 8, "y", 10);

            Sheet booleanTexts = wb.createSheet("boolean texts");
            values(booleanTexts.createRow(0), "a", "b", "c", "d");
            values(booleanTexts.createRow(1), "true", "TRUE", "yes", "False");
            values(booleanTexts.createRow(2), "false", true, "no", "x");
            return save(wb, "shapes.xlsx");
        }
    }

    private static void values(Row row, Object... values) {
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) {
                continue;
            }
            Cell cell = row.createCell(c);
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else if (v instanceof Boolean b) {
                cell.setCellValue(b);
            } else {
                cell.setCellValue(v.toString());
            }
        }
    }

    private static Path save(Workbook wb, String name) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            wb.write(out);
        }
        return file;
    }
}
