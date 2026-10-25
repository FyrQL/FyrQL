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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipArchiveTest {

    private static final String LOCATION = "mem://b/book.xlsx";

    @Test
    void storedAndDeflatedEntriesRead() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.xml", "<a/>".getBytes(StandardCharsets.UTF_8));
        entries.put("dir/B.xml", "<b>text</b>".getBytes(StandardCharsets.UTF_8));
        entries.put("empty", new byte[0]);
        for (boolean stored : new boolean[] {true, false}) {
            ZipArchive zip = ZipArchive.open(Zips.file(Zips.zip(entries, stored)));
            assertEquals(List.of("a.xml", "dir/B.xml", "empty"), List.copyOf(zip.names()));
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                assertArrayEquals(e.getValue(), readAll(zip, e.getKey()));
                assertEquals(e.getValue().length, zip.size(e.getKey()));
            }
            assertEquals("dir/B.xml", zip.find("DIR/b.XML").orElseThrow());
            assertTrue(zip.find("c.xml").isEmpty());
            NoSuchFileException e = assertThrows(NoSuchFileException.class, () -> zip.open("c.xml"));
            assertEquals(LOCATION + "!/c.xml", e.getMessage());
        }
    }

    @Test
    void largeEntriesReadInPieces() throws IOException {
        byte[] data = new byte[3 << 20];
        Random random = new Random(42);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + random.nextInt(4));
        }
        for (boolean stored : new boolean[] {true, false}) {
            ZipArchive zip = ZipArchive.open(Zips.file(Zips.zip(Map.of("big.xml", data), stored)));
            assertArrayEquals(data, readAll(zip, "big.xml"));
        }
    }

    @Test
    void backslashesInNamesAreSlashes() throws IOException {
        ZipArchive zip = ZipArchive.open(Zips.file(Zips.zip("xl\\workbook.xml", "<w/>")));
        assertEquals(List.of("xl/workbook.xml"), List.copyOf(zip.names()));
    }

    @Test
    void zip64IsRefused() {
        byte[] zip = Zips.zip("a.xml", "<a/>");
        byte[] count = Zips.patch(zip, Zips.end(zip) + 10, 2, 0xffff);
        ZipException e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(count)));
        assertEquals(LOCATION + " is a Zip64 archive, which is not supported", e.getMessage());
        byte[] withLocator = new byte[zip.length + 20];
        int end = Zips.end(zip);
        System.arraycopy(zip, 0, withLocator, 0, end);
        System.arraycopy(new byte[] {0x50, 0x4b, 0x06, 0x07}, 0, withLocator, end, 4);
        System.arraycopy(zip, end, withLocator, end + 20, 22);
        e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(withLocator)));
        assertEquals(LOCATION + " is a Zip64 archive, which is not supported", e.getMessage());
        byte[] size = Zips.patch(zip, Zips.central(zip) + 24, 4, 0xffffffffL);
        e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(size)));
        assertEquals(LOCATION + " is a Zip64 archive, which is not supported", e.getMessage());
    }

    @Test
    void encryptionAndOtherMethodsAreRefused() throws IOException {
        byte[] zip = Zips.zip("a.xml", "<a/>");
        ZipArchive encrypted = ZipArchive.open(Zips.file(Zips.patch(zip, Zips.central(zip) + 8, 2, 1)));
        ZipException e = assertThrows(ZipException.class, () -> encrypted.open("a.xml"));
        assertEquals("The entry a.xml of " + LOCATION + " is encrypted, which is not supported", e.getMessage());
        ZipArchive bzip2 = ZipArchive.open(Zips.file(Zips.patch(zip, Zips.central(zip) + 10, 2, 12)));
        e = assertThrows(ZipException.class, () -> bzip2.open("a.xml"));
        assertEquals("The entry a.xml of " + LOCATION + " is compressed with the method 12, which is not supported;"
                + " supported are stored and deflate", e.getMessage());
        byte[] ole = new byte[512];
        System.arraycopy(new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a,
            (byte) 0xe1}, 0, ole, 0, 8);
        e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(ole)));
        assertEquals(LOCATION + " is an encrypted workbook or an old .xls workbook, which are not supported; save it"
                + " as an unencrypted .xlsx workbook", e.getMessage());
    }

    @Test
    void damagedArchivesAreRefused() throws IOException {
        ZipException e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(new byte[10])));
        assertEquals(LOCATION + " is no valid xlsx workbook: too short for a zip archive", e.getMessage());
        byte[] text = "a,b,c\n1,2,3\n".repeat(10).getBytes(StandardCharsets.UTF_8);
        e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(text)));
        assertEquals(LOCATION + " is no valid xlsx workbook: no end of a central directory", e.getMessage());
        byte[] zip = Zips.zip("a.xml", "<a>" + "x".repeat(1000) + "</a>");
        byte[] cut = Arrays.copyOf(zip, zip.length - 30);
        e = assertThrows(ZipException.class, () -> ZipArchive.open(Zips.file(cut)));
        assertEquals(LOCATION + " is no valid xlsx workbook: no end of a central directory", e.getMessage());
        ZipArchive wrongCrc = ZipArchive.open(Zips.file(Zips.patch(zip, Zips.central(zip) + 16, 4, 12345)));
        e = assertThrows(ZipException.class, () -> readAll(wrongCrc, "a.xml"));
        assertEquals("The entry a.xml of " + LOCATION + " has a wrong checksum", e.getMessage());
        ZipArchive shorter = ZipArchive.open(Zips.file(Zips.patch(zip, Zips.central(zip) + 24, 4, 100)));
        e = assertThrows(ZipException.class, () -> readAll(shorter, "a.xml"));
        assertEquals("The entry a.xml of " + LOCATION + " inflates to more than its size of 100 bytes",
                e.getMessage());
        ZipArchive longer = ZipArchive.open(Zips.file(Zips.patch(zip, Zips.central(zip) + 24, 4, 5000)));
        e = assertThrows(ZipException.class, () -> readAll(longer, "a.xml"));
        assertEquals("The entry a.xml of " + LOCATION + " has 1007 bytes instead of 5000", e.getMessage());
    }

    @Test
    void bombsAreRefused() throws IOException {
        // 80 MiB of zeros deflate to some 80 KiB, a ratio of about 1000.
        long plain = 80L << 20;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        CRC32 crc = new CRC32();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed, new Deflater(9, true))) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 80; i++) {
                out.write(zeros);
                crc.update(zeros);
            }
        }
        // The directory gives the true size, so only the ratio stops the entry.
        ZipArchive archive = ZipArchive.open(Zips.file(bombArchive(compressed.toByteArray(), crc.getValue(), plain)));
        ZipException e = assertThrows(ZipException.class, () -> readAll(archive, "sheet.xml"));
        assertEquals("The entry sheet.xml of " + LOCATION + " inflates to more than 100 times its compressed size",
                e.getMessage());
    }

    /** Returns an archive of one deflated entry {@code sheet.xml} with the compressed bytes given. */
    private static byte[] bombArchive(byte[] deflated, long crc, long plain) {
        byte[] name = "sheet.xml".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(30 + name.length + deflated.length + 46 + name.length + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x04034b50).putShort((short) 20).putShort((short) 0).putShort((short) 8).putInt(0)
                .putInt((int) crc).putInt(deflated.length).putInt((int) plain).putShort((short) name.length)
                .putShort((short) 0).put(name).put(deflated);
        int central = b.position();
        b.putInt(0x02014b50).putShort((short) 20).putShort((short) 20).putShort((short) 0).putShort((short) 8)
                .putInt(0).putInt((int) crc).putInt(deflated.length).putInt((int) plain).putShort((short) name.length)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(0)
                .put(name);
        int size = b.position() - central;
        b.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1)
                .putInt(size).putInt(central).putShort((short) 0);
        return b.array();
    }

    private static byte[] readAll(ZipArchive zip, String name) throws IOException {
        try (InputStream in = zip.open(name)) {
            return in.readAllBytes();
        }
    }
}
