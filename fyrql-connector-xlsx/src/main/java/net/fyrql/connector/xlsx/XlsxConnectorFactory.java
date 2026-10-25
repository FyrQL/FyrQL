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
package net.fyrql.connector.xlsx;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.FileStores;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates Excel sources of the type {@code xlsx} from options:
 * {@code directory}, whose {@code .xlsx} and {@code .xlsm} workbooks become
 * tables of their first worksheets, and {@code tables.<name>} or
 * {@code tables.<name>.file} for single workbooks, both resolved against the
 * directory of the configuration, or a file in a store of the engine such
 * as {@code s3://bucket/key}, with {@code tables.<name>.sheet} and
 * {@code tables.<name>.range} for the sheet and the cells; and
 * {@code header}, {@code stopAtEmpty}, {@code allVarchar},
 * {@code ignoreErrors}, {@code emptyAsVarchar}, {@code normalizeNames} and
 * {@code batchSize}, which apply to every table. A source without files
 * serves only the table function {@code read_xlsx}.
 * <p>
 * Its type and its options are keys of the configuration file, which the
 * compatibility promise covers; the class itself is internal.
 * <p>
 * Internal; not covered by the compatibility promise.
 */
public final class XlsxConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "xlsx";
    }

    @Override
    public List<String> fileFormats() {
        return XlsxConnector.FILE_FORMATS;
    }

    @Override
    public XlsxConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the Excel source " + name, options, directory);
        XlsxConnector.Builder b = XlsxConnector.builder(name);
        o.bool("header").ifPresent(b::header);
        o.bool("stopAtEmpty").ifPresent(b::stopAtEmpty);
        o.bool("allVarchar").ifPresent(b::allVarchar);
        o.bool("ignoreErrors").ifPresent(b::ignoreErrors);
        o.bool("emptyAsVarchar").ifPresent(b::emptyAsVarchar);
        o.bool("normalizeNames").ifPresent(b::normalizeNames);
        o.integer("batchSize").ifPresent(b::batchSize);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("file", "sheet", "range");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> {
            String file = attributes.get("file");
            String location = FileStores.isLocal(file) ? o.resolve(file).toString() : file;
            b.file(table, location, attributes.get("sheet"), attributes.get("range"));
        });
        return b.build();
    }
}
