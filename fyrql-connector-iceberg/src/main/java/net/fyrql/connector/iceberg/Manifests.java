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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The manifest lists and manifests of Iceberg, read with the schemas of
 * their files: the entries of a manifest list, and the live data and
 * delete files of a manifest with what they inherit from it.
 */
final class Manifests {

    /** The content of a data file and of a manifest of data files. */
    static final int DATA = 0;
    /** The content of a file of position deletes, and of a manifest of delete files. */
    static final int POSITION_DELETES = 1;
    /** The content of a file of equality deletes. */
    static final int EQUALITY_DELETES = 2;

    private static final int DELETED = 2;
    private static final int ADDED = 1;

    private Manifests() {
    }

    /**
     * An entry of a manifest list.
     *
     * @param path the location of the manifest as the list gives it
     * @param specId the id of the partition spec of its files
     * @param content {@link #DATA} or {@link #POSITION_DELETES} for deletes
     * @param sequenceNumber the sequence number of the snapshot that added it
     * @param addedSnapshotId the id of the snapshot that added it
     * @param partitions the summaries of the partition fields, or none
     */
    record ManifestFile(String path, int specId, int content, long sequenceNumber, long addedSnapshotId,
            List<FieldSummary> partitions) {
    }

    /** The summary of a partition field over the files of a manifest; the bounds may be {@code null}. */
    record FieldSummary(boolean containsNull, Boolean containsNan, byte[] lower, byte[] upper) {
    }

    /**
     * A live data or delete file of a manifest.
     *
     * @param content {@link #DATA}, {@link #POSITION_DELETES} or {@link #EQUALITY_DELETES}
     * @param path the path as the manifest gives it
     * @param location where the file is now, see {@link TableLocation#relocate}
     * @param format the file format in upper case, such as {@code PARQUET} or {@code PUFFIN}
     * @param specId the id of the partition spec
     * @param partition the partition values in the order of the spec, see {@link SingleValues}
     * @param recordCount the rows of the file, or the deletes of a delete file
     * @param fileSize the size of the file in bytes
     * @param valueCounts the values of each field id, nulls included
     * @param nullValueCounts the nulls of each field id
     * @param nanValueCounts the {@code NaN}s of each field id
     * @param lowerBounds the least value of each field id, see {@link SingleValues#bound}
     * @param upperBounds the greatest value of each field id
     * @param equalityIds the field ids an equality delete file matches rows by, or none
     * @param referencedDataFile the data file the deletes are for, as the manifest gives it, or {@code null}
     * @param contentOffset where the blob of a deletion vector starts in its file, or {@code null}
     * @param contentSize the bytes of the blob of a deletion vector, or {@code null}
     * @param dataSequenceNumber the data sequence number, inherited from the manifest if added by it
     * @param snapshotId the id of the snapshot that added the file
     */
    record ContentFile(int content, String path, String location, String format, int specId, Object[] partition,
            long recordCount, long fileSize, Map<Integer, Long> valueCounts, Map<Integer, Long> nullValueCounts,
            Map<Integer, Long> nanValueCounts, Map<Integer, byte[]> lowerBounds, Map<Integer, byte[]> upperBounds,
            List<Integer> equalityIds, String referencedDataFile, Long contentOffset, Long contentSize,
            long dataSequenceNumber, long snapshotId) {

        /** Tells whether the file is a deletion vector. */
        boolean isDeletionVector() {
            return content == POSITION_DELETES && format.equals("PUFFIN");
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /**
     * Returns the entries of a manifest list.
     *
     * @throws IllegalArgumentException if an entry lacks a field the specification requires
     */
    static List<ManifestFile> list(AvroFile file) {
        List<ManifestFile> manifests = new ArrayList<>();
        for (Object o : file.records()) {
            Map<?, ?> r = (Map<?, ?>) o;
            List<FieldSummary> summaries = new ArrayList<>();
            if (r.get("partitions") instanceof List<?> list) {
                for (Object s : list) {
                    Map<?, ?> f = (Map<?, ?>) s;
                    summaries.add(new FieldSummary(Boolean.TRUE.equals(f.get("contains_null")),
                            (Boolean) f.get("contains_nan"), (byte[]) f.get("lower_bound"),
                            (byte[]) f.get("upper_bound")));
                }
            }
            manifests.add(new ManifestFile((String) required(r, "manifest_path"),
                    ((Number) required(r, "partition_spec_id")).intValue(),
                    r.get("content") instanceof Integer c ? c : DATA,
                    r.get("sequence_number") instanceof Long n ? n : 0,
                    r.get("added_snapshot_id") instanceof Long id ? id : -1, summaries));
        }
        return manifests;
    }

    /**
     * Returns the live files of a manifest, those not deleted, with the
     * snapshot id and the data sequence number inherited from the manifest
     * where the entry leaves them out.
     *
     * @param manifest the entry of the manifest list, or for a snapshot
     *        without one an entry made from the header of the file
     * @param spec the partition spec of the files
     * @param schemas the schemas of the table, for the types of the partition values
     * @param relocate where the paths of files are now
     */
    static List<ContentFile> files(AvroFile file, ManifestFile manifest, PartitionSpec spec,
            Collection<IcebergSchema> schemas, UnaryOperator<String> relocate) {
        List<IcebergSchema.Primitive> types = new ArrayList<>();
        for (PartitionSpec.Field f : spec.fields()) {
            IcebergSchema.Primitive source = source(schemas, f.sourceId());
            types.add(source == null ? null : PartitionSpec.resultType(f.transform(), source));
        }
        List<ContentFile> files = new ArrayList<>();
        for (Object o : file.records()) {
            Map<?, ?> entry = (Map<?, ?>) o;
            int status = ((Number) required(entry, "status")).intValue();
            if (status == DELETED) {
                continue;
            }
            Map<?, ?> f = (Map<?, ?>) required(entry, "data_file");
            long snapshotId = entry.get("snapshot_id") instanceof Long id ? id : manifest.addedSnapshotId();
            long sequence = entry.get("sequence_number") instanceof Long n ? n
                    : status == ADDED ? manifest.sequenceNumber() : 0;
            Object[] partition = new Object[spec.fields().size()];
            Map<?, ?> values = f.get("partition") instanceof Map<?, ?> m ? m : Map.of();
            for (int i = 0; i < partition.length; i++) {
                Object v = values.get(spec.fields().get(i).name());
                partition[i] = types.get(i) == null ? v : SingleValues.partition(types.get(i), v);
            }
            String path = (String) required(f, "file_path");
            String referenced = (String) f.get("referenced_data_file");
            files.add(new ContentFile(f.get("content") instanceof Integer c ? c : DATA, path, relocate.apply(path),
                    String.valueOf(required(f, "file_format")).toUpperCase(java.util.Locale.ROOT), spec.id(),
                    partition, ((Number) required(f, "record_count")).longValue(),
                    ((Number) required(f, "file_size_in_bytes")).longValue(), longs(f.get("value_counts")),
                    longs(f.get("null_value_counts")), longs(f.get("nan_value_counts")), bytes(f.get("lower_bounds")),
                    bytes(f.get("upper_bounds")), ints(f.get("equality_ids")), referenced,
                    f.get("content_offset") instanceof Long off ? off : null,
                    f.get("content_size_in_bytes") instanceof Long size ? size : null, sequence, snapshotId));
        }
        return files;
    }

    /** Returns the primitive type of a column by its field id in the latest schema that has it, or {@code null}. */
    private static IcebergSchema.Primitive source(Collection<IcebergSchema> schemas, int fieldId) {
        IcebergSchema.Primitive found = null;
        for (IcebergSchema s : schemas) {
            IcebergSchema.Column c = s.column(fieldId);
            if (c != null) {
                found = c.primitive();
            }
        }
        return found;
    }

    private static Object required(Map<?, ?> record, String field) {
        Object v = record.get(field);
        if (v == null) {
            throw new IllegalArgumentException("A manifest entry without " + field);
        }
        return v;
    }

    /** Reads an Iceberg map from field ids, an array of records {@code key} and {@code value}. */
    private static <T> Map<Integer, T> map(Object o, Class<T> type) {
        if (!(o instanceof List<?> list)) {
            return Map.of();
        }
        Map<Integer, T> map = new LinkedHashMap<>();
        for (Object e : list) {
            Map<?, ?> kv = (Map<?, ?>) e;
            map.put((Integer) kv.get("key"), type.cast(kv.get("value")));
        }
        return map;
    }

    private static Map<Integer, Long> longs(Object o) {
        return map(o, Long.class);
    }

    private static Map<Integer, byte[]> bytes(Object o) {
        return map(o, byte[].class);
    }

    private static List<Integer> ints(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> ints = new ArrayList<>();
        for (Object e : list) {
            ints.add((Integer) e);
        }
        return ints;
    }
}
