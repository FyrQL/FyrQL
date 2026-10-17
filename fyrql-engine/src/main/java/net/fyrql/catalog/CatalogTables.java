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

import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.Schema;

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
 * source, in the order its catalog gives.
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

    /** A table, with its number of rows if the source knows it cheaply. */
    public record TableInfo(TableRef table, OptionalLong rowCount) {
    }

    /** A column of a table, with its position from 1. */
    public record ColumnInfo(TableRef table, int ordinal, Schema.Field field) {
    }

    private final Map<String, Connector> sources;

    /** The lists of sources by name, in the order of their registration. */
    public CatalogTables(Map<String, Connector> sources) {
        this.sources = new LinkedHashMap<>(sources);
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

    /** Returns the tables whose source, schema and name match the patterns. */
    public List<TableInfo> tables(NamePattern catalog, NamePattern schema, NamePattern table) {
        List<TableInfo> result = new ArrayList<>();
        for (SchemaInfo s : schemas(catalog, schema)) {
            Catalog c = sources.get(s.source()).catalog();
            for (String t : table.select(c.tables(s.schema()))) {
                TableRef ref = new TableRef(s.source(), s.schema(), t);
                result.add(new TableInfo(ref, c.rowCount(ref)));
            }
        }
        return result;
    }

    /** Returns the columns whose table matches the first three patterns and whose name matches the last. */
    public List<ColumnInfo> columns(NamePattern catalog, NamePattern schema, NamePattern table,
            NamePattern column) {
        List<ColumnInfo> result = new ArrayList<>();
        for (SchemaInfo s : schemas(catalog, schema)) {
            Catalog c = sources.get(s.source()).catalog();
            for (String t : table.select(c.tables(s.schema()))) {
                TableRef ref = new TableRef(s.source(), s.schema(), t);
                List<Schema.Field> fields = c.schema(ref).fields();
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
}
