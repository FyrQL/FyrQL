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

import net.fyrql.sql.render.GenericDialect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates JDBC sources of the type {@code jdbc} from options:
 * {@code url} (required), {@code user}, {@code password}, {@code dialect}
 * ({@code h2}, {@code sqlite}, {@code postgresql}, {@code mysql} or
 * {@code generic}; by default from the URL or the database, and
 * {@code generic} for a database without a dialect of its own), for the
 * generic dialect {@code pushdown} ({@code filters}, the default, or
 * {@code none}), {@code quote} ({@code "}, {@code `}, {@code [} or
 * {@code none}), {@code limit} ({@code maxRows}, the default,
 * {@code fetchFirst}, {@code limit} or {@code top}) and {@code trust} (a
 * comma-separated list of {@code decimal}, {@code double},
 * {@code timestamp} and {@code boolean}), {@code schemas} (a
 * comma-separated list),
 * {@code defaultSchema}, {@code fetchSize}, {@code batchSize},
 * {@code batchRows}, {@code recordStatements}, {@code splitRows} and
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
        o.get("pushdown", "filters or none", JdbcConnectorFactory::pushdown).ifPresent(b::pushdownFilters);
        o.get("quote", "\", `, [ or none", v -> v.strip().equalsIgnoreCase("none") ? "" : v.strip())
                .ifPresent(b::quote);
        o.get("limit", "maxRows, fetchFirst, limit or top", JdbcConnectorFactory::limit).ifPresent(b::limitStyle);
        o.get("trust", "a list of decimal, double, timestamp and boolean", JdbcConnectorFactory::trust)
                .ifPresent(b::trust);
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
        o.integer("batchRows").ifPresent(b::batchRows);
        o.bool("recordStatements").ifPresent(b::recordStatements);
        o.get("splitRows", "a number of rows", v -> Long.parseLong(v.strip())).ifPresent(b::splitRows);
        o.prefixed("splitColumn", "<table>").forEach(b::splitColumn);
        o.prefixed("properties", "<key>").forEach(b::property);
        o.rejectUnknown();
        return b.build();
    }

    private static boolean pushdown(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "filters" -> true;
            case "none" -> false;
            default -> throw new IllegalArgumentException(value);
        };
    }

    private static GenericDialect.LimitStyle limit(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "maxrows" -> GenericDialect.LimitStyle.MAX_ROWS;
            case "fetchfirst" -> GenericDialect.LimitStyle.FETCH_FIRST;
            case "limit" -> GenericDialect.LimitStyle.LIMIT;
            case "top" -> GenericDialect.LimitStyle.TOP;
            default -> throw new IllegalArgumentException(value);
        };
    }

    private static Set<GenericDialect.Trust> trust(String value) {
        Set<GenericDialect.Trust> types = EnumSet.noneOf(GenericDialect.Trust.class);
        for (String t : value.split(",")) {
            if (!t.isBlank()) {
                types.add(GenericDialect.Trust.valueOf(t.strip().toUpperCase(Locale.ROOT)));
            }
        }
        return types;
    }
}
