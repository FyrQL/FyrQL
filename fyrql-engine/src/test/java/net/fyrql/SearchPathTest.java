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

package net.fyrql;

import net.fyrql.catalog.EngineCatalog;
import net.fyrql.catalog.SearchPath;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A search path given with a query instead of the one of the builder. */
class SearchPathTest {

    /**
     * A source with the tables {@code t} in {@code main} and {@code u} in
     * {@code other}, whose one column is named after the source and the
     * table, and a table function {@code read_x} that names the source too.
     * It cannot be scanned.
     */
    private record Source(String name) implements Connector, TableFunctionProvider {

        @Override
        public Catalog catalog() {
            return new Catalog() {
                @Override
                public List<String> schemas() {
                    return List.of("main", "other");
                }

                @Override
                public List<String> tables(String schema) {
                    return schema.equals("main") ? List.of("t") : List.of("u");
                }

                @Override
                public Schema schema(TableRef table) {
                    return Schema.of(new Schema.Field(name + "_" + table.table(), DataType.INTEGER));
                }
            };
        }

        @Override
        public List<String> tableFunctions() {
            return List.of("read_x");
        }

        @Override
        public TableRef bind(String function, List<Object> arguments, Map<String, Object> namedArguments) {
            return TableRef.of(name, "x");
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

    private static FyrQL engine() {
        return FyrQL.builder().register(new Source("a")).register(new Source("b")).build();
    }

    private static String column(FyrQL engine, String sql, SearchPath path) {
        return engine.plan(sql, path).schema().name(0);
    }

    @Test
    void aQueryResolvesItsNamesAlongItsSearchPath() {
        try (FyrQL engine = engine()) {
            SqlException e = assertThrows(SqlException.class, () -> engine.plan("SELECT * FROM t"));
            assertEquals("Table name t is ambiguous: a.main.t, b.main.t", e.reason());
            assertEquals("a_t", column(engine, "SELECT * FROM t", new SearchPath("a", "main")));
            assertEquals("b_t", column(engine, "SELECT * FROM T", new SearchPath("b", "main")));
            assertEquals("b_u", column(engine, "SELECT * FROM u", new SearchPath("b", "other")));
            assertEquals("a_u", column(engine, "SELECT * FROM other.u", new SearchPath("a", "main")));
            assertEquals("b_t", column(engine, "SELECT * FROM b.t", new SearchPath("a", "main")),
                    "a name with its source keeps it");
            assertEquals("b_x", column(engine, "SELECT * FROM read_x('f')", new SearchPath("b", "main")),
                    "the table functions of the default source come first");
            assertEquals("a_x", column(engine, "SELECT * FROM read_x('f')", SearchPath.none()));
        }
    }

    @Test
    void thePathOfTheBuilderStaysAndAnUnknownSourceIsAnError() {
        try (FyrQL engine = FyrQL.builder().register(new Source("a")).register(new Source("b")).defaultSource("b")
                .build()) {
            engine.plan("SELECT * FROM t", new SearchPath("a", "main"));
            assertEquals("b_t", engine.plan("SELECT * FROM t").schema().name(0));
            EngineCatalog catalog = engine.catalog();
            assertSame(catalog, catalog.withSearchPath(new SearchPath("b", "main")));
            assertEquals(new SearchPath("a", "other"), catalog.withSearchPath(new SearchPath("a", "other"))
                    .searchPath());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> engine.plan("SELECT 1", new SearchPath("c", "main")));
            assertEquals("The default source c is not registered; registered: [a, b]", e.getMessage());
        }
    }
}
