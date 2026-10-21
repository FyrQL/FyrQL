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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata file of an Iceberg table, format versions 1 to 3: its
 * location, schemas, partition specs, snapshots and references.
 *
 * @param formatVersion the format version
 * @param location the location of the table the files were written for
 * @param schemas the schemas by id
 * @param currentSchemaId the id of the current schema
 * @param specs the partition specs by id
 * @param snapshots the snapshots, in the order of the file
 * @param currentSnapshotId the id of the current snapshot, or {@code null} for a table without one
 * @param refs the branches and tags by name
 * @param log the history of the current snapshot, oldest first
 * @param properties the properties of the table
 */
record TableMetadata(int formatVersion, String location, Map<Integer, IcebergSchema> schemas, int currentSchemaId,
        Map<Integer, PartitionSpec> specs, List<Snapshot> snapshots, Long currentSnapshotId, Map<String, Ref> refs,
        List<LogEntry> log, Map<String, String> properties) {

    /**
     * A snapshot.
     *
     * @param id the snapshot id
     * @param parentId the id of the parent snapshot, or {@code null}
     * @param sequenceNumber the sequence number, 0 in format version 1
     * @param timestampMillis when the snapshot was made, in milliseconds since 1970
     * @param operation the operation of the summary, or {@code null}
     * @param manifestList the location of the manifest list, or {@code null}
     * @param manifests the locations of the manifests of a snapshot of format version 1 without a list
     * @param schemaId the id of the schema of the snapshot, or {@code null}
     * @param summary the summary, such as {@code total-records}
     */
    record Snapshot(long id, Long parentId, long sequenceNumber, long timestampMillis, String operation,
            String manifestList, List<String> manifests, Integer schemaId, Map<String, String> summary) {

        /**
         * Returns the rows of the snapshot if its summary says that no delete
         * file applies to them, or nothing.
         */
        java.util.OptionalLong rowsWithoutDeletes() {
            String rows = summary.get("total-records");
            boolean noDeletes = "0".equals(summary.get("total-delete-files"))
                    || "0".equals(summary.get("total-position-deletes"))
                    && "0".equals(summary.get("total-equality-deletes"));
            try {
                return rows != null && noDeletes ? java.util.OptionalLong.of(Long.parseLong(rows))
                        : java.util.OptionalLong.empty();
            } catch (NumberFormatException e) {
                return java.util.OptionalLong.empty();
            }
        }
    }

    /** A branch or tag. */
    record Ref(String name, long snapshotId, String type) {
    }

    /** An entry of the history of the current snapshot. */
    record LogEntry(long timestampMillis, long snapshotId) {
    }

    /** Returns the current schema. */
    IcebergSchema schema() {
        return schemas.get(currentSchemaId);
    }

    /** Returns the schema of a snapshot: the one it was made with, or the current one if it does not say. */
    IcebergSchema schema(Snapshot snapshot) {
        IcebergSchema s = snapshot.schemaId() == null ? null : schemas.get(snapshot.schemaId());
        return s == null ? schema() : s;
    }

    /** Returns the current snapshot, or {@code null} for a table without snapshots. */
    Snapshot current() {
        return currentSnapshotId == null ? null : snapshot(currentSnapshotId);
    }

    /**
     * Returns the snapshot with an id.
     *
     * @throws IllegalArgumentException if there is none
     */
    Snapshot snapshot(long id) {
        for (Snapshot s : snapshots) {
            if (s.id() == id) {
                return s;
            }
        }
        throw new IllegalArgumentException("The Iceberg table " + location + " has no snapshot " + id);
    }

    /**
     * Returns the snapshot that was current at a time: the last one of the
     * history at or before it, or of the snapshots if there is no history.
     *
     * @throws IllegalArgumentException if no snapshot is that old
     */
    Snapshot asOf(long timestampMillis) {
        List<LogEntry> history = log;
        if (history.isEmpty()) {
            history = new ArrayList<>();
            for (Snapshot s : snapshots) {
                history.add(new LogEntry(s.timestampMillis(), s.id()));
            }
            history.sort(java.util.Comparator.comparingLong(LogEntry::timestampMillis));
        }
        Long id = null;
        for (LogEntry e : history) {
            if (e.timestampMillis() <= timestampMillis) {
                id = e.snapshotId();
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("The Iceberg table " + location + " has no snapshot at or before "
                    + java.time.Instant.ofEpochMilli(timestampMillis));
        }
        return snapshot(id);
    }

    /**
     * Returns the snapshot of a branch or tag.
     *
     * @throws IllegalArgumentException if there is none of the name
     */
    Snapshot ref(String name) {
        Ref ref = refs.get(name);
        if (ref == null) {
            throw new IllegalArgumentException("The Iceberg table " + location + " has no branch or tag " + name
                    + "; it has " + String.join(", ", refs.keySet()));
        }
        return snapshot(ref.snapshotId());
    }

    /**
     * Parses a metadata file.
     *
     * @param file the location of the file, for messages
     * @throws IllegalArgumentException naming the file if it is not the
     *         metadata of an Iceberg table this reader supports
     */
    static TableMetadata parse(String file, byte[] json) {
        try {
            return parse(IcebergSchema.map(Json.parse(json), "metadata"));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read the Iceberg metadata " + file + ": "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }
    }

    private static TableMetadata parse(Map<?, ?> m) {
        int version = IcebergSchema.integer(m.get("format-version"), "format-version");
        if (version < 1 || version > 3) {
            throw new IllegalArgumentException("Unsupported format version " + version);
        }
        String location = IcebergSchema.string(m.get("location"), "location");
        Map<Integer, IcebergSchema> schemas = new LinkedHashMap<>();
        int currentSchemaId;
        if (m.get("schemas") instanceof List<?> list) {
            for (Object s : list) {
                IcebergSchema schema = IcebergSchema.parse(IcebergSchema.map(s, "schema"));
                schemas.put(schema.id(), schema);
            }
            currentSchemaId = IcebergSchema.integer(m.get("current-schema-id"), "current-schema-id");
        } else {
            IcebergSchema schema = IcebergSchema.parse(IcebergSchema.map(m.get("schema"), "schema"));
            schemas.put(schema.id(), schema);
            currentSchemaId = schema.id();
        }
        if (!schemas.containsKey(currentSchemaId)) {
            throw new IllegalArgumentException("No schema " + currentSchemaId);
        }
        Map<Integer, PartitionSpec> specs = new LinkedHashMap<>();
        if (m.get("partition-specs") instanceof List<?> list) {
            for (Object s : list) {
                PartitionSpec spec = PartitionSpec.parse(IcebergSchema.map(s, "partition spec"));
                specs.put(spec.id(), spec);
            }
        } else if (m.get("partition-spec") instanceof List<?> fields) {
            specs.put(0, PartitionSpec.parse(0, fields));
        } else {
            specs.put(0, PartitionSpec.UNPARTITIONED);
        }
        List<Snapshot> snapshots = new ArrayList<>();
        if (m.get("snapshots") instanceof List<?> list) {
            for (Object s : list) {
                snapshots.add(snapshot(IcebergSchema.map(s, "snapshot")));
            }
        }
        Long current = m.get("current-snapshot-id") instanceof Long l && l != -1 ? l : null;
        Map<String, Ref> refs = new LinkedHashMap<>();
        if (m.get("refs") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Map<?, ?> r = IcebergSchema.map(e.getValue(), "ref");
                String name = (String) e.getKey();
                refs.put(name, new Ref(name, longValue(r.get("snapshot-id"), "snapshot-id of " + name),
                        (String) r.get("type")));
            }
        }
        if (!refs.containsKey("main") && current != null) {
            refs.put("main", new Ref("main", current, "branch"));
        }
        List<LogEntry> log = new ArrayList<>();
        if (m.get("snapshot-log") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> e = IcebergSchema.map(o, "snapshot-log");
                log.add(new LogEntry(longValue(e.get("timestamp-ms"), "timestamp-ms"),
                        longValue(e.get("snapshot-id"), "snapshot-id")));
            }
        }
        Map<String, String> properties = new LinkedHashMap<>();
        if (m.get("properties") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
        }
        return new TableMetadata(version, location, schemas, currentSchemaId, specs, snapshots, current, refs, log,
                properties);
    }

    private static Snapshot snapshot(Map<?, ?> s) {
        long id = longValue(s.get("snapshot-id"), "snapshot-id");
        List<String> manifests = new ArrayList<>();
        if (s.get("manifests") instanceof List<?> list) {
            for (Object o : list) {
                manifests.add(IcebergSchema.string(o, "manifest of the snapshot " + id));
            }
        }
        String list = s.get("manifest-list") instanceof String l ? l : null;
        if (list == null && manifests.isEmpty() && !s.containsKey("manifests")) {
            throw new IllegalArgumentException("The snapshot " + id + " has neither manifest-list nor manifests");
        }
        Map<String, String> summary = new LinkedHashMap<>();
        if (s.get("summary") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> summary.put(String.valueOf(k), String.valueOf(v)));
        }
        String operation = summary.get("operation");
        return new Snapshot(id, s.get("parent-snapshot-id") instanceof Long p ? p : null,
                s.get("sequence-number") instanceof Long n ? n : 0, longValue(s.get("timestamp-ms"), "timestamp-ms"),
                operation, list, List.copyOf(manifests),
                s.get("schema-id") instanceof Long schema ? Integer.valueOf(schema.intValue()) : null,
                Map.copyOf(summary));
    }

    private static long longValue(Object o, String what) {
        if (o instanceof Long l) {
            return l;
        }
        throw new IllegalArgumentException("Invalid " + what + ": " + o);
    }
}
