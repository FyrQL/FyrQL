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
 * <p>
 * Internal; not covered by the compatibility promise.
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
