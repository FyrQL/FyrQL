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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;

/**
 * The shared strings of a workbook, which cells of the type {@code s}
 * refer to by their index. A string is the text of its {@code t} element,
 * or of the {@code t} elements of its runs of rich text one after the
 * other; phonetic runs ({@code rPh}) are not part of it. The escapes
 * {@code _xHHHH_} are decoded.
 * <p>
 * All strings are held in memory while a sheet is read, as every reader
 * of the format holds them; the limits of the archive bound their size.
 */
final class SharedStrings {

    private static final SharedStrings NONE = new SharedStrings(List.of());

    private final List<String> strings;

    private SharedStrings(List<String> strings) {
        this.strings = strings;
    }

    /** Returns the shared strings of a workbook, none if it has no such part. */
    static SharedStrings read(Workbook workbook) throws IOException {
        if (workbook.sharedStrings().isEmpty()) {
            return NONE;
        }
        String part = workbook.sharedStrings().get();
        ZipArchive archive = workbook.archive();
        List<String> strings = new ArrayList<>();
        try (InputStream in = archive.open(part);
                XmlInput xml = XmlInput.open(in, part + " of " + archive.file().location())) {
            StringBuilder current = null;
            for (int event = xml.nextTag(); event != XMLStreamConstants.END_DOCUMENT; event = xml.nextTag()) {
                String name = xml.name();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (name) {
                        case "si" -> current = new StringBuilder();
                        case "t" -> {
                            String text = xml.text();
                            if (current != null) {
                                current.append(text);
                            }
                        }
                        case "rPh", "phoneticPr" -> xml.skip();
                        default -> {
                            // Runs and their properties: the texts inside them count.
                        }
                    }
                } else if (name.equals("si") && current != null) {
                    strings.add(Escapes.decode(current.toString()));
                    current = null;
                }
            }
        }
        return new SharedStrings(strings);
    }

    /** Returns the number of strings. */
    int size() {
        return strings.size();
    }

    /**
     * Returns a string by its index.
     *
     * @throws IndexOutOfBoundsException if there is no string of the index
     */
    String get(int index) {
        return strings.get(index);
    }
}
