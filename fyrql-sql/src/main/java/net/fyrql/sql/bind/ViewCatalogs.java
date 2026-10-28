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

package net.fyrql.sql.bind;

import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.Position;
import net.fyrql.sql.SqlException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalogs in which the query of a view binds: one that records what
 * its names resolve to when {@code CREATE VIEW} binds it, and one that
 * gives the same answers when a query that reads the view binds it again.
 */
final class ViewCatalogs {

    private ViewCatalogs() {
    }

    /** A catalog that asks another one. */
    private abstract static class Delegating implements CatalogView {
        final CatalogView catalog;

        Delegating(CatalogView catalog) {
            this.catalog = catalog;
        }

        @Override
        public TableRef resolve(List<String> name) {
            return catalog.resolve(name);
        }

        @Override
        public Optional<TableRef> find(List<String> name) {
            return catalog.find(name);
        }

        @Override
        public TableRef resolveNew(List<String> name) {
            return catalog.resolveNew(name);
        }

        @Override
        public TableRef resolveView(List<String> name) {
            return catalog.resolveView(name);
        }

        @Override
        public Optional<ViewDefinition> view(TableRef table) {
            return catalog.view(table);
        }

        @Override
        public boolean exists(TableRef table) {
            return catalog.exists(table);
        }

        @Override
        public void checkFileWrite(String path) {
            catalog.checkFileWrite(path);
        }

        @Override
        public boolean postgresNames() {
            return catalog.postgresNames();
        }

        @Override
        public Schema schema(TableRef table) {
            return catalog.schema(table);
        }

        @Override
        public List<String> primaryKey(TableRef table) {
            return catalog.primaryKey(table);
        }

        @Override
        public List<String> resolveNewSchema(List<String> name) {
            return catalog.resolveNewSchema(name);
        }

        @Override
        public TableRef resolveTemporary(List<String> name) {
            return catalog.resolveTemporary(name);
        }

        @Override
        public boolean temporary(TableRef table) {
            return catalog.temporary(table);
        }

        @Override
        public List<String> path(TableRef table) {
            return catalog.path(table);
        }

        @Override
        public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
            return catalog.tableFunction(name, arguments, namedArguments);
        }

        @Override
        public TableRef fileTable(String format, String path, Schema schema, Map<String, String> options) {
            return catalog.fileTable(format, path, schema, options);
        }
    }

    /** Records the tables that names resolve to, and the paths that qualify their columns. */
    static final class Recording extends Delegating {
        final Map<List<String>, TableRef> names = new LinkedHashMap<>();
        final Map<TableRef, List<String>> paths = new LinkedHashMap<>();

        Recording(CatalogView catalog) {
            super(catalog);
        }

        @Override
        public TableRef resolve(List<String> name) {
            TableRef table = catalog.resolve(name);
            names.put(List.copyOf(name), table);
            return table;
        }

        @Override
        public List<String> path(TableRef table) {
            List<String> path = catalog.path(table);
            paths.put(table, List.copyOf(path));
            return path;
        }
    }

    /** Resolves names to the tables of a view, and asks a catalog of now for everything else. */
    static final class Replay extends Delegating {
        private final ViewDefinition view;

        Replay(ViewDefinition view, CatalogView now) {
            super(now);
            this.view = view;
        }

        /**
         * Returns the table the name resolved to when the view was created.
         *
         * @throws SqlException if that table is gone
         */
        @Override
        public TableRef resolve(List<String> name) {
            TableRef table = view.names().get(name);
            if (table == null) {
                return catalog.resolve(name);
            }
            if (!catalog.exists(table)) {
                throw new SqlException("Table with name " + table + " does not exist", Position.UNKNOWN);
            }
            return table;
        }

        @Override
        public List<String> path(TableRef table) {
            List<String> path = view.paths().get(table);
            return path != null ? path : catalog.path(table);
        }

        @Override
        public boolean postgresNames() {
            return view.postgresNames();
        }
    }
}
