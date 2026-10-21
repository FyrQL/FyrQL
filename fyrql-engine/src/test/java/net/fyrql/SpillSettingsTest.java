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

package net.fyrql;

import net.fyrql.exec.SpillStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether and where operators spill to disk when the memory budget is exhausted. */
class SpillSettingsTest {

    @TempDir
    Path temp;

    @Test
    void theBuilderSetsWhetherAndWhereToSpill() {
        try (FyrQL engine = FyrQL.builder().build()) {
            assertEquals(Optional.of(Path.of(System.getProperty("java.io.tmpdir"))), engine.spillDirectory(),
                    "spilling is on by default, to the temporary directory of the JVM");
        }
        Path spill = temp.resolve("not/yet");
        try (FyrQL engine = FyrQL.builder().memoryBudget(1 << 20).spillDirectory(spill).build()) {
            assertEquals(Optional.of(spill), engine.spillDirectory());
            assertFalse(Files.exists(spill), "the directory comes when an operator spills first");
        }
        try (FyrQL engine = FyrQL.builder().spillDirectory(spill).spill(false).build()) {
            assertEquals(Optional.empty(), engine.spillDirectory());
        }
    }

    @Test
    void aSpillDirectoryMustBeADirectory() throws IOException {
        Path file = Files.writeString(temp.resolve("plain"), "x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FyrQL.builder().spillDirectory(file));
        assertEquals("The spill directory " + file + " is no directory that can be written", e.getMessage());
        assertThrows(NullPointerException.class, () -> FyrQL.builder().spillDirectory(null));
    }

    @Test
    void aQueryThatFitsSpillsNothing() {
        try (FyrQL engine = FyrQL.builder().memoryBudget(1 << 20).spillDirectory(temp).build()) {
            try (QueryResult result = engine.sql("SELECT k, count(*) FROM (VALUES (1), (2), (1)) t(k) GROUP BY k"
                    + " ORDER BY k")) {
                assertEquals(2, result.rows().size());
                assertEquals(SpillStatistics.NONE, result.spill());
            }
            assertEquals(SpillStatistics.NONE, engine.spillStatistics());
        }
        try (FyrQL engine = FyrQL.builder().spill(false).build()) {
            assertEquals(SpillStatistics.NONE, engine.spillStatistics());
        }
        try (var files = Files.list(temp)) {
            assertTrue(files.findAny().isEmpty(), "no directory for an engine that spilled nothing");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
