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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.Sink;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.Vector;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes rows into a workbook of one sheet for {@code COPY ... TO}, as
 * DuckDB's {@code COPY ... (FORMAT xlsx)} writes them, into a file of any
 * store as it goes: the package is streamed into the file, the sheet last.
 * <ul>
 * <li>A number of any numeric type is a number cell with its exact text,
 * which Excel reads as a double; {@code NaN} and infinity, which a
 * workbook cannot hold as numbers, are the texts {@code nan}, {@code inf}
 * and {@code -inf}.</li>
 * <li>A {@code BOOLEAN} is a boolean cell.</li>
 * <li>A {@code DATE}, {@code TIMESTAMP} or {@code TIME} is its serial number
 * with a number format of its kind, so that a reader takes it for one; a
 * {@code TIMESTAMP WITH TIME ZONE} is the timestamp in the time zone of the
 * session. Before 1900-03-01 the numbers are Excel's, see
 * {@link Serials}.</li>
 * <li>Every other value is an inline string with its text as a cast to
 * {@code VARCHAR} gives it; a character XML cannot hold is written as
 * {@code _xHHHH_}, see {@link Escapes}.</li>
 * <li>A {@code NULL} is no cell.</li>
 * </ul>
 * The options, with DuckDB's names: {@code header}, whether the first row
 * holds the names of the columns, {@code false} by default; {@code sheet},
 * the name of the sheet, {@code Sheet1} by default, at most 31 characters
 * without {@code [ ] : * ? / \}; {@code sheet_row_limit}, the number of rows
 * above which the write fails, 1 048 576 by default, the rows of a sheet.
 */
final class XlsxFileWriter implements Sink {

    private static final int BUFFER = 1 << 16;
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PACKAGE_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    /** The styles of the cells, by their index into {@code cellXfs}. */
    private static final int DATE_STYLE = 1;
    private static final int TIMESTAMP_STYLE = 2;
    private static final int TIME_STYLE = 3;

    private final FileOutput file;
    private final ZipOutputStream zip;
    private final Writer out;
    private final DataType[] types;
    private final ZoneId zone;
    private final long rowLimit;
    private final StringBuilder line = new StringBuilder();
    private int row;
    private long rows;
    private boolean done;

    private XlsxFileWriter(FileOutput file, ZipOutputStream zip, Writer out, Schema schema, ZoneId zone,
            long rowLimit) {
        this.file = file;
        this.zip = zip;
        this.out = out;
        this.types = schema.fields().stream().map(Schema.Field::type).toArray(DataType[]::new);
        this.zone = zone;
        this.rowLimit = rowLimit;
    }

    /**
     * Checks the options, starts to write the file in its store and writes
     * the package up to the rows of the sheet, the header first among them.
     *
     * @throws IllegalArgumentException if an option is unknown or does not fit
     */
    static XlsxFileWriter open(String path, FileStores stores, Schema schema, Map<String, String> options) {
        ConfigOptions o = new ConfigOptions("the Excel file " + path, options, Path.of(""));
        boolean header = o.bool("header").orElse(false);
        String sheet = o.string("sheet").orElse("Sheet1");
        long limit = o.get("sheet_row_limit", "a number of rows from 1 to " + CellRef.MAX_ROWS,
                v -> {
                    long n = Long.parseLong(v.strip());
                    if (n < 1 || n > CellRef.MAX_ROWS) {
                        throw new IllegalArgumentException(v);
                    }
                    return n;
                }).orElse((long) CellRef.MAX_ROWS);
        ZoneId zone = o.get(FileWriterProvider.TIME_ZONE, null, FileWriterProvider::timeZone).orElse(ZoneOffset.UTC);
        o.rejectUnknown();
        checkSheetName(sheet, path);
        if (schema.size() > CellRef.MAX_COLUMNS) {
            throw new IllegalArgumentException("The Excel file " + path + " cannot hold " + schema.size()
                    + " columns; a sheet has at most " + CellRef.MAX_COLUMNS);
        }
        FileOutput file;
        try {
            file = stores.create(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path + ": " + e.getMessage(), e);
        }
        try {
            ZipOutputStream zip = new ZipOutputStream(file.stream());
            part(zip, "[Content_Types].xml", DECLARATION
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships"
                    + "+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.spreadsheetml.worksheet+xml\"/></Types>");
            part(zip, "_rels/.rels", DECLARATION + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + RELATIONSHIPS + "/officeDocument\""
                    + " Target=\"xl/workbook.xml\"/></Relationships>");
            part(zip, "xl/workbook.xml", DECLARATION + "<workbook xmlns=\"" + MAIN + "\" xmlns:r=\"" + RELATIONSHIPS
                    + "\"><sheets><sheet name=\"" + xml(sheet, true) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                    + "</workbook>");
            part(zip, "xl/_rels/workbook.xml.rels", DECLARATION + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS
                    + "\"><Relationship Id=\"rId1\" Type=\"" + RELATIONSHIPS + "/worksheet\""
                    + " Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + RELATIONSHIPS
                    + "/styles\" Target=\"styles.xml\"/></Relationships>");
            part(zip, "xl/styles.xml", DECLARATION + "<styleSheet xmlns=\"" + MAIN + "\">"
                    + "<numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/>"
                    + "<numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss.000\"/>"
                    + "<numFmt numFmtId=\"166\" formatCode=\"hh:mm:ss\"/></numFmts>"
                    + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                    + "</cellStyleXfs><cellXfs count=\"4\">"
                    + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
                    + " applyNumberFormat=\"1\"/>"
                    + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
                    + " applyNumberFormat=\"1\"/>"
                    + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\""
                    + " applyNumberFormat=\"1\"/>"
                    + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>"
                    + "</cellStyles></styleSheet>");
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer out = new BufferedWriter(new OutputStreamWriter(new FilterOutputStream(zip) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() {
                    // The entry is closed by the writer, the archive at the commit.
                }
            }, StandardCharsets.UTF_8), BUFFER);
            out.write(DECLARATION + "<worksheet xmlns=\"" + MAIN + "\"><sheetData>");
            XlsxFileWriter writer = new XlsxFileWriter(file, zip, out, schema, zone, limit);
            if (header) {
                writer.startRow();
                for (int c = 0; c < schema.size(); c++) {
                    writer.text(c, schema.fields().get(c).name());
                }
                writer.endRow();
            }
            return writer;
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /**
     * Checks the name of a sheet as Excel does: 1 to 31 characters, none of
     * {@code [ ] : * ? / \}, and no apostrophe at its start or end.
     */
    static void checkSheetName(String name, String path) {
        String problem = null;
        if (name.isEmpty() || name.length() > 31) {
            problem = "it must have 1 to 31 characters";
        } else if (name.startsWith("'") || name.endsWith("'")) {
            problem = "it must not start or end with an apostrophe";
        } else if (!Escapes.encode(name).equals(name)) {
            problem = "it must not hold control characters";
        } else {
            for (int i = 0; i < name.length(); i++) {
                if ("[]:*?/\\".indexOf(name.charAt(i)) >= 0) {
                    problem = "it must not hold any of [ ] : * ? / \\";
                    break;
                }
            }
        }
        if (problem != null) {
            throw new IllegalArgumentException("The sheet name \"" + name + "\" of the Excel file " + path
                    + " is invalid: " + problem);
        }
    }

    private static void part(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void write(Batch batch) {
        if (done) {
            throw new IllegalStateException("The Excel file " + file.location() + " is already closed");
        }
        int n = batch.rowCount();
        for (int r = 0; r < n; r++) {
            startRow();
            for (int c = 0; c < types.length; c++) {
                Vector v = batch.column(c);
                if (!v.isNull(r)) {
                    cell(c, v, r, types[c]);
                }
            }
            endRow();
        }
        rows += n;
    }

    private void cell(int column, Vector v, int r, DataType type) {
        if (type instanceof DataType.Decimal) {
            number(column, ((BigDecimal) v.get(r)).toPlainString(), 0);
            return;
        }
        if (!(type instanceof DataType.Scalar s)) {
            text(column, Casts.format(v.get(r), type, zone));
            return;
        }
        switch (s) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, HUGEINT -> number(column, String.valueOf(v.get(r)), 0);
            case FLOAT, DOUBLE -> {
                double d = s == DataType.Scalar.DOUBLE ? ((DoubleVector) v).getDouble(r) : ((Float) v.get(r));
                String formatted = Casts.format(v.get(r), type, zone);
                if (Double.isFinite(d)) {
                    number(column, formatted, 0);
                } else {
                    text(column, formatted);
                }
            }
            case BOOLEAN -> {
                start(column, "b", 0);
                line.append(Boolean.TRUE.equals(v.get(r)) ? '1' : '0').append("</v></c>");
            }
            case DATE -> number(column, serial(Serials.toSerial(((IntVector) v).getInt(r) * Serials.MICROS_PER_DAY)),
                    DATE_STYLE);
            case TIMESTAMP -> number(column, serial(Serials.toSerial(((BigIntVector) v).getLong(r))),
                    TIMESTAMP_STYLE);
            case TIMESTAMPTZ -> {
                long utc = ((BigIntVector) v).getLong(r);
                Instant instant = Instant.ofEpochSecond(Math.floorDiv(utc, 1_000_000L),
                        Math.floorMod(utc, 1_000_000L) * 1000L);
                ZonedDateTime local = instant.atZone(zone);
                long micros = local.toLocalDateTime().toEpochSecond(ZoneOffset.UTC) * 1_000_000L
                        + local.getNano() / 1000;
                number(column, serial(Serials.toSerial(micros)), TIMESTAMP_STYLE);
            }
            case TIME -> number(column, serial(Serials.timeSerial(((BigIntVector) v).getLong(r))), TIME_STYLE);
            default -> text(column, Casts.format(v.get(r), type, zone));
        }
    }

    /** Returns the text of a serial number: the shortest that reads back as the same double. */
    private static String serial(double value) {
        return Conversions.formatDouble(value);
    }

    private void startRow() {
        if (row >= rowLimit) {
            throw new IllegalArgumentException("The Excel file " + file.location() + " exceeds its limit of "
                    + rowLimit + " rows of the sheet; the option sheet_row_limit sets it, to at most "
                    + CellRef.MAX_ROWS);
        }
        row++;
        line.append("<row r=\"").append(row).append("\">");
    }

    private void endRow() {
        line.append("</row>");
        try {
            out.append(line);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        }
        line.setLength(0);
    }

    private void start(int column, String type, int style) {
        line.append("<c r=\"").append(CellRef.columnName(column)).append(row).append('"');
        if (type != null) {
            line.append(" t=\"").append(type).append('"');
        }
        if (style != 0) {
            line.append(" s=\"").append(style).append('"');
        }
        line.append("><v>");
    }

    private void number(int column, String text, int style) {
        start(column, null, style);
        line.append(text).append("</v></c>");
    }

    private void text(int column, String text) {
        line.append("<c r=\"").append(CellRef.columnName(column)).append(row)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(Escapes.encode(text), false))
                .append("</t></is></c>");
    }

    /** Escapes the characters of a text that XML gives a meaning, for an attribute also the quote. */
    private static String xml(String text, boolean attribute) {
        StringBuilder b = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String entity = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> attribute ? "&quot;" : null;
                default -> null;
            };
            if (entity != null && b == null) {
                b = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            if (b != null) {
                if (entity != null) {
                    b.append(entity);
                } else {
                    b.append(c);
                }
            }
        }
        return b == null ? text : b.toString();
    }

    @Override
    public long commit() {
        if (done) {
            throw new IllegalStateException("The Excel file " + file.location() + " is already closed");
        }
        done = true;
        try {
            out.write("</sheetData></worksheet>");
            out.flush();
            zip.closeEntry();
            zip.finish();
            zip.close();
            file.commit();
        } catch (IOException e) {
            file.close();
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        }
        return rows;
    }

    @Override
    public void close() {
        if (!done) {
            done = true;
            try {
                zip.close();
            } catch (IOException e) {
                // The file is deleted anyway.
            }
        }
        file.close();
    }
}
