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

import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.bind.ViewDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The sources, schemas, tables and columns of an engine as lists, for the
 * metadata of tools: the JDBC driver builds its result sets from them.
 * Each list is in the order of the sources' registration and, within a
 * schema, has the views in the order of their creation, then the tables
 * in the order the catalog of the source gives; a view hides a table of
 * the same name.
 * <p>
 * The catalog of a source is read only if the source matches the pattern
 * of the call, and the columns of a table only if the table matches; the
 * names of the sources need none. An error of a source goes to the caller
 * as it is.
 */
public final class CatalogTables {

    /** A schema of a source. */
    public record SchemaInfo(String source, String schema) {
    }

    /**
     * A table or a view, with its number of rows if the source knows it
     * cheaply.
     *
     * @param view whether it is a view
     */
    public record TableInfo(TableRef table, OptionalLong rowCount, boolean view) {

        /** A table. */
        public TableInfo(TableRef table, OptionalLong rowCount) {
            this(table, rowCount, false);
        }
    }

    /** A column of a table or a view, with its position from 1. */
    public record ColumnInfo(TableRef table, int ordinal, Schema.Field field) {
    }

    /** A view, with the text of its query as written. */
    public record ViewInfo(TableRef view, String definition) {
    }

    private final Map<String, Connector> sources;
    private final Views views;

    /** The lists of sources by name, in the order of their registration. */
    public CatalogTables(Map<String, Connector> sources) {
        this(sources, new Views());
    }

    /**
     * The lists of sources by name, in the order of their registration, with
     * views in them; see {@link EngineCatalog#catalogTables()}.
     */
    CatalogTables(Map<String, Connector> sources, Views views) {
        this.sources = new LinkedHashMap<>(sources);
        this.views = views;
    }

    /** Returns the names of the sources. */
    public List<String> catalogs() {
        return List.copyOf(sources.keySet());
    }

    /** Returns the schemas of the sources a pattern matches whose names match the other. */
    public List<SchemaInfo> schemas(NamePattern catalog, NamePattern schema) {
        List<SchemaInfo> result = new ArrayList<>();
        for (String source : catalog.select(catalogs())) {
            for (String s : schema.select(sources.get(source).catalog().schemas())) {
                result.add(new SchemaInfo(source, s));
            }
        }
        return result;
    }

    /** Returns the tables and views whose source, schema and name match the patterns. */
    public List<TableInfo> tables(NamePattern catalog, NamePattern schema, NamePattern table) {
        List<TableInfo> result = new ArrayList<>();
        for (SchemaInfo s : schemas(catalog, schema)) {
            Catalog c = sources.get(s.source()).catalog();
            List<String> viewNames = views.names(s.source(), s.schema());
            for (String t : table.select(names(c, s, viewNames))) {
                TableRef ref = new TableRef(s.source(), s.schema(), t);
                result.add(viewNames.contains(t) ? new TableInfo(ref, OptionalLong.empty(), true)
                        : new TableInfo(ref, c.rowCount(ref)));
            }
        }
        return result;
    }

    /**
     * Returns the columns whose table or view matches the first three
     * patterns and whose name matches the last; those of a view are its
     * columns when it was created.
     */
    public List<ColumnInfo> columns(NamePattern catalog, NamePattern schema, NamePattern table,
            NamePattern column) {
        List<ColumnInfo> result = new ArrayList<>();
        for (SchemaInfo s : schemas(catalog, schema)) {
            Catalog c = sources.get(s.source()).catalog();
            List<String> viewNames = views.names(s.source(), s.schema());
            for (String t : table.select(names(c, s, viewNames))) {
                TableRef ref = new TableRef(s.source(), s.schema(), t);
                List<Schema.Field> fields;
                if (viewNames.contains(t)) {
                    // A view dropped in the meantime has no columns.
                    fields = views.get(ref).map(v -> v.schema().fields()).orElse(List.of());
                } else {
                    fields = c.schema(ref).fields();
                }
                List<String> names = fields.stream().map(Schema.Field::name).toList();
                Set<String> selected = new HashSet<>(column.select(names));
                for (int i = 0; i < fields.size(); i++) {
                    if (selected.contains(fields.get(i).name())) {
                        result.add(new ColumnInfo(ref, i + 1, fields.get(i)));
                    }
                }
            }
        }
        return result;
    }

    /** Returns the views whose source, schema and name match the patterns. */
    public List<ViewInfo> views(NamePattern catalog, NamePattern schema, NamePattern view) {
        List<ViewInfo> result = new ArrayList<>();
        if (views.isEmpty()) {
            return result;
        }
        for (SchemaInfo s : schemas(catalog, schema)) {
            for (String v : view.select(views.names(s.source(), s.schema()))) {
                TableRef ref = new TableRef(s.source(), s.schema(), v);
                views.get(ref).map(ViewDefinition::text).ifPresent(text -> result.add(new ViewInfo(ref, text)));
            }
        }
        return result;
    }

    /** Returns the names of the views of a schema, then those of its tables that no view hides. */
    private static List<String> names(Catalog catalog, SchemaInfo schema, List<String> viewNames) {
        List<String> tables = catalog.tables(schema.schema());
        if (viewNames.isEmpty()) {
            return tables;
        }
        List<String> all = new ArrayList<>(viewNames);
        for (String t : tables) {
            if (!viewNames.contains(t)) {
                all.add(t);
            }
        }
        return all;
    }
}
