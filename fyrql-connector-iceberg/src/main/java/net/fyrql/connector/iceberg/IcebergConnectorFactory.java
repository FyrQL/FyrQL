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

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates Iceberg sources of the type {@code iceberg} from options:
 * {@code tables.<name>} or {@code tables.<name>.path} for the directory of a
 * table or a metadata file, resolved against the directory of the
 * configuration or in a store of the engine such as
 * {@code s3://bucket/warehouse/db/orders}, with at most one of
 * {@code tables.<name>.snapshot_from_id}, {@code .snapshot_from_timestamp}
 * and {@code .ref} to fix the snapshot; {@code directory}, whose
 * subdirectories with a directory {@code metadata} become tables named
 * after them; {@code batchSize}; and {@code readAhead}, the bytes by which
 * a file in such a store is read ahead for small reads, such as
 * {@code 1MB}, the default.
 * <p>
 * The engine creates a source of this type without options when the
 * factory is on the class path, for the table functions.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class IcebergConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "iceberg";
    }

    /** Returns {@code true}: a source without tables serves {@code iceberg_scan} and the other table functions. */
    @Override
    public boolean implicit() {
        return true;
    }

    @Override
    public IcebergConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the Iceberg source " + name, options, directory);
        IcebergConnector.Builder b = IcebergConnector.builder(name).directory(o.directory());
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bytes("readAhead").ifPresent(bytes -> b.readAhead((int) Math.min(bytes, Integer.MAX_VALUE)));
        Optional<Path> warehouse = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("path", "snapshot_from_id", "snapshot_from_timestamp",
                "ref");
        o.rejectUnknown();
        warehouse.ifPresent(d -> {
            for (Path table : tables(d)) {
                b.table(table.getFileName().toString(), table.toString());
            }
        });
        tables.forEach((table, attributes) -> {
            String path = attributes.get("path");
            String id = attributes.get("snapshot_from_id");
            String time = attributes.get("snapshot_from_timestamp");
            String ref = attributes.get("ref");
            if ((id != null ? 1 : 0) + (time != null ? 1 : 0) + (ref != null ? 1 : 0) > 1) {
                throw new IllegalArgumentException("The table " + table + " of the Iceberg source " + name
                        + " has more than one of snapshot_from_id, snapshot_from_timestamp and ref");
            }
            try {
                if (id != null) {
                    b.table(table, path, Long.parseLong(id.trim()));
                } else if (time != null) {
                    b.tableAsOf(table, path, Instant.ofEpochMilli(IcebergConnector.millis(time)));
                } else if (ref != null) {
                    b.tableAtRef(table, path, ref);
                } else {
                    b.table(table, path);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("The table " + table + " of the Iceberg source " + name
                        + " has an invalid snapshot_from_id: " + id, e);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The table " + table + " of the Iceberg source " + name + ": "
                        + e.getMessage(), e);
            }
        });
        return b.build();
    }

    /** Returns the subdirectories of a directory that hold a directory {@code metadata}, sorted. */
    private static List<Path> tables(Path directory) {
        List<Path> tables = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path p : entries) {
                if (Files.isDirectory(p.resolve("metadata"))) {
                    tables.add(p.toAbsolutePath().normalize());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
        tables.sort(null);
        return tables;
    }
}
