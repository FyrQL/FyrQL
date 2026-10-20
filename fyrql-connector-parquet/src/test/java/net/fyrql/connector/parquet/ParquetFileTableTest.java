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

package net.fyrql.connector.parquet;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ConnectorFactory;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parquet files read for {@code COPY ... FROM}, through {@link ParquetConnector#fileTable}. */
class ParquetFileTableTest {

    private static final Schema SCHEMA = Schema.of(new Schema.Field("k", DataType.INTEGER, false),
            new Schema.Field("h", DataType.HUGEINT), new Schema.Field("l", DataType.listOf(DataType.VARCHAR)));

    @TempDir
    Path dir;

    private final ParquetConnector parquet = ParquetConnector.builder("parquet").build();

    private Path write(String name, int rows) {
        Path file = dir.resolve(name);
        List<Object[]> values = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            values.add(new Object[] {r, BigInteger.valueOf(r).negate(), r % 2 == 0 ? null : List.of("x", "y")});
        }
        try (Sink sink = parquet.openFile("parquet", file, SCHEMA, Map.of())) {
            sink.write(Batch.ofRows(SCHEMA, values));
            sink.commit();
        }
        return file;
    }

    /** Reads all columns of a table from each of its splits in turn. */
    private List<Object[]> read(TableRef table) {
        ScanPlan plan = parquet.plan(table, ScanRequest.columns(IntStream.range(0,
                parquet.catalog().schema(table).size()).boxed().toList()));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : parquet.splits(plan)) {
            for (Batch b : Batches.readAll(parquet.open(split))) {
                rows.addAll(b.rows());
            }
        }
        return rows;
    }

    @Test
    void theTableHasTheColumnsOfTheFile() {
        Path file = write("p.parquet", 3);
        Schema wanted = Schema.of(new Schema.Field("a", DataType.BIGINT), new Schema.Field("b", DataType.VARCHAR),
                new Schema.Field("c", DataType.VARCHAR));
        TableRef table = parquet.fileTable("parquet", file, wanted, Map.of());
        assertEquals("parquet", table.source());
        assertTrue(table.table().startsWith("#"), table.table());
        // A HUGEINT is a DECIMAL(38,0) in a Parquet file.
        assertEquals(Schema.of(new Schema.Field("k", DataType.INTEGER, false), new Schema.Field("h",
                DataType.decimal(38, 0)), new Schema.Field("l", DataType.listOf(DataType.VARCHAR))),
                parquet.catalog().schema(table));
        assertEquals(Arrays.deepToString(new Object[] {
            new Object[] {0, BigDecimal.ZERO, null}, new Object[] {1, BigDecimal.ONE.negate(), List.of("x", "y")},
            new Object[] {2, BigDecimal.valueOf(-2), null}}), Arrays.deepToString(read(table).toArray()));
        assertEquals(List.of(), parquet.catalog().tables("main"));

        // The same file in the same version gives the same table, as read_parquet does; a new version another one.
        assertEquals(table, parquet.fileTable("parquet", file, SCHEMA, Map.of()));
        assertEquals(table, parquet.bind("read_parquet", List.of(file.toString()), Map.of()));
        write("p.parquet", 5);
        TableRef changed = parquet.fileTable("parquet", file, wanted, Map.of());
        assertNotEquals(table, changed);
        assertEquals(5, read(changed).size());
    }

    @Test
    void errors() {
        Path file = write("e.parquet", 1);
        List<Map.Entry<String, Runnable>> errors = new ArrayList<>();
        errors.add(Map.entry("The Parquet source parquet reads no files of the format csv",
                () -> parquet.fileTable("csv", file, SCHEMA, Map.of())));
        errors.add(Map.entry("The Parquet file " + file + " is read without options, not with compression",
                () -> parquet.fileTable("parquet", file, SCHEMA, Map.of("compression", "zstd"))));
        Path missing = dir.resolve("missing.parquet");
        errors.add(Map.entry("No files found that match the pattern \"" + missing + "\"",
                () -> parquet.fileTable("parquet", missing, SCHEMA, Map.of())));
        errors.add(Map.entry("No files found that match the pattern \"" + dir + "\"",
                () -> parquet.fileTable("parquet", dir, SCHEMA, Map.of())));
        for (Map.Entry<String, Runnable> e : errors) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, e.getValue()::run);
            assertEquals(e.getKey(), x.getMessage());
        }
    }

    @Test
    void theFactoryNamesTheFormat() {
        ConnectorFactory factory = ServiceLoader.load(ConnectorFactory.class).stream()
                .map(ServiceLoader.Provider::get).filter(f -> f.type().equals("parquet")).findFirst().orElseThrow();
        assertEquals(List.of("parquet"), factory.fileFormats());
        assertEquals(List.of("parquet"), parquet.fileFormats());
    }
}
