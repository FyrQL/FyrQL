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
import net.fyrql.connector.ColumnPath;
import net.fyrql.connector.FileObject;
import net.fyrql.connector.FileStores;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Sink;
import net.fyrql.connector.Split;
import net.fyrql.core.expr.Expression;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads Parquet files of a store other than the local one, which records
 * the ranges read: the footer takes one request, a filter that excludes
 * every row group by the statistics reads no data, and a scan of one
 * column of one row group reads a small part of the file only; the rows
 * are those of the same file read locally.
 */
class StoreFileTest {

    private static final String SCHEMA = """
            message t {
              required int64 id;
              optional binary name (STRING);
              optional double amount;
            }
            """;

    private static final int ROWS = 20_000;
    private static final int GROUP = 2000;
    private static final int READ_AHEAD = 16 * 1024;
    private static final String LOCATION = "mem://b/data/t.parquet";
    private static final Schema NONE = new Schema(List.of());

    @TempDir
    static Path dir;

    private static Path local;
    private static byte[] bytes;

    @BeforeAll
    static void write() throws IOException {
        SplittableRandom random = new SplittableRandom(20261020);
        local = ParquetTestFiles.write(dir.resolve("t.parquet"), SCHEMA, w -> w.withRowGroupRowCountLimit(GROUP)
                .withPageRowCountLimit(200), w -> {
                    for (int r = 0; r < ROWS; r++) {
                        long id = r;
                        String name = "name-" + Long.toHexString(random.nextLong());
                        double amount = random.nextDouble();
                        w.row(g -> g.append("id", id).append("name", name).append("amount", amount));
                    }
                });
        bytes = Files.readAllBytes(local);
    }

    private static MemoryStore store() {
        MemoryStore store = new MemoryStore();
        store.put(LOCATION, bytes);
        store.put("mem://b/data/u.parquet", bytes);
        store.put("mem://b/data/notes.txt", new byte[] {1});
        store.put("mem://b/data/sub/v.parquet", bytes);
        return store;
    }

    private static ParquetConnector connector(MemoryStore store) {
        ParquetConnector c = ParquetConnector.builder("pq").readAhead(READ_AHEAD).recordReads(true).build();
        c.fileStores(new FileStores(Map.of("mem", store), Path.of("")));
        return c;
    }

    private static List<Object[]> scan(ParquetConnector c, TableRef table, List<Integer> columns,
            List<Expression> filters) {
        List<ColumnPath> paths = columns.stream().map(i -> new ColumnPath(i, List.of())).toList();
        ScanPlan plan = c.plan(table, new ScanRequest(paths, filters, OptionalLong.empty(), List.of(),
                Optional.empty()));
        List<Object[]> rows = new ArrayList<>();
        for (Split split : c.splits(plan)) {
            for (Batch b : Batches.readAll(c.open(split))) {
                rows.addAll(b.rows());
            }
        }
        return rows;
    }

    private static long bytesRead(MemoryStore store) {
        return store.requests().stream().mapToLong(MemoryStore.Request::length).sum();
    }

    @Test
    void theFooterTakesOneRequestForTheTailOfTheFile() {
        MemoryStore store = store();
        ParquetConnector c = connector(store);
        TableRef table = c.bind("read_parquet", List.of(LOCATION), Map.of());
        assertEquals(List.of(new MemoryStore.Request(LOCATION, bytes.length - READ_AHEAD, READ_AHEAD)),
                store.requests());
        assertEquals(ROWS, c.catalog().rowCount(table).getAsLong());
        // The same file again: the footer is known.
        store.clearRequests();
        assertEquals(table, c.bind("read_parquet", List.of(LOCATION), Map.of()));
        assertEquals(List.of(), store.requests());
    }

    @Test
    void aFilterThatExcludesEveryRowGroupReadsNoData() {
        MemoryStore store = store();
        ParquetConnector c = connector(store);
        TableRef table = c.bind("read_parquet", List.of(LOCATION), Map.of());
        store.clearRequests();
        Expression filter = new Expression.Comparison(Expression.ComparisonOp.LT,
                new Expression.Column(0, DataType.BIGINT), new Expression.Literal(DataType.BIGINT, 0L));
        assertEquals(List.of(), scan(c, table, List.of(0, 1, 2), List.of(filter)));
        assertEquals(List.of(), store.requests());
        assertEquals(List.of(), c.reads());
    }

    @Test
    void aScanOfOneColumnOfOneRowGroupReadsLittleOfTheFile() {
        MemoryStore store = store();
        ParquetConnector c = connector(store);
        TableRef table = c.bind("read_parquet", List.of(LOCATION), Map.of());
        store.clearRequests();
        Expression filter = new Expression.Comparison(Expression.ComparisonOp.EQ,
                new Expression.Column(0, DataType.BIGINT), new Expression.Literal(DataType.BIGINT, 9_123L));
        List<Object[]> rows = scan(c, table, List.of(0), List.of(filter));
        assertTrue(rows.stream().anyMatch(r -> r[0].equals(9_123L)));
        assertEquals(List.of(new ParquetConnector.Read(LOCATION, 4, List.of("id"), rows.size())), c.reads());
        long read = bytesRead(store);
        assertTrue(read < bytes.length / 4, read + " bytes read of " + bytes.length);
    }

    @Test
    void theRowsAreThoseOfTheLocalFile() {
        ParquetConnector c = connector(store());
        TableRef remote = c.bind("read_parquet", List.of(LOCATION), Map.of());
        ParquetConnector l = ParquetConnector.builder("pq").build();
        TableRef table = l.bind("read_parquet", List.of(local.toString()), Map.of());
        assertEquals(l.catalog().schema(table), c.catalog().schema(remote));
        assertEquals(Arrays.deepToString(scan(l, table, List.of(0, 1, 2), List.of()).toArray()),
                Arrays.deepToString(scan(c, remote, List.of(0, 1, 2), List.of()).toArray()));
        assertEquals(Arrays.deepToString(scan(l, table, List.of(2, 0), List.of()).toArray()),
                Arrays.deepToString(scan(c, remote, List.of(2, 0), List.of()).toArray()));
    }

    @Test
    void aLocationInAStoreIsAFileAPrefixOrAPattern() {
        MemoryStore store = store();
        ParquetConnector c = connector(store);
        assertEquals(2 * ROWS, c.catalog().rowCount(c.bind("read_parquet", List.of("mem://b/data/"), Map.of()))
                .getAsLong());
        assertEquals(2 * ROWS, c.catalog().rowCount(c.bind("read_parquet", List.of("mem://b/data"), Map.of()))
                .getAsLong());
        assertEquals(ROWS, c.catalog().rowCount(c.bind("read_parquet", List.of("mem://b/data/u*"), Map.of()))
                .getAsLong());
        assertEquals("No files found that match the pattern \"mem://b/none/*.parquet\"",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet",
                        List.of("mem://b/none/*.parquet"), Map.of())).getMessage());
        assertEquals("No files found that match the pattern \"mem://b/none.parquet\"",
                assertThrows(IllegalArgumentException.class, () -> c.fileTable("parquet", "mem://b/none.parquet",
                        NONE, Map.of())).getMessage());
        assertEquals("No source handles s3:// locations; register a source of the type s3",
                assertThrows(IllegalArgumentException.class, () -> c.bind("read_parquet", List.of("s3://b/x"),
                        Map.of())).getMessage());
        TableRef file = c.fileTable("parquet", LOCATION, NONE, Map.of());
        assertEquals(ROWS, scan(c, file, List.of(0), List.of()).size());
    }

    @Test
    void aTableInAStoreIsLookedUpWhenItIsFirstUsed() {
        MemoryStore store = store();
        ParquetConnector c = ParquetConnector.builder("pq").path("t", LOCATION).path("w", "mem://b/none.parquet")
                .build();
        assertEquals(List.of("t", "w"), c.catalog().tables("main"));
        assertEquals("The table t of the Parquet source pq: No source handles mem:// locations; register a source"
                + " of the type mem", assertThrows(IllegalArgumentException.class,
                        () -> c.catalog().schema(TableRef.of("pq", "t"))).getMessage());
        c.fileStores(new FileStores(Map.of("mem", store), Path.of("")));
        assertEquals(3, c.catalog().schema(TableRef.of("pq", "t")).size());
        assertEquals(ROWS, scan(c, TableRef.of("pq", "t"), List.of(0), List.of()).size());
        assertEquals("The table w of the Parquet source pq: No files found that match the pattern"
                + " \"mem://b/none.parquet\"", assertThrows(IllegalArgumentException.class,
                        () -> c.catalog().schema(TableRef.of("pq", "w"))).getMessage());
    }

    @Test
    void aFileIsWrittenToTheStoreWithTheCommitOnly() {
        MemoryStore store = new MemoryStore();
        ParquetConnector c = connector(store);
        Schema schema = Schema.of(new Schema.Field("a", DataType.INTEGER), new Schema.Field("b", DataType.VARCHAR));
        List<Object[]> values = List.of(new Object[] {1, "x"}, new Object[] {2, null});
        try (Sink sink = c.openFile("parquet", "mem://b/out.parquet", schema, Map.of())) {
            sink.write(Batch.ofRows(schema, values));
        }
        assertEquals(List.of(), store.locations());
        try (Sink sink = c.openFile("parquet", "mem://b/out.parquet", schema, Map.of())) {
            sink.write(Batch.ofRows(schema, values));
            assertEquals(2, sink.commit());
        }
        TableRef table = c.fileTable("parquet", "mem://b/out.parquet", schema, Map.of());
        assertEquals("[[1, x], [2, null]]", Arrays.deepToString(scan(c, table, List.of(0, 1), List.of()).toArray()));
    }

    @Test
    void theStreamReadsWhatTheFileHolds() throws IOException {
        MemoryStore store = store();
        FileObject object = store.object(LOCATION);
        SplittableRandom random = new SplittableRandom(7);
        for (int readAhead : new int[] {8, 100, 4096, 1 << 20}) {
            try (SeekableInputStream in = new StoreFile(object, readAhead).newStream()) {
                for (int k = 0; k < 300; k++) {
                    int length = random.nextInt(4) == 0 ? random.nextInt(StoreFile.DIRECT, 3 * StoreFile.DIRECT)
                            : random.nextInt(0, 300);
                    long position = random.nextLong(0, bytes.length - Math.min(length, bytes.length) + 1);
                    length = (int) Math.min(length, bytes.length - position);
                    in.seek(position);
                    byte[] expected = Arrays.copyOfRange(bytes, (int) position, (int) position + length);
                    switch (k % 4) {
                        case 0 -> {
                            byte[] actual = new byte[length];
                            in.readFully(actual);
                            assertArrayEquals(expected, actual);
                        }
                        case 1 -> {
                            ByteBuffer actual = ByteBuffer.allocateDirect(length);
                            in.readFully(actual);
                            actual.flip();
                            byte[] copy = new byte[length];
                            actual.get(copy);
                            assertArrayEquals(expected, copy);
                        }
                        case 2 -> {
                            for (byte b : expected) {
                                assertEquals(b & 0xff, in.read());
                            }
                        }
                        default -> {
                            byte[] actual = new byte[length + 2];
                            int done = 0;
                            while (done < length) {
                                int n = in.read(actual, 1 + done, length - done);
                                assertTrue(n > 0, "read " + n);
                                done += n;
                            }
                            assertArrayEquals(expected, Arrays.copyOfRange(actual, 1, 1 + length));
                        }
                    }
                    assertEquals(position + length, in.getPos());
                }
                in.seek(bytes.length);
                assertEquals(-1, in.read());
                assertEquals(-1, in.read(new byte[3], 0, 3));
                in.seek(bytes.length - 2);
                assertThrows(EOFException.class, () -> in.readFully(new byte[3]));
            }
        }
        assertNull(store.get("mem://b/none"));
    }
}
