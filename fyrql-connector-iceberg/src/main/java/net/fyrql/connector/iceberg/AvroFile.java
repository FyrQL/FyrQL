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

package net.fyrql.connector.iceberg;

import net.fyrql.connector.FileObject;
import net.fyrql.connector.parquet.AvroCodecs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Avro container file read whole, as the manifest lists and manifests
 * of Iceberg are: its metadata, its schema and its records, each read with
 * the schema of the file. A record is a map from the names of its fields
 * to their values: {@code Integer}, {@code Long}, {@code Float},
 * {@code Double}, {@code Boolean}, {@code String}, {@code byte[]} for
 * bytes and fixed, a {@code List} for an array, a {@code Map} for a map or
 * a record, and {@code null}.
 */
final class AvroFile {

    private static final byte[] MAGIC = {'O', 'b', 'j', 1};
    private static final int SYNC = 16;

    private final Map<String, String> metadata;
    private final AvroSchema schema;
    private final List<Object> records;

    private AvroFile(Map<String, String> metadata, AvroSchema schema, List<Object> records) {
        this.metadata = metadata;
        this.schema = schema;
        this.records = records;
    }

    /**
     * Reads a file.
     *
     * @throws IllegalArgumentException naming the file if it cannot be read
     *         or is not an Avro container file
     */
    static AvroFile read(FileObject file) {
        byte[] bytes;
        try (InputStream in = file.open()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file.location() + ": " + e.getMessage(), e);
        }
        return read(file.location(), bytes);
    }

    /**
     * Reads the bytes of a file.
     *
     * @param location the location of the file, for messages
     * @throws IllegalArgumentException naming the file if it is not an Avro
     *         container file or uses a codec other than those of {@link AvroCodecs}
     */
    static AvroFile read(String location, byte[] bytes) {
        try {
            if (bytes.length < MAGIC.length || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
                throw new IOException("not an Avro container file");
            }
            Decoder header = new Decoder(bytes, MAGIC.length, bytes.length);
            Map<String, String> metadata = new LinkedHashMap<>();
            for (long n = header.blockCount(); n != 0; n = header.blockCount()) {
                for (long i = 0; i < n; i++) {
                    metadata.put(header.string(), new String(header.bytes(), StandardCharsets.UTF_8));
                }
            }
            String json = metadata.get("avro.schema");
            if (json == null) {
                throw new IOException("no avro.schema in the metadata");
            }
            AvroSchema schema = AvroSchema.parse(json);
            String codec = metadata.get("avro.codec");
            byte[] sync = header.fixed(SYNC);
            List<Object> records = new ArrayList<>();
            int at = header.position();
            while (at < bytes.length) {
                Decoder block = new Decoder(bytes, at, bytes.length);
                long count = block.readLong();
                long size = block.readLong();
                if (count < 0 || size < 0 || size > bytes.length - block.position()) {
                    throw new IOException("invalid block at byte " + at);
                }
                byte[] data = AvroCodecs.decompress(codec, block.fixed((int) size));
                if (!Arrays.equals(block.fixed(SYNC), sync)) {
                    throw new IOException("wrong sync marker after the block at byte " + at);
                }
                Decoder values = new Decoder(data, 0, data.length);
                for (long i = 0; i < count; i++) {
                    records.add(values.read(schema));
                }
                at = block.position();
            }
            return new AvroFile(Collections.unmodifiableMap(metadata), schema, Collections.unmodifiableList(records));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Avro file " + location + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    /** Returns the metadata of the file, the values as UTF-8 text. */
    Map<String, String> metadata() {
        return metadata;
    }

    /** Returns the schema the file was written with. */
    AvroSchema schema() {
        return schema;
    }

    /** Returns the records, as values of the schema. */
    List<Object> records() {
        return records;
    }

    /** Reads the binary encoding of Avro from a range of bytes. */
    static final class Decoder {
        private final byte[] bytes;
        private int position;
        private final int end;

        Decoder(byte[] bytes, int from, int to) {
            this.bytes = bytes;
            this.position = from;
            this.end = to;
        }

        int position() {
            return position;
        }

        /** Reads a {@code long} as a variable-length zig-zag number. */
        long readLong() throws IOException {
            long raw = 0;
            for (int shift = 0; ; shift += 7) {
                if (shift > 63) {
                    throw new IOException("invalid variable-length number");
                }
                int b = next();
                raw |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            return raw >>> 1 ^ -(raw & 1);
        }

        int readInt() throws IOException {
            long v = readLong();
            if (v != (int) v) {
                throw new IOException("int out of range: " + v);
            }
            return (int) v;
        }

        private int next() throws IOException {
            if (position >= end) {
                throw new IOException("unexpected end of data");
            }
            return bytes[position++] & 0xff;
        }

        byte[] fixed(int size) throws IOException {
            if (size < 0 || size > end - position) {
                throw new IOException("unexpected end of data");
            }
            byte[] b = Arrays.copyOfRange(bytes, position, position + size);
            position += size;
            return b;
        }

        byte[] bytes() throws IOException {
            long length = readLong();
            if (length < 0 || length > end - position) {
                throw new IOException("invalid length " + length);
            }
            return fixed((int) length);
        }

        String string() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        private long fixedLittleEndian(int size) throws IOException {
            if (size > end - position) {
                throw new IOException("unexpected end of data");
            }
            long v = 0;
            for (int i = size - 1; i >= 0; i--) {
                v = v << 8 | bytes[position + i] & 0xff;
            }
            position += size;
            return v;
        }

        /** Reads the count of the next block of an array or map; a negative count is followed by the bytes. */
        long blockCount() throws IOException {
            long n = readLong();
            if (n < 0) {
                readLong();
                n = -n;
            }
            return n;
        }

        /** Reads a value of a schema. */
        Object read(AvroSchema schema) throws IOException {
            return switch (schema) {
                case AvroSchema.Primitive p -> switch (p.kind()) {
                    case NULL -> null;
                    case BOOLEAN -> next() != 0;
                    case INT -> readInt();
                    case LONG -> readLong();
                    case FLOAT -> Float.intBitsToFloat((int) fixedLittleEndian(4));
                    case DOUBLE -> Double.longBitsToDouble(fixedLittleEndian(8));
                    case BYTES -> bytes();
                    case STRING -> string();
                };
                case AvroSchema.Record r -> {
                    Map<String, Object> record = new LinkedHashMap<>();
                    for (AvroSchema.Field f : r.fields()) {
                        record.put(f.name(), read(f.schema()));
                    }
                    yield record;
                }
                case AvroSchema.Enum e -> {
                    int index = readInt();
                    if (index < 0 || index >= e.symbols().size()) {
                        throw new IOException("invalid symbol " + index + " of the enum " + e.name());
                    }
                    yield e.symbols().get(index);
                }
                case AvroSchema.Array a -> {
                    List<Object> list = new ArrayList<>();
                    for (long n = blockCount(); n != 0; n = blockCount()) {
                        for (long i = 0; i < n; i++) {
                            list.add(read(a.items()));
                        }
                    }
                    yield list;
                }
                case AvroSchema.MapType m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (long n = blockCount(); n != 0; n = blockCount()) {
                        for (long i = 0; i < n; i++) {
                            map.put(string(), read(m.values()));
                        }
                    }
                    yield map;
                }
                case AvroSchema.Union u -> {
                    int index = readInt();
                    if (index < 0 || index >= u.branches().size()) {
                        throw new IOException("invalid branch " + index + " of a union");
                    }
                    yield read(u.branches().get(index));
                }
                case AvroSchema.Fixed f -> fixed(f.size());
            };
        }
    }
}
