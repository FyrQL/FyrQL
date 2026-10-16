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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Creates Parquet sources of the type {@code parquet} from options:
 * {@code directory}, whose {@code .parquet} files and subdirectories with
 * {@code .parquet} files become tables, and {@code tables.<name>} or
 * {@code tables.<name>.path} for a file, a directory or a pattern with
 * {@code *} and {@code ?} in the name of the file, all resolved against the
 * directory of the configuration; and {@code batchSize}. A source without
 * tables serves only the table functions.
 */
public final class ParquetConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "parquet";
    }

    @Override
    public ParquetConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the Parquet source " + name, options, directory);
        ParquetConnector.Builder b = ParquetConnector.builder(name);
        o.integer("batchSize").ifPresent(b::batchSize);
        Optional<Path> files = o.path("directory");
        Map<String, Map<String, String>> tables = o.tables("path");
        o.rejectUnknown();
        files.ifPresent(b::directory);
        tables.forEach((table, attributes) -> b.path(table,
                ParquetFiles.absolute(attributes.get("path"), o.directory())));
        return b.build();
    }
}
