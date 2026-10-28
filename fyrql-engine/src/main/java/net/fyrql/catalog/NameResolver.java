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
import net.fyrql.sql.Position;

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
 * <p>
 * With a {@link DatabasePath}, names resolve as in PostgreSQL instead,
 * as it describes; names of three parts still reach every source.
 * <p>
 * The {@link Views} count as tables of their schemas; a view hides a table
 * of the same name.
 * <p>
 * A session may have a source of temporary tables, whose schema
 * {@code main} comes first for a name of one part, as in DuckDB: a
 * temporary table hides a table of its name elsewhere.
 */
final class NameResolver {

    private final Map<String, Connector> sources;
    private final SearchPath searchPath;
    private final DatabasePath database;
    private final Views views;
    /** The source of the temporary tables of a session, or {@code null}. */
    private final String temporary;

    /** A resolver over sources by name, in the order of their registration. */
    public NameResolver(Map<String, Connector> sources, SearchPath searchPath) {
        this(sources, searchPath, null, new Views());
    }

    /** A resolver along the path of a database, see {@link #NameResolver(Map, SearchPath, DatabasePath, Views)}. */
    public NameResolver(Map<String, Connector> sources, SearchPath searchPath, DatabasePath database) {
        this(sources, searchPath, database, new Views());
    }

    /**
     * A resolver over sources by name, in the order of their registration.
     *
     * @param database the path of the database along which names resolve as
     *        in PostgreSQL, or {@code null} for the rules of FyrQL
     * @param views the views, which count as tables
     * @throws IllegalArgumentException if the default source, the database
     *         or a catalog is not registered
     */
    public NameResolver(Map<String, Connector> sources, SearchPath searchPath, DatabasePath database,
            Views views) {
        this(sources, searchPath, database, views, null);
    }

    /**
     * A resolver as {@link #NameResolver(Map, SearchPath, DatabasePath, Views)},
     * with the source of the temporary tables of a session.
     *
     * @param temporary the name of that source among the sources, or {@code null}
     */
    NameResolver(Map<String, Connector> sources, SearchPath searchPath, DatabasePath database, Views views,
            String temporary) {
        this.temporary = temporary;
        this.sources = new LinkedHashMap<>(sources);
        this.searchPath = searchPath;
        this.database = database;
        this.views = views;
        if (searchPath.defaultSource() != null && !this.sources.containsKey(searchPath.defaultSource())) {
            throw new IllegalArgumentException("The default source " + searchPath.defaultSource()
                    + " is not registered; registered: " + this.sources.keySet());
        }
        if (database != null) {
            for (String source : concat(List.of(database.database()), database.catalogs())) {
                if (!this.sources.containsKey(source)) {
                    throw new IllegalArgumentException("The source " + source + " of the database path is not"
                            + " registered; registered: " + this.sources.keySet());
                }
            }
        }
    }

    /** Returns the search path. */
    public SearchPath searchPath() {
        return searchPath;
    }

    /** Returns the path of the database, or {@code null} for the rules of FyrQL. */
    public DatabasePath databasePath() {
        return database;
    }

    /**
     * Returns the schema of the database that a new table of one part goes
     * to along the path of the database: the first schema of the path that
     * the database has and that is no catalog.
     *
     * @return the schema, or empty if there is none or no path of a database
     * @throws SqlException if a schema of the path matches several ignoring case
     */
    public Optional<String> creationSchema() {
        if (database == null) {
            return Optional.empty();
        }
        var catalog = sources.get(database.database()).catalog();
        for (String schema : database.schemas()) {
            if (match(database.catalogs(), schema).isEmpty()) {
                List<String> found = match(catalog.schemas(), schema);
                if (!found.isEmpty()) {
                    return Optional.of(one(found, "Schema", schema));
                }
            }
        }
        return Optional.empty();
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
        if (temporary != null && name.size() == 1) {
            List<TableRef> temp = tables(temporary, TableRef.MAIN, name.get(0));
            if (!temp.isEmpty()) {
                return Optional.of(temp.get(0));
            }
        }
        List<TableRef> found;
        if (database == null || name.isEmpty() || name.size() > 3) {
            found = candidates(name);
        } else if (name.size() < 3) {
            found = inDatabase(name);
        } else if (isDatabase(name.get(0)) && !match(database.catalogs(), name.get(1)).isEmpty()) {
            found = inSchema(name.get(1), name.get(2));
        } else {
            found = candidates(name);
        }
        if (found.size() > 1) {
            throw new SqlException("Table name " + text + " is ambiguous: " + found.stream().map(TableRef::toString)
                    .collect(Collectors.joining(", ")), Position.UNKNOWN);
        }
        return found.stream().findFirst();
    }

    /** Returns the tables of a name of one or two parts along the path of the database. */
    private List<TableRef> inDatabase(List<String> name) {
        if (name.size() == 2) {
            return inSchema(name.get(0), name.get(1));
        }
        List<String> order = new ArrayList<>();
        if (!database.catalogs().isEmpty() && match(database.schemas(), database.catalogs().get(0)).isEmpty()) {
            order.add(database.catalogs().get(0));
        }
        order.addAll(database.schemas());
        for (String schema : order) {
            List<TableRef> found = inSchema(schema, name.get(0));
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    /** Returns the tables of a name in the catalog of a schema name, or else in that schema of the database. */
    private List<TableRef> inSchema(String schema, String table) {
        List<String> catalogs = match(database.catalogs(), schema);
        if (catalogs.isEmpty()) {
            return tables(database.database(), schema, table);
        }
        List<TableRef> result = new ArrayList<>();
        for (String c : catalogs) {
            for (String s : sources.get(c).catalog().schemas()) {
                result.addAll(tables(c, s, table));
            }
        }
        return result;
    }

    /** Tells whether a name, matched as the parts of names are, is the database. */
    private boolean isDatabase(String name) {
        return !match(List.of(database.database()), name).isEmpty();
    }

    /** Returns the tables of a name along the rules of FyrQL, see the class. */
    private List<TableRef> candidates(List<String> name) {
        String text = String.join(".", name);
        return switch (name.size()) {
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
     * Along the path of a database, one part is a table in its
     * {@linkplain #creationSchema() schema for new tables}, and two parts
     * are {@code catalog.table} or else {@code schema.table} in the
     * database. A table of the name that exists there, matched as by
     * {@link #resolve}, comes with its own name, so that the caller finds
     * that it exists.
     *
     * @throws SqlException if the name names no source or schema, or more than one
     */
    public TableRef resolveNew(List<String> name) {
        String text = String.join(".", name);
        String source;
        String schema;
        // Negative for one or two parts along the path of a database, whose three parts are those of FyrQL.
        int parts = database != null && name.size() < 3 ? -name.size() : name.size();
        switch (parts) {
            case -1 -> {
                String db = database.database();
                source = db;
                schema = creationSchema().orElseThrow(() -> new SqlException("No schema has been selected to create"
                        + " in; set the search_path to a schema of " + db, Position.UNKNOWN));
            }
            case -2 -> {
                List<String> catalogs = match(database.catalogs(), name.get(0));
                if (catalogs.isEmpty()) {
                    source = database.database();
                    schema = one(match(sources.get(source).catalog().schemas(), name.get(0)), "Schema",
                            name.get(0));
                } else {
                    source = one(catalogs, "Schema", name.get(0));
                    schema = one(sources.get(source).catalog().schemas(), "Schema", name.get(0));
                }
            }
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
        List<String> existing = match(tableNames(source, schema), table);
        return new TableRef(source, schema, existing.size() == 1 ? existing.get(0) : table);
    }

    /**
     * Returns where a new temporary table goes: the schema {@code main} of
     * the source of temporary tables, also named as {@code temp.name} or
     * {@code temp.main.name}.
     *
     * @throws SqlException if the session has no such source, or the name names another source or schema
     */
    public TableRef resolveTemporary(List<String> name) {
        if (temporary == null) {
            throw new SqlException("Not supported: temporary tables, since a source of the engine is named temp",
                    Position.UNKNOWN);
        }
        boolean fits = switch (name.size()) {
            case 1 -> true;
            case 2 -> name.get(0).equalsIgnoreCase(temporary);
            case 3 -> name.get(0).equalsIgnoreCase(temporary) && name.get(1).equalsIgnoreCase(TableRef.MAIN);
            default -> false;
        };
        if (!fits) {
            throw new SqlException("A temporary table goes to " + temporary + "." + TableRef.MAIN + ", not to "
                    + String.join(".", name.subList(0, Math.max(0, name.size() - 1))), Position.UNKNOWN);
        }
        String table = name.get(name.size() - 1);
        List<String> existing = match(tableNames(temporary, TableRef.MAIN), table);
        return new TableRef(temporary, TableRef.MAIN, existing.size() == 1 ? existing.get(0) : table);
    }

    /** Tells whether a table is in the source of temporary tables. */
    public boolean temporary(TableRef table) {
        return temporary != null && table.source().equals(temporary);
    }

    /**
     * Returns where a new schema goes: two parts are {@code source.schema},
     * one part is a schema of the default source, or of the database along
     * the path of a database.
     *
     * @return the source and the schema
     * @throws SqlException if the name names no source, or there is no default source
     */
    public List<String> resolveNewSchema(List<String> name) {
        String source;
        if (name.size() == 2) {
            source = one(match(sources.keySet(), name.get(0)), "Source", name.get(0));
        } else if (database != null) {
            source = database.database();
        } else {
            source = searchPath.defaultSource();
            if (source == null) {
                throw new SqlException("There is no default source for the schema " + name.get(0)
                        + "; name its source, as in source." + name.get(0), Position.UNKNOWN);
            }
        }
        return List.of(source, name.get(name.size() - 1));
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

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** Returns the tables of a name in a schema, given by names that need not match exactly. */
    private List<TableRef> tables(String source, String schemaName, String tableName) {
        var catalog = sources.get(source).catalog();
        List<TableRef> result = new ArrayList<>();
        for (String schema : match(catalog.schemas(), schemaName)) {
            for (String table : match(tableNames(source, schema), tableName)) {
                result.add(new TableRef(source, schema, table));
            }
        }
        return result;
    }

    /** Returns the names of the views of a schema, then those of its tables that no view hides. */
    private List<String> tableNames(String source, String schema) {
        List<String> tables = sources.get(source).catalog().tables(schema);
        List<String> names = views.names(source, schema);
        if (names.isEmpty()) {
            return tables;
        }
        List<String> all = new ArrayList<>(names);
        for (String t : tables) {
            if (!names.contains(t)) {
                all.add(t);
            }
        }
        return all;
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
