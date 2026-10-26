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

package net.fyrql.connector.files;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.files.HivePartitions.Columns;
import net.fyrql.connector.files.HivePartitions.Layout;
import net.fyrql.connector.files.HivePartitions.Match;
import net.fyrql.connector.files.HivePartitions.Options;
import net.fyrql.connector.files.HivePartitions.Partitions;
import net.fyrql.connector.files.HivePartitions.Projection;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vectors;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static net.fyrql.core.expr.Expressions.call;
import static net.fyrql.core.expr.Expressions.cast;
import static net.fyrql.core.expr.Expressions.col;
import static net.fyrql.core.expr.Expressions.eq;
import static net.fyrql.core.expr.Expressions.gt;
import static net.fyrql.core.expr.Expressions.isNull;
import static net.fyrql.core.expr.Expressions.lit;
import static net.fyrql.core.expr.Expressions.or;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HivePartitionsTest {

    private static final String ROOT = "s3://b/sales";

    private static Partitions partitions(Options options, String... files) {
        List<String> locations = Arrays.stream(files).map(f -> ROOT + "/" + f).toList();
        return HivePartitions.partitions(locations, options);
    }

    private static String message(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }

    @Test
    void theDirectoriesOnThePathOfAFileArePartitions() {
        List<String[]> s = HivePartitions.segments("C:\\data\\sales\\year=2024\\other/city=New%20York\\x=1.csv");
        assertEquals(2, s.size());
        assertArrayEquals(new String[] {"year", "2024"}, s.get(0));
        assertArrayEquals(new String[] {"city", "New York"}, s.get(1));
        assertArrayEquals(new String[] {"k", null},
                HivePartitions.segments("s3://b/d/k=__HIVE_DEFAULT_PARTITION__/f.parquet").get(0));
        assertArrayEquals(new String[] {"k", ""}, HivePartitions.segments("/d/k=/f.parquet").get(0));
        assertEquals(List.of(), HivePartitions.segments("/d/f=1.parquet"));
        assertEquals(List.of(), HivePartitions.segments("/d/=1/f.parquet"));
        assertEquals("The directories of /d/a=1/A=2/f.parquet repeat the partition A",
                message(() -> HivePartitions.segments("/d/a=1/A=2/f.parquet")));
    }

    @Test
    void percentEncodedBytesAreDecodedAsUtf8() {
        assertEquals("a/b=c", HivePartitions.decode("a%2Fb%3dc"));
        assertEquals("Zürich", HivePartitions.decode("Z%C3%BCrich"));
        assertEquals("100%", HivePartitions.decode("100%"));
        assertEquals("%zz%4", HivePartitions.decode("%zz%4"));
    }

    @Test
    void theColumnsAreOrderedByTheirKeysAndTypedByAllValues() {
        Partitions p = partitions(Options.AUTO, "year=2024/day=2024-01-02/t=2024-01-02 10:00:00/c=1/Z=1/a.parquet",
                "year=2025/day=2025-12-31/t=2025-01-01/c=x/z=007/b.parquet",
                "year=__HIVE_DEFAULT_PARTITION__/day=2025-12-31/t=2025-01-01/c=2/Z=-3/c.parquet");
        assertEquals(List.of(new Schema.Field("Z", DataType.VARCHAR, true), new Schema.Field("c", DataType.VARCHAR,
                true), new Schema.Field("day", DataType.DATE, true), new Schema.Field("t", DataType.VARCHAR, true),
                new Schema.Field("year", DataType.BIGINT, true)), p.layout().columns());
        assertEquals("007", p.values().get(1)[0]);
        assertEquals("x", p.values().get(1)[1]);
        assertEquals((int) LocalDate.of(2024, 1, 2).toEpochDay(), p.values().get(0)[2]);
        assertEquals(2024L, p.values().get(0)[4]);
        assertNull(p.values().get(2)[4]);
        assertEquals(List.of(new Schema.Field("n", DataType.BIGINT, true)),
                partitions(Options.AUTO, "n=8/a.parquet", "n=-5/b.parquet").layout().columns());
        assertEquals(DataType.VARCHAR, partitions(Options.AUTO, "n=+5/a.parquet").layout().columns().get(0).type());
        assertEquals(DataType.VARCHAR, partitions(Options.AUTO, "d=2024-1-2/a.parquet").layout().columns().get(0)
                .type());

        Partitions text = partitions(new Options(null, false, Map.of()), "year=2024/a.parquet");
        assertEquals(DataType.VARCHAR, text.layout().columns().get(0).type());
        assertEquals("2024", text.values().get(0)[0]);
        Partitions nulls = partitions(Options.AUTO, "k=__HIVE_DEFAULT_PARTITION__/a.parquet");
        assertEquals(DataType.VARCHAR, nulls.layout().columns().get(0).type());
    }

    @Test
    void hiveTypesSetTheTypesOfPartitions() {
        Options options = Options.AUTO.withTypes(Map.of("YEAR", DataType.INTEGER));
        Partitions p = partitions(options, "year=2024/a.parquet");
        assertEquals(DataType.INTEGER, p.layout().columns().get(0).type());
        assertEquals(2024, p.values().get(0)[0]);
        assertEquals("The partition year=x of s3://b/sales/year=x/a.parquet is no INTEGER",
                message(() -> partitions(options, "year=x/a.parquet")));
        assertEquals("hive_types names month, which is no partition; the partitions are year",
                message(() -> partitions(Options.AUTO.withTypes(Map.of("month", DataType.INTEGER)),
                        "year=1/a.parquet")));
        assertEquals("hive_types names month, which is no partition of the files",
                message(() -> partitions(new Options(false, true, Map.of("month", DataType.INTEGER)),
                        "year=1/a.parquet")));
    }

    @Test
    void filesWithDifferentKeysHaveNoPartitionsUnlessTheyMust() {
        assertEquals(Layout.NONE, partitions(Options.AUTO, "year=1/a.parquet", "b.parquet").layout());
        assertEquals(Layout.NONE, partitions(Options.AUTO, "year=1/a.parquet", "month=1/b.parquet").layout());
        assertEquals(List.of("year"), partitions(Options.AUTO, "year=1/a.parquet", "x/YEAR=2/b.parquet")
                .layout().keys());
        Partitions swapped = partitions(Options.AUTO, "b=1/a=x/f.parquet", "a=y/b=2/g.parquet");
        assertEquals(List.of("a", "b"), swapped.layout().keys());
        assertArrayEquals(new Object[] {"y", 2L}, swapped.values().get(1));
        assertEquals(Layout.NONE, partitions(new Options(false, true, Map.of()), "year=1/a.parquet").layout());
        assertEquals(0, partitions(new Options(false, true, Map.of()), "year=1/a.parquet").values().get(0).length);
        assertEquals(Layout.NONE, partitions(new Options(true, true, Map.of()), "a.parquet").layout());
        assertEquals("The files have different partitions: s3://b/sales/year=1/a.parquet has [year],"
                + " s3://b/sales/b.parquet has []",
                message(() -> partitions(new Options(true, true, Map.of()), "year=1/a.parquet", "b.parquet")));
    }

    @Test
    void aColumnOfTheFilesWithTheNameOfAKeyTakesThePartition() {
        Schema file = Schema.of(new Schema.Field("amount", DataType.DOUBLE, true),
                new Schema.Field("Year", DataType.VARCHAR, false));
        Layout layout = new Layout(List.of(new Schema.Field("year", DataType.BIGINT, true),
                new Schema.Field("month", DataType.BIGINT, true)));
        Columns columns = HivePartitions.columns(file, layout);
        assertEquals(List.of(new Schema.Field("amount", DataType.DOUBLE, true),
                new Schema.Field("Year", DataType.BIGINT, true), new Schema.Field("month", DataType.BIGINT, true)),
                columns.schema().fields());
        assertEquals(0, columns.fileColumn(0));
        assertEquals(-1, columns.fileColumn(1));
        assertEquals(0, columns.partition(1));
        assertEquals(1, columns.partition(2));
        assertEquals(-1, columns.partition(0));
        assertEquals(Arrays.asList(null, ColumnPath.whole(0)),
                columns.filePaths(List.of(ColumnPath.whole(2), ColumnPath.whole(0))));
    }

    /** The table amount DOUBLE, s STRUCT(a INTEGER), year BIGINT, month VARCHAR, read in another order. */
    private static Columns table() {
        Schema file = Schema.of(new Schema.Field("amount", DataType.DOUBLE, true),
                new Schema.Field("s", DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER)), true));
        return HivePartitions.columns(file, new Layout(List.of(new Schema.Field("year", DataType.BIGINT, true),
                new Schema.Field("month", DataType.VARCHAR, true))));
    }

    private static final List<ColumnPath> READ = List.of(ColumnPath.whole(2), ColumnPath.whole(0),
            ColumnPath.whole(3), new ColumnPath(1, List.of("a")));

    @Test
    void theConnectorTakesOverTheFiltersOfPartitionsOnly() {
        Predicate<Expression> filter = HivePartitions.filter(READ, table());
        Expression year = col(0, DataType.BIGINT);
        Expression amount = col(1, DataType.DOUBLE);
        Expression month = col(2, DataType.VARCHAR);
        assertTrue(filter.test(eq(year, lit(2024L))));
        assertTrue(filter.test(or(eq(year, lit(2024L)), isNull(month))));
        assertFalse(filter.test(or(eq(year, lit(2024L)), gt(amount, lit(10.0)))));
        assertFalse(filter.test(lit(true)));
        assertFalse(filter.test(gt(col(3, DataType.INTEGER), lit(1))));
        assertFalse(filter.test(or(eq(year, lit(2024L)), gt(call("random"), lit(0.5)))));
    }

    @Test
    void theFiltersOfPartitionsSayWhetherToReadAFile() {
        Columns table = table();
        List<Expression> filters = List.of(eq(col(0, DataType.BIGINT), lit(2024L)), isNull(col(2, DataType.VARCHAR)));
        assertEquals(Match.ALL, HivePartitions.match(filters, READ, table, new Object[] {2024L, null}));
        assertEquals(Match.NONE, HivePartitions.match(filters, READ, table, new Object[] {2025L, null}));
        assertEquals(Match.NONE, HivePartitions.match(filters, READ, table, new Object[] {2024L, "x"}));
        assertEquals(Match.NONE, HivePartitions.match(filters, READ, table, new Object[] {null, null}));
        assertEquals(Match.ALL, HivePartitions.match(List.of(), READ, table, new Object[] {2025L, "x"}));
        List<Expression> failing = List.of(eq(cast(col(2, DataType.VARCHAR), DataType.INTEGER), lit(1)));
        assertEquals(Match.FAILS, HivePartitions.match(failing, READ, table, new Object[] {2024L, "x"}));
        assertEquals(Match.ALL, HivePartitions.match(failing, READ, table, new Object[] {2024L, "1"}));
        assertThrows(RuntimeException.class, () -> HivePartitions.passes(failing, READ, table,
                new Object[] {2024L, "x"}));
    }

    @Test
    void theReaderAddsTheValuesOfThePartitionsToTheColumnsOfTheFile() {
        Columns table = table();
        Schema output = new Schema(READ.stream().map(p -> p.field(table.schema())).toList());
        Projection projection = HivePartitions.project(READ, output, table, new Object[] {2024L, "x"});
        assertEquals(List.of(ColumnPath.whole(0), new ColumnPath(1, List.of("a"))), projection.fileColumns());
        assertEquals(Schema.of(new Schema.Field("amount", DataType.DOUBLE, true),
                new Schema.Field("s.a", DataType.INTEGER, true)).types(), projection.fileOutput().types());
        assertArrayEquals(new int[] {-1, 0, -1, 1}, projection.sources());
        assertTrue(projection.hasPartitions());

        Batch file = Batch.of(projection.fileOutput(), List.of(Vectors.of(DataType.DOUBLE, 1.0, 2.0, 3.0),
                Vectors.of(DataType.INTEGER, 1, null, 3)));
        List<Object[]> rows = rows(HivePartitions.withPartitions(reader(file), output, projection));
        assertEquals(3, rows.size());
        assertArrayEquals(new Object[] {2024L, 2.0, "x", null}, rows.get(1));
        Projection nulls = HivePartitions.project(READ, output, table, new Object[] {null, "3"});
        rows = rows(HivePartitions.withPartitions(reader(file), output, nulls));
        assertArrayEquals(new Object[] {null, 3.0, "3", 3}, rows.get(2));

        // Without columns of the file, the file delivers only its number of rows.
        List<ColumnPath> partitionsOnly = List.of(ColumnPath.whole(3));
        Schema month = Schema.of(new Schema.Field("month", DataType.VARCHAR, true));
        Projection none = HivePartitions.project(partitionsOnly, month, table, new Object[] {1L, "m"});
        assertEquals(List.of(), none.fileColumns());
        rows = rows(HivePartitions.withPartitions(reader(new Batch(new Schema(List.of()), 2, List.of())), month,
                none));
        assertEquals(2, rows.size());
        assertArrayEquals(new Object[] {"m"}, rows.get(1));
    }

    private static BatchReader reader(Batch... batches) {
        Iterator<Batch> it = List.of(batches).iterator();
        return () -> it.hasNext() ? it.next() : null;
    }

    private static List<Object[]> rows(BatchReader reader) {
        List<Object[]> rows = new ArrayList<>();
        for (Batch b = reader.next(); b != null; b = reader.next()) {
            for (int r = 0; r < b.rowCount(); r++) {
                Object[] row = new Object[b.schema().size()];
                for (int c = 0; c < row.length; c++) {
                    row[c] = b.column(c).get(r);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    void theOptionsComeFromTheNamedArguments() {
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("hive_partitioning", true);
        named.put("hive_types_autocast", false);
        named.put("hive_types", Map.of("year", "integer"));
        named.put("timezone", "UTC");
        Options o = HivePartitions.options("read_parquet", named, Options.AUTO);
        assertEquals(new Options(true, false, Map.of("year", DataType.INTEGER)), o);
        assertEquals(Options.AUTO, HivePartitions.options("read_parquet", Map.of("timezone", "UTC"), Options.AUTO));
        assertEquals("hive_partitioning of read_csv takes true or false",
                message(() -> HivePartitions.options("read_csv", Map.of("hive_partitioning", 1), Options.AUTO)));
        assertEquals("hive_types of read_csv takes the types of the partitions as text, such as"
                + " {'year': 'INTEGER'}", message(() -> HivePartitions.options("read_csv",
                        Map.of("hive_types", "year INTEGER"), Options.AUTO)));
        assertEquals("A partition cannot be a INTEGER[]",
                message(() -> HivePartitions.options("read_csv", Map.of("hive_types", Map.of("y", "INTEGER[]")),
                        Options.AUTO)));
        assertEquals(Map.of("year", DataType.INTEGER, "price", DataType.decimal(10, 2)),
                HivePartitions.types(" year INTEGER , price DECIMAL(10, 2)"));
        assertEquals("Expected a name and a type, such as year INTEGER, not 'year'",
                message(() -> HivePartitions.types("year")));
    }
}
