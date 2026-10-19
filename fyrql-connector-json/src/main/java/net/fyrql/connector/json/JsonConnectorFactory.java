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
package net.fyrql.connector.json;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.core.function.DateFormats;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Creates JSON sources of the type {@code json} from options:
 * {@code directory}, whose {@code .json}, {@code .jsonl} and
 * {@code .ndjson} files become tables, and {@code tables.<name>} or
 * {@code tables.<name>.file} for single files, both resolved against the
 * directory of the configuration; and the options of {@link JsonOptions},
 * which apply to every file: {@code format} ({@code auto}, {@code array} or
 * {@code newline_delimited}), {@code sampleSize}, {@code mapInferenceThreshold},
 * {@code fieldAppearanceThreshold}, {@code ignoreErrors}, {@code dateFormat}
 * and {@code timestampFormat}; and {@code batchSize} and {@code splitSize}
 * (a number of bytes, such as {@code 4MiB}). A source without files serves
 * only the table functions.
 */
public final class JsonConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "json";
    }

    @Override
    public JsonConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the JSON source " + name, options, directory);
        JsonConnector.Builder b = JsonConnector.builder(name);
        o.get("format", null, JsonFormat::of).ifPresent(b::format);
        o.integer("sampleSize").ifPresent(b::sampleSize);
        o.integer("mapInferenceThreshold").ifPresent(b::mapInferenceThreshold);
        o.number("fieldAppearanceThreshold").ifPresent(b::fieldAppearanceThreshold);
        o.bool("ignoreErrors").ifPresent(b::ignoreErrors);
        o.get("dateFormat", null, JsonConnectorFactory::format).ifPresent(b::dateFormat);
        o.get("timestampFormat", null, JsonConnectorFactory::format).ifPresent(b::timestampFormat);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bytes("splitSize").ifPresent(b::splitSize);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("file");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> b.file(table, o.resolve(attributes.get("file"))));
        return b.build();
    }

    private static String format(String format) {
        DateFormats.check(format);
        return format;
    }
}
