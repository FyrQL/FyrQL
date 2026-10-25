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

package net.fyrql.connector.xlsx;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XlsxConnectorTest {

    @TempDir
    Path dir;

    @Test
    void headerTypesAndValues() throws IOException {
        Path file = workbook("basic.xlsx", s -> {
            rows(s, new Object[] {"i", "f", "s", "b"}, new Object[] {1, 1.5, "a", true},
                    new Object[] {2, 2.25, "b", false}, new Object[] {3, -0.5, "c", true});
        });
        XlsxConnector c = connector();
        TableRef t = call(c, file);
        assertEquals(schema("i", DataType.DOUBLE, "f", DataType.DOUBLE, "s", DataType.VARCHAR, "b", DataType.BOOLEAN),
                c.catalog().schema(t));
        assertEquals(List.of(List.of(1.0, 1.5, "a", true), List.of(2.0, 2.25, "b", false),
                List.of(3.0, -0.5, "c", true)), rows(c, t));
        assertEquals(List.of(List.of("a", 1.0), List.of("b", 2.0), List.of("c", 3.0)), rows(c, t, 2, 0));
        // POI writes the number 1 as 1.0, which is the text of the cell.
        assertEquals(List.of(List.of("i", "f", "s", "b"), List.of("1.0", "1.5", "a", "1")),
                rows(c, call(c, file, "header", false, "all_varchar", true)).subList(0, 2));
        assertEquals(schema("A1", DataType.VARCHAR, "B1", DataType.VARCHAR, "C1", DataType.VARCHAR, "D1",
                DataType.VARCHAR), c.catalog().schema(call(c, file, "header", false)));
    }

    @Test
    void namesWithoutHeader() throws IOException {
        Path numbers = workbook("numbers.xlsx", s -> rows(s, new Object[] {1, 2.5, "x"}, new Object[] {3, 4.5, "y"}));
        XlsxConnector c = connector();
        TableRef t = call(c, numbers);
        assertEquals(schema("A1", DataType.DOUBLE, "B1", DataType.DOUBLE, "C1", DataType.VARCHAR),
                c.catalog().schema(t));
        assertEquals(2, rows(c, t).size());
        assertEquals(schema("1.0", DataType.DOUBLE, "2.5", DataType.DOUBLE, "x", DataType.VARCHAR),
                c.catalog().schema(call(c, numbers, "header", true)));
        Path headers = workbook("headers.xlsx", s -> rows(s, new Object[] {"a", "a", null, 7, "a_1"},
                new Object[] {1, 2, 3, 4, 5}));
        assertEquals(List.of("A1", "B1", "C1", "D1", "E1"), names(c.catalog().schema(call(c, headers))));
        assertEquals(List.of("a", "a_1", "C2", "7.0", "a_1_1"),
                names(c.catalog().schema(call(c, headers, "header", true))));
        assertEquals(List.of("a", "a_1", "_", "_70", "a_1_1"),
                names(c.catalog().schema(call(c, headers, "header", true, "normalize_names", true))));
        Path dups = workbook("dups.xlsx", s -> rows(s, new Object[] {"a", "a", "a_1", "A", " x ", "First Name",
            "Select", "1abc"}, new Object[] {1, 2, 3, 4, 5, 6, 7, 8}));
        assertEquals(List.of("a", "a_1", "a_1_1", "A_2", "x", "First Name", "Select", "1abc"),
                names(c.catalog().schema(call(c, dups))));
        assertEquals(List.of("a", "a_1", "a_1_1", "a_2", "x", "first_name", "_select", "_1abc"),
                names(c.catalog().schema(call(c, dups, "normalize_names", true))));
    }

    @Test
    void typesComeFromTheFirstRowOfData() throws IOException {
        Path mixed = workbook("mixed.xlsx", s -> rows(s, new Object[] {"m", "n"}, new Object[] {1, "a"},
                new Object[] {"b", 2}, new Object[] {3.5, 3}));
        XlsxConnector c = connector();
        TableRef t = call(c, mixed);
        assertEquals(schema("m", DataType.DOUBLE, "n", DataType.VARCHAR), c.catalog().schema(t));
        ConversionException e = assertThrows(ConversionException.class, () -> rows(c, t));
        assertEquals("Failed to parse cell 'A3' of the sheet \"Sheet1\" of " + mixed
                + ": Could not convert string 'b' to DOUBLE", e.getMessage());
        assertEquals(Arrays.asList(Arrays.asList(1.0, "a"), Arrays.asList(null, "2.0"), Arrays.asList(3.5, "3.0")),
                rows(c, call(c, mixed, "ignore_errors", true)));
        Path empty = workbook("firstempty.xlsx", s -> rows(s, new Object[] {"a", "b", "c"},
                new Object[] {null, "x", null}, new Object[] {2, "y", "late"}));
        assertEquals(schema("a", DataType.DOUBLE, "b", DataType.VARCHAR, "c", DataType.DOUBLE),
                c.catalog().schema(call(c, empty)));
        assertEquals(schema("a", DataType.VARCHAR, "b", DataType.VARCHAR, "c", DataType.VARCHAR),
                c.catalog().schema(call(c, empty, "empty_as_varchar", true)));
        assertEquals(Arrays.asList(Arrays.asList(null, "x", null), Arrays.asList("2.0", "y", "late")),
                rows(c, call(c, empty, "empty_as_varchar", true)));
        // POI writes the number 1 as 1.0, which does not cast to a BOOLEAN; DuckDB reads 1 as true.
        Path bools = workbook("bools.xlsx", s -> rows(s, new Object[] {"b", "d"}, new Object[] {true, 1.5},
                new Object[] {"yes", true}, new Object[] {"0", " 7 "}, new Object[] {"T", "1e3"}));
        assertEquals(List.of(List.of(true, 1.5), List.of(true, 1.0), List.of(false, 7.0), List.of(true, 1000.0)),
                rows(c, call(c, bools)));
        Path two = workbook("two.xlsx", s -> rows(s, new Object[] {"b"}, new Object[] {true}, new Object[] {2}));
        e = assertThrows(ConversionException.class, () -> rows(c, call(c, two)));
        assertEquals("Failed to parse cell 'A3' of the sheet \"Sheet1\" of " + two
                + ": Could not convert string '2.0' to BOOLEAN", e.getMessage());
    }

    @Test
    void datesTimesAndTheirFormats() throws IOException {
        Path file;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Sheet1");
            String[] formats = {"m/d/yy", "m/d/yy h:mm", "h:mm:ss", "yyyy-mm-dd", "dd/mm/yyyy hh:mm", "[h]:mm:ss",
                "0.00%", "mm:ss"};
            double[][] values = {{45351, 45351.57326388889, 0.5732638888888889, 37256, 36162.12777777778, 1.75, 0.125,
                0.0105}, {61, 1.000011574074074, 5.787037037037037e-06, 25569, 73415.99930555555, 25.5, 1, 0.5}};
            Row header = s.createRow(0);
            for (int c = 0; c < formats.length; c++) {
                header.createCell(c).setCellValue("c" + c);
            }
            for (int r = 0; r < values.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < formats.length; c++) {
                    CellStyle style = wb.createCellStyle();
                    style.setDataFormat(wb.createDataFormat().getFormat(formats[c]));
                    Cell cell = row.createCell(c);
                    cell.setCellValue(values[r][c]);
                    cell.setCellStyle(style);
                }
            }
            file = save(wb, "dates.xlsx");
        }
        XlsxConnector c = connector();
        TableRef t = call(c, file);
        assertEquals(List.of(DataType.DATE, DataType.TIMESTAMP, DataType.TIME, DataType.DATE, DataType.TIMESTAMP,
                DataType.DOUBLE, DataType.DOUBLE, DataType.DOUBLE), c.catalog().schema(t).types());
        assertEquals(List.of(date("2024-02-29"), ts("2024-02-29T13:45:30"), time("13:45:30"), date("2001-12-31"),
                ts("1999-01-02T03:04:00"), 1.75, 0.125, 0.0105), rows(c, t).get(0));
        assertEquals(List.of(date("1900-03-01"), ts("1900-01-01T00:00:01"), time("00:00:00.500"), date("1970-01-01"),
                ts("2100-12-31T23:59:00"), 25.5, 1.0, 0.5), rows(c, t).get(1));
        assertEquals(List.of("45351.0", "45351.57326388889"), rows(c, call(c, file, "all_varchar", true)).get(0)
                .subList(0, 2));
    }

    @Test
    void datesOf1904() throws IOException {
        Path file;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.getCTWorkbook().getWorkbookPr().setDate1904(true);
            Sheet s = wb.createSheet("Sheet1");
            s.createRow(0).createCell(0).setCellValue("d");
            CellStyle style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            Cell cell = s.createRow(1).createCell(0);
            cell.setCellValue(43889);
            cell.setCellStyle(style);
            file = save(wb, "mac.xlsx");
        }
        XlsxConnector c = connector();
        assertEquals(List.of(List.of(date("2024-02-29"))), rows(c, call(c, file)));
    }

    @Test
    void formulasGiveTheirStoredResults() throws IOException {
        Path file;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Sheet1");
            rows(s, new Object[] {"num", "str", "bool", "err", "plain"});
            Row r = s.createRow(1);
            r.createCell(0).setCellFormula("1+2");
            r.createCell(1).setCellFormula("\"a\"&\"b\"");
            r.createCell(2).setCellFormula("TRUE()");
            r.createCell(3).setCellFormula("NA()");
            r.createCell(4).setCellValue(5);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            Row r2 = s.createRow(2);
            r2.createCell(0).setCellFormula("A2*2");
            Cell error = r2.createCell(3);
            error.setCellErrorValue(FormulaError.DIV0.getCode());
            file = save(wb, "formulas.xlsx");
        }
        XlsxConnector c = connector();
        TableRef t = call(c, file);
        assertEquals(schema("num", DataType.DOUBLE, "str", DataType.VARCHAR, "bool", DataType.BOOLEAN, "err",
                DataType.VARCHAR, "plain", DataType.DOUBLE), c.catalog().schema(t));
        assertEquals(Arrays.asList(Arrays.asList(3.0, "ab", true, "#N/A", 5.0),
                Arrays.asList(null, null, null, "#DIV/0!", null)), rows(c, t));
    }

    @Test
    void emptyRowsRangesAndOffsets() throws IOException {
        Path gaps = workbook("gaps.xlsx", s -> {
            s.createRow(0).createCell(0).setCellValue("a");
            s.createRow(1).createCell(0).setCellValue(1);
            s.createRow(2).createCell(0).setCellValue(2);
            s.createRow(4).createCell(0).setCellValue(5);
            s.createRow(5).createCell(0).setCellValue(6);
            s.createRow(6).createCell(3).setCellValue("outside");
        });
        XlsxConnector c = connector();
        assertEquals(List.of(List.of(1.0), List.of(2.0)), rows(c, call(c, gaps)));
        assertEquals(Arrays.asList(List.of(1.0), List.of(2.0), Arrays.asList((Object) null), List.of(5.0),
                List.of(6.0), Arrays.asList((Object) null)), rows(c, call(c, gaps, "stop_at_empty", false)));
        List<List<Object>> padded = rows(c, call(c, gaps, "range", "A1:A10"));
        assertEquals(9, padded.size());
        assertEquals(List.of(5.0), padded.get(3));
        assertEquals(Arrays.asList((Object) null), padded.get(8));
        assertEquals(List.of(List.of(1.0), List.of(2.0)), rows(c, call(c, gaps, "range", "A1:A10",
                "stop_at_empty", true)));
        TableRef columns = call(c, gaps, "range", "A:A");
        assertEquals(OptionalLong.of(CellRef.MAX_ROWS - 1), c.catalog().rowCount(columns));
        assertEquals(CellRef.MAX_ROWS - 1, count(c, columns));

        Path offset = workbook("offset.xlsx", s -> {
            Row r3 = s.createRow(2);
            r3.createCell(2).setCellValue("c");
            r3.createCell(3).setCellValue("d");
            Row r4 = s.createRow(3);
            r4.createCell(2).setCellValue(1);
            r4.createCell(3).setCellValue("x");
            Row r5 = s.createRow(4);
            r5.createCell(2).setCellValue(2);
            r5.createCell(3).setCellValue("y");
            Row r6 = s.createRow(5);
            r6.createCell(1).setCellValue("left");
            r6.createCell(4).setCellValue("right");
        });
        assertEquals(schema("c", DataType.DOUBLE, "d", DataType.VARCHAR), c.catalog().schema(call(c, offset)));
        assertEquals(List.of(List.of(1.0, "x"), List.of(2.0, "y")), rows(c, call(c, offset)));
        assertEquals(schema("C", DataType.DOUBLE, "D", DataType.VARCHAR),
                c.catalog().schema(call(c, offset, "range", "C4:D5")));
        assertEquals(schema("C", DataType.VARCHAR, "D", DataType.VARCHAR),
                c.catalog().schema(call(c, offset, "range", "C3:D5", "header", false)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call(c, offset, "range", "D4"));
        assertEquals("Invalid range 'D4' specified", e.getMessage());
    }

    /** As DuckDB: a range starts at its first row, its header and types come from the rows the sheet writes. */
    @Test
    void rowsTheSheetWrites() throws IOException {
        Path late = workbook("late.xlsx", s -> {
            rows(s, 1, new Object[] {1, "x"});
            rows(s, 2, new Object[] {2, "y"});
        });
        XlsxConnector c = connector();
        TableRef t = call(c, late, "range", "A1:B5");
        assertEquals(schema("A", DataType.DOUBLE, "B", DataType.VARCHAR), c.catalog().schema(t));
        assertEquals(Arrays.asList(Arrays.asList(null, null), Arrays.asList(1.0, "x"), Arrays.asList(2.0, "y"),
                Arrays.asList(null, null), Arrays.asList(null, null)), rows(c, t));
        TableRef header = call(c, late, "range", "A1:B5", "header", true);
        assertEquals(schema("1.0", DataType.DOUBLE, "x", DataType.VARCHAR), c.catalog().schema(header));
        assertEquals(Arrays.asList(Arrays.asList(2.0, "y"), Arrays.asList(null, null), Arrays.asList(null, null)),
                rows(c, header));
        Path gap = workbook("gap.xlsx", s -> {
            rows(s, 0, new Object[] {"h", "g"});
            rows(s, 2, new Object[] {"text", 5});
            rows(s, 3, new Object[] {"more", 6});
        });
        TableRef g = call(c, gap, "stop_at_empty", false);
        assertEquals(schema("h", DataType.VARCHAR, "g", DataType.DOUBLE), c.catalog().schema(g));
        assertEquals(Arrays.asList(Arrays.asList(null, null), Arrays.asList("text", 5.0), Arrays.asList("more", 6.0)),
                rows(c, g));
        assertEquals(List.of(), rows(c, call(c, gap)));
        Path written = workbook("written.xlsx", s -> {
            rows(s, 0, new Object[] {"h", "g"});
            s.createRow(1);
            rows(s, 2, new Object[] {"text", 5});
        });
        TableRef w = call(c, written, "stop_at_empty", false);
        assertEquals(schema("h", DataType.DOUBLE, "g", DataType.DOUBLE), c.catalog().schema(w));
        ConversionException e = assertThrows(ConversionException.class, () -> rows(c, w));
        assertTrue(e.getMessage().startsWith("Failed to parse cell 'A3' "), e.getMessage());
    }

    /** Empty texts and the texts true and false, as DuckDB reads them. */
    @Test
    void emptyTextsAndBooleanTexts() throws IOException {
        XlsxConnector c = connector();
        String empty = "<is><t></t></is>";
        Path inline = dir.resolve("inline.xlsx");
        Files.write(inline, Zips.zip(Zips.workbook("<row r='1'><c r='A1' t='inlineStr'><is><t>v</t></is></c>"
                + "<c r='B1' t='inlineStr'><is><t>w</t></is></c><c r='C1' t='inlineStr'>" + empty + "</c></row>"
                + "<row r='2'><c r='A2' t='inlineStr'>" + empty + "</c><c r='B2'><v>1</v></c></row>"
                + "<row r='3'><c r='A3' t='inlineStr'><is><t>x</t></is></c><c r='B3' t='inlineStr'>" + empty
                + "</c></row><row r='4'><c r='A4' t='inlineStr'>" + empty + "</c></row>"
                + "<row r='5'><c r='A5' t='inlineStr'><is><t>y</t></is></c><c r='B5'><v>5</v></c></row>"), false));
        TableRef t = call(c, inline);
        // An inline string without text is no value of the header row, nor of a row that has nothing else.
        assertEquals(schema("v", DataType.VARCHAR, "w", DataType.DOUBLE), c.catalog().schema(t));
        assertEquals(Arrays.asList(Arrays.asList("", 1.0), Arrays.asList("x", null)), rows(c, t));
        assertEquals(Arrays.asList(Arrays.asList("", 1.0), Arrays.asList("x", null), Arrays.asList("", null),
                Arrays.asList("y", 5.0)), rows(c, call(c, inline, "stop_at_empty", false)));
        Path shared = workbook("shared.xlsx", s -> rows(s, new Object[] {"", "b", "", "d", " "},
                new Object[] {1, 2, 3, 4, 5}, new Object[] {"", "", "", "", ""}, new Object[] {6, 7, 8, 9, 10}));
        TableRef st = call(c, shared);
        assertEquals(List.of("C0", "b", "_1", "d", "_2"), names(c.catalog().schema(st)));
        assertEquals(Arrays.asList(Arrays.asList(1.0, 2.0, 3.0, 4.0, 5.0), Arrays.asList(null, null, null, null, null),
                Arrays.asList(6.0, 7.0, 8.0, 9.0, 10.0)), rows(c, st));
        assertEquals(List.of("_", "b", "__1", "d", "__2"),
                names(c.catalog().schema(call(c, shared, "normalize_names", true))));
        // An inline string true or false makes a BOOLEAN, a shared one, as POI writes it, a VARCHAR.
        for (String text : new String[] {"true", "TRUE", "False"}) {
            Path file = dir.resolve("bool-" + text + ".xlsx");
            Files.write(file, Zips.zip(Zips.workbook("<row r='1'><c r='A1' t='inlineStr'><is><t>v</t></is></c></row>"
                    + "<row r='2'><c r='A2' t='inlineStr'><is><t>" + text + "</t></is></c></row>"
                    + "<row r='3'><c r='A3' t='b'><v>0</v></c></row>"), false));
            assertEquals(schema("v", DataType.BOOLEAN), c.catalog().schema(call(c, file)));
            assertEquals(List.of(List.of(text.equalsIgnoreCase("true")), List.of(false)), rows(c, call(c, file)));
        }
        for (String text : new String[] {"true", "yes", "t", "1", "2024-01-02"}) {
            Path file = workbook("text-" + text + ".xlsx", s -> rows(s, new Object[] {"v"}, new Object[] {text}));
            assertEquals(schema("v", DataType.VARCHAR), c.catalog().schema(call(c, file)));
        }
    }

    /** Cells without values, formula texts and spaces, as DuckDB reads them in the test data of POI. */
    @Test
    void cellsWithoutValues() throws IOException {
        XlsxConnector c = connector();
        Path file = dir.resolve("cells.xlsx");
        Map<String, byte[]> parts = Zips.workbook("<row r='1'><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>1</v></c>"
                + "<c r='C1' t='s'><v>2</v></c><c r='D1' t='s'><v>3</v></c><c r='E1' t='s'><v>4</v></c>"
                + "<c r='F1' s='1'/><c r='G1' t='s'><v>4</v></c></row>"
                + "<row r='2'><c r='A2' s='1'/><c r='B2'/><c r='C2' t='s'><v/></c><c r='D2' t='str'><v></v></c>"
                + "<c r='E2'><v>5</v></c></row>"
                + "<row r='3'><c r='A3'><v>45000</v></c><c r='B3'><v>2</v></c><c r='C3' t='s'><v>4</v></c>"
                + "<c r='D3' t='str'><v>x</v></c></row>");
        Zips.put(parts, "xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/"
                + "2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/"
                + "2006/relationships/worksheet' Target='worksheets/sheet1.xml'/><Relationship Id='rId2' Type='http://"
                + "schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings' Target='strings.xml'/>"
                + "<Relationship Id='rId3' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + "styles' Target='styles.xml'/></Relationships>");
        Zips.put(parts, "xl/strings.xml", "<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'>"
                + "<si><t>date\u00a0</t></si><si><t>n</t></si><si><t>s</t></si><si><t>f</t></si><si><t>e</t></si>"
                + "</sst>");
        Zips.put(parts, "xl/styles.xml", "<styleSheet"
                + " xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><cellXfs count='2'>"
                + "<xf numFmtId='0'/><xf numFmtId='14'/></cellXfs></styleSheet>");
        Files.write(file, Zips.zip(parts, false));
        TableRef t = call(c, file);
        // The cell F1 without a value ends the columns; a cell with a date format and no value makes a DATE, a
        // shared string without its index an empty text and a formula text without text a VARCHAR of NULLs.
        assertEquals(schema("date", DataType.DATE, "n", DataType.DOUBLE, "s", DataType.VARCHAR, "f",
                DataType.VARCHAR, "e", DataType.DOUBLE), c.catalog().schema(t));
        assertEquals(Arrays.asList(Arrays.asList(null, null, "", null, 5.0),
                Arrays.asList(date("2023-03-15"), 2.0, "e", "x", null)), rows(c, t));
        Path formulas = dir.resolve("formulas-header.xlsx");
        Files.write(formulas, Zips.zip(Zips.workbook("<row r='1'><c r='A1' t='str'><v>a</v></c>"
                + "<c r='B1' t='str'><v>b</v></c></row><row r='2'><c r='A2'><v>1</v></c><c r='B2'><v>2</v></c></row>"),
                false));
        // Formula texts make no header.
        assertEquals(schema("A1", DataType.VARCHAR, "B1", DataType.VARCHAR), c.catalog().schema(call(c, formulas)));
    }

    @Test
    void sheetsAndMergedCells() throws IOException {
        Path file;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            rows(wb.createSheet("hidden"), new Object[] {"h"}, new Object[] {1});
            rows(wb.createSheet("second"), new Object[] {"x"}, new Object[] {2});
            Sheet merged = wb.createSheet("merged");
            rows(merged, new Object[] {"a", "b"}, new Object[] {1, null}, new Object[] {null, null},
                    new Object[] {4, 5});
            merged.addMergedRegion(new CellRangeAddress(1, 2, 0, 1));
            wb.setSheetHidden(0, true);
            file = save(wb, "sheets.xlsx");
        }
        XlsxConnector c = connector();
        assertEquals(List.of(List.of(1.0)), rows(c, call(c, file)));
        assertEquals(List.of(List.of(2.0)), rows(c, call(c, file, "sheet", "second")));
        assertEquals(Arrays.asList(Arrays.asList(1.0, null)), rows(c, call(c, file, "sheet", "merged")));
        assertEquals(Arrays.asList(Arrays.asList(1.0, null), Arrays.asList(null, null), Arrays.asList(4.0, 5.0)),
                rows(c, call(c, file, "sheet", "merged", "stop_at_empty", false)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call(c, file, "sheet", "SECOND"));
        assertEquals("Sheet \"SECOND\" not found in xlsx file \"" + file + "\" Did you mean: \"second\"",
                e.getMessage());
    }

    @Test
    void inlineStringsOfStreamedWorkbooks() throws IOException {
        Path file = dir.resolve("streamed.xlsx");
        try (SXSSFWorkbook wb = new SXSSFWorkbook(10)) {
            Sheet s = wb.createSheet("Data");
            rows(s, new Object[] {"n", "s"});
            for (int i = 1; i <= 500; i++) {
                rows(s, i, new Object[] {i, "row " + i});
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
            wb.close();
        }
        XlsxConnector c = XlsxConnector.builder("x").batchSize(64).file("t", file).build();
        TableRef t = TableRef.of("x", "t");
        assertEquals(OptionalLong.of(500), c.catalog().rowCount(t));
        List<List<Object>> rows = rows(c, t);
        assertEquals(500, rows.size());
        assertEquals(List.of(500.0, "row 500"), rows.get(499));
    }

    @Test
    void emptySheetsAndOnlyAHeader() throws IOException {
        Path empty = workbook("empty.xlsx", s -> { });
        XlsxConnector c = connector();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call(c, empty));
        assertEquals("No rows found in xlsx file \"" + empty + "\"", e.getMessage());
        Path onlyHeader = workbook("onlyheader.xlsx", s -> rows(s, new Object[] {"a", "b"}));
        TableRef t = call(c, onlyHeader);
        assertEquals(schema("a", DataType.DOUBLE, "b", DataType.DOUBLE), c.catalog().schema(t));
        assertEquals(List.of(), rows(c, t));
    }

    @Test
    void argumentsAndCalls() throws IOException {
        Path file = workbook("a.xlsx", s -> rows(s, new Object[] {"x"}, new Object[] {1}));
        XlsxConnector c = connector();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> call(c, file, "bogus", 1));
        assertEquals("Invalid named parameter \"bogus\" for function read_xlsx; supported are header, sheet, range,"
                + " stop_at_empty, all_varchar, ignore_errors, empty_as_varchar, normalize_names", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> call(c, file, "header", "maybe"));
        assertEquals("The argument header of read_xlsx must be a BOOLEAN", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> call(c, dir.resolve("missing.xlsx")));
        assertEquals("No files found that match the pattern \"" + dir.resolve("missing.xlsx") + "\"", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> c.bind("read_xlsx", List.of(dir + "/*.xlsx"), Map.of()));
        assertTrue(e.getMessage().startsWith("Patterns of file names are not supported yet"), e.getMessage());
        Path text = dir.resolve("text.xlsx");
        Files.writeString(text, "a,b\n1,2\n".repeat(10));
        e = assertThrows(IllegalArgumentException.class, () -> call(c, text));
        assertEquals(text + " is no valid xlsx workbook: no end of a central directory", e.getMessage());
        TableRef first = call(c, file, "header", "true");
        assertEquals(first, call(c, file, "header", true));
        assertNotEquals(first, call(c, file));
        workbook("a.xlsx", s -> rows(s, new Object[] {"y"}, new Object[] {2}));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()
                + 5000));
        TableRef changed = call(c, file, "header", true);
        assertNotEquals(first, changed);
        assertEquals(List.of("y"), names(c.catalog().schema(changed)));
    }

    @Test
    void workbooksInOtherStores() throws IOException {
        MemoryStore store = new MemoryStore();
        Path local = workbook("remote.xlsx", s -> rows(s, new Object[] {"x", "y"}, new Object[] {1, "a"},
                new Object[] {2, "b"}));
        store.put("mem://bucket/remote.xlsx", Files.readAllBytes(local));
        XlsxConnector c = XlsxConnector.builder("x").file("r", "mem://bucket/remote.xlsx").build();
        c.fileStores(new FileStores(Map.of("mem", store), dir));
        assertEquals(List.of(List.of(1.0, "a"), List.of(2.0, "b")), rows(c, TableRef.of("x", "r")));
        TableRef t = c.bind("read_xlsx", List.of("mem://bucket/remote.xlsx"), Map.of("sheet", "Sheet1"));
        assertEquals(List.of(List.of("a"), List.of("b")), rows(c, t, 1));
    }

    @Test
    void sourcesFromOptions() throws IOException {
        workbook("one.xlsx", s -> rows(s, new Object[] {"x"}, new Object[] {1}));
        workbook("two.xlsm", s -> rows(s, new Object[] {"y"}, new Object[] {"a"}));
        Files.writeString(dir.resolve("notes.txt"), "no workbook");
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Path many = sub.resolve("many.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            rows(wb.createSheet("first"), new Object[] {"f"}, new Object[] {1});
            Sheet second = wb.createSheet("second");
            rows(second, new Object[] {"skip"}, new Object[] {"g", "h"}, new Object[] {2, 3});
            try (OutputStream out = Files.newOutputStream(many)) {
                wb.write(out);
            }
        }
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream().map(ServiceLoader.Provider::get)
                .filter(f -> f.type().equals("xlsx")).findFirst().orElseThrow();
        assertEquals(List.of("xlsx"), factory.fileFormats());
        Map<String, String> options = new LinkedHashMap<>();
        options.put("directory", ".");
        options.put("tables.part.file", "sub/many.xlsx");
        options.put("tables.part.sheet", "second");
        options.put("tables.part.range", "A2:B3");
        options.put("allVarchar", "true");
        XlsxConnector c = (XlsxConnector) factory.create("x", options, dir);
        assertEquals(List.of("one", "two", "part"), c.catalog().tables("main"));
        assertEquals(List.of(List.of("1.0")), rows(c, TableRef.of("x", "one")));
        assertEquals(List.of(List.of("2.0", "3.0")), rows(c, TableRef.of("x", "part")));
        assertEquals(List.of("g", "h"), names(c.catalog().schema(TableRef.of("x", "part"))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("x", Map.of("directory", ".", "stopAtEmpty", "true", "allVarchars", "true"),
                        dir));
        assertEquals("The Excel source x has an unknown option allVarchars; known are header, stopAtEmpty,"
                + " allVarchar, ignoreErrors, emptyAsVarchar, normalizeNames, batchSize, directory, tables.<name>,"
                + " tables.<name>.file, tables.<name>.sheet, tables.<name>.range", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> factory.create("x", Map.of("tables.bad", "notes.txt"), dir));
        assertEquals("The table bad of the Excel source x: " + dir.resolve("notes.txt")
                + " is no valid xlsx workbook: too short for a zip archive", e.getMessage());
    }

    @Test
    void copyFromWithGivenColumns() throws IOException {
        Path file = workbook("copy.xlsx", s -> rows(s, new Object[] {"x", "y"}, new Object[] {1, "p"},
                new Object[] {2.6, "q"}));
        XlsxConnector c = connector();
        Schema given = schema("a", DataType.INTEGER, "b", DataType.VARCHAR);
        TableRef t = c.fileTable("xlsx", file.toString(), given, Map.of("timezone", "UTC"));
        assertEquals(given, c.catalog().schema(t));
        assertEquals(List.of(List.of(1, "p"), List.of(3, "q")), rows(c, t));
        assertEquals(t, c.fileTable("xlsx", file.toString(), given, Map.of("timezone", "UTC")));
        TableRef noHeader = c.fileTable("xlsx", file.toString(), given, Map.of("header", "false"));
        ConversionException e = assertThrows(ConversionException.class, () -> rows(c, noHeader));
        assertTrue(e.getMessage().startsWith("Failed to parse cell 'A1' of the sheet \"Sheet1\" of " + file + ": "),
                e.getMessage());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> c.fileTable("xlsx", file.toString(), given, Map.of("delimiter", ";")));
        assertTrue(bad.getMessage().startsWith("The Excel file " + file + " has an unknown option delimiter;"),
                bad.getMessage());
    }

    private XlsxConnector connector() {
        return XlsxConnector.builder("x").build();
    }

    private static TableRef call(XlsxConnector c, Path file, Object... named) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < named.length; i += 2) {
            arguments.put((String) named[i], named[i + 1]);
        }
        arguments.put("timezone", "UTC");
        return c.bind("read_xlsx", List.of(file.toString()), arguments);
    }

    static List<List<Object>> rows(XlsxConnector c, TableRef t, int... columns) {
        int[] cols = columns.length > 0 ? columns
                : IntStream.range(0, c.catalog().schema(t).size()).toArray();
        ScanPlan plan = c.plan(t, ScanRequest.columns(Arrays.stream(cols).boxed().toList()));
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }

    private static long count(XlsxConnector c, TableRef t) {
        ScanPlan plan = c.plan(t, ScanRequest.columns(List.of(0)));
        long n = 0;
        for (Batch b : Batches.readAll(c.open(c.splits(plan).get(0)))) {
            n += b.rowCount();
        }
        return n;
    }

    static List<String> names(Schema schema) {
        return schema.fields().stream().map(Schema.Field::name).toList();
    }

    static Schema schema(Object... namesAndTypes) {
        List<Schema.Field> fields = new ArrayList<>();
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            fields.add(new Schema.Field((String) namesAndTypes[i], (DataType) namesAndTypes[i + 1]));
        }
        return new Schema(fields);
    }

    private Path workbook(String name, Consumer<Sheet> fill) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            fill.accept(wb.createSheet("Sheet1"));
            return save(wb, name);
        }
    }

    private Path save(XSSFWorkbook wb, String name) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            wb.write(out);
        }
        return file;
    }

    /** Writes rows from the first row on; a null is no cell. */
    static void rows(Sheet sheet, Object[]... rows) {
        for (int r = 0; r < rows.length; r++) {
            rows(sheet, sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1, rows[r]);
        }
    }

    static void rows(Sheet sheet, int index, Object[] values) {
        Row row = sheet.createRow(index);
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

    static int date(String date) {
        return (int) LocalDate.parse(date).toEpochDay();
    }

    static long ts(String timestamp) {
        LocalDateTime t = LocalDateTime.parse(timestamp);
        return t.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + t.getNano() / 1000;
    }

    static long time(String time) {
        return LocalTime.parse(time).toNanoOfDay() / 1000;
    }
}
