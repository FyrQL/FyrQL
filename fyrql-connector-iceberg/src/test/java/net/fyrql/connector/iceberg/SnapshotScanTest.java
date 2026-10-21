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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import org.apache.iceberg.ContentFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fyrql.connector.FileStores;

/**
 * The data files of every snapshot of the test tables with the delete
 * files that apply to them, against Iceberg Java, and the rules of the
 * specification on files made up for them.
 */
class SnapshotScanTest {

    private static IcebergTestTables tables;
    private static FileStores stores;

    @TempDir
    static Path dir;

    @BeforeAll
    static void tables() throws IOException {
        tables = IcebergTestTables.shared();
        stores = FileStores.local(dir);
    }

    private static final Function<String, AvroFile> READ = location -> {
        try {
            return AvroFile.read(stores.object(location));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };

    @Test
    void everySnapshotAsIcebergJavaPlansIt() throws IOException {
        int compared = 0;
        for (String name : IcebergTestTables.NAMES) {
            Table table = tables.table(name);
            TableLocation location = TableLocation.resolve(stores, tables.location(name).toString());
            TableMetadata metadata = TableMetadata.parse(location.metadataFile().location(),
                    TableLocation.bytes(location.metadataFile()));
            for (Snapshot snapshot : table.snapshots()) {
                Map<String, Set<String>> expected = new TreeMap<>();
                try (CloseableIterable<FileScanTask> tasks = table.newScan().useSnapshot(snapshot.snapshotId())
                        .planFiles()) {
                    for (FileScanTask task : tasks) {
                        Set<String> deletes = expected.computeIfAbsent(task.file().location(), f -> new TreeSet<>());
                        task.deletes().forEach(d -> deletes.add(((ContentFile<?>) d).location()));
                    }
                }
                List<SnapshotScan.FileTask> ours = SnapshotScan.plan(metadata, location,
                        metadata.snapshot(snapshot.snapshotId()), READ, m -> true, f -> true);
                assertEquals(expected, files(ours), name + " " + snapshot.snapshotId());
                for (SnapshotScan.FileTask task : ours) {
                    assertTrue(Files.isRegularFile(Path.of(task.data().location())), task.data().location());
                    assertEquals(task.data().recordCount() > 0, true);
                }
                compared++;
            }
        }
        assertEquals(24, compared);
    }

    @Test
    void theFilesCarryWhatTheManifestsSay() throws IOException {
        Map<String, SnapshotScan.FileTask> pos = byName("pos_v2");
        assertEquals(2, pos.size());
        for (SnapshotScan.FileTask t : pos.values()) {
            assertEquals(1, t.data().dataSequenceNumber());
            assertEquals(1, t.positionDeletes().size());
            assertEquals(2, t.positionDeletes().get(0).dataSequenceNumber());
            assertEquals("PARQUET", t.positionDeletes().get(0).format());
            assertEquals(10, t.data().recordCount());
            Object cat = t.data().partition()[0];
            assertTrue(cat.equals("a") || cat.equals("b"), String.valueOf(cat));
            assertEquals(Long.valueOf(10), t.data().valueCounts().get(1));
            assertTrue(t.hasPositionDeletes());
        }
        SnapshotScan.FileTask dv = byName("dv_v3").values().iterator().next();
        assertEquals("PUFFIN", dv.deletionVector().format());
        assertTrue(dv.deletionVector().isDeletionVector());
        assertEquals(dv.data().path(), dv.deletionVector().referencedDataFile());
        assertTrue(dv.deletionVector().contentOffset() > 0);
        assertEquals(2, dv.deletionVector().recordCount());
        List<SnapshotScan.FileTask> eq = new ArrayList<>(byName("eq_v2").values());
        assertEquals(List.of(1, 0), eq.stream().map(t -> t.equalityDeletes().size()).sorted((a, b) -> b - a)
                .toList());
        assertEquals(List.of(1), eq.stream().filter(t -> !t.equalityDeletes().isEmpty()).findFirst().orElseThrow()
                .equalityDeletes().get(0).equalityIds());
        Map<String, SnapshotScan.FileTask> part = byName("part");
        SnapshotScan.FileTask any = part.values().iterator().next();
        assertEquals(4, any.data().partition().length);
        assertTrue(any.data().partition()[1] instanceof Integer, "day as days since 1970");
        assertTrue(any.data().lowerBounds().containsKey(3));
    }

    @Test
    void theRulesOfTheSpecification() {
        PartitionSpec byCat = new PartitionSpec(1, List.of(new PartitionSpec.Field(2, 1000, "cat",
                new PartitionSpec.Identity())));
        Map<Integer, PartitionSpec> specs = Map.of(0, PartitionSpec.UNPARTITIONED, 1, byCat);
        Manifests.ContentFile a = file(0, "a.parquet", 1, "a", 3, null);
        Manifests.ContentFile b = file(0, "b.parquet", 1, "b", 3, null);
        Manifests.ContentFile old = file(1, "pos-old", 1, "a", 2, null);
        Manifests.ContentFile same = file(1, "pos-same", 1, "a", 3, null);
        Manifests.ContentFile other = file(1, "pos-other", 1, "a", 4, "c.parquet");
        Manifests.ContentFile eqSame = file(2, "eq-same", 1, "a", 3, null);
        Manifests.ContentFile eqLater = file(2, "eq-later", 1, "a", 4, null);
        Manifests.ContentFile eqGlobal = file(2, "eq-global", 0, null, 5, null);
        List<SnapshotScan.FileTask> tasks = SnapshotScan.match(List.of(a, b),
                List.of(old, same, other, eqSame, eqLater, eqGlobal), specs);
        assertEquals(List.of(same), tasks.get(0).positionDeletes());
        assertEquals(List.of(eqLater, eqGlobal), tasks.get(0).equalityDeletes());
        assertEquals(List.of(), tasks.get(1).positionDeletes());
        assertEquals(List.of(eqGlobal), tasks.get(1).equalityDeletes());
        Manifests.ContentFile vector = dv("dv", "a.parquet", 3);
        Manifests.ContentFile oldVector = dv("dv-old", "b.parquet", 2);
        tasks = SnapshotScan.match(List.of(a, b), List.of(same, vector, oldVector), specs);
        assertSame(vector, tasks.get(0).deletionVector());
        assertEquals(List.of(), tasks.get(0).positionDeletes());
        assertNull(tasks.get(1).deletionVector());
        assertTrue(!tasks.get(1).hasDeletes());
        Manifests.ContentFile bounded = withPathBounds(file(1, "pos-bounded", 1, "a", 3, null), "m", "z");
        Manifests.ContentFile covering = withPathBounds(file(1, "pos-covering", 1, "a", 3, null), "a", "a.q");
        tasks = SnapshotScan.match(List.of(a), List.of(bounded, covering), specs);
        assertEquals(List.of(covering), tasks.get(0).positionDeletes());
    }

    private static Map<String, SnapshotScan.FileTask> byName(String name) throws IOException {
        TableLocation location = TableLocation.resolve(stores, tables.location(name).toString());
        TableMetadata metadata = TableMetadata.parse(location.metadataFile().location(),
                TableLocation.bytes(location.metadataFile()));
        Map<String, SnapshotScan.FileTask> tasks = new TreeMap<>();
        for (SnapshotScan.FileTask t : SnapshotScan.plan(metadata, location, metadata.current(), READ, m -> true,
                f -> true)) {
            tasks.put(t.data().path(), t);
        }
        return tasks;
    }

    private static Map<String, Set<String>> files(List<SnapshotScan.FileTask> tasks) {
        Map<String, Set<String>> files = new TreeMap<>();
        for (SnapshotScan.FileTask t : tasks) {
            Set<String> deletes = files.computeIfAbsent(t.data().path(), f -> new TreeSet<>());
            if (t.deletionVector() != null) {
                deletes.add(t.deletionVector().path());
            }
            t.positionDeletes().forEach(d -> deletes.add(d.path()));
            t.equalityDeletes().forEach(d -> deletes.add(d.path()));
        }
        return files;
    }

    private static Manifests.ContentFile file(int content, String path, int specId, String cat, long sequence,
            String referenced) {
        return new Manifests.ContentFile(content, path, path, "PARQUET", specId,
                specId == 0 ? new Object[0] : new Object[] {cat}, 1, 1, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), content == 2 ? List.of(1) : List.of(), referenced, null, null, sequence, 1);
    }

    private static Manifests.ContentFile dv(String path, String referenced, long sequence) {
        return new Manifests.ContentFile(1, path, path, "PUFFIN", 0, new Object[0], 1, 1, Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), List.of(), referenced, 4L, 40L, sequence, 1);
    }

    private static Manifests.ContentFile withPathBounds(Manifests.ContentFile f, String lower, String upper) {
        return new Manifests.ContentFile(f.content(), f.path(), f.location(), f.format(), f.specId(), f.partition(),
                f.recordCount(), f.fileSize(), f.valueCounts(), f.nullValueCounts(), f.nanValueCounts(),
                Map.of(SnapshotScan.DELETE_FILE_PATH, lower.getBytes(StandardCharsets.UTF_8)),
                Map.of(SnapshotScan.DELETE_FILE_PATH, upper.getBytes(StandardCharsets.UTF_8)), f.equalityIds(),
                f.referencedDataFile(), f.contentOffset(), f.contentSize(), f.dataSequenceNumber(), f.snapshotId());
    }
}
