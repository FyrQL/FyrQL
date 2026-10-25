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

import net.fyrql.FyrQL;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.TableFunctionProvider;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.SqlException;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NameResolverTest {

    /** A source with fixed schemas and tables, all of one column, that cannot be scanned. */
    private static class Stub implements Connector {
        private final String name;
        private final Map<String, List<String>> tables;

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
                    return Schema.of(new Schema.Field(table.table() + "_id", DataType.INTEGER));
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

    /** A stub that also offers table functions, which name the table "f" with the arguments in it. */
    private static final class Functions extends Stub implements TableFunctionProvider {
        Functions(String name) {
            super(name, Map.of("main", List.of()));
        }

        @Override
        public List<String> tableFunctions() {
            return List.of("read_csv", "read_" + name());
        }

        @Override
        public TableRef bind(String function, List<Object> arguments, Map<String, Object> namedArguments) {
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException(function + " needs an argument");
            }
            return TableRef.of(name(), function + arguments + namedArguments);
        }
    }

    private static Map<String, Connector> sources() {
        Map<String, List<String>> pg = new LinkedHashMap<>();
        pg.put("main", List.of("orders"));
        pg.put("public", List.of("orders", "users"));
        pg.put("sales", List.of("Orders", "ORDERS"));
        Map<String, Connector> sources = new LinkedHashMap<>();
        sources.put("pg", new Stub("pg", pg));
        sources.put("mem", new Stub("mem", Map.of("main", List.of("orders", "items", "users"))));
        sources.put("csv", new Stub("csv", Map.of("main", List.of("files", "Mixed"))));
        return sources;
    }

    private static String resolve(NameResolver r, String... name) {
        return r.resolve(List.of(name)).toString();
    }

    private static String error(NameResolver r, String... name) {
        return assertThrows(SqlException.class, () -> r.resolve(List.of(name))).getMessage();
    }

    @Test
    void threePartsAreSourceSchemaTable() {
        NameResolver r = new NameResolver(sources(), SearchPath.none());
        assertEquals("pg.public.orders", resolve(r, "pg", "public", "orders"));
        assertEquals("pg.public.users", resolve(r, "PG", "PUBLIC", "Users"));
        assertEquals("pg.sales.Orders", resolve(r, "pg", "sales", "Orders"));
        assertEquals("Table name pg.sales.orders is ambiguous: pg.sales.Orders, pg.sales.ORDERS",
                error(r, "pg", "sales", "orders"));
        assertEquals("Table with name pg.public.items does not exist", error(r, "pg", "public", "items"));
        assertEquals("Table with name nosuch.main.orders does not exist", error(r, "nosuch", "main", "orders"));
        assertEquals("A table name has one to three parts: a.b.c.d", error(r, "a", "b", "c", "d"));
    }

    @Test
    void twoPartsAreSourceTableBeforeSchemaTable() {
        NameResolver none = new NameResolver(sources(), SearchPath.none());
        assertEquals("pg.main.orders", resolve(none, "pg", "orders"));
        assertEquals("mem.main.items", resolve(none, "mem", "items"));
        // pg has no table users in main, so pg.users is no source.table; and no source has a schema pg.
        assertEquals("Table with name pg.users does not exist", error(none, "pg", "users"));
        // Without a default source, schema.table is looked up in every source.
        assertEquals("pg.public.users", resolve(none, "public", "users"));
        assertEquals("Table name main.orders is ambiguous: pg.main.orders, mem.main.orders",
                error(none, "main", "orders"));
        NameResolver mem = new NameResolver(sources(), new SearchPath("mem", "main"));
        assertEquals("mem.main.orders", resolve(mem, "main", "orders"));
        assertEquals("Table with name public.users does not exist", error(mem, "public", "users"));
    }

    @Test
    void onePartIsTheDefaultPlaceThenAnywhere() {
        NameResolver mem = new NameResolver(sources(), new SearchPath("mem", "main"));
        assertEquals("mem.main.orders", resolve(mem, "orders"));
        assertEquals("mem.main.items", resolve(mem, "ITEMS"));
        assertEquals("csv.main.files", resolve(mem, "files"));
        assertEquals("csv.main.Mixed", resolve(mem, "mixed"));
        NameResolver pg = new NameResolver(sources(), new SearchPath("pg", "public"));
        assertEquals("pg.public.users", resolve(pg, "users"));
        assertEquals("pg.public.orders", resolve(pg, "orders"));
        NameResolver none = new NameResolver(sources(), SearchPath.none());
        assertEquals("Table name users is ambiguous: pg.public.users, mem.main.users", error(none, "users"));
        assertEquals("Table name orders is ambiguous: pg.main.orders, pg.public.orders, pg.sales.Orders,"
                + " pg.sales.ORDERS, mem.main.orders", error(none, "orders"));
        assertEquals("Table with name nothing does not exist", error(none, "nothing"));
        assertThrows(IllegalArgumentException.class, () -> new NameResolver(sources(), new SearchPath("x", "main")));
    }

    /** The sources with the catalogs of PostgreSQL, of one schema each. */
    private static Map<String, Connector> withCatalogs() {
        Map<String, Connector> sources = new LinkedHashMap<>(sources());
        sources.put("pg_catalog", new Stub("pg_catalog", Map.of("main", List.of("pg_class", "users"))));
        sources.put("information_schema", new Stub("information_schema",
                Map.of("main", List.of("tables", "columns"))));
        return sources;
    }

    /** A resolver as in PostgreSQL in the database pg along the schemas. */
    private static NameResolver postgres(String... schemas) {
        return new NameResolver(withCatalogs(), new SearchPath("pg", "public"),
                new DatabasePath("pg", List.of(schemas), List.of("pg_catalog", "information_schema")));
    }

    private static String resolveNew(NameResolver r, String... name) {
        return r.resolveNew(List.of(name)).toString();
    }

    @Test
    void onePartGoesAlongThePathOfTheDatabase() {
        NameResolver r = postgres("public", "main");
        // pg_catalog first, then the schemas in their order, and the first one with the table wins.
        assertEquals("pg_catalog.main.users", resolve(r, "users"));
        assertEquals("pg.public.orders", resolve(r, "orders"));
        assertEquals("pg.main.orders", resolve(postgres("main", "public"), "orders"));
        assertEquals("pg.public.orders", resolve(postgres("PUBLIC"), "Orders"));
        // A pg_catalog that the path names keeps its place.
        assertEquals("pg.public.users", resolve(postgres("public", "pg_catalog"), "users"));
        assertEquals("pg_catalog.main.pg_class", resolve(postgres("public", "pg_catalog"), "pg_class"));
        // A schema that the database does not have is passed over.
        assertEquals("pg.public.orders", resolve(postgres("nowhere", "public"), "orders"));
        assertEquals("Table with name orders does not exist", error(postgres("nowhere"), "orders"));
        assertEquals("pg_catalog.main.pg_class", resolve(postgres(), "pg_class"));
        // Other sources and information_schema only by their names.
        assertEquals("Table with name items does not exist", error(r, "items"));
        assertEquals("Table with name columns does not exist", error(r, "columns"));
        assertEquals("information_schema.main.columns", resolve(postgres("information_schema"), "columns"));
        // Only names in one schema can be ambiguous.
        assertEquals("Table name orders is ambiguous: pg.sales.Orders, pg.sales.ORDERS",
                error(postgres("sales", "public"), "orders"));
    }

    @Test
    void twoPartsAreCatalogOrSchemaOfTheDatabaseAndThreeAnySource() {
        NameResolver r = postgres("public");
        assertEquals("pg.public.users", resolve(r, "public", "users"));
        assertEquals("pg.main.orders", resolve(r, "main", "orders"));
        assertEquals("pg_catalog.main.users", resolve(r, "pg_catalog", "users"));
        assertEquals("information_schema.main.tables", resolve(r, "INFORMATION_SCHEMA", "tables"));
        assertEquals("Table with name mem.items does not exist", error(r, "mem", "items"));
        assertEquals("Table with name nowhere.users does not exist", error(r, "nowhere", "users"));
        assertEquals("pg_catalog.main.pg_class", resolve(r, "pg", "pg_catalog", "pg_class"));
        assertEquals("mem.main.items", resolve(r, "mem", "main", "items"));
        assertEquals("pg.public.users", resolve(r, "pg", "public", "users"));
        assertEquals("Table with name mem.pg_catalog.pg_class does not exist",
                error(r, "mem", "pg_catalog", "pg_class"));
        assertThrows(IllegalArgumentException.class, () -> new NameResolver(withCatalogs(), SearchPath.none(),
                new DatabasePath("nosuch", List.of(), List.of("pg_catalog"))));
        assertThrows(IllegalArgumentException.class, () -> new NameResolver(sources(), SearchPath.none(),
                new DatabasePath("pg", List.of(), List.of("pg_catalog"))));
    }

    @Test
    void newTablesGoToTheFirstSchemaOfThePath() {
        assertEquals("pg.public.t", resolveNew(postgres("nowhere", "public", "main"), "t"));
        assertEquals("pg.public.users", resolveNew(postgres("public"), "USERS"));
        assertEquals("pg.main.t", resolveNew(postgres("pg_catalog", "main"), "t"));
        assertEquals(Optional.of("sales"), postgres("nowhere", "SALES").creationSchema());
        assertEquals(Optional.empty(), postgres("nowhere", "information_schema").creationSchema());
        assertEquals(Optional.empty(), new NameResolver(sources(), SearchPath.none()).creationSchema());
        assertEquals("No schema has been selected to create in; set the search_path to a schema of pg",
                assertThrows(SqlException.class, () -> postgres("nowhere").resolveNew(List.of("t"))).getMessage());
        assertEquals("pg.sales.x", resolveNew(postgres(), "sales", "x"));
        assertEquals("pg_catalog.main.x", resolveNew(postgres(), "pg_catalog", "x"));
        assertEquals("Schema with name nosuch does not exist",
                assertThrows(SqlException.class, () -> postgres().resolveNew(List.of("nosuch", "x"))).getMessage());
        assertEquals("mem.main.x", resolveNew(postgres(), "mem", "main", "x"));
    }

    @Test
    void theCatalogQualifiesTheColumnsOfCatalogsAsDatabaseCatalogTable() {
        DatabasePath path = new DatabasePath("pg", List.of("public"), List.of("pg_catalog", "information_schema"));
        EngineCatalog fyrql = new EngineCatalog(withCatalogs(), new SearchPath("pg", "public"));
        EngineCatalog postgres = fyrql.withDatabasePath(path);
        assertEquals(List.of("pg_catalog", "main", "pg_class"), fyrql.path(fyrql.resolve(List.of("pg_class"))));
        assertEquals(List.of("pg", "pg_catalog", "pg_class"), postgres.path(postgres.resolve(List.of("pg_class"))));
        assertEquals(List.of("pg", "public", "users"), postgres.path(postgres.resolve(List.of("public", "users"))));
        assertEquals(path, postgres.withSearchPath(new SearchPath("pg", "main")).databasePath());
        assertEquals(path, postgres.withSources(Map.of("more", new Stub("more", Map.of()))).databasePath());
        assertEquals(path, postgres.withSession(SearchPath.none(), () -> ZoneId.of("UTC")).databasePath());
        assertEquals(postgres, postgres.withDatabasePath(path));
        assertNull(postgres.withDatabasePath(null).databasePath());
    }

    @Test
    void builderSetsTheSearchPath() {
        Stub only = new Stub("only", Map.of("main", List.of("t")));
        try (FyrQL engine = FyrQL.builder().register(only).build()) {
            assertEquals(new SearchPath("only", "main"), engine.catalog().searchPath());
            assertEquals(TableRef.of("only", "t"), engine.catalog().resolve(List.of("T")));
        }
        FyrQL.Builder two = FyrQL.builder().register(sources().get("pg")).register(sources().get("mem"));
        try (FyrQL engine = two.build()) {
            assertNull(engine.catalog().searchPath().defaultSource());
            assertEquals(List.of("pg", "mem"), engine.sources());
        }
        try (FyrQL engine = two.defaultSource("pg").defaultSchema("public").build()) {
            assertEquals("pg.public.users", engine.catalog().resolve(List.of("users")).toString());
            assertEquals(Schema.of(new Schema.Field("users_id", DataType.INTEGER)),
                    engine.catalog().schema(engine.catalog().resolve(List.of("users"))));
        }
        assertThrows(IllegalArgumentException.class, () -> two.defaultSource("nosuch").build());
    }

    @Test
    void tableFunctionsComeFromTheirProviders() {
        Map<String, Connector> sources = new LinkedHashMap<>(sources());
        sources.put("a", new Functions("a"));
        sources.put("b", new Functions("b"));
        // The catalog adds the time zone of the session to the named arguments.
        EngineCatalog first = new EngineCatalog(sources, SearchPath.none(), () -> ZoneId.of("UTC"));
        assertEquals(List.of("read_csv", "read_a", "read_b"), first.tableFunctions());
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("Delim", ";");
        named.put("HEADER", true);
        named.put("delim", ",");
        assertEquals("a.main.read_csv[x.csv]{delim=,, header=true, timezone=UTC}",
                first.tableFunction("READ_CSV", List.of("x.csv"), named).toString());
        assertEquals("b.main.read_b[1]{timezone=UTC}", first.tableFunction("read_b", List.of(1L), Map.of()).toString());
        EngineCatalog preferB = new EngineCatalog(sources, new SearchPath("b", "main"), () -> ZoneId.of("UTC"));
        assertEquals("b.main.read_csv[x.csv]{timezone=UTC}",
                preferB.tableFunction("read_csv", List.of("x.csv"), Map.of()).toString());
        assertEquals("read_csv needs an argument",
                assertThrows(SqlException.class, () -> first.tableFunction("read_csv", List.of(), Map.of()))
                        .getMessage());
        assertEquals("Table function with name read_xml does not exist; there are read_csv, read_a, read_b",
                assertThrows(SqlException.class, () -> first.tableFunction("read_xml", List.of(), Map.of()))
                        .getMessage());
    }
}
