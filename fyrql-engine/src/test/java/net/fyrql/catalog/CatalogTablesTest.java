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

import net.fyrql.FyrQL;
import net.fyrql.catalog.CatalogTables.ColumnInfo;
import net.fyrql.catalog.CatalogTables.SchemaInfo;
import net.fyrql.catalog.CatalogTables.TableInfo;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogTablesTest {

    /**
     * A source with fixed tables whose columns are named after the table,
     * that counts how often its catalog and the columns of a table are
     * read; it cannot be scanned.
     */
    private static final class Stub implements Connector {
        private final String name;
        private final Map<String, List<String>> tables;
        int catalogs;
        int schemas;

        Stub(String name, Map<String, List<String>> tables) {
            this.name = name;
            this.tables = tables;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Catalog catalog() {
            catalogs++;
            return new Catalog() {
                @Override
                public List<String> schemas() {
                    return List.copyOf(tables.keySet());
                }

                @Override
                public List<String> tables(String schema) {
                    return tables.getOrDefault(schema, List.of());
                }

                @Override
                public Schema schema(TableRef table) {
                    schemas++;
                    return Schema.of(new Schema.Field("id", DataType.INTEGER, false),
                            new Schema.Field(table.table() + "_Name", DataType.VARCHAR),
                            new Schema.Field("amount", new DataType.Decimal(12, 2)));
                }

                @Override
                public OptionalLong rowCount(TableRef table) {
                    return table.table().equals("orders") ? OptionalLong.of(1500) : OptionalLong.empty();
                }
            };
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchReader open(Split split) {
            throw new UnsupportedOperationException();
        }
    }

    /** A source whose catalog fails. */
    private static final class Broken implements Connector {
        @Override
        public String name() {
            return "broken";
        }

        @Override
        public Catalog catalog() {
            throw new IllegalStateException("The database of broken is down");
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchReader open(Split split) {
            throw new UnsupportedOperationException();
        }
    }

    private final Stub pg;
    private final Stub mem;
    private final CatalogTables tables;

    CatalogTablesTest() {
        Map<String, List<String>> pgTables = new LinkedHashMap<>();
        pgTables.put("public", List.of("orders", "users"));
        pgTables.put("sales", List.of("Orders", "ORDERS", "t_x", "tax"));
        pg = new Stub("pg", pgTables);
        mem = new Stub("mem", Map.of("main", List.of("orders", "items")));
        Map<String, Connector> sources = new LinkedHashMap<>();
        sources.put("pg", pg);
        sources.put("mem", mem);
        sources.put("broken", new Broken());
        tables = new CatalogTables(sources);
    }

    private static NamePattern p(String pattern) {
        return NamePattern.of(pattern);
    }

    private static List<String> names(List<TableInfo> tables) {
        return tables.stream().map(t -> t.table().toString()).toList();
    }

    @Test
    void theSourcesAreListedWithoutReadingACatalog() {
        assertEquals(List.of("pg", "mem", "broken"), tables.catalogs());
        assertEquals(0, pg.catalogs + mem.catalogs);
    }

    @Test
    void schemasAreListedForTheSourcesThatMatch() {
        assertEquals(List.of(new SchemaInfo("pg", "public"), new SchemaInfo("pg", "sales")),
                tables.schemas(NamePattern.name("PG"), NamePattern.ANY));
        assertEquals(0, mem.catalogs, "a source that does not match is not read");
        assertEquals(List.of(new SchemaInfo("pg", "sales")), tables.schemas(p("p%"), p("S_LES")));
        assertEquals(List.of(), tables.schemas(NamePattern.name(""), NamePattern.ANY),
                "every schema belongs to a source");
        assertEquals(List.of(new SchemaInfo("mem", "main")), tables.schemas(p("_%m"), p("%i%")));
    }

    @Test
    void tablesMatchExactlyFirstAndCarryTheirRowCount() {
        List<TableInfo> all = tables.tables(p("%g"), NamePattern.ANY, NamePattern.ANY);
        assertEquals(List.of("pg.public.orders", "pg.public.users", "pg.sales.Orders", "pg.sales.ORDERS",
                "pg.sales.t_x", "pg.sales.tax"), names(all));
        assertEquals(OptionalLong.of(1500), all.get(0).rowCount());
        assertEquals(OptionalLong.empty(), all.get(1).rowCount());
        assertEquals(List.of("pg.public.orders", "pg.sales.Orders", "pg.sales.ORDERS"),
                names(tables.tables(p("pg"), NamePattern.ANY, p("orders"))),
                "a name matches exactly first within each schema, as names resolve");
        assertEquals(List.of("pg.public.orders", "pg.sales.ORDERS"),
                names(tables.tables(p("pg"), NamePattern.ANY, p("ORDERS"))));
        assertEquals(List.of("pg.sales.Orders", "pg.sales.ORDERS"),
                names(tables.tables(p("pg"), p("sales"), p("oRdErS"))));
        assertEquals(List.of("pg.sales.t_x"), names(tables.tables(p("pg"), p("sales"), p("t\\_x"))));
        assertEquals(List.of("pg.sales.t_x", "pg.sales.tax"), names(tables.tables(p("pg"), p("sales"), p("t_x"))));
        assertEquals(0, pg.schemas, "listing tables reads no columns");
    }

    @Test
    void columnsAreListedWithTheirPosition() {
        List<ColumnInfo> columns = tables.columns(p("mem"), NamePattern.ANY, p("items"), NamePattern.ANY);
        assertEquals(3, columns.size());
        TableRef items = new TableRef("mem", "main", "items");
        assertEquals(new ColumnInfo(items, 1, new Schema.Field("id", DataType.INTEGER, false)), columns.get(0));
        assertEquals(new ColumnInfo(items, 2, new Schema.Field("items_Name", DataType.VARCHAR)), columns.get(1));
        assertEquals(new ColumnInfo(items, 3, new Schema.Field("amount", new DataType.Decimal(12, 2))),
                columns.get(2));
        assertEquals(1, mem.schemas, "only the columns of the matching table are read");
        List<ColumnInfo> named = tables.columns(p("mem"), NamePattern.ANY, NamePattern.ANY, p("%_name"));
        assertEquals(List.of("orders_Name", "items_Name"), named.stream().map(c -> c.field().name()).toList());
        assertEquals(List.of(2, 2), named.stream().map(ColumnInfo::ordinal).toList());
    }

    @Test
    void anErrorOfASourceGoesToTheCallerOnlyWhenTheSourceIsRead() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> tables.tables(NamePattern.ANY, NamePattern.ANY, NamePattern.ANY));
        assertEquals("The database of broken is down", e.getMessage());
        assertEquals(List.of("pg", "mem", "broken"), tables.catalogs());
        assertEquals(2, tables.tables(p("mem"), NamePattern.ANY, NamePattern.ANY).size());
    }

    @Test
    void theEngineListsItsSources() {
        try (FyrQL engine = FyrQL.builder().register(mem).register(pg).build()) {
            assertSame(engine.catalogTables(), engine.catalogTables());
            assertEquals(List.of("mem", "pg"), engine.catalogTables().catalogs());
            assertEquals(List.of("mem.main.items"),
                    names(engine.catalogTables().tables(NamePattern.ANY, NamePattern.ANY, p("item%"))));
        }
    }
}
