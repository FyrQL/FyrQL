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
import java.util.Optional;
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
 * <p>
 * The name of a new table, see {@link #resolveNew}, is resolved to a
 * source and a schema in the same way, without the table.
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
        return find(name).orElseThrow(() -> new SqlException("Table with name " + String.join(".", name)
                + " does not exist", Position.UNKNOWN));
    }

    /**
     * Resolves a name of one to three parts of a table that may not exist.
     *
     * @return the table, or empty if there is none of the name
     * @throws SqlException if the name is ambiguous
     */
    public Optional<TableRef> find(List<String> name) {
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
        if (found.size() > 1) {
            throw new SqlException("Table name " + text + " is ambiguous: " + found.stream().map(TableRef::toString)
                    .collect(Collectors.joining(", ")), Position.UNKNOWN);
        }
        return found.stream().findFirst();
    }

    /**
     * Returns where a new table of a name of one to three parts goes:
     * <ul>
     * <li>three parts are {@code source.schema.table};</li>
     * <li>two parts are {@code source.table} in the schema {@code main} of
     * a source of that name, otherwise {@code schema.table} in the default
     * source, or in the one source with such a schema if there is no
     * default;</li>
     * <li>one part is a table in the default schema of the default
     * source.</li>
     * </ul>
     * A table of the name that exists there, matched as by
     * {@link #resolve}, comes with its own name, so that the caller finds
     * that it exists.
     *
     * @throws SqlException if the name names no source or schema, or more than one
     */
    public TableRef resolveNew(List<String> name) {
        String text = String.join(".", name);
        String source;
        String schema;
        switch (name.size()) {
            case 3 -> {
                source = one(match(sources.keySet(), name.get(0)), "Source", name.get(0));
                schema = one(match(sources.get(source).catalog().schemas(), name.get(1)), "Schema",
                        source + "." + name.get(1));
            }
            case 2 -> {
                List<String> named = match(sources.keySet(), name.get(0));
                if (named.size() == 1 && sources.get(named.get(0)).catalog().schemas().contains(TableRef.MAIN)) {
                    source = named.get(0);
                    schema = TableRef.MAIN;
                } else {
                    List<String> places = new ArrayList<>();
                    for (String s : searchPath.defaultSource() != null ? List.of(searchPath.defaultSource())
                            : sources.keySet()) {
                        for (String sc : match(sources.get(s).catalog().schemas(), name.get(0))) {
                            places.add(s + "." + sc);
                        }
                    }
                    if (places.isEmpty() && named.size() == 1) {
                        throw new SqlException("Source " + named.get(0) + " has no schema " + TableRef.MAIN
                                + "; name the schema of the table, as in " + named.get(0) + ".schema."
                                + name.get(1), Position.UNKNOWN);
                    }
                    String place = one(places, "Source or schema", name.get(0));
                    int dot = place.indexOf('.');
                    source = place.substring(0, dot);
                    schema = place.substring(dot + 1);
                }
            }
            case 1 -> {
                source = searchPath.defaultSource();
                if (source == null) {
                    throw new SqlException("There is no default source for the table " + text
                            + "; name its source, as in source." + text, Position.UNKNOWN);
                }
                schema = one(match(sources.get(source).catalog().schemas(), searchPath.defaultSchema()), "Schema",
                        source + "." + searchPath.defaultSchema());
            }
            default -> throw new SqlException("A table name has one to three parts: " + text, Position.UNKNOWN);
        }
        String table = name.get(name.size() - 1);
        List<String> existing = match(sources.get(source).catalog().tables(schema), table);
        return new TableRef(source, schema, existing.size() == 1 ? existing.get(0) : table);
    }

    /** Returns the one name found, or fails with the kind of thing that was looked for. */
    private static String one(List<String> found, String kind, String name) {
        if (found.isEmpty()) {
            throw new SqlException(kind + " with name " + name + " does not exist", Position.UNKNOWN);
        }
        if (found.size() > 1) {
            throw new SqlException(kind + " name " + name + " is ambiguous: " + String.join(", ", found),
                    Position.UNKNOWN);
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
