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
package net.fyrql.connector.http;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.json.JsonFormat;

import java.nio.file.Path;
import java.util.Map;

/**
 * Creates HTTP sources of the type {@code http} from options:
 * {@code tables.<name>} or {@code tables.<name>.url} for the endpoint of a
 * table, {@code tables.<name>.pointer} for where in its answer the records
 * are, {@code headers.<name>} for a header of every request,
 * {@code timeout} (such as {@code 10s}), and {@code format},
 * {@code sampleSize}, {@code ignoreErrors} and {@code batchSize} as for
 * JSON files.
 */
public final class HttpConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "http";
    }

    @Override
    public HttpConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the HTTP source " + name, options, directory);
        HttpConnector.Builder b = HttpConnector.builder(name);
        o.duration("timeout").ifPresent(b::timeout);
        o.get("format", null, JsonFormat::of).ifPresent(b::format);
        o.integer("sampleSize").ifPresent(b::sampleSize);
        o.bool("ignoreErrors").ifPresent(b::ignoreErrors);
        o.integer("batchSize").ifPresent(b::batchSize);
        Map<String, String> headers = o.prefixed("headers", "<name>");
        Map<String, Map<String, String>> tables = o.tables("url", "pointer");
        o.rejectUnknown();
        b.headers(headers);
        tables.forEach((table, attributes) -> {
            b.table(table, attributes.get("url"));
            String pointer = attributes.get("pointer");
            if (pointer != null) {
                b.pointer(table, pointer);
            }
        });
        return b.build();
    }
}
