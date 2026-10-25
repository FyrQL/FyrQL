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

package net.fyrql.test.connector;

import net.fyrql.FyrQL;
import net.fyrql.connector.FileAccess;
import net.fyrql.connector.FileAccessException;
import net.fyrql.connector.csv.CsvConnector;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.Position;
import net.fyrql.test.WrittenFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine checks the locations that statements in SQL name against its
 * file access: {@code FROM 'file'}, table functions, {@code COPY ... TO}
 * and {@code COPY ... FROM}, also in {@code EXPLAIN}. The tables of a
 * registered source stay readable wherever their files are.
 */
class FileAccessTest {

    @TempDir
    static Path dir;

    private static Path data;
    private static Path secret;

    @BeforeAll
    static void files() throws IOException {
        data = Files.createDirectories(dir.resolve("data"));
        secret = Files.createDirectories(dir.resolve("secret"));
        Files.createDirectories(data.resolve("parts"));
        Files.createDirectories(data.resolve("out"));
        Files.writeString(data.resolve("a.csv"), "k,v\n1,x\n2,y\n");
        Files.writeString(secret.resolve("s.csv"), "k,v\n7,hidden\n");
        try (FyrQL engine = engine(FileAccess.READ_WRITE, List.of())) {
            engine.update("COPY (SELECT 1 AS k, 'p' AS v) TO '" + path(data.resolve("parts/p1.parquet")) + "'");
            engine.update("COPY (SELECT 1 AS k, 'x' AS v) TO '" + path(data.resolve("a.xlsx")) + "' (HEADER true)");
            engine.update("COPY (SELECT 7 AS k, 'hidden' AS v) TO '" + path(secret.resolve("s.xlsx"))
                    + "' (HEADER true)");
        }
    }

    private static FyrQL engine(FileAccess access, List<String> locations) {
        return FyrQL.builder()
                .register(MemoryConnector.builder("mem").build())
                .register(CsvConnector.builder("conf").file("secret", secret.resolve("s.csv")).build())
                .defaultSource("mem")
                .fileAccess(access)
                .allowedLocations(locations)
                .build();
    }

    private static String path(Path file) {
        return WrittenFiles.path(file);
    }

    private static SqlException denied(Executable statement, String message) {
        SqlException e = assertThrows(SqlException.class, statement);
        assertInstanceOf(FileAccessException.class, e.getCause(), e::toString);
        assertTrue(e.reason().contains(message), e.reason());
        return e;
    }

    @Test
    void anEngineAllowsEverythingByDefault() {
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("mem").build()).build()) {
            assertEquals(FileAccess.READ_WRITE, engine.fileAccess());
            assertEquals(List.of(), engine.allowedLocations());
            assertEquals(1L, engine.rows("SELECT count(*) FROM '" + path(secret.resolve("s.csv")) + "'").get(0)[0]);
        }
    }

    @Test
    void noneAllowsNoLocationFromSql() {
        try (FyrQL engine = engine(FileAccess.NONE, List.of())) {
            String none = "the engine allows no access to files from SQL (fileAccess none)";
            SqlException e = denied(() -> engine.rows("SELECT * FROM '" + path(data.resolve("a.csv")) + "'"), none);
            assertEquals(new Position(1, 15), e.position());
            denied(() -> engine.rows("SELECT * FROM read_csv('" + path(data.resolve("a.csv")) + "')"), none);
            denied(() -> engine.rows("SELECT * FROM read_parquet('" + path(data.resolve("parts")) + "')"), none);
            denied(() -> engine.rows("SELECT * FROM read_xlsx('" + path(data.resolve("a.xlsx")) + "')"), none);
            denied(() -> engine.rows("SELECT * FROM '" + path(data.resolve("a.xlsx")) + "'"), none);
            denied(() -> engine.update("COPY (SELECT 1 AS k) TO '" + path(data.resolve("out.csv")) + "'"),
                    "Writing " + path(data.resolve("out.csv")) + " is not allowed: " + none);
            denied(() -> engine.explain("COPY (SELECT 1 AS k) TO '" + path(data.resolve("out.csv")) + "'"), none);
            engine.update("CREATE TABLE t (k INTEGER, v VARCHAR)");
            denied(() -> engine.update("COPY t FROM '" + path(data.resolve("a.csv")) + "'"), none);
            assertFalse(Files.exists(data.resolve("out.csv")));
            assertEquals(List.of(7L), engine.rows("SELECT k FROM conf.secret").stream().map(r -> r[0]).toList());
        }
    }

    @Test
    void readAllowsReadingUnderTheLocations() {
        try (FyrQL engine = engine(FileAccess.READ, List.of(path(data)))) {
            assertEquals(FileAccess.READ, engine.fileAccess());
            assertEquals(1, engine.allowedLocations().size());
            assertEquals(2, engine.rows("SELECT * FROM '" + path(data.resolve("a.csv")) + "'").size());
            assertEquals(2, engine.rows("SELECT * FROM read_csv('" + path(data.resolve("a.csv")) + "')").size());
            assertEquals(1, engine.rows("SELECT * FROM read_parquet('" + path(data.resolve("parts"))
                    + "/*.parquet')").size());
            engine.update("CREATE TABLE t (k INTEGER, v VARCHAR)");
            assertEquals(2L, engine.update("COPY t FROM '" + path(data.resolve("a.csv")) + "'"));
            String outside = "is not under the allowed locations";
            denied(() -> engine.rows("SELECT * FROM '" + path(secret.resolve("s.csv")) + "'"), outside);
            denied(() -> engine.rows("SELECT * FROM '" + path(data) + "/../secret/s.csv'"), outside);
            assertEquals(1, engine.rows("SELECT * FROM read_xlsx('" + path(data.resolve("a.xlsx")) + "')").size());
            assertEquals(1, engine.rows("SELECT * FROM '" + path(data.resolve("a.xlsx")) + "'").size());
            denied(() -> engine.rows("SELECT * FROM read_xlsx('" + path(secret.resolve("s.xlsx")) + "')"), outside);
            denied(() -> engine.rows("SELECT * FROM '" + path(data) + "/../secret/s.xlsx'"), outside);
            denied(() -> engine.update("COPY (SELECT 1 AS k) TO '" + path(data.resolve("out.xlsx")) + "'"),
                    "the engine allows only reading files from SQL (fileAccess read)");
            SqlException e = denied(() -> engine.update("COPY t FROM '" + path(secret.resolve("s.csv")) + "'"),
                    outside);
            assertEquals(1, e.position().line());
            denied(() -> engine.update("COPY (SELECT 1 AS k) TO '" + path(data.resolve("out.csv")) + "'"),
                    "the engine allows only reading files from SQL (fileAccess read)");
            assertEquals(List.of(7L), engine.rows("SELECT k FROM conf.secret").stream().map(r -> r[0]).toList());
        }
    }

    @Test
    void readWriteAllowsWritingUnderTheLocations() {
        try (FyrQL engine = engine(FileAccess.READ_WRITE, List.of(path(data)))) {
            assertEquals(1L, engine.update("COPY (SELECT 1 AS k) TO '" + path(data.resolve("out/w.csv")) + "'"));
            assertTrue(Files.exists(data.resolve("out/w.csv")));
            denied(() -> engine.update("COPY (SELECT 1 AS k) TO '" + path(secret.resolve("w.csv")) + "'"),
                    "Writing " + path(secret.resolve("w.csv")) + " is not allowed: it is not under the allowed"
                            + " locations");
            denied(() -> engine.update("COPY (SELECT 1 AS k) TO '" + path(data) + "/../w.csv'"),
                    "is not under the allowed locations");
            assertFalse(Files.exists(secret.resolve("w.csv")));
            assertFalse(Files.exists(dir.resolve("w.csv")));
        }
    }

    @Test
    void aFileUriIsReadAsTheFileThatIsChecked() {
        try (FyrQL engine = engine(FileAccess.READ, List.of(path(data)))) {
            String allowed = data.resolve("a.csv").toUri().toString();
            assertEquals(2, engine.rows("SELECT * FROM read_csv('" + allowed + "')").size());
            assertEquals(1, engine.rows("SELECT * FROM read_parquet('" + data.resolve("parts").toUri() + "')")
                    .size());
            denied(() -> engine.rows("SELECT * FROM read_csv('" + secret.resolve("s.csv").toUri() + "')"),
                    "is not under the allowed locations");
            if (!System.getProperty("os.name").startsWith("Windows")) {
                // Without "//", a path that climbs above the root stays at the root, for the check and the reader.
                String climbing = "file:/.." + path(data.resolve("a.csv"));
                assertEquals(2, engine.rows("SELECT * FROM read_csv('" + climbing + "')").size());
                denied(() -> engine.rows("SELECT * FROM read_csv('file:/.." + path(secret.resolve("s.csv")) + "')"),
                        "is not under the allowed locations");
            }
        }
    }

    @Test
    void aLinkInAnAllowedDirectoryDoesNotLeadAReadElsewhere() throws IOException {
        Path linked = Files.createDirectories(dir.resolve("linked"));
        Files.copy(data.resolve("parts/p1.parquet"), linked.resolve("p1.parquet"));
        Path outside = Files.copy(data.resolve("parts/p1.parquet"), secret.resolve("p2.parquet"));
        try {
            Files.createSymbolicLink(linked.resolve("p2.parquet"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links cannot be created here: " + e);
        }
        try (FyrQL engine = engine(FileAccess.READ, List.of(path(linked)))) {
            String reading = "Reading " + linked.resolve("p2.parquet").toAbsolutePath() + " is not allowed";
            denied(() -> engine.rows("SELECT * FROM read_parquet('" + path(linked) + "')"), reading);
            denied(() -> engine.rows("SELECT * FROM read_parquet('" + path(linked) + "/*.parquet')"), reading);
            assertEquals(1, engine.rows("SELECT * FROM read_parquet('" + path(linked) + "/p1.parquet')").size());
        }
    }

    @Test
    void allowedLocationsNeedAccess() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> engine(FileAccess.NONE, List.of(path(data))));
        assertEquals("allowedLocations needs fileAccess read or readWrite, not none", e.getMessage());
    }
}
