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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.FileOutput;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.FileWriterProvider;
import net.fyrql.connector.Sink;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.BigIntVector;
import net.fyrql.core.vector.BooleanVector;
import net.fyrql.core.vector.BytesVector;
import net.fyrql.core.vector.DecimalVector;
import net.fyrql.core.vector.DoubleVector;
import net.fyrql.core.vector.FloatVector;
import net.fyrql.core.vector.HugeIntVector;
import net.fyrql.core.vector.IntVector;
import net.fyrql.core.vector.IntervalVector;
import net.fyrql.core.vector.ListVector;
import net.fyrql.core.vector.MapVector;
import net.fyrql.core.vector.SmallIntVector;
import net.fyrql.core.vector.StructVector;
import net.fyrql.core.vector.TinyIntVector;
import net.fyrql.core.vector.UuidVector;
import net.fyrql.core.vector.Vector;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.format.ConvertedType;
import org.apache.parquet.format.FileMetaData;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.format.Util;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.MessageType;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes rows into a Parquet file in pure Java, see
 * {@link ParquetConnector#openFile}: parquet-java's writer with a
 * {@link WriteSupport} that reads the values of each row from the vectors
 * of its batch, the codecs of {@link PureJavaCodecs}, and the schema of
 * {@link ParquetSchemas#message}.
 */
final class ParquetFileSink implements Sink {

    /** The rows of a row group by default, as in DuckDB. */
    static final int ROW_GROUP_ROWS = 122_880;

    private static final BigInteger MAX_HUGEINT_DECIMAL = BigInteger.TEN.pow(DataType.MAX_DECIMAL_PRECISION)
            .subtract(BigInteger.ONE);
    private static final long MAX_HIGH = MAX_HUGEINT_DECIMAL.shiftRight(64).longValue();
    private static final long MAX_LOW = MAX_HUGEINT_DECIMAL.longValue();
    private static final long MIN_HIGH = MAX_HUGEINT_DECIMAL.negate().shiftRight(64).longValue();
    private static final long MIN_LOW = MAX_HUGEINT_DECIMAL.negate().longValue();

    private final FileOutput file;
    private final Output output;
    private final ParquetWriter<Cursor> writer;
    private final Cursor cursor = new Cursor();
    private long rows;
    private boolean done;

    /** The row of a batch that the writer writes next. */
    private static final class Cursor {
        Batch batch;
        int row;
    }

    private ParquetFileSink(FileOutput file, Output output, ParquetWriter<Cursor> writer) {
        this.file = file;
        this.output = output;
        this.writer = writer;
    }

    /**
     * Checks the options and the types, and starts to write the file in its store.
     *
     * @throws IllegalArgumentException if an option is unknown or does not
     *         fit, or if a column has a type the file cannot hold
     */
    static ParquetFileSink open(String location, FileStores stores, Schema schema, Map<String, String> options) {
        String owner = "the Parquet file " + location;
        ConfigOptions o = new ConfigOptions(owner, options, Path.of(""));
        Optional<String> compression = o.string("compression");
        Optional<String> codecOption = o.string("codec");
        String codecName = compression.or(() -> codecOption).orElse("zstd").toLowerCase(Locale.ROOT);
        CompressionCodecName codec = switch (codecName) {
            case "zstd" -> CompressionCodecName.ZSTD;
            case "snappy" -> CompressionCodecName.SNAPPY;
            case "gzip" -> CompressionCodecName.GZIP;
            case "lz4", "lz4_raw" -> CompressionCodecName.LZ4_RAW;
            case "uncompressed", "none" -> CompressionCodecName.UNCOMPRESSED;
            default -> throw new IllegalArgumentException("The option compression of " + owner
                    + " must be zstd, snappy, gzip, lz4_raw or uncompressed, not " + codecName);
        };
        int rowGroupRows = o.get("row_group_size", "a positive number of rows", v -> positive(Long.parseLong(
                v.strip()))).map(Math::toIntExact).orElse(ROW_GROUP_ROWS);
        long rowGroupBytes = o.bytes("row_group_size_bytes").orElse((long) ParquetWriter.DEFAULT_BLOCK_SIZE);
        if (rowGroupBytes <= 0) {
            throw new IllegalArgumentException("The option row_group_size_bytes of " + owner + " must be positive");
        }
        ParquetProperties.WriterVersion version = o.get("parquet_version", "v1 or v2", v -> switch (
                v.strip().toLowerCase(Locale.ROOT)) {
            case "v1" -> ParquetProperties.WriterVersion.PARQUET_1_0;
            case "v2" -> ParquetProperties.WriterVersion.PARQUET_2_0;
            default -> throw new IllegalArgumentException(v);
        }).orElse(ParquetProperties.WriterVersion.PARQUET_1_0);
        // A file holds instants in UTC.
        o.string(FileWriterProvider.TIME_ZONE);
        o.rejectUnknown();
        MessageType message = ParquetSchemas.message(schema);
        ValueWriter[] columns = new ValueWriter[schema.size()];
        for (int c = 0; c < columns.length; c++) {
            columns[c] = writer(schema.type(c), schema.fields().get(c).name());
        }

        FileOutput file;
        try {
            file = stores.create(location);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + location + ": " + e.getMessage(), e);
        }
        boolean intervals = schema.fields().stream().anyMatch(f -> Casts.mentions(f.type(), DataType.Scalar.INTERVAL));
        Output output = new Output(file, intervals);
        try {
            ParquetWriter<Cursor> writer = new Builder(output, new RowSupport(message, schema, columns))
                    .withConf(new PlainParquetConfiguration())
                    .withCodecFactory(new PureJavaCodecs())
                    .withCompressionCodec(codec)
                    .withRowGroupRowCountLimit(rowGroupRows)
                    .withRowGroupSize(rowGroupBytes)
                    .withWriterVersion(version)
                    .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                    .build();
            return new ParquetFileSink(file, output, writer);
        } catch (IOException e) {
            output.abort();
            file.close();
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            output.abort();
            file.close();
            throw e;
        }
    }

    private static long positive(long n) {
        if (n <= 0) {
            throw new IllegalArgumentException(Long.toString(n));
        }
        return n;
    }

    @Override
    public void write(Batch batch) {
        if (done) {
            throw new IllegalStateException("The Parquet file " + file.location() + " is already closed");
        }
        cursor.batch = batch;
        try {
            for (int r = 0; r < batch.rowCount(); r++) {
                cursor.row = r;
                writer.write(cursor);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        } finally {
            cursor.batch = null;
        }
        rows += batch.rowCount();
    }

    @Override
    public long commit() {
        if (done) {
            throw new IllegalStateException("The Parquet file " + file.location() + " is already closed");
        }
        done = true;
        try {
            output.holdBack();
            writer.close();
            file.commit();
        } catch (IOException e) {
            output.abort();
            file.close();
            throw new UncheckedIOException("Cannot write " + file.location() + ": " + e.getMessage(), e);
        }
        return rows;
    }

    /** Abandons the file without writing what the writer holds: closes the stream and leaves no file. */
    @Override
    public void close() {
        if (!done) {
            done = true;
            output.abort();
        }
        file.close();
    }

    /** Writes a value that is not {@code NULL} at a row of a vector. */
    @FunctionalInterface
    private interface ValueWriter {
        void write(RecordConsumer out, Vector vector, int row);
    }

    /** Returns the writer of the values of a type, for the column or field at a path. */
    private static ValueWriter writer(DataType type, String path) {
        return switch (type) {
            case DataType.Decimal d when d.precision() <= 9 -> (out, v, r) -> out.addInteger((int) unscaled(v, r));
            case DataType.Decimal d when d.precision() <= 18 -> (out, v, r) -> out.addLong(unscaled(v, r));
            case DataType.Decimal d -> (out, v, r) -> out.addBinary(Binary.fromConstantByteArray(
                    sixteenBytes(((DecimalVector) v).getUnscaled(r))));
            case DataType.Struct s -> struct(s, path);
            case DataType.ListType l -> list(l, path);
            case DataType.MapType m -> map(m, path);
            case DataType.Scalar s -> switch (s) {
                case BOOLEAN -> (out, v, r) -> out.addBoolean(((BooleanVector) v).getBoolean(r));
                case TINYINT -> (out, v, r) -> out.addInteger(((TinyIntVector) v).getByte(r));
                case SMALLINT -> (out, v, r) -> out.addInteger(((SmallIntVector) v).getShort(r));
                case INTEGER, DATE -> (out, v, r) -> out.addInteger(((IntVector) v).getInt(r));
                case BIGINT, TIMESTAMP, TIME, TIMESTAMPTZ -> (out, v, r) -> out.addLong(((BigIntVector) v)
                        .getLong(r));
                case INTERVAL -> (out, v, r) -> out.addBinary(interval((IntervalVector) v, r, path));
                case HUGEINT -> (out, v, r) -> out.addBinary(hugeint((HugeIntVector) v, r, path));
                case FLOAT -> (out, v, r) -> out.addFloat(((FloatVector) v).getFloat(r));
                case DOUBLE -> (out, v, r) -> out.addDouble(((DoubleVector) v).getDouble(r));
                case VARCHAR, BLOB -> (out, v, r) -> {
                    BytesVector b = (BytesVector) v;
                    // Reused: parquet-java copies the bytes it keeps, so that it holds no batch.
                    out.addBinary(Binary.fromReusedByteArray(b.data(), b.start(r), b.length(r)));
                };
                case UUID -> (out, v, r) -> {
                    UuidVector u = (UuidVector) v;
                    byte[] bytes = new byte[16];
                    putLong(bytes, 0, u.getMost(r));
                    putLong(bytes, 8, u.getLeast(r));
                    out.addBinary(Binary.fromConstantByteArray(bytes));
                };
            };
        };
    }

    private static ValueWriter struct(DataType.Struct type, String path) {
        List<DataType.Struct.Field> fields = type.fields();
        ValueWriter[] writers = new ValueWriter[fields.size()];
        for (int f = 0; f < writers.length; f++) {
            writers[f] = writer(fields.get(f).type(), path + "." + fields.get(f).name());
        }
        return (out, v, r) -> {
            StructVector s = (StructVector) v;
            out.startGroup();
            for (int f = 0; f < writers.length; f++) {
                Vector child = s.child(f);
                if (!child.isNull(r)) {
                    String name = fields.get(f).name();
                    out.startField(name, f);
                    writers[f].write(out, child, r);
                    out.endField(name, f);
                }
            }
            out.endGroup();
        };
    }

    private static ValueWriter list(DataType.ListType type, String path) {
        ValueWriter element = writer(type.element(), path + "[]");
        return (out, v, r) -> {
            ListVector l = (ListVector) v;
            Vector elements = l.child();
            out.startGroup();
            if (l.length(r) > 0) {
                out.startField("list", 0);
                for (int e = l.start(r); e < l.end(r); e++) {
                    out.startGroup();
                    if (!elements.isNull(e)) {
                        out.startField("element", 0);
                        element.write(out, elements, e);
                        out.endField("element", 0);
                    }
                    out.endGroup();
                }
                out.endField("list", 0);
            }
            out.endGroup();
        };
    }

    private static ValueWriter map(DataType.MapType type, String path) {
        ValueWriter key = writer(type.key(), path + ".key");
        ValueWriter value = writer(type.value(), path + ".value");
        return (out, v, r) -> {
            MapVector m = (MapVector) v;
            Vector keys = m.keys();
            Vector values = m.values();
            out.startGroup();
            if (m.length(r) > 0) {
                out.startField("key_value", 0);
                for (int e = m.start(r); e < m.end(r); e++) {
                    out.startGroup();
                    out.startField("key", 0);
                    key.write(out, keys, e);
                    out.endField("key", 0);
                    if (!values.isNull(e)) {
                        out.startField("value", 1);
                        value.write(out, values, e);
                        out.endField("value", 1);
                    }
                    out.endGroup();
                }
                out.endField("key_value", 0);
            }
            out.endGroup();
        };
    }

    /** Returns the unscaled value of a {@code DECIMAL} of at most 18 digits. */
    private static long unscaled(Vector v, int row) {
        DecimalVector d = (DecimalVector) v;
        return d.isSmall() ? d.getUnscaledLong(row) : d.getUnscaled(row).longValueExact();
    }

    /**
     * Returns a {@code HUGEINT} as the 16 bytes of a {@code DECIMAL(38,0)}.
     *
     * @throws IllegalArgumentException for a value of 39 digits
     */
    /**
     * An interval as DuckDB writes it: months, days and milliseconds as three
     * little-endian unsigned integers, the milliseconds cut to 32 bits.
     *
     * @throws IllegalArgumentException if a part is negative, as in DuckDB
     */
    private static Binary interval(IntervalVector v, int row, String path) {
        int months = v.getMonths(row);
        int days = v.getDays(row);
        long micros = v.getMicros(row);
        if (months < 0 || days < 0 || micros < 0) {
            throw new IllegalArgumentException("Parquet files do not support negative intervals, as "
                    + v.get(row) + " of the column " + path + " is");
        }
        byte[] bytes = new byte[12];
        putIntLittleEndian(bytes, 0, months);
        putIntLittleEndian(bytes, 4, days);
        putIntLittleEndian(bytes, 8, (int) (micros / 1000));
        return Binary.fromConstantByteArray(bytes);
    }

    private static Binary hugeint(HugeIntVector v, int row, String path) {
        long high = v.getHigh(row);
        long low = v.getLow(row);
        if (HugeIntVector.compare(high, low, MAX_HIGH, MAX_LOW) > 0
                || HugeIntVector.compare(high, low, MIN_HIGH, MIN_LOW) < 0) {
            throw new IllegalArgumentException("The value " + v.getBigInteger(row) + " of the column " + path
                    + " has more than 38 digits; a Parquet file holds a HUGEINT as a DECIMAL(38,0)");
        }
        byte[] bytes = new byte[16];
        putLong(bytes, 0, high);
        putLong(bytes, 8, low);
        return Binary.fromConstantByteArray(bytes);
    }

    /** Returns a number of at most 38 digits in two's complement on 16 bytes, big-endian. */
    private static byte[] sixteenBytes(BigInteger unscaled) {
        byte[] raw = unscaled.toByteArray();
        byte[] bytes = new byte[16];
        java.util.Arrays.fill(bytes, 0, 16 - raw.length, (byte) (unscaled.signum() < 0 ? 0xff : 0));
        System.arraycopy(raw, 0, bytes, 16 - raw.length, raw.length);
        return bytes;
    }

    private static void putIntLittleEndian(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            bytes[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static void putLong(byte[] bytes, int offset, long value) {
        for (int i = 7; i >= 0; i--) {
            bytes[offset + i] = (byte) value;
            value >>>= 8;
        }
    }

    /** Writes the rows of the cursor, column by column; a {@code NULL} in a required column is an error. */
    private static final class RowSupport extends WriteSupport<Cursor> {
        private final MessageType message;
        private final String[] names;
        private final boolean[] required;
        private final ValueWriter[] columns;
        private RecordConsumer out;

        RowSupport(MessageType message, Schema schema, ValueWriter[] columns) {
            this.message = message;
            this.names = schema.fields().stream().map(Schema.Field::name).toArray(String[]::new);
            this.required = new boolean[names.length];
            for (int c = 0; c < names.length; c++) {
                required[c] = !schema.nullable(c);
            }
            this.columns = columns;
        }

        @Override
        public WriteContext init(org.apache.hadoop.conf.Configuration configuration) {
            return new WriteContext(message, Map.of());
        }

        @Override
        public WriteContext init(ParquetConfiguration configuration) {
            return new WriteContext(message, Map.of());
        }

        /** The name parquet-java writes as {@code writer.model.name} into the metadata of the file. */
        @Override
        public String getName() {
            return "fyrql";
        }

        @Override
        public void prepareForWrite(RecordConsumer recordConsumer) {
            out = recordConsumer;
        }

        @Override
        public void write(Cursor cursor) {
            out.startMessage();
            for (int c = 0; c < columns.length; c++) {
                Vector v = cursor.batch.column(c);
                if (v.isNull(cursor.row)) {
                    if (required[c]) {
                        throw new IllegalArgumentException("The column " + names[c] + " is NOT NULL but holds a"
                                + " NULL");
                    }
                    continue;
                }
                out.startField(names[c], c);
                columns[c].write(out, v, cursor.row);
                out.endField(names[c], c);
            }
            out.endMessage();
        }
    }

    private static final class Builder extends ParquetWriter.Builder<Cursor, Builder> {
        private final RowSupport support;

        Builder(OutputFile file, RowSupport support) {
            super(file);
            this.support = support;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        protected WriteSupport<Cursor> getWriteSupport(org.apache.hadoop.conf.Configuration configuration) {
            return support;
        }

        @Override
        protected WriteSupport<Cursor> getWriteSupport(ParquetConfiguration configuration) {
            return support;
        }
    }

    /**
     * The file being written as an output of parquet-java, which keeps the
     * stream so that a write can be abandoned without the footer.
     * <p>
     * parquet-java marks an interval with the converted type
     * {@code INTERVAL} and the logical type {@code UNKNOWN}, which means a
     * column of nothing but {@code NULL}s and which DuckDB reads so. For a
     * schema with intervals the output holds back what the writer writes
     * when it closes, the last row group and the footer, and writes the
     * footer without that logical type, as DuckDB does.
     */
    private static final class Output implements OutputFile {
        private static final byte[] MAGIC = "PAR1".getBytes(StandardCharsets.US_ASCII);

        private final FileOutput file;
        private final boolean intervals;
        private OutputStream stream;
        /** What is held back, or {@code null}. */
        private ByteArrayOutputStream held;

        Output(FileOutput file, boolean intervals) {
            this.file = file;
            this.intervals = intervals;
        }

        /** Holds back what is written from now on if the schema has intervals. */
        void holdBack() {
            if (intervals) {
                held = new ByteArrayOutputStream();
            }
        }

        /** Writes what was held back, with the footer without the logical type of the intervals. */
        private void release(OutputStream s) throws IOException {
            if (held == null) {
                return;
            }
            byte[] tail = held.toByteArray();
            held = null;
            int n = tail.length;
            int length = n < 8 ? -1 : ByteBuffer.wrap(tail, n - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int start = n - 8 - length;
            if (length < 0 || start < 0) {
                throw new IOException("The footer of the Parquet file " + file.location() + " was not found");
            }
            FileMetaData footer = Util.readFileMetaData(new ByteArrayInputStream(tail, start, length));
            for (SchemaElement e : footer.getSchema()) {
                if (e.getConverted_type() == ConvertedType.INTERVAL) {
                    e.unsetLogicalType();
                }
            }
            ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
            Util.writeFileMetaData(footer, rewritten);
            s.write(tail, 0, start);
            rewritten.writeTo(s);
            s.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(rewritten.size()).array());
            s.write(MAGIC);
        }

        @Override
        public PositionOutputStream create(long blockSizeHint) throws IOException {
            return createOrOverwrite(blockSizeHint);
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
            OutputStream s = new BufferedOutputStream(file.stream(), 1 << 16);
            stream = s;
            return new PositionOutputStream() {
                private long position;

                @Override
                public long getPos() {
                    return position;
                }

                @Override
                public void write(int b) throws IOException {
                    (held != null ? held : s).write(b);
                    position++;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    (held != null ? held : s).write(b, off, len);
                    position += len;
                }

                @Override
                public void flush() throws IOException {
                    s.flush();
                }

                @Override
                public void close() throws IOException {
                    try {
                        release(s);
                    } finally {
                        s.close();
                    }
                }
            };
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0;
        }

        @Override
        public String getPath() {
            return file.location();
        }

        /** Closes the stream, if any, quietly. */
        void abort() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // The file is deleted anyway.
                }
            }
        }
    }
}
