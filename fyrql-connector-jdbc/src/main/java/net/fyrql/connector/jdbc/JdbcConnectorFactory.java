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

package net.fyrql.connector.jdbc;

import net.fyrql.connector.ConfigOptions;
import net.fyrql.connector.ConnectorFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates JDBC sources of the type {@code jdbc} from options:
 * {@code url} (required), {@code user}, {@code password}, {@code dialect}
 * ({@code h2}, {@code sqlite}, {@code postgresql} or {@code mysql}; by
 * default from the URL), {@code schemas} (a comma-separated list),
 * {@code defaultSchema}, {@code fetchSize}, {@code batchSize},
 * {@code recordStatements}, and {@code properties.<key>} for any property
 * of the driver. A relative path in the URL is resolved by the driver, not
 * against the directory of the configuration.
 */
public final class JdbcConnectorFactory implements ConnectorFactory {

    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public JdbcConnector create(String name, Map<String, String> options, Path directory) {
        ConfigOptions o = new ConfigOptions("the JDBC source " + name, options, directory);
        JdbcConnector.Builder b = JdbcConnector.builder(name).url(o.required("url"));
        o.string("user").ifPresent(b::user);
        o.string("password").ifPresent(b::password);
        o.get("dialect", null, JdbcDialect::ofName).ifPresent(b::dialect);
        o.string("schemas").ifPresent(v -> {
            List<String> schemas = new ArrayList<>();
            for (String s : v.split(",")) {
                if (!s.isBlank()) {
                    schemas.add(s.strip());
                }
            }
            b.schemas(schemas);
        });
        o.string("defaultSchema").ifPresent(b::defaultSchema);
        o.integer("fetchSize").ifPresent(b::fetchSize);
        o.integer("batchSize").ifPresent(b::batchSize);
        o.bool("recordStatements").ifPresent(b::recordStatements);
        o.prefixed("properties", "<key>").forEach(b::property);
        o.rejectUnknown();
        return b.build();
    }
}
