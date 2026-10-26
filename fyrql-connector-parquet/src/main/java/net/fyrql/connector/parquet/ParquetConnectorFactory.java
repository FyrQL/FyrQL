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
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.files.HivePartitions;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Creates Parquet sources of the type {@code parquet} from options:
 * {@code directory}, whose {@code .parquet} files and subdirectories with
 * {@code .parquet} files at any depth become tables, and
 * {@code tables.<name>} or {@code tables.<name>.path} for a file, a
 * directory or a pattern with {@code *}, {@code ?} and {@code **}, all
 * resolved against the directory of the configuration, or in a store of
 * the engine such as {@code s3://bucket/orders/}, with
 * {@code tables.<name>.hiveTypes} for the types of its partitions, such as
 * {@code year INTEGER, region VARCHAR}; {@code hivePartitioning},
 * {@code true}, {@code false} or {@code auto}, the default, and
 * {@code hiveTypesAutocast}, see {@link ParquetConnector.Builder#hivePartitioning};
 * {@code batchSize}; and {@code readAhead}, the bytes by which a file in
 * such a store is read ahead for small reads, such as {@code 1MB}, the
 * default. A source without tables serves only the table functions.
 * <p>
 * Its type and its options are keys of the configuration file, which the
 * compatibility promise covers; the class itself is internal.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class ParquetConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "parquet";
    }

    @Override
    public List<String> fileFormats() {
        return ParquetConnector.FILE_FORMATS;
    }

    @Override
    public ParquetConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the Parquet source " + name, options, directory);
        ParquetConnector.Builder b = ParquetConnector.builder(name);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bytes("readAhead").ifPresent(bytes -> b.readAhead((int) Math.min(bytes, Integer.MAX_VALUE)));
        o.get("hivePartitioning", "true, false or auto", ParquetConnectorFactory::partitioning)
                .ifPresent(p -> b.hivePartitioning(p.equals("auto") ? null : Boolean.valueOf(p)));
        o.bool("hiveTypesAutocast").ifPresent(b::hiveTypesAutocast);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("path", "hiveTypes");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> {
            b.path(table, ParquetFiles.absolute(attributes.get("path"), o.directory()));
            String types = attributes.get("hiveTypes");
            if (types != null) {
                try {
                    b.hiveTypes(table, HivePartitions.types(types));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("The table " + table + " of the Parquet source " + name
                            + " has invalid hiveTypes: " + e.getMessage(), e);
                }
            }
        });
        return b.build();
    }

    /** Returns {@code true}, {@code false} or {@code auto}, in any case. */
    static String partitioning(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!v.equals("true") && !v.equals("false") && !v.equals("auto")) {
            throw new IllegalArgumentException(value);
        }
        return v;
    }
}
