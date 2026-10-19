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

package net.fyrql.catalog;

import net.fyrql.connector.Connector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the names of tables against the registered sources:
 * <ul>
 * <li>three parts are {@code source.schema.table};</li>
 * <li>two parts are {@code source.table} in the schema {@code main} of the
 * source if that table exists, otherwise {@code schema.table} in the default
 * source, or in every source if there is no default;</li>
 * <li>one part is a table in the default schema of the default source if it
 * exists there, otherwise in any schema of any source.</li>
 * </ul>
 * A search over several places must find exactly one table; more is an
 * error that names the candidates. Each part matches its name exactly, or
 * else ignoring case if that is unique, since SQL names are
 * case-insensitive as in DuckDB while sources may tell case apart.
 */
public final class NameResolver {

    private final Map<String, Connector> sources;
    private final SearchPath searchPath;

    /** A resolver over sources by name, in the order of their registration. */
    public NameResolver(Map<String, Connector> sources, SearchPath searchPath) {
        this.sources = new LinkedHashMap<>(sources);
        this.searchPath = searchPath;
        if (searchPath.defaultSource() != null && !this.sources.containsKey(searchPath.defaultSource())) {
            throw new IllegalArgumentException("The default source " + searchPath.defaultSource()
                    + " is not registered; registered: " + this.sources.keySet());
        }
    }

    /** Returns the search path. */
    public SearchPath searchPath() {
        return searchPath;
    }

    /**
     * Resolves a name of one to three parts.
     *
     * @throws SqlException if there is no such table or the name is ambiguous
     */
    public TableRef resolve(List<String> name) {
        String text = String.join(".", name);
        List<TableRef> found = switch (name.size()) {
            case 3 -> {
                List<TableRef> result = new ArrayList<>();
                for (String source : match(sources.keySet(), name.get(0))) {
                    result.addAll(tables(source, name.get(1), name.get(2)));
                }
                yield result;
            }
            case 2 -> {
                List<TableRef> asSourceTable = new ArrayList<>();
                for (String source : match(sources.keySet(), name.get(0))) {
                    asSourceTable.addAll(tables(source, TableRef.MAIN, name.get(1)));
                }
                if (!asSourceTable.isEmpty()) {
                    yield asSourceTable;
                }
                List<TableRef> asSchemaTable = new ArrayList<>();
                for (String source : searchPath.defaultSource() != null ? List.of(searchPath.defaultSource())
                        : sources.keySet()) {
                    asSchemaTable.addAll(tables(source, name.get(0), name.get(1)));
                }
                yield asSchemaTable;
            }
            case 1 -> {
                if (searchPath.defaultSource() != null) {
                    List<TableRef> inDefault = tables(searchPath.defaultSource(), searchPath.defaultSchema(),
                            name.get(0));
                    if (!inDefault.isEmpty()) {
                        yield inDefault;
                    }
                }
                List<TableRef> anywhere = new ArrayList<>();
                for (String source : sources.keySet()) {
                    for (String schema : sources.get(source).catalog().schemas()) {
                        anywhere.addAll(tables(source, schema, name.get(0)));
                    }
                }
                yield anywhere;
            }
            default -> throw new SqlException("A table name has one to three parts: " + text, Position.UNKNOWN);
        };
        if (found.isEmpty()) {
            throw new SqlException("Table with name " + text + " does not exist", Position.UNKNOWN);
        }
        if (found.size() > 1) {
            throw new SqlException("Table name " + text + " is ambiguous: " + found.stream().map(TableRef::toString)
                    .collect(Collectors.joining(", ")), Position.UNKNOWN);
        }
        return found.get(0);
    }

    /** Returns the tables of a name in a schema, given by names that need not match exactly. */
    private List<TableRef> tables(String source, String schemaName, String tableName) {
        var catalog = sources.get(source).catalog();
        List<TableRef> result = new ArrayList<>();
        for (String schema : match(catalog.schemas(), schemaName)) {
            for (String table : match(catalog.tables(schema), tableName)) {
                result.add(new TableRef(source, schema, table));
            }
        }
        return result;
    }

    /**
     * Returns the name that matches exactly, or else all that match ignoring
     * case; the caller reports more than one as ambiguous.
     */
    private static List<String> match(Iterable<String> names, String wanted) {
        List<String> ignoringCase = new ArrayList<>();
        for (String n : names) {
            if (n.equals(wanted)) {
                return List.of(n);
            }
            if (n.equalsIgnoreCase(wanted)) {
                ignoringCase.add(n);
            }
        }
        return ignoringCase;
    }
}
