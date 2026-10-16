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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.json.JsonFormat;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.CodegenMode;
import net.fyrql.sql.render.DuckDbDialect;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.db.TestDatabase;
import net.fyrql.test.db.TestDatabases;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The TPC-H tables at a scale factor, spread over four sources: the large
 * tables {@code lineitem} and {@code partsupp} in CSV files (source
 * {@code files}), {@code orders} and {@code customer} in a database (source
 * {@code db}: PostgreSQL, or H2 at the small scale factor if PostgreSQL is
 * off), {@code part} and {@code supplier} in files of JSON lines (source
 * {@code json}), and {@code nation} and {@code region} in the memory
 * connector (source {@code mem}). The queries name the tables without a
 * source; each name is found in exactly one. DuckDB reads the same files
 * through views.
 */
final class TpchEngine {

    /** The scale factor of the tests in every build. */
    static final double SMALL = 0.01;

    private final double scaleFactor;
    private final Path dir;
    private final TestDatabase database;
    private final Map<TpchTable, List<Object[]>> memoryRows = new EnumMap<>(TpchTable.class);

    private TpchEngine(double scaleFactor, Path dir, TestDatabase database) {
        this.scaleFactor = scaleFactor;
        this.dir = dir;
        this.database = database;
        for (TpchTable table : TpchTable.values()) {
            if (table.store() == TpchTable.Store.MEMORY) {
                List<Object[]> rows = new ArrayList<>();
                table.rows(scaleFactor).forEach(rows::add);
                memoryRows.put(table, rows);
            }
        }
    }

    /**
     * Writes the files of a scale factor into a directory unless they are
     * there, and loads the tables of the database into a new database.
     * Above the small scale factor the database must be PostgreSQL; without
     * it, the test is skipped.
     */
    static TpchEngine load(double scaleFactor, Path dir) {
        TpchData.generate(scaleFactor, dir);
        Optional<TestDatabase> postgres = TestDatabases.postgres();
        TestDatabase database;
        if (postgres.isPresent()) {
            database = postgres.get();
            for (TpchTable table : TpchTable.values()) {
                if (table.store() == TpchTable.Store.DATABASE) {
                    database.copy(table.tableName(), table.schema(), TpchData.file(dir, table), TpchData.DELIMITER);
                }
            }
        } else {
            assumeTrue(scaleFactor <= SMALL, "PostgreSQL is off; TPC-H above scale factor " + SMALL + " needs it");
            database = TestDatabases.h2();
            for (TpchTable table : TpchTable.values()) {
                if (table.store() == TpchTable.Store.DATABASE) {
                    List<Object[]> rows = new ArrayList<>();
                    table.rows(scaleFactor).forEach(rows::add);
                    database.load(table.tableName(), table.schema(), rows);
                }
            }
        }
        return new TpchEngine(scaleFactor, dir, database);
    }

    double scaleFactor() {
        return scaleFactor;
    }

    /** Returns the database of {@code orders} and {@code customer}. */
    TestDatabase database() {
        return database;
    }

    /**
     * Opens an engine over the four sources.
     *
     * @param pushdown whether scans are negotiated, or {@code null} for the system property
     * @param codegen when to compile, or {@code null} for the system property
     */
    FyrQL open(Boolean pushdown, CodegenMode codegen) {
        CsvConnector.Builder csv = CsvConnector.builder("files").delimiter(TpchData.DELIMITER).header(true);
        JsonConnector.Builder json = JsonConnector.builder("json").format(JsonFormat.NEWLINE_DELIMITED);
        MemoryConnector.Builder memory = MemoryConnector.builder("mem");
        for (TpchTable table : TpchTable.values()) {
            String name = table.tableName();
            switch (table.store()) {
                case CSV -> csv.schema(name, table.schema()).file(name, TpchData.file(dir, table));
                case JSON -> json.schema(name, table.schema()).file(name, TpchData.file(dir, table));
                case MEMORY -> memory.table(name, table.schema(), memoryRows.get(table));
                case DATABASE -> {
                }
            }
        }
        FyrQL.Builder b = FyrQL.builder().register(csv.build()).register(json.build()).register(memory.build())
                .register(database.connector("db"));
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        if (codegen != null) {
            b.codegen(codegen);
        }
        return b.build();
    }

    /** Creates the tables in DuckDB: views over the files, and copies of the tables of the memory connector. */
    void views(DuckDbReference duck) {
        for (TpchTable table : TpchTable.values()) {
            String name = table.tableName();
            String file = TpchData.file(dir, table).toAbsolutePath().toString().replace('\\', '/');
            switch (table.store()) {
                case CSV, DATABASE -> duck.execute("CREATE OR REPLACE VIEW " + name + " AS SELECT * FROM read_csv('"
                        + file + "', delim = '" + TpchData.DELIMITER + "', header = true, quote = '\"', columns = "
                        + columns(table.schema()) + ")");
                case JSON -> duck.execute("CREATE OR REPLACE VIEW " + name + " AS SELECT * FROM read_json('" + file
                        + "', format = 'newline_delimited', columns = " + columns(table.schema()) + ")");
                case MEMORY -> duck.load(name, table.schema(), List.of(Batch.ofRows(table.schema(),
                        memoryRows.get(table))));
            }
        }
    }

    /** The columns of a schema as a struct literal of DuckDB: names to types. */
    private static String columns(Schema schema) {
        StringBuilder b = new StringBuilder("{");
        for (int c = 0; c < schema.size(); c++) {
            b.append(c > 0 ? ", " : "").append('\'').append(schema.name(c)).append("': '")
                    .append(DuckDbDialect.INSTANCE.typeName(schema.type(c))).append('\'');
        }
        return b.append('}').toString();
    }
}
