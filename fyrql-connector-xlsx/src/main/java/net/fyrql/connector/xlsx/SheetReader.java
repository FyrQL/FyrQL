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
import java.util.Arrays;

import javax.xml.stream.XMLStreamConstants;

/**
 * Reads the rows of a sheet one by one, with their cells in a window of
 * columns. A row or a cell without its reference {@code r} follows the one
 * before it. A cell that holds no value, such as a formula without a stored
 * result or a cell with a style alone, is {@link #EMPTY}, with its style. The
 * texts of shared and inline strings and of formula results are given with
 * their escapes decoded; a number, a boolean ({@code 1} or {@code 0}), an
 * error such as {@code #N/A} and an ISO date are given as written.
 */
final class SheetReader implements AutoCloseable {

    /** What a cell holds: the type {@code n} or none. */
    static final byte NUMBER = 0;
    /** What a cell holds: a shared string, {@code s}. */
    static final byte SHARED = 1;
    /** What a cell holds: an inline string, {@code inlineStr}. */
    static final byte INLINE = 2;
    /** What a cell holds: the text a formula gave, {@code str}. */
    static final byte FORMULA_TEXT = 3;
    /** What a cell holds: a boolean, {@code b}. */
    static final byte BOOLEAN = 4;
    /** What a cell holds: an error, {@code e}. */
    static final byte ERROR = 5;
    /** What a cell holds: a date or timestamp in ISO 8601, {@code d}. */
    static final byte ISO_DATE = 6;
    /** What a cell holds: nothing, as a cell with a style alone; a shared string without its index too. */
    static final byte EMPTY = 7;

    private final InputStream in;
    private final XmlInput xml;
    private final SharedStrings strings;
    private final String sheet;
    private final int firstColumn;
    private final int lastColumn;
    private int lastDimensionRow = -1;
    private boolean inData;
    private boolean ended;

    private int row = -1;
    private int size;
    private int[] columns = new int[16];
    private byte[] kinds = new byte[16];
    private String[] texts = new String[16];
    private int[] styles = new int[16];

    private SheetReader(InputStream in, XmlInput xml, SharedStrings strings, String sheet, int firstColumn,
            int lastColumn) {
        this.in = in;
        this.xml = xml;
        this.strings = strings;
        this.sheet = sheet;
        this.firstColumn = firstColumn;
        this.lastColumn = lastColumn;
    }

    /**
     * Starts to read a sheet.
     *
     * @param firstColumn the first column of the window, counted from 0
     * @param lastColumn the last column of the window
     */
    static SheetReader open(Book book, Workbook.Sheet sheet, int firstColumn, int lastColumn) throws IOException {
        InputStream in = book.workbook().archive().open(sheet.part());
        try {
            XmlInput xml = XmlInput.open(in, sheet.part() + " of " + book.location());
            return new SheetReader(in, xml, book.strings(), "the sheet \"" + sheet.name() + "\" of " + book.location(),
                    firstColumn, lastColumn);
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    /**
     * Moves to the next row of the sheet that is written in it, with or
     * without cells.
     *
     * @return false after the last row
     */
    boolean next() throws IOException {
        if (ended) {
            return false;
        }
        while (true) {
            int event = xml.nextTag();
            if (event == XMLStreamConstants.END_DOCUMENT) {
                ended = true;
                return false;
            }
            String name = xml.name();
            if (event == XMLStreamConstants.END_ELEMENT) {
                if (name.equals("sheetData")) {
                    ended = true;
                    return false;
                }
                continue;
            }
            if (!inData) {
                if (name.equals("sheetData")) {
                    inData = true;
                } else if (name.equals("dimension")) {
                    dimension(xml.attribute("ref"));
                }
                continue;
            }
            if (name.equals("row")) {
                readRow();
                return true;
            }
            xml.skip();
        }
    }

    /** Returns the last row the sheet says it has, counted from 0, or -1 if it does not say. */
    int lastDimensionRow() {
        return lastDimensionRow;
    }

    /** Returns the current row, counted from 0. */
    int row() {
        return row;
    }

    /** Returns the number of cells with a value of the current row in the window. */
    int size() {
        return size;
    }

    /**
     * Tells whether a cell of the current row counts as a value of the row,
     * as DuckDB counts it: every cell does but an empty one, an inline string
     * without text, which counts only as the empty text of a {@code VARCHAR},
     * and a formula result without text, which makes a {@code VARCHAR} of
     * {@code NULL}s.
     */
    boolean counts(int cell) {
        byte kind = kinds[cell];
        return kind != EMPTY && (kind != INLINE && kind != FORMULA_TEXT || !texts[cell].isEmpty());
    }

    /** Tells whether the current row has a value that {@linkplain #counts counts}. */
    boolean hasValue() {
        for (int i = 0; i < size; i++) {
            if (counts(i)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the column of a cell of the current row, counted from 0. */
    int column(int cell) {
        return columns[cell];
    }

    /** Returns what a cell of the current row holds, one of the constants of this class. */
    byte kind(int cell) {
        return kinds[cell];
    }

    /** Returns the text of a cell of the current row. */
    String text(int cell) {
        return texts[cell];
    }

    /** Returns the style of a cell of the current row, its index into the cell styles. */
    int style(int cell) {
        return styles[cell];
    }

    /** Returns the reference of a cell of the current row, such as {@code B12}, for messages. */
    String reference(int cell) {
        return CellRef.name(columns[cell], row);
    }

    /** Returns which sheet of which file this reads, for messages. */
    String sheet() {
        return sheet;
    }

    private void dimension(String ref) {
        if (ref == null) {
            return;
        }
        String last = ref.substring(ref.indexOf(':') + 1);
        long cell = CellRef.parseCell(last);
        if (cell >= 0) {
            lastDimensionRow = CellRef.row(cell);
        }
    }

    private void readRow() throws IOException {
        String r = xml.attribute("r");
        int number;
        if (r == null) {
            number = row + 1;
        } else {
            try {
                number = Integer.parseInt(r.strip()) - 1;
            } catch (NumberFormatException e) {
                throw new IOException(sheet + " has a row with the number \"" + r + "\"");
            }
        }
        if (number <= row || number >= CellRef.MAX_ROWS) {
            throw new IOException(sheet + " has the row " + (number + 1) + " " + (number <= row
                    ? "after the row " + (row + 1) : "beyond the last row of a sheet, " + CellRef.MAX_ROWS));
        }
        row = number;
        size = 0;
        int column = -1;
        while (true) {
            int event = xml.nextTag();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new IOException(sheet + " ends inside the row " + (row + 1));
            }
            if (!xml.name().equals("c")) {
                xml.skip();
                continue;
            }
            column = readCell(column);
        }
    }

    /** Reads a cell and keeps it if it has a value in the window; returns its column. */
    private int readCell(int previous) throws IOException {
        String ref = xml.attribute("r");
        int column;
        if (ref == null) {
            column = previous + 1;
        } else {
            long cell = CellRef.parseCell(ref);
            if (cell < 0) {
                throw new IOException(sheet + " has a cell with the reference \"" + ref + "\" in the row " + (row + 1));
            }
            column = CellRef.column(cell);
        }
        if (column >= CellRef.MAX_COLUMNS) {
            throw new IOException(sheet + " has a cell beyond the last column of a sheet in the row " + (row + 1));
        }
        String type = xml.attribute("t");
        String style = xml.attribute("s");
        byte kind = kind(type, column);
        String value = null;
        while (true) {
            int event = xml.nextTag();
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new IOException(sheet + " ends inside the cell " + CellRef.name(column, row));
            }
            switch (xml.name()) {
                case "v" -> value = xml.text();
                case "is" -> value = inline();
                default -> xml.skip();
            }
        }
        if (column < firstColumn || column > lastColumn) {
            return column;
        }
        if (value == null && kind == INLINE) {
            // As in DuckDB, an inline string without its text is an empty one.
            value = "";
        }
        if (value == null || value.isEmpty() && kind != INLINE && kind != SHARED && kind != FORMULA_TEXT) {
            add(column, EMPTY, "", style == null ? 0 : parseStyle(style));
            return column;
        }
        String text = switch (kind) {
            // A shared string without its index is an empty text, where DuckDB takes the first shared string.
            case SHARED -> value.isBlank() ? "" : shared(value, column);
            case INLINE, FORMULA_TEXT -> Escapes.decode(value);
            default -> value;
        };
        add(column, kind, text, style == null ? 0 : parseStyle(style));
        return column;
    }

    private byte kind(String type, int column) throws IOException {
        if (type == null) {
            return NUMBER;
        }
        return switch (type) {
            case "n" -> NUMBER;
            case "s" -> SHARED;
            case "inlineStr" -> INLINE;
            case "str" -> FORMULA_TEXT;
            case "b" -> BOOLEAN;
            case "e" -> ERROR;
            case "d" -> ISO_DATE;
            default -> throw new IOException(sheet + " has the cell " + CellRef.name(column, row)
                    + " of the unknown type \"" + type + "\"");
        };
    }

    /** Reads the text of an inline string, the runs of rich text one after the other, without phonetic runs. */
    private String inline() throws IOException {
        StringBuilder b = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = xml.nextTag();
            if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                switch (xml.name()) {
                    case "t" -> b.append(xml.text());
                    case "rPh", "phoneticPr" -> xml.skip();
                    default -> depth++;
                }
            } else {
                throw new IOException(sheet + " ends inside an inline string");
            }
        }
        return b.toString();
    }

    private String shared(String value, int column) throws IOException {
        int index;
        try {
            index = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            index = -1;
        }
        if (index < 0 || index >= strings.size()) {
            throw new IOException(sheet + " has the cell " + CellRef.name(column, row) + " with the shared string "
                    + value.strip() + ", of " + strings.size() + " shared strings");
        }
        return strings.get(index);
    }

    private static int parseStyle(String style) {
        try {
            return Integer.parseInt(style.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void add(int column, byte kind, String text, int style) {
        if (size == columns.length) {
            int n = size * 2;
            columns = Arrays.copyOf(columns, n);
            kinds = Arrays.copyOf(kinds, n);
            texts = Arrays.copyOf(texts, n);
            styles = Arrays.copyOf(styles, n);
        }
        columns[size] = column;
        kinds[size] = kind;
        texts[size] = text;
        styles[size] = style;
        size++;
    }

    @Override
    public void close() throws IOException {
        xml.close();
        in.close();
    }
}
