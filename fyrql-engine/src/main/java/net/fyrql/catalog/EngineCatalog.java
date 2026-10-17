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

package net.fyrql.catalog;

import net.fyrql.connector.Connector;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.ast.Position;
import net.fyrql.sql.bind.CatalogView;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalog of an engine for the binder: names through the
 * {@link NameResolver}, and table functions from the connectors that offer
 * them. A function offered by several connectors is taken from the default
 * source if it offers it, otherwise from the first one registered.
 */
public final class EngineCatalog implements CatalogView {

    private final Map<String, Connector> sources;
    private final NameResolver resolver;
    private final Map<String, TableFunctionProvider> functions = new LinkedHashMap<>();

    /** A catalog over sources by name, in the order of their registration. */
    public EngineCatalog(Map<String, Connector> sources, SearchPath searchPath) {
        this.sources = new LinkedHashMap<>(sources);
        this.resolver = new NameResolver(sources, searchPath);
        Connector preferred = searchPath.defaultSource() == null ? null : sources.get(searchPath.defaultSource());
        if (preferred instanceof TableFunctionProvider p) {
            for (String f : p.tableFunctions()) {
                functions.put(f.toLowerCase(Locale.ROOT), p);
            }
        }
        for (Connector c : sources.values()) {
            if (c instanceof TableFunctionProvider p) {
                for (String f : p.tableFunctions()) {
                    functions.putIfAbsent(f.toLowerCase(Locale.ROOT), p);
                }
            }
        }
    }

    /** Returns the search path. */
    public SearchPath searchPath() {
        return resolver.searchPath();
    }

    /**
     * Returns a catalog over the same sources with another search path, or
     * this catalog if the path is its own.
     *
     * @throws IllegalArgumentException if the default source of the path is not registered
     */
    public EngineCatalog withSearchPath(SearchPath searchPath) {
        return searchPath.equals(searchPath()) ? this : new EngineCatalog(sources, searchPath);
    }

    /** Returns the names of the table functions, in lower case. */
    public List<String> tableFunctions() {
        return List.copyOf(functions.keySet());
    }

    @Override
    public TableRef resolve(List<String> name) {
        return resolver.resolve(name);
    }

    @Override
    public Schema schema(TableRef table) {
        Connector c = sources.get(table.source());
        if (c == null) {
            throw new SqlException("No source " + table.source(), Position.UNKNOWN);
        }
        return c.catalog().schema(table);
    }

    @Override
    public TableRef tableFunction(String name, List<Object> arguments, Map<String, Object> namedArguments) {
        String function = name.toLowerCase(Locale.ROOT);
        TableFunctionProvider provider = functions.get(function);
        if (provider == null) {
            throw new SqlException("Table function with name " + name + " does not exist"
                    + (functions.isEmpty() ? "" : "; there are " + String.join(", ", functions.keySet())),
                    Position.UNKNOWN);
        }
        // Names are case-insensitive; a repeated name counts with its last value, as in DuckDB.
        Map<String, Object> named = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : namedArguments.entrySet()) {
            named.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        try {
            return provider.bind(function, arguments, named);
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new SqlException(e.getMessage(), Position.UNKNOWN);
        }
    }
}
