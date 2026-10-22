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
package net.fyrql.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCountersTest {

    @Test
    void outsideOfAScanNothingCounts() {
        assertSame(SourceCounters.NONE, SourceCounters.current());
        SourceCounters.NONE.request(5);
        SourceCounters.NONE.bytes(7);
        SourceCounters.NONE.rowGroups(1, 2);
        SourceCounters.NONE.files(3, 4);
        SourceCounters.NONE.splits(9);
        SourceCounters.NONE.statement("SELECT 1");
        assertEquals(SourceStatistics.NONE, SourceCounters.NONE.snapshot());
        InputStream in = new ByteArrayInputStream(new byte[3]);
        assertSame(in, SourceCounters.NONE.counted(in));
    }

    @Test
    void scopesNestAndRestoreTheCountersBefore() {
        SourceCounters outer = new SourceCounters();
        SourceCounters inner = new SourceCounters();
        try (SourceCounters.Scope a = SourceCounters.enter(outer)) {
            assertSame(outer, SourceCounters.current());
            try (SourceCounters.Scope b = SourceCounters.enter(inner)) {
                assertSame(inner, SourceCounters.current());
                SourceCounters.current().bytes(10);
            }
            assertSame(outer, SourceCounters.current());
            SourceCounters.current().bytes(1);
        }
        assertSame(SourceCounters.NONE, SourceCounters.current());
        assertEquals(10, inner.snapshot().bytes());
        assertEquals(1, outer.snapshot().bytes());
    }

    @Test
    void anotherThreadHasCountersOfItsOwn() throws InterruptedException {
        AtomicReference<SourceCounters> seen = new AtomicReference<>();
        try (SourceCounters.Scope s = SourceCounters.enter(new SourceCounters())) {
            Thread t = new Thread(() -> seen.set(SourceCounters.current()));
            t.start();
            t.join();
        }
        assertSame(SourceCounters.NONE, seen.get());
    }

    @Test
    void everythingCountsInTheSnapshot() throws IOException {
        SourceCounters c = new SourceCounters();
        c.splits(3);
        c.request(100);
        c.request(50);
        c.bytes(1000);
        c.rowGroups(2, 5);
        c.rowGroups(1, 0);
        c.files(4, 6);
        c.statement("SELECT a FROM t");
        c.statement("SELECT b FROM t");
        try (InputStream in = c.counted(new ByteArrayInputStream(new byte[300]))) {
            assertTrue(in.read() >= 0);
            assertEquals(99, in.read(new byte[99]));
            assertEquals(50, in.skip(50));
            in.readAllBytes();
        }
        assertEquals(new SourceStatistics(3, 2, 150, 1300, 3, 5, 4, 6, List.of("SELECT a FROM t", "SELECT b FROM t")),
                c.snapshot());
    }

    @Test
    void aRequestCountsForTheScanOfTheThread() {
        SourceCounters c = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(c)) {
            SourceRequest r = SourceRequest.start();
            r.done("lake", "GET", "s3://lake/a.parquet", 200, 42);
        }
        SourceRequest.start().done("lake", "GET", "s3://lake/b.parquet", 200, 42);
        SourceStatistics s = c.snapshot();
        assertEquals(1, s.requests());
        assertTrue(s.requestNanos() >= 0);
        assertEquals(0, s.bytes());
    }

    @Test
    void localFilesCountTheBytesTheyRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.write(file, new byte[64]);
        SourceCounters c = new SourceCounters();
        try (SourceCounters.Scope s = SourceCounters.enter(c)) {
            FileObject object = new LocalFileStore(dir).object("a.txt");
            try (InputStream in = object.open()) {
                assertEquals(64, in.readAllBytes().length);
            }
            assertEquals(16, object.read(8, ByteBuffer.allocate(16)));
        }
        assertEquals(80, c.snapshot().bytes());
        assertEquals(0, c.snapshot().requests());
    }
}
