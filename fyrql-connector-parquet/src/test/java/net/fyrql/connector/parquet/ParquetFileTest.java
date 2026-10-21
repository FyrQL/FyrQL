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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.FileStores;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The public view of one Parquet file for the readers of table formats on top of Parquet. */
class ParquetFileTest {

    private static final String SCHEMA = "message m { required int32 a = 10; optional binary s (STRING) = 20;"
            + " optional float f; optional int32 d (DECIMAL(5,2)) = 40; }";

    @TempDir
    static Path dir;

    private static Path file;

    @BeforeAll
    static void write() throws Exception {
        file = ParquetTestFiles.write(dir.resolve("ids.parquet"), SCHEMA,
                w -> w.withCompressionCodec(CompressionCodecName.GZIP).withRowGroupRowCountLimit(400)
                        .withPageRowCountLimit(100),
                w -> {
                    for (int i = 0; i < 1000; i++) {
                        int n = i;
                        w.row(g -> {
                            g.append("a", n);
                            g.append("s", "s" + n);
                            if (n % 3 != 0) {
                                g.append("f", n + 0.25f);
                            }
                            g.append("d", n % 100 * 101);
                        });
                    }
                });
    }

    private static ParquetFile open(Path path) {
        return ParquetFile.open(FileStores.local(dir), path.toString(), 1 << 16);
    }

    @Test
    void theFieldIdsAndRowGroupsOfAFile() {
        ParquetFile parquet = open(file);
        assertEquals(file.toAbsolutePath().toString(), parquet.location());
        assertEquals("(a INTEGER NOT NULL, s VARCHAR, f FLOAT, d DECIMAL(5,2))",
                ParquetFiles.columns(parquet.schema()));
        assertArrayEquals(new int[] {10, 20, -1, 40}, parquet.fieldIds());
        assertEquals(3, parquet.rowGroups());
        assertEquals(1000, parquet.rows());
        assertEquals(List.of(400L, 400L, 200L), List.of(parquet.rows(0), parquet.rows(1), parquet.rows(2)));
        assertEquals(List.of(0L, 400L, 800L), List.of(parquet.firstRow(0), parquet.firstRow(1), parquet.firstRow(2)));
    }

    @Test
    void theFieldIdsDuckDbWrites() throws Exception {
        Path duck = dir.resolve("duck.parquet");
        ParquetTestFiles.duckdb("COPY (SELECT 1 AS x, 'a' AS y, 2.5 AS z) TO " + ParquetTestFiles.sql(duck)
                + " (FORMAT parquet, FIELD_IDS {x: 7, y: 8, z: 9})");
        assertArrayEquals(new int[] {7, 8, 9}, open(duck).fieldIds());
    }

    @Test
    void columnsAreReadInAnyOrderAndWidened() {
        ParquetFile parquet = open(file);
        Schema output = new Schema(List.of(new Schema.Field("d", DataType.decimal(9, 2), true),
                new Schema.Field("f", DataType.DOUBLE, true), new Schema.Field("a", DataType.BIGINT, false),
                new Schema.Field("d2", DataType.decimal(9, 2), true)));
        List<ColumnPath> columns = List.of(path(3), path(2), path(0), path(3));
        List<List<Object>> rows = new ArrayList<>();
        for (int g = 0; g < parquet.rowGroups(); g++) {
            rows.addAll(rows(parquet.read(g, parquet.rows(g), output, columns, List.of(), 3, null)));
        }
        assertEquals(1000, rows.size());
        assertEquals(List.of(new BigDecimal("5.05"), 5.25, 5L, new BigDecimal("5.05")), rows.get(5));
        assertEquals(Arrays.asList(new BigDecimal("0.00"), null, 0L, new BigDecimal("0.00")), rows.get(0));
        assertEquals(2.25, rows.get(2).get(1));
        Schema wide = new Schema(List.of(new Schema.Field("d", DataType.decimal(20, 2), true)));
        assertEquals(List.of(List.of(new BigDecimal("99.99"))), rows(parquet.read(0, 400, wide, List.of(path(3)),
                List.of(), 1000, null)).subList(99, 100));
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> parquet.read(0, 4,
                new Schema(List.of(new Schema.Field("d", DataType.decimal(9, 2), true),
                        new Schema.Field("d", DataType.decimal(20, 2), true))), List.of(path(3), path(3)),
                List.of(), 10, null));
        assertTrue(twice.getMessage().contains("is read as DECIMAL(9,2) and as DECIMAL(20,2)"), twice.getMessage());
    }

    @Test
    void theFirstRowsOfARowGroup() {
        ParquetFile parquet = open(file);
        Schema output = new Schema(List.of(new Schema.Field("a", DataType.INTEGER, false)));
        List<ParquetConnector.Read> reads = new ArrayList<>();
        List<List<Object>> rows = rows(parquet.read(1, 3, output, List.of(path(0)), List.of(), 2, reads::add));
        assertEquals(List.of(List.of(400), List.of(401), List.of(402)), rows);
        assertEquals(List.of(new ParquetConnector.Read(parquet.location(), 1, List.of("a"), 3)), reads);
    }

    @Test
    void rowGroupsAndPagesThatCanPassFilters() {
        ParquetFile parquet = open(file);
        List<ColumnPath> columns = List.of(path(1), path(0));
        Expression atLeast700 = new Expression.Comparison(Expression.ComparisonOp.GE,
                new Expression.Column(1, DataType.INTEGER), new Expression.Literal(DataType.INTEGER, 700));
        assertEquals(List.of(1, 2), parquet.rowGroups(List.of(atLeast700), columns));
        assertEquals(List.of(0, 1, 2), parquet.rowGroups(List.of(), columns));
        Schema output = new Schema(List.of(new Schema.Field("s", DataType.VARCHAR, true),
                new Schema.Field("a", DataType.INTEGER, false)));
        // Pages of 100 rows: of the row group with 400 to 799 only the page from 700 is read.
        List<List<Object>> rows = rows(parquet.read(1, 400, output, columns, List.of(atLeast700), 64, null));
        assertEquals(100, rows.size());
        assertEquals(List.of("s700", 700), rows.get(0));
        assertEquals(List.of("s799", 799), rows.get(99));
    }

    @Test
    void columnsThatCannotBeReadAsAType() {
        ParquetFile parquet = open(file);
        IllegalArgumentException narrower = assertThrows(IllegalArgumentException.class, () -> parquet.read(0, 4,
                new Schema(List.of(new Schema.Field("a", DataType.SMALLINT, false))), List.of(path(0)), List.of(),
                10, null));
        assertTrue(narrower.getMessage().contains("Cannot read the column a of type INTEGER of the Parquet file "
                + parquet.location() + " as SMALLINT"), narrower.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parquet.read(0, 4,
                new Schema(List.of(new Schema.Field("s", DataType.INTEGER, true))), List.of(path(1)), List.of(), 10,
                null));
        assertThrows(IllegalArgumentException.class, () -> parquet.read(0, 4,
                new Schema(List.of(new Schema.Field("d", DataType.decimal(9, 3), true))), List.of(path(3)),
                List.of(), 10, null));
        assertThrows(IllegalArgumentException.class, () -> parquet.read(0, 4,
                new Schema(List.of(new Schema.Field("x", DataType.INTEGER, true))), List.of(path(4)), List.of(), 10,
                null));
        assertThrows(IllegalArgumentException.class, () -> open(dir.resolve("none.parquet")));
    }

    @Test
    void wideningIsWithoutLoss() {
        assertTrue(ParquetFile.widens(DataType.INTEGER, DataType.BIGINT));
        assertTrue(ParquetFile.widens(DataType.TINYINT, DataType.INTEGER));
        assertTrue(ParquetFile.widens(DataType.FLOAT, DataType.DOUBLE));
        assertTrue(ParquetFile.widens(DataType.decimal(5, 2), DataType.decimal(38, 2)));
        assertTrue(!ParquetFile.widens(DataType.BIGINT, DataType.INTEGER));
        assertTrue(!ParquetFile.widens(DataType.DOUBLE, DataType.FLOAT));
        assertTrue(!ParquetFile.widens(DataType.INTEGER, DataType.HUGEINT));
        assertTrue(!ParquetFile.widens(DataType.decimal(5, 2), DataType.decimal(6, 3)));
        assertTrue(!ParquetFile.widens(DataType.INTEGER, DataType.DOUBLE));
    }

    private static ColumnPath path(int column) {
        return new ColumnPath(column, List.of());
    }

    private static List<List<Object>> rows(net.fyrql.connector.BatchReader reader) {
        List<List<Object>> rows = new ArrayList<>();
        for (Batch b : Batches.readAll(reader)) {
            for (int i = 0; i < b.rowCount(); i++) {
                List<Object> row = new ArrayList<>();
                for (int c = 0; c < b.schema().size(); c++) {
                    row.add(b.column(c).get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
