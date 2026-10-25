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

import net.fyrql.connector.FileStores;
import net.fyrql.connector.Sink;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Interval;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.ZipFile;

import static net.fyrql.connector.xlsx.XlsxConnectorTest.date;
import static net.fyrql.connector.xlsx.XlsxConnectorTest.schema;
import static net.fyrql.connector.xlsx.XlsxConnectorTest.time;
import static net.fyrql.connector.xlsx.XlsxConnectorTest.ts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XlsxFileWriterTest {

    @TempDir
    Path dir;

    private static final Schema ALL = schema("ti", DataType.TINYINT, "si", DataType.SMALLINT, "i", DataType.INTEGER,
            "bi", DataType.BIGINT, "hi", DataType.HUGEINT, "f", DataType.FLOAT, "d", DataType.DOUBLE, "de",
            DataType.decimal(10, 3), "s", DataType.VARCHAR, "b", DataType.BOOLEAN, "dt", DataType.DATE, "ts",
            DataType.TIMESTAMP, "tz", DataType.TIMESTAMPTZ, "t", DataType.TIME, "iv", DataType.INTERVAL, "bl",
            DataType.BLOB, "n", DataType.INTEGER, "li", DataType.listOf(DataType.INTEGER));

    private static Batch allTypes() {
        Object[] row = {(byte) 1, (short) 2, 3, 9_007_199_254_740_993L, new BigInteger("12345678901234567890"), 1.5f,
            0.1, new BigDecimal("123.456"), "a<b&\"c'", true, date("2024-02-29"), ts("2024-02-29T13:45:30.123456"),
            ts("2024-02-29T11:45:30"), time("13:45:30.5"), Interval.parse("1 day"),
            "x".getBytes(StandardCharsets.UTF_8),
            null, List.of(1, 2)};
        Object[] second = {null, null, null, null, null, Float.NaN, Double.NEGATIVE_INFINITY, null,
            "line1\nline2" + (char) 1 + "_x0041_", false, date("1900-01-01"), ts("1899-12-31T12:00"), null,
            time("00:00:00.123456"), null, null, null, null};
        List<Vector> vectors = new ArrayList<>();
        for (int c = 0; c < ALL.size(); c++) {
            vectors.add(Vectors.of(ALL.type(c), Arrays.asList(row[c], second[c])));
        }
        return new Batch(ALL, 2, vectors);
    }

    @Test
    void poiReadsWhatIsWritten() throws IOException {
        Path file = dir.resolve("all.xlsx");
        write(file, ALL, Map.of("header", "true", "timezone", "Europe/Berlin"), allTypes());
        try (InputStream in = Files.newInputStream(file); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            XSSFSheet sheet = wb.getSheet("Sheet1");
            Row header = sheet.getRow(0);
            for (int c = 0; c < ALL.size(); c++) {
                assertEquals(ALL.name(c), header.getCell(c).getStringCellValue());
            }
            Row r = sheet.getRow(1);
            assertEquals(1.0, r.getCell(0).getNumericCellValue());
            assertEquals(9.007199254740992E15, r.getCell(3).getNumericCellValue());
            assertEquals(1.2345678901234567E19, r.getCell(4).getNumericCellValue());
            assertEquals(1.5, r.getCell(5).getNumericCellValue());
            assertEquals(0.1, r.getCell(6).getNumericCellValue());
            assertEquals(123.456, r.getCell(7).getNumericCellValue());
            assertEquals("a<b&\"c'", r.getCell(8).getStringCellValue());
            assertEquals(CellType.BOOLEAN, r.getCell(9).getCellType());
            assertTrue(r.getCell(9).getBooleanCellValue());
            assertTrue(DateUtil.isCellDateFormatted(r.getCell(10)));
            assertEquals(LocalDateTime.parse("2024-02-29T00:00"), r.getCell(10).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.parse("2024-02-29T13:45:30.123"), r.getCell(11).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.parse("2024-02-29T12:45:30"), r.getCell(12).getLocalDateTimeCellValue());
            assertTrue(DateUtil.isCellDateFormatted(r.getCell(13)));
            assertEquals((13 * 3600 + 45 * 60 + 30.5) / 86400, r.getCell(13).getNumericCellValue(), 1e-12);
            assertEquals("1 day", r.getCell(14).getStringCellValue());
            assertEquals("x", r.getCell(15).getStringCellValue());
            assertEquals(null, r.getCell(16));
            assertEquals("[1, 2]", r.getCell(17).getStringCellValue());
            Row s = sheet.getRow(2);
            assertEquals("nan", s.getCell(5).getStringCellValue());
            assertEquals("-inf", s.getCell(6).getStringCellValue());
            assertEquals("line1\nline2" + (char) 1 + "_x0041_", s.getCell(8).getStringCellValue());
            assertFalse(s.getCell(9).getBooleanCellValue());
            assertEquals(LocalDateTime.parse("1900-01-01T00:00"), s.getCell(10).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.parse("1899-12-31T12:00"), s.getCell(11).getLocalDateTimeCellValue());
        }
    }

    @Test
    void readsBackWhatIsWritten() throws IOException {
        Path file = dir.resolve("back.xlsx");
        write(file, ALL, Map.of("header", "true", "timezone", "Europe/Berlin"), allTypes());
        XlsxConnector c = XlsxConnector.builder("x").build();
        TableRef t = c.bind("read_xlsx", List.of(file.toString()), Map.of("timezone", "UTC"));
        assertEquals(List.of(DataType.DOUBLE, DataType.DOUBLE, DataType.DOUBLE, DataType.DOUBLE, DataType.DOUBLE,
                DataType.DOUBLE, DataType.DOUBLE, DataType.DOUBLE, DataType.VARCHAR, DataType.BOOLEAN, DataType.DATE,
                DataType.TIMESTAMP, DataType.TIMESTAMP, DataType.TIME, DataType.VARCHAR, DataType.VARCHAR,
                DataType.DOUBLE, DataType.VARCHAR), c.catalog().schema(t).types());
        List<List<Object>> rows = XlsxConnectorTest.rows(c, t);
        assertEquals(Arrays.asList(1.0, 2.0, 3.0, 9.007199254740992E15, 1.2345678901234567E19, 1.5, 0.1, 123.456,
                "a<b&\"c'", true, date("2024-02-29"), ts("2024-02-29T13:45:30.123456"), ts("2024-02-29T12:45:30"),
                time("13:45:30.5"), "1 day", "x", null, "[1, 2]"), rows.get(0));
        assertEquals(Arrays.asList(null, null, null, null, null, Double.NaN, Double.NEGATIVE_INFINITY, null,
                "line1\nline2" + (char) 1 + "_x0041_", false, date("1900-01-01"), ts("1899-12-31T12:00"), null,
                time("00:00:00.123456"), null, null, null, null), rows.get(1));
        Schema flat = ALL.select(IntStream.range(0, ALL.size() - 1).boxed().toList());
        TableRef given = c.fileTable("xlsx", file.toString(), flat, Map.of("timezone", "Europe/Berlin"));
        List<Object> typed = XlsxConnectorTest.rows(c, given).get(0);
        assertEquals(Arrays.asList((byte) 1, (short) 2, 3, 9_007_199_254_740_992L), typed.subList(0, 4));
        assertEquals(ts("2024-02-29T11:45:30"), typed.get(12));
    }

    @Test
    void thePartsAsWritten() throws IOException {
        Path file = dir.resolve("parts.xlsx");
        Schema schema = schema("n", DataType.INTEGER, "s", DataType.VARCHAR, "d", DataType.DATE);
        Batch batch = new Batch(schema, 2, List.of(Vectors.of(DataType.INTEGER, Arrays.asList(1, null)),
                Vectors.of(DataType.VARCHAR, Arrays.asList(" a ", null)),
                Vectors.of(DataType.DATE, Arrays.asList(date("2024-02-29"), null))));
        write(file, schema, Map.of("sheet", "My Data"), batch);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                    "xl/styles.xml", "xl/worksheets/sheet1.xml"), zip.stream().map(e -> e.getName()).toList());
            String sheet = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(sheet.endsWith("<sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\" t=\"inlineStr\"><is>"
                    + "<t xml:space=\"preserve\"> a </t></is></c><c r=\"C1\" s=\"1\"><v>45351.0</v></c></row>"
                    + "<row r=\"2\"></row></sheetData></worksheet>"), sheet);
            String workbook = new String(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(workbook.contains("<sheet name=\"My Data\" sheetId=\"1\" r:id=\"rId1\"/>"), workbook);
        }
    }

    @Test
    void optionsAndLimits() throws IOException {
        Schema schema = schema("n", DataType.INTEGER);
        Batch five = new Batch(schema, 5, List.of(Vectors.of(DataType.INTEGER, Arrays.asList(1, 2, 3, 4, 5))));
        XlsxConnector c = XlsxConnector.builder("x").build();
        Path limited = dir.resolve("limited.xlsx");
        try (Sink sink = c.openFile("xlsx", limited.toString(), schema, Map.of("sheet_row_limit", "3"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> sink.write(five));
            assertEquals("The Excel file " + limited + " exceeds its limit of 3 rows of the sheet; the option"
                    + " sheet_row_limit sets it, to at most 1048576", e.getMessage());
        }
        assertFalse(Files.exists(limited));
        write(limited, schema, Map.of("sheet_row_limit", "5"), five);
        assertTrue(Files.exists(limited));
        for (String bad : new String[] {"", "a/b", "x[1]", "what?", "'quoted'", "abcdefghijklmnopqrstuvwxyz0123456",
            "ctl" + (char) 1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> c.openFile("xlsx", dir.resolve("bad.xlsx").toString(), schema, Map.of("sheet", bad)));
            assertTrue(e.getMessage().startsWith("The sheet name \"" + bad + "\" of the Excel file "), e.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> c.openFile("xlsx", dir.resolve("bad.xlsx").toString(), schema, Map.of("bogus", "1")));
        assertEquals("The Excel file " + dir.resolve("bad.xlsx") + " has an unknown option bogus; known are header,"
                + " sheet, sheet_row_limit, timezone", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> c.openFile("xlsx", dir.resolve("bad.xlsx").toString(),
                schema, Map.of("sheet_row_limit", "0")));
        assertEquals("The option sheet_row_limit of the Excel file " + dir.resolve("bad.xlsx") + " must be a number"
                + " of rows from 1 to 1048576: 0", e.getMessage());
        assertFalse(Files.exists(dir.resolve("bad.xlsx")));
    }

    @Test
    void writesIntoOtherStores() throws IOException {
        MemoryStore store = new MemoryStore();
        XlsxConnector c = XlsxConnector.builder("x").build();
        c.fileStores(new FileStores(Map.of("mem", store), dir));
        Schema schema = schema("s", DataType.VARCHAR);
        try (Sink sink = c.openFile("xlsx", "mem://b/out.xlsx", schema, Map.of("header", "true"))) {
            sink.write(new Batch(schema, 1, List.of(Vectors.of(DataType.VARCHAR, List.of("hello")))));
            assertEquals(1, sink.commit());
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(store.get("mem://b/out.xlsx")))) {
            Cell cell = wb.getSheetAt(0).getRow(1).getCell(0);
            assertEquals("hello", cell.getStringCellValue());
        }
        TableRef t = c.bind("read_xlsx", List.of("mem://b/out.xlsx"), Map.of());
        assertEquals(List.of(List.of("hello")), XlsxConnectorTest.rows(c, t));
    }

    private void write(Path file, Schema schema, Map<String, String> options, Batch batch) {
        XlsxConnector c = XlsxConnector.builder("x").build();
        try (Sink sink = c.openFile("xlsx", file.toString(), schema, options)) {
            sink.write(batch);
            assertEquals(batch.rowCount(), sink.commit());
        }
    }
}
