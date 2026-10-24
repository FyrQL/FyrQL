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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates Parquet sources of the type {@code parquet} from options:
 * {@code directory}, whose {@code .parquet} files and subdirectories with
 * {@code .parquet} files become tables, and {@code tables.<name>} or
 * {@code tables.<name>.path} for a file, a directory or a pattern with
 * {@code *} and {@code ?} in the name of the file, all resolved against the
 * directory of the configuration, or in a store of the engine such as
 * {@code s3://bucket/orders/}; {@code batchSize}; and {@code readAhead},
 * the bytes by which a file in such a store is read ahead for small reads,
 * such as {@code 1MB}, the default. A source without tables serves only the
 * table functions.
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
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("path");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> b.path(table,
                ParquetFiles.absolute(attributes.get("path"), o.directory())));
        return b.build();
    }
}
