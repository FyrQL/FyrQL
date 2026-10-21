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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The files of a snapshot: its data files, each with the delete files that
 * apply to it by the rules of the specification.
 * <ul>
 * <li>A deletion vector applies to the data file it names; then the
 * position delete files of that data file are left out.</li>
 * <li>A position delete file applies to a data file of the same partition
 * spec and partition whose data sequence number is not greater than its
 * own, unless it names another data file or the bounds of its paths leave
 * the data file out.</li>
 * <li>An equality delete file applies to a data file whose data sequence
 * number is less than its own, of the same spec and partition or with any
 * partition if its own spec is unpartitioned.</li>
 * </ul>
 */
final class SnapshotScan {

    /** The field id of the paths in position delete files. */
    static final int DELETE_FILE_PATH = 2147483546;

    /**
     * A data file and the delete files that apply to it.
     *
     * @param data the data file
     * @param deletionVector the deletion vector of the file, or {@code null}
     * @param positionDeletes the position delete files, none if there is a deletion vector
     * @param equalityDeletes the equality delete files
     */
    record FileTask(Manifests.ContentFile data, Manifests.ContentFile deletionVector,
            List<Manifests.ContentFile> positionDeletes, List<Manifests.ContentFile> equalityDeletes) {

        /** Tells whether rows of the file may be deleted. */
        boolean hasDeletes() {
            return deletionVector != null || !positionDeletes.isEmpty() || !equalityDeletes.isEmpty();
        }

        /** Tells whether rows of the file are deleted by their positions. */
        boolean hasPositionDeletes() {
            return deletionVector != null || !positionDeletes.isEmpty();
        }
    }

    private SnapshotScan() {
    }

    /**
     * Returns the files of a snapshot.
     *
     * @param metadata the metadata of the table
     * @param location where the table is now
     * @param snapshot the snapshot
     * @param read reads an Avro file at a location
     * @param manifests which manifests to read, by the entries of the manifest list
     * @param files which data files to keep
     * @throws IllegalArgumentException if a manifest cannot be read or names
     *         a partition spec the metadata lacks
     */
    static List<FileTask> plan(TableMetadata metadata, TableLocation location, TableMetadata.Snapshot snapshot,
            Function<String, AvroFile> read, Predicate<Manifests.ManifestFile> manifests,
            Predicate<Manifests.ContentFile> files) {
        List<Manifests.ContentFile> data = new ArrayList<>();
        List<Manifests.ContentFile> deletes = new ArrayList<>();
        for (Manifests.ManifestFile m : manifests(metadata, location, snapshot, read)) {
            if (!manifests.test(m)) {
                continue;
            }
            PartitionSpec spec = metadata.specs().get(m.specId());
            if (spec == null) {
                throw new IllegalArgumentException("The manifest " + m.path() + " has the partition spec "
                        + m.specId() + ", which the metadata of " + metadata.location() + " lacks");
            }
            AvroFile file = read.apply(location.relocate(m.path(), metadata.location()));
            for (Manifests.ContentFile f : Manifests.files(file, m, spec, metadata.schemas().values(),
                    p -> location.relocate(p, metadata.location()))) {
                if (f.content() == Manifests.DATA) {
                    if (files.test(f)) {
                        data.add(f);
                    }
                } else {
                    deletes.add(f);
                }
            }
        }
        return match(data, deletes, metadata.specs());
    }

    /** Returns the entries of the manifest list of a snapshot, or of its manifests in format version 1. */
    static List<Manifests.ManifestFile> manifests(TableMetadata metadata, TableLocation location,
            TableMetadata.Snapshot snapshot, Function<String, AvroFile> read) {
        if (snapshot.manifestList() != null) {
            return Manifests.list(read.apply(location.relocate(snapshot.manifestList(), metadata.location())));
        }
        List<Manifests.ManifestFile> list = new ArrayList<>();
        for (String path : snapshot.manifests()) {
            AvroFile file = read.apply(location.relocate(path, metadata.location()));
            String spec = file.metadata().getOrDefault("partition-spec-id", "0");
            list.add(new Manifests.ManifestFile(path, Integer.parseInt(spec), Manifests.DATA, 0, snapshot.id(),
                    List.of()));
        }
        return list;
    }

    /** Returns each data file with the delete files that apply to it. */
    static List<FileTask> match(List<Manifests.ContentFile> data, List<Manifests.ContentFile> deletes,
            Map<Integer, PartitionSpec> specs) {
        Map<String, Manifests.ContentFile> vectors = new HashMap<>();
        Map<Partition, List<Manifests.ContentFile>> positions = new HashMap<>();
        Map<Partition, List<Manifests.ContentFile>> equalities = new HashMap<>();
        List<Manifests.ContentFile> global = new ArrayList<>();
        for (Manifests.ContentFile d : deletes) {
            if (d.isDeletionVector()) {
                if (d.referencedDataFile() == null) {
                    throw new IllegalArgumentException("The deletion vector " + d.path() + " names no data file");
                }
                Manifests.ContentFile other = vectors.put(d.referencedDataFile(), d);
                if (other != null && other.dataSequenceNumber() > d.dataSequenceNumber()) {
                    vectors.put(d.referencedDataFile(), other);
                }
            } else if (d.content() == Manifests.POSITION_DELETES) {
                positions.computeIfAbsent(new Partition(d), p -> new ArrayList<>()).add(d);
            } else {
                PartitionSpec spec = specs.get(d.specId());
                if (spec == null || spec.unpartitioned()) {
                    global.add(d);
                } else {
                    equalities.computeIfAbsent(new Partition(d), p -> new ArrayList<>()).add(d);
                }
            }
        }
        List<FileTask> tasks = new ArrayList<>();
        for (Manifests.ContentFile f : data) {
            long seq = f.dataSequenceNumber();
            Manifests.ContentFile vector = vectors.get(f.path());
            if (vector != null && vector.dataSequenceNumber() < seq) {
                vector = null;
            }
            List<Manifests.ContentFile> pos = new ArrayList<>();
            if (vector == null) {
                for (Manifests.ContentFile d : positions.getOrDefault(new Partition(f), List.of())) {
                    if (d.dataSequenceNumber() >= seq && mayHold(d, f.path())) {
                        pos.add(d);
                    }
                }
            }
            List<Manifests.ContentFile> eq = new ArrayList<>();
            for (Manifests.ContentFile d : equalities.getOrDefault(new Partition(f), List.of())) {
                if (d.dataSequenceNumber() > seq) {
                    eq.add(d);
                }
            }
            for (Manifests.ContentFile d : global) {
                if (d.dataSequenceNumber() > seq) {
                    eq.add(d);
                }
            }
            tasks.add(new FileTask(f, vector, List.copyOf(pos), List.copyOf(eq)));
        }
        return tasks;
    }

    /** Tells whether a position delete file can hold deletes of a data file, by the file it names or its bounds. */
    private static boolean mayHold(Manifests.ContentFile deletes, String dataFile) {
        if (deletes.referencedDataFile() != null) {
            return deletes.referencedDataFile().equals(dataFile);
        }
        // Bounds of strings compare as UTF-8 bytes; a truncated lower bound is less, a truncated upper one greater.
        byte[] path = dataFile.getBytes(StandardCharsets.UTF_8);
        byte[] lower = deletes.lowerBounds().get(DELETE_FILE_PATH);
        byte[] upper = deletes.upperBounds().get(DELETE_FILE_PATH);
        return (lower == null || Arrays.compareUnsigned(lower, path) <= 0)
                && (upper == null || Arrays.compareUnsigned(upper, path) >= 0);
    }

    /** The partition spec and values of a file, compared by value. */
    private record Partition(int specId, Object[] values) {

        Partition(Manifests.ContentFile f) {
            this(f.specId(), f.partition());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Partition p && specId == p.specId && Arrays.deepEquals(values, p.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(specId, Arrays.deepHashCode(values));
        }

        @Override
        public String toString() {
            return specId + Arrays.deepToString(values);
        }
    }
}
