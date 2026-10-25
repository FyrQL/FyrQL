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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipException;

import javax.xml.stream.XMLStreamConstants;

/**
 * The parts of a workbook that tell where its sheets are: the package
 * relationships lead to the workbook part, which lists the sheets with
 * their names and states and whether its dates count from 1904; the
 * relationships of the workbook part lead to the parts of the sheets, the
 * shared strings and the styles.
 * <p>
 * Relationship types are matched by their last segment, so that Strict
 * Open XML, whose types have other prefixes, reads the same.
 */
final class Workbook {

    /** The kind of a sheet: a worksheet holds cells, a chart sheet or dialog sheet none. */
    enum Kind { WORKSHEET, OTHER }

    /** A sheet in the order of the workbook, with the name of its part, or null if it has none. */
    record Sheet(String name, boolean hidden, Kind kind, String part) {}

    private final ZipArchive archive;
    private final List<Sheet> sheets;
    private final boolean date1904;
    private final String sharedStrings;
    private final String styles;

    private Workbook(ZipArchive archive, List<Sheet> sheets, boolean date1904, String sharedStrings,
            String styles) {
        this.archive = archive;
        this.sheets = sheets;
        this.date1904 = date1904;
        this.sharedStrings = sharedStrings;
        this.styles = styles;
    }

    /**
     * Reads the structure of a workbook.
     *
     * @throws ZipException if the archive is no workbook or a binary one
     */
    static Workbook read(ZipArchive archive) throws IOException {
        String location = archive.file().location();
        Map<String, Relationship> root = relationships(archive, "");
        Relationship office = root.values().stream().filter(r -> r.type().equals("officeDocument")).findFirst()
                .orElse(null);
        String workbookPart;
        if (office != null) {
            workbookPart = office.target();
        } else if (archive.find("xl/workbook.xml").isPresent()) {
            workbookPart = "xl/workbook.xml";
        } else {
            throw new ZipException(location + " is no xlsx workbook: it has no workbook part");
        }
        if (workbookPart.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            throw new ZipException(location + " is a binary workbook (.xlsb), which is not supported; save it as"
                    + " .xlsx");
        }
        workbookPart = archive.find(workbookPart).orElseThrow(
                () -> new ZipException(location + " is no xlsx workbook: it lacks its workbook part"));
        Map<String, Relationship> rels = relationships(archive, workbookPart);
        List<Sheet> sheets = new ArrayList<>();
        boolean date1904 = false;
        try (InputStream in = archive.open(workbookPart);
                XmlInput xml = XmlInput.open(in, workbookPart + " of " + location)) {
            for (int event = xml.nextTag(); event != XMLStreamConstants.END_DOCUMENT; event = xml.nextTag()) {
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (xml.name().equals("workbookPr")) {
                    date1904 = isTrue(xml.attribute("date1904"));
                } else if (xml.name().equals("sheet")) {
                    String name = xml.attribute("name");
                    String id = xml.attribute("id", true);
                    Relationship r = id == null ? null : rels.get(id);
                    String part = r == null ? null : archive.find(r.target()).orElse(null);
                    Kind kind = part != null && r.type().equals("worksheet") ? Kind.WORKSHEET : Kind.OTHER;
                    String state = xml.attribute("state");
                    sheets.add(new Sheet(name == null ? "" : name, state != null && !state.equals("visible"), kind,
                            part));
                }
            }
        }
        String shared = target(archive, rels, "sharedStrings");
        String styles = target(archive, rels, "styles");
        return new Workbook(archive, List.copyOf(sheets), date1904, shared, styles);
    }

    /** Returns the archive. */
    ZipArchive archive() {
        return archive;
    }

    /** Returns the sheets in the order of the workbook. */
    List<Sheet> sheets() {
        return sheets;
    }

    /** Tells whether the serial numbers of dates count from 1904 instead of 1900. */
    boolean date1904() {
        return date1904;
    }

    /** Returns the name of the part of the shared strings. */
    Optional<String> sharedStrings() {
        return Optional.ofNullable(sharedStrings);
    }

    /** Returns the name of the part of the styles. */
    Optional<String> styles() {
        return Optional.ofNullable(styles);
    }

    /**
     * Returns the first worksheet, or the worksheet of a name, compared
     * exactly as DuckDB compares it.
     *
     * @param name the name, or null for the first worksheet
     * @throws IllegalArgumentException if there is no such worksheet
     */
    Sheet sheet(String name) {
        String location = archive.file().location();
        if (name == null) {
            return sheets.stream().filter(s -> s.kind() == Kind.WORKSHEET).findFirst().orElseThrow(
                    () -> new IllegalArgumentException("No rows found in xlsx file " + location
                            + ": it has no worksheet"));
        }
        for (Sheet s : sheets) {
            if (s.name().equals(name)) {
                if (s.kind() != Kind.WORKSHEET) {
                    throw new IllegalArgumentException("Sheet \"" + name + "\" of xlsx file \"" + location
                            + "\" is a chart sheet or dialog sheet without cells");
                }
                return s;
            }
        }
        String hint = sheets.stream().map(Sheet::name)
                .min(Comparator.comparingInt(n -> distance(n.toLowerCase(Locale.ROOT),
                        name.toLowerCase(Locale.ROOT))))
                .map(n -> " Did you mean: \"" + n + "\"").orElse("");
        throw new IllegalArgumentException("Sheet \"" + name + "\" not found in xlsx file \"" + location + "\""
                + hint);
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] t = previous;
            previous = current;
            current = t;
        }
        return previous[b.length()];
    }

    /** A relationship with the last segment of its type and its target resolved to a part name. */
    private record Relationship(String id, String type, String target) {}

    private static String target(ZipArchive archive, Map<String, Relationship> rels, String type) {
        return rels.values().stream().filter(r -> r.type().equals(type)).findFirst()
                .flatMap(r -> archive.find(r.target())).orElse(null);
    }

    /** Reads the internal relationships of a part, or of the package for the empty name; none if there is no file. */
    private static Map<String, Relationship> relationships(ZipArchive archive, String part) throws IOException {
        int slash = part.lastIndexOf('/');
        String directory = part.substring(0, slash + 1);
        String relsName = directory + "_rels/" + part.substring(slash + 1) + ".rels";
        Optional<String> entry = archive.find(relsName);
        Map<String, Relationship> rels = new LinkedHashMap<>();
        if (entry.isEmpty()) {
            return rels;
        }
        String location = archive.file().location();
        try (InputStream in = archive.open(entry.get());
                XmlInput xml = XmlInput.open(in, entry.get() + " of " + location)) {
            for (int event = xml.nextTag(); event != XMLStreamConstants.END_DOCUMENT; event = xml.nextTag()) {
                if (event != XMLStreamConstants.START_ELEMENT || !xml.name().equals("Relationship")) {
                    continue;
                }
                String id = xml.attribute("Id");
                String type = xml.attribute("Type");
                String target = xml.attribute("Target");
                if (id == null || type == null || target == null || "External".equals(xml.attribute("TargetMode"))) {
                    continue;
                }
                String lastSegment = type.substring(type.lastIndexOf('/') + 1);
                rels.putIfAbsent(id, new Relationship(id, lastSegment, resolve(directory, target)));
            }
        }
        return rels;
    }

    /**
     * Resolves the target of a relationship against the directory of its
     * source, as a part name without a leading slash.
     */
    static String resolve(String directory, String target) {
        String path = target.startsWith("/") ? target.substring(1) : directory + target;
        Deque<String> parts = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                parts.pollLast();
            } else {
                parts.addLast(segment);
            }
        }
        return String.join("/", parts);
    }

    private static boolean isTrue(String value) {
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }
}
