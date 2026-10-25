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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;

/**
 * What the number formats of the cell styles of a workbook make of a
 * number: a plain number, a date, a timestamp or a time of day. A cell
 * refers to its style by the index {@code s} into {@code cellXfs}, the
 * style to its number format by {@code numFmtId}, which is either a built-in
 * format or one of {@code numFmts} with its format code.
 */
final class Styles {

    /** What a number of a cell stands for. */
    enum Kind { NUMBER, DATE, TIMESTAMP, TIME }

    private static final Styles NONE = new Styles(new Kind[0]);

    private final Kind[] kinds;

    private Styles(Kind[] kinds) {
        this.kinds = kinds;
    }

    /** Returns the styles of a workbook, none if it has no such part. */
    static Styles read(Workbook workbook) throws IOException {
        if (workbook.styles().isEmpty()) {
            return NONE;
        }
        String part = workbook.styles().get();
        ZipArchive archive = workbook.archive();
        Map<Integer, String> codes = new HashMap<>();
        List<Integer> formats = new ArrayList<>();
        try (InputStream in = archive.open(part);
                XmlInput xml = XmlInput.open(in, part + " of " + archive.file().location())) {
            String section = "";
            for (int event = xml.nextTag(); event != XMLStreamConstants.END_DOCUMENT; event = xml.nextTag()) {
                String name = xml.name();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    if (name.equals(section)) {
                        section = "";
                    }
                    continue;
                }
                switch (name) {
                    case "numFmts", "cellXfs" -> section = name;
                    case "dxfs", "cellStyleXfs", "extLst" -> xml.skip();
                    case "numFmt" -> {
                        Integer id = integer(xml.attribute("numFmtId"));
                        String code = xml.attribute("formatCode");
                        if (section.equals("numFmts") && id != null && code != null) {
                            codes.put(id, code);
                        }
                    }
                    case "xf" -> {
                        if (section.equals("cellXfs")) {
                            Integer id = integer(xml.attribute("numFmtId"));
                            formats.add(id == null ? 0 : id);
                        }
                    }
                    default -> {
                        // Fonts, fills, borders and the like say nothing about values.
                    }
                }
            }
        }
        Kind[] kinds = new Kind[formats.size()];
        for (int i = 0; i < kinds.length; i++) {
            int id = formats.get(i);
            kinds[i] = kind(id, codes.get(id));
        }
        return new Styles(kinds);
    }

    /** Returns what a number of a cell of a style stands for; a plain number for an unknown style. */
    Kind kind(int style) {
        return style >= 0 && style < kinds.length ? kinds[style] : Kind.NUMBER;
    }

    /**
     * Returns what a number format makes of a number, as DuckDB 1.5.6 judges
     * it: the built-in formats 14 to 17 are dates, 18 to 21 times and 22 a
     * timestamp, the other built-in formats numbers, also the elapsed times 45
     * to 47; a format code is judged by {@link #kind(String)}.
     *
     * @param code the format code, or null for a built-in format without one
     */
    static Kind kind(int id, String code) {
        if (id >= 14 && id <= 17) {
            return Kind.DATE;
        }
        if (id >= 18 && id <= 21) {
            return Kind.TIME;
        }
        if (id == 22) {
            return Kind.TIMESTAMP;
        }
        return code == null ? Kind.NUMBER : kind(code);
    }

    /**
     * Returns what a format code makes of a number, as DuckDB 1.5.6 judges it
     * by the letters of the whole code, in quotes, brackets and every section
     * too: {@code dd}, {@code DD}, {@code yy} or {@code YY} make a date, an
     * {@code h} or {@code H} a time, both a timestamp; so {@code d-mmm} and
     * {@code mm:ss} stay numbers and {@code [h]:mm:ss} is a time.
     */
    static Kind kind(String code) {
        boolean date = code.contains("dd") || code.contains("DD") || code.contains("yy") || code.contains("YY");
        boolean hours = code.indexOf('h') >= 0 || code.indexOf('H') >= 0;
        if (date) {
            return hours ? Kind.TIMESTAMP : Kind.DATE;
        }
        return hours ? Kind.TIME : Kind.NUMBER;
    }

    private static Integer integer(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
