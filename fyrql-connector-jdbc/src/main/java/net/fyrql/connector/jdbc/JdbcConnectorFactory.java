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
 * {@code recordStatements}, {@code splitRows} and
 * {@code splitColumn.<table>} (see {@link JdbcConnector.Builder#splitRows}
 * and {@link JdbcConnector.Builder#splitColumn}), and
 * {@code properties.<key>} for any property of the driver. A relative path in the URL is resolved by the driver, not
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
        o.get("splitRows", "a number of rows", v -> Long.parseLong(v.strip())).ifPresent(b::splitRows);
        o.prefixed("splitColumn", "<table>").forEach(b::splitColumn);
        o.prefixed("properties", "<key>").forEach(b::property);
        o.rejectUnknown();
        return b.build();
    }
}
