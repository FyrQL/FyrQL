/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.fyrql.connector.csv;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Creates CSV sources of the type {@code csv} from options:
 * {@code directory}, whose {@code .csv} and {@code .tsv} files become
 * tables, and {@code tables.<name>} or {@code tables.<name>.file} for single
 * files, both resolved against the directory of the configuration; and
 * {@code delimiter}, {@code quote}, {@code header}, {@code nullString},
 * {@code charset}, {@code sampleRows} and {@code batchSize}, which apply to
 * every file. A source without files serves only the table functions.
 */
public final class CsvConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "csv";
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
        o.integer("sampleRows").ifPresent(b::sampleRows);
        o.integer("batchSize").ifPresent(b::batchSize);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("file");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> b.file(table, o.resolve(attributes.get("file"))));
        return b.build();
    }
}
