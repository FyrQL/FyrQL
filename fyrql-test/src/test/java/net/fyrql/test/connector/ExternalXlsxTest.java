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
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.sql.SqlException;
import net.fyrql.test.DuckDbReference;
import net.fyrql.test.ResultComparison;
import net.fyrql.test.WrittenFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every workbook of the directory the system property
 * {@code fyrql.test.xlsx.dir} names, such as the spreadsheets of the test
 * data of Apache POI, read with {@code read_xlsx} by FyrQL and by DuckDB with
 * its extension {@code excel}, see {@link WrittenFiles#xlsx}. The report
 * counts the workbooks both read alike, those they read otherwise, and those
 * only one of them reads; the test fails only if FyrQL fails without a
 * message, or with an error that is no error of the workbook, such as a
 * {@code NullPointerException}.
 */
@EnabledIfSystemProperty(named = "fyrql.test.xlsx.dir", matches = ".+")
class ExternalXlsxTest {

    @Test
    void workbooksOfADirectory() throws IOException {
        Path dir = Path.of(System.getProperty("fyrql.test.xlsx.dir"));
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                    .matches(".*\\.xls[xm]")).sorted().toList();
        }
        int same = 0;
        int both = 0;
        int onlyFyrql = 0;
        int onlyDuckDb = 0;
        List<String> differences = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        List<String> slow = new ArrayList<>();
        DuckDbReference duck = new DuckDbReference();
        try (FyrQL engine = FyrQL.builder().register(MemoryConnector.builder("mem").build()).build()) {
            assumeTrue(WrittenFiles.loadExcel(duck), "DuckDB has no extension excel in its cache");
            for (Path file : files) {
                String name = dir.relativize(file).toString();
                String fyrqlError = null;
                long start = System.nanoTime();
                try {
                    engine.rows("SELECT * FROM read_xlsx('" + WrittenFiles.path(file) + "', ignore_errors = true)");
                } catch (SqlException e) {
                    fyrqlError = e.getMessage();
                    if (fyrqlError == null || fyrqlError.isBlank()) {
                        broken.add(name + ": an error without a message");
                    }
                } catch (RuntimeException | StackOverflowError e) {
                    broken.add(name + ": " + e);
                    continue;
                }
                long millis = (System.nanoTime() - start) / 1_000_000;
                if (millis > 5_000) {
                    slow.add(name + ": " + millis + " ms in FyrQL");
                }
                boolean[] stuck = {false};
                Boolean duckReads = duckReads(duck, "SELECT count(*) FROM read_xlsx('" + WrittenFiles.path(file)
                        + "', ignore_errors = true)", stuck);
                if (duckReads == null) {
                    slow.add(name + ": DuckDB takes more than " + DUCKDB_SECONDS + " s");
                    if (stuck[0]) {
                        duck = new DuckDbReference();
                        assumeTrue(WrittenFiles.loadExcel(duck), "DuckDB has no extension excel in its cache");
                    }
                    continue;
                }
                if (fyrqlError != null) {
                    if (duckReads) {
                        onlyDuckDb++;
                        differences.add(name + ": only DuckDB reads it; FyrQL: " + firstLine(fyrqlError));
                    } else {
                        both++;
                    }
                    continue;
                }
                if (!duckReads) {
                    onlyFyrql++;
                    continue;
                }
                try {
                    ResultComparison.Outcome outcome = WrittenFiles.xlsx(duck, engine, file, "").compare(true);
                    if (outcome.same()) {
                        same++;
                    } else {
                        differences.add(name + ":\n" + outcome.report().indent(2));
                    }
                } catch (RuntimeException e) {
                    differences.add(name + ": " + firstLine(String.valueOf(e.getMessage())));
                }
            }
        } finally {
            duck.close();
        }
        String report = "Workbooks of " + dir + ": " + files.size() + ", " + same + " read alike, "
                + differences.size() + " read otherwise or only by DuckDB (" + onlyDuckDb + "), " + onlyFyrql
                + " only by FyrQL, " + both + " by neither, " + slow.size() + " slow\n"
                + String.join("\n", differences) + (slow.isEmpty() ? "" : "\nslow:\n" + String.join("\n", slow))
                + (broken.isEmpty() ? "" : "\nbroken:\n" + String.join("\n", broken));
        System.out.println(report);
        assertTrue(broken.isEmpty(), report);
    }

    /** The time DuckDB gets for a workbook; some of POI's make it run on for minutes. */
    private static final int DUCKDB_SECONDS = 30;

    /**
     * Tells whether DuckDB reads a workbook, or null if it takes too long.
     * The query is then interrupted, as {@code Statement.cancel} waits for
     * the query to end; a query that goes on even so is left to itself on a
     * daemon thread, and {@code stuck} is set, so that the caller goes on
     * with another DuckDB.
     */
    private static Boolean duckReads(DuckDbReference duck, String sql, boolean[] stuck) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "duckdb-read");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Boolean> read = executor.submit(() -> {
                try (Statement statement = duck.connection().createStatement();
                        ResultSet rs = statement.executeQuery(sql)) {
                    return rs.next();
                } catch (SQLException e) {
                    return false;
                }
            });
            try {
                return read.get(DUCKDB_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                interrupt(duck.connection());
                try {
                    read.get(DUCKDB_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException stillRunning) {
                    stuck[0] = true;
                }
                return null;
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdown();
        }
    }

    /** Interrupts the query of a connection of DuckDB, whose method for it its driver does not make public. */
    private static void interrupt(Connection connection) {
        try {
            Method interrupt = connection.getClass().getDeclaredMethod("interrupt");
            interrupt.setAccessible(true);
            interrupt.invoke(connection);
        } catch (ReflectiveOperationException e) {
            // The query goes on; the caller waits for it a while and then leaves it.
        }
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
