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

import net.fyrql.connector.BatchReader;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;
import net.fyrql.core.vector.Vectors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the rows of the table of a sheet into batches of the requested
 * columns. A value is read as its column's type the way DuckDB reads it:
 * <ul>
 * <li>{@code VARCHAR}: the text of a string, and the value as written of
 * a number ({@code 45292}), a boolean ({@code 1}) or an error;</li>
 * <li>{@code DOUBLE}: a number, a boolean as 1 or 0, a text as its cast;</li>
 * <li>{@code BOOLEAN}: the value as written, cast, so that {@code 1} is
 * true and {@code 2} an error;</li>
 * <li>{@code DATE}, {@code TIMESTAMP}, {@code TIME}: a number, or a text
 * that casts to one, as a serial number, see {@link Serials}; an ISO date
 * as its cast;</li>
 * <li>any other type, for a table of given columns: the value as a
 * {@code DOUBLE}, a {@code BOOLEAN} or a text, cast to the type; a
 * {@code TIMESTAMP WITH TIME ZONE} as a serial number in the time zone of
 * the session.</li>
 * </ul>
 * A value that does not fit is an error that names its cell, or a
 * {@code NULL} with {@code ignore_errors}; an empty text is a {@code NULL}
 * in a column of another type. A row is empty, for {@code stop_at_empty},
 * if it has no value that {@linkplain SheetReader#counts counts}.
 */
final class SheetBatches implements BatchReader {

    private final SheetSchema table;
    private final SheetReader reader;
    private final Schema output;
    private final Column[] columns;
    /** The positions in the output of each column of the table. */
    private final int[][] targets;
    private final boolean[] filled;
    private final int batchSize;
    private int expected;
    private boolean held;
    private long gap;
    private boolean done;

    private SheetBatches(SheetSchema table, SheetReader reader, List<Integer> requested, Schema output,
            int batchSize, ZoneId zone, boolean date1904) {
        this.table = table;
        this.reader = reader;
        this.output = output;
        this.batchSize = batchSize;
        this.columns = new Column[requested.size()];
        List<List<Integer>> positions = new ArrayList<>();
        for (int f = 0; f < table.schema().size(); f++) {
            positions.add(new ArrayList<>());
        }
        for (int c = 0; c < requested.size(); c++) {
            columns[c] = Column.of(output.type(c), batchSize, zone, date1904);
            positions.get(requested.get(c)).add(c);
        }
        targets = new int[positions.size()][];
        for (int f = 0; f < targets.length; f++) {
            targets[f] = positions.get(f).stream().mapToInt(Integer::intValue).toArray();
        }
        filled = new boolean[requested.size()];
        expected = table.dataRow();
    }

    /**
     * Starts to read the table of a sheet.
     *
     * @param requested the columns to read, by their positions in the table
     * @param output their names and the types to read them as
     * @param zone the time zone of the session
     */
    static SheetBatches open(Book book, SheetSchema table, List<Integer> requested, Schema output, int batchSize,
            ZoneId zone) throws IOException {
        Workbook.Sheet sheet = book.workbook().sheet(table.sheet());
        SheetReader reader = SheetReader.open(book, sheet, table.firstColumn(), table.lastColumn());
        return new SheetBatches(table, reader, requested, output, batchSize, zone, book.workbook().date1904());
    }

    @Override
    public Batch next() {
        if (done) {
            return null;
        }
        int n = 0;
        try {
            while (n < batchSize) {
                if (gap > 0) {
                    nullRow();
                    gap--;
                    n++;
                    continue;
                }
                int r = held ? reader.row() : advance();
                if (r < 0) {
                    if (table.lastRow() >= 0 && !table.stopAtEmpty() && expected <= table.lastRow()) {
                        gap = table.lastRow() - expected + 1L;
                        expected = table.lastRow() + 1;
                        continue;
                    }
                    done = true;
                    break;
                }
                if (r > expected) {
                    if (table.stopAtEmpty()) {
                        done = true;
                        break;
                    }
                    gap = r - expected;
                    expected = r;
                    held = true;
                    continue;
                }
                held = false;
                if (!reader.hasValue() && table.stopAtEmpty()) {
                    done = true;
                    break;
                }
                row();
                n++;
                expected = r + 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + reader.sheet() + ": " + e.getMessage(), e);
        }
        if (n == 0) {
            return null;
        }
        List<Vector> vectors = new ArrayList<>(columns.length);
        for (Column c : columns) {
            vectors.add(c.finish());
        }
        return new Batch(output, n, vectors);
    }

    /** Moves to the next row of data; -1 after the last one, or past the end of the range. */
    private int advance() throws IOException {
        while (reader.next()) {
            int r = reader.row();
            if (r < table.dataRow()) {
                continue;
            }
            if (table.lastRow() >= 0 && r > table.lastRow()) {
                return -1;
            }
            return r;
        }
        return -1;
    }

    private void nullRow() {
        for (Column c : columns) {
            c.appendNull();
        }
    }

    private void row() {
        Arrays.fill(filled, false);
        int first = table.firstColumn();
        for (int i = 0; i < reader.size(); i++) {
            byte kind = reader.kind(i);
            if (kind == SheetReader.EMPTY || kind == SheetReader.FORMULA_TEXT && reader.text(i).isEmpty()) {
                continue;
            }
            int[] to = targets[reader.column(i) - first];
            // As in DuckDB, an empty text is a NULL in a column of another type than VARCHAR, never an error.
            boolean emptyText = reader.text(i).isEmpty() && SheetSchema.isText(reader.kind(i));
            for (int c : to) {
                if (emptyText && columns[c].type != DataType.VARCHAR) {
                    columns[c].appendNull();
                    filled[c] = true;
                    continue;
                }
                try {
                    columns[c].append(reader.kind(i), reader.text(i));
                } catch (RuntimeException e) {
                    if (!table.ignoreErrors()) {
                        throw new ConversionException("Failed to parse cell '" + reader.reference(i) + "' of "
                                + reader.sheet() + ": " + e.getMessage(), e);
                    }
                    columns[c].appendNull();
                }
                filled[c] = true;
            }
        }
        for (int c = 0; c < columns.length; c++) {
            if (!filled[c]) {
                columns[c].appendNull();
            }
        }
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            // Only read from; nothing is lost.
        }
    }

    /** Reads the cells of one column into vectors of its type. */
    private abstract static class Column {
        final DataType type;
        private final int capacity;
        Vector.Builder builder;

        Column(DataType type, int capacity) {
            this.type = type;
            this.capacity = capacity;
            start();
        }

        static Column of(DataType type, int capacity, ZoneId zone, boolean date1904) {
            if (type == DataType.VARCHAR) {
                return new Text(capacity);
            }
            if (type == DataType.DOUBLE) {
                return new Real(capacity);
            }
            if (type == DataType.BOOLEAN) {
                return new Bool(capacity);
            }
            if (type == DataType.DATE || type == DataType.TIMESTAMP || type == DataType.TIME
                    || type == DataType.TIMESTAMPTZ) {
                return new Temporal(type, capacity, zone, date1904);
            }
            return new Cast(type, capacity, zone);
        }

        /** Appends the value of a cell, or fails without appending. */
        abstract void append(byte kind, String text);

        final void appendNull() {
            builder.appendNull();
        }

        final Vector finish() {
            Vector v = builder.build();
            start();
            return v;
        }

        private void start() {
            builder = Vectors.builder(type, capacity);
            started();
        }

        /** Takes the new builder, typed. */
        abstract void started();
    }

    /** A {@code VARCHAR}: every value as its text. */
    private static final class Text extends Column {
        private BytesVector.Builder b;

        Text(int capacity) {
            super(DataType.VARCHAR, capacity);
        }

        @Override
        void started() {
            b = (BytesVector.Builder) builder;
        }

        @Override
        void append(byte kind, String text) {
            b.appendString(text);
        }
    }

    /** A {@code DOUBLE}. */
    private static final class Real extends Column {
        private DoubleVector.Builder b;

        Real(int capacity) {
            super(DataType.DOUBLE, capacity);
        }

        @Override
        void started() {
            b = (DoubleVector.Builder) builder;
        }

        @Override
        void append(byte kind, String text) {
            b.appendDouble(number(kind, text));
        }
    }

    /** A {@code BOOLEAN}. */
    private static final class Bool extends Column {
        private BooleanVector.Builder b;

        Bool(int capacity) {
            super(DataType.BOOLEAN, capacity);
        }

        @Override
        void started() {
            b = (BooleanVector.Builder) builder;
        }

        @Override
        void append(byte kind, String text) {
            boolean value;
            try {
                value = Conversions.parseBoolean(text);
            } catch (RuntimeException e) {
                throw new ConversionException("Could not convert string '" + text + "' to BOOLEAN", e);
            }
            b.appendBoolean(value);
        }
    }

    /** A {@code DATE}, {@code TIMESTAMP}, {@code TIME} or {@code TIMESTAMP WITH TIME ZONE} from a serial number. */
    private static final class Temporal extends Column {
        private final ZoneId zone;
        private final boolean date1904;
        private IntVector.Builder days;
        private BigIntVector.Builder micros;

        Temporal(DataType type, int capacity, ZoneId zone, boolean date1904) {
            super(type, capacity);
            this.zone = zone;
            this.date1904 = date1904;
        }

        @Override
        void started() {
            if (type == DataType.DATE) {
                days = (IntVector.Builder) builder;
            } else {
                micros = (BigIntVector.Builder) builder;
            }
        }

        @Override
        void append(byte kind, String text) {
            if (kind == SheetReader.ISO_DATE) {
                builder.append(Casts.parse(text, type, zone));
                return;
            }
            double serial = number(kind, text);
            long m;
            try {
                m = Serials.toMicros(serial, date1904);
            } catch (ArithmeticException e) {
                throw new ConversionException("Could not convert " + text + " to " + type.sqlName() + ": "
                        + e.getMessage(), e);
            }
            if (type == DataType.DATE) {
                long d = Serials.days(m);
                days.appendInt(Math.toIntExact(d));
            } else if (type == DataType.TIMESTAMP) {
                micros.appendLong(m);
            } else if (type == DataType.TIME) {
                micros.appendLong(Serials.timeOfDay(m));
            } else {
                LocalDateTime local = LocalDateTime.ofEpochSecond(Math.floorDiv(m, 1_000_000L),
                        (int) Math.floorMod(m, 1_000_000L) * 1000, ZoneOffset.UTC);
                Instant instant = local.atZone(zone).toInstant();
                micros.appendLong(instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1000);
            }
        }
    }

    /** Any other type: the value of the cell cast to it. */
    private static final class Cast extends Column {
        private final ZoneId zone;

        Cast(DataType type, int capacity, ZoneId zone) {
            super(type, capacity);
            this.zone = zone;
        }

        @Override
        void started() {
        }

        @Override
        void append(byte kind, String text) {
            Object value = switch (kind) {
                case SheetReader.NUMBER -> Casts.cast(number(kind, text), DataType.DOUBLE, type, zone);
                case SheetReader.BOOLEAN -> Casts.cast(Conversions.parseBoolean(text), DataType.BOOLEAN, type, zone);
                default -> Casts.parse(text, type, zone);
            };
            builder.append(value);
        }
    }

    /** Reads a cell as a number: a number as written, a boolean as 1 or 0, anything else as the cast of its text. */
    static double number(byte kind, String text) {
        if (kind == SheetReader.BOOLEAN) {
            return text.equals("0") || text.equalsIgnoreCase("false") ? 0 : 1;
        }
        if (kind == SheetReader.NUMBER) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                // Such as nan or inf as DuckDB writes them; read below.
            }
        }
        try {
            return Conversions.parseDouble(text);
        } catch (RuntimeException e) {
            throw new ConversionException("Could not convert string '" + text + "' to DOUBLE", e);
        }
    }
}
