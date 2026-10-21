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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fyrql.connector.FileStores;
import net.fyrql.connector.parquet.ParquetFile;
import net.fyrql.core.type.DataType;

/** The metadata files of the test tables and where they are, against Iceberg Java. */
class TableMetadataTest {

    private static IcebergTestTables tables;
    private static FileStores stores;

    @TempDir
    static Path dir;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
        stores = FileStores.local(dir);
    }

    private static TableMetadata metadata(TableLocation location) throws IOException {
        return TableMetadata.parse(location.metadataFile().location(), TableLocation.bytes(location.metadataFile()));
    }

    @Test
    void everyTableAsIcebergJavaSeesIt() throws IOException {
        for (String name : IcebergTestTables.NAMES) {
            Table table = tables.table(name);
            TableLocation location = TableLocation.resolve(stores, tables.location(name).toString());
            assertEquals(tables.metadata(name), Path.of(location.metadataFile().location()), name);
            assertEquals(tables.location(name).toString(), location.root(), name);
            TableMetadata m = metadata(location);
            var current = ((HasTableOperations) table).operations().current();
            assertEquals(current.formatVersion(), m.formatVersion(), name);
            assertEquals(table.location(), m.location(), name);
            assertEquals(table.currentSnapshot().snapshotId(), m.currentSnapshotId(), name);
            List<Long> ids = new ArrayList<>();
            List<Long> sequences = new ArrayList<>();
            for (Snapshot s : table.snapshots()) {
                ids.add(s.snapshotId());
                sequences.add(s.sequenceNumber());
                TableMetadata.Snapshot ours = m.snapshot(s.snapshotId());
                assertEquals(s.manifestListLocation(), ours.manifestList(), name);
                assertEquals(s.timestampMillis(), ours.timestampMillis(), name);
                assertEquals(s.operation(), ours.operation(), name);
                assertEquals(s.schemaId(), ours.schemaId(), name);
            }
            assertEquals(ids, m.snapshots().stream().map(TableMetadata.Snapshot::id).toList(), name);
            assertEquals(sequences, m.snapshots().stream().map(TableMetadata.Snapshot::sequenceNumber).toList(), name);
            var schema = table.schema();
            assertEquals(schema.columns().stream().map(c -> c.fieldId() + " " + c.name() + " " + c.isRequired())
                    .toList(), m.schema().columns().stream().map(c -> c.fieldId() + " " + c.name() + " "
                            + c.required()).toList(), name);
            assertEquals(table.specs().keySet(), m.specs().keySet(), name);
            table.specs().forEach((id, spec) -> assertEquals(spec.fields().stream().map(f -> f.sourceId() + " "
                    + f.fieldId() + " " + f.name() + " " + f.transform()).toList(), m.specs().get(id).fields().stream()
                    .map(f -> f.sourceId() + " " + f.fieldId() + " " + f.name() + " " + text(f.transform())).toList(),
                    name));
        }
    }

    @Test
    void theTypesOfTheColumnsAreThoseOfTheDataFiles() throws IOException {
        TableMetadata m = metadata(TableLocation.resolve(stores, tables.location("types").toString()));
        String file;
        try (var tasks = tables.table("types").newScan().planFiles()) {
            file = tasks.iterator().next().file().location();
        }
        ParquetFile parquet = ParquetFile.open(stores, file, 1 << 16);
        List<DataType> fromFile = new ArrayList<>();
        for (int c = 0; c < parquet.schema().size(); c++) {
            fromFile.add(parquet.schema().type(c));
        }
        assertEquals(fromFile, m.schema().schema().fields().stream().map(f -> f.type()).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
                m.schema().columns().stream().map(IcebergSchema.Column::fieldId).toList());
        assertEquals(new IcebergSchema.Primitive("decimal", 10, 2, 0), m.schema().column(6).primitive());
        assertEquals(new IcebergSchema.Primitive("fixed", 0, 0, 4), m.schema().column(13).primitive());
        assertNull(m.schema().column(15).primitive());
    }

    @Test
    void schemasOfSnapshotsRefsAndTimeTravel() throws IOException {
        TableMetadata evo = metadata(TableLocation.resolve(stores, tables.location("evo").toString()));
        assertEquals(2, evo.schemas().size());
        List<TableMetadata.Snapshot> snapshots = evo.snapshots();
        assertEquals(List.of("a", "f", "c", "d", "b"), names(evo.schema(snapshots.get(0))));
        assertEquals(List.of("a", "f", "c2", "d", "e"), names(evo.schema(snapshots.get(2))));
        assertEquals(DataType.BIGINT, evo.schema().schema().type(0));
        assertEquals(2, evo.specs().size());
        assertTrue(evo.specs().get(0).unpartitioned());
        TableMetadata branch = metadata(TableLocation.resolve(stores, tables.location("branch").toString()));
        Table table = tables.table("branch");
        assertEquals(table.refs().get("t1").snapshotId(), branch.ref("t1").id());
        assertEquals(table.refs().get("b1").snapshotId(), branch.ref("b1").id());
        assertEquals("tag", branch.refs().get("t1").type());
        assertEquals(table.currentSnapshot().snapshotId(), branch.ref("main").id());
        assertThrows(IllegalArgumentException.class, () -> branch.ref("nope"));
        TableMetadata.Snapshot first = branch.snapshots().get(0);
        assertEquals(first.id(), branch.asOf(first.timestampMillis()).id());
        assertEquals(branch.currentSnapshotId(), branch.asOf(Long.MAX_VALUE).id());
        assertThrows(IllegalArgumentException.class, () -> branch.asOf(first.timestampMillis() - 1));
        assertThrows(IllegalArgumentException.class, () -> branch.snapshot(42));
    }

    @Test
    void hintsListingsAndMetadataFiles() throws IOException {
        Path copy = tables.copy("eq_v2", dir.resolve("hinted"));
        Path metadata = copy.resolve("metadata");
        TableLocation latest = TableLocation.resolve(stores, copy.toString());
        assertEquals(tables.metadata("eq_v2").getFileName().toString(), latest.metadataFile().name());
        Files.writeString(metadata.resolve("version-hint.text"), "1\n");
        TableLocation one = TableLocation.resolve(stores, copy + "/");
        assertTrue(one.metadataFile().name().startsWith("00001-"), one.metadataFile().name());
        String second;
        try (var files = Files.list(metadata)) {
            second = files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("00002-")).findFirst()
                    .orElseThrow();
        }
        Files.writeString(metadata.resolve("version-hint.text"), second);
        assertEquals(second, TableLocation.resolve(stores, copy.toString()).metadataFile().name());
        Files.writeString(metadata.resolve("version-hint.text"), "99");
        assertEquals(latest.metadataFile().name(), TableLocation.resolve(stores, copy.toString()).metadataFile()
                .name());
        TableLocation file = TableLocation.resolve(stores, metadata.resolve(second).toString());
        assertEquals(copy.toString(), file.root());
        Path elsewhere = dir.resolve("elsewhere.metadata.json");
        Files.copy(metadata.resolve(second), elsewhere);
        assertNull(TableLocation.resolve(stores, elsewhere.toString()).root());
        assertThrows(IllegalArgumentException.class, () -> TableLocation.resolve(stores, dir.resolve("none")
                .toString()));
        assertThrows(IllegalArgumentException.class, () -> TableLocation.resolve(stores, dir.resolve(
                "none.metadata.json").toString()));
    }

    @Test
    void movedTablesAndPaths() throws IOException {
        Path copy = tables.copy("pos_v2", dir.resolve("moved"));
        TableLocation location = TableLocation.resolve(stores, copy.toString());
        TableMetadata m = metadata(location);
        String list = location.relocate(m.current().manifestList(), m.location());
        assertTrue(Files.isRegularFile(Path.of(list)), list);
        assertTrue(Path.of(list).startsWith(copy), list);
        assertEquals("s3://other/x", location.relocate("s3a://other/x", m.location()));
        assertEquals(copy.toString(), location.relocate(m.location(), m.location()));
        assertEquals(m.location() + "x/y", location.relocate(m.location() + "x/y", m.location()));
        assertEquals("C:/x", TableLocation.normalize("file:/C:/x"));
        assertEquals("C:/x", TableLocation.normalize("file:///C:/x"));
        assertEquals("/home/x", TableLocation.normalize("file:///home/x"));
        assertEquals("/home/x", TableLocation.normalize("file:/home/x"));
        assertEquals("s3://b/k", TableLocation.normalize("s3n://b/k"));
        assertEquals("/C:/x", TableLocation.normalize("/C:/x"));
    }

    @Test
    void version1WithoutListsAndInvalidMetadata() {
        String v1 = """
                {"format-version": 1, "location": "s3://b/t", "last-updated-ms": 1,
                 "schema": {"type": "struct", "fields": [{"id": 1, "name": "x", "required": true, "type": "int"},
                   {"id": 2, "name": "s", "required": false, "type": "string"}]},
                 "partition-spec": [{"name": "s", "transform": "identity", "source-id": 2}],
                 "current-snapshot-id": 7,
                 "snapshots": [{"snapshot-id": 7, "timestamp-ms": 5, "manifests": ["s3://b/t/m1.avro"]}]}""";
        TableMetadata m = TableMetadata.parse("v1.json", v1.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, m.formatVersion());
        assertEquals(List.of("s3://b/t/m1.avro"), m.current().manifests());
        assertNull(m.current().manifestList());
        assertEquals(0, m.current().sequenceNumber());
        assertEquals(new PartitionSpec.Field(2, 1000, "s", new PartitionSpec.Identity()),
                m.specs().get(0).fields().get(0));
        assertEquals(Map.of("main", new TableMetadata.Ref("main", 7, "branch")), m.refs());
        assertEquals(7, m.asOf(5).id());
        IllegalArgumentException variant = assertThrows(IllegalArgumentException.class, () -> TableMetadata.parse(
                "x.json", v1.replace("\"string\"", "\"variant\"").getBytes(StandardCharsets.UTF_8)));
        assertTrue(variant.getMessage().contains("Unsupported Iceberg type variant of the column s"),
                variant.getMessage());
        IllegalArgumentException version = assertThrows(IllegalArgumentException.class, () -> TableMetadata.parse(
                "x.json", v1.replace("\"format-version\": 1", "\"format-version\": 4")
                        .getBytes(StandardCharsets.UTF_8)));
        assertTrue(version.getMessage().startsWith("Cannot read the Iceberg metadata x.json: "), version.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TableMetadata.parse("x.json", "[]".getBytes()));
    }

    private static List<String> names(IcebergSchema schema) {
        return schema.columns().stream().map(IcebergSchema.Column::name).toList();
    }

    private static String text(PartitionSpec.Transform t) {
        return switch (t) {
            case PartitionSpec.Identity i -> "identity";
            case PartitionSpec.Bucket b -> "bucket[" + b.buckets() + "]";
            case PartitionSpec.Truncate r -> "truncate[" + r.width() + "]";
            case PartitionSpec.Time time -> time.unit();
            case PartitionSpec.VoidTransform v -> "void";
        };
    }
}
