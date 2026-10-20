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

package net.fyrql.test.tpch;

import net.fyrql.FyrQL;
import net.fyrql.connector.Connector;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.json.JsonConnector;
import net.fyrql.connector.json.JsonFormat;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.connector.parquet.ParquetConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.CodegenMode;
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
 * The TPC-H tables at a scale factor, spread over five sources: the
 * largest table {@code lineitem} in a Parquet file (source
 * {@code parquet}), {@code partsupp} in a CSV file (source {@code files}),
 * {@code orders} and {@code customer} in a database (source {@code db}:
 * PostgreSQL, or H2 at the small scale factor if PostgreSQL is off),
 * {@code part} and {@code supplier} in files of JSON lines (source
 * {@code json}), and {@code nation} and {@code region} in the memory
 * connector (source {@code mem}). The queries name the tables without a
 * source; each name is found in exactly one. DuckDB reads the same files
 * through views. At the small scale factor, the CSV and the JSON files are
 * read in parts of 64 KiB. In the database, {@code orders} and
 * {@code customer} have their primary keys, by whose ranges the source
 * reads them in parts: of 250,000 rows, or of 1,000 at the small scale
 * factor, so that both are read in several parts there too.
 */
final class TpchEngine {

    /** The scale factor of the tests in every build. */
    static final double SMALL = 0.01;

    /**
     * The size of the parts that the CSV and the JSON files are read in at
     * the small scale factor, so that they are several splits there too.
     */
    private static final long SMALL_SPLIT_SIZE = 64 << 10;

    /** The rows of the parts the tables of the database are read in. */
    private static final long SPLIT_ROWS = 250_000;

    /** The rows of the parts the tables of the database are read in at the small scale factor. */
    private static final long SMALL_SPLIT_ROWS = 1_000;

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
                    primaryKey(database, table);
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
                    primaryKey(database, table);
                }
            }
        }
        return new TpchEngine(scaleFactor, dir, database);
    }

    /** Gives a table of the database the primary key of TPC-H. */
    private static void primaryKey(TestDatabase database, TpchTable table) {
        String key = switch (table) {
            case ORDERS -> "o_orderkey";
            case CUSTOMER -> "c_custkey";
            default -> throw new IllegalArgumentException(table + " is not in the database");
        };
        String q = database.dialect().sql().quoteIdentifier("x").substring(0, 1);
        database.execute("ALTER TABLE " + q + table.tableName() + q + " ADD PRIMARY KEY (" + q + key + q + ")");
    }

    double scaleFactor() {
        return scaleFactor;
    }

    /** Returns the database of {@code orders} and {@code customer}. */
    TestDatabase database() {
        return database;
    }

    /**
     * Opens an engine over the five sources. The columns of a Parquet file
     * come from its footer; they must be those of the table.
     *
     * @param pushdown whether scans are negotiated, or {@code null} for the system property
     * @param codegen when to compile, or {@code null} for the system property
     */
    FyrQL open(Boolean pushdown, CodegenMode codegen) {
        return open(pushdown, codegen, 0);
    }

    /**
     * Opens an engine over the five sources that runs each query on at most
     * {@code threads} threads, or as many as its builder chooses if that is 0.
     */
    FyrQL open(Boolean pushdown, CodegenMode codegen, int threads) {
        return open(pushdown, codegen, threads, List.of());
    }

    /** Opens an engine over the five sources and further ones, as {@link #open(Boolean, CodegenMode, int)} does. */
    FyrQL open(Boolean pushdown, CodegenMode codegen, int threads, List<Connector> more) {
        long splitSize = scaleFactor <= SMALL ? SMALL_SPLIT_SIZE : CsvConnector.SPLIT_SIZE;
        CsvConnector.Builder csv = CsvConnector.builder("files").delimiter(TpchData.DELIMITER).header(true)
                .splitSize(splitSize);
        JsonConnector.Builder json = JsonConnector.builder("json").format(JsonFormat.NEWLINE_DELIMITED)
                .splitSize(splitSize);
        MemoryConnector.Builder memory = MemoryConnector.builder("mem");
        ParquetConnector.Builder parquet = ParquetConnector.builder("parquet");
        for (TpchTable table : TpchTable.values()) {
            String name = table.tableName();
            switch (table.store()) {
                case CSV -> csv.schema(name, table.schema()).file(name, TpchData.file(dir, table));
                case JSON -> json.schema(name, table.schema()).file(name, TpchData.file(dir, table));
                case MEMORY -> memory.table(name, table.schema(), memoryRows.get(table));
                case PARQUET -> parquet.path(name, TpchData.file(dir, table));
                case DATABASE -> {
                }
            }
        }
        ParquetConnector files = parquet.build();
        for (TpchTable table : TpchTable.values()) {
            if (table.store() == TpchTable.Store.PARQUET
                    && !files.catalog().schema(TableRef.of("parquet", table.tableName())).equals(table.schema())) {
                throw new IllegalStateException("The Parquet file of " + table.tableName() + " has the columns "
                        + files.catalog().schema(TableRef.of("parquet", table.tableName())));
            }
        }
        FyrQL.Builder b = FyrQL.builder().register(csv.build()).register(json.build()).register(memory.build())
                .register(files).register(database.connector("db",
                        db -> db.splitRows(scaleFactor <= SMALL ? SMALL_SPLIT_ROWS : SPLIT_ROWS)));
        more.forEach(b::register);
        if (pushdown != null) {
            b.pushdown(pushdown);
        }
        if (codegen != null) {
            b.codegen(codegen);
        }
        if (threads > 0) {
            b.threads(threads);
        }
        return b.build();
    }

    /** Creates the tables in DuckDB: views over the files, and copies of the tables of the memory connector. */
    void views(DuckDbReference duck) {
        for (TpchTable table : TpchTable.values()) {
            String name = table.tableName();
            Path file = TpchData.file(dir, table);
            switch (table.store()) {
                case CSV, DATABASE -> duck.execute("CREATE OR REPLACE VIEW " + name + " AS SELECT * FROM "
                        + TpchData.readCsv(table, file));
                case JSON -> duck.execute("CREATE OR REPLACE VIEW " + name + " AS SELECT * FROM read_json('"
                        + TpchData.path(file) + "', format = 'newline_delimited', columns = "
                        + TpchData.columns(table.schema()) + ")");
                case PARQUET -> duck.execute("CREATE OR REPLACE VIEW " + name + " AS SELECT * FROM read_parquet('"
                        + TpchData.path(file) + "')");
                case MEMORY -> duck.load(name, table.schema(), List.of(Batch.ofRows(table.schema(),
                        memoryRows.get(table))));
            }
        }
    }
}
