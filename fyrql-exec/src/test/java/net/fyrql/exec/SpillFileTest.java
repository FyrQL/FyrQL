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

package net.fyrql.exec;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpillFileTest {

    private static final Schema SCHEMA = Schema.of(new Schema.Field("k", DataType.INTEGER),
            new Schema.Field("s", DataType.VARCHAR));

    @TempDir
    Path temp;

    private static Batch batch(int from, int n) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            rows.add(new Object[] {i, i % 3 == 0 ? null : "row " + i});
        }
        return Batch.ofRows(SCHEMA, rows);
    }

    @Test
    void theDirectoryComesWithTheFirstFileAndGoesWithTheEngine() throws IOException {
        Path parent = temp.resolve("not/yet");
        SpillDirectory dir = new SpillDirectory(parent);
        assertNull(dir.path());
        assertTrue(!Files.exists(parent), "nothing is created before an operator spills");
        QueryContext context = new QueryContext();
        try (SpillFile file = dir.create("Sort#1", "run", SCHEMA, context)) {
            Path path = dir.path();
            assertTrue(Files.isDirectory(path));
            assertTrue(path.getFileName().toString().startsWith(SpillDirectory.PREFIX), path.toString());
            assertEquals(path, file.path().getParent());
            assertTrue(file.path().getFileName().toString().endsWith("-Sort1-run.spill"), file.path().toString());
            file.append(batch(0, 10));
            assertTrue(Files.exists(file.path()));
        }
        try (Stream<Path> files = Files.list(dir.path())) {
            assertEquals(0, files.count(), "closing a file deletes it");
        }
        SpillFile left = dir.create("Aggregate#2", "partition-3", SCHEMA, context);
        left.append(batch(0, 5));
        left.finish();
        Path path = dir.path();
        dir.close();
        assertTrue(!Files.exists(path), "closing the directory deletes it with what is left in it");
        assertNull(dir.path());
        QueryException e = assertThrows(QueryException.class, () -> dir.create("Sort#1", "run", SCHEMA,
                context));
        assertTrue(e.getMessage().startsWith("Spilling to "), e.getMessage());
        left.close();
    }

    @Test
    void batchesComeBackInTheirOrderAndAreCounted() {
        SpillDirectory dir = new SpillDirectory(temp);
        QueryContext context = new QueryContext();
        QueryContext other = new QueryContext();
        try (SpillFile file = dir.create("Sort#1", "run", SCHEMA, context);
                SpillFile empty = dir.create("Sort#1", "run", SCHEMA, context);
                SpillFile second = dir.create("Sort#3", "run", SCHEMA, other)) {
            List<Batch> written = List.of(batch(0, 7), batch(7, 0), batch(7, 100), batch(107, 1));
            for (Batch b : written) {
                file.append(b);
            }
            assertEquals(108, file.rows());
            assertEquals(3, file.batches(), "an empty batch is skipped");
            assertEquals(SpillStatistics.NONE, context.spill(), "a file counts once it is finished");
            file.finish();
            long bytes = file.bytes();
            assertTrue(bytes > 0);
            assertEquals(new SpillStatistics(1, bytes, 0), context.spill());
            assertThrows(IllegalStateException.class, () -> file.append(batch(0, 1)));
            for (int pass = 0; pass < 2; pass++) {
                SpillFile.SpillReader reader = file.reader();
                List<Object[]> rows = new ArrayList<>();
                for (Batch b = reader.next(); b != null; b = reader.next()) {
                    rows.addAll(b.rows());
                }
                assertEquals(108, rows.size());
                for (int i = 0; i < rows.size(); i++) {
                    assertArrayEquals(new Object[] {i, i % 3 == 0 ? null : "row " + i}, rows.get(i));
                }
                reader.close();
                assertNull(reader.next(), "a closed reader has nothing more");
            }
            assertEquals(new SpillStatistics(1, bytes, 2 * bytes), context.spill());

            // A file without rows is no part and reads nothing.
            empty.finish();
            assertNull(empty.reader().next());
            assertEquals(new SpillStatistics(1, bytes, 2 * bytes), context.spill());

            second.append(batch(0, 3));
            SpillFile.SpillReader open = second.reader();
            assertEquals(3, open.next().rowCount());
            assertEquals(1, other.spill().parts());
            assertEquals(new SpillStatistics(2, bytes + second.bytes(), 2 * bytes + second.bytes()),
                    dir.statistics(), "the directory counts the files of all queries");
        }
        // Closing a file closes its open readers before it deletes the file.
        try (Stream<Path> files = Files.list(dir.path())) {
            assertEquals(0, files.count());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        dir.close();
    }

    @Test
    void beyondTheMostOpenFilesAFinishedFileIsOpenedAgainToBeRead() {
        try (SpillDirectory dir = new SpillDirectory(temp)) {
            QueryContext context = new QueryContext();
            List<SpillFile> files = new ArrayList<>();
            try {
                for (int i = 0; i < SpillDirectory.MAX_OPEN_FILES + 20; i++) {
                    SpillFile file = dir.create("Sort#1", "run", SCHEMA, context);
                    files.add(file);
                    file.append(batch(i, 2));
                    file.finish();
                    assertTrue(dir.openFiles() <= SpillDirectory.MAX_OPEN_FILES, "open files: " + dir.openFiles());
                }
                for (int i = 0; i < files.size(); i++) {
                    SpillFile.SpillReader reader = files.get(i).reader();
                    assertArrayEquals(new Object[] {i + 1, (i + 1) % 3 == 0 ? null : "row " + (i + 1)},
                            reader.next().row(1));
                    assertNull(reader.next());
                }
            } finally {
                for (SpillFile f : files) {
                    f.close();
                }
            }
            assertEquals(0, dir.openFiles(), "closing the files closes them all");
        }
    }

    @Test
    void aCanceledQueryStopsWritingAndReading() {
        try (SpillDirectory dir = new SpillDirectory(temp)) {
            QueryContext context = new QueryContext();
            try (SpillFile file = dir.create("Sort#1", "run", SCHEMA, context)) {
                file.append(batch(0, 10));
                SpillFile.SpillReader reader = file.reader();
                context.cancel();
                assertThrows(QueryCancelledException.class, () -> reader.next());
                try (SpillFile next = dir.create("Sort#1", "run", SCHEMA, context)) {
                    assertThrows(QueryCancelledException.class, () -> next.append(batch(0, 1)));
                }
            }
        }
    }

    @Test
    void aParentThatIsAFileFailsWithTheQuery() throws IOException {
        Path file = Files.writeString(temp.resolve("plain"), "x");
        SpillDirectory dir = new SpillDirectory(file);
        QueryException e = assertThrows(QueryException.class, () -> dir.create("Aggregate#4", "partition-0",
                SCHEMA, new QueryContext()));
        assertTrue(e.getMessage().startsWith("Spilling to " + file + " failed: "), e.getMessage());
        assertTrue(e.getMessage().endsWith(" in Aggregate#4"), e.getMessage());
        assertTrue(e.getCause() instanceof IOException);
        dir.close();
    }
}
