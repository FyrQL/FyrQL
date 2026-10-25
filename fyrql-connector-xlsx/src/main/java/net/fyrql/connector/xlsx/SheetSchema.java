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

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where the table of a sheet is and what its columns are, decided as DuckDB
 * 1.5.6 decides it for {@code read_xlsx}.
 * <ul>
 * <li>Without a range, the table starts at the first row with a value; its
 * columns reach from the first value of that row to the last one before a
 * cell without a value, where a column without a cell does not end them.
 * With a range,
 * the table is the range, and its first row is the first row the sheet
 * writes in it, with or without values.</li>
 * <li>The first row is a header if all its cells hold shared or inline
 * strings, none missing,
 * unless {@code header} says otherwise. The names of a header are trimmed;
 * without a header a column is named after the reference of its cell in the
 * first row, such as {@code A1}, or with a range after its letters alone,
 * such as {@code A}. {@code normalize_names} makes them lower case ASCII
 * letters, digits and underscores, an empty one {@code _}; a repeated name,
 * compared without case, gets {@code _1}, {@code _2} and so on, and then an
 * empty name of a header is {@code C} and the position of its column, such
 * as {@code C0}.</li>
 * <li>The type of a column is that of its cell in the first row alone, or
 * after a header in the next row the sheet writes, with or without values:
 * a number is a {@code DOUBLE}, or a {@code DATE}, {@code TIMESTAMP}
 * or {@code TIME} by its number format; text and errors are a
 * {@code VARCHAR}, a boolean a {@code BOOLEAN}, and so is an inline string
 * {@code true} or {@code false} in any case, an ISO date a {@code DATE};
 * a cell without a value by its number format, so a {@code DOUBLE} by
 * default, and a missing cell a {@code DOUBLE}, or a {@code VARCHAR} with
 * {@code empty_as_varchar}; every column a {@code VARCHAR} with
 * {@code all_varchar}.</li>
 * <li>Reading stops at the first empty row unless {@code stop_at_empty} is
 * false, which it is by default with a range; a range is read to its last
 * row, with the rows past the data as rows of {@code NULL}s.</li>
 * </ul>
 *
 * @param sheet the name of the sheet
 * @param schema the columns
 * @param firstColumn the column of the first column of the table, counted from 0
 * @param dataRow the first row of data, after the header if there is one, counted from 0
 * @param lastRow the last row of the range, or -1 without a range
 * @param stopAtEmpty whether reading stops at the first empty row
 * @param ignoreErrors whether a value that does not fit its column is a {@code NULL} instead of an error
 * @param rows the number of rows of data the sheet says it has at most, or -1 if unknown
 */
record SheetSchema(String sheet, Schema schema, int firstColumn, int dataRow, int lastRow, boolean stopAtEmpty,
        boolean ignoreErrors, long rows) {

    /**
     * How to read a sheet: the options of {@code read_xlsx} and of a source
     * of the type {@code xlsx}.
     *
     * @param sheet the name of the sheet, or null for the first one
     * @param header whether the first row is a header, or null to decide it
     * @param range the cells to read, or null for those with values
     * @param stopAtEmpty whether to stop at the first empty row, or null for the default
     */
    record Options(String sheet, Boolean header, CellRef.Range range, Boolean stopAtEmpty, boolean allVarchar,
            boolean ignoreErrors, boolean emptyAsVarchar, boolean normalizeNames) {

        /** The options by default. */
        static final Options DEFAULT = new Options(null, null, null, null, false, false, false, false);
    }

    /**
     * The words DuckDB puts an underscore before when it normalizes a name:
     * its reserved keywords and type functions.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case", "cast",
            "check", "collate", "column", "constraint", "create", "default", "deferrable", "desc", "describe",
            "distinct", "do", "else", "end", "except", "false", "fetch", "for", "foreign", "from", "group", "having",
            "in", "initially", "intersect", "into", "lambda", "lateral", "leading", "limit", "not", "null", "offset",
            "on", "only", "or", "order", "pivot", "pivot_longer", "pivot_wider", "placing", "primary", "qualify",
            "references", "returning", "select", "show", "some", "summarize", "symmetric", "table", "then", "to",
            "trailing", "true", "union", "unique", "unpivot", "using", "variadic", "when", "where", "window", "with",
            "anti", "asof", "at", "authorization", "binary", "by", "collation", "columns", "concurrently", "cross",
            "freeze", "full", "generated", "glob", "ilike", "inner", "is", "isnull", "join", "left", "like", "map",
            "natural", "notnull", "outer", "overlaps", "positional", "right", "semi", "similar", "struct",
            "tablesample", "try_cast", "unpack", "verbose");

    /** Returns the last column of the table, counted from 0. */
    int lastColumn() {
        return firstColumn + schema.size() - 1;
    }

    /**
     * Decides the table of a sheet of a workbook.
     *
     * @throws IllegalArgumentException if there is no such sheet or it has no rows
     */
    static SheetSchema infer(Book book, Options options) throws IOException {
        Workbook.Sheet sheet = book.workbook().sheet(options.sheet());
        CellRef.Range range = options.range();
        int first = range != null ? range.firstColumn() : 0;
        int last = range != null ? range.lastColumn() : CellRef.MAX_COLUMNS - 1;
        try (SheetReader reader = SheetReader.open(book, sheet, first, last)) {
            int lastRow = range != null ? range.lastRow() : -1;
            int startRow = range != null ? range.firstRow() : -1;
            // The first row of the table: without a range the first one with a value, with a range the first
            // row the sheet writes in it, with or without values, which a missing row before it does not end.
            boolean more = reader.next();
            while (more && (range != null ? reader.row() < startRow : !reader.hasValue())) {
                more = reader.next();
            }
            if (range == null) {
                if (!more) {
                    throw new IllegalArgumentException("No rows found in xlsx file \"" + book.location() + "\"");
                }
                startRow = reader.row();
                // As in DuckDB, the columns run from the first value of the row to the last one before a cell
                // without a value; a column without a cell does not end them.
                first = -1;
                last = -1;
                for (int i = 0; i < reader.size(); i++) {
                    if (reader.counts(i)) {
                        first = first < 0 ? reader.column(i) : first;
                        last = reader.column(i);
                    } else if (first >= 0) {
                        break;
                    }
                }
            }
            int width = last - first + 1;
            boolean found = more && (lastRow < 0 || reader.row() <= lastRow);
            int headRow = found ? reader.row() : startRow;
            Cell[] head = found ? cells(reader, first, width) : new Cell[width];
            more = found && reader.next();
            boolean header = options.header() != null ? options.header() : isHeader(head);
            int dataRow = header ? headRow + 1 : startRow;
            // The types come from the first row of the table, or after a header from the next row written.
            Cell[] data;
            if (!header) {
                data = head;
            } else if (more && (lastRow < 0 || reader.row() <= lastRow)) {
                data = cells(reader, first, width);
            } else {
                data = new Cell[width];
            }
            List<Schema.Field> fields = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int c = 0; c < width; c++) {
                String name = header ? (head[c] == null ? "" : trim(head[c].text()))
                        : range != null ? CellRef.columnName(first + c) : CellRef.name(first + c, dataRow);
                if (options.normalizeNames()) {
                    name = normalize(name);
                }
                String unique = name;
                for (int k = 1; !used.add(unique.toLowerCase(Locale.ROOT)); k++) {
                    unique = name + "_" + k;
                }
                // As in DuckDB, the first empty name of a header, once the others are told apart, is C and the
                // position of its column.
                if (unique.isEmpty()) {
                    unique = "C" + c;
                }
                fields.add(new Schema.Field(unique, options.allVarchar() ? DataType.VARCHAR
                        : type(data[c], book.styles(), options.emptyAsVarchar())));
            }
            long rows = -1;
            if (range != null) {
                rows = Math.max(0, lastRow - dataRow + 1L);
            } else if (reader.lastDimensionRow() >= 0) {
                rows = Math.max(0, reader.lastDimensionRow() - dataRow + 1L);
            }
            boolean stop = options.stopAtEmpty() != null ? options.stopAtEmpty() : range == null;
            return new SheetSchema(sheet.name(), new Schema(fields), first, dataRow, lastRow, stop,
                    options.ignoreErrors(), rows);
        }
    }

    /** A cell of the first rows: what it holds, its text and its style. */
    private record Cell(byte kind, String text, int style) {
    }

    private static Cell[] cells(SheetReader reader, int first, int width) {
        Cell[] cells = new Cell[width];
        for (int i = 0; i < reader.size(); i++) {
            int c = reader.column(i) - first;
            if (c >= 0 && c < width) {
                cells[c] = new Cell(reader.kind(i), reader.text(i), reader.style(i));
            }
        }
        return cells;
    }

    /** Tells whether a row is a header: all its cells are shared or inline strings, as DuckDB has it. */
    private static boolean isHeader(Cell[] head) {
        for (Cell cell : head) {
            if (cell == null || cell.kind() != SheetReader.SHARED && cell.kind() != SheetReader.INLINE) {
                return false;
            }
        }
        return head.length > 0;
    }

    /** Tells whether a cell holds a text of a string, not a number, boolean, error or date. */
    static boolean isText(byte kind) {
        return kind == SheetReader.SHARED || kind == SheetReader.INLINE || kind == SheetReader.FORMULA_TEXT;
    }

    private static DataType type(Cell cell, Styles styles, boolean emptyAsVarchar) {
        if (cell == null) {
            return emptyAsVarchar ? DataType.VARCHAR : DataType.DOUBLE;
        }
        return switch (cell.kind()) {
            // As in DuckDB, a cell without a value takes the type of its number format, which no missing cell has.
            case SheetReader.NUMBER, SheetReader.EMPTY -> switch (styles.kind(cell.style())) {
                case DATE -> DataType.DATE;
                case TIMESTAMP -> DataType.TIMESTAMP;
                case TIME -> DataType.TIME;
                case NUMBER -> DataType.DOUBLE;
            };
            case SheetReader.BOOLEAN -> DataType.BOOLEAN;
            case SheetReader.ISO_DATE -> DataType.DATE;
            // As in DuckDB, an inline string true or false in any case makes a BOOLEAN, other texts a VARCHAR.
            case SheetReader.INLINE -> cell.text().equalsIgnoreCase("true") || cell.text().equalsIgnoreCase("false")
                    ? DataType.BOOLEAN : DataType.VARCHAR;
            default -> DataType.VARCHAR;
        };
    }

    /** Trims a name of whitespace and of other spaces, such as the no-break space, as DuckDB trims it. */
    static String trim(String name) {
        int from = 0;
        int to = name.length();
        while (from < to && isSpace(name.charAt(from))) {
            from++;
        }
        while (to > from && isSpace(name.charAt(to - 1))) {
            to--;
        }
        return name.substring(from, to);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * Normalizes a name as DuckDB's {@code normalize_names} of
     * {@code read_xlsx} does: trimmed, without accents, in lower case, with
     * each run of whitespace an underscore and everything else that is no
     * ASCII letter, digit or underscore dropped; a name that is then empty,
     * starts with a digit, or is a reserved word gets an underscore before
     * it.
     */
    static String normalize(String name) {
        String decomposed = Normalizer.normalize(trim(name), Normalizer.Form.NFKD);
        StringBuilder b = new StringBuilder(decomposed.length());
        boolean space = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!space) {
                    b.append('_');
                }
                space = true;
                continue;
            }
            space = false;
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                b.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                b.append((char) (c + ('a' - 'A')));
            }
        }
        String s = b.toString();
        if (s.isEmpty() || Character.isDigit(s.charAt(0)) || KEYWORDS.contains(s)) {
            return "_" + s;
        }
        return s;
    }
}
