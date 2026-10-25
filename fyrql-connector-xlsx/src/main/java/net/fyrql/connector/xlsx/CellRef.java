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

/**
 * References to cells in the A1 style, such as {@code B12}, as a column and
 * a row counted from 0, within the limits of Excel: 16 384 columns
 * ({@code A} to {@code XFD}) and 1 048 576 rows.
 */
final class CellRef {

    /** The number of columns of a sheet. */
    static final int MAX_COLUMNS = 16_384;

    /** The number of rows of a sheet. */
    static final int MAX_ROWS = 1_048_576;

    private CellRef() {
    }

    /**
     * A rectangle of cells, from its first to its last column and row, both
     * included and counted from 0; a range of whole columns such as
     * {@code A:C} reaches to the last row.
     */
    record Range(int firstColumn, int firstRow, int lastColumn, int lastRow) {

        /**
         * Reads a range such as {@code A1:C10} or {@code A:C}.
         *
         * @throws IllegalArgumentException if it is no such range, or its end
         *         comes before its start
         */
        static Range parse(String text) {
            String t = text.strip();
            int colon = t.indexOf(':');
            if (colon > 0 && colon < t.length() - 1) {
                String from = t.substring(0, colon);
                String to = t.substring(colon + 1);
                long a = parseCell(from);
                long b = parseCell(to);
                if (a >= 0 && b >= 0 && column(a) <= column(b) && row(a) <= row(b)) {
                    return new Range(column(a), row(a), column(b), row(b));
                }
                int c = columnIndex(from, 0, from.length());
                int d = columnIndex(to, 0, to.length());
                if (c >= 0 && d >= 0 && c <= d) {
                    return new Range(c, 0, d, MAX_ROWS - 1);
                }
            }
            throw new IllegalArgumentException("Invalid range '" + text + "' specified");
        }

        /** Returns the number of columns. */
        int width() {
            return lastColumn - firstColumn + 1;
        }
    }

    /**
     * Reads a reference such as {@code B12}.
     *
     * @return the column in the high and the row in the low 32 bits, or -1
     *         if it is no reference within the limits
     */
    static long parseCell(String ref) {
        int n = ref.length();
        int letters = 0;
        while (letters < n && isLetter(ref.charAt(letters))) {
            letters++;
        }
        if (letters == 0 || letters == n) {
            return -1;
        }
        int column = columnIndex(ref, 0, letters);
        if (column < 0) {
            return -1;
        }
        int row = 0;
        for (int i = letters; i < n; i++) {
            char c = ref.charAt(i);
            if (c < '0' || c > '9' || row > MAX_ROWS) {
                return -1;
            }
            row = row * 10 + (c - '0');
        }
        if (row < 1 || row > MAX_ROWS) {
            return -1;
        }
        return ((long) column << 32) | (row - 1);
    }

    /** Returns the column of a reference read by {@link #parseCell}. */
    static int column(long cell) {
        return (int) (cell >>> 32);
    }

    /** Returns the row of a reference read by {@link #parseCell}. */
    static int row(long cell) {
        return (int) cell;
    }

    /**
     * Reads the letters of a column such as {@code AB}, in any case.
     *
     * @return the column counted from 0, or -1 if the part is no column
     *         within the limits
     */
    static int columnIndex(CharSequence text, int from, int to) {
        if (from >= to || to - from > 3) {
            return -1;
        }
        int column = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (!isLetter(c)) {
                return -1;
            }
            column = column * 26 + (Character.toUpperCase(c) - 'A' + 1);
        }
        return column <= MAX_COLUMNS ? column - 1 : -1;
    }

    /** Returns the letters of a column counted from 0, such as {@code AB} for 27. */
    static String columnName(int column) {
        StringBuilder b = new StringBuilder(3);
        for (int c = column + 1; c > 0; c = (c - 1) / 26) {
            b.append((char) ('A' + (c - 1) % 26));
        }
        return b.reverse().toString();
    }

    /** Returns the reference of a cell counted from 0, such as {@code B12}. */
    static String name(int column, int row) {
        return columnName(column) + (row + 1);
    }

    private static boolean isLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
