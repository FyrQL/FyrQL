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

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbookTest {

    private static final String LOCATION = "mem://b/book.xlsx";

    @Test
    void sheetsOfPoiWorkbooks() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("hidden one").createRow(0).createCell(0).setCellValue(1);
            wb.createSheet("Second").createRow(0).createCell(0).setCellValue("x");
            wb.createSheet("third");
            wb.setSheetHidden(0, true);
            bytes = write(wb);
        }
        Workbook wb = Workbook.read(ZipArchive.open(Zips.file(bytes)));
        assertEquals(List.of("hidden one", "Second", "third"), wb.sheets().stream().map(Workbook.Sheet::name).toList());
        assertTrue(wb.sheets().get(0).hidden());
        assertFalse(wb.sheets().get(1).hidden());
        assertEquals("xl/worksheets/sheet2.xml", wb.sheets().get(1).part());
        assertFalse(wb.date1904());
        assertEquals("hidden one", wb.sheet(null).name());
        assertEquals("Second", wb.sheet("Second").name());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> wb.sheet("second"));
        assertEquals("Sheet \"second\" not found in xlsx file \"" + LOCATION + "\" Did you mean: \"Second\"",
                e.getMessage());
        assertEquals("xl/sharedStrings.xml", wb.sharedStrings().orElseThrow());
        assertEquals("xl/styles.xml", wb.styles().orElseThrow());
    }

    @Test
    void date1904() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("s");
            wb.getCTWorkbook().getWorkbookPr().setDate1904(true);
            bytes = write(wb);
        }
        assertTrue(Workbook.read(ZipArchive.open(Zips.file(bytes))).date1904());
    }

    @Test
    void sharedStringsWithRichTextPhoneticsAndEscapes() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("s");
            XSSFRichTextString rich = new XSSFRichTextString("plain bold");
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            rich.applyFont(6, 10, bold);
            sheet.createRow(0).createCell(0).setCellValue(rich);
            sheet.createRow(1).createCell(0).setCellValue("second");
            bytes = write(wb);
        }
        Workbook wb = Workbook.read(ZipArchive.open(Zips.file(bytes)));
        SharedStrings strings = SharedStrings.read(wb);
        assertEquals(2, strings.size());
        assertEquals("plain bold", strings.get(0));
        assertEquals("second", strings.get(1));

        Map<String, byte[]> parts = Zips.workbook("");
        Zips.put(parts, "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + "<Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + "worksheet' Target='worksheets/sheet1.xml'/><Relationship Id='rId2' Type='http://schemas."
                + "openxmlformats.org/officeDocument/2006/relationships/sharedStrings' Target='/xl/strings.xml'/>"
                + "</Relationships>");
        Zips.put(parts, "xl/strings.xml", "<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'>"
                + "<si><t>\u6771\u4eac</t><rPh sb='0' eb='2'><t>\u30c8\u30a6</t></rPh><phoneticPr fontId='1'/></si>"
                + "<si><r><t xml:space='preserve'>a </t></r><r><rPr><b/></rPr><t>b</t></r></si>"
                + "<si><t>_x0041__x005F_x0042_ tab_x0009_ _x00e4_ _xZZZZ_ _x12_</t></si><si><t/></si></sst>");
        strings = SharedStrings.read(Workbook.read(ZipArchive.open(Zips.file(Zips.zip(parts, false)))));
        assertEquals(List.of("\u6771\u4eac", "a b", "A_x0042_ tab\t \u00e4 _xZZZZ_ _x12_", ""),
                List.of(strings.get(0), strings.get(1), strings.get(2), strings.get(3)));
    }

    @Test
    void stylesOfPoiWorkbooks() throws IOException {
        String[] formats = {"General", "0.00", "yyyy-mm-dd", "dd/mm/yyyy hh:mm", "h:mm:ss", "[h]:mm:ss", "mm:ss",
            "0%", "m/d/yy", "\"Day \"0", "[$-409]mmmm d, yyyy;@"};
        Styles.Kind[] kinds = {Styles.Kind.NUMBER, Styles.Kind.NUMBER, Styles.Kind.DATE, Styles.Kind.TIMESTAMP,
            Styles.Kind.TIME, Styles.Kind.NUMBER, Styles.Kind.NUMBER, Styles.Kind.NUMBER, Styles.Kind.DATE,
            Styles.Kind.NUMBER, Styles.Kind.DATE};
        byte[] bytes;
        short[] styles = new short[formats.length];
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("s");
            DataFormat df = wb.createDataFormat();
            for (int i = 0; i < formats.length; i++) {
                CellStyle style = wb.createCellStyle();
                style.setDataFormat(df.getFormat(formats[i]));
                styles[i] = style.getIndex();
            }
            bytes = write(wb);
        }
        Styles read = Styles.read(Workbook.read(ZipArchive.open(Zips.file(bytes))));
        for (int i = 0; i < formats.length; i++) {
            assertEquals(kinds[i], read.kind(styles[i]), formats[i]);
        }
        assertEquals(Styles.Kind.NUMBER, read.kind(0));
        assertEquals(Styles.Kind.NUMBER, read.kind(999));
    }

    @Test
    void strictOpenXmlReadsLikeTransitional() throws IOException {
        String main = "http://purl.oclc.org/ooxml/spreadsheetml/main";
        String rel = "http://purl.oclc.org/ooxml/officeDocument/relationships";
        Map<String, byte[]> parts = Zips.workbook("");
        Zips.put(parts, "_rels/.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/"
                + "relationships'><Relationship Id='rId1' Type='" + rel + "/officeDocument' Target='/xl/workbook.xml'/>"
                + "</Relationships>");
        Zips.put(parts, "xl/workbook.xml", "<x:workbook xmlns:x='" + main + "' xmlns:r='" + rel + "'>"
                + "<x:workbookPr date1904='true'/><x:sheets><x:sheet name='Strict' sheetId='1' r:id='rId1'/>"
                + "</x:sheets></x:workbook>");
        Zips.put(parts, "xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/"
                + "package/2006/relationships'><Relationship Id='rId1' Type='" + rel + "/worksheet'"
                + " Target='./worksheets/../worksheets/sheet1.xml'/></Relationships>");
        Workbook wb = Workbook.read(ZipArchive.open(Zips.file(Zips.zip(parts, false))));
        assertEquals("Strict", wb.sheet(null).name());
        assertEquals("xl/worksheets/sheet1.xml", wb.sheet(null).part());
        assertTrue(wb.date1904());
    }

    @Test
    void chartSheetsHaveNoCells() throws IOException {
        Map<String, byte[]> parts = Zips.workbook("");
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        Zips.put(parts, "xl/workbook.xml", "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'"
                + " xmlns:r='" + rel + "'><sheets><sheet name='Chart' sheetId='2' r:id='rId2'/>"
                + "<sheet name='Data' sheetId='1' r:id='rId1'/></sheets></workbook>");
        Zips.put(parts, "xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/"
                + "package/2006/relationships'><Relationship Id='rId1' Type='" + rel + "/worksheet'"
                + " Target='worksheets/sheet1.xml'/><Relationship Id='rId2' Type='" + rel + "/chartsheet'"
                + " Target='chartsheets/sheet1.xml'/></Relationships>");
        Zips.put(parts, "xl/chartsheets/sheet1.xml", "<chartsheet/>");
        Workbook wb = Workbook.read(ZipArchive.open(Zips.file(Zips.zip(parts, false))));
        assertEquals("Data", wb.sheet(null).name());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> wb.sheet("Chart"));
        assertEquals("Sheet \"Chart\" of xlsx file \"" + LOCATION + "\" is a chart sheet or dialog sheet without"
                + " cells", e.getMessage());
    }

    @Test
    void documentTypeDeclarationsAreRefused() throws IOException {
        String refused = "xl/workbook.xml of " + LOCATION + " has a document type declaration, which is not allowed";
        String[] documents = {
            "<?xml version='1.0'?><!DOCTYPE workbook [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                + "<workbook><sheets><sheet name='&x;'/></sheets></workbook>",
            "<?xml version='1.0'?><!DOCTYPE r [<!ENTITY a 'aaaaaaaaaa'>"
                + "<!ENTITY b '&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;'><!ENTITY c '&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;'>]>"
                + "<workbook>&c;</workbook>",
            "<?xml version='1.0'?><!DOCTYPE r SYSTEM 'http://127.0.0.1:1/x.dtd'><workbook/>"};
        for (String document : documents) {
            Map<String, byte[]> parts = Zips.workbook("");
            Zips.put(parts, "xl/workbook.xml", document);
            ZipArchive zip = ZipArchive.open(Zips.file(Zips.zip(parts, false)));
            IOException e = assertThrows(IOException.class, () -> Workbook.read(zip));
            assertEquals(refused, e.getMessage());
        }
        Map<String, byte[]> parts = Zips.workbook("");
        Zips.put(parts, "xl/workbook.xml", "<workbook><sheets>");
        ZipArchive zip = ZipArchive.open(Zips.file(Zips.zip(parts, false)));
        IOException e = assertThrows(IOException.class, () -> Workbook.read(zip));
        assertTrue(e.getMessage().startsWith("xl/workbook.xml of " + LOCATION + " is not well-formed XML: "),
                e.getMessage());
    }

    @Test
    void archivesThatAreNoWorkbooks() throws IOException {
        ZipArchive other = ZipArchive.open(Zips.file(Zips.zip("readme.txt", "hello")));
        ZipException e = assertThrows(ZipException.class, () -> Workbook.read(other));
        assertEquals(LOCATION + " is no xlsx workbook: it has no workbook part", e.getMessage());
        Map<String, byte[]> parts = Zips.workbook("");
        Zips.put(parts, "_rels/.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/"
                + "relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/"
                + "2006/relationships/officeDocument' Target='xl/workbook.bin'/></Relationships>");
        ZipArchive binary = ZipArchive.open(Zips.file(Zips.zip(parts, false)));
        e = assertThrows(ZipException.class, () -> Workbook.read(binary));
        assertEquals(LOCATION + " is a binary workbook (.xlsb), which is not supported; save it as .xlsx",
                e.getMessage());
    }

    @Test
    void targetsResolve() {
        assertEquals("xl/worksheets/sheet1.xml", Workbook.resolve("xl/", "worksheets/sheet1.xml"));
        assertEquals("xl/worksheets/sheet1.xml", Workbook.resolve("xl/", "/xl/worksheets/sheet1.xml"));
        assertEquals("media/a.png", Workbook.resolve("xl/", "../media/a.png"));
        assertEquals("xl/workbook.xml", Workbook.resolve("", "./xl/workbook.xml"));
    }

    static byte[] write(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
