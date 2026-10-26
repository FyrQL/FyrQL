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
package net.fyrql.connector.csv;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.files.HivePartitions;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Creates CSV sources of the type {@code csv} from options:
 * {@code directory}, whose {@code .csv} and {@code .tsv} files and
 * subdirectories with such files become tables, and {@code tables.<name>}
 * or {@code tables.<name>.file} for a file, a directory or a pattern, both
 * resolved against the directory of the configuration, or in a store of the
 * engine such as {@code s3://bucket/key}, with
 * {@code tables.<name>.hiveTypes} for the types of its partitions, such as
 * {@code year INTEGER, region VARCHAR}; and {@code delimiter},
 * {@code quote}, {@code header}, {@code nullString}, {@code charset},
 * {@code hivePartitioning} ({@code true}, {@code false} or {@code auto}, the
 * default), {@code hiveTypesAutocast}, {@code sampleSize},
 * {@code batchSize} and {@code splitSize} (a number of bytes, such as
 * {@code 4MiB}), which apply to every file. A source without files serves
 * only the table functions.
 * <p>
 * Its type and its options are keys of the configuration file, which the
 * compatibility promise covers; the class itself is internal.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class CsvConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "csv";
    }

    @Override
    public List<String> fileFormats() {
        return CsvConnector.FILE_FORMATS;
    }

    @Override
    public CsvConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the CSV source " + name, options, directory);
        CsvConnector.Builder b = CsvConnector.builder(name);
        o.character("delimiter").ifPresent(b::delimiter);
        o.character("quote").ifPresent(b::quote);
        o.bool("header").ifPresent(b::header);
        o.string("nullString").ifPresent(b::nullString);
        o.get("charset", "a charset such as UTF-8", Charset::forName).ifPresent(b::charset);
        o.integer("sampleSize").ifPresent(b::sampleSize);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bytes("splitSize").ifPresent(b::splitSize);
        o.get("hivePartitioning", "true, false or auto", CsvConnectorFactory::partitioning)
                .ifPresent(p -> b.hivePartitioning(p.equals("auto") ? null : Boolean.valueOf(p)));
        o.bool("hiveTypesAutocast").ifPresent(b::hiveTypesAutocast);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("file", "hiveTypes");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> {
            String file = attributes.get("file");
            b.file(table, FileStores.isLocal(file) ? CsvFiles.absolute(file, o.directory()) : file);
            String types = attributes.get("hiveTypes");
            if (types != null) {
                try {
                    b.hiveTypes(table, HivePartitions.types(types));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("The table " + table + " of the CSV source " + name
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
