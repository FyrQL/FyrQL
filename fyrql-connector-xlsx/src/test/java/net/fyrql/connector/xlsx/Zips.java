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

import net.fyrql.connector.FileObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds zip archives and workbooks by hand for the tests, and damages them on purpose. */
final class Zips {

    private Zips() {
    }

    /** Returns an archive of entries, deflated or stored. */
    static byte[] zip(Map<String, byte[]> entries, boolean stored) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Returns a deflated archive of entries given as text. */
    static byte[] zip(String... namesAndTexts) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            entries.put(namesAndTexts[i], namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return zip(entries, false);
    }

    /** Returns a file in memory of bytes, as a bucket of S3 serves it. */
    static FileObject file(byte[] bytes) {
        MemoryStore store = new MemoryStore();
        store.put("mem://b/book.xlsx", bytes);
        try {
            return store.object("mem://b/book.xlsx");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the position of the end of the central directory of an archive without a comment. */
    static int end(byte[] zip) {
        return zip.length - 22;
    }

    /** Returns the position of the first header of the central directory. */
    static int central(byte[] zip) {
        return ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).getInt(end(zip) + 16);
    }

    /** Writes a little-endian number of 2 or 4 bytes into a copy of an archive. */
    static byte[] patch(byte[] zip, int position, int bytes, long value) {
        byte[] copy = zip.clone();
        ByteBuffer b = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes == 2) {
            b.putShort(position, (short) value);
        } else {
            b.putInt(position, (int) value);
        }
        return copy;
    }

    /** The parts of a minimal workbook of one sheet with the sheet data given, in the transitional namespaces. */
    static Map<String, byte[]> workbook(String sheetData) {
        String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        Map<String, byte[]> parts = new LinkedHashMap<>();
        put(parts, "[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'>"
                + "<Default Extension='xml' ContentType='application/xml'/></Types>");
        put(parts, "_rels/.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + "<Relationship Id='rId1' Type='" + rel + "/officeDocument' Target='xl/workbook.xml'/>"
                + "</Relationships>");
        put(parts, "xl/workbook.xml", "<workbook xmlns='" + main + "' xmlns:r='" + rel + "'><sheets>"
                + "<sheet name='Sheet1' sheetId='1' r:id='rId1'/></sheets></workbook>");
        put(parts, "xl/_rels/workbook.xml.rels",
                "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'>"
                + "<Relationship Id='rId1' Type='" + rel + "/worksheet' Target='worksheets/sheet1.xml'/>"
                + "</Relationships>");
        put(parts, "xl/worksheets/sheet1.xml", "<worksheet xmlns='" + main + "'><sheetData>" + sheetData
                + "</sheetData></worksheet>");
        return parts;
    }

    /** Puts a part given as text. */
    static void put(Map<String, byte[]> parts, String name, String text) {
        parts.put(name, text.getBytes(StandardCharsets.UTF_8));
    }
}
