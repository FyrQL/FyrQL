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

import net.fyrql.connector.FileAccessException;
import net.fyrql.connector.FileAccessPolicy;
import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.Globs;
import net.fyrql.connector.LocalFileStore;
import net.fyrql.connector.parquet.ParquetFile;
import net.fyrql.core.type.Schema;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * The files of the Iceberg tables of a connector, read through the stores
 * of the engine and kept while they are the same: metadata files by their
 * location and version, manifest lists, manifests and the footers of data
 * and delete files by their location, since Iceberg never changes them.
 */
final class IcebergFiles {

    /** The most manifests, manifest lists and footers kept. */
    private static final int CACHED = 256;

    private final Map<String, Versioned> metadata = lru(64);
    private final Map<String, AvroFile> avro = lru(CACHED);
    private final Map<String, ParquetFile> parquet = lru(CACHED);
    private final int readAhead;
    private volatile FileStores stores;

    /** A metadata file in a version. */
    private record Versioned(String version, long size, TableMetadata metadata) {
    }

    /**
     * Which snapshot of a table a name refers to: the current one, or one
     * by its id, by a time or by a branch or tag; at most one of them.
     *
     * @param location where the table is, absolute
     * @param snapshotId the id of a snapshot, or {@code null}
     * @param asOf a time in milliseconds since 1970, or {@code null}
     * @param ref the name of a branch or tag, or {@code null}
     */
    record TableSpec(String location, Long snapshotId, Long asOf, String ref) {

        TableSpec {
            int given = (snapshotId != null ? 1 : 0) + (asOf != null ? 1 : 0) + (ref != null ? 1 : 0);
            if (given > 1) {
                throw new IllegalArgumentException("Only one of a snapshot id, a timestamp and a ref selects the"
                        + " snapshot of " + location);
            }
        }
    }

    /**
     * A table in the snapshot a scan reads.
     *
     * @param location where the table is
     * @param metadata its metadata
     * @param snapshot the snapshot, or {@code null} for a table without snapshots
     * @param schema the schema of the snapshot
     */
    record Resolved(TableLocation location, TableMetadata metadata, TableMetadata.Snapshot snapshot,
            IcebergSchema schema) {

        /** Returns the columns. */
        Schema columns() {
            return schema.schema();
        }

        /** Returns the rows if no delete file applies to them, or nothing. */
        OptionalLong rowsWithoutDeletes() {
            return snapshot == null ? OptionalLong.of(0) : snapshot.rowsWithoutDeletes();
        }
    }

    IcebergFiles(FileStores stores, int readAhead) {
        this.stores = stores;
        this.readAhead = readAhead;
    }

    private static <V> Map<String, V> lru(int size) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > size;
            }
        });
    }

    FileStores stores() {
        return stores;
    }

    void stores(FileStores stores) {
        this.stores = stores;
    }

    /**
     * Returns a location as an absolute one, a relative local one resolved
     * against a directory, a location with a scheme as it is. A local
     * location is resolved as {@link LocalFileStore#resolve} resolves it,
     * also a URI of the scheme {@code file}, so that the table read is the
     * one the policy of the engine checked.
     */
    static String absolute(String location, Path directory) {
        if (FileStores.scheme(location).isPresent()) {
            return location;
        }
        try {
            return LocalFileStore.resolve(location, directory).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path " + location, e);
        }
    }

    /**
     * Finds a table and its snapshot.
     *
     * @throws IllegalArgumentException if there is no table at the location,
     *         its metadata cannot be read or it has no such snapshot
     */
    Resolved resolve(TableSpec spec) {
        return resolve(spec, FileAccessPolicy.ALL);
    }

    /**
     * Finds a table and its snapshot, and checks that a policy allows to
     * read its metadata file before it is read, such as one that a link
     * in the directory of the table leads to.
     *
     * @throws IllegalArgumentException if there is no table at the location,
     *         its metadata cannot be read or it has no such snapshot
     * @throws FileAccessException if the policy does not allow to read the metadata file
     */
    Resolved resolve(TableSpec spec, FileAccessPolicy policy) {
        Globs.checkPattern(spec.location());
        TableLocation location = TableLocation.resolve(stores, spec.location());
        policy.checkRead(location.metadataFile().location());
        TableMetadata m = metadata(location.metadataFile());
        TableMetadata.Snapshot snapshot;
        if (spec.snapshotId() != null) {
            snapshot = m.snapshot(spec.snapshotId());
        } else if (spec.asOf() != null) {
            snapshot = m.asOf(spec.asOf());
        } else if (spec.ref() != null) {
            snapshot = m.ref(spec.ref());
        } else {
            snapshot = m.current();
        }
        return new Resolved(location, m, snapshot, snapshot == null ? m.schema() : m.schema(snapshot));
    }

    /** Returns the metadata in a file, read again if the file changed. */
    TableMetadata metadata(FileObject file) {
        Versioned known = metadata.get(file.location());
        if (known != null && known.version().equals(file.version()) && known.size() == file.size()) {
            return known.metadata();
        }
        TableMetadata m;
        try {
            m = TableMetadata.parse(file.location(), TableLocation.bytes(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the Iceberg metadata " + file.location() + ": "
                    + e.getMessage(), e);
        }
        metadata.put(file.location(), new Versioned(file.version(), file.size(), m));
        return m;
    }

    /** Returns a manifest list or a manifest. */
    AvroFile avro(String location) {
        AvroFile file = avro.get(location);
        if (file == null) {
            file = AvroFile.read(object(location));
            avro.put(location, file);
        }
        return file;
    }

    /** Returns the footer of a data or delete file. */
    ParquetFile parquet(String location) {
        ParquetFile file = parquet.get(location);
        if (file == null) {
            file = ParquetFile.open(stores, object(location), readAhead);
            parquet.put(location, file);
        }
        return file;
    }

    /** Returns bytes of a file, such as the blob of a deletion vector. */
    byte[] range(String location, long offset, int length) {
        FileObject file = object(location);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try {
            while (buffer.hasRemaining()) {
                int n = file.read(offset + buffer.position(), buffer);
                if (n < 0) {
                    throw new IOException("The file ends before byte " + (offset + length));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + location + ": " + e.getMessage(), e);
        }
        return buffer.array();
    }

    /** Returns the file at a location. */
    FileObject object(String location) {
        try {
            return stores.object(location);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("The file " + location + " of an Iceberg table does not exist", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + location + ": " + e.getMessage(), e);
        }
    }

    /** Returns the history of a table as rows: sequence number, snapshot id, time and manifest list. */
    static List<Object[]> snapshots(TableMetadata m) {
        return m.snapshots().stream().map(s -> new Object[] {s.sequenceNumber(), s.id(),
                Math.multiplyExact(s.timestampMillis(), 1000L), s.manifestList()}).toList();
    }

    /** Returns a time for messages. */
    static String time(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }
}
