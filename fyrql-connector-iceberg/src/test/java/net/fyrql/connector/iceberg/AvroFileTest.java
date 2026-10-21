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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fyrql.connector.FileStores;

/**
 * The reader of Avro container files, against the manifest lists and
 * manifests that Iceberg Java writes with every codec, and against files of
 * every type that Apache Avro writes.
 */
class AvroFileTest {

    private static final String ALL_TYPES = """
            {"type": "record", "name": "r", "namespace": "ns", "fields": [
              {"name": "n", "type": "null"},
              {"name": "b", "type": "boolean"},
              {"name": "i", "type": "int", "field-id": 3},
              {"name": "l", "type": "long"},
              {"name": "f", "type": "float"},
              {"name": "d", "type": "double"},
              {"name": "by", "type": "bytes"},
              {"name": "s", "type": "string"},
              {"name": "u", "type": ["null", "string"]},
              {"name": "e", "type": {"type": "enum", "name": "color", "symbols": ["RED", "GREEN"]}},
              {"name": "e2", "type": "color"},
              {"name": "fx", "type": {"type": "fixed", "name": "four", "size": 4}},
              {"name": "a", "type": {"type": "array", "items": "long", "element-id": 7}},
              {"name": "m", "type": {"type": "map", "values": ["null", "int"]}},
              {"name": "inner", "type": {"type": "record", "name": "in", "fields": [
                {"name": "x", "type": "ns.four"}]}},
              {"name": "again", "type": ["null", "in"]}
            ]}""";

    private static IcebergTestTables tables;

    @TempDir
    static Path dir;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
    }

    @Test
    void manifestListsAndManifestsOfEveryTable() throws IOException {
        Map<String, String> codecs = Map.of("codec_snappy", "snappy", "codec_zstd", "zstandard",
                "codec_uncompressed", "null");
        for (String name : IcebergTestTables.NAMES) {
            Table table = tables.table(name);
            for (Snapshot snapshot : table.snapshots()) {
                AvroFile list = read(snapshot.manifestListLocation());
                assertEquals(String.valueOf(snapshot.snapshotId()), list.metadata().get("snapshot-id"), name);
                assertEquals("deflate", list.metadata().get("avro.codec"), name);
                List<ManifestFile> manifests = snapshot.allManifests(table.io());
                assertEquals(manifests.stream().map(ManifestFile::path).toList(),
                        list.records().stream().map(r -> ((Map<?, ?>) r).get("manifest_path")).toList(), name);
                for (ManifestFile manifest : manifests) {
                    AvroFile file = read(manifest.path());
                    assertEquals(codecs.getOrDefault(name, "deflate"), file.metadata().get("avro.codec"), name);
                    assertEquals(String.valueOf(manifest.partitionSpecId()), file.metadata().get("partition-spec-id"));
                    if (!name.equals("v1")) {
                        assertEquals(manifest.content() == ManifestContent.DATA ? "data" : "deletes",
                                file.metadata().get("content"), name);
                    }
                    Set<String> live = new HashSet<>();
                    for (Object r : file.records()) {
                        Map<?, ?> entry = (Map<?, ?>) r;
                        assertInstanceOf(Integer.class, entry.get("status"));
                        if ((Integer) entry.get("status") != 2) {
                            live.add((String) ((Map<?, ?>) entry.get("data_file")).get("file_path"));
                        }
                    }
                    assertEquals(paths(table, manifest), live, name + " " + manifest.path());
                }
            }
        }
    }

    @Test
    void theEntriesOfAManifest() throws IOException {
        Table table = tables.table("part");
        ManifestFile manifest = table.currentSnapshot().dataManifests(table.io()).get(0);
        Map<?, ?> entry = (Map<?, ?>) read(manifest.path()).records().get(0);
        Map<?, ?> file = (Map<?, ?>) entry.get("data_file");
        assertEquals(List.of("content", "file_path", "file_format", "partition", "record_count",
                "file_size_in_bytes"), new ArrayList<>(file.keySet()).subList(0, 6));
        assertEquals("PARQUET", file.get("file_format"));
        assertEquals(List.of("cat", "ts_day", "id_bucket", "name_trunc"),
                new ArrayList<>(((Map<?, ?>) file.get("partition")).keySet()));
        List<?> lower = (List<?>) file.get("lower_bounds");
        Map<?, ?> first = (Map<?, ?>) lower.get(0);
        assertEquals(Set.of("key", "value"), first.keySet());
        assertInstanceOf(Integer.class, first.get("key"));
        assertInstanceOf(byte[].class, first.get("value"));
    }

    @Test
    void everyTypeWithEveryCodecInSeveralBlocks() throws IOException {
        Schema schema = new Schema.Parser().parse(ALL_TYPES);
        List<GenericRecord> written = new ArrayList<>();
        for (int k = 0; k < 300; k++) {
            written.add(record(schema, k));
        }
        for (CodecFactory codec : List.of(CodecFactory.nullCodec(), CodecFactory.deflateCodec(6),
                CodecFactory.snappyCodec(), CodecFactory.zstandardCodec(3))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataFileWriter<GenericRecord> writer = new DataFileWriter<>(new GenericDatumWriter<>(schema))) {
                writer.setCodec(codec);
                writer.setSyncInterval(64);
                writer.setMeta("format-version", "2");
                writer.create(schema, out);
                for (GenericRecord r : written) {
                    writer.append(r);
                }
            }
            AvroFile file = AvroFile.read("all-" + codec, out.toByteArray());
            assertEquals("2", file.metadata().get("format-version"));
            AvroSchema.Record record = (AvroSchema.Record) file.schema();
            assertEquals(3, record.fields().get(2).fieldId());
            assertEquals(7, ((AvroSchema.Array) record.fields().get(12).schema()).elementId());
            assertEquals(300, file.records().size());
            for (int k = 0; k < 300; k++) {
                assertEquals(expected(k), normalize(file.records().get(k)), codec + " row " + k);
            }
        }
    }

    @Test
    void filesThatAreNotAvro() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> AvroFile.read("x", "not avro".getBytes()));
        Table table = tables.table("pos_v2");
        byte[] bytes = Files.readAllBytes(Path.of(table.currentSnapshot().manifestListLocation()));
        IllegalArgumentException cut = assertThrows(IllegalArgumentException.class,
                () -> AvroFile.read("cut.avro", Arrays.copyOf(bytes, bytes.length - 3)));
        assertTrue(cut.getMessage().startsWith("Cannot read the Avro file cut.avro: "), cut.getMessage());
        byte[] sync = bytes.clone();
        sync[sync.length - 1] ^= 1;
        IllegalArgumentException marker = assertThrows(IllegalArgumentException.class,
                () -> AvroFile.read("sync.avro", sync));
        assertTrue(marker.getMessage().contains("sync marker"), marker.getMessage());
    }

    private static GenericRecord record(Schema schema, int k) {
        GenericRecord r = new GenericData.Record(schema);
        r.put("n", null);
        r.put("b", k % 2 == 0);
        r.put("i", k * (k % 3 == 0 ? -1 : 1) * 1000);
        r.put("l", (long) k << 40 ^ -k);
        r.put("f", k / 4f);
        r.put("d", -k / 8.0);
        r.put("by", ByteBuffer.wrap(new byte[] {(byte) k, 1}));
        r.put("s", "s\u00E4" + k);
        r.put("u", k % 5 == 0 ? null : "u" + k);
        Schema color = schema.getField("e").schema();
        r.put("e", new GenericData.EnumSymbol(color, k % 2 == 0 ? "RED" : "GREEN"));
        r.put("e2", new GenericData.EnumSymbol(color, "GREEN"));
        r.put("fx", new GenericData.Fixed(schema.getField("fx").schema(), new byte[] {1, 2, 3, (byte) k}));
        List<Long> a = new ArrayList<>();
        for (int j = 0; j < k % 4; j++) {
            a.add((long) j - k);
        }
        r.put("a", a);
        Map<String, Integer> m = new LinkedHashMap<>();
        if (k % 3 == 1) {
            m.put("one", k);
            m.put("none", null);
        }
        r.put("m", m);
        GenericRecord inner = new GenericData.Record(schema.getField("inner").schema());
        inner.put("x", new GenericData.Fixed(schema.getField("fx").schema(), new byte[] {4, 3, 2, 1}));
        r.put("inner", inner);
        r.put("again", k % 2 == 0 ? null : inner);
        return r;
    }

    /** The values of a record of {@link #record} as the reader gives them, with arrays as lists. */
    private static List<Object> expected(int k) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (k % 3 == 1) {
            m.put("one", k);
            m.put("none", null);
        }
        List<Long> a = new ArrayList<>();
        for (int j = 0; j < k % 4; j++) {
            a.add((long) j - k);
        }
        Map<String, Object> inner = Map.of("x", List.of((byte) 4, (byte) 3, (byte) 2, (byte) 1));
        return Arrays.asList(null, k % 2 == 0, k * (k % 3 == 0 ? -1 : 1) * 1000, (long) k << 40 ^ -k, k / 4f,
                -k / 8.0, List.of((byte) k, (byte) 1), "s\u00E4" + k, k % 5 == 0 ? null : "u" + k,
                k % 2 == 0 ? "RED" : "GREEN", "GREEN", List.of((byte) 1, (byte) 2, (byte) 3, (byte) k), a, m, inner,
                k % 2 == 0 ? null : inner);
    }

    /** Returns the values of a record in order, byte arrays as lists so that they compare by content. */
    private static List<Object> normalize(Object record) {
        List<Object> values = new ArrayList<>();
        for (Object v : ((Map<?, ?>) record).values()) {
            values.add(bytesAsList(v));
        }
        return values;
    }

    private static Object bytesAsList(Object v) {
        if (v instanceof byte[] b) {
            List<Byte> list = new ArrayList<>();
            for (byte x : b) {
                list.add(x);
            }
            return list;
        }
        if (v instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(key, bytesAsList(value)));
            return copy;
        }
        return v;
    }

    private static AvroFile read(String location) throws IOException {
        return AvroFile.read(FileStores.local(dir).object(location));
    }

    private static Set<String> paths(Table table, ManifestFile manifest) throws IOException {
        Set<String> paths = new HashSet<>();
        if (manifest.content() == ManifestContent.DATA) {
            try (CloseableIterable<DataFile> files = ManifestFiles.read(manifest, table.io(), table.specs())) {
                files.forEach(f -> paths.add(f.location()));
            }
        } else {
            try (CloseableIterable<DeleteFile> files = ManifestFiles.readDeleteManifest(manifest, table.io(),
                    table.specs())) {
                files.forEach(f -> paths.add(((ContentFile<?>) f).location()));
            }
        }
        return paths;
    }
}
